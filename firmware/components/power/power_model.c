/**
 * @file power_model.c
 * @brief `power.py` in C. See `power_model.h`.
 *
 * The arithmetic here follows `power.py`'s **order** as well as its formulas.
 * That is not fussiness: `test_power_model.c` compares against doubles the real
 * Python produced, for equality rather than for closeness, and floating-point
 * addition is not associative. Summing the subsystems in a different order
 * would land a few ulp away and the vectors would fail for a reason that has
 * nothing to do with the model being wrong.
 */

#include "power_model.h"

#include <math.h>
#include <stddef.h>

/* The table, in `power.DRAWS` order, which is ::power_draw_id_t order. The
 * one-sentence provenance for each row is in `power.py` - see the note on
 * ::power_source_t for why it is not repeated here. Twelve of the fourteen are
 * ESTIMATE and none is MEASURED, which is the honest state of this model and is
 * asserted by both test packs so that it cannot drift quietly. */
const power_draw_t POWER_DRAWS[POWER_DRAW_COUNT] = {
    [POWER_DRAW_SENSOR_MEASURING] = {"sensor_measuring", 55.0, POWER_SPS30_RAIL_V,
                                     POWER_SOURCE_DATASHEET, POWER_SUBSYSTEM_SENSOR},
    [POWER_DRAW_SENSOR_FAN_STARTING] = {"sensor_fan_starting", 80.0, POWER_SPS30_RAIL_V,
                                        POWER_SOURCE_DATASHEET, POWER_SUBSYSTEM_SENSOR},
    [POWER_DRAW_SENSOR_IDLE] = {"sensor_idle", 3.6, POWER_SPS30_RAIL_V, POWER_SOURCE_ESTIMATE,
                                POWER_SUBSYSTEM_SENSOR},
    [POWER_DRAW_SENSOR_SLEEP] = {"sensor_sleep", 0.5, POWER_SPS30_RAIL_V, POWER_SOURCE_ESTIMATE,
                                 POWER_SUBSYSTEM_SENSOR},
    [POWER_DRAW_SENSOR_CLEANING] = {"sensor_cleaning", 80.0, POWER_SPS30_RAIL_V,
                                    POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_SENSOR},
    [POWER_DRAW_CPU_ACTIVE] = {"cpu_active", 68.0, POWER_ESP32_RAIL_V, POWER_SOURCE_ESTIMATE,
                               POWER_SUBSYSTEM_CONTROLLER},
    [POWER_DRAW_CPU_LIGHT_SLEEP] = {"cpu_light_sleep", 1.5, POWER_ESP32_RAIL_V,
                                    POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_CONTROLLER},
    [POWER_DRAW_BOARD_OVERHEAD] = {"board_overhead", 15.0, POWER_ESP32_RAIL_V,
                                   POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_CONTROLLER},
    [POWER_DRAW_WIFI_IDLE] = {"wifi_idle", 90.0, POWER_ESP32_RAIL_V, POWER_SOURCE_ESTIMATE,
                              POWER_SUBSYSTEM_RADIO},
    [POWER_DRAW_WIFI_STREAMING] = {"wifi_streaming", 120.0, POWER_ESP32_RAIL_V,
                                   POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_RADIO},
    [POWER_DRAW_BLE_ADVERTISING] = {"ble_advertising", 15.0, POWER_ESP32_RAIL_V,
                                    POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_RADIO},
    [POWER_DRAW_BLE_CONNECTED] = {"ble_connected", 30.0, POWER_ESP32_RAIL_V,
                                  POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_RADIO},
    [POWER_DRAW_FLASH_PAGE_WRITE] = {"flash_page_write", 20.0, POWER_ESP32_RAIL_V,
                                     POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_STORAGE},
    [POWER_DRAW_FLASH_SECTOR_ERASE] = {"flash_sector_erase", 25.0, POWER_ESP32_RAIL_V,
                                       POWER_SOURCE_ESTIMATE, POWER_SUBSYSTEM_STORAGE},
};

/**
 * The state the model actually runs on: bounds applied, unknowns resolved
 * toward cost.
 *
 * An enum value the caller invented is read as the costliest member of its
 * family rather than as its zero, which would be "off". This arrives from a
 * client over a socket by the same route a profile does, and reading "something
 * I do not recognise" as "nothing running" would lengthen the estimate.
 */
static power_state_t normalized(const power_state_t *state)
{
    power_state_t s = state != NULL ? *state : POWER_DEFAULT_STATE();
    acq_profile_normalize(&s.profile);
    if (s.wifi != POWER_WIFI_OFF && s.wifi != POWER_WIFI_IDLE && s.wifi != POWER_WIFI_STREAMING) {
        s.wifi = POWER_WIFI_STREAMING;
    }
    if (s.ble != POWER_BLE_OFF && s.ble != POWER_BLE_ADVERTISING &&
        s.ble != POWER_BLE_CONNECTED) {
        s.ble = POWER_BLE_CONNECTED;
    }
    if (s.cpu != POWER_CPU_ACTIVE && s.cpu != POWER_CPU_LIGHT_SLEEP) {
        s.cpu = POWER_CPU_ACTIVE;
    }
    return s;
}

static double draw_ma(power_draw_id_t id)
{
    return POWER_DRAWS[id].current_ma;
}

double power_model_recorded_readings_per_s(const acq_profile_t *profile)
{
    acq_profile_t p = profile != NULL ? *profile : ACQ_DEFAULT_PROFILE();
    acq_profile_normalize(&p);
    if (p.mode == ACQ_MODE_DUTY_CYCLE) {
        return 1.0 / p.cycle_s;
    }
    return 1.0 / p.interval_s;
}

/**
 * The sensor's average current over one whole cycle, written into @p by_draw.
 *
 * Continuous mode is the easy half: the fan and the laser never stop, so there
 * is nothing to average and the recording interval changes only the flash.
 *
 * Duty-cycle mode is where the conservative choices are. The warm-up window is
 * charged at the fan's *peak* current, because the warm-up is exactly when the
 * fan is accelerating; and the rest of the cycle is charged at idle unless the
 * caller has said the sensor can be put to sleep.
 */
static void sensor_draw_ma(const power_state_t *s, double *by_draw)
{
    if (!s->sensor_present) {
        return;
    }
    /* Amortised over the whole week between cleans, and present in every state:
     * the fan-clean account (`acq_clean.h`) survives restarts and is not
     * something a profile can switch off. */
    by_draw[POWER_DRAW_SENSOR_CLEANING] =
        draw_ma(POWER_DRAW_SENSOR_CLEANING) * POWER_CLEAN_SECONDS / POWER_CLEAN_INTERVAL_SECONDS;

    if (s->profile.mode != ACQ_MODE_DUTY_CYCLE) {
        by_draw[POWER_DRAW_SENSOR_MEASURING] = draw_ma(POWER_DRAW_SENSOR_MEASURING);
        return;
    }

    const double cycle_s = s->profile.cycle_s;
    const double warmup_s = s->profile.warmup_s;
    const double average_s = s->profile.average_s;
    /* `acq_profile_normalize` guarantees cycle_s >= warmup_s + average_s, so
     * this cannot go negative. Clamped anyway: a negative idle window would
     * shorten the mean and so lengthen the answer. */
    double off_s = cycle_s - warmup_s - average_s;
    if (!(off_s > 0.0)) {
        off_s = 0.0;
    }
    const power_draw_id_t between =
        s->sensor_sleeps_between_cycles ? POWER_DRAW_SENSOR_SLEEP : POWER_DRAW_SENSOR_IDLE;

    by_draw[POWER_DRAW_SENSOR_FAN_STARTING] =
        draw_ma(POWER_DRAW_SENSOR_FAN_STARTING) * warmup_s / cycle_s;
    by_draw[POWER_DRAW_SENSOR_MEASURING] =
        draw_ma(POWER_DRAW_SENSOR_MEASURING) * average_s / cycle_s;
    by_draw[between] = draw_ma(between) * off_s / cycle_s;
}

/** The processor and the board's own overhead. Radios are counted separately. */
static void controller_draw_ma(const power_state_t *s, double *by_draw)
{
    const power_draw_id_t cpu =
        s->cpu == POWER_CPU_LIGHT_SLEEP ? POWER_DRAW_CPU_LIGHT_SLEEP : POWER_DRAW_CPU_ACTIVE;
    by_draw[cpu] = draw_ma(cpu);
    if (s->board_overhead) {
        by_draw[POWER_DRAW_BOARD_OVERHEAD] = draw_ma(POWER_DRAW_BOARD_OVERHEAD);
    }
}

/** Both radios, summed rather than maxed: this firmware really runs both. */
static void radio_draw_ma(const power_state_t *s, double *by_draw)
{
    if (s->wifi == POWER_WIFI_IDLE) {
        by_draw[POWER_DRAW_WIFI_IDLE] = draw_ma(POWER_DRAW_WIFI_IDLE);
    } else if (s->wifi == POWER_WIFI_STREAMING) {
        by_draw[POWER_DRAW_WIFI_STREAMING] = draw_ma(POWER_DRAW_WIFI_STREAMING);
    }
    if (s->ble == POWER_BLE_ADVERTISING) {
        by_draw[POWER_DRAW_BLE_ADVERTISING] = draw_ma(POWER_DRAW_BLE_ADVERTISING);
    } else if (s->ble == POWER_BLE_CONNECTED) {
        by_draw[POWER_DRAW_BLE_CONNECTED] = draw_ma(POWER_DRAW_BLE_CONNECTED);
    }
}

/**
 * The flash's average adder for the write rate this state implies.
 *
 * **The ring is assumed to have wrapped**, so every page written is preceded by
 * its share of a sector erase. On a fresh board no erase is needed and this is
 * smaller, but a board that has been recording for two days is a wrapped one,
 * and assuming the cheaper case would lengthen the estimate.
 *
 * It is a small term either way, and that is a finding rather than an
 * approximation: at 1 Hz, writing to storage costs well under a milliamp.
 */
static void storage_draw_ma(const power_state_t *s, double *by_draw)
{
    double rate = s->storage_rate_stated ? s->storage_readings_per_s
                                         : power_model_recorded_readings_per_s(&s->profile);
    if (!isfinite(rate) || rate <= 0.0) {
        return;
    }
    const double pages_per_s = rate * POWER_BYTES_PER_READING / POWER_PAGE_BODY_BYTES;
    const double sectors_per_s = pages_per_s / POWER_PAGES_PER_SECTOR;
    by_draw[POWER_DRAW_FLASH_PAGE_WRITE] =
        pages_per_s * POWER_FLASH_PAGE_WRITE_SECONDS * draw_ma(POWER_DRAW_FLASH_PAGE_WRITE);
    by_draw[POWER_DRAW_FLASH_SECTOR_ERASE] =
        sectors_per_s * POWER_FLASH_SECTOR_ERASE_SECONDS * draw_ma(POWER_DRAW_FLASH_SECTOR_ERASE);
}

void power_model_estimate(const power_state_t *state, power_estimate_t *out)
{
    if (out == NULL) {
        return;
    }
    const power_state_t s = normalized(state);

    /* Currents at their own rails first, then one multiply each. Every row is
     * present, including the ones this state does not use, so the breakdown is
     * a complete account rather than only what happened to be running. */
    double by_draw_ma[POWER_DRAW_COUNT] = {0};
    sensor_draw_ma(&s, by_draw_ma);
    controller_draw_ma(&s, by_draw_ma);
    radio_draw_ma(&s, by_draw_ma);
    storage_draw_ma(&s, by_draw_ma);

    for (int i = 0; i < POWER_SUBSYSTEM_COUNT; i++) {
        out->by_subsystem_mw[i] = 0.0;
    }
    /* In table order, which is `power.DRAWS` order, which is the order the
     * Python accumulates in. See the file header for why that matters. */
    for (int i = 0; i < POWER_DRAW_COUNT; i++) {
        out->by_draw_mw[i] = by_draw_ma[i] * POWER_DRAWS[i].rail_v;
        out->by_subsystem_mw[POWER_DRAWS[i].subsystem] += out->by_draw_mw[i];
    }
    out->total_mw = 0.0;
    for (int i = 0; i < POWER_SUBSYSTEM_COUNT; i++) {
        out->total_mw += out->by_subsystem_mw[i];
    }
}

void power_model_runtime(const power_estimate_t *estimate, const power_battery_t *battery,
                         double derating, power_runtime_t *out)
{
    if (out == NULL) {
        return;
    }
    if (!isfinite(derating) || derating <= 0.0) {
        derating = POWER_RUNTIME_DERATING;
    }
    const double total_mw = estimate != NULL ? estimate->total_mw : 0.0;

    *out = (power_runtime_t){
        .status = POWER_RUNTIME_OK,
        .hours = 0.0,
        .modelled_hours = 0.0,
        .derating = derating,
        .total_mw = total_mw,
    };

    if (battery == NULL || !battery->capacity_known) {
        out->status = POWER_RUNTIME_NO_CAPACITY;
        return;
    }
    if (!isfinite(battery->capacity_mah) || battery->capacity_mah < 0.0 ||
        !isfinite(battery->nominal_v) || battery->nominal_v <= 0.0) {
        out->status = POWER_RUNTIME_UNUSABLE_CAPACITY;
        return;
    }
    if (!isfinite(total_mw) || total_mw <= 0.0) {
        /* No real state does this - the board's own overhead is never free -
         * but dividing by it would report an infinite runtime, which is the
         * single worst answer this function could give. */
        out->status = POWER_RUNTIME_NO_DRAW;
        return;
    }

    /* A remaining capacity of exactly zero is deliberately not one of the
     * failures above: it is a perfectly good answer of zero hours, and
     * reporting it as unknown would hide a flat battery behind a shrug. */
    const double energy_mwh = battery->capacity_mah * battery->nominal_v;
    out->modelled_hours = energy_mwh / total_mw;
    out->hours = out->modelled_hours * derating;
}

/**
 * @file power_model.h
 * @brief What the board draws, and how long a battery would last it.
 *
 * The mirror of the repository's `power.py`, which is the specification.
 * `firmware/test/gen_power_vectors.py` runs the real Python and writes what it
 * returned into a header; `test_power_model.c` asserts this C lands on the same
 * doubles. Change `power.py`, then `make -C firmware/test power-vectors`.
 *
 * ## There is no battery on this board
 *
 * None of this reads a fuel gauge, because there is not one to read, and
 * nothing here is a driver. ::power_battery_t is the shape the answer will
 * arrive in, and ::power_model_runtime already knows what to do when it says
 * "nobody knows" - which is what it says today, for every board.
 *
 * ## Pure on purpose
 *
 * No ESP-IDF, no clock, no flash - the same split that lets `acq_profile.c` and
 * `store_merge.c` be checked on the host. Everything this needs to know about
 * the world arrives in a ::power_state_t.
 *
 * ## Two rules carried over from `power.py`, and both are load-bearing
 *
 * **Milliwatts, not milliamps.** The SPS30 is on 5 V and the ESP32 on 3.3 V, so
 * their currents cannot be added. Each row of ::POWER_DRAWS is a current at its
 * own rail and the model multiplies before it sums.
 *
 * **The answer is short on purpose.** ::POWER_RUNTIME_DERATING shortens every
 * reported runtime, because promising eight hours and delivering six is a worse
 * failure than promising six and delivering eight. `power.py`'s own docstring
 * for it is the full argument, including what it covers and how to tune it once
 * there is something to tune it against.
 */
#pragma once

#include <stdbool.h>

#include "acq_profile.h"

#ifdef __cplusplus
extern "C" {
#endif

/* --- the rails ---------------------------------------------------------- */

/** The SPS30's supply, from `power.SPS30_RAIL_V`. */
#define POWER_SPS30_RAIL_V 5.0
/** The ESP32's supply and everything else on the board, `power.ESP32_RAIL_V`. */
#define POWER_ESP32_RAIL_V 3.3

/* --- the derating ------------------------------------------------------- */

/**
 * @brief The single conservatism knob: every runtime is multiplied by this.
 *
 * 0.70, i.e. report 70% of what the model says. Roughly 0.85 for the battery
 * half - ageing, cold, the unusable end of the discharge curve - times 0.85 for
 * the conversion half, taken down for how many rows of ::POWER_DRAWS are
 * estimates. It is one constant and not a product deliberately: a product
 * invites tuning one factor and forgetting the rest, and there is no
 * measurement yet that could justify separating them.
 *
 * `power.py`'s ::RUNTIME_DERATING carries the full reasoning and is the copy to
 * change; this one follows it, and the vectors fail if it does not.
 */
#define POWER_RUNTIME_DERATING 0.70

/* --- where a number came from ------------------------------------------- */

/**
 * @brief How much a row of ::POWER_DRAWS is worth trusting.
 *
 * Recorded in the firmware and not only in the Python because a table of
 * fourteen plausible-looking numbers, twelve of which are guesses, is exactly
 * the thing somebody later treats as fact. The one-sentence provenance for each
 * row lives in `power.py` rather than here: it is prose, it would cost flash on
 * a part that has none spare, and it belongs beside the specification.
 */
typedef enum {
    POWER_SOURCE_DATASHEET = 0, /**< traceable to a manufacturer document */
    POWER_SOURCE_MEASURED,      /**< somebody metered it and wrote down how */
    POWER_SOURCE_ESTIMATE,      /**< a guess, including unverified recollection */
} power_source_t;

/** @brief The four things a draw can belong to, in the order a report reads. */
typedef enum {
    POWER_SUBSYSTEM_SENSOR = 0,
    POWER_SUBSYSTEM_CONTROLLER,
    POWER_SUBSYSTEM_RADIO,
    POWER_SUBSYSTEM_STORAGE,
    POWER_SUBSYSTEM_COUNT,
} power_subsystem_t;

/**
 * @brief Every physical constant, in `power.DRAWS` order.
 *
 * The order is the contract: the vectors index by it and `power.py`'s summation
 * order follows it, which is what lets both sides land on the same bits rather
 * than merely on the same value. Add a row at the end.
 */
typedef enum {
    POWER_DRAW_SENSOR_MEASURING = 0,
    POWER_DRAW_SENSOR_FAN_STARTING,
    POWER_DRAW_SENSOR_IDLE,
    POWER_DRAW_SENSOR_SLEEP,
    POWER_DRAW_SENSOR_CLEANING,
    POWER_DRAW_CPU_ACTIVE,
    POWER_DRAW_CPU_LIGHT_SLEEP,
    POWER_DRAW_BOARD_OVERHEAD,
    POWER_DRAW_WIFI_IDLE,
    POWER_DRAW_WIFI_STREAMING,
    POWER_DRAW_BLE_ADVERTISING,
    POWER_DRAW_BLE_CONNECTED,
    POWER_DRAW_FLASH_PAGE_WRITE,
    POWER_DRAW_FLASH_SECTOR_ERASE,
    POWER_DRAW_COUNT,
} power_draw_id_t;

/** @brief One physical constant. @see POWER_DRAWS */
typedef struct {
    const char *key;   /**< the `power.DRAWS` key, so a vector can name it */
    double current_ma; /**< at ::rail_v, not at the battery */
    double rail_v;
    power_source_t source;
    power_subsystem_t subsystem;
} power_draw_t;

/** @brief The constants table, indexed by ::power_draw_id_t. */
extern const power_draw_t POWER_DRAWS[POWER_DRAW_COUNT];

/* --- the other constants ------------------------------------------------ */

/** Seconds an SPS30 fan self-clean runs, from `power.CLEAN_SECONDS`. */
#define POWER_CLEAN_SECONDS 20.0
/** Seconds between cleans: `acq_clean.h`'s ACQ_CLEAN_AFTER_SECONDS. */
#define POWER_CLEAN_INTERVAL_SECONDS 604800.0
/** Seconds of flash activity per page programmed. */
#define POWER_FLASH_PAGE_WRITE_SECONDS 0.005
/** Seconds of flash activity per sector erased. */
#define POWER_FLASH_SECTOR_ERASE_SECONDS 0.1
/**
 * Bytes a stored reading costs. The one measured number in the whole model:
 * `STORAGE_FORMAT.md` settled it over 120,000 real readings from this sensor.
 */
#define POWER_BYTES_PER_READING 10.0
/** Usable bytes in a store page, from `store.BODY_BYTES`. */
#define POWER_PAGE_BODY_BYTES 464.0
/** Store pages per flash erase sector: 4096 / `store.PAGE_BYTES`. */
#define POWER_PAGES_PER_SECTOR 8.0

/* --- what the device is doing ------------------------------------------- */

/** @brief Wi-Fi, from `power.WIFI_*`. */
typedef enum {
    POWER_WIFI_OFF = 0,
    POWER_WIFI_IDLE,      /**< associated, no client traffic */
    POWER_WIFI_STREAMING, /**< associated and serving clients */
} power_wifi_t;

/** @brief Bluetooth, from `power.BLE_*`. */
typedef enum {
    POWER_BLE_OFF = 0,
    POWER_BLE_ADVERTISING,
    POWER_BLE_CONNECTED,
} power_ble_t;

/** @brief The processor, from `power.CPU_*`. */
typedef enum {
    POWER_CPU_ACTIVE = 0,
    /**
     * Unreachable today: there is no `esp_pm` configuration and no call to
     * `esp_light_sleep_start` anywhere in this firmware, so the processor stays
     * awake even while `acq` has the sensor duty cycled. Modelled so the saving
     * can be argued about before anyone builds it.
     */
    POWER_CPU_LIGHT_SLEEP,
} power_cpu_t;

/**
 * @brief Everything the model needs to know about what the board is doing.
 *
 * ::wifi and ::ble are independent and both being on is not an error: this
 * firmware serves the identical protocol over each at once (`net_link.h`), and
 * a transport change is make-before-break, so there are moments when both are
 * live and paid for.
 *
 * ::sensor_sleeps_between_cycles defaults false in ::POWER_DEFAULT_STATE, which
 * is the expensive answer, and not merely out of caution. Sleep needs SPS30
 * firmware >= 2.0, and `park_sensor` in `components/acq/acq.c` says the sleep
 * command can fail and the cycle then "burns idle power instead of sleep
 * power" - so a duty-cycled board that is not sleeping is a state this firmware
 * already knows how to be in. Assuming the cheaper case would *lengthen* the
 * estimate, which is the one direction this model may not err in.
 *
 * ::storage_rate_stated is how "unknown" is spelled here, because C has no
 * `None`. False means "derive the write rate from the profile", which is the
 * ordinary case. True means the caller knows better - a board serving an import
 * writes at a rate its acquisition profile says nothing about. A stated rate
 * that is zero, negative or not finite costs nothing rather than raising, on
 * both sides.
 */
typedef struct {
    acq_profile_t profile;
    power_wifi_t wifi;
    power_ble_t ble;
    power_cpu_t cpu;
    bool sensor_sleeps_between_cycles;
    bool sensor_present;
    bool storage_rate_stated;
    double storage_readings_per_s; /**< only read when ::storage_rate_stated */
    bool board_overhead;
} power_state_t;

/**
 * @brief The board as this firmware actually runs it today.
 *
 * Continuous acquisition, Wi-Fi serving, Bluetooth advertising for whoever
 * turns up, and a development board's own overhead being paid. Also the more
 * expensive reading of an unknown state, which is why it is the default.
 */
#define POWER_DEFAULT_STATE()                                                  \
    ((power_state_t){                                                          \
        .profile = ACQ_DEFAULT_PROFILE(),                                      \
        .wifi = POWER_WIFI_STREAMING,                                          \
        .ble = POWER_BLE_ADVERTISING,                                          \
        .cpu = POWER_CPU_ACTIVE,                                               \
        .sensor_sleeps_between_cycles = false,                                 \
        .sensor_present = true,                                                \
        .storage_rate_stated = false,                                          \
        .storage_readings_per_s = 0.0,                                         \
        .board_overhead = true,                                                \
    })

/* --- the answer --------------------------------------------------------- */

/**
 * @brief What a state costs: the same total cut two ways.
 *
 * ::by_draw_mw is for deciding which constant is worth putting a meter on;
 * ::by_subsystem_mw is for a person reading a report. Both sum to ::total_mw.
 */
typedef struct {
    double by_draw_mw[POWER_DRAW_COUNT];
    double by_subsystem_mw[POWER_SUBSYSTEM_COUNT];
    double total_mw;
} power_estimate_t;

/** @brief What is left in the battery, as far as anything knows. */
typedef struct {
    /**
     * False - the state of every board in this project - means nobody knows,
     * and ::power_model_runtime answers with ::POWER_RUNTIME_NO_CAPACITY rather
     * than with a number.
     */
    bool capacity_known;
    /** Remaining capacity, not rated. A gauge reports the first, a datasheet
     *  the second, and spending one for the other is the difference between a
     *  runtime and a fantasy. */
    double capacity_mah;
    /** The cell's nominal voltage; 3.7 for one lithium-ion cell. Nominal and
     *  not the fully charged 4.2, because the cell spends most of the discharge
     *  below the latter. */
    double nominal_v;
} power_battery_t;

/** @brief A battery nobody has measured, which is every battery here. */
#define POWER_BATTERY_UNKNOWN()                                                \
    ((power_battery_t){.capacity_known = false, .capacity_mah = 0.0, .nominal_v = 3.7})

/** @brief Why a runtime is or is not a number. */
typedef enum {
    POWER_RUNTIME_OK = 0,             /**< `hours` means something */
    POWER_RUNTIME_NO_CAPACITY,        /**< nothing knows what is left */
    POWER_RUNTIME_UNUSABLE_CAPACITY,  /**< NaN, infinite, negative, bad voltage */
    POWER_RUNTIME_NO_DRAW,            /**< modelled power was not positive */
} power_runtime_status_t;

/**
 * @brief How long the board keeps going, or why that cannot be said.
 *
 * ::hours is meaningful only when ::status is ::POWER_RUNTIME_OK, and a caller
 * must not turn any other status into a zero or into a dash and a number:
 * "unknown" and "about to die" are opposite states, and keeping them
 * distinguishable is the whole point of the status.
 *
 * ::hours is already derated. ::modelled_hours is what the physics alone said,
 * kept so the derating can be seen rather than trusted, and so that the day
 * somebody runs a real battery flat they can compare against it directly.
 */
typedef struct {
    power_runtime_status_t status;
    double hours;
    double modelled_hours;
    double derating;
    double total_mw;
} power_runtime_t;

/* --- the model ---------------------------------------------------------- */

/**
 * @brief How many readings a second @p profile causes to be **stored**.
 *
 * The storage input, not the measurement one. In continuous mode raising the
 * interval saves flash and saves nothing else, because the fan never stops -
 * `wire.py` says so in as many words and this is where it is true.
 *
 * @param profile normalized in place semantics are not applied; a copy is
 *        normalized internally, so a profile straight off a socket is safe.
 */
double power_model_recorded_readings_per_s(const acq_profile_t *profile);

/**
 * @brief The power @p state uses, in milliwatts.
 *
 * The state is normalized first - the profile through `acq_profile_normalize`,
 * which is `wire.normalize_profile` - so a profile straight off a socket is
 * safe. An unrecognised enum value is read as the costliest member of its
 * family, because ignorance must shorten the answer and not lengthen it.
 *
 * @param state what the board is doing. NULL is treated as the default state.
 * @param out filled in. NULL is ignored.
 */
void power_model_estimate(const power_state_t *state, power_estimate_t *out);

/**
 * @brief How long @p estimate runs on @p battery, deliberately underestimated.
 *
 * @param derating pass ::POWER_RUNTIME_DERATING. Any other positive finite
 *        value is honoured, which exists so that 1.0 can be passed to get the
 *        physics with no derating when comparing this model against a battery somebody
 *        actually ran flat. A value that is not usable falls back to the
 *        constant rather than being taken at face value.
 * @param out filled in. NULL is ignored.
 */
void power_model_runtime(const power_estimate_t *estimate, const power_battery_t *battery,
                         double derating, power_runtime_t *out);

#ifdef __cplusplus
}
#endif

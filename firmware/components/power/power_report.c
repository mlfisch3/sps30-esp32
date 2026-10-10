/**
 * @file power_report.c
 * @brief `power.estimate_message` in C. See `power_report.h`.
 */

#include "power_report.h"

#include <stddef.h>

_Static_assert(POWER_SUBSYSTEM_COUNT == WIRE_POWER_SUBSYSTEM_COUNT,
               "the by_subsystem vocabulary and the model's enum are one list");

static const char *mode_string(acq_mode_t mode)
{
    return mode == ACQ_MODE_DUTY_CYCLE ? WIRE_MODE_DUTY_CYCLE : WIRE_MODE_CONTINUOUS;
}

static const char *runtime_status_string(power_runtime_status_t status)
{
    switch (status) {
    case POWER_RUNTIME_OK:
        return WIRE_POWER_RUNTIME_OK;
    case POWER_RUNTIME_UNUSABLE_CAPACITY:
        return WIRE_POWER_RUNTIME_UNUSABLE_CAPACITY;
    case POWER_RUNTIME_NO_DRAW:
        return WIRE_POWER_RUNTIME_NO_DRAW;
    case POWER_RUNTIME_NO_CAPACITY:
    default:
        return WIRE_POWER_RUNTIME_NO_CAPACITY;
    }
}

int power_report_line(char *out, size_t cap, const power_state_t *state,
                      const power_battery_t *battery)
{
    power_state_t s = state != NULL ? *state : POWER_DEFAULT_STATE();
    /* For the echo: the estimate normalizes again internally, which is
     * idempotent, but the profile the reply names must be the one it priced. */
    acq_profile_normalize(&s.profile);

    power_estimate_t estimate;
    power_model_estimate(&s, &estimate);
    power_runtime_t runtime;
    power_model_runtime(&estimate, battery, POWER_RUNTIME_DERATING, &runtime);

    /* The alternatives, in `wire.power_compare_profiles` order: continuous
     * first, then each supported duty-cycle period, everything else at its
     * default. Each is this board doing something else - same radios, same
     * processor, same sleeps - so the comparison isolates the one thing the
     * user can change from the panel. */
    wire_power_alternative_t alternatives[WIRE_POWER_ALTERNATIVE_COUNT];
    for (size_t i = 0; i < WIRE_POWER_ALTERNATIVE_COUNT; i++) {
        acq_profile_t profile = ACQ_DEFAULT_PROFILE();
        if (i > 0) {
            profile.mode = ACQ_MODE_DUTY_CYCLE;
            profile.cycle_s = WIRE_POWER_COMPARE_CYCLES[i - 1];
        }
        acq_profile_normalize(&profile);

        power_state_t alt = s;
        alt.profile = profile;
        /* A stated write rate describes what the board is doing right now, not
         * what recording under this profile would write. */
        alt.storage_rate_stated = false;
        alt.storage_readings_per_s = 0.0;

        power_estimate_t alt_estimate;
        power_model_estimate(&alt, &alt_estimate);
        power_runtime_t alt_runtime;
        power_model_runtime(&alt_estimate, battery, POWER_RUNTIME_DERATING, &alt_runtime);

        alternatives[i] = (wire_power_alternative_t){
            .mode = mode_string(profile.mode),
            .cycle_s = profile.cycle_s,
            .sleeps = s.sensor_sleeps_between_cycles,
            .total_mw = alt_estimate.total_mw,
            .has_hours = alt_runtime.status == POWER_RUNTIME_OK,
            .hours = alt_runtime.hours,
        };
    }

    wire_power_estimate_t reply = {
        .profile = {
            .mode = mode_string(s.profile.mode),
            .interval = s.profile.interval_s,
            .cycle_s = s.profile.cycle_s,
            .warmup_s = s.profile.warmup_s,
            .average_s = s.profile.average_s,
        },
        .sleeps = s.sensor_sleeps_between_cycles,
        .total_mw = estimate.total_mw,
        .alternatives = alternatives,
        .alternative_count = WIRE_POWER_ALTERNATIVE_COUNT,
        .runtime_status = runtime_status_string(runtime.status),
        .runtime_has_hours = runtime.status == POWER_RUNTIME_OK,
        .runtime_hours = runtime.hours,
        .runtime_modelled_hours = runtime.modelled_hours,
        .runtime_derating = runtime.derating,
    };
    for (int i = 0; i < POWER_SUBSYSTEM_COUNT; i++) {
        reply.by_subsystem_mw[i] = estimate.by_subsystem_mw[i];
    }
    for (int i = 0; i < POWER_DRAW_COUNT; i++) {
        switch (POWER_DRAWS[i].source) {
        case POWER_SOURCE_DATASHEET:
            reply.sources_datasheet++;
            break;
        case POWER_SOURCE_MEASURED:
            reply.sources_measured++;
            break;
        case POWER_SOURCE_ESTIMATE:
        default:
            reply.sources_estimate++;
            break;
        }
    }

    return wire_power_estimate_line(out, cap, &reply);
}

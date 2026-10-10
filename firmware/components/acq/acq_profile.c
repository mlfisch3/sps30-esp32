/**
 * @file acq_profile.c
 * @brief `wire.normalize_profile` in C. See `acq_profile.h`.
 */

#include "acq_profile.h"

#include <assert.h>
#include <stddef.h>

/* The invariant `wire.py` asserts at import: the "a cycle holds the work it
 * describes" floor may only ever raise `cycle_s`, and it must never raise it
 * past its own ceiling.
 *
 * Cast per operand rather than summed and then cast: C11 6.6p6 lets an integer
 * constant expression contain a floating constant only as the immediate operand
 * of a cast, and `-Wpedantic` holds us to it. All four bounds are whole numbers
 * of seconds, so nothing is lost. */
_Static_assert((int)ACQ_WARMUP_MAX_S + (int)ACQ_AVERAGE_MAX_S <= (int)ACQ_CYCLE_MAX_S,
               "a maximal warm-up plus average must still fit one maximal cycle");

/**
 * One duration field, as `wire.normalize_profile`'s inner `bounded` does it.
 *
 * `!(value > 0)` rather than `value <= 0` so that NaN takes the default branch:
 * a NaN has no sensible end to clamp to, and Python's `float("nan") > 0` is
 * false for the same reason. An infinity does clamp, because what it asks for
 * is unambiguous.
 */
static double bounded(double value, double fallback, double ceiling)
{
    if (!(value > 0)) {
        return fallback;
    }
    return value < ceiling ? value : ceiling;
}

void acq_profile_normalize(acq_profile_t *profile)
{
    if (profile == NULL) {
        return;
    }
    const acq_profile_t defaults = ACQ_DEFAULT_PROFILE();

    if (profile->mode != ACQ_MODE_CONTINUOUS && profile->mode != ACQ_MODE_DUTY_CYCLE) {
        profile->mode = defaults.mode;
    }
    profile->interval_s = bounded(profile->interval_s, defaults.interval_s, ACQ_INTERVAL_MAX_S);
    profile->cycle_s = bounded(profile->cycle_s, defaults.cycle_s, ACQ_CYCLE_MAX_S);
    profile->warmup_s = bounded(profile->warmup_s, defaults.warmup_s, ACQ_WARMUP_MAX_S);
    profile->average_s = bounded(profile->average_s, defaults.average_s, ACQ_AVERAGE_MAX_S);

    if (profile->warmup_s < ACQ_WARMUP_MIN_S) {
        profile->warmup_s = ACQ_WARMUP_MIN_S;
    }
    /* A cycle has to be long enough to hold the work it describes, or the
     * sensor would never actually reach the sleep the mode exists for. */
    const double needed = profile->warmup_s + profile->average_s;
    if (profile->cycle_s < needed) {
        profile->cycle_s = needed;
    }
}

/**
 * @file acq_clean.h
 * @brief Fan-clean accounting: how long the sensor has measured since a clean.
 *
 * A port of the daemon's `_accrue_run_time` / `_maybe_auto_clean` /
 * `_load_state` / `_save_state` group in `sps30_daemon.py`, with the arithmetic
 * lifted out of the state machine so it can be tested without a board.
 *
 * ## Why the controller counts at all
 *
 * The SPS30 runs its own fan clean after a week, and would need no help if it
 * were left running. Datasheet 4.2 is the problem: the counter it does that on
 * **resets on every power-down**. Duty cycling parks the sensor in Sleep-Mode
 * for fourteen minutes in every fifteen, and a controller on a consumer's mains
 * socket gets its plug pulled, so in practice that counter never reaches a week
 * and the fan is never cleaned. Nothing announces this. A dirty fan does not
 * fail, it just quietly reads low, which is the worst way for an instrument to
 * be wrong.
 *
 * So the controller keeps the account itself, over *measured* seconds rather
 * than wall-clock ones, and it has to survive a restart or a board that reboots
 * weekly would sit at zero for ever.
 *
 * ## Pure on purpose
 *
 * No ESP-IDF, no clock, no flash: the caller says how many seconds were
 * measured and where the stored value came from. That is what lets
 * `test_acq_clean.c` run the whole week on the host in a few microseconds,
 * which is the only practical way to check a threshold that takes seven days to
 * reach on hardware.
 */
#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Measured seconds between fan cleans: `wire.CLEAN_AFTER_SECONDS`.
 *
 * One week, matching the sensor's own default rather than choosing a new
 * cadence. Sensirion set that interval; the only thing wrong with it on this
 * hardware is the counter it runs on, so the counter is what gets replaced.
 */
#define ACQ_CLEAN_AFTER_SECONDS 604800.0

/**
 * @brief How much accrual is worth a flash write, in seconds.
 *
 * A tenth of an hour, which is exactly the granularity `wire.clean_due_in_hours`
 * reports at: below this the number a client would be told cannot change, so a
 * write below it would buy nothing and cost a flash cycle. The daemon persists
 * on the same trigger, as a side effect of only broadcasting `power` when the
 * payload differs.
 *
 * The cost of losing the un-persisted remainder to a power cut is at most six
 * minutes off a week, which is a rounding error against an interval whose whole
 * point is "about now".
 */
#define ACQ_CLEAN_PERSIST_SECONDS 360.0

/**
 * @brief The account. Seconds of measurement since the last fan clean.
 *
 * Treat the fields as private; they are exposed so the caller can own the
 * storage rather than because they are an interface.
 */
typedef struct {
    double run_seconds;       /**< accrued since the last clean */
    double persisted_seconds; /**< what the last successful save wrote */
} acq_clean_t;

/**
 * @brief Start the account from @p stored_seconds, as read back from storage.
 *
 * Anything a restart could hand back and the arithmetic could not survive - a
 * negative, a NaN, a value past the interval - is coerced to something the rest
 * of this file can work with. A count that has run past a week is not corrupt,
 * it is overdue, so it clamps to the threshold rather than to zero: clamping to
 * zero would give a sensor that is overdue another full week of grace on every
 * reboot, which is the exact failure this whole file exists to prevent.
 */
void acq_clean_init(acq_clean_t *account, double stored_seconds);

/** @brief Add @p seconds of measurement. Ignores negatives and non-finites. */
void acq_clean_accrue(acq_clean_t *account, double seconds);

/** @brief True once a week of measurement has accrued. */
bool acq_clean_due(const acq_clean_t *account);

/** @brief Seconds accrued since the last clean. */
double acq_clean_run_seconds(const acq_clean_t *account);

/** @brief Record that a fan clean has just run, whoever asked for it. */
void acq_clean_reset(acq_clean_t *account);

/**
 * @brief Whether enough has accrued since the last save to be worth another.
 *
 * True after ::acq_clean_reset too, unless storage already holds zero, because
 * zero is the one value that must reach storage promptly: a reboot in the
 * minutes after a clean would otherwise restore the count that clean just
 * cleared and run a second one.
 */
bool acq_clean_should_persist(const acq_clean_t *account);

/** @brief Note that the current value reached storage. */
void acq_clean_persisted(acq_clean_t *account);

#ifdef __cplusplus
}
#endif

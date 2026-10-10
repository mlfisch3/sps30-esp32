/**
 * @file acq_clean.c
 * @brief Fan-clean accounting - see acq_clean.h.
 */

#include "acq_clean.h"

#include <math.h>
#include <stddef.h>

void acq_clean_init(acq_clean_t *account, double stored_seconds)
{
    if (account == NULL) {
        return;
    }
    double seconds = stored_seconds;
    if (!isfinite(seconds) || seconds < 0.0) {
        seconds = 0.0;
    } else if (seconds > ACQ_CLEAN_AFTER_SECONDS) {
        seconds = ACQ_CLEAN_AFTER_SECONDS;
    }
    account->run_seconds = seconds;
    account->persisted_seconds = seconds;
}

void acq_clean_accrue(acq_clean_t *account, double seconds)
{
    if (account == NULL || !isfinite(seconds) || seconds <= 0.0) {
        return;
    }
    account->run_seconds += seconds;
    /* Saturate rather than run on. Past the threshold the excess says nothing
     * that "due" does not already say, and an unbounded double here would
     * eventually be handed to a fixed-width store as an out-of-range value -
     * which is undefined, not merely large. */
    if (account->run_seconds > ACQ_CLEAN_AFTER_SECONDS) {
        account->run_seconds = ACQ_CLEAN_AFTER_SECONDS;
    }
}

bool acq_clean_due(const acq_clean_t *account)
{
    return account != NULL && account->run_seconds >= ACQ_CLEAN_AFTER_SECONDS;
}

double acq_clean_run_seconds(const acq_clean_t *account)
{
    return account != NULL ? account->run_seconds : 0.0;
}

void acq_clean_reset(acq_clean_t *account)
{
    if (account != NULL) {
        account->run_seconds = 0.0;
    }
}

bool acq_clean_should_persist(const acq_clean_t *account)
{
    if (account == NULL) {
        return false;
    }
    return fabs(account->run_seconds - account->persisted_seconds) >= ACQ_CLEAN_PERSIST_SECONDS ||
           (account->run_seconds == 0.0 && account->persisted_seconds != 0.0);
}

void acq_clean_persisted(acq_clean_t *account)
{
    if (account != NULL) {
        account->persisted_seconds = account->run_seconds;
    }
}

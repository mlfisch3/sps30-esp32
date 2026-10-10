/**
 * @file test_acq_clean.c
 * @brief Host tests for the fan-clean account.
 *
 * The thing being checked takes a week of measurement to happen once, which is
 * exactly why the arithmetic was lifted out of the state machine: on the host
 * a week runs in microseconds, and every case that matters - the reboot, the
 * duty cycle that only measures a minute in fifteen, the corrupt stored value -
 * can be run to completion rather than reasoned about.
 *
 * What it is checking is a silent failure. A fan that needs cleaning does not
 * stop, it reads low, so nothing here can be caught later by looking at the
 * data: if the count is wrong the readings are wrong and look fine.
 *
 * Builds and runs on the host with no ESP-IDF: see the Makefile.
 */

#include <math.h>
#include <stdio.h>

#include "acq_clean.h"

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        g_checks++;                                                            \
        if (!(cond)) {                                                         \
            g_failures++;                                                      \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);                      \
            printf(__VA_ARGS__);                                               \
            printf("\n");                                                      \
        }                                                                      \
    } while (0)

/** Accrue @p seconds in @p step-sized pieces, as the state machine does. */
static void run_for(acq_clean_t *account, double seconds, double step)
{
    for (double done = 0.0; done < seconds; done += step) {
        acq_clean_accrue(account, step);
    }
}

static void test_starts_empty(void)
{
    acq_clean_t a;
    acq_clean_init(&a, 0.0);

    CHECK(acq_clean_run_seconds(&a) == 0.0, "a fresh account is not at zero");
    CHECK(!acq_clean_due(&a), "a fresh account already wants a clean");
    CHECK(!acq_clean_should_persist(&a), "a fresh account wants a pointless write");

    printf("a board that has never measured owes nothing and asks for nothing\n");
}

static void test_week_of_continuous(void)
{
    acq_clean_t a;
    acq_clean_init(&a, 0.0);

    /* One second per reading, which is what continuous mode accrues. */
    run_for(&a, ACQ_CLEAN_AFTER_SECONDS - 1.0, 1.0);
    CHECK(!acq_clean_due(&a), "due a second early, after %.0f s", acq_clean_run_seconds(&a));

    acq_clean_accrue(&a, 1.0);
    CHECK(acq_clean_due(&a), "not due after a full week of measurement");

    acq_clean_reset(&a);
    CHECK(!acq_clean_due(&a), "still due after the clean ran");
    CHECK(acq_clean_run_seconds(&a) == 0.0, "the clean did not clear the count");

    printf("a week of 1 Hz continuous: due to the second, and cleared by the clean\n");
}

/**
 * The case the whole file exists for.
 *
 * Sensirion's low-power figures are 60 s of measurement in every 15 min, so a
 * duty-cycling sensor takes about seventeen weeks of wall-clock time to reach
 * one week of run time - and its own counter, which resets on every Sleep-Mode
 * entry, never gets anywhere near it.
 */
static void test_duty_cycle_reaches_the_week(void)
{
    acq_clean_t a;
    acq_clean_init(&a, 0.0);

    const double per_cycle = 30.0 + 30.0; /* warm-up plus average, the two accrued */
    unsigned cycles = 0;
    while (!acq_clean_due(&a) && cycles < 100000u) {
        acq_clean_accrue(&a, 30.0);
        acq_clean_accrue(&a, 30.0);
        cycles++;
    }

    CHECK(acq_clean_due(&a), "duty cycling never reached a week of run time");
    CHECK(cycles == (unsigned)(ACQ_CLEAN_AFTER_SECONDS / per_cycle),
          "took %u cycles, expected %u", cycles,
          (unsigned)(ACQ_CLEAN_AFTER_SECONDS / per_cycle));

    const double wall_clock_days = (double)cycles * 900.0 / 86400.0;
    printf("duty cycling: %u cycles, %.0f days of wall clock, one week of fan\n", cycles,
           wall_clock_days);
}

/**
 * A restart must not restart the week.
 *
 * This is the failure the counter exists to prevent, and it is invisible: a
 * board that reboots every few days would sit for ever at "not due" while the
 * fan got steadily dirtier.
 */
static void test_survives_a_restart(void)
{
    acq_clean_t before;
    acq_clean_init(&before, 0.0);
    run_for(&before, 300000.0, 1.0);
    const double carried = acq_clean_run_seconds(&before);

    acq_clean_t after;
    acq_clean_init(&after, carried);
    CHECK(acq_clean_run_seconds(&after) == carried, "the restart lost %.0f s",
          carried - acq_clean_run_seconds(&after));
    CHECK(!acq_clean_should_persist(&after), "a reload immediately rewrote what it read");

    run_for(&after, ACQ_CLEAN_AFTER_SECONDS - carried, 1.0);
    CHECK(acq_clean_due(&after), "the week was not reached by adding to the carried count");

    printf("a restart carries the accrual forward rather than starting the week again\n");
}

static void test_stored_value_is_not_trusted(void)
{
    acq_clean_t a;

    acq_clean_init(&a, -1.0);
    CHECK(acq_clean_run_seconds(&a) == 0.0, "a negative stored value survived");

    acq_clean_init(&a, NAN);
    CHECK(acq_clean_run_seconds(&a) == 0.0, "a NaN stored value survived");

    /* Overdue, not corrupt: it clamps to the threshold and stays due. Clamping
     * this to zero would hand an already-overdue sensor another whole week of
     * grace on every reboot. */
    acq_clean_init(&a, ACQ_CLEAN_AFTER_SECONDS * 10.0);
    CHECK(acq_clean_due(&a), "an overdue stored value came back not due");
    CHECK(acq_clean_run_seconds(&a) == ACQ_CLEAN_AFTER_SECONDS, "an overdue value did not clamp");

    printf("a stored value that is negative, NaN or absurd cannot skip a clean\n");
}

static void test_accrual_ignores_nonsense(void)
{
    acq_clean_t a;
    acq_clean_init(&a, 100.0);

    acq_clean_accrue(&a, -50.0);
    acq_clean_accrue(&a, NAN);
    acq_clean_accrue(&a, INFINITY);
    acq_clean_accrue(&a, 0.0);
    CHECK(acq_clean_run_seconds(&a) == 100.0, "nonsense moved the count to %.1f",
          acq_clean_run_seconds(&a));

    /* Saturation: the count is handed to a fixed-width store, so it must not be
     * able to run past what that can hold. */
    acq_clean_accrue(&a, ACQ_CLEAN_AFTER_SECONDS * 100.0);
    CHECK(acq_clean_run_seconds(&a) == ACQ_CLEAN_AFTER_SECONDS, "the count ran past the threshold");

    printf("accrual ignores negatives, NaN and infinity, and saturates at the week\n");
}

/**
 * Writes are worth their flash cycle, and there are not many of them.
 *
 * The granularity is the one the figure is reported at, so a write below it
 * could not change what any client is shown.
 */
static void test_persists_at_the_reported_granularity(void)
{
    acq_clean_t a;
    acq_clean_init(&a, 0.0);

    run_for(&a, ACQ_CLEAN_PERSIST_SECONDS - 1.0, 1.0);
    CHECK(!acq_clean_should_persist(&a), "wrote before a tenth of an hour had accrued");

    acq_clean_accrue(&a, 1.0);
    CHECK(acq_clean_should_persist(&a), "did not write after a tenth of an hour");
    acq_clean_persisted(&a);
    CHECK(!acq_clean_should_persist(&a), "asked to write the value it had just written");

    /* A whole week, counting what it would cost. */
    acq_clean_init(&a, 0.0);
    unsigned writes = 0;
    for (double t = 0.0; t < ACQ_CLEAN_AFTER_SECONDS; t += 1.0) {
        acq_clean_accrue(&a, 1.0);
        if (acq_clean_should_persist(&a)) {
            acq_clean_persisted(&a);
            writes++;
        }
    }
    CHECK(writes == (unsigned)(ACQ_CLEAN_AFTER_SECONDS / ACQ_CLEAN_PERSIST_SECONDS),
          "a week cost %u writes", writes);

    printf("a week of continuous measurement costs %u flash writes, one per 6 min\n", writes);
}

/**
 * Zero has to reach storage promptly, whichever kind of clean produced it.
 *
 * Otherwise a reboot in the minutes after a clean restores the count that clean
 * just cleared, and the controller runs a second clean it does not need. The
 * small print is that "promptly" means "when storage does not already hold
 * zero": a clean on a board that has never written one is already recorded.
 */
static void test_a_clean_asks_to_be_written(void)
{
    acq_clean_t a;
    acq_clean_init(&a, 0.0);

    /* A week's worth stored, then a clean. This is the case that matters: the
     * count on flash is a week, and a reboot before it is cleared would run a
     * second clean straight away. */
    run_for(&a, ACQ_CLEAN_PERSIST_SECONDS * 3.0, 1.0);
    acq_clean_persisted(&a);
    acq_clean_reset(&a);
    CHECK(acq_clean_should_persist(&a), "a clean did not ask for the zero to be stored");

    acq_clean_persisted(&a);
    CHECK(!acq_clean_should_persist(&a), "asked again once the zero was stored");

    /* Nothing had reached flash since the last clean, so flash already says
     * zero and this one costs no write. */
    acq_clean_init(&a, 0.0);
    run_for(&a, 60.0, 1.0);
    acq_clean_reset(&a);
    CHECK(!acq_clean_should_persist(&a), "rewrote a zero that storage already held");

    printf("a clean stores its zero, unless the zero is what is stored already\n");
}

static void test_null_safety(void)
{
    acq_clean_init(NULL, 5.0);
    acq_clean_accrue(NULL, 5.0);
    acq_clean_reset(NULL);
    acq_clean_persisted(NULL);
    CHECK(!acq_clean_due(NULL), "a NULL account claimed a clean was due");
    CHECK(acq_clean_run_seconds(NULL) == 0.0, "a NULL account reported run time");

    printf("every entry point survives a NULL account\n");
}

int main(void)
{
    printf("fan-clean accounting host tests\n");
    printf("the count that replaces one the sensor loses on every power-down\n\n");

    test_starts_empty();
    test_week_of_continuous();
    test_duty_cycle_reaches_the_week();
    test_survives_a_restart();
    test_stored_value_is_not_trusted();
    test_accrual_ignores_nonsense();
    test_persists_at_the_reported_granularity();
    test_a_clean_asks_to_be_written();
    test_null_safety();

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    if (g_failures == 0) {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL\n");
    return 1;
}

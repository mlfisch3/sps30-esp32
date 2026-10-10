/**
 * @file test_acq_profile.c
 * @brief Host tests for profile clamping, held against the real `wire.py`.
 *
 * `acq_profile_normalize` is the firmware's copy of `wire.normalize_profile`,
 * and the two agreeing is not a nicety: they are one defence implemented twice.
 * A profile that only the Python bounds is an ESP32 that can be told, by anyone
 * who can reach the port, to record once a fortnight - and then stay connected,
 * keep answering, keep reporting itself healthy, and never write a reading
 * again. Nothing about that looks broken from the app.
 *
 * So the expectations here are not invented. `gen_wire_vectors.py` runs the
 * real `wire.normalize_profile` over a table of inputs and writes down what it
 * returned; this asserts the C returns the same four numbers. Regenerate with
 * `make -C firmware/test wire-vectors` after any change to `wire.py`.
 *
 * Builds and runs on the host with no ESP-IDF: see the Makefile.
 */

#include <math.h>
#include <stdbool.h>
#include <stdio.h>

#include "acq_profile.h"
#include "wire_vectors_generated.h"

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

/**
 * Exact equality, deliberately.
 *
 * Every value in the table is either a literal a client sent or a bound spelled
 * in both files, so there is no arithmetic here for a tolerance to absorb - and
 * a tolerance would hide the one failure that matters, the two mirrors settling
 * on different bounds.
 */
static void expect_same(const char *what, const char *field, double got, double want)
{
    CHECK(got == want, "%s: %s came out %.6f, wire.py says %.6f", what, field, got, want);
}

static void test_matches_python(void)
{
    printf("wire.normalize_profile, case for case\n");

    const size_t n = sizeof(WIRE_PROFILE_VECTORS) / sizeof(WIRE_PROFILE_VECTORS[0]);
    for (size_t i = 0; i < n; i++) {
        const wire_profile_vector_t *v = &WIRE_PROFILE_VECTORS[i];

        /* Built exactly as `net_server.c` builds one: start from the default,
         * overwrite with whatever the client actually supplied, and let a field
         * it did not supply stay zero. */
        acq_profile_t p = ACQ_DEFAULT_PROFILE();
        if (v->mode_in == 0) {
            p.mode = ACQ_MODE_CONTINUOUS;
        } else if (v->mode_in == 1) {
            p.mode = ACQ_MODE_DUTY_CYCLE;
        } else if (v->mode_in == -2) {
            p.mode = (acq_mode_t)99; /* a mode string the C could not resolve */
        }
        p.interval_s = v->interval_in;
        p.cycle_s = v->cycle_in;
        p.warmup_s = v->warmup_in;
        p.average_s = v->average_in;

        acq_profile_normalize(&p);

        CHECK((int)p.mode == v->mode_out, "%s: mode came out %d, wire.py says %d", v->description,
              (int)p.mode, v->mode_out);
        expect_same(v->description, "interval", p.interval_s, v->interval_out);
        expect_same(v->description, "cycle_s", p.cycle_s, v->cycle_out);
        expect_same(v->description, "warmup_s", p.warmup_s, v->warmup_out);
        expect_same(v->description, "average_s", p.average_s, v->average_out);
    }
    printf("  %zu cases from wire.py\n", n);
}

/**
 * The bounds themselves, as constants.
 *
 * The vectors above would still pass if both mirrors were changed to agree on a
 * new, wrong ceiling. This says what the ceiling is, so widening one is a
 * deliberate edit here rather than a silent consequence somewhere else.
 */
static void test_bounds_match_python(void)
{
    printf("the bounds are the ones wire.py declares\n");

    CHECK(ACQ_INTERVAL_MAX_S == PY_INTERVAL_MAX_S, "interval ceiling disagrees with wire.py");
    CHECK(ACQ_CYCLE_MAX_S == PY_CYCLE_MAX_S, "cycle ceiling disagrees with wire.py");
    CHECK(ACQ_WARMUP_MAX_S == PY_WARMUP_MAX_S, "warm-up ceiling disagrees with wire.py");
    CHECK(ACQ_AVERAGE_MAX_S == PY_AVERAGE_MAX_S, "average ceiling disagrees with wire.py");
    CHECK(ACQ_WARMUP_MIN_S == PY_WARMUP_MIN_S, "warm-up floor disagrees with wire.py");
}

/**
 * The property the vectors sample and this states: whatever is asked for, the
 * profile that comes out is one the board can actually record on.
 *
 * The exhaustive sweep is the point. A table can only carry the cases somebody
 * thought of, and "silently stops recording" is a failure nobody would think to
 * look for until the data was already missing.
 */
static void test_nothing_escapes_the_bounds(void)
{
    printf("no input at all produces an unrecordable profile\n");

    static const double wild[] = {
        0.0, -0.0, -1.0, -1e300, 1e-9, 0.5, 1.0, 8.0, 30.0, 900.0, 86400.0,
        86401.0, 1e6, 1e12, 1e300, NAN, INFINITY, -INFINITY,
    };
    const size_t n = sizeof(wild) / sizeof(wild[0]);
    int violations = 0;

    for (size_t a = 0; a < n; a++) {
        for (size_t b = 0; b < n; b++) {
            acq_profile_t p = {
                .mode = ACQ_MODE_DUTY_CYCLE,
                .interval_s = wild[a],
                .cycle_s = wild[b],
                .warmup_s = wild[(a + 1) % n],
                .average_s = wild[(b + 1) % n],
            };
            acq_profile_normalize(&p);

            const bool ok = p.interval_s > 0 && p.interval_s <= ACQ_INTERVAL_MAX_S &&
                            p.cycle_s > 0 && p.cycle_s <= ACQ_CYCLE_MAX_S &&
                            p.warmup_s >= ACQ_WARMUP_MIN_S && p.warmup_s <= ACQ_WARMUP_MAX_S &&
                            p.average_s > 0 && p.average_s <= ACQ_AVERAGE_MAX_S &&
                            /* and the one invariant that is not a bound */
                            p.cycle_s >= p.warmup_s + p.average_s;
            if (!ok && violations++ == 0) {
                CHECK(false,
                      "interval %g / cycle %g / warmup %g / average %g escaped the bounds",
                      p.interval_s, p.cycle_s, p.warmup_s, p.average_s);
            }
        }
    }
    CHECK(violations == 0, "%d of %zu profiles escaped the bounds", violations, n * n);
    printf("  %zu profiles swept\n", n * n);
}

/**
 * A `set_profile` that names one field resets the others.
 *
 * This is the rule `handle_set_profile` in `net_server.c` obeys, and the reason
 * it starts from ::ACQ_DEFAULT_PROFILE rather than from the profile in force. It
 * used to merge, which meant the same command left the controller and the Pi
 * running different profiles while both believed they had honoured it. The
 * merge is not testable from the host - `net_server.c` needs ESP-IDF - so what
 * is pinned here is the arithmetic the handler depends on: an unsupplied field
 * arrives as zero, and zero normalizes to that field's default, never to
 * whatever was running.
 */
static void test_a_partial_profile_resets_the_rest(void)
{
    printf("a partial set_profile is a whole profile\n");

    const acq_profile_t defaults = ACQ_DEFAULT_PROFILE();

    /* What the controller is running: duty cycling with a warm-up of its own. */
    acq_profile_t running = ACQ_DEFAULT_PROFILE();
    running.mode = ACQ_MODE_DUTY_CYCLE;
    running.warmup_s = 45.0;
    running.cycle_s = 300.0;
    acq_profile_normalize(&running);
    CHECK(running.warmup_s == 45.0, "the running profile did not take its warm-up");

    /* A client sends `{"interval": 5}` and nothing else. The handler starts from
     * the defaults, so every field it did not name is zero going in. */
    acq_profile_t applied = ACQ_DEFAULT_PROFILE();
    applied.interval_s = 5.0;
    applied.cycle_s = 0.0;
    applied.warmup_s = 0.0;
    applied.average_s = 0.0;
    acq_profile_normalize(&applied);

    CHECK(applied.interval_s == 5.0, "the field that was named did not take");
    CHECK(applied.warmup_s == defaults.warmup_s,
          "warmup came out %.1f, not the default %.1f", applied.warmup_s, defaults.warmup_s);
    CHECK(applied.warmup_s != running.warmup_s,
          "warmup kept the running value; this is the merge that was removed");
    CHECK(applied.cycle_s == defaults.cycle_s, "cycle_s did not reset");
    CHECK(applied.average_s == defaults.average_s, "average_s did not reset");
}

static void test_null_safety(void)
{
    acq_profile_normalize(NULL);
    printf("a NULL profile is ignored rather than dereferenced\n");
}

int main(void)
{
    printf("acquisition profile host tests\n");
    printf("the bounds that stop a controller being told to record nothing\n\n");

    test_matches_python();
    test_bounds_match_python();
    test_nothing_escapes_the_bounds();
    test_a_partial_profile_resets_the_rest();
    test_null_safety();

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    if (g_failures == 0) {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL\n");
    return 1;
}

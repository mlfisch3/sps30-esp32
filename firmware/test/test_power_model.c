/**
 * @file test_power_model.c
 * @brief Host tests for the power model, held against the real `power.py`.
 *
 * `power_model.c` is the firmware's copy of `power.py`, and the two agreeing is
 * the only external check either of them has. Nothing in this model has been
 * measured: every constant is a datasheet figure or an estimate, so there is no
 * ground truth to compare against. What there is, is a specification and a
 * port, and this asserts they are the same calculation.
 *
 * That matters most for the parts that are deliberately pessimistic. A mirror
 * that dropped the derating, or read an unrecognised radio state as "off"
 * instead of as the costliest one, or charged the warm-up window at the fan's
 * running current instead of its peak, would report *longer* runtimes and look
 * entirely healthy doing it. The whole point of the model is that it does not
 * promise time it cannot deliver, so those are the checks that earn their keep.
 *
 * `gen_power_vectors.py` runs the real Python over a table of states and writes
 * down what it returned; this asserts the C returns the same doubles, for
 * equality rather than for closeness. Regenerate with
 * `make -C firmware/test power-vectors` after any change to `power.py`.
 *
 * Builds and runs on the host with no ESP-IDF: see the Makefile.
 */

#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>

#include "power_model.h"
#include "power_report.h"
#include "power_vectors_generated.h"

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
 * Both implementations do the same multiplications in the same order over the
 * same constants, so there is nothing here for a tolerance to absorb - and a
 * tolerance would hide the failure that matters most, the two mirrors drifting
 * slightly apart and each looking fine on its own.
 */
static void expect_same(const char *what, const char *field, double got, double want)
{
    CHECK(got == want, "%s: %s came out %.17g, power.py says %.17g", what, field, got, want);
}

/* --- the table --------------------------------------------------------- */

static void test_the_constants_are_the_same_constants(void)
{
    printf("the constants, against power.py\n");

    expect_same("derating", "RUNTIME_DERATING", POWER_RUNTIME_DERATING, PY_RUNTIME_DERATING);
    expect_same("rails", "SPS30_RAIL_V", POWER_SPS30_RAIL_V, PY_SPS30_RAIL_V);
    expect_same("rails", "ESP32_RAIL_V", POWER_ESP32_RAIL_V, PY_ESP32_RAIL_V);
    expect_same("clean", "CLEAN_SECONDS", POWER_CLEAN_SECONDS, PY_CLEAN_SECONDS);
    expect_same("clean", "CLEAN_INTERVAL_SECONDS", POWER_CLEAN_INTERVAL_SECONDS,
                PY_CLEAN_INTERVAL_SECONDS);
    expect_same("flash", "PAGE_WRITE_SECONDS", POWER_FLASH_PAGE_WRITE_SECONDS,
                PY_FLASH_PAGE_WRITE_SECONDS);
    expect_same("flash", "SECTOR_ERASE_SECONDS", POWER_FLASH_SECTOR_ERASE_SECONDS,
                PY_FLASH_SECTOR_ERASE_SECONDS);
    expect_same("storage", "BYTES_PER_READING", POWER_BYTES_PER_READING, PY_BYTES_PER_READING);
    expect_same("storage", "PAGE_BODY_BYTES", POWER_PAGE_BODY_BYTES, PY_PAGE_BODY_BYTES);
    expect_same("storage", "PAGES_PER_SECTOR", POWER_PAGES_PER_SECTOR, PY_PAGES_PER_SECTOR);

    CHECK(POWER_DRAW_COUNT == PY_DRAW_COUNT, "the tables are different lengths: %d vs %d",
          (int)POWER_DRAW_COUNT, PY_DRAW_COUNT);
    CHECK(POWER_SUBSYSTEM_COUNT == PY_SUBSYSTEM_COUNT, "different subsystem counts");
}

static void test_the_draws_are_the_same_draws(void)
{
    printf("power.DRAWS, row for row and in order\n");

    const size_t n = sizeof(PY_DRAWS) / sizeof(PY_DRAWS[0]);
    for (size_t i = 0; i < n && i < POWER_DRAW_COUNT; i++) {
        const py_draw_vector_t *want = &PY_DRAWS[i];
        const power_draw_t *got = &POWER_DRAWS[i];

        /* The key first: if the two tables have drifted out of order, every
         * other comparison on this row is comparing unrelated things and the
         * failure would read as fourteen wrong numbers rather than one wrong
         * order. The order is a contract - the vectors index by it and both
         * sides accumulate in it. */
        CHECK(strcmp(got->key, want->key) == 0, "row %zu is '%s' here and '%s' in power.py",
              i, got->key, want->key);
        expect_same(want->key, "current_ma", got->current_ma, want->current_ma);
        expect_same(want->key, "rail_v", got->rail_v, want->rail_v);
        CHECK((int)got->source == want->source, "%s: source is %d here and %d in power.py",
              want->key, (int)got->source, want->source);
        CHECK((int)got->subsystem == want->subsystem, "%s: subsystem is %d here and %d",
              want->key, (int)got->subsystem, want->subsystem);
    }
    printf("  %zu rows from power.py\n", n);
}

/**
 * The state of the evidence, asserted rather than assumed.
 *
 * Not a rule against measuring anything: it is a rule against a row *claiming*
 * to have been measured when nobody has put a meter on this board. When
 * somebody does, this is one of the two tests to change - the other is
 * `test_nothing_claims_to_have_been_measured` in `tests/test_power.py` - in the
 * same commit that records who measured it, with what, and when.
 */
static void test_nothing_claims_to_have_been_measured(void)
{
    printf("what the model is built on\n");

    int datasheet = 0, measured = 0, estimate = 0;
    for (int i = 0; i < POWER_DRAW_COUNT; i++) {
        switch (POWER_DRAWS[i].source) {
        case POWER_SOURCE_DATASHEET: datasheet++; break;
        case POWER_SOURCE_MEASURED: measured++; break;
        case POWER_SOURCE_ESTIMATE: estimate++; break;
        }
    }
    CHECK(measured == PY_SOURCE_MEASURED_COUNT && measured == 0,
          "a row claims to have been measured (%d of them). If that is true, update this "
          "test and tests/test_power.py, and record the provenance in power.py",
          measured);
    CHECK(datasheet == PY_SOURCE_DATASHEET_COUNT, "datasheet rows: %d here, %d in power.py",
          datasheet, PY_SOURCE_DATASHEET_COUNT);
    CHECK(estimate == PY_SOURCE_ESTIMATE_COUNT, "estimate rows: %d here, %d in power.py",
          estimate, PY_SOURCE_ESTIMATE_COUNT);
    printf("  %d datasheet, %d measured, %d estimate\n", datasheet, measured, estimate);
}

/* --- a state in, a breakdown out ---------------------------------------- */

static void test_power_matches_python(void)
{
    printf("power.estimate_power, case for case\n");

    const size_t n = sizeof(PY_POWER_VECTORS) / sizeof(PY_POWER_VECTORS[0]);
    for (size_t i = 0; i < n; i++) {
        const py_power_vector_t *v = &PY_POWER_VECTORS[i];

        power_estimate_t got;
        power_model_estimate(&v->state, &got);

        for (int d = 0; d < POWER_DRAW_COUNT; d++) {
            expect_same(v->description, POWER_DRAWS[d].key, got.by_draw_mw[d], v->by_draw_mw[d]);
        }
        for (int s = 0; s < POWER_SUBSYSTEM_COUNT; s++) {
            expect_same(v->description, "subsystem", got.by_subsystem_mw[s],
                        v->by_subsystem_mw[s]);
        }
        expect_same(v->description, "total_mw", got.total_mw, v->total_mw);

        /* Derived from the same profile the state carries, and checked here
         * because it is the input the storage term is built on. */
        expect_same(v->description, "readings/s",
                    power_model_recorded_readings_per_s(&v->state.profile),
                    v->recorded_readings_per_s);
    }
    printf("  %zu states from power.py\n", n);
}

static void test_the_breakdown_adds_up(void)
{
    printf("the two cuts of the total are the same total\n");

    const size_t n = sizeof(PY_POWER_VECTORS) / sizeof(PY_POWER_VECTORS[0]);
    for (size_t i = 0; i < n; i++) {
        power_estimate_t got;
        power_model_estimate(&PY_POWER_VECTORS[i].state, &got);

        double by_draw = 0.0;
        for (int d = 0; d < POWER_DRAW_COUNT; d++) {
            by_draw += got.by_draw_mw[d];
        }
        double by_subsystem = 0.0;
        for (int s = 0; s < POWER_SUBSYSTEM_COUNT; s++) {
            by_subsystem += got.by_subsystem_mw[s];
        }
        /* The per-draw sum is over fourteen terms and the subsystem sum over
         * four of their partial sums, so these two are the one place a
         * tolerance is honest: they are the same quantity added up differently.
         * A part in a billion catches a missing row and forgives an ulp. */
        CHECK(fabs(by_draw - by_subsystem) <= 1e-9 * (fabs(by_draw) + 1.0),
              "case %zu: draws sum to %.17g, subsystems to %.17g", i, by_draw, by_subsystem);
        CHECK(by_subsystem == got.total_mw, "case %zu: total_mw is not the subsystem sum", i);
        CHECK(got.total_mw > 0.0 || PY_POWER_VECTORS[i].total_mw == 0.0,
              "case %zu: a state costing nothing", i);
    }
}

/* --- the runtime, and the derating -------------------------------------- */

static void test_runtime_matches_python(void)
{
    printf("power.estimate_runtime, case for case\n");

    const size_t n = sizeof(PY_RUNTIME_VECTORS) / sizeof(PY_RUNTIME_VECTORS[0]);
    for (size_t i = 0; i < n; i++) {
        const py_runtime_vector_t *v = &PY_RUNTIME_VECTORS[i];

        power_estimate_t estimate;
        power_model_estimate(&v->state, &estimate);

        const power_battery_t battery = {
            .capacity_known = v->capacity_known,
            .capacity_mah = v->capacity_mah,
            .nominal_v = v->nominal_v,
        };
        power_runtime_t got;
        power_model_runtime(&estimate, &battery, v->derating_in, &got);

        CHECK((int)got.status == v->status, "%s: status %d here, %d in power.py",
              v->description, (int)got.status, v->status);
        expect_same(v->description, "derating", got.derating, v->derating_out);
        if (v->status == 0) {
            expect_same(v->description, "hours", got.hours, v->hours);
            expect_same(v->description, "modelled_hours", got.modelled_hours,
                        v->modelled_hours);
        }
    }
    printf("  %zu runtimes from power.py\n", n);
}

/**
 * The captain's requirement, as the one thing that must never fail.
 *
 * Everything else here checks that the port matches the specification. This
 * checks the property both of them exist to have: the number reported is
 * shorter than the number the physics gives. It is written independently of the
 * vectors on purpose - if somebody changed `power.py` to stop derating and
 * regenerated, every vector test above would pass.
 */
static void test_the_reported_time_is_always_shorter_than_the_physics(void)
{
    printf("the reported time is short by design\n");

    const power_battery_t battery = {
        .capacity_known = true, .capacity_mah = 5000.0, .nominal_v = 3.7};

    power_state_t states[4] = {
        POWER_DEFAULT_STATE(), POWER_DEFAULT_STATE(), POWER_DEFAULT_STATE(),
        POWER_DEFAULT_STATE()};
    states[1].profile.mode = ACQ_MODE_DUTY_CYCLE;
    states[2].wifi = POWER_WIFI_OFF;
    states[2].ble = POWER_BLE_OFF;
    states[3].cpu = POWER_CPU_LIGHT_SLEEP;
    states[3].sensor_present = false;

    for (int i = 0; i < 4; i++) {
        power_estimate_t estimate;
        power_model_estimate(&states[i], &estimate);
        power_runtime_t got;
        power_model_runtime(&estimate, &battery, POWER_RUNTIME_DERATING, &got);

        CHECK(got.status == POWER_RUNTIME_OK, "state %d: no runtime at all", i);
        CHECK(got.hours < got.modelled_hours,
              "state %d: reported %.3f h against a modelled %.3f h - the derating is not "
              "being applied, and this model would be promising time it cannot deliver",
              i, got.hours, got.modelled_hours);
    }

    CHECK(POWER_RUNTIME_DERATING > 0.0 && POWER_RUNTIME_DERATING < 1.0,
          "the derating is not a derating");
}

/* --- not knowing --------------------------------------------------------- */

static void test_not_knowing_degrades_gracefully(void)
{
    printf("every way of not knowing, and the one boundary that is known\n");

    power_state_t state = POWER_DEFAULT_STATE();
    power_estimate_t estimate;
    power_model_estimate(&state, &estimate);
    power_runtime_t got;

    const power_battery_t unknown = POWER_BATTERY_UNKNOWN();
    power_model_runtime(&estimate, &unknown, POWER_RUNTIME_DERATING, &got);
    CHECK(got.status == POWER_RUNTIME_NO_CAPACITY, "an unmeasured battery gave a number");

    power_model_runtime(&estimate, NULL, POWER_RUNTIME_DERATING, &got);
    CHECK(got.status == POWER_RUNTIME_NO_CAPACITY, "no battery at all gave a number");

    const double junk[] = {NAN, INFINITY, -100.0};
    for (size_t i = 0; i < sizeof(junk) / sizeof(junk[0]); i++) {
        const power_battery_t bad = {
            .capacity_known = true, .capacity_mah = junk[i], .nominal_v = 3.7};
        power_model_runtime(&estimate, &bad, POWER_RUNTIME_DERATING, &got);
        CHECK(got.status == POWER_RUNTIME_UNUSABLE_CAPACITY,
              "a capacity of %.3g was taken at face value", junk[i]);
    }

    const power_battery_t no_volts = {
        .capacity_known = true, .capacity_mah = 2000.0, .nominal_v = 0.0};
    power_model_runtime(&estimate, &no_volts, POWER_RUNTIME_DERATING, &got);
    CHECK(got.status == POWER_RUNTIME_UNUSABLE_CAPACITY, "a cell at 0 V gave a runtime");

    /* Flat is knowledge, not ignorance. Reporting it as unknown would hide the
     * one state a user most needs told behind the same words used for "there is
     * no gauge fitted". */
    const power_battery_t flat = {
        .capacity_known = true, .capacity_mah = 0.0, .nominal_v = 3.7};
    power_model_runtime(&estimate, &flat, POWER_RUNTIME_DERATING, &got);
    CHECK(got.status == POWER_RUNTIME_OK, "a flat battery was reported as unknown");
    CHECK(got.hours == 0.0, "a flat battery has %.3f hours left", got.hours);

    /* No real state draws nothing - the board's own overhead is never free -
     * but dividing by it would report an infinite runtime, which is the single
     * worst answer this function could give. */
    const power_estimate_t nothing = {{0}, {0}, 0.0};
    const power_battery_t full = {
        .capacity_known = true, .capacity_mah = 2000.0, .nominal_v = 3.7};
    power_model_runtime(&nothing, &full, POWER_RUNTIME_DERATING, &got);
    CHECK(got.status == POWER_RUNTIME_NO_DRAW, "a power of zero gave a runtime");
    CHECK(isfinite(got.hours), "a power of zero gave %.3f hours", got.hours);
}

static void test_a_nonsense_derating_falls_back_to_the_constant(void)
{
    printf("a derating that is not usable is not honoured\n");

    power_state_t state = POWER_DEFAULT_STATE();
    power_estimate_t estimate;
    power_model_estimate(&state, &estimate);
    const power_battery_t battery = {
        .capacity_known = true, .capacity_mah = 2000.0, .nominal_v = 3.7};

    const double bad[] = {0.0, -1.0, NAN, INFINITY};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        power_runtime_t got;
        power_model_runtime(&estimate, &battery, bad[i], &got);
        CHECK(got.derating == POWER_RUNTIME_DERATING, "a derating of %.3g was honoured", bad[i]);
        CHECK(got.hours < got.modelled_hours, "and it stopped shortening the answer");
    }
}

/* --- the conservative choices, checked because they were decisions ------- */

static void test_an_unrecognised_state_is_read_as_the_costliest(void)
{
    printf("ignorance shortens the answer, it does not lengthen it\n");

    power_state_t junk = POWER_DEFAULT_STATE();
    junk.wifi = (power_wifi_t)99;
    junk.ble = (power_ble_t)99;
    junk.cpu = (power_cpu_t)99;

    power_state_t worst = POWER_DEFAULT_STATE();
    worst.wifi = POWER_WIFI_STREAMING;
    worst.ble = POWER_BLE_CONNECTED;
    worst.cpu = POWER_CPU_ACTIVE;

    power_estimate_t a, b;
    power_model_estimate(&junk, &a);
    power_model_estimate(&worst, &b);
    CHECK(a.total_mw == b.total_mw,
          "an unrecognised state cost %.3f mW where the costliest known one costs %.3f",
          a.total_mw, b.total_mw);
}

static void test_a_sensor_that_might_not_sleep_is_assumed_not_to(void)
{
    printf("the assumptions that were resolved toward a shorter runtime\n");

    power_state_t assumed = POWER_DEFAULT_STATE();
    assumed.profile.mode = ACQ_MODE_DUTY_CYCLE;
    power_state_t told = assumed;
    told.sensor_sleeps_between_cycles = true;

    power_estimate_t a, b;
    power_model_estimate(&assumed, &a);
    power_model_estimate(&told, &b);
    CHECK(a.by_subsystem_mw[POWER_SUBSYSTEM_SENSOR] > b.by_subsystem_mw[POWER_SUBSYSTEM_SENSOR],
          "assuming the sensor can sleep would have lengthened the estimate");
    CHECK(POWER_DEFAULT_STATE().sensor_sleeps_between_cycles == false,
          "the default assumes a sensor that can sleep");

    /* Sleep needs SPS30 firmware >= 2.0; the warm-up window is charged at the
     * fan's peak because that is when it is accelerating. Both of these make
     * the number smaller, which is the direction they were chosen for. */
    CHECK(POWER_DRAWS[POWER_DRAW_SENSOR_FAN_STARTING].current_ma >
              POWER_DRAWS[POWER_DRAW_SENSOR_MEASURING].current_ma,
          "the warm-up is not charged at the fan's peak");
}

static void test_a_profile_off_a_socket_is_clamped_not_trusted(void)
{
    printf("a thousand-day cycle does not become a runtime of months\n");

    power_state_t absurd = POWER_DEFAULT_STATE();
    absurd.profile.mode = ACQ_MODE_DUTY_CYCLE;
    absurd.profile.cycle_s = 8.64e10;

    power_state_t bounded = POWER_DEFAULT_STATE();
    bounded.profile.mode = ACQ_MODE_DUTY_CYCLE;
    bounded.profile.cycle_s = ACQ_CYCLE_MAX_S;

    power_estimate_t a, b;
    power_model_estimate(&absurd, &a);
    power_model_estimate(&bounded, &b);
    CHECK(a.total_mw == b.total_mw,
          "an unbounded cycle averaged the sensor away: %.6f mW against %.6f", a.total_mw,
          b.total_mw);
}

/**
 * The whole `power_estimate` reply - composition and encoding - against lines
 * the real `power.estimate_message` produced.
 *
 * Byte for byte, which is what lets the app stay free of a power model of its
 * own: the one implementation is this one, and the specification it is held to
 * is the Python that also defines the wire format.
 */
static void test_the_reply_is_the_python_reply(void)
{
    /* Sized as the client's real send buffer is (net_server.c CLIENT_OUT_MAX),
     * so a reply that outgrew what the board can actually send fails here
     * first, as -1 from the encoder, rather than on the board as a message
     * that silently never arrives. */
    char line[2048];
    for (size_t i = 0; i < sizeof(PY_REPORT_VECTORS) / sizeof(PY_REPORT_VECTORS[0]); i++) {
        const py_report_vector_t *v = &PY_REPORT_VECTORS[i];
        power_battery_t battery = {
            .capacity_known = v->capacity_known,
            .capacity_mah = v->capacity_mah,
            .nominal_v = v->nominal_v,
        };
        const int written = power_report_line(line, sizeof(line), &v->state,
                                              v->battery_null ? NULL : &battery);
        CHECK(written > 0, "%s: no reply line at all", v->description);
        if (written <= 0) {
            continue;
        }
        CHECK(strcmp(line, v->expected_line) == 0,
              "%s:\n    here:      %s    power.py:  %s", v->description, line,
              v->expected_line);
    }
    printf("the reply is power.estimate_message, byte for byte\n");
}

/** The reply's vocabulary and comparison list against the wire.py originals. */
static void test_the_reply_vocabulary_matches_wire_py(void)
{
    CHECK(strcmp(WIRE_CMD_GET_POWER_ESTIMATE, PY_CMD_GET_POWER_ESTIMATE) == 0,
          "the command is '%s', wire.py says '%s'", WIRE_CMD_GET_POWER_ESTIMATE,
          PY_CMD_GET_POWER_ESTIMATE);
    CHECK(strcmp(WIRE_MSG_POWER_ESTIMATE, PY_MSG_POWER_ESTIMATE) == 0,
          "the message type drifted from wire.py");
    CHECK(strcmp(WIRE_POWER_BASIS_MODELLED, PY_POWER_BASIS_MODELLED) == 0,
          "the basis string drifted from wire.py");
    CHECK(strcmp(WIRE_POWER_RUNTIME_OK, PY_POWER_RUNTIME_OK) == 0, "runtime 'ok' drifted");
    CHECK(strcmp(WIRE_POWER_RUNTIME_NO_CAPACITY, PY_POWER_RUNTIME_NO_CAPACITY) == 0,
          "runtime 'no_capacity' drifted");
    CHECK(strcmp(WIRE_POWER_RUNTIME_UNUSABLE_CAPACITY, PY_POWER_RUNTIME_UNUSABLE_CAPACITY) == 0,
          "runtime 'unusable_capacity' drifted");
    CHECK(strcmp(WIRE_POWER_RUNTIME_NO_DRAW, PY_POWER_RUNTIME_NO_DRAW) == 0,
          "runtime 'no_draw' drifted");

    CHECK(WIRE_POWER_COMPARE_CYCLE_COUNT == PY_POWER_COMPARE_CYCLE_COUNT,
          "%d compare cycles here, wire.py offers %d", WIRE_POWER_COMPARE_CYCLE_COUNT,
          PY_POWER_COMPARE_CYCLE_COUNT);
    for (int i = 0; i < WIRE_POWER_COMPARE_CYCLE_COUNT && i < PY_POWER_COMPARE_CYCLE_COUNT;
         i++) {
        expect_same("compare cycles", "cycle_s", WIRE_POWER_COMPARE_CYCLES[i],
                    PY_POWER_COMPARE_CYCLES[i]);
    }

    CHECK(WIRE_POWER_SUBSYSTEM_COUNT == PY_SUBSYSTEM_COUNT,
          "%d subsystems here, power.py has %d", WIRE_POWER_SUBSYSTEM_COUNT,
          PY_SUBSYSTEM_COUNT);
    for (int i = 0; i < WIRE_POWER_SUBSYSTEM_COUNT && i < PY_SUBSYSTEM_COUNT; i++) {
        CHECK(strcmp(WIRE_POWER_SUBSYSTEMS[i], PY_POWER_SUBSYSTEMS[i]) == 0,
              "subsystem %d is '%s' here, wire.py says '%s'", i, WIRE_POWER_SUBSYSTEMS[i],
              PY_POWER_SUBSYSTEMS[i]);
    }
    printf("the reply vocabulary is wire.py's, word for word\n");
}

static void test_null_safety(void)
{
    power_estimate_t estimate;
    power_model_estimate(NULL, &estimate); /* the default state, not a crash */
    CHECK(estimate.total_mw > 0.0, "a NULL state produced no power at all");

    power_state_t state = POWER_DEFAULT_STATE();
    power_model_estimate(&state, NULL); /* a NULL out is ignored, not written */
    power_model_runtime(NULL, NULL, POWER_RUNTIME_DERATING, NULL);

    power_runtime_t runtime;
    power_model_runtime(NULL, NULL, POWER_RUNTIME_DERATING, &runtime);
    CHECK(runtime.status == POWER_RUNTIME_NO_CAPACITY, "a NULL estimate gave a number");
    printf("NULLs are ignored rather than dereferenced\n");
}

int main(void)
{
    printf("power model host tests\n");
    printf("the estimate that is short on purpose, and the port that has to keep it\n\n");

    test_the_constants_are_the_same_constants();
    test_the_draws_are_the_same_draws();
    test_nothing_claims_to_have_been_measured();
    test_power_matches_python();
    test_the_breakdown_adds_up();
    test_runtime_matches_python();
    test_the_reported_time_is_always_shorter_than_the_physics();
    test_not_knowing_degrades_gracefully();
    test_a_nonsense_derating_falls_back_to_the_constant();
    test_an_unrecognised_state_is_read_as_the_costliest();
    test_a_sensor_that_might_not_sleep_is_assumed_not_to();
    test_a_profile_off_a_socket_is_clamped_not_trusted();
    test_the_reply_is_the_python_reply();
    test_the_reply_vocabulary_matches_wire_py();
    test_null_safety();

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    if (g_failures == 0) {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL\n");
    return 1;
}

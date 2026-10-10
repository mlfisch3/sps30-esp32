/**
 * @file test_wire.c
 * @brief Host tests for the JSONL protocol encoder and its command reader.
 *
 * The interesting half is not written here at all: `wire_vectors_generated.h`
 * holds lines produced by running the repository's real `wire.py`, and the
 * encoder tests assert **byte-for-byte** equality against them. That is the same
 * standard `test_shdlc.c` holds the codec to, and for the same reason - `wire.py`
 * is not a specification somebody wrote down, it is the module the Raspberry Pi
 * daemon and the Android app have been agreeing through in the field.
 *
 * A tolerance would have hidden the two mistakes that actually matter here: a
 * float printed as `1` where Python prints `1.0`, and a key order or name that
 * differs by one character. Neither changes what a lenient parser sees today;
 * both are the kind of drift that turns into a silent client-side bug later.
 *
 * The rest - the command reader, the framing, the buffer bounds - is tested
 * against hand-written cases, because there is no Python counterpart: the
 * daemon uses `json.loads`, which this deliberately does not.
 *
 * Builds and runs on the host with no ESP-IDF: see the Makefile.
 */

#include <math.h>
#include <stdio.h>
#include <string.h>

#include "store_alert.h"
#include "store_map.h"
#include "wire.h"
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

#define CHECK_STR(got, want, what)                                             \
    do {                                                                       \
        g_checks++;                                                            \
        if (strcmp((got), (want)) != 0) {                                      \
            g_failures++;                                                      \
            printf("  FAIL %s:%d: %s\n    want %s\n    got  %s\n", __FILE__,    \
                   __LINE__, (what), (want), (got));                           \
        }                                                                      \
    } while (0)

/** Rebuild a measurement from the float32 bit patterns the generator exported. */
static sps30_measurement_t from_bits(const uint32_t *bits)
{
    sps30_measurement_t m;
    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        memcpy(&m.values[i], &bits[i], sizeof(float));
    }
    return m;
}

/* --- the cross-check against wire.py -------------------------------------- */

static void test_reading_lines_match_python(void)
{
    const size_t count = sizeof(WIRE_READING_VECTORS) / sizeof(WIRE_READING_VECTORS[0]);
    for (size_t i = 0; i < count; i++) {
        const wire_reading_vector_t *v = &WIRE_READING_VECTORS[i];
        const sps30_measurement_t values = from_bits(v->values);
        const wire_reading_t reading = {
            .csv = v->csv,
            .index = v->index,
            .timestamp = v->timestamp,
            .event = v->event,
            .values = &values,
        };
        char line[WIRE_LINE_MAX];
        const int n = wire_reading_line(line, sizeof(line), &reading);
        CHECK(n > 0, "%s: the line did not fit in %d bytes", v->name, WIRE_LINE_MAX);
        if (n <= 0) {
            continue;
        }
        CHECK_STR(line, v->expected, v->name);
        CHECK(line[n - 1] == '\n', "%s: the frame does not end in a newline", v->name);
        CHECK((size_t)n == strlen(v->expected), "%s: length %d, wire.py said %zu", v->name,
              n, strlen(v->expected));
    }
    printf("readings: %zu lines compared byte-for-byte against wire.py\n", count);
}

static void test_timestamps_match_python(void)
{
    const size_t count = sizeof(WIRE_TIME_VECTORS) / sizeof(WIRE_TIME_VECTORS[0]);
    for (size_t i = 0; i < count; i++) {
        const wire_time_vector_t *v = &WIRE_TIME_VECTORS[i];
        char iso[WIRE_TIMESTAMP_LEN];
        char csv[WIRE_CSV_NAME_LEN];

        CHECK(wire_iso_timestamp(v->epoch_s, v->utc_offset_s, iso, sizeof(iso)) > 0,
              "%s: no timestamp produced", v->name);
        CHECK_STR(iso, v->iso, v->name);

        CHECK(wire_csv_name(v->epoch_s, v->utc_offset_s, csv, sizeof(csv)) > 0,
              "%s: no session name produced", v->name);
        CHECK_STR(csv, v->csv, v->name);
    }
    printf("timestamps: %zu instants compared against wire.py, both changeovers included\n",
           count);
}

static void test_index_matches_python(void)
{
    const size_t count = sizeof(WIRE_INDEX_VECTORS) / sizeof(WIRE_INDEX_VECTORS[0]);
    for (size_t i = 0; i < count; i++) {
        char got[WIRE_INDEX_LEN + 8];
        wire_format_index(WIRE_INDEX_VECTORS[i].index, got, sizeof(got));
        CHECK_STR(got, WIRE_INDEX_VECTORS[i].expected, "format_index");
    }
    printf("indices: %zu widths compared against wire.format_index, overflow included\n",
           count);
}

/**
 * The vocabulary, asserted rather than trusted.
 *
 * A message type or command that differs from `wire.py` by one character is not
 * a compile error and not a crash. It is a client that quietly ignores every
 * message of that kind, which is the hardest class of protocol bug to see.
 */
static void test_vocabulary_matches_python(void)
{
    CHECK(WIRE_TCP_PORT == PY_DEFAULT_PORT, "port %d, wire.py says %d", WIRE_TCP_PORT,
          PY_DEFAULT_PORT);
    CHECK(WIRE_MAX_CLIENTS == PY_MAX_CLIENTS, "max clients %d, wire.py says %d",
          WIRE_MAX_CLIENTS, PY_MAX_CLIENTS);
    CHECK(WIRE_INDEX_WIDTH == PY_INDEX_WIDTH, "index width %d, wire.py says %d",
          WIRE_INDEX_WIDTH, PY_INDEX_WIDTH);
    CHECK(WIRE_CONTROL_TTL_SECONDS == PY_CONTROL_TTL_SECONDS,
          "control TTL %.1f, wire.py says %.1f", WIRE_CONTROL_TTL_SECONDS,
          PY_CONTROL_TTL_SECONDS);

    CHECK_STR(WIRE_MSG_HELLO, PY_MSG_HELLO, "MSG_HELLO");
    CHECK_STR(WIRE_MSG_READING, PY_MSG_READING, "MSG_READING");
    CHECK_STR(WIRE_MSG_STATE, PY_MSG_STATE, "MSG_STATE");
    CHECK_STR(WIRE_MSG_ERROR, PY_MSG_ERROR, "MSG_ERROR");
    CHECK_STR(WIRE_MSG_PROFILE, PY_MSG_PROFILE, "MSG_PROFILE");
    CHECK_STR(WIRE_MSG_CONTROL, PY_MSG_CONTROL, "MSG_CONTROL");
    CHECK_STR(WIRE_MSG_CLIENTS, PY_MSG_CLIENTS, "MSG_CLIENTS");
    CHECK_STR(WIRE_MSG_POWER, PY_MSG_POWER, "MSG_POWER");
    CHECK_STR(WIRE_MSG_FILES, PY_MSG_FILES, "MSG_FILES");
    CHECK_STR(WIRE_MSG_RAW, PY_MSG_RAW, "MSG_RAW");
    CHECK_STR(WIRE_MSG_TIME_MAP, PY_MSG_TIME_MAP, "MSG_TIME_MAP");
    CHECK_STR(WIRE_COL_REL_RUN, PY_COL_REL_BOOT, "COL_REL_BOOT");
    CHECK_STR(WIRE_COL_REL_MS, PY_COL_REL_MS, "COL_REL_MS");
    CHECK_STR(WIRE_CMD_SET_TIME, PY_CMD_SET_TIME, "CMD_SET_TIME");

    CHECK_STR(WIRE_CMD_PAUSE, PY_CMD_PAUSE, "CMD_PAUSE");
    CHECK_STR(WIRE_CMD_RESUME, PY_CMD_RESUME, "CMD_RESUME");
    CHECK_STR(WIRE_CMD_CLEAN, PY_CMD_CLEAN, "CMD_CLEAN");
    CHECK_STR(WIRE_CMD_RESTART, PY_CMD_RESTART, "CMD_RESTART");
    CHECK_STR(WIRE_CMD_RAW, PY_CMD_RAW, "CMD_RAW");
    CHECK_STR(WIRE_CMD_LIST_FILES, PY_CMD_LIST_FILES, "CMD_LIST_FILES");
    CHECK_STR(WIRE_CMD_GET_FILE, PY_CMD_GET_FILE, "CMD_GET_FILE");
    CHECK_STR(WIRE_CMD_SET_PROFILE, PY_CMD_SET_PROFILE, "CMD_SET_PROFILE");
    CHECK_STR(WIRE_CMD_SHUTDOWN, PY_CMD_SHUTDOWN, "CMD_SHUTDOWN");
    CHECK_STR(WIRE_CMD_IDENTIFY, PY_CMD_IDENTIFY, "CMD_IDENTIFY");
    CHECK_STR(WIRE_CMD_CLAIM, PY_CMD_CLAIM, "CMD_CLAIM");
    CHECK_STR(WIRE_CMD_RELEASE, PY_CMD_RELEASE, "CMD_RELEASE");
    CHECK_STR(WIRE_CMD_BACKUP, PY_CMD_BACKUP, "CMD_BACKUP");
    CHECK_STR(WIRE_MSG_ALERTS, PY_MSG_ALERTS, "MSG_ALERTS");
    CHECK_STR(WIRE_MSG_ALERT_SEGMENT, PY_MSG_ALERT_SEGMENT, "MSG_ALERT_SEGMENT");
    CHECK_STR(WIRE_MSG_ALERT_DATA, PY_MSG_ALERT_DATA, "MSG_ALERT_DATA");
    CHECK_STR(WIRE_CMD_GET_ALERTS, PY_CMD_GET_ALERTS, "CMD_GET_ALERTS");
    CHECK_STR(WIRE_CMD_SET_ALERTS, PY_CMD_SET_ALERTS, "CMD_SET_ALERTS");
    CHECK_STR(WIRE_MSG_DRAIN_STATE, PY_MSG_DRAIN_STATE, "MSG_DRAIN_STATE");
    CHECK_STR(WIRE_MSG_DRAIN_DATA, PY_MSG_DRAIN_DATA, "MSG_DRAIN_DATA");
    CHECK_STR(WIRE_CMD_DRAIN_BEGIN, PY_CMD_DRAIN_BEGIN, "CMD_DRAIN_BEGIN");
    CHECK_STR(WIRE_CMD_DRAIN_NEXT, PY_CMD_DRAIN_NEXT, "CMD_DRAIN_NEXT");
    CHECK_STR(WIRE_CMD_DRAIN_CONFIRM, PY_CMD_DRAIN_CONFIRM, "CMD_DRAIN_CONFIRM");
    CHECK_STR(WIRE_CMD_DRAIN_CANCEL, PY_CMD_DRAIN_CANCEL, "CMD_DRAIN_CANCEL");
    CHECK_STR(WIRE_DRAIN_KIND_BODY, PY_DRAIN_KIND_BODY, "DRAIN_KIND_BODY");
    CHECK_STR(WIRE_DRAIN_KIND_SEGMENTS, PY_DRAIN_KIND_SEGMENTS, "DRAIN_KIND_SEGMENTS");
    CHECK_STR(WIRE_DRAIN_KIND_RING, PY_DRAIN_KIND_RING, "DRAIN_KIND_RING");
    /* Every state the C can send is one wire.py names. A state the app has
     * never heard of would be a drain whose outcome it cannot report, and the
     * two that matter - `freed` and `kept` - are the two ways this operation
     * says it removed something or deliberately did not. */
    {
        const char *const mine[] = {
            WIRE_DRAIN_STATE_IDLE,     WIRE_DRAIN_STATE_OFFERED,  WIRE_DRAIN_STATE_FREED,
            WIRE_DRAIN_STATE_KEPT,     WIRE_DRAIN_STATE_REFUSED,  WIRE_DRAIN_STATE_FINISHED,
            WIRE_DRAIN_STATE_ERROR,
        };
        const size_t theirs = sizeof(PY_DRAIN_STATES) / sizeof(PY_DRAIN_STATES[0]);
        CHECK(sizeof(mine) / sizeof(mine[0]) == theirs,
              "the C names %u drain states and wire.py names %u",
              (unsigned)(sizeof(mine) / sizeof(mine[0])), (unsigned)theirs);
        for (size_t i = 0; i < theirs && i < sizeof(mine) / sizeof(mine[0]); i++) {
            CHECK_STR(mine[i], PY_DRAIN_STATES[i], "DRAIN_STATES");
        }
    }
    /* The line a board with no phone preserves against has to be the same
     * number in the firmware, in wire.py and in the app's AlertRule defaults.
     * Three copies of 20.0 is exactly the kind of constant that drifts. */
    CHECK((double)STORE_ALERT_DEFAULT_PM2_5 == PY_ALERT_DEFAULT_PM2_5,
          "the default preservation line is %.2f here and %.2f in wire.py",
          (double)STORE_ALERT_DEFAULT_PM2_5, PY_ALERT_DEFAULT_PM2_5);
    CHECK_STR(SPS30_FIELD_NAMES[STORE_ALERT_FIELD_PM2_5], "pm2_5",
              "the default watched field is not pm2_5");
    CHECK_STR(WIRE_MODE_CONTINUOUS, PY_MODE_CONTINUOUS, "MODE_CONTINUOUS");
    CHECK_STR(WIRE_MODE_DUTY_CYCLE, PY_MODE_DUTY_CYCLE, "MODE_DUTY_CYCLE");
    CHECK_STR(WIRE_HELLO_SUPPORTS_SLEEP, PY_HELLO_SUPPORTS_SLEEP,
              "HELLO_SUPPORTS_SLEEP");
    CHECK_STR(WIRE_HELLO_CAN_POWER_OFF, PY_HELLO_CAN_POWER_OFF,
              "HELLO_CAN_POWER_OFF");
    CHECK_STR(WIRE_HELLO_HAS_CLOCK, PY_HELLO_HAS_CLOCK, "HELLO_HAS_CLOCK");
    CHECK_STR(WIRE_POWER_CLEAN_DUE_HOURS, PY_POWER_CLEAN_DUE_HOURS,
              "POWER_CLEAN_DUE_HOURS");
    CHECK(WIRE_CLEAN_AFTER_SECONDS == PY_CLEAN_AFTER_SECONDS,
          "the fan-clean interval is %.1f s here and %.1f s in wire.py",
          WIRE_CLEAN_AFTER_SECONDS, PY_CLEAN_AFTER_SECONDS);

    /* The ten-value order is a contract shared with the CSV, the daemon and the
     * Kotlin port. The C names them from SPS30_FIELD_NAMES; this asserts that
     * list is wire.FIELDS, so a reorder in either language fails the build. */
    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        CHECK_STR(SPS30_FIELD_NAMES[i], PY_WIRE_FIELDS[i], "field order");
    }
    printf("vocabulary: types, commands and the ten field names compared to wire.py\n");
}

static void test_profile_matches_python(void)
{
    const wire_profile_t profile = WIRE_DEFAULT_PROFILE();
    char line[WIRE_LINE_MAX];
    CHECK(wire_profile_line(line, sizeof(line), &profile) > 0, "no profile line");
    CHECK_STR(line, PY_PROFILE_LINE, "wire.DEFAULT_PROFILE");
    printf("profile: the default profile compared to what wire.py encodes\n");
}

/* --- the encoders that have no Python counterpart ------------------------- */

static void test_hello(void)
{
    const wire_hello_t hello = {
        .product = "00080000",
        .serial = "E3164D2544338487",
        .firmware = "2.1",
        .state = "measuring",
        .detail = "sensor detected",
        .csv = "SPS30-20260801_154500.csv",
        .index = 123,
        .seconds = -1.0,
        .profile = WIRE_DEFAULT_PROFILE(),
        .power_source = "usb",
        /* A day of measurement in: 168 h - 24 h = 144 h to go. */
        .clean_run_seconds = 86400.0,
        .supports_sleep = true,
        .can_power_off = false,
        .has_clock = true,
        .client_id = 3,
        .max_clients = WIRE_MAX_CLIENTS,
    };
    char line[WIRE_LINE_MAX];
    const int n = wire_hello_line(line, sizeof(line), &hello);
    CHECK(n > 0, "hello did not fit in %d bytes", WIRE_LINE_MAX);
    CHECK_STR(line,
              "{\"type\":\"hello\",\"product\":\"00080000\",\"serial\":\"E3164D2544338487\","
              "\"firmware\":\"2.1\",\"state\":\"measuring\",\"detail\":\"sensor detected\","
              "\"csv\":\"SPS30-20260801_154500.csv\",\"index\":\"0000123\","
              "\"profile\":{\"mode\":\"continuous\",\"interval\":1.0,\"cycle_s\":900.0,"
              "\"warmup_s\":30.0,\"average_s\":30.0},"
              "\"power\":{\"source\":\"usb\",\"method\":\"none\","
              "\"clean_due_in_hours\":144.0},"
              "\"supports_sleep\":true,\"can_power_off\":false,"
              "\"has_clock\":true,"
              "\"client_id\":3,\"max_clients\":8}\n",
              "hello");

    /* A controller with no clock says so, which is what asks a client to send
     * one. Absent means yes, so nothing that predates this starts being asked. */
    wire_hello_t clockless = hello;
    clockless.has_clock = false;
    CHECK(wire_hello_line(line, sizeof(line), &clockless) > 0, "clockless hello");
    CHECK(strstr(line, "\"has_clock\":false") != NULL,
          "a clockless controller did not ask to be told the time");

    /* `seconds` is omitted when it is not known, present when it is - the
     * daemon's rule, and the app models it as a nullable. */
    wire_hello_t timed = hello;
    timed.seconds = 30.0;
    CHECK(wire_hello_line(line, sizeof(line), &timed) > 0, "timed hello did not fit");
    CHECK(strstr(line, "\"seconds\":30.0") != NULL, "seconds missing when it is known");
    CHECK(strstr(line, "\"detail\":\"sensor detected\",\"seconds\":30.0,\"csv\"") != NULL,
          "seconds is not where the daemon puts it");

    /* No session yet: a JSON null, not the string "null" and not an empty name. */
    wire_hello_t fresh = hello;
    fresh.csv = NULL;
    CHECK(wire_hello_line(line, sizeof(line), &fresh) > 0, "fresh hello did not fit");
    CHECK(strstr(line, "\"csv\":null,") != NULL, "an absent session is not a JSON null");

    /* Naming the transport is what stops "1 of 8" reaching a Bluetooth screen:
     * the figure and the link it belongs to travel together. */
    wire_hello_t over_ble = hello;
    over_ble.transport = WIRE_TRANSPORT_BLE;
    over_ble.max_clients = wire_max_clients_for(WIRE_TRANSPORT_BLE);
    CHECK(wire_hello_line(line, sizeof(line), &over_ble) > 0, "bluetooth hello did not fit");
    CHECK(strstr(line, "\"max_clients\":1,\"transport\":\"ble\"}") != NULL,
          "a Bluetooth hello did not report the capacity of its own link: %s", line);

    printf("hello: every field the app reads, and the two it treats as nullable\n");
}

/**
 * The capacity each link can really serve.
 *
 * Wi-Fi serves a room and Bluetooth serves one phone, and the constant that
 * sizes the socket array is not the answer to "how many can connect over this".
 * ::WIRE_MAX_CLIENTS_BLE must also equal ::BLE_NUS_MAX_LINKS, which is asserted
 * where the two are visible together rather than here.
 */
static void test_capacity_is_per_transport(void)
{
    CHECK(wire_max_clients_for(WIRE_TRANSPORT_TCP) == WIRE_MAX_CLIENTS_TCP,
          "Wi-Fi capacity moved");
    CHECK(wire_max_clients_for(WIRE_TRANSPORT_BLE) == 1, "Bluetooth serves exactly one");
    /* An unnamed or unknown link gets the figure every client was already being
     * given, so nothing that does not name its transport changes behaviour. */
    CHECK(wire_max_clients_for(NULL) == WIRE_MAX_CLIENTS_TCP, "an unnamed link");
    CHECK(wire_max_clients_for("carrier pigeon") == WIRE_MAX_CLIENTS_TCP, "an unknown link");

    printf("capacity: %u over Wi-Fi, %u over Bluetooth\n",
           wire_max_clients_for(WIRE_TRANSPORT_TCP), wire_max_clients_for(WIRE_TRANSPORT_BLE));
}

/**
 * The fan-clean figure, held against what wire.py actually returns.
 *
 * This is the one number in the protocol whose *value* has to match the daemon
 * rather than merely its shape: the app shows "clean due in N hours" and a user
 * comparing the Pi against the controller must not be told two different things
 * about the same sensor. The vectors are generated by running
 * `wire.clean_due_in_hours` itself, so a change to the rounding or the interval
 * on either side fails here rather than in the field.
 */
static void test_power_and_clean_due(void)
{
    char line[WIRE_LINE_MAX];

    for (size_t i = 0; i < sizeof(WIRE_CLEAN_VECTORS) / sizeof(WIRE_CLEAN_VECTORS[0]); i++) {
        const wire_clean_vector_t *v = &WIRE_CLEAN_VECTORS[i];
        const double hours = wire_clean_due_in_hours(v->run_seconds);
        CHECK(hours == v->expected_hours, "%.1f s measured: %.17g h here, %.17g h in wire.py",
              v->run_seconds, hours, v->expected_hours);

        CHECK(wire_power_line(line, sizeof(line), "usb", v->run_seconds) > 0,
              "no power line for %.1f s", v->run_seconds);
        CHECK_STR(line, v->expected_line, "power line");
    }

    /* Never negative and never a NaN on the wire: both would reach the app as a
     * number it would happily render, and "clean due in -3.2 hours" is worse
     * than no figure at all. */
    CHECK(wire_clean_due_in_hours(WIRE_CLEAN_AFTER_SECONDS * 10.0) == 0.0,
          "an overdue count went negative");
    /* A corrupt stored count reports the clean as due, which is what wire.py
     * does and is the safe direction: this figure exists to warn, so failing
     * towards "clean it" costs twelve seconds of fan and failing towards "a
     * full week left" costs the warning. The firmware used to do the second,
     * and the generated vectors above now carry the case so the two mirrors
     * cannot drift apart on it again. */
    CHECK(wire_clean_due_in_hours(NAN) == 0.0, "a NaN count did not report the clean as due");
    CHECK(wire_clean_due_in_hours(-INFINITY) == 0.0, "an infinite count escaped the floor");
    CHECK(wire_clean_due_in_hours(-1.0) > 168.0 || wire_clean_due_in_hours(-1.0) == 168.0,
          "a negative count reported less than a full week");

    CHECK(wire_power_line(line, sizeof(line), "usb", 0.0) > 0, "no power line");
    CHECK(strstr(line, "\"average_ma\"") == NULL,
          "this board reported a current it has no way to measure");

    printf("power: the fan-clean figure is wire.py's, to the last digit\n");
}

static void test_state_control_clients_error(void)
{
    char line[WIRE_LINE_MAX];

    CHECK(wire_state_line(line, sizeof(line), "checking", "looking for a sensor", -1.0) > 0,
          "no state line");
    CHECK_STR(line,
              "{\"type\":\"state\",\"state\":\"checking\","
              "\"detail\":\"looking for a sensor\"}\n",
              "state without a duration");

    CHECK(wire_state_line(line, sizeof(line), "cleaning", "fan self-clean", 10.0) > 0,
          "no timed state line");
    CHECK_STR(line,
              "{\"type\":\"state\",\"state\":\"cleaning\",\"detail\":\"fan self-clean\","
              "\"seconds\":10.0}\n",
              "state with a duration");

    CHECK(wire_control_line(line, sizeof(line), 0, "", 0.0) > 0, "no control line");
    CHECK_STR(line,
              "{\"type\":\"control\",\"holder\":null,\"holder_name\":null,"
              "\"expires_in\":0.0}\n",
              "control held by nobody");

    CHECK(wire_control_line(line, sizeof(line), 2, "Mike's phone", 119.4) > 0,
          "no held control line");
    CHECK_STR(line,
              "{\"type\":\"control\",\"holder\":2,\"holder_name\":\"Mike's phone\","
              "\"expires_in\":119.4}\n",
              "control held");

    const wire_client_t roster[] = {{1, "phone"}, {2, "laptop"}};
    /* No transport named: byte-for-byte the frame this sent before the field
     * existed, which is what an older controller and the Pi both still send. */
    CHECK(wire_clients_line(line, sizeof(line), roster, 2, WIRE_MAX_CLIENTS, NULL) > 0,
          "no clients line");
    CHECK_STR(line,
              "{\"type\":\"clients\",\"clients\":[{\"id\":1,\"name\":\"phone\"},"
              "{\"id\":2,\"name\":\"laptop\"}],\"max\":8}\n",
              "the roster");

    CHECK(wire_clients_line(line, sizeof(line), roster, 0, WIRE_MAX_CLIENTS, NULL) > 0,
          "no empty clients line");
    CHECK_STR(line, "{\"type\":\"clients\",\"clients\":[],\"max\":8}\n", "an empty roster");

    /* Over Bluetooth the honest figure is one, and the roster says which link
     * that one belongs to rather than leaving the app to infer it. */
    CHECK(wire_clients_line(line, sizeof(line), roster, 1,
                            wire_max_clients_for(WIRE_TRANSPORT_BLE), WIRE_TRANSPORT_BLE) > 0,
          "no bluetooth clients line");
    CHECK_STR(line,
              "{\"type\":\"clients\",\"clients\":[{\"id\":1,\"name\":\"phone\"}],"
              "\"max\":1,\"transport\":\"ble\"}\n",
              "the roster on Bluetooth");

    /* An untagged error is exactly the bytes it was before `ref` existed. That
     * is what makes the field additive: a reader that does not know it sees no
     * difference at all. */
    CHECK(wire_error_line(line, sizeof(line), "no sensor to clean", false, false, NULL) > 0,
          "no error line");
    CHECK_STR(line, "{\"type\":\"error\",\"message\":\"no sensor to clean\"}\n",
              "a plain error");
    CHECK(wire_error_line(line, sizeof(line), "no sensor to clean", false, false, "") > 0,
          "no empty-ref error line");
    CHECK_STR(line, "{\"type\":\"error\",\"message\":\"no sensor to clean\"}\n",
              "an empty ref is the same as none");

    CHECK(wire_error_line(line, sizeof(line), "phone has control", true, false, NULL) > 0,
          "no control error");
    CHECK_STR(line,
              "{\"type\":\"error\",\"message\":\"phone has control\","
              "\"needs_control\":true}\n",
              "an error the app can offer to fix");

    CHECK(wire_error_line(line, sizeof(line), "already serving 8 clients", false, true, NULL) > 0,
          "no capacity error");
    CHECK(strstr(line, "\"at_capacity\":true") != NULL, "the capacity flag is missing");

    /* Tagged: the client can now tell a refused clean from a failed transfer.
     * Without this an unrelated refusal cancelled a running backfill. */
    CHECK(wire_error_line(line, sizeof(line), "no sensor to clean", false, false,
                          WIRE_CMD_CLEAN) > 0,
          "no tagged error");
    CHECK_STR(line,
              "{\"type\":\"error\",\"message\":\"no sensor to clean\",\"ref\":\"clean\"}\n",
              "an error that says what it answers");

    CHECK(wire_error_line(line, sizeof(line), "phone has control", true, false,
                          WIRE_CMD_SET_PROFILE) > 0,
          "no tagged control error");
    CHECK_STR(line,
              "{\"type\":\"error\",\"message\":\"phone has control\","
              "\"needs_control\":true,\"ref\":\"set_profile\"}\n",
              "a tagged control refusal");

    /* The three file_list shapes, each against the bytes wire.py's own
     * file_list_page emits for it - not against a string written out here,
     * which would only ever prove this file agrees with itself. */
    CHECK(wire_file_list_line(line, sizeof(line), NULL, 0, NULL) > 0, "no file list");
    CHECK_STR(line, PY_FILE_LIST_EMPTY_LINE, "an empty file list");

    /* And a list with sessions in it, which is what a controller that stores
     * readings actually answers. The four keys and their order are the
     * daemon's, because the app reads both with the same parser. */
    const wire_file_entry_t files[] = {
        {"SPS30-20260802_175923.csv", 1834, 399212, "2026-08-02T17:59:23-04:00", false},
        {"SPS30-20260802_172243.csv", 41, 8923, "2026-08-02T17:22:43-04:00", false},
        {"SPS30-20260801_090000.csv", 7, 1526, "2026-08-01T09:00:00-04:00", false},
    };
    CHECK(wire_file_list_line(line, sizeof(line), files, 2, NULL) > 0, "no populated file list");
    CHECK_STR(line, PY_FILE_LIST_WHOLE_LINE, "a file list naming two stored sessions");

    /*
     * One page of a listing the sender could not fit in one frame.
     *
     * `next` is what makes a board that holds more sessions than a frame can
     * name still able to name them all. Without it a copy stops at whatever the
     * frame happened to carry and calls itself a backup - which is what a board
     * holding 84 sessions and listing 64 actually did.
     */
    const wire_file_page_t page = {.offset = 1, .total = 3, .more = true, .next = 2};
    CHECK(wire_file_list_line(line, sizeof(line), &files[1], 1, &page) > 0, "no paged file list");
    CHECK_STR(line, PY_FILE_LIST_PAGE_LINE, "one page of a longer listing");
    CHECK(strstr(line, "\"" PY_FILES_NEXT "\":2") != NULL,
          "the page did not say where the rest begin: %s", line);
    CHECK(strstr(PY_FILE_LIST_WHOLE_LINE, "\"" PY_FILES_NEXT "\"") == NULL,
          "a listing with nothing following still claimed a next page");
    /* Both of the other keys are on every frame, complete listing or not: a
     * client that has to guess whether a listing is whole guesses wrong. */
    CHECK(strstr(PY_FILE_LIST_EMPTY_LINE, "\"" PY_FILES_TOTAL "\":0") != NULL,
          "an empty listing did not say it held nothing");
    CHECK(strstr(PY_FILE_LIST_WHOLE_LINE, "\"" PY_FILES_OFFSET "\":0") != NULL,
          "a whole listing did not say where it starts");

    printf("snapshots: state, control, clients, error and file_list\n");
}

/* --- escaping and bounds -------------------------------------------------- */

static void test_escaping(void)
{
    char line[WIRE_LINE_MAX];
    const wire_client_t roster[] = {{1, "quote\" backslash\\ newline\n tab\t bell\x07"}};
    CHECK(wire_clients_line(line, sizeof(line), roster, 1, WIRE_MAX_CLIENTS, NULL) > 0,
          "no escaped line");
    CHECK(strstr(line, "quote\\\" backslash\\\\ newline\\n tab\\t bell\\u0007") != NULL,
          "a client name was not escaped: %s", line);

    /* The property that matters: a name a client chose can never end a JSON
     * string early or inject a newline, because a newline is the frame
     * delimiter and one in the middle of a line would split it into two. */
    size_t newlines = 0;
    for (const char *p = line; *p != '\0'; p++) {
        if (*p == '\n') {
            newlines++;
        }
    }
    CHECK(newlines == 1, "%zu newlines in one frame; a client name split it", newlines);

    printf("escaping: quotes, backslashes and control bytes cannot break a frame\n");
}

/**
 * A line that does not fit produces nothing, never a prefix.
 *
 * This is the one bound that has to hold: a truncated object still ends in the
 * `\n` the peer frames on, so it would be delivered and parsed as a corrupt
 * message rather than ignored. Every buffer size from zero up to just past the
 * real length is tried, because the interesting failures are at the boundary.
 */
static void test_truncation_produces_nothing(void)
{
    const sps30_measurement_t values = from_bits(WIRE_READING_VECTORS[0].values);
    const wire_reading_t reading = {
        .csv = WIRE_READING_VECTORS[0].csv,
        .index = WIRE_READING_VECTORS[0].index,
        .timestamp = WIRE_READING_VECTORS[0].timestamp,
        .event = WIRE_READING_VECTORS[0].event,
        .values = &values,
    };
    const size_t needed = strlen(WIRE_READING_VECTORS[0].expected);

    char buffer[WIRE_LINE_MAX];
    for (size_t cap = 1; cap <= needed + 2; cap++) {
        memset(buffer, 'x', sizeof(buffer));
        const int n = wire_reading_line(buffer, cap, &reading);
        if (cap <= needed) {
            CHECK(n == -1, "cap %zu (need %zu) produced %d bytes", cap, needed, n);
            CHECK(buffer[0] == '\0', "cap %zu left a partial line behind", cap);
        } else {
            CHECK(n == (int)needed, "cap %zu produced %d bytes, wanted %zu", cap, n, needed);
        }
    }

    /* A zero-length buffer must not be written to at all. */
    CHECK(wire_reading_line(NULL, 0, &reading) == -1, "a NULL buffer was accepted");
    CHECK(wire_reading_line(buffer, 0, &reading) == -1, "a zero capacity was accepted");
    CHECK(wire_hello_line(buffer, sizeof(buffer), NULL) == -1, "a NULL hello was accepted");

    printf("bounds: %zu buffer sizes, none produced a partial frame\n", needed + 2);
}

/** Non-finite values cannot appear in JSON, and Python would emit `NaN`. */
static void test_non_finite_becomes_null(void)
{
    sps30_measurement_t values = from_bits(WIRE_READING_VECTORS[1].values);
    values.pm2_5 = (float)NAN;
    values.pm10 = (float)INFINITY;
    const wire_reading_t reading = {
        .csv = "SPS30-20260801_154500.csv",
        .index = 1,
        .timestamp = "2026-08-01T15:45:00-04:00",
        .event = "",
        .values = &values,
    };
    char line[WIRE_LINE_MAX];
    CHECK(wire_reading_line(line, sizeof(line), &reading) > 0, "no line");
    CHECK(strstr(line, "\"pm2_5\":null") != NULL, "NaN was not encoded as null: %s", line);
    CHECK(strstr(line, "\"pm10\":null") != NULL, "infinity was not encoded as null");
    CHECK(strstr(line, "NaN") == NULL && strstr(line, "Infinity") == NULL,
          "an invalid JSON literal reached the wire");
    printf("non-finite: NaN and infinity become null rather than invalid JSON\n");
}

/* --- the command reader --------------------------------------------------- */

static void expect_field(const char *line, const char *key, const char *want)
{
    char got[64];
    const bool found = wire_json_string(line, strlen(line), key, got, sizeof(got));
    CHECK(found, "%s not found in %s", key, line);
    if (found) {
        CHECK_STR(got, want, key);
    }
}

static void test_command_reader(void)
{
    expect_field("{\"cmd\":\"pause\"}", "cmd", "pause");
    expect_field("{ \"cmd\" : \"pause\" }", "cmd", "pause");
    expect_field("{\"cmd\":\"identify\",\"name\":\"Mike's phone\"}", "name", "Mike's phone");
    expect_field("{\"name\":\"first\",\"cmd\":\"identify\"}", "cmd", "identify");

    /* Escapes, including the one that would otherwise end the string early. */
    expect_field("{\"name\":\"a\\\"b\"}", "name", "a\"b");
    expect_field("{\"name\":\"a\\\\b\"}", "name", "a\\b");
    expect_field("{\"name\":\"a\\nb\"}", "name", "a\nb");
    expect_field("{\"name\":\"a\\u0041b\"}", "name", "aAb");
    expect_field("{\"name\":\"\\u00e9\"}", "name", "\xc3\xa9");        /* e-acute */
    expect_field("{\"name\":\"\\ud83d\\ude00\"}", "name", "\xf0\x9f\x98\x80"); /* emoji */

    char got[64];
    /* Values that are not strings, and keys that are not there. */
    CHECK(!wire_json_string("{\"cmd\":42}", 10, "cmd", got, sizeof(got)),
          "a number was read as a string");
    CHECK(!wire_json_string("{\"cmd\":\"pause\"}", 15, "name", got, sizeof(got)),
          "a missing key was found");
    CHECK(!wire_json_string("", 0, "cmd", got, sizeof(got)), "an empty line parsed");
    CHECK(!wire_json_string("not json", 8, "cmd", got, sizeof(got)), "garbage parsed");
    CHECK(!wire_json_string("{\"cmd\":\"unterminated", 20, "cmd", got, sizeof(got)),
          "an unterminated string parsed");

    /* Nesting: a key inside an object must not be mistaken for a top-level one.
     * `set_profile` is exactly this shape, and reading its inner "mode" as the
     * command would dispatch something nobody asked for. */
    CHECK(!wire_json_string("{\"cmd\":\"set_profile\",\"profile\":{\"mode\":\"duty_cycle\"}}",
                            52, "mode", got, sizeof(got)),
          "a nested key was read as a top-level one");
    expect_field("{\"profile\":{\"mode\":\"duty_cycle\"},\"cmd\":\"set_profile\"}", "cmd",
                 "set_profile");
    expect_field("{\"rows\":[[1,2],[3,4]],\"cmd\":\"pause\"}", "cmd", "pause");
    expect_field("{\"note\":\"a } inside a string\",\"cmd\":\"pause\"}", "cmd", "pause");

    /* Truncation is at a character boundary: half a UTF-8 sequence is not a
     * shorter name, it is an invalid one, and it goes straight to a UI. */
    char narrow[4];
    CHECK(wire_json_string("{\"name\":\"\\u00e9\\u00e9\\u00e9\"}", 30, "name", narrow,
                           sizeof(narrow)),
          "a long name was rejected instead of clipped");
    CHECK(strcmp(narrow, "\xc3\xa9") == 0, "the clip split a UTF-8 sequence: %s", narrow);

    printf("commands: escapes, surrogate pairs, nesting and clipping\n");
}

static void test_framing(void)
{
    CHECK(wire_line_length("{\"cmd\":\"pause\"}\n", 16) == 16, "a whole line was not framed");
    CHECK(wire_line_length("{\"cmd\":\"pause\"}", 15) == 0, "a partial line was framed");
    CHECK(wire_line_length("a\nb\n", 4) == 2, "framing took more than the first line");
    CHECK(wire_line_length("\n", 1) == 1, "an empty line was not framed");
    CHECK(wire_line_length("", 0) == 0, "an empty buffer was framed");
    CHECK(wire_line_length(NULL, 5) == 0, "a NULL buffer was framed");
    printf("framing: one line at a time, and never an incomplete one\n");
}


/* --- the transfer half ----------------------------------------------------- */

/**
 * Every instant this protocol can emit reads back as the instant it was.
 *
 * Driven by the encoder rather than by written-out strings, because the property
 * that matters is that the pair is a bijection over the range the controller
 * actually uses - and an import that misreads a timestamp by an hour files a
 * session under a name that does not exist.
 */
static void test_iso_round_trips(void)
{
    printf("timestamps read back as the instant they were written from\n");
    static const int offsets[] = {0, -14400, -18000, 3600, 19800, -34200, 50400};

    for (size_t o = 0; o < sizeof(offsets) / sizeof(offsets[0]); o++) {
        /* Ten years of instants at an awkward stride, so every month, every leap
         * day and both sides of midnight are crossed. */
        for (int64_t e = 1500000000; e < 1800000000; e += 999983) {
            char text[WIRE_TIMESTAMP_LEN];
            CHECK(wire_iso_timestamp(e, offsets[o], text, sizeof(text)) > 0,
                  "could not format %lld at offset %d", (long long)e, offsets[o]);
            int64_t back = 0;
            int back_offset = 0;
            CHECK(wire_parse_iso(text, &back, &back_offset), "could not parse '%s'", text);
            CHECK(back == e, "'%s' read back as %lld, not %lld", text, (long long)back,
                  (long long)e);
            CHECK(back_offset == offsets[o], "'%s' lost its offset: %d not %d", text,
                  back_offset, offsets[o]);
        }
    }
}

static void test_iso_rejects_anything_else(void)
{
    printf("a timestamp that is not exactly the protocol's is refused\n");
    static const char *const bad[] = {
        "",
        "2026-08-02T14:27:17",           /* no offset */
        "2026-08-02T14:27:17Z",          /* Zulu, which this protocol never emits */
        "2026-08-02T14:27:17-0400",      /* no colon: what strftime %z gives */
        "2026-08-02 14:27:17-04:00",     /* space for the T */
        "2026-13-02T14:27:17-04:00",     /* month 13 */
        "2026-08-02T24:27:17-04:00",     /* hour 24 */
        "20x6-08-02T14:27:17-04:00",     /* not a digit */
        "2026-08-02T14:27:17-04:00 ",    /* trailing space */
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        int64_t epoch = 12345;
        int offset = 999;
        CHECK(!wire_parse_iso(bad[i], &epoch, &offset), "'%s' was accepted", bad[i]);
    }
}

/**
 * A session name, the instant behind it, and the offset it was recorded in are
 * three views of one thing, and an import has to get from any of them to the
 * others. This is the loop that says so.
 */
static void test_csv_name_round_trips(void)
{
    printf("a session name recovers the instant that produced it\n");
    static const int offsets[] = {0, -14400, 3600, 19800};
    for (size_t o = 0; o < sizeof(offsets) / sizeof(offsets[0]); o++) {
        for (int64_t e = 1600000000; e < 1800000000; e += 1000003) {
            char name[WIRE_CSV_NAME_LEN];
            CHECK(wire_csv_name(e, offsets[o], name, sizeof(name)) > 0,
                  "could not name %lld", (long long)e);
            int64_t local = 0;
            CHECK(wire_parse_csv_name(name, &local), "could not read '%s'", name);
            /* The name is local wall clock; the offset turns it into the instant. */
            CHECK(local - offsets[o] == e, "'%s' recovered %lld, not %lld", name,
                  (long long)(local - offsets[o]), (long long)e);

            /* And back again, which is the property `store.c` depends on: a
             * session filed under a reconstructed start keeps the name it had. */
            char again[WIRE_CSV_NAME_LEN];
            wire_csv_name(local - offsets[o], offsets[o], again, sizeof(again));
            CHECK(strcmp(again, name) == 0, "'%s' renamed itself '%s'", name, again);
        }
    }
}

static void test_csv_name_rejects_anything_else(void)
{
    printf("a name that is not a session name is refused\n");
    static const char *const bad[] = {
        "",
        "SPS30-20260802_142717",          /* no suffix */
        "SPS30-20260802_142717.CSV",      /* the wrong case */
        "SPS30-20260802-142717.csv",      /* dash for the underscore */
        "sps30-20260802_142717.csv",      /* the wrong case again */
        "SPS30-2026080_142717.csv",       /* a digit short */
        "SPS30-20261302_142717.csv",      /* month 13 */
        "readme.txt",
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        int64_t local = 42;
        CHECK(!wire_parse_csv_name(bad[i], &local), "'%s' was accepted", bad[i]);
    }
}

/**
 * The export path and the import path, composed.
 *
 * `wire_file_data_row` is what a session becomes on its way to a phone, and
 * `wire_parse_row` is what a row becomes on its way back. Testing them together
 * is testing the round trip the user is actually promised, and it is where a
 * disagreement about column order or decimal places would show up.
 */
static void test_rows_survive_the_round_trip(void)
{
    printf("a row written for export reads back as the reading it was\n");
    sps30_measurement_t values;
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        values.values[f] = (float)(f + 1) * 1.25f;
    }
    char frame[2048];
    wire_file_data_t fd;
    CHECK(wire_file_data_open(&fd, frame, sizeof(frame), "SPS30-20260802_142717.csv",
                              false) == 0,
          "could not open a file_data frame");
    CHECK(wire_file_data_row(&fd, 42, "2026-08-02T14:27:17-04:00", &values, "", 0, 0),
          "could not write a row");
    CHECK(wire_file_data_close(&fd, true) > 0, "could not close the frame");
    /* The column list is wire.CSV_COLUMNS, cell for cell. A file written from
     * this frame carries the same names as its header, so the two cannot drift. */
    CHECK(strstr(frame, "\"columns\":" PY_CSV_COLUMNS_JSON) != NULL,
          "the columns are not wire.CSV_COLUMNS in '%s'", frame);

    /* Pull the row back out of the JSON array the encoder built. A sender does
     * this properly; here it only has to be enough to get at the cells. */
    const char *open_bracket = strstr(frame, "[[");
    CHECK(open_bracket != NULL, "no rows array in '%s'", frame);
    if (open_bracket == NULL) {
        return;
    }
    char row[256];
    size_t n = 0;
    for (const char *p = open_bracket + 2; *p != ']' && n + 1 < sizeof(row); p++) {
        if (*p != '"') {
            row[n++] = *p;
        }
    }
    row[n] = '\0';

    wire_row_t parsed;
    CHECK(wire_parse_row(row, strlen(row), &parsed), "could not parse '%s'", row);
    CHECK(parsed.index == 42, "index came back as %u", parsed.index);
    CHECK(parsed.epoch_s == 1785695237LL, "epoch came back as %lld",
          (long long)parsed.epoch_s);
    CHECK(parsed.utc_offset_s == -14400, "offset came back as %d", parsed.utc_offset_s);
    CHECK(!parsed.settling, "an empty event read as settling");
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        /* The wire carries two decimals (three for particle size), which is the
         * store's own quantum, so this is exact rather than approximate. */
        const float want = values.values[f];
        const float got = parsed.values.values[f];
        CHECK(fabsf(got - want) < 0.0005f, "field %zu came back %f, not %f", f, (double)got,
              (double)want);
    }
}

static void test_row_reader_is_strict_where_it_must_be(void)
{
    printf("a row that cannot be placed is refused, not guessed at\n");
    wire_row_t r;
    /* No index. */
    CHECK(!wire_parse_row(",2026-08-02T14:27:17-04:00,1,2,3,4,5,6,7,8,9,0.5,",
                          strlen(",2026-08-02T14:27:17-04:00,1,2,3,4,5,6,7,8,9,0.5,"), &r),
          "a row with no index was accepted");
    /* Index zero: 1-based everywhere, so this is not a reading. */
    CHECK(!wire_parse_row("0,2026-08-02T14:27:17-04:00,1,2,3,4,5,6,7,8,9,0.5,",
                          strlen("0,2026-08-02T14:27:17-04:00,1,2,3,4,5,6,7,8,9,0.5,"), &r),
          "index 0 was accepted");
    /* An unreadable timestamp. */
    CHECK(!wire_parse_row("7,not-a-time,1,2,3,4,5,6,7,8,9,0.5,",
                          strlen("7,not-a-time,1,2,3,4,5,6,7,8,9,0.5,"), &r),
          "a row with no readable time was accepted");
    /* Blank, and a bare newline. */
    CHECK(!wire_parse_row("", 0, &r), "an empty row was accepted");
    CHECK(!wire_parse_row("\n", 1, &r), "a bare newline was accepted");

    /* Zero-padded, as the protocol formats indices. Base 10, not octal. */
    static const char padded[] = "0000042,2026-08-02T14:27:17-04:00,1,2,3,4,5,6,7,8,9,0.5,";
    CHECK(wire_parse_row(padded, strlen(padded), &r), "a padded index was refused");
    CHECK(r.index == 42, "a padded index read as %u, not 42", r.index);

    /* A short row still places what it has: the values it lacks read as zero,
     * which is what every other reader in this project does with one. */
    static const char shortened[] = "9,2026-08-02T14:27:17-04:00,1.5";
    CHECK(wire_parse_row(shortened, strlen(shortened), &r), "a short row was refused");
    CHECK(r.index == 9, "a short row lost its index");
    CHECK(fabsf(r.values.values[0] - 1.5f) < 0.0005f, "a short row lost its first value");
    CHECK(fabsf(r.values.values[5]) < 0.0005f, "a missing value did not read as zero");
    CHECK(!r.settling, "a missing event column read as settling");

    /* Any non-blank event means disturbed, whatever the word is. */
    static const char settling[] =
        "3,2026-08-02T14:27:17-04:00,1,2,3,4,5,6,7,8,9,0.5,startup";
    CHECK(wire_parse_row(settling, strlen(settling), &r), "an evented row was refused");
    CHECK(r.settling, "'startup' did not read as disturbed");

    /* A file that has been through a Windows editor still reads. */
    static const char crlf[] = "5,2026-08-02T14:27:17-04:00,1,2,3,4,5,6,7,8,9,0.5,\r";
    CHECK(wire_parse_row(crlf, strlen(crlf), &r), "a CRLF row was refused");
    CHECK(r.index == 5, "a CRLF row lost its index");
}

/**
 * How many readings a backfill frame carries, measured rather than assumed.
 *
 * This is the number the transfer rate is made of: the client loop sends one
 * `file_data` frame per turn, so readings-per-frame times turns-per-second is
 * what a phone waiting for a day of recording actually gets. It was worth
 * pinning because it is easy to make it much smaller by accident - a longer
 * session name, another column, a wider timestamp - and the effect would be a
 * transfer that quietly takes twice as long rather than anything that fails.
 *
 * 2048 is `CLIENT_OUT_MAX` in components/net; the values and the name are the
 * shapes the store actually hands out, not the shortest ones that would fit.
 */
static void test_a_backfill_frame_carries_thirteen_readings(void)
{
    printf("a 2 KiB backfill frame carries thirteen readings\n");
    sps30_measurement_t values = {{12.34f, 23.45f, 34.56f, 45.67f, 123.45f, 234.56f, 345.67f,
                                   456.78f, 567.89f, 0.678f}};
    char frame[2048];
    wire_file_data_t fd;
    CHECK(wire_file_data_open(&fd, frame, sizeof(frame), "SPS30-20260803_014500.csv",
                              false) == 0,
          "could not open a file_data frame");

    unsigned rows = 0;
    while (wire_file_data_room(&fd)) {
        char timestamp[WIRE_TIMESTAMP_LEN];
        wire_iso_timestamp(1785000000LL + rows, -4 * 3600, timestamp, sizeof(timestamp));
        if (!wire_file_data_row(&fd, 1000000u + rows, timestamp, &values, "", 0, 0)) {
            break;
        }
        rows++;
    }
    const int written = wire_file_data_close(&fd, false);

    CHECK(rows == 13, "a 2 KiB frame took %u readings, not 13", rows);
    CHECK(written > 0 && (size_t)written <= sizeof(frame),
          "a full frame came out as %d bytes", written);
    /* Nearly all of the frame is readings. If this drops the framing has grown,
     * and the transfer is paying for scaffolding rather than data. */
    CHECK(written > 1800, "a full frame was only %d bytes; the rows got smaller", written);
}

static void test_transfer_messages(void)
{
    printf("the transfer messages encode as the app expects to read them\n");
    char line[WIRE_LINE_MAX];

    const wire_import_result_t imported = {
        .name = "SPS30-20260802_142717.csv",
        .added = 12,
        .duplicate = 3400,
        .rejected = 0,
        .sessions = 2,
        .state = "ok",
        .message = "",
    };
    CHECK(wire_import_result_line(line, sizeof(line), &imported) > 0,
          "import_result would not fit");
    CHECK(strcmp(line,
                 "{\"type\":\"import_result\",\"name\":\"SPS30-20260802_142717.csv\","
                 "\"added\":12,\"duplicate\":3400,\"rejected\":0,\"sessions\":2,"
                 "\"state\":\"ok\",\"message\":\"\"}\n") == 0,
          "import_result encoded as %s", line);

    const wire_storage_t storage = {
        .used_pages = 4300,
        .total_pages = 4992,
        .readings = 176000,
        .sessions = 5,
        .fraction = 0.86,
        .warn = true,
        .ready = true,
    };
    CHECK(wire_storage_line(line, sizeof(line), &storage) > 0, "storage would not fit");
    CHECK(strcmp(line,
                 "{\"type\":\"storage\",\"used_pages\":4300,\"total_pages\":4992,"
                 "\"readings\":176000,\"sessions\":5,\"fraction\":0.86,"
                 "\"warn_at\":0.85,\"warn\":true,\"ready\":true}\n") == 0,
          "storage encoded as %s", line);

    const wire_erase_result_t refused = {
        .state = "refused",
        .message = "2 sessions are not backed up on this device",
        .sessions_confirmed = 3,
        .sessions_refused = 2,
        .pages_erased = 0,
        .readings_erased = 0,
        .overridden = false,
    };
    CHECK(wire_erase_result_line(line, sizeof(line), &refused) > 0,
          "erase_result would not fit");
    CHECK(strcmp(line,
                 "{\"type\":\"erase_result\",\"state\":\"refused\","
                 "\"message\":\"2 sessions are not backed up on this device\","
                 "\"sessions_confirmed\":3,\"sessions_refused\":2,"
                 "\"pages_erased\":0,\"readings_erased\":0,\"overridden\":false}\n") ==
              0,
          "erase_result encoded as %s", line);
}


/**
 * Booleans, which this could not read at all until a board hung on one.
 *
 * `put_file`'s `done` and `erase`'s `override` are JSON booleans. The command
 * reader had an integer path and a string path and no boolean path, so `true`
 * matched neither: the last frame of a transfer never closed the import, the
 * controller never answered, and the client sat waiting forever on a transfer
 * that had in fact arrived complete. Caught on hardware, not here - which is
 * exactly why it is pinned here now.
 */
static void test_boolean_fields(void)
{
    printf("boolean command fields read as booleans\n");
    static const char done_true[] =
        "{\"cmd\": \"put_file\", \"name\": \"SPS30-20260802_142717.csv\", "
        "\"rows\": \"1,x\", \"seq\": 3, \"done\": true}";
    static const char done_false[] =
        "{\"cmd\": \"put_file\", \"name\": \"a\", \"rows\": \"r\", \"seq\": 0, "
        "\"done\": false}";

    bool value = false;
    CHECK(wire_json_bool(done_true, strlen(done_true), "done", &value),
          "a true `done` was not found");
    CHECK(value, "`done: true` read as false");

    value = true;
    CHECK(wire_json_bool(done_false, strlen(done_false), "done", &value),
          "a false `done` was not found");
    CHECK(!value, "`done: false` read as true");

    /* A key that is not there, and one that is not a boolean. */
    CHECK(!wire_json_bool(done_true, strlen(done_true), "override", &value),
          "an absent key was reported present");
    CHECK(!wire_json_bool(done_true, strlen(done_true), "seq", &value),
          "an integer was read as a boolean");
    CHECK(!wire_json_bool(done_true, strlen(done_true), "name", &value),
          "a string was read as a boolean");

    /* A quoted "true" is refused. These two fields decide whether a transfer is
     * finished and whether an erase skips its safety check; coercing a string
     * into either is not a kindness. */
    static const char quoted[] = "{\"cmd\": \"erase\", \"override\": \"true\"}";
    CHECK(!wire_json_bool(quoted, strlen(quoted), "override", &value),
          "a quoted \"true\" was accepted as a boolean");

    /* The default when it is missing has to be the safe one, which is how the
     * callers use it: no `override` means do not override. */
    static const char no_override[] = "{\"cmd\": \"erase\", \"backup\": \"x,1,0\"}";
    bool override = false;
    CHECK(!wire_json_bool(no_override, strlen(no_override), "override", &override),
          "a missing override was reported present");
    CHECK(!override, "a missing override did not stay false");

    /*
     * And the app's closing frame, byte for byte.
     *
     * The cases above are this suite's own spelling: spaces after the colons, a
     * key order chosen here. The app writes neither, and the whole of "the send
     * back completes" rests on this controller reading *its* bytes rather than
     * bytes that resemble them. The other half of this assertion is
     * `the closing frame is byte for byte what the controller parses` in
     * ArchiveSendTest.kt, which requires the app to still produce this exact
     * line; between them a change of spelling, key order or type on either end
     * turns one of the two suites red instead of turning a device pass red four
     * times running.
     */
    static const char app_last_frame[] =
        "{\"cmd\":\"put_file\",\"name\":\"SPS30-20260802_142717.csv\",\"rows\":\"1,x\","
        "\"seq\":3,\"done\":true}";
    char name[WIRE_CSV_NAME_LEN];
    char rows[32];
    value = false;
    CHECK(wire_json_bool(app_last_frame, strlen(app_last_frame), "done", &value) && value,
          "the app's closing frame does not read as done");
    CHECK(wire_json_string(app_last_frame, strlen(app_last_frame), "name", name, sizeof(name)) &&
              strcmp(name, "SPS30-20260802_142717.csv") == 0,
          "the app's closing frame named %s", name);
    CHECK(wire_json_string(app_last_frame, strlen(app_last_frame), "rows", rows, sizeof(rows)) &&
              strcmp(rows, "1,x") == 0,
          "the app's closing frame carried rows %s", rows);
}

/**
 * The nested profile, which is the shape the mode controls actually arrive in.
 *
 * `set_profile` is the one command that does not fit the flat scan, and the way
 * it did not fit was expensive: the controller answered every one of them with
 * "not implemented", so the app's Low power chip and its whole interval row did
 * nothing at all while looking like ordinary buttons. Two things are pinned
 * here. The flat accessors must still refuse to descend - reading `mode` off the
 * whole frame would find the *nested* one and make `{"profile":{...}}` and
 * `{...}` indistinguishable - and ::wire_json_object must hand back a span the
 * same accessors can then be run over.
 */
static void test_nested_profile(void)
{
    printf("the nested set_profile object\n");
    static const char line[] =
        "{\"cmd\":\"set_profile\",\"profile\":{\"mode\":\"duty_cycle\","
        "\"interval\":1.0,\"cycle_s\":900.0,\"warmup_s\":30.0,\"average_s\":30.0}}";
    const size_t len = strlen(line);

    char mode[16];
    CHECK(!wire_json_string(line, len, "mode", mode, sizeof(mode)),
          "a nested key was found at the top level");
    double number = -1;
    CHECK(!wire_json_double(line, len, "interval", &number),
          "a nested number was found at the top level");

    const char *body = NULL;
    size_t body_len = 0;
    CHECK(wire_json_object(line, len, "profile", &body, &body_len),
          "the profile object was not found");
    CHECK(body[0] == '{' && body[body_len - 1] == '}',
          "the borrowed span is not the object");

    CHECK(wire_json_string(body, body_len, "mode", mode, sizeof(mode)),
          "mode was not readable inside the profile");
    CHECK(strcmp(mode, "duty_cycle") == 0, "mode came back wrong");

    struct {
        const char *key;
        double want;
    } fields[] = {
        {"interval", 1.0}, {"cycle_s", 900.0}, {"warmup_s", 30.0}, {"average_s", 30.0},
    };
    for (size_t i = 0; i < sizeof(fields) / sizeof(fields[0]); i++) {
        number = -1;
        CHECK(wire_json_double(body, body_len, fields[i].key, &number),
              "a profile field was not found");
        CHECK(number == fields[i].want, "a profile field came back wrong");
    }

    /* `cmd` is still readable from the frame: stepping over the object must not
     * consume the members after it. Here it is before, so also check the
     * reverse ordering, which is what a hand-typed probe tends to produce. */
    char cmd[24];
    CHECK(wire_json_string(line, len, "cmd", cmd, sizeof(cmd)), "cmd was lost");
    CHECK(strcmp(cmd, "set_profile") == 0, "cmd came back wrong");
    static const char after[] = "{\"profile\":{\"mode\":\"continuous\"},\"cmd\":\"set_profile\"}";
    CHECK(wire_json_string(after, strlen(after), "cmd", cmd, sizeof(cmd)),
          "a key after the nested object was not reached");
    CHECK(strcmp(cmd, "set_profile") == 0, "the key after the object came back wrong");

    /* A profile that is not an object, and a partial one. The controller starts
     * from the profile in force and overwrites what it is given, so a partial
     * profile is a legitimate request rather than a malformed one. */
    static const char flat[] = "{\"cmd\":\"set_profile\",\"profile\":\"duty_cycle\"}";
    CHECK(!wire_json_object(flat, strlen(flat), "profile", &body, &body_len),
          "a string was accepted as the profile object");
    static const char partial[] = "{\"cmd\":\"set_profile\",\"profile\":{\"interval\":30}}";
    CHECK(wire_json_object(partial, strlen(partial), "profile", &body, &body_len),
          "a partial profile was refused");
    CHECK(!wire_json_string(body, body_len, "mode", mode, sizeof(mode)),
          "an absent mode was reported present");
    CHECK(wire_json_double(body, body_len, "interval", &number) && number == 30.0,
          "an integral interval was not read as a number");
}

/**
 * Fractional seconds, which ::wire_json_int cannot express.
 *
 * Every field of the profile is a duration and every one of them is allowed to
 * be fractional. Reading them as integers would work by luck on `1.0` and then
 * silently refuse `0.5`, which is a perfectly good interval.
 */
static void test_double_fields(void)
{
    printf("fractional profile fields\n");
    static const char line[] =
        "{\"a\":0.5,\"b\":-2.25,\"c\":\"1.5\",\"d\":1e3,\"e\":\"x\",\"f\":true}";
    const size_t len = strlen(line);

    double v = 0;
    CHECK(wire_json_double(line, len, "a", &v) && v == 0.5, "0.5 was not read");
    CHECK(wire_json_double(line, len, "b", &v) && v == -2.25, "a negative was not read");
    CHECK(wire_json_double(line, len, "c", &v) && v == 1.5, "a quoted number was not read");
    CHECK(wire_json_double(line, len, "d", &v) && v == 1000.0, "an exponent was not read");
    CHECK(!wire_json_double(line, len, "e", &v), "a string was read as a number");
    CHECK(!wire_json_double(line, len, "f", &v), "a boolean was read as a number");
    CHECK(!wire_json_double(line, len, "z", &v), "an absent key was reported present");

    /* NaN and infinity are not durations, and one reaching the state machine
     * would poison every comparison it took part in - including the clamp that
     * is supposed to catch bad values. */
    static const char nasty[] = "{\"a\":\"nan\",\"b\":\"inf\",\"c\":\"1.5x\"}";
    CHECK(!wire_json_double(nasty, strlen(nasty), "a", &v), "NaN was accepted");
    CHECK(!wire_json_double(nasty, strlen(nasty), "b", &v), "infinity was accepted");
    CHECK(!wire_json_double(nasty, strlen(nasty), "c", &v), "trailing junk was accepted");
}

/*
 * The raw console's two halves: hex in both directions, and the line that
 * carries the exchange.
 *
 * Hex is not interesting code, which is exactly why it is worth testing: a
 * console whose whole promise is "these are the bytes, untouched" is broken by
 * any silent mangling, and a wrong nibble looks like a sensor fault rather than
 * a bug here.
 */
static void test_raw_hex_round_trips(void)
{
    printf("raw console hex\n");

    static const char *const frames[] = {
        PY_RAW_REQUEST_HEX, PY_RAW_RESPONSE_HEX, PY_RAW_STUFFED_HEX,
    };
    for (size_t i = 0; i < sizeof(frames) / sizeof(frames[0]); i++) {
        uint8_t bytes[WIRE_RAW_MAX_BYTES];
        const int n = wire_hex_decode(frames[i], bytes, sizeof(bytes));
        CHECK(n > 0, "%s did not decode", frames[i]);
        CHECK((size_t)n == strlen(frames[i]) / 2, "%s decoded to %d bytes", frames[i], n);

        char back[2 * WIRE_RAW_MAX_BYTES + 1];
        CHECK(wire_hex_encode(bytes, (size_t)n, back, sizeof(back)) == 2 * n,
              "%s did not re-encode to the same length", frames[i]);
        CHECK_STR(back, frames[i], "hex round trip");
    }

    /* Spaces are how a person types a frame, and the daemon strips them before
     * bytes.fromhex; upper case is how a datasheet prints one. */
    uint8_t bytes[WIRE_RAW_MAX_BYTES];
    CHECK(wire_hex_decode("7e 00 00 03 00 02 fd 7e", bytes, sizeof(bytes)) == 8,
          "a spaced frame was rejected");
    CHECK(wire_hex_decode("7E0000030002FD7E", bytes, sizeof(bytes)) == 8,
          "upper-case hex was rejected");
    char back[32];
    CHECK(wire_hex_encode(bytes, 8, back, sizeof(back)) == 16, "encode of 8 bytes");
    CHECK_STR(back, "7e0000030002fd7e", "upper case is normalised to lower");

    /* Everything that must be refused rather than half-sent. */
    CHECK(wire_hex_decode("7e0", bytes, sizeof(bytes)) < 0, "an odd digit count was accepted");
    CHECK(wire_hex_decode("7e0g", bytes, sizeof(bytes)) < 0, "a non-hex digit was accepted");
    CHECK(wire_hex_decode("7e:00", bytes, sizeof(bytes)) < 0, "a separator was accepted");
    CHECK(wire_hex_decode("", bytes, sizeof(bytes)) == 0, "an empty string is zero bytes");
    CHECK(wire_hex_decode("7e0000", bytes, 2) < 0, "an over-long frame was accepted");

    /* An output buffer one byte short must produce nothing, not a truncated
     * frame: half a frame reported as a whole one is a lie about the wire. */
    char tight[16];
    CHECK(wire_hex_encode(bytes, 8, tight, sizeof(tight)) < 0,
          "encode wrote into a buffer with no room for the NUL");
}

/**
 * The three preserved-alert messages, byte for byte against `wire.py`.
 *
 * Worth pinning rather than eyeballing because two of the three are read by an
 * app that has to place their rows into an existing recording. A key order or a
 * cell shape that drifted from `file_data` would put alert rows somewhere
 * plausible and wrong, and the whole design exists so alert data is the part
 * that cannot be lost or misfiled.
 */
static void test_alert_messages_match_python(void)
{
    printf("preserved alert data: alerts, alert_segment and alert_data\n");
    char line[WIRE_LINE_MAX];

    float thresholds[SPS30_MEASUREMENT_FIELDS] = {0};
    thresholds[STORE_ALERT_FIELD_PM2_5] = 20.0f;
    const wire_alerts_t alerts = {
        .ready = true,
        .segments = 7,
        .pressure = 0.625,
        .rate_floor_s = 60.0,
        .wraps = 11,
        .thresholds = thresholds,
        .threshold_count = SPS30_MEASUREMENT_FIELDS,
    };
    CHECK(wire_alerts_line(line, sizeof(line), &alerts) > 0, "alerts would not fit");
    CHECK_STR(line, PY_ALERTS_LINE, "alerts");

    /* Nothing watched is an empty object, not ten zeroes. */
    float none[SPS30_MEASUREMENT_FIELDS] = {0};
    const wire_alerts_t quiet = {
        .ready = true,
        .segments = 0,
        .pressure = 0.0,
        .rate_floor_s = 0.0,
        .wraps = 0,
        .thresholds = none,
        .threshold_count = SPS30_MEASUREMENT_FIELDS,
    };
    CHECK(wire_alerts_line(line, sizeof(line), &quiet) > 0, "a quiet alerts line did not fit");
    CHECK(strstr(line, "\"thresholds\":{}") != NULL,
          "watching nothing did not encode as an empty object: %s", line);

    const wire_alert_segment_t segment = {
        .seq = 42,
        .session = PY_ALERT_SESSION,
        .field = "pm2_5",
        .threshold = 20.0,
        .start = PY_ALERT_START,
        .stop = PY_ALERT_STOP,
        .first = 21.5,
        .last = 19.25,
        .midpoint = 44.125,
        .mean = 33.75,
        .variance = 12.5,
        .minimum = 18.0,
        .maximum = 91.5,
        .points = 100,
        .first_index = 3745,
        .last_index = 3844,
        .wrap = 11,
        .has_body = true,
    };
    CHECK(wire_alert_segment_line(line, sizeof(line), &segment) > 0,
          "alert_segment would not fit in a %d-byte line", WIRE_LINE_MAX);
    CHECK_STR(line, PY_ALERT_SEGMENT_LINE, "alert_segment");

    /* The case the header exists for: the samples are gone and the summary is
     * still a complete account of the event. */
    wire_alert_segment_t reduced = segment;
    reduced.seq = 43;
    reduced.has_body = false;
    CHECK(wire_alert_segment_line(line, sizeof(line), &reduced) > 0, "reduced would not fit");
    CHECK_STR(line, PY_ALERT_SEGMENT_REDUCED_LINE, "alert_segment with its samples gone");

    /* And the rows, in `file_data`'s exact shape, because that is what lets the
     * client fold them into the session through the code it already has. */
    sps30_measurement_t values;
    const float raw[SPS30_MEASUREMENT_FIELDS] = {21.5f,  44.25f, 47.0f,   48.5f,   100.0f,
                                                 110.5f, 120.25f, 130.0f, 140.75f, 0.75f};
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        values.values[f] = raw[f];
    }
    char big[2048];
    wire_alert_data_t ad;
    CHECK(wire_alert_data_open(&ad, big, sizeof(big), 42, PY_ALERT_SESSION) == 0,
          "alert_data would not open");
    CHECK(wire_alert_data_row(&ad, 3745, PY_ALERT_START, &values, ""), "the row did not fit");
    CHECK(wire_alert_data_close(&ad, true) > 0, "alert_data would not close");
    CHECK_STR(big, PY_ALERT_DATA_LINE, "alert_data");
}

/**
 * The two drain messages, byte for byte against `wire.py`.
 *
 * Pinned harder than most, because a cell of `drain_data` is not only shown to a
 * user: the device computes a digest over these exact strings and the controller
 * frees flash when that digest matches its own. A cell that lost a digit between
 * the two implementations would fail a copy that is perfectly complete - and, in
 * the direction that costs data, could agree about the wrong thing.
 */
static void test_drain_messages_match_python(void)
{
    printf("the drain: drain_state, and drain_data in both of its shapes\n");
    char line[WIRE_LINE_MAX];

    const wire_drain_state_t state = {
        .state = WIRE_DRAIN_STATE_OFFERED,
        .message = "",
        .block = 17,
        .kind = WIRE_DRAIN_KIND_RING,
        .final = false,
        .rows = 328,
        .digest = 0x0a1b2c3du,
        .blocks_left = 461,
        .blocks_freed = 99,
        .blocks_kept = 0,
        .blocks_refused = 0,
        .readings_copied = 32472,
        .segments_copied = 6,
        .readings_freed = 32144,
    };
    CHECK(wire_drain_state_line(line, sizeof(line), &state) > 0, "drain_state would not fit");
    CHECK_STR(line, PY_DRAIN_STATE_LINE, "drain_state");

    sps30_measurement_t values;
    const float raw[SPS30_MEASUREMENT_FIELDS] = {21.5f,  44.25f,  47.0f,  48.5f,   100.0f,
                                                 110.5f, 120.25f, 130.0f, 140.75f, 0.75f};
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        values.values[f] = raw[f];
    }

    char big[4096];
    wire_drain_data_t dd;
    CHECK(wire_drain_data_open(&dd, big, sizeof(big), 17, WIRE_DRAIN_KIND_RING) == 0,
          "drain_data would not open");
    /* A dated reading and a clockless one in the same block, which is the case
     * one column list has to describe: a sector of the ring can hold pages
     * written either side of the moment a clock arrived. */
    CHECK(wire_drain_data_row(&dd, PY_DRAIN_SESSION, 3745, PY_DRAIN_TIMESTAMP, &values, "",
                              false, 0, 0),
          "the dated row did not fit");
    CHECK(wire_drain_data_row(&dd, "SPS30-R00000007-0012D450.csv", 3746, "", &values,
                              WIRE_EVENT_SETTLING, true, 7, 1235009),
          "the clockless row did not fit");
    CHECK(wire_drain_data_close(&dd, true) > 0, "drain_data would not close");
    CHECK_STR(big, PY_DRAIN_DATA_LINE, "drain_data");

    /* The column list, in order, so a reader that maps cells by name and one
     * that maps them by position agree. */
    {
        const size_t want = sizeof(PY_DRAIN_READING_COLUMNS) / sizeof(PY_DRAIN_READING_COLUMNS[0]);
        for (size_t i = 0; i < want; i++) {
            CHECK(strstr(big, PY_DRAIN_READING_COLUMNS[i]) != NULL,
                  "the reading columns are missing %s", PY_DRAIN_READING_COLUMNS[i]);
        }
    }

    /* And the other shape: a block of preserved segment summaries. Its floats
     * are the exact text a reader parses back into the same number, not the two
     * decimals a reading row carries - the digest is over the values. */
    const wire_alert_segment_t segment = {
        .seq = 42,
        .session = PY_DRAIN_SESSION,
        .field = "pm2_5",
        .threshold = 20.0,
        .start = "",
        .stop = "",
        .first = 21.5,
        .last = 19.25,
        .midpoint = 44.125,
        .mean = 33.75,
        .variance = 12.5,
        .minimum = 18.0,
        .maximum = 91.5,
        .points = 100,
        .first_index = 3745,
        .last_index = 3844,
        .wrap = 11,
        .has_body = true,
    };
    CHECK(wire_drain_data_open(&dd, big, sizeof(big), 18, WIRE_DRAIN_KIND_SEGMENTS) == 0,
          "a segments frame would not open");
    CHECK(wire_drain_data_segment(&dd, &segment, PY_DRAIN_START_S, PY_DRAIN_START_S + 99),
          "the summary row did not fit");
    CHECK(wire_drain_data_close(&dd, true) > 0, "a segments frame would not close");
    CHECK_STR(big, PY_DRAIN_SEGMENTS_LINE, "drain_data (segments)");

    {
        const size_t want = sizeof(PY_DRAIN_SEGMENT_COLUMNS) / sizeof(PY_DRAIN_SEGMENT_COLUMNS[0]);
        for (size_t i = 0; i < want; i++) {
            CHECK(strstr(big, PY_DRAIN_SEGMENT_COLUMNS[i]) != NULL,
                  "the segment columns are missing %s", PY_DRAIN_SEGMENT_COLUMNS[i]);
        }
    }

    /* A reading row refuses to be written into a segments frame and the other
     * way round. Both would produce a row of the wrong width under a column
     * list that says otherwise, which is the one corruption a CSV cannot
     * survive - and these rows become CSV on the device. */
    /*
     * The widest row this message can produce, measured, against the bound the
     * encoder decides to leave room for.
     *
     * Asserted as a *length* rather than by filling a buffer and seeing whether
     * it broke. A room check that under-estimates by less than the tail slack
     * does not overflow anything today and is a trap waiting for the next cell
     * to be added - and the failure it eventually causes is not a truncated
     * message: the writer marks itself failed, the frame is dropped, and the
     * block it belonged to never completes, so a copy stalls on a block the
     * sensor is waiting to be told about with nothing on either side saying why.
     *
     * 288 and 420 are `DRAIN_READING_ROW_MAX` and `DRAIN_SEGMENT_ROW_MAX` in
     * `wire.c`. Move a number here only by moving that one.
     */
    {
        char frame[2048]; /* CLIENT_OUT_MAX in net_server.c */
        sps30_measurement_t wide;
        for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
            wide.values[f] = -99999.99f;
        }
        wire_drain_data_t widest;
        CHECK(wire_drain_data_open(&widest, frame, sizeof(frame), 4294967295u,
                                   WIRE_DRAIN_KIND_RING) == 0,
              "a frame with the widest block id would not open");

        /* A dated row spends its width on the timestamp and a clockless one on
         * the two clock cells, so both are measured. */
        size_t before = widest.w.len;
        CHECK(wire_drain_data_row(&widest, "SPS30-R0FFFFFFF-0FFFFFFF.csv", 9999999u,
                                  "2026-08-02T18:47:42-04:00", &wide, WIRE_EVENT_SETTLING,
                                  false, 0, 0),
              "the widest dated row did not fit");
        const size_t dated = widest.w.len - before;
        before = widest.w.len;
        CHECK(wire_drain_data_row(&widest, "SPS30-R0FFFFFFF-0FFFFFFF.csv", 9999999u, "",
                                  &wide, WIRE_EVENT_SETTLING, true, 2147483647u, 4294967295u),
              "the widest clockless row did not fit");
        const size_t clockless = widest.w.len - before;
        CHECK(dated <= 288, "the widest dated row is %u bytes, past the 288 the encoder "
                            "leaves room for", (unsigned)dated);
        CHECK(clockless <= 288, "the widest clockless row is %u bytes, past the 288 the "
                                "encoder leaves room for", (unsigned)clockless);

        uint32_t rows = 2;
        while (wire_drain_data_room(&widest)) {
            if (!wire_drain_data_row(&widest, "SPS30-R0FFFFFFF-0FFFFFFF.csv", 9999999u,
                                     "2026-08-02T18:47:42-04:00", &wide, WIRE_EVENT_SETTLING,
                                     false, 0, 0)) {
                break;
            }
            rows++;
        }
        CHECK(rows > 1, "only %u of the widest rows fit a frame", (unsigned)rows);
        CHECK(wire_drain_data_close(&widest, false) > 0,
              "a frame of the widest rows would not close");

        /* And a summary row, whose floats can each be seventeen significant
         * digits and an exponent where a reading's are four characters. */
        wire_alert_segment_t awkward = segment;
        awkward.session = "SPS30-R0FFFFFFF-0FFFFFFF.csv";
        awkward.field = "typical_size";
        awkward.seq = 4294967295u;
        awkward.points = 4294967295u;
        awkward.first_index = 4294967295u;
        awkward.last_index = 4294967295u;
        awkward.wrap = 4294967295u;
        awkward.threshold = 1.0 / 3.0;
        awkward.first = -1.2345678901234567e-5;
        awkward.last = 9.87654321098765e18;
        awkward.midpoint = 1.0 / 7.0;
        awkward.mean = 2.0 / 3.0;
        awkward.variance = 1.0 / 9.0;
        awkward.minimum = -1.0 / 11.0;
        awkward.maximum = 1.0 / 13.0;
        wire_drain_data_t summaries;
        CHECK(wire_drain_data_open(&summaries, frame, sizeof(frame), 4294967295u,
                                   WIRE_DRAIN_KIND_SEGMENTS) == 0,
              "a summaries frame would not open");
        before = summaries.w.len;
        CHECK(wire_drain_data_segment(&summaries, &awkward, -2147483648LL, 4294967295LL),
              "the widest summary row did not fit");
        const size_t summary = summaries.w.len - before;
        CHECK(summary <= 420, "the widest summary row is %u bytes, past the 420 the encoder "
                              "leaves room for", (unsigned)summary);
        uint32_t written = 1;
        while (wire_drain_data_room(&summaries)) {
            if (!wire_drain_data_segment(&summaries, &awkward, -2147483648LL, 4294967295LL)) {
                break;
            }
            written++;
        }
        CHECK(written > 1, "only %u of the widest summaries fit a frame", (unsigned)written);
        CHECK(wire_drain_data_close(&summaries, false) > 0,
              "a frame of the widest summaries would not close");
    }

    wire_drain_data_t reading_frame;
    CHECK(wire_drain_data_open(&reading_frame, big, sizeof(big), 19, WIRE_DRAIN_KIND_RING) == 0,
          "a ring frame would not open");
    CHECK(!wire_drain_data_segment(&reading_frame, &segment, 0, 0),
          "a summary must not go into a frame of readings");
    wire_drain_data_t segment_frame;
    CHECK(wire_drain_data_open(&segment_frame, big, sizeof(big), 20,
                               WIRE_DRAIN_KIND_SEGMENTS) == 0,
          "a segments frame would not open");
    CHECK(!wire_drain_data_row(&segment_frame, PY_DRAIN_SESSION, 1, "", &values, "", false, 0, 0),
          "a reading must not go into a frame of summaries");
}

/**
 * The storage occupancy map, held to wire.py at both of its sizes.
 *
 * The small line is readable in a failure message; the full-size line is the
 * claim that matters - the whole 4,992-page partition, sections and all,
 * inside the 8 KiB big buffer - and only a vector at the real geometry can
 * hold the C to it. The cells vectors run the shipping packer and the
 * shipping base64 together, because the padding edges (one page, five pages)
 * are where a hand-rolled base64 goes quietly wrong.
 */
static void test_storage_map_matches_python(void)
{
    printf("the storage map: cells, a young board, and the real geometry\n");

    CHECK_STR(WIRE_MSG_STORAGE_MAP, PY_MSG_STORAGE_MAP, "MSG_STORAGE_MAP");
    CHECK_STR(WIRE_CMD_STORAGE_MAP, PY_CMD_STORAGE_MAP, "CMD_STORAGE_MAP");
    CHECK_STR(WIRE_MAP_SECTION_RING, PY_MAP_SECTION_RING, "MAP_SECTION_RING");
    CHECK_STR(WIRE_MAP_SECTION_ALERT_HEADERS, PY_MAP_SECTION_ALERT_HEADERS,
              "MAP_SECTION_ALERT_HEADERS");
    CHECK_STR(WIRE_MAP_SECTION_ALERT_BODIES, PY_MAP_SECTION_ALERT_BODIES,
              "MAP_SECTION_ALERT_BODIES");

    /* Every cells vector, through the shipping packer and the shipping base64:
     * a one-section frame whose cells string must appear exactly. */
    for (size_t v = 0; v < sizeof(WIRE_CELLS_VECTORS) / sizeof(WIRE_CELLS_VECTORS[0]); v++) {
        const wire_cells_vector_t *vec = &WIRE_CELLS_VECTORS[v];
        uint8_t packed[8];
        memset(packed, 0, sizeof(packed));
        for (size_t i = 0; i < vec->pages; i++) {
            store_map_cell_set(packed, (uint32_t)i, vec->states[i]);
        }
        const wire_map_section_t section = {
            .id = WIRE_MAP_SECTION_RING,
            .start = 0,
            .pages = (uint32_t)vec->pages,
            .head = 0,
            .wraps = 0,
            .cells = packed,
        };
        const wire_storage_map_t map = {
            .page_bytes = 512,
            .pages = (uint32_t)vec->pages,
            .sections = &section,
            .section_count = 1,
        };
        char line[256];
        char fragment[64];
        CHECK(wire_storage_map_line(line, sizeof(line), &map) > 0,
              "cells vector %zu would not encode", v);
        snprintf(fragment, sizeof(fragment), "\"cells\":\"%s\"", vec->expected);
        CHECK(strstr(line, fragment) != NULL,
              "cells vector %zu: wanted %s in %s", v, fragment, line);
    }

    /* The young board, byte for byte against wire.py, then every buffer size
     * up to the full line: a partial frame is worse than none. */
    uint8_t ring_cells[STORE_MAP_CELLS_BYTES(12)];
    memset(ring_cells, 0, sizeof(ring_cells));
    const uint8_t young_ring[12] = {
        STORE_MAP_PAGE_DATA, STORE_MAP_PAGE_DATA, STORE_MAP_PAGE_ANCHOR,
        STORE_MAP_PAGE_DATA, STORE_MAP_PAGE_BAD, STORE_MAP_PAGE_EMPTY,
        STORE_MAP_PAGE_EMPTY, STORE_MAP_PAGE_EMPTY, STORE_MAP_PAGE_EMPTY,
        STORE_MAP_PAGE_EMPTY, STORE_MAP_PAGE_EMPTY, STORE_MAP_PAGE_EMPTY,
    };
    for (uint32_t i = 0; i < 12; i++) {
        store_map_cell_set(ring_cells, i, young_ring[i]);
    }
    uint8_t header_cells[STORE_MAP_CELLS_BYTES(4)];
    memset(header_cells, 0, sizeof(header_cells));
    store_map_cell_set(header_cells, 0, STORE_MAP_PAGE_DATA);
    uint8_t body_cells[STORE_MAP_CELLS_BYTES(4)];
    memset(body_cells, 0, sizeof(body_cells));
    const wire_map_section_t young[] = {
        { .id = WIRE_MAP_SECTION_RING, .start = 0, .pages = 12, .head = 5,
          .wraps = 0, .cells = ring_cells },
        { .id = WIRE_MAP_SECTION_ALERT_HEADERS, .start = 12, .pages = 4,
          .head = 1, .wraps = 0, .cells = header_cells },
        { .id = WIRE_MAP_SECTION_ALERT_BODIES, .start = 16, .pages = 4,
          .head = 0, .wraps = 0, .cells = body_cells },
    };
    const wire_storage_map_t young_map = {
        .page_bytes = 512,
        .pages = 20,
        .sections = young,
        .section_count = 3,
    };
    char line[512];
    CHECK(wire_storage_map_line(line, sizeof(line), &young_map) > 0,
          "the young board would not encode");
    CHECK_STR(line, PY_STORAGE_MAP_LINE, "storage_map line");

    const size_t needed = strlen(PY_STORAGE_MAP_LINE);
    for (size_t cap = 1; cap <= needed + 2; cap++) {
        memset(line, 'x', sizeof(line));
        const int n = wire_storage_map_line(line, cap, &young_map);
        if (cap <= needed) {
            CHECK(n == -1, "cap %zu (need %zu) produced %d bytes", cap, needed, n);
            CHECK(line[0] == '\0', "cap %zu left a partial line behind", cap);
        } else {
            CHECK(n == (int)needed, "cap %zu produced %d bytes, wanted %zu", cap, n, needed);
        }
    }
    CHECK(wire_storage_map_line(NULL, 0, &young_map) == -1, "a NULL buffer was accepted");
    CHECK(wire_storage_map_line(line, sizeof(line), NULL) == -1, "a NULL map was accepted");
    const wire_map_section_t no_cells = { .id = "ring", .pages = 4, .cells = NULL };
    const wire_storage_map_t hollow = {
        .page_bytes = 512, .pages = 4, .sections = &no_cells, .section_count = 1,
    };
    CHECK(wire_storage_map_line(line, sizeof(line), &hollow) == -1,
          "a section without cells was accepted");

    /* The real geometry, mid-eighth pass: the same three rules the generator
     * used to build the board, so the two sides describe one flash. */
    static uint8_t full_ring[STORE_MAP_CELLS_BYTES(4480)];
    static uint8_t full_headers[STORE_MAP_CELLS_BYTES(128)];
    static uint8_t full_bodies[STORE_MAP_CELLS_BYTES(384)];
    memset(full_ring, 0, sizeof(full_ring));
    memset(full_headers, 0, sizeof(full_headers));
    memset(full_bodies, 0, sizeof(full_bodies));
    for (uint32_t i = 0; i < 4480; i++) {
        uint8_t state = STORE_MAP_PAGE_DATA;
        if (i >= 1200 && i < 1208) {
            state = STORE_MAP_PAGE_EMPTY; /* the erased sector ahead of the head */
        } else if (i == 4000) {
            state = STORE_MAP_PAGE_BAD;
        } else if (i % 500 == 137) {
            state = STORE_MAP_PAGE_ANCHOR;
        }
        store_map_cell_set(full_ring, i, state);
    }
    for (uint32_t i = 0; i < 9; i++) {
        store_map_cell_set(full_headers, i, STORE_MAP_PAGE_DATA);
    }
    for (uint32_t i = 0; i < 37; i++) {
        store_map_cell_set(full_bodies, i, STORE_MAP_PAGE_DATA);
    }
    const wire_map_section_t sections[] = {
        { .id = WIRE_MAP_SECTION_RING, .start = 0, .pages = 4480, .head = 1200,
          .wraps = 7, .cells = full_ring },
        { .id = WIRE_MAP_SECTION_ALERT_HEADERS, .start = 4480, .pages = 128,
          .head = 9, .wraps = 0, .cells = full_headers },
        { .id = WIRE_MAP_SECTION_ALERT_BODIES, .start = 4608, .pages = 384,
          .head = 37, .wraps = 0, .cells = full_bodies },
    };
    const wire_storage_map_t full_map = {
        .page_bytes = 512,
        .pages = 4992,
        .sections = sections,
        .section_count = 3,
    };
    static char big[8192];
    const int n = wire_storage_map_line(big, sizeof(big), &full_map);
    CHECK(n > 0, "the full-size map would not fit the big buffer");
    CHECK_STR(big, PY_STORAGE_MAP_FULL_LINE, "full-size storage_map line");
    CHECK(n == (int)strlen(PY_STORAGE_MAP_FULL_LINE), "full-size length drifted");
}

static void test_raw_response_line_matches_python(void)
{
    printf("raw_response line\n");

    char line[WIRE_LINE_MAX];
    const int n = wire_raw_response_line(line, sizeof(line), PY_RAW_REQUEST_HEX,
                                         PY_RAW_RESPONSE_HEX);
    CHECK(n > 0, "the line did not fit");
    CHECK_STR(line, PY_RAW_RESPONSE_LINE, "raw_response");

    /* The longest exchange the bound allows still has to fit the line every
     * transport frames on; that is the whole argument for WIRE_RAW_MAX_BYTES. */
    char longest[2 * WIRE_RAW_MAX_BYTES + 1];
    for (size_t i = 0; i < sizeof(longest) - 1; i++) {
        longest[i] = 'f';
    }
    longest[sizeof(longest) - 1] = '\0';
    CHECK(wire_raw_response_line(line, sizeof(line), longest, longest) > 0,
          "two %d-byte frames did not fit in a %d-byte line", WIRE_RAW_MAX_BYTES,
          WIRE_LINE_MAX);
}

/**
 * Relative time on the wire, against the Python that defines it.
 *
 * Three things, and the first is the one that matters: a reading from a session
 * recorded with no clock carries an **empty** timestamp and the board's own
 * clock beside it. Emitting a plausible instant there would be believed by every
 * consumer, which is the whole failure this feature exists to avoid.
 */
static void test_relative_time_matches_python(void)
{
    printf("relative time matches wire.py\n");

    char name[WIRE_CSV_NAME_LEN];
    for (size_t i = 0; i < sizeof(WIRE_REL_NAME_VECTORS) / sizeof(WIRE_REL_NAME_VECTORS[0]);
         i++) {
        const wire_rel_name_vector_t *v = &WIRE_REL_NAME_VECTORS[i];
        CHECK(wire_rel_csv_name(v->run, v->start_ms, name, sizeof(name)) > 0,
              "rel name %u did not fit", (unsigned)i);
        CHECK_STR(name, v->expected, "rel session name");
    }

    uint32_t back_run = 0, back_ms = 0;
    CHECK(wire_parse_rel_csv_name("SPS30-R00000356-00001925.csv", &back_run, &back_ms),
          "a relative name did not read back");
    CHECK(back_run == 0x356u && back_ms == 0x1925u, "read back %u/%u",
          (unsigned)back_run, (unsigned)back_ms);
    CHECK(!wire_parse_rel_csv_name("SPS30-20260803_004512.csv", NULL, NULL),
          "a dated name was read as a relative one");
    CHECK(!wire_parse_rel_csv_name("SPS30-Rzzzzzzzz-00001925.csv", NULL, NULL),
          "a name with non-hex digits was accepted");

    wire_anchor_t anchors[PY_TIME_MAP_COUNT];
    for (size_t i = 0; i < PY_TIME_MAP_COUNT; i++) {
        anchors[i].run = WIRE_ANCHOR_VECTORS[i].run;
        anchors[i].rel_ms = WIRE_ANCHOR_VECTORS[i].rel_ms;
        anchors[i].epoch_ms = WIRE_ANCHOR_VECTORS[i].epoch_ms;
        anchors[i].utc_offset_min = WIRE_ANCHOR_VECTORS[i].utc_offset_min;
    }
    char line[WIRE_LINE_MAX];
    CHECK(wire_time_map_line(line, sizeof(line), PY_TIME_MAP_RUN, anchors,
                             PY_TIME_MAP_COUNT) > 0,
          "time_map did not fit");
    CHECK_STR(line, PY_TIME_MAP_LINE, "time_map");

    /* An empty map is still a message: a client has to be able to tell "this
     * controller has no equivalences" from "this controller never answered". */
    CHECK(wire_time_map_line(line, sizeof(line), 7, anchors, 0) > 0,
          "an empty time_map did not fit");
    CHECK_STR(line, "{\"type\":\"time_map\",\"boot\":7,\"anchors\":[]}\n",
              "an empty time_map");

    sps30_measurement_t values;
    memcpy(values.values, WIRE_REL_READING_VALUES, sizeof(values.values));
    const wire_reading_t reading = {
        .csv = WIRE_REL_READING_CSV,
        .index = WIRE_REL_READING_INDEX,
        .timestamp = "",
        .event = "",
        .values = &values,
        .relative = true,
        .rel_run = WIRE_REL_READING_RUN,
        .rel_ms = WIRE_REL_READING_MS,
    };
    CHECK(wire_reading_line(line, sizeof(line), &reading) > 0,
          "a relative reading did not fit");
    CHECK_STR(line, WIRE_REL_READING_LINE, "relative reading");

    /* And an absolute reading is untouched by any of this - the field is absent,
     * not empty, so an existing controller's output is byte-identical. */
    const wire_reading_t absolute = {
        .csv = "SPS30-20260801_154512.csv",
        .index = 1,
        .timestamp = "2026-08-01T15:45:12-04:00",
        .event = "",
        .values = &values,
    };
    CHECK(wire_reading_line(line, sizeof(line), &absolute) > 0, "absolute reading");
    CHECK(strstr(line, WIRE_COL_REL_MS) == NULL,
          "an absolute reading carried a relative clock: %s", line);

    /* file_list says `relative` only when it is true, for the same reason. */
    const wire_file_entry_t files[] = {
        {"SPS30-R00000356-00001925.csv", 41, 8923, "", true},
    };
    CHECK(wire_file_list_line(line, sizeof(line), files, 1, NULL) > 0, "relative file_list");
    CHECK_STR(line,
              "{\"type\":\"file_list\",\"files\":["
              "{\"name\":\"SPS30-R00000356-00001925.csv\",\"rows\":41,"
              "\"bytes\":8923,\"start\":\"\",\"relative\":true}],"
              "\"offset\":0,\"total\":1}\n",
              "a relative file_list entry");

    /* A relative transfer's rows carry two extra named columns and no instant. */
    char frame[2048];
    wire_file_data_t fd;
    CHECK(wire_file_data_open(&fd, frame, sizeof(frame), "SPS30-R00000356-00001925.csv",
                              true) == 0,
          "could not open a relative file_data frame");
    CHECK(wire_file_data_row(&fd, 42, "", &values, "", 854u, 21437u), "relative row");
    CHECK(wire_file_data_close(&fd, true) > 0, "could not close the frame");
    CHECK(strstr(frame, "\"rel_boot\",\"rel_ms\"") != NULL,
          "the relative columns are missing from %s", frame);
    CHECK(strstr(frame, "\"854\",\"21437\"") != NULL,
          "the relative cells are missing from %s", frame);
    /* And exactly wire.CSV_COLUMNS_RELATIVE, which is what a phone writes at the
     * top of the file it keeps this session in. The two extra columns come last,
     * after `event`, so the thirteen before them are where they have always been
     * - the whole reason this could be added without breaking an older reader. */
    CHECK(strstr(frame, "\"columns\":" PY_CSV_COLUMNS_RELATIVE_JSON) != NULL,
          "the columns are not wire.CSV_COLUMNS_RELATIVE in '%s'", frame);
    CHECK(strncmp(PY_CSV_HEADER_RELATIVE, PY_CSV_HEADER, strlen(PY_CSV_HEADER)) == 0,
          "the relative header is not the classic one with columns appended");
}

/**
 * ::wire_format_fixed against `snprintf`, which is the only thing it may be.
 *
 * A backfilled row is ten of these, and replacing the ten `printf` float
 * conversions with integer arithmetic is a **formatting** change only for as
 * long as the bytes are identical - the moment they are not it is a protocol
 * change, and the cross-language vectors above are what that would break.
 *
 * So: the values a row actually carries (the store's quanta, dequantised), the
 * edges where rounding decides, and the range where the exact path hands back
 * to `printf`. `slow_wire_format` then does the same thing for every float32
 * there is; this is the part fast enough to run every time.
 */
static void test_fixed_formatting_is_printf_to_the_byte(void)
{
    printf("fixed-point formatting is printf, to the byte\n");
    char mine[32];
    char theirs[32];

    /* Every value a stored reading can hold, at the spacing the store keeps it
     * at: `store_dequantise` is `quanta * 0.01f` (0.001f for particle size), so
     * this is the domain of the ten conversions in a `file_data` row. */
    for (int32_t quanta = -200000; quanta <= 200000; quanta += 1) {
        const float two = (float)((double)quanta * 0.01);
        const float three = (float)((double)quanta * 0.001);
        if (!wire_format_fixed(two, 2, mine, sizeof(mine))) {
            CHECK(false, "the exact path refused %d hundredths", quanta);
            continue;
        }
        snprintf(theirs, sizeof(theirs), "%.2f", (double)two);
        if (strcmp(mine, theirs) != 0) {
            CHECK(false, "%%.2f of %d hundredths: want %s, got %s", quanta, theirs, mine);
            continue;
        }
        if (!wire_format_fixed(three, 3, mine, sizeof(mine))) {
            CHECK(false, "the exact path refused %d thousandths", quanta);
            continue;
        }
        snprintf(theirs, sizeof(theirs), "%.3f", (double)three);
        if (strcmp(mine, theirs) != 0) {
            CHECK(false, "%%.3f of %d thousandths: want %s, got %s", quanta, theirs, mine);
            continue;
        }
    }
    CHECK(true, "the store's whole quantised domain agrees with printf");

    /* The ones worth naming. `x.125` is an exact tie at two decimals and the
     * only kind there is, since a tie needs the double to be a multiple of 1/8;
     * `-0.001` is the negative zero printf writes with a sign. */
    const struct {
        float value;
        unsigned decimals;
    } named[] = {
        {0.0f, 2},        {-0.0f, 2},       {0.125f, 2},      {0.375f, 2},
        {-0.125f, 2},     {2.675f, 2},      {0.005f, 2},      {-0.005f, 2},
        {0.0001f, 2},     {-0.0001f, 3},    {9.9999f, 2},     {-9.9999f, 3},
        {1e-45f, 2},      {-1e-45f, 3},     {1e-38f, 3},      {16777216.0f, 2},
        {8388608.5f, 2},  {0.0625f, 3},     {1000.005f, 2},   {65535.996f, 3},
    };
    for (size_t i = 0; i < sizeof(named) / sizeof(named[0]); i++) {
        CHECK(wire_format_fixed(named[i].value, named[i].decimals, mine, sizeof(mine)),
              "the exact path refused a named case at index %zu", i);
        snprintf(theirs, sizeof(theirs), named[i].decimals == 3 ? "%.3f" : "%.2f",
                 (double)named[i].value);
        CHECK_STR(mine, theirs, "a named rounding case");
    }

    /* And where it must refuse rather than guess. Refusing is not a gap: the
     * caller falls back to `snprintf`, so these still go on the wire exactly as
     * they always have. */
    CHECK(!wire_format_fixed(INFINITY, 2, mine, sizeof(mine)), "inf was formatted");
    CHECK(!wire_format_fixed(-INFINITY, 2, mine, sizeof(mine)), "-inf was formatted");
    CHECK(!wire_format_fixed(NAN, 2, mine, sizeof(mine)), "nan was formatted");
    CHECK(!wire_format_fixed(1.0e30f, 2, mine, sizeof(mine)), "1e30 was formatted");
    CHECK(!wire_format_fixed(1.0f, 4, mine, sizeof(mine)), "a fourth decimal was accepted");
    CHECK(!wire_format_fixed(1.0f, 2, mine, 8), "a buffer too small was written to");

    /* The handover point, from both sides: 2^44 is where the exact path stops. */
    CHECK(wire_format_fixed(17592186044416.0f, 2, mine, sizeof(mine)) == false,
          "2^44 was taken by the exact path");
    CHECK(wire_format_fixed(8796093022208.0f, 2, mine, sizeof(mine)),
          "2^43 was refused by the exact path");
    snprintf(theirs, sizeof(theirs), "%.2f", 8796093022208.0);
    CHECK_STR(mine, theirs, "the largest value the exact path takes");
}

int main(void)
{
    printf("wire protocol host tests\n");
    printf("cross-checked byte-for-byte against the repository's wire.py\n\n");

    test_reading_lines_match_python();
    test_timestamps_match_python();
    test_index_matches_python();
    test_vocabulary_matches_python();
    test_profile_matches_python();
    test_hello();
    test_power_and_clean_due();
    test_state_control_clients_error();
    test_capacity_is_per_transport();
    test_escaping();
    test_truncation_produces_nothing();
    test_non_finite_becomes_null();
    test_command_reader();
    test_framing();
    test_iso_round_trips();
    test_iso_rejects_anything_else();
    test_csv_name_round_trips();
    test_csv_name_rejects_anything_else();
    test_relative_time_matches_python();
    test_rows_survive_the_round_trip();
    test_row_reader_is_strict_where_it_must_be();
    test_a_backfill_frame_carries_thirteen_readings();
    test_transfer_messages();
    test_boolean_fields();
    test_double_fields();
    test_nested_profile();
    test_raw_hex_round_trips();
    test_alert_messages_match_python();
    test_drain_messages_match_python();
    test_storage_map_matches_python();
    test_raw_response_line_matches_python();
    test_fixed_formatting_is_printf_to_the_byte();

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    if (g_failures == 0) {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL\n");
    return 1;
}

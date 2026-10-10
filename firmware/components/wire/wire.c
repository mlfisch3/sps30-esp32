/**
 * @file wire.c
 * @brief JSONL encoding and decoding for the daemon protocol. See `wire.h`.
 *
 * Two things in here look like more work than the job needs, and both are
 * deliberate.
 *
 * **Numbers are formatted the way Python's `json.dumps` formats them** -
 * shortest representation that round-trips, with a forced `.0` on anything
 * integral. That is not cosmetic. It is what lets `test_wire.c` compare this
 * encoder's output against `wire.py`'s **byte for byte**, which is the standard
 * the rest of this firmware is already held to (see `test/gen_vectors.py`). A
 * tolerance-based comparison would pass while the two implementations drifted.
 *
 * **The JSON reader is a scanner, not a parser.** The commands this controller
 * accepts are short objects with a handful of scalar fields. Pulling in a parser
 * and an allocator to read `{"cmd":"pause"}` off a socket would cost more code,
 * more heap and more attack surface than the whole feature. Exactly one command
 * nests - `set_profile` puts its settings in `{"profile":{...}}` - and it is
 * served by running the same flat scan twice, once over the frame to borrow the
 * inner object and once over that; see ::wire_json_object.
 */

#include "wire.h"

#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* --- bounded appending ---------------------------------------------------- */

/*
 * The writer is `wire_writer_t`, declared in wire.h because `file_data` is
 * built a row at a time by a caller that has to hold one between calls. `wr_t`
 * is the shorter spelling this file has always used for it.
 */
typedef wire_writer_t wr_t;

static void wr_init(wr_t *w, char *buf, size_t cap)
{
    w->buf = buf;
    w->cap = cap;
    w->len = 0;
    w->ok = (buf != NULL && cap > 0);
    if (w->ok) {
        buf[0] = '\0';
    }
}

static void wr_char(wr_t *w, char c)
{
    if (!w->ok) {
        return;
    }
    if (w->len + 2 > w->cap) { /* the byte plus its NUL */
        w->ok = false;
        return;
    }
    w->buf[w->len++] = c;
    w->buf[w->len] = '\0';
}

static void wr_raw(wr_t *w, const char *s)
{
    if (s == NULL) {
        return;
    }
    for (; *s != '\0'; s++) {
        wr_char(w, *s);
    }
}

/**
 * Append @p s as a quoted JSON string.
 *
 * `json.dumps` defaults to `ensure_ascii=True` and would escape non-ASCII as
 * `\uXXXX`; this passes UTF-8 through unchanged. That is the **one** place the
 * encoders knowingly differ from `wire.py`, both forms are valid JSON and decode
 * to the same string, and it can only show up in a client's display name -
 * nothing in the measurement path can carry a non-ASCII byte. Escaping it
 * properly would mean decoding UTF-8 here to re-encode it as UTF-16 escapes,
 * which is a lot of machinery for a cosmetic match on one field.
 */
static void wr_json_string(wr_t *w, const char *s)
{
    wr_char(w, '"');
    for (const unsigned char *p = (const unsigned char *)(s ? s : ""); *p != '\0'; p++) {
        switch (*p) {
        case '"': wr_raw(w, "\\\""); break;
        case '\\': wr_raw(w, "\\\\"); break;
        case '\n': wr_raw(w, "\\n"); break;
        case '\r': wr_raw(w, "\\r"); break;
        case '\t': wr_raw(w, "\\t"); break;
        case '\b': wr_raw(w, "\\b"); break;
        case '\f': wr_raw(w, "\\f"); break;
        default:
            if (*p < 0x20) {
                char esc[7];
                snprintf(esc, sizeof(esc), "\\u%04x", (unsigned)*p);
                wr_raw(w, esc);
            } else {
                wr_char(w, (char)*p);
            }
            break;
        }
    }
    wr_char(w, '"');
}

/**
 * `"key":` with `json.dumps`'s compact separators, comma included when needed.
 *
 * Whether a comma is needed is read off the last byte written rather than
 * tracked in a field, which is what makes nesting work: the first key of a
 * freshly opened `{` follows a brace and takes no comma, at any depth.
 */
static void wr_key(wr_t *w, const char *key)
{
    if (w->ok && w->len > 0) {
        const char last = w->buf[w->len - 1];
        if (last != '{' && last != '[') {
            wr_char(w, ',');
        }
    }
    wr_json_string(w, key);
    wr_char(w, ':');
}

/**
 * Append @p v exactly as `json.dumps` would, which is `repr(float)`.
 *
 * Two rules, and both were found by the cross-check rather than reasoned out in
 * advance - `%g` gets each of them wrong:
 *
 * 1. **The shortest digit string that reads back as the same double.** Found by
 *    trying `%.*e` from one significant digit upwards until `strtod` returns the
 *    value again. `%.16e` always round-trips, so the search terminates.
 * 2. **Fixed notation unless the decimal point sits outside a window**, which
 *    CPython's `format_float_short` writes as `decpt <= -4 || decpt > 16`.
 *    `%g` uses the *requested precision* to make that choice instead, so it
 *    renders 10.0 as `1e+01` and a million as `1e+06`, where Python writes
 *    `10.0` and `1000000.0`. Both are valid JSON and both parse to the same
 *    number, which is exactly why this had to be tested rather than eyeballed.
 *
 * The trailing `.0` on an integral value is Python's too: `repr(1.0)` is "1.0".
 *
 * Non-finite values become `null`. JSON has no NaN or Infinity, Python would
 * emit the invalid literal `NaN`, and the app's parser turns a null into a
 * missing field, which is the right reading of "the sensor did not give a usable
 * number". Acquisition already discards implausible frames, so this is a
 * backstop rather than a path.
 */
/**
 * The text above, into a buffer rather than onto a writer.
 *
 * Split out so `drain_data` can put the very same digits inside a JSON *string*
 * cell. That message digests the values themselves rather than a quantised copy
 * of them, so its cells have to be the shortest text that reads back as the same
 * number - and there must be exactly one piece of code that decides what that
 * text is, or the number in a cell and the number in a field would eventually
 * differ in their last digit.
 *
 * @return false for a value that is not finite, which has no representation
 *         here; the caller writes `null` or an empty cell as its message
 *         requires. @p out is then untouched.
 */
static bool format_double(char *out, size_t cap, double v)
{
    if (!isfinite(v)) {
        return false;
    }

    char tmp[48];
    int digits = 1;
    for (; digits <= 17; digits++) {
        snprintf(tmp, sizeof(tmp), "%.*e", digits - 1, v);
        if (strtod(tmp, NULL) == v) {
            break;
        }
    }
    if (digits > 17) {
        digits = 17; /* tmp already holds the %.16e form, which always fits */
    }

    /* Where the decimal point falls, counting digits before it. */
    const char *exponent = strchr(tmp, 'e');
    const int decimal_point = exponent != NULL ? atoi(exponent + 1) + 1 : 1;

    if (decimal_point <= -4 || decimal_point > 16) {
        /* tmp is already the exponential form Python would print, with the
         * shortest mantissa and a two-digit exponent. */
        snprintf(out, cap, "%s", tmp);
        return true;
    }

    int decimals = digits - decimal_point;
    if (decimals < 0) {
        decimals = 0;
    }
    snprintf(tmp, sizeof(tmp), "%.*f", decimals, v);
    if (strchr(tmp, '.') == NULL) {
        snprintf(out, cap, "%s.0", tmp);
    } else {
        snprintf(out, cap, "%s", tmp);
    }
    return true;
}

static void wr_double(wr_t *w, double v)
{
    char tmp[52];
    if (!format_double(tmp, sizeof(tmp), v)) {
        wr_raw(w, "null");
        return;
    }
    wr_raw(w, tmp);
}

static void wr_int(wr_t *w, long long v)
{
    char tmp[24];
    snprintf(tmp, sizeof(tmp), "%lld", v);
    wr_raw(w, tmp);
}

static void wr_bool(wr_t *w, bool v)
{
    wr_raw(w, v ? "true" : "false");
}

/** Close the object, add the JSONL newline, and report the outcome. */
static int wr_finish(wr_t *w)
{
    wr_char(w, '}');
    wr_char(w, '\n');
    if (!w->ok) {
        if (w->buf != NULL && w->cap > 0) {
            w->buf[0] = '\0';
        }
        return -1;
    }
    return (int)w->len;
}

static void wr_begin(wr_t *w, char *out, size_t cap, const char *type)
{
    wr_init(w, out, cap);
    wr_char(w, '{');
    wr_key(w, "type");
    wr_json_string(w, type);
}

/** Empty rather than absent: `strOr` in the app treats a null string as "". */
static const char *or_empty(const char *s)
{
    return s != NULL ? s : "";
}

/* --- encoders ------------------------------------------------------------- */

static void wr_profile_body(wr_t *w, const wire_profile_t *p)
{
    wr_char(w, '{');
    wr_key(w, "mode");
    wr_json_string(w, or_empty(p->mode));
    wr_key(w, "interval");
    wr_double(w, p->interval);
    wr_key(w, "cycle_s");
    wr_double(w, p->cycle_s);
    wr_key(w, "warmup_s");
    wr_double(w, p->warmup_s);
    wr_key(w, "average_s");
    wr_double(w, p->average_s);
    wr_char(w, '}');
}

double wire_clean_due_in_hours(double run_seconds)
{
    /* Not a real number means a corrupt stored count, and this is a decision
     * about which way to fail rather than an arithmetic detail. `wire.py` says
     * zero - due now - and this used to substitute zero for `run_seconds`
     * instead, which reports a full week left. The two controllers therefore
     * failed in opposite directions on the one value whose whole purpose is to
     * warn, and the generated vectors could not see it because they carried no
     * such case. They do now; see `gen_wire_vectors.py`. */
    if (!isfinite(run_seconds)) {
        return 0.0;
    }
    double left = WIRE_CLEAN_AFTER_SECONDS - run_seconds;
    if (!(left > 0.0)) {
        return 0.0;
    }
    /* `round(x, 1)` in the daemon. Scaling by ten and back is the same value for
     * every input this can see: the count is bounded by a week, so the product
     * is at most 1680 and nowhere near the magnitudes where the two disagree.
     * `test_wire.c` holds it against what wire.py actually returns. */
    return round(left / 360.0) / 10.0;
}

/**
 * The `power` payload, shared by `hello` and the standalone message so the two
 * cannot drift into describing the same sensor differently.
 */
static void wr_power_body(wr_t *w, const char *source, double clean_run_seconds)
{
    wr_char(w, '{');
    wr_key(w, "source");
    wr_json_string(w, or_empty(source));
    /* "none" is `wire.py`'s value for "nothing measured this". There is no fuel
     * gauge on this board and no host to interrogate, so claiming a method would
     * be claiming a measurement. `average_ma` is absent for the same reason. */
    wr_key(w, "method");
    wr_json_string(w, "none");
    /* Unlike the draw, this one *is* measured: it is the controller's own count
     * of seconds the fan has run, which is why it is here and the milliamps are
     * not. */
    wr_key(w, WIRE_POWER_CLEAN_DUE_HOURS);
    wr_double(w, wire_clean_due_in_hours(clean_run_seconds));
    wr_char(w, '}');
}

int wire_power_line(char *out, size_t cap, const char *source, double clean_run_seconds)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_POWER);
    wr_key(&w, "power");
    wr_power_body(&w, source, clean_run_seconds);
    return wr_finish(&w);
}

/* --- the modelled power estimate ----------------------------------------- */

const char *const WIRE_POWER_SUBSYSTEMS[WIRE_POWER_SUBSYSTEM_COUNT] = {
    "sensor", "controller", "radio", "storage",
};

const double WIRE_POWER_COMPARE_CYCLES[WIRE_POWER_COMPARE_CYCLE_COUNT] = {
    300.0, 900.0, 1800.0, 3600.0, 43200.0, 86400.0,
};

/**
 * The runtime slot, under `wire.power_runtime_payload`'s rule: an unrecognised
 * status is written as `no_capacity`, and so is an "ok" arriving without a
 * usable number - the slot must never claim to know while carrying nothing.
 */
static void wr_power_runtime(wr_t *w, const wire_power_estimate_t *e)
{
    const char *status = or_empty(e->runtime_status);
    if (strcmp(status, WIRE_POWER_RUNTIME_OK) != 0 &&
        strcmp(status, WIRE_POWER_RUNTIME_NO_CAPACITY) != 0 &&
        strcmp(status, WIRE_POWER_RUNTIME_UNUSABLE_CAPACITY) != 0 &&
        strcmp(status, WIRE_POWER_RUNTIME_NO_DRAW) != 0) {
        status = WIRE_POWER_RUNTIME_NO_CAPACITY;
    }
    if (strcmp(status, WIRE_POWER_RUNTIME_OK) == 0 &&
        (!e->runtime_has_hours || !isfinite(e->runtime_hours) || e->runtime_hours < 0.0)) {
        status = WIRE_POWER_RUNTIME_NO_CAPACITY;
    }

    wr_char(w, '{');
    wr_key(w, "status");
    wr_json_string(w, status);
    if (strcmp(status, WIRE_POWER_RUNTIME_OK) == 0) {
        wr_key(w, "hours");
        wr_double(w, e->runtime_hours);
        wr_key(w, "modelled_hours");
        wr_double(w, e->runtime_modelled_hours);
        wr_key(w, "derating");
        wr_double(w, e->runtime_derating);
    }
    wr_char(w, '}');
}

/** One priced alternative: `wire.power_alternative_payload`'s shape. */
static void wr_power_alternative(wr_t *w, const wire_power_alternative_t *a)
{
    wr_char(w, '{');
    wr_key(w, "mode");
    wr_json_string(w, or_empty(a->mode));
    if (a->mode != NULL && strcmp(a->mode, WIRE_MODE_DUTY_CYCLE) == 0) {
        wr_key(w, "cycle_s");
        wr_double(w, a->cycle_s);
        wr_key(w, "sleeps");
        wr_bool(w, a->sleeps);
    }
    wr_key(w, "total_mw");
    wr_double(w, a->total_mw);
    if (a->has_hours) {
        wr_key(w, "hours");
        wr_double(w, a->hours);
    }
    wr_char(w, '}');
}

int wire_power_estimate_line(char *out, size_t cap, const wire_power_estimate_t *e)
{
    if (e == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_POWER_ESTIMATE);
    wr_key(&w, "basis");
    wr_json_string(&w, WIRE_POWER_BASIS_MODELLED);

    wr_key(&w, "sources");
    wr_char(&w, '{');
    wr_key(&w, "datasheet");
    wr_int(&w, (long long)e->sources_datasheet);
    wr_key(&w, "measured");
    wr_int(&w, (long long)e->sources_measured);
    wr_key(&w, "estimate");
    wr_int(&w, (long long)e->sources_estimate);
    wr_char(&w, '}');

    wr_key(&w, "current");
    wr_char(&w, '{');
    wr_key(&w, "profile");
    wr_profile_body(&w, &e->profile);
    wr_key(&w, "sleeps");
    wr_bool(&w, e->sleeps);
    wr_key(&w, "total_mw");
    wr_double(&w, e->total_mw);
    wr_key(&w, "by_subsystem");
    wr_char(&w, '{');
    for (int i = 0; i < WIRE_POWER_SUBSYSTEM_COUNT; i++) {
        wr_key(&w, WIRE_POWER_SUBSYSTEMS[i]);
        wr_double(&w, e->by_subsystem_mw[i]);
    }
    wr_char(&w, '}');
    wr_char(&w, '}');

    wr_key(&w, "alternatives");
    wr_char(&w, '[');
    for (size_t i = 0; i < e->alternative_count; i++) {
        if (i > 0) {
            wr_char(&w, ',');
        }
        wr_power_alternative(&w, &e->alternatives[i]);
    }
    wr_char(&w, ']');

    wr_key(&w, "runtime");
    wr_power_runtime(&w, e);
    return wr_finish(&w);
}

int wire_hello_line(char *out, size_t cap, const wire_hello_t *h)
{
    if (h == NULL) {
        return -1;
    }
    char index[WIRE_INDEX_LEN];
    wire_format_index(h->index, index, sizeof(index));

    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_HELLO);
    wr_key(&w, "product");
    wr_json_string(&w, or_empty(h->product));
    wr_key(&w, "serial");
    wr_json_string(&w, or_empty(h->serial));
    wr_key(&w, "firmware");
    wr_json_string(&w, or_empty(h->firmware));
    wr_key(&w, "state");
    wr_json_string(&w, or_empty(h->state));
    wr_key(&w, "detail");
    wr_json_string(&w, or_empty(h->detail));
    if (h->seconds >= 0.0) {
        wr_key(&w, "seconds");
        wr_double(&w, h->seconds);
    }
    wr_key(&w, "csv");
    if (h->csv != NULL && h->csv[0] != '\0') {
        wr_json_string(&w, h->csv);
    } else {
        /* No session yet. The daemon sends null here and the app models it as a
         * nullable, so null says "nothing to key de-duplication on" precisely. */
        wr_raw(&w, "null");
    }
    wr_key(&w, "index");
    wr_json_string(&w, index);
    wr_key(&w, "profile");
    wr_profile_body(&w, &h->profile);
    wr_key(&w, "power");
    wr_power_body(&w, h->power_source, h->clean_run_seconds);
    wr_key(&w, WIRE_HELLO_SUPPORTS_SLEEP);
    wr_bool(&w, h->supports_sleep);
    wr_key(&w, WIRE_HELLO_CAN_POWER_OFF);
    wr_bool(&w, h->can_power_off);
    wr_key(&w, WIRE_HELLO_HAS_CLOCK);
    wr_bool(&w, h->has_clock);
    wr_key(&w, "client_id");
    wr_int(&w, h->client_id);
    wr_key(&w, "max_clients");
    wr_int(&w, (long long)h->max_clients);
    /* Omitted when unknown rather than guessed: a client that is told a capacity
     * without being told whose it is has to infer the link, and inferring is
     * exactly what got "1 of 8" onto a Bluetooth screen. */
    if (h->transport != NULL && h->transport[0] != '\0') {
        wr_key(&w, "transport");
        wr_json_string(&w, h->transport);
    }
    return wr_finish(&w);
}

int wire_reading_line(char *out, size_t cap, const wire_reading_t *r)
{
    if (r == NULL || r->values == NULL) {
        return -1;
    }
    char index[WIRE_INDEX_LEN];
    wire_format_index(r->index, index, sizeof(index));

    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_READING);
    wr_key(&w, "csv");
    if (r->csv != NULL && r->csv[0] != '\0') {
        wr_json_string(&w, r->csv);
    } else {
        wr_raw(&w, "null");
    }
    wr_key(&w, "index");
    wr_json_string(&w, index);
    wr_key(&w, "timestamp");
    wr_json_string(&w, or_empty(r->timestamp));
    /* Named from SPS30_FIELD_NAMES so the ten-value order is the decoder's, not
     * a second copy of it. The order is a contract with the CSV, the Kotlin port
     * and the daemon; the host tests assert it against sps30.py's dataclass. */
    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        wr_key(&w, SPS30_FIELD_NAMES[i]);
        wr_double(&w, (double)r->values->values[i]);
    }
    wr_key(&w, "event");
    wr_json_string(&w, or_empty(r->event));
    /* Only on a session recorded with no clock. `timestamp` is then empty rather
     * than invented, and these two say what the board actually knew: which run
     * of its own clock this is, and what that clock read. The client converts
     * through an equivalence; nothing here does arithmetic on its behalf. */
    if (r->relative) {
        wr_key(&w, WIRE_COL_REL_RUN);
        wr_int(&w, (int64_t)r->rel_run);
        wr_key(&w, WIRE_COL_REL_MS);
        wr_int(&w, (int64_t)r->rel_ms);
    }
    return wr_finish(&w);
}

int wire_state_line(char *out, size_t cap, const char *state, const char *detail,
                    double seconds)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_STATE);
    wr_key(&w, "state");
    wr_json_string(&w, or_empty(state));
    wr_key(&w, "detail");
    wr_json_string(&w, or_empty(detail));
    if (seconds >= 0.0) {
        wr_key(&w, "seconds");
        wr_double(&w, seconds);
    }
    return wr_finish(&w);
}

int wire_control_line(char *out, size_t cap, int holder, const char *holder_name,
                      double expires_in)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_CONTROL);
    wr_key(&w, "holder");
    if (holder > 0) {
        wr_int(&w, holder);
    } else {
        wr_raw(&w, "null");
    }
    wr_key(&w, "holder_name");
    if (holder > 0) {
        wr_json_string(&w, or_empty(holder_name));
    } else {
        wr_raw(&w, "null");
    }
    wr_key(&w, "expires_in");
    wr_double(&w, expires_in);
    return wr_finish(&w);
}

int wire_clients_line(char *out, size_t cap, const wire_client_t *clients, size_t count,
                      unsigned max_clients, const char *transport)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_CLIENTS);
    wr_key(&w, "clients");
    wr_char(&w, '[');
    for (size_t i = 0; i < count; i++) {
        if (i > 0) {
            wr_char(&w, ',');
        }
        wr_char(&w, '{');
        wr_key(&w, "id");
        wr_int(&w, clients[i].id);
        wr_key(&w, "name");
        wr_json_string(&w, or_empty(clients[i].name));
        wr_char(&w, '}');
    }
    wr_char(&w, ']');
    wr_key(&w, "max");
    wr_int(&w, (long long)max_clients);
    if (transport != NULL && transport[0] != '\0') {
        wr_key(&w, "transport");
        wr_json_string(&w, transport);
    }
    return wr_finish(&w);
}

int wire_time_map_line(char *out, size_t cap, uint32_t run, const wire_anchor_t *anchors,
                       size_t count)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_TIME_MAP);
    wr_key(&w, "boot");
    wr_int(&w, (long long)run);
    wr_key(&w, "anchors");
    wr_char(&w, '[');
    for (size_t i = 0; i < count; i++) {
        if (i > 0) {
            wr_char(&w, ',');
        }
        wr_char(&w, '{');
        wr_key(&w, "boot");
        wr_int(&w, (long long)anchors[i].run);
        wr_key(&w, WIRE_COL_REL_MS);
        wr_int(&w, (long long)anchors[i].rel_ms);
        wr_key(&w, "epoch_ms");
        wr_int(&w, (long long)anchors[i].epoch_ms);
        wr_key(&w, "utc_offset_min");
        wr_int(&w, (long long)anchors[i].utc_offset_min);
        wr_char(&w, '}');
    }
    wr_char(&w, ']');
    return wr_finish(&w);
}

unsigned wire_max_clients_for(const char *transport)
{
    if (transport != NULL && strcmp(transport, WIRE_TRANSPORT_BLE) == 0) {
        return WIRE_MAX_CLIENTS_BLE;
    }
    return WIRE_MAX_CLIENTS_TCP;
}

int wire_error_line(char *out, size_t cap, const char *message, bool needs_control,
                    bool at_capacity, const char *ref)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_ERROR);
    wr_key(&w, "message");
    wr_json_string(&w, or_empty(message));
    /* Both flags are omitted when false, as the daemon omits them: the app
     * defaults each to false, and an absent flag is the common case. */
    if (needs_control) {
        wr_key(&w, "needs_control");
        wr_bool(&w, true);
    }
    if (at_capacity) {
        wr_key(&w, "at_capacity");
        wr_bool(&w, true);
    }
    /* Omitted when there is nothing to attribute the error to, so the frame is
     * byte-for-byte what it was before the field existed. */
    if (ref != NULL && ref[0] != '\0') {
        wr_key(&w, "ref");
        wr_json_string(&w, ref);
    }
    return wr_finish(&w);
}

int wire_profile_line(char *out, size_t cap, const wire_profile_t *profile)
{
    if (profile == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_PROFILE);
    wr_key(&w, "profile");
    wr_profile_body(&w, profile);
    return wr_finish(&w);
}

int wire_raw_response_line(char *out, size_t cap, const char *request_hex,
                           const char *response_hex)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_RAW);
    wr_key(&w, "request");
    wr_json_string(&w, or_empty(request_hex));
    wr_key(&w, "response");
    wr_json_string(&w, or_empty(response_hex));
    return wr_finish(&w);
}

/* --- hex, for the raw console --------------------------------------------- */

int wire_hex_encode(const uint8_t *bytes, size_t len, char *out, size_t cap)
{
    if (out == NULL || (len > 0 && bytes == NULL) || cap < 2 * len + 1) {
        return -1;
    }
    static const char digits[] = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        out[2 * i] = digits[bytes[i] >> 4];
        out[2 * i + 1] = digits[bytes[i] & 0x0F];
    }
    out[2 * len] = '\0';
    return (int)(2 * len);
}

/** One hex digit's value, or -1. */
static int hex_digit(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

int wire_hex_decode(const char *hex, uint8_t *out, size_t cap)
{
    if (hex == NULL || out == NULL) {
        return -1;
    }
    size_t n = 0;
    int high = -1; /* the first digit of the byte being assembled, or -1 */
    for (const char *p = hex; *p != '\0'; p++) {
        if (*p == ' ') {
            continue;
        }
        const int value = hex_digit(*p);
        if (value < 0) {
            return -1;
        }
        if (high < 0) {
            high = value;
            continue;
        }
        if (n >= cap) {
            return -1;
        }
        out[n++] = (uint8_t)((high << 4) | value);
        high = -1;
    }
    /* A trailing half-byte is a typo, not a short frame; sending it would put
     * bytes on the wire that the person never meant to send. */
    if (high >= 0) {
        return -1;
    }
    return (int)n;
}

int wire_file_list_line(char *out, size_t cap, const wire_file_entry_t *files, size_t count,
                        const wire_file_page_t *page)
{
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_FILES);
    wr_key(&w, "files");
    wr_char(&w, '[');
    for (size_t i = 0; i < count; i++) {
        if (i != 0) {
            wr_char(&w, ',');
        }
        wr_char(&w, '{');
        /* The daemon's four keys, in its order. `bytes` is what the session
         * would be as CSV, not what it costs in flash: the app shows it to a
         * person deciding whether to copy it, and the flash figure would be a
         * number about our implementation rather than about their data. */
        wr_key(&w, "name");
        wr_json_string(&w, or_empty(files[i].name));
        wr_key(&w, "rows");
        wr_int(&w, (long long)files[i].rows);
        wr_key(&w, "bytes");
        wr_int(&w, (long long)files[i].bytes);
        wr_key(&w, "start");
        wr_json_string(&w, or_empty(files[i].start));
        /* Only when true, so an absolute controller's list is byte-identical to
         * what it has always sent. `start` is empty on a relative session: there
         * is no instant to put there and a fabricated one would be believed. */
        if (files[i].relative) {
            wr_key(&w, "relative");
            wr_bool(&w, true);
        }
        wr_char(&w, '}');
    }
    wr_char(&w, ']');
    /* Where this frame sits, in `wire.file_list_page`'s key order. Always said,
     * even by a controller that has nothing following: a client that has to
     * guess whether a listing is complete guesses wrong on the day it matters. */
    wr_key(&w, "offset");
    wr_int(&w, page != NULL ? (long long)page->offset : 0);
    wr_key(&w, "total");
    wr_int(&w, page != NULL ? (long long)page->total : (long long)count);
    if (page != NULL && page->more) {
        wr_key(&w, "next");
        wr_int(&w, (long long)page->next);
    }
    return wr_finish(&w);
}

int wire_file_data_open(wire_file_data_t *fd, char *out, size_t cap, const char *name,
                        bool relative)
{
    if (fd == NULL) {
        return -1;
    }
    memset(fd, 0, sizeof(*fd));
    fd->relative = relative;
    wr_begin(&fd->w, out, cap, WIRE_MSG_FILE_DATA);
    wr_key(&fd->w, "name");
    wr_json_string(&fd->w, or_empty(name));
    wr_key(&fd->w, "columns");
    wr_char(&fd->w, '[');
    wr_json_string(&fd->w, "index");
    wr_char(&fd->w, ',');
    wr_json_string(&fd->w, "timestamp");
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        wr_char(&fd->w, ',');
        wr_json_string(&fd->w, SPS30_FIELD_NAMES[f]);
    }
    wr_char(&fd->w, ',');
    wr_json_string(&fd->w, "event");
    /* A relative session's rows carry the board's own clock instead of an
     * instant, and the columns say so by name. The app maps cells by column
     * name, so this is additive: a build that has never heard of relative time
     * reads the same rows and ignores two of them. */
    if (relative) {
        wr_char(&fd->w, ',');
        wr_json_string(&fd->w, WIRE_COL_REL_RUN);
        wr_char(&fd->w, ',');
        wr_json_string(&fd->w, WIRE_COL_REL_MS);
    }
    wr_char(&fd->w, ']');
    wr_key(&fd->w, "rows");
    wr_char(&fd->w, '[');
    return fd->w.ok ? 0 : -1;
}

/**
 * The bytes one more row needs, including the comma and the closing
 * `],"done":false}\n`. Used to decide whether a row fits before it is written,
 * because a half-written row cannot be taken back out of the buffer.
 */
#define FILE_DATA_ROW_MAX 200
#define FILE_DATA_TAIL_MAX 24

bool wire_file_data_room(const wire_file_data_t *fd)
{
    if (fd == NULL || !fd->w.ok) {
        return false;
    }
    return fd->w.len + FILE_DATA_ROW_MAX + FILE_DATA_TAIL_MAX < fd->w.cap;
}

/**
 * One `[index, timestamp, ...ten values, event]` row.
 *
 * Shared by `file_data` and `alert_data` rather than written twice, and that is
 * not tidiness: preserved alert rows are readings of a real session and the
 * client folds them in through the code that folds a backfill in, which only
 * works if the two messages carry byte-identical rows. Two copies of this would
 * be two chances for them to stop being identical.
 */
/**
 * One CSV-shaped row.
 *
 * @param relative append the board's own clock as two further cells. Only a
 *        session recorded with no clock has them, and only then does the
 *        column list name them - see ::wire_file_data_open.
 */
static void wr_row_cells(wr_t *w, uint32_t index, const char *timestamp,
                         const sps30_measurement_t *values, const char *event,
                         bool relative, uint32_t rel_run, uint32_t rel_ms)
{
    wr_char(w, '[');
    /* Every cell is a string. The daemon serves rows straight out of a CSV
     * reader, so that is what the app parses, and emitting numbers here would
     * be a second shape for the same message. */
    char tmp[32];
    wire_format_index(index, tmp, sizeof(tmp));
    wr_json_string(w, tmp);
    wr_char(w, ',');
    wr_json_string(w, or_empty(timestamp));
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        wr_char(w, ',');
        /* Two decimals, three for the particle size: exactly the precision the
         * store keeps. Printing more would be inventing digits that were
         * quantised away, and printing them as if they were measured. */
        const unsigned decimals = f == SPS30_MEASUREMENT_FIELDS - 1 ? 3u : 2u;
        /* Ten of these a row, fourteen rows a frame. ::wire_format_fixed is the
         * same bytes without the double and without `printf`; `snprintf` is
         * still here, and is still what decides the answer for anything the
         * integer path will not claim to do exactly. */
        if (!wire_format_fixed(values->values[f], decimals, tmp, sizeof(tmp))) {
            snprintf(tmp, sizeof(tmp), decimals == 3u ? "%.3f" : "%.2f",
                     (double)values->values[f]);
        }
        wr_json_string(w, tmp);
    }
    wr_char(w, ',');
    wr_json_string(w, or_empty(event));
    if (relative) {
        wr_char(w, ',');
        snprintf(tmp, sizeof(tmp), "%" PRIu32, rel_run);
        wr_json_string(w, tmp);
        wr_char(w, ',');
        snprintf(tmp, sizeof(tmp), "%" PRIu32, rel_ms);
        wr_json_string(w, tmp);
    }
    wr_char(w, ']');
}

bool wire_file_data_row(wire_file_data_t *fd, uint32_t index, const char *timestamp,
                        const sps30_measurement_t *values, const char *event,
                        uint32_t rel_run, uint32_t rel_ms)
{
    if (fd == NULL || values == NULL || !wire_file_data_room(fd)) {
        return false;
    }
    if (fd->rows != 0) {
        wr_char(&fd->w, ',');
    }
    wr_row_cells(&fd->w, index, timestamp, values, event, fd->relative, rel_run,
                 rel_ms);
    fd->rows++;
    return fd->w.ok;
}

int wire_file_data_close(wire_file_data_t *fd, bool done)
{
    if (fd == NULL) {
        return -1;
    }
    wr_char(&fd->w, ']');
    wr_key(&fd->w, "done");
    wr_bool(&fd->w, done);
    return wr_finish(&fd->w);
}

/* --- formatting ----------------------------------------------------------- */

void wire_format_index(uint32_t index, char *out, size_t cap)
{
    if (out == NULL || cap == 0) {
        return;
    }
    snprintf(out, cap, "%0*u", WIRE_INDEX_WIDTH, (unsigned)index);
}

/** Longest this writes: sign, 14 integer digits, a point, 3 decimals, NUL. */
#define WIRE_FIXED_MIN_CAP 24

/**
 * The largest binary exponent the exact path takes.
 *
 * `m * 10^3 << e` has to stay inside a `uint64_t`: `m < 2^24` and `10^3 < 2^10`,
 * so 20 leaves the product under 2^54 and the value it describes under 2^44.
 * Anything above that is `snprintf`'s, which is also where the digits would stop
 * fitting in a caller's buffer.
 */
#define WIRE_FIXED_MAX_EXP 20

bool wire_format_fixed(float value, unsigned decimals, char *out, size_t cap)
{
    static const uint32_t POW10[4] = {1u, 10u, 100u, 1000u};
    if (out == NULL || decimals > 3 || cap < WIRE_FIXED_MIN_CAP) {
        return false;
    }
    /* Through the bits rather than through arithmetic on the value, because the
     * whole point is to touch no floating-point unit that may not exist. */
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    const bool negative = (bits >> 31) != 0;
    const uint32_t biased = (bits >> 23) & 0xFFu;
    const uint32_t fraction = bits & 0x7FFFFFu;
    if (biased == 0xFFu) {
        return false; /* `inf` and `nan` are words, not numbers; printf spells them */
    }
    /* value == m * 2^e, exactly, for every finite float32 including subnormals. */
    const uint32_t m = biased == 0 ? fraction : (fraction | 0x800000u);
    const int e = biased == 0 ? -149 : (int)biased - 150;

    uint64_t scaled = (uint64_t)m * POW10[decimals];
    if (e >= 0) {
        if (e > WIRE_FIXED_MAX_EXP) {
            return false;
        }
        scaled <<= e;
    } else {
        const int k = -e;
        if (k >= 35) {
            /* `scaled` is under 2^34 and the halfway point is 2^(k-1), so this
             * is below half of the last decimal place and rounds to zero -
             * keeping its sign, because printf writes "-0.00". */
            scaled = 0;
        } else {
            const uint64_t whole = scaled >> k;
            const uint64_t rest = scaled - (whole << k);
            const uint64_t half = (uint64_t)1 << (k - 1);
            /* Nearest, ties to even: the default rounding mode, applied to the
             * exact value rather than to a decimal approximation of it. */
            scaled = whole + ((rest > half || (rest == half && (whole & 1u) != 0)) ? 1u : 0u);
        }
    }

    char digits[WIRE_FIXED_MIN_CAP];
    int n = 0;
    do {
        digits[n++] = (char)('0' + (int)(scaled % 10u));
        scaled /= 10u;
    } while (scaled != 0);
    while (n <= (int)decimals) {
        digits[n++] = '0'; /* pad so there is always a digit before the point */
    }

    size_t at = 0;
    if (negative) {
        out[at++] = '-';
    }
    for (int i = n - 1; i >= 0; i--) {
        out[at++] = digits[i];
        if (decimals > 0 && i == (int)decimals) {
            out[at++] = '.';
        }
    }
    out[at] = '\0';
    return true;
}

/**
 * Civil date from a day number, Howard Hinnant's `civil_from_days`.
 *
 * Used instead of `gmtime_r` so this file stays free of libc's time zone state:
 * the caller has already resolved the zone into an offset, and a pure function
 * is one the host tests can drive across a decade of instants without setting
 * TZ or depending on which tzdata the host happens to have.
 *
 * @param days days since 1970-01-01, negative before it.
 */
static void civil_from_days(int64_t days, int *year, unsigned *month, unsigned *day)
{
    days += 719468; /* shift the epoch to 0000-03-01, so leap day lands last */
    const int64_t era = (days >= 0 ? days : days - 146096) / 146097;
    const uint64_t day_of_era = (uint64_t)(days - era * 146097);         /* [0, 146096] */
    const uint64_t year_of_era = (day_of_era - day_of_era / 1460 + day_of_era / 36524 -
                                  day_of_era / 146096) / 365;            /* [0, 399] */
    const int64_t y = (int64_t)year_of_era + era * 400;
    const uint64_t day_of_year =
        day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100); /* [0, 365] */
    const uint64_t mp = (5 * day_of_year + 2) / 153;                     /* [0, 11] */
    const uint64_t d = day_of_year - (153 * mp + 2) / 5 + 1;             /* [1, 31] */
    const uint64_t m = mp < 10 ? mp + 3 : mp - 9;                        /* [1, 12] */

    *year = (int)(y + (m <= 2));
    *month = (unsigned)m;
    *day = (unsigned)d;
}

/** Split a local-time instant into its calendar and clock parts. */
static void split_local(int64_t epoch_s, int utc_offset_s, int *year, unsigned *month,
                        unsigned *day, unsigned *hour, unsigned *minute, unsigned *second)
{
    const int64_t local = epoch_s + utc_offset_s;
    /* Floor division, so instants before 1970 land on the right day. */
    int64_t days = local / 86400;
    int64_t rem = local % 86400;
    if (rem < 0) {
        rem += 86400;
        days -= 1;
    }
    civil_from_days(days, year, month, day);
    *hour = (unsigned)(rem / 3600);
    *minute = (unsigned)((rem % 3600) / 60);
    *second = (unsigned)(rem % 60);
}

int wire_iso_timestamp(int64_t epoch_s, int utc_offset_s, char *out, size_t cap)
{
    if (out == NULL || cap < WIRE_TIMESTAMP_LEN) {
        return -1;
    }
    int year;
    unsigned month, day, hour, minute, second;
    split_local(epoch_s, utc_offset_s, &year, &month, &day, &hour, &minute, &second);

    const char sign = utc_offset_s < 0 ? '-' : '+';
    const int magnitude = utc_offset_s < 0 ? -utc_offset_s : utc_offset_s;
    return snprintf(out, cap, "%04d-%02u-%02uT%02u:%02u:%02u%c%02d:%02d", year, month, day,
                    hour, minute, second, sign, magnitude / 3600, (magnitude % 3600) / 60);
}

int wire_csv_name(int64_t epoch_s, int utc_offset_s, char *out, size_t cap)
{
    if (out == NULL || cap < WIRE_CSV_NAME_LEN) {
        return -1;
    }
    int year;
    unsigned month, day, hour, minute, second;
    split_local(epoch_s, utc_offset_s, &year, &month, &day, &hour, &minute, &second);
    return snprintf(out, cap, "SPS30-%04d%02u%02u_%02u%02u%02u.csv", year, month, day, hour,
                    minute, second);
}

int wire_rel_csv_name(uint32_t run, uint32_t start_ms, char *out, size_t cap)
{
    if (out == NULL || cap < WIRE_CSV_NAME_LEN) {
        return -1;
    }
    return snprintf(out, cap, "SPS30-R%08" PRIX32 "-%08" PRIX32 ".csv",
                    run & 0x7FFFFFFFu, start_ms);
}

/** Eight hex digits at @p p, or false. Upper case only: this reads back what
 *  ::wire_rel_csv_name wrote, and accepting more would accept names it never
 *  produces and so could not have written. */
static bool hex8(const char *p, uint32_t *out)
{
    uint32_t value = 0;
    for (int i = 0; i < 8; i++) {
        const char ch = p[i];
        if (ch >= '0' && ch <= '9') {
            value = (value << 4) | (uint32_t)(ch - '0');
        } else if (ch >= 'A' && ch <= 'F') {
            value = (value << 4) | (uint32_t)(ch - 'A' + 10);
        } else {
            return false;
        }
    }
    *out = value;
    return true;
}

bool wire_parse_rel_csv_name(const char *name, uint32_t *run, uint32_t *start_ms)
{
    if (name == NULL || strlen(name) != 28 || strncmp(name, "SPS30-R", 7) != 0 ||
        name[15] != '-' || strcmp(name + 24, ".csv") != 0) {
        return false;
    }
    uint32_t a = 0, b = 0;
    if (!hex8(name + 7, &a) || !hex8(name + 16, &b)) {
        return false;
    }
    if (run != NULL) {
        *run = a;
    }
    if (start_ms != NULL) {
        *start_ms = b;
    }
    return true;
}

/* --- decoding ------------------------------------------------------------- */

static const char *skip_space(const char *p, const char *end)
{
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) {
        p++;
    }
    return p;
}

/** Past the closing quote of the string starting at @p p (which is the quote). */
static const char *skip_string(const char *p, const char *end)
{
    if (p >= end || *p != '"') {
        return NULL;
    }
    for (p++; p < end; p++) {
        if (*p == '\\') {
            p++; /* whatever follows is consumed, including a quote */
            continue;
        }
        if (*p == '"') {
            return p + 1;
        }
    }
    return NULL;
}

/** Past the end of any one JSON value, descending only far enough to skip it. */
static const char *skip_value(const char *p, const char *end)
{
    p = skip_space(p, end);
    if (p >= end) {
        return NULL;
    }
    if (*p == '"') {
        return skip_string(p, end);
    }
    if (*p == '{' || *p == '[') {
        int depth = 0;
        for (; p < end; p++) {
            if (*p == '"') {
                const char *after = skip_string(p, end);
                if (after == NULL) {
                    return NULL;
                }
                p = after - 1;
                continue;
            }
            if (*p == '{' || *p == '[') {
                depth++;
            } else if (*p == '}' || *p == ']') {
                if (--depth == 0) {
                    return p + 1;
                }
            }
        }
        return NULL;
    }
    /* A number, true, false or null: everything up to the next structural byte. */
    while (p < end && *p != ',' && *p != '}' && *p != ']' && *p != ' ' && *p != '\t' &&
           *p != '\r' && *p != '\n') {
        p++;
    }
    return p;
}

/** Append @p code point as UTF-8, or nothing at all if it will not fit. */
static void append_utf8(char *out, size_t cap, size_t *len, uint32_t code)
{
    char seq[4];
    size_t n;
    if (code < 0x80) {
        seq[0] = (char)code;
        n = 1;
    } else if (code < 0x800) {
        seq[0] = (char)(0xC0 | (code >> 6));
        seq[1] = (char)(0x80 | (code & 0x3F));
        n = 2;
    } else if (code < 0x10000) {
        seq[0] = (char)(0xE0 | (code >> 12));
        seq[1] = (char)(0x80 | ((code >> 6) & 0x3F));
        seq[2] = (char)(0x80 | (code & 0x3F));
        n = 3;
    } else {
        seq[0] = (char)(0xF0 | (code >> 18));
        seq[1] = (char)(0x80 | ((code >> 12) & 0x3F));
        seq[2] = (char)(0x80 | ((code >> 6) & 0x3F));
        seq[3] = (char)(0x80 | (code & 0x3F));
        n = 4;
    }
    /* All or nothing: half a UTF-8 sequence is not a shorter string, it is an
     * invalid one, and it would be handed straight to a UI. */
    if (*len + n + 1 > cap) {
        return;
    }
    memcpy(out + *len, seq, n);
    *len += n;
    out[*len] = '\0';
}

static bool hex4(const char *p, const char *end, uint32_t *value)
{
    if (end - p < 4) {
        return false;
    }
    uint32_t v = 0;
    for (int i = 0; i < 4; i++) {
        const char c = p[i];
        v <<= 4;
        if (c >= '0' && c <= '9') {
            v |= (uint32_t)(c - '0');
        } else if (c >= 'a' && c <= 'f') {
            v |= (uint32_t)(c - 'a' + 10);
        } else if (c >= 'A' && c <= 'F') {
            v |= (uint32_t)(c - 'A' + 10);
        } else {
            return false;
        }
    }
    *value = v;
    return true;
}

/** Copy the string starting at @p p (its opening quote) into @p out, unescaped. */
static void unescape_into(const char *p, const char *end, char *out, size_t cap)
{
    size_t len = 0;
    out[0] = '\0';
    for (p++; p < end && *p != '"'; p++) {
        if (*p != '\\') {
            append_utf8(out, cap, &len, (unsigned char)*p);
            continue;
        }
        p++;
        if (p >= end) {
            return;
        }
        switch (*p) {
        case 'n': append_utf8(out, cap, &len, '\n'); break;
        case 't': append_utf8(out, cap, &len, '\t'); break;
        case 'r': append_utf8(out, cap, &len, '\r'); break;
        case 'b': append_utf8(out, cap, &len, '\b'); break;
        case 'f': append_utf8(out, cap, &len, '\f'); break;
        case 'u': {
            uint32_t code = 0;
            if (!hex4(p + 1, end, &code)) {
                return;
            }
            p += 4;
            /* A high surrogate is only half a character; pair it with the low
             * one that must follow, or the UI gets a replacement box. */
            if (code >= 0xD800 && code <= 0xDBFF && end - p >= 7 && p[1] == '\\' &&
                p[2] == 'u') {
                uint32_t low = 0;
                if (hex4(p + 3, end, &low) && low >= 0xDC00 && low <= 0xDFFF) {
                    code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                    p += 6;
                }
            }
            append_utf8(out, cap, &len, code);
            break;
        }
        default: append_utf8(out, cap, &len, (unsigned char)*p); break; /* " \ / */
        }
    }
}

/**
 * Find one top-level member and hand back the raw bytes of its value.
 *
 * The scan every accessor below shares. It stops at the first level: a nested
 * object or array is stepped over by ::skip_value rather than descended, so a
 * key inside `{"profile":{...}}` is never mistaken for a top-level one. That is
 * a deliberate limit rather than an oversight, and ::wire_json_object is how a
 * caller that wants the inner object gets at it.
 *
 * On success @p value_start points at the first byte of the value and
 * @p value_end one past its last, both inside @p line.
 */
static bool find_member(const char *line, size_t len, const char *key,
                        const char **value_start, const char **value_end)
{
    const char *const end = line + len;
    const char *p = skip_space(line, end);
    if (p >= end || *p != '{') {
        return false;
    }
    p++;
    const size_t key_len = strlen(key);

    while (p < end) {
        p = skip_space(p, end);
        if (p >= end || *p == '}') {
            return false;
        }
        const char *key_start = p;
        const char *after_key = skip_string(p, end);
        if (after_key == NULL) {
            return false;
        }
        p = skip_space(after_key, end);
        if (p >= end || *p != ':') {
            return false;
        }
        p = skip_space(p + 1, end);
        const char *start = p;
        const char *after_value = skip_value(p, end);
        if (after_value == NULL) {
            return false;
        }

        /* Key match is on the raw bytes between the quotes. An escaped key would
         * not match, which is correct for a vocabulary of ASCII literals. */
        const size_t raw_len = (size_t)(after_key - key_start) - 2;
        if (raw_len == key_len && memcmp(key_start + 1, key, key_len) == 0) {
            *value_start = start;
            *value_end = after_value;
            return true;
        }

        p = skip_space(after_value, end);
        if (p < end && *p == ',') {
            p++;
        } else {
            return false; /* end of the object, or malformed */
        }
    }
    return false;
}

bool wire_json_string(const char *line, size_t len, const char *key, char *out, size_t cap)
{
    if (line == NULL || key == NULL || out == NULL || cap == 0) {
        return false;
    }
    out[0] = '\0';
    const char *start = NULL;
    const char *stop = NULL;
    if (!find_member(line, len, key, &start, &stop)) {
        return false;
    }
    if (*start != '"') {
        return false; /* present, but not a string */
    }
    unescape_into(start, stop - 1, out, cap);
    return true;
}

bool wire_json_int(const char *line, size_t len, const char *key, int64_t *out)
{
    if (line == NULL || key == NULL || out == NULL) {
        return false;
    }
    const char *start = NULL;
    const char *stop = NULL;
    if (!find_member(line, len, key, &start, &stop)) {
        return false;
    }

    /* A number, or a string holding one. The daemon reads `since_index` with
     * `int(...)` and the app sends it as a number, but the same field arrives
     * quoted from a hand-typed probe, and accepting both costs one branch. */
    const char *digits = start;
    if (*digits == '"') {
        digits++;
        stop--;
    }
    bool negative = false;
    if (digits < stop && (*digits == '-' || *digits == '+')) {
        negative = *digits == '-';
        digits++;
    }
    if (digits >= stop) {
        return false;
    }
    int64_t value = 0;
    for (const char *d = digits; d < stop; d++) {
        if (*d < '0' || *d > '9') {
            return false; /* a float or nonsense: not an index */
        }
        if (value > (INT64_MAX - (*d - '0')) / 10) {
            return false; /* would overflow; refuse rather than wrap */
        }
        value = value * 10 + (*d - '0');
    }
    *out = negative ? -value : value;
    return true;
}

bool wire_json_double(const char *line, size_t len, const char *key, double *out)
{
    if (line == NULL || key == NULL || out == NULL) {
        return false;
    }
    const char *start = NULL;
    const char *stop = NULL;
    if (!find_member(line, len, key, &start, &stop)) {
        return false;
    }
    if (*start == '"') { /* quoted, as ::wire_json_int accepts */
        start++;
        stop--;
    }

    /* `strtod` needs a terminator, and the value is a span inside a frame that
     * must not be written to. A profile field is at most a handful of digits, so
     * a stack copy is enough and an over-long one is refused rather than
     * truncated - a clipped number is a different number. */
    char buf[40];
    const size_t n = (size_t)(stop - start);
    if (n == 0 || n >= sizeof(buf)) {
        return false;
    }
    memcpy(buf, start, n);
    buf[n] = '\0';

    char *tail = NULL;
    const double value = strtod(buf, &tail);
    if (tail == buf || *tail != '\0') {
        return false; /* not a number, or not only a number */
    }
    if (!isfinite(value)) {
        return false; /* NaN and infinity are not intervals */
    }
    *out = value;
    return true;
}

bool wire_json_bool(const char *line, size_t len, const char *key, bool *out)
{
    if (line == NULL || key == NULL || out == NULL) {
        return false;
    }
    const char *start = NULL;
    const char *stop = NULL;
    if (!find_member(line, len, key, &start, &stop)) {
        return false;
    }
    const size_t value_len = (size_t)(stop - start);
    if (value_len == 4 && memcmp(start, "true", 4) == 0) {
        *out = true;
        return true;
    }
    if (value_len == 5 && memcmp(start, "false", 5) == 0) {
        *out = false;
        return true;
    }
    return false; /* present, but not a boolean */
}

bool wire_json_object(const char *line, size_t len, const char *key, const char **out,
                      size_t *out_len)
{
    if (line == NULL || key == NULL || out == NULL || out_len == NULL) {
        return false;
    }
    const char *start = NULL;
    const char *stop = NULL;
    if (!find_member(line, len, key, &start, &stop)) {
        return false;
    }
    if (*start != '{') {
        return false; /* present, but not an object */
    }
    *out = start;
    *out_len = (size_t)(stop - start);
    return true;
}

size_t wire_line_length(const char *buf, size_t len)
{
    if (buf == NULL) {
        return 0;
    }
    const char *nl = memchr(buf, '\n', len);
    return nl == NULL ? 0 : (size_t)(nl - buf) + 1;
}

/* --- reading a timestamp and a name back ---------------------------------- */

/** Digits of `[from, to)` as an int, or -1 if any of them is not a digit. */
static int digits(const char *s, size_t from, size_t to)
{
    int acc = 0;
    for (size_t i = from; i < to; i++) {
        const int d = s[i] - '0';
        if (d < 0 || d > 9) {
            return -1;
        }
        acc = acc * 10 + d;
    }
    return acc;
}

/**
 * The inverse of ::civil_from_days (Howard Hinnant's `days_from_civil`), so a
 * calendar date becomes a day number without libc's time zone machinery.
 */
static int64_t days_from_civil(int year, unsigned month, unsigned day)
{
    const int64_t y = (int64_t)year - (month <= 2);
    const int64_t era = (y >= 0 ? y : y - 399) / 400;
    const int64_t yoe = y - era * 400;                                    /* [0, 399] */
    const int64_t doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
    const int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;            /* [0, 146096] */
    return era * 146097 + doe - 719468;
}

bool wire_parse_iso(const char *text, int64_t *epoch_s, int *utc_offset_s)
{
    /* 0123456789...              YYYY-MM-DDTHH:MM:SS+hh:mm
     * Fixed width throughout, which is what ::wire_iso_timestamp emits and what
     * the app's own fast path assumes. Anything else is refused. */
    if (text == NULL || epoch_s == NULL || utc_offset_s == NULL) {
        return false;
    }
    if (strlen(text) != 25 || text[4] != '-' || text[7] != '-' || text[10] != 'T' ||
        text[13] != ':' || text[16] != ':' || text[22] != ':' ||
        (text[19] != '+' && text[19] != '-')) {
        return false;
    }
    const int year = digits(text, 0, 4);
    const int month = digits(text, 5, 7);
    const int day = digits(text, 8, 10);
    const int hour = digits(text, 11, 13);
    const int minute = digits(text, 14, 16);
    const int second = digits(text, 17, 19);
    const int off_h = digits(text, 20, 22);
    const int off_m = digits(text, 23, 25);
    if (year < 0 || month < 1 || month > 12 || day < 1 || day > 31 || hour < 0 ||
        hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 60 ||
        off_h < 0 || off_h > 23 || off_m < 0 || off_m > 59) {
        return false;
    }

    const int offset = (off_h * 3600 + off_m * 60) * (text[19] == '-' ? -1 : 1);
    const int64_t local = days_from_civil(year, (unsigned)month, (unsigned)day) * 86400 +
                          hour * 3600 + minute * 60 + second;
    *utc_offset_s = offset;
    *epoch_s = local - offset;
    return true;
}

bool wire_parse_csv_name(const char *name, int64_t *local_s)
{
    /* "SPS30-YYYYmmdd_HHMMSS.csv", exactly. */
    static const char PREFIX[] = "SPS30-";
    static const char SUFFIX[] = ".csv";
    if (name == NULL || local_s == NULL) {
        return false;
    }
    const size_t len = strlen(name);
    if (len != sizeof(PREFIX) - 1 + 15 + sizeof(SUFFIX) - 1 ||
        memcmp(name, PREFIX, sizeof(PREFIX) - 1) != 0 || name[14] != '_' ||
        strcmp(name + 21, SUFFIX) != 0) {
        return false;
    }
    const int year = digits(name, 6, 10);
    const int month = digits(name, 10, 12);
    const int day = digits(name, 12, 14);
    const int hour = digits(name, 15, 17);
    const int minute = digits(name, 17, 19);
    const int second = digits(name, 19, 21);
    if (year < 0 || month < 1 || month > 12 || day < 1 || day > 31 || hour < 0 ||
        hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 60) {
        return false;
    }
    *local_s = days_from_civil(year, (unsigned)month, (unsigned)day) * 86400 +
               hour * 3600 + minute * 60 + second;
    return true;
}

/* --- reading a row back ---------------------------------------------------- */

/**
 * The `n`th comma-separated cell of `[row, row + len)`, copied out NUL-terminated.
 *
 * Copied rather than pointed at because the caller's row is a slice of a much
 * larger buffer with no NUL where a cell ends, and every consumer below
 * (`strtod`, ::wire_parse_iso) wants a C string. A cell longer than @p cap is
 * truncated, which turns it into an unparseable value rather than a smash.
 *
 * @return false when the row has no `n`th cell.
 */
static bool cell(const char *row, size_t len, size_t n, char *out, size_t cap)
{
    size_t start = 0;
    size_t field = 0;
    for (size_t i = 0; i <= len; i++) {
        if (i == len || row[i] == ',') {
            if (field == n) {
                size_t take = i - start;
                if (take >= cap) {
                    take = cap - 1;
                }
                memcpy(out, row + start, take);
                out[take] = '\0';
                return true;
            }
            field++;
            start = i + 1;
        }
    }
    out[0] = '\0';
    return false;
}

/** A cell as a double, or @p fallback when it is blank or not a number. */
static double cell_double(const char *row, size_t len, size_t n, double fallback)
{
    char text[32];
    if (!cell(row, len, n, text, sizeof(text)) || text[0] == '\0') {
        return fallback;
    }
    char *end = NULL;
    const double v = strtod(text, &end);
    if (end == text) {
        return fallback;
    }
    return v;
}

bool wire_parse_row(const char *row, size_t len, wire_row_t *out)
{
    if (row == NULL || out == NULL) {
        return false;
    }
    /* Strip a trailing \r, so a file that has been through a Windows editor on
     * its way back is not one long column of rejected rows. */
    while (len > 0 && (row[len - 1] == '\r' || row[len - 1] == '\n')) {
        len--;
    }
    if (len == 0) {
        return false;
    }

    memset(out, 0, sizeof(*out));

    char text[40];
    if (!cell(row, len, 0, text, sizeof(text))) {
        return false;
    }
    /* The index is zero-padded on the wire ("0000042"); `strtoul` reads that as
     * decimal only because the base is given, which is the whole reason it is
     * given - base 0 would make "0000042" octal and file it as 34. */
    char *end = NULL;
    const unsigned long index = strtoul(text, &end, 10);
    if (end == text || index == 0 || index > 0xFFFFFFFFul) {
        return false;
    }
    out->index = (uint32_t)index;

    if (!cell(row, len, 1, text, sizeof(text)) ||
        !wire_parse_iso(text, &out->epoch_s, &out->utc_offset_s)) {
        return false;
    }

    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        out->values.values[f] = (float)cell_double(row, len, 2 + f, 0.0);
    }

    /* Any non-blank event marks the reading disturbed. That is the whole of the
     * contract the clients have always applied to this column. */
    if (cell(row, len, 2 + SPS30_MEASUREMENT_FIELDS, text, sizeof(text))) {
        out->settling = text[0] != '\0';
    }
    return true;
}

/* --- the transfer messages ------------------------------------------------- */

int wire_import_result_line(char *out, size_t cap, const wire_import_result_t *result)
{
    if (result == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_IMPORT_RESULT);
    wr_key(&w, "name");
    wr_json_string(&w, or_empty(result->name));
    wr_key(&w, "added");
    wr_int(&w, (long long)result->added);
    wr_key(&w, "duplicate");
    wr_int(&w, (long long)result->duplicate);
    wr_key(&w, "rejected");
    wr_int(&w, (long long)result->rejected);
    wr_key(&w, "sessions");
    wr_int(&w, (long long)result->sessions);
    wr_key(&w, "state");
    wr_json_string(&w, or_empty(result->state));
    wr_key(&w, "message");
    wr_json_string(&w, or_empty(result->message));
    return wr_finish(&w);
}

int wire_storage_line(char *out, size_t cap, const wire_storage_t *storage)
{
    if (storage == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_STORAGE);
    wr_key(&w, "used_pages");
    wr_int(&w, (long long)storage->used_pages);
    wr_key(&w, "total_pages");
    wr_int(&w, (long long)storage->total_pages);
    wr_key(&w, "readings");
    wr_int(&w, (long long)storage->readings);
    wr_key(&w, "sessions");
    wr_int(&w, (long long)storage->sessions);
    wr_key(&w, "fraction");
    wr_double(&w, storage->fraction);
    wr_key(&w, "warn_at");
    wr_double(&w, WIRE_STORE_WARN_FRACTION);
    wr_key(&w, "warn");
    wr_bool(&w, storage->warn);
    wr_key(&w, "ready");
    wr_bool(&w, storage->ready);
    return wr_finish(&w);
}

/**
 * Append @p len bytes as a quoted standard-base64 string, padding included.
 *
 * `base64.b64encode` byte for byte, which the cells vectors assert. Streams
 * straight into the writer because the only caller's payload is 1,248 bytes -
 * an intermediate buffer would be the second-largest allocation in the
 * component for no reason.
 */
static void wr_base64_string(wr_t *w, const uint8_t *bytes, size_t len)
{
    static const char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    wr_char(w, '"');
    for (size_t i = 0; i < len; i += 3) {
        const size_t left = len - i;
        const uint32_t b0 = bytes[i];
        const uint32_t b1 = left > 1 ? bytes[i + 1] : 0;
        const uint32_t b2 = left > 2 ? bytes[i + 2] : 0;
        const uint32_t group = (b0 << 16) | (b1 << 8) | b2;
        wr_char(w, alphabet[(group >> 18) & 0x3F]);
        wr_char(w, alphabet[(group >> 12) & 0x3F]);
        wr_char(w, left > 1 ? alphabet[(group >> 6) & 0x3F] : '=');
        wr_char(w, left > 2 ? alphabet[group & 0x3F] : '=');
    }
    wr_char(w, '"');
}

int wire_storage_map_line(char *out, size_t cap, const wire_storage_map_t *map)
{
    if (map == NULL || (map->section_count > 0 && map->sections == NULL)) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_STORAGE_MAP);
    wr_key(&w, "page_bytes");
    wr_int(&w, (long long)map->page_bytes);
    wr_key(&w, "pages");
    wr_int(&w, (long long)map->pages);
    wr_key(&w, "sections");
    wr_char(&w, '[');
    for (size_t i = 0; i < map->section_count; i++) {
        const wire_map_section_t *section = &map->sections[i];
        if (section->cells == NULL) {
            return -1;
        }
        if (i != 0) {
            wr_char(&w, ',');
        }
        wr_char(&w, '{');
        wr_key(&w, "id");
        wr_json_string(&w, or_empty(section->id));
        wr_key(&w, "start");
        wr_int(&w, (long long)section->start);
        wr_key(&w, "pages");
        wr_int(&w, (long long)section->pages);
        wr_key(&w, "head");
        wr_int(&w, (long long)section->head);
        wr_key(&w, "wraps");
        wr_int(&w, (long long)section->wraps);
        wr_key(&w, "cells");
        wr_base64_string(&w, section->cells, (section->pages + 3u) / 4u);
        wr_char(&w, '}');
    }
    wr_char(&w, ']');
    return wr_finish(&w);
}

/* --- preserved alert data -------------------------------------------------- */

int wire_alerts_line(char *out, size_t cap, const wire_alerts_t *alerts)
{
    if (alerts == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_ALERTS);
    wr_key(&w, "ready");
    wr_bool(&w, alerts->ready);
    wr_key(&w, "segments");
    wr_int(&w, (long long)alerts->segments);
    wr_key(&w, "pressure");
    wr_double(&w, alerts->pressure);
    wr_key(&w, "rate_floor_s");
    wr_double(&w, alerts->rate_floor_s);
    wr_key(&w, "wraps");
    wr_int(&w, (long long)alerts->wraps);
    /* Only the fields actually being watched. Ten keys of which nine are zero
     * would say "these are all off" in a message whose whole subject is which
     * ones are on, and an empty object is the honest way to say "none". */
    wr_key(&w, "thresholds");
    wr_char(&w, '{');
    size_t written = 0;
    for (size_t f = 0; f < alerts->threshold_count && f < SPS30_MEASUREMENT_FIELDS; f++) {
        if (alerts->thresholds == NULL || !(alerts->thresholds[f] > 0.0f)) {
            continue;
        }
        if (written++ != 0) {
            wr_char(&w, ',');
        }
        wr_json_string(&w, SPS30_FIELD_NAMES[f]);
        wr_char(&w, ':');
        wr_double(&w, (double)alerts->thresholds[f]);
    }
    wr_char(&w, '}');
    return wr_finish(&w);
}

int wire_alert_segment_line(char *out, size_t cap, const wire_alert_segment_t *segment)
{
    if (segment == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_ALERT_SEGMENT);
    /* `wire.ALERT_SEGMENT_KEYS`, in that order. */
    wr_key(&w, "seq");
    wr_int(&w, (long long)segment->seq);
    wr_key(&w, "session");
    wr_json_string(&w, or_empty(segment->session));
    wr_key(&w, "field");
    wr_json_string(&w, or_empty(segment->field));
    wr_key(&w, "threshold");
    wr_double(&w, segment->threshold);
    wr_key(&w, "start");
    wr_json_string(&w, or_empty(segment->start));
    wr_key(&w, "stop");
    wr_json_string(&w, or_empty(segment->stop));
    wr_key(&w, "first");
    wr_double(&w, segment->first);
    wr_key(&w, "last");
    wr_double(&w, segment->last);
    wr_key(&w, "midpoint");
    wr_double(&w, segment->midpoint);
    wr_key(&w, "mean");
    wr_double(&w, segment->mean);
    wr_key(&w, "variance");
    wr_double(&w, segment->variance);
    wr_key(&w, "minimum");
    wr_double(&w, segment->minimum);
    wr_key(&w, "maximum");
    wr_double(&w, segment->maximum);
    wr_key(&w, "points");
    wr_int(&w, (long long)segment->points);
    wr_key(&w, "first_index");
    wr_int(&w, (long long)segment->first_index);
    wr_key(&w, "last_index");
    wr_int(&w, (long long)segment->last_index);
    wr_key(&w, "wrap");
    wr_int(&w, (long long)segment->wrap);
    wr_key(&w, "has_body");
    wr_bool(&w, segment->has_body);
    return wr_finish(&w);
}

int wire_alert_data_open(wire_alert_data_t *ad, char *out, size_t cap, uint32_t seq,
                         const char *name)
{
    if (ad == NULL) {
        return -1;
    }
    memset(ad, 0, sizeof(*ad));
    wr_begin(&ad->w, out, cap, WIRE_MSG_ALERT_DATA);
    wr_key(&ad->w, "seq");
    wr_int(&ad->w, (long long)seq);
    /* The session's name as well as the segment's sequence. The rows are
     * readings of a real recording and the client folds them into it, so the
     * message has to say which one - the sequence identifies the segment, not
     * the session. */
    wr_key(&ad->w, "name");
    wr_json_string(&ad->w, or_empty(name));
    wr_key(&ad->w, "columns");
    wr_char(&ad->w, '[');
    wr_json_string(&ad->w, "index");
    wr_char(&ad->w, ',');
    wr_json_string(&ad->w, "timestamp");
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        wr_char(&ad->w, ',');
        wr_json_string(&ad->w, SPS30_FIELD_NAMES[f]);
    }
    wr_char(&ad->w, ',');
    wr_json_string(&ad->w, "event");
    wr_char(&ad->w, ']');
    wr_key(&ad->w, "rows");
    wr_char(&ad->w, '[');
    return ad->w.ok ? 0 : -1;
}

bool wire_alert_data_room(const wire_alert_data_t *ad)
{
    if (ad == NULL || !ad->w.ok) {
        return false;
    }
    return ad->w.len + FILE_DATA_ROW_MAX + FILE_DATA_TAIL_MAX < ad->w.cap;
}

bool wire_alert_data_row(wire_alert_data_t *ad, uint32_t index, const char *timestamp,
                         const sps30_measurement_t *values, const char *event)
{
    if (ad == NULL || values == NULL || !wire_alert_data_room(ad)) {
        return false;
    }
    if (ad->rows != 0) {
        wr_char(&ad->w, ',');
    }
    wr_row_cells(&ad->w, index, timestamp, values, event, false, 0, 0);
    ad->rows++;
    return ad->w.ok;
}

int wire_alert_data_close(wire_alert_data_t *ad, bool done)
{
    if (ad == NULL) {
        return -1;
    }
    wr_char(&ad->w, ']');
    wr_key(&ad->w, "done");
    wr_bool(&ad->w, done);
    return wr_finish(&ad->w);
}

int wire_erase_result_line(char *out, size_t cap, const wire_erase_result_t *result)
{
    if (result == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_ERASE_RESULT);
    wr_key(&w, "state");
    wr_json_string(&w, or_empty(result->state));
    wr_key(&w, "message");
    wr_json_string(&w, or_empty(result->message));
    wr_key(&w, "sessions_confirmed");
    wr_int(&w, (long long)result->sessions_confirmed);
    wr_key(&w, "sessions_refused");
    wr_int(&w, (long long)result->sessions_refused);
    wr_key(&w, "pages_erased");
    wr_int(&w, (long long)result->pages_erased);
    wr_key(&w, "readings_erased");
    wr_int(&w, (long long)result->readings_erased);
    wr_key(&w, "overridden");
    wr_bool(&w, result->overridden);
    return wr_finish(&w);
}

/* --- backing everything up and clearing the board -------------------------- */

int wire_drain_state_line(char *out, size_t cap, const wire_drain_state_t *state)
{
    if (state == NULL) {
        return -1;
    }
    wr_t w;
    wr_begin(&w, out, cap, WIRE_MSG_DRAIN_STATE);
    wr_key(&w, "state");
    wr_json_string(&w, or_empty(state->state));
    wr_key(&w, "message");
    wr_json_string(&w, or_empty(state->message));
    wr_key(&w, "block");
    wr_int(&w, (long long)state->block);
    wr_key(&w, "kind");
    wr_json_string(&w, or_empty(state->kind));
    wr_key(&w, "final");
    wr_bool(&w, state->final);
    wr_key(&w, "rows");
    wr_int(&w, (long long)state->rows);
    /* Hex, like the erase gate's claims and for the same reason: a digest is a
     * bit pattern rather than a quantity, and reading it as one loses the top
     * bit to a sign somewhere along the way. */
    wr_key(&w, "digest");
    {
        char tmp[16];
        snprintf(tmp, sizeof(tmp), "%08" PRIx32, state->digest);
        wr_json_string(&w, tmp);
    }
    wr_key(&w, "blocks_left");
    wr_int(&w, (long long)state->blocks_left);
    wr_key(&w, "blocks_freed");
    wr_int(&w, (long long)state->blocks_freed);
    wr_key(&w, "blocks_kept");
    wr_int(&w, (long long)state->blocks_kept);
    wr_key(&w, "blocks_refused");
    wr_int(&w, (long long)state->blocks_refused);
    wr_key(&w, "readings_copied");
    wr_int(&w, (long long)state->readings_copied);
    wr_key(&w, "segments_copied");
    wr_int(&w, (long long)state->segments_copied);
    wr_key(&w, "readings_freed");
    wr_int(&w, (long long)state->readings_freed);
    return wr_finish(&w);
}

/** One float cell of a segment row: exact text, or empty for a non-number. */
static void wr_exact_cell(wr_t *w, double v)
{
    char tmp[52];
    if (!format_double(tmp, sizeof(tmp), v)) {
        wr_json_string(w, "");
        return;
    }
    wr_json_string(w, tmp);
}

int wire_drain_data_open(wire_drain_data_t *dd, char *out, size_t cap, uint32_t block,
                         const char *kind)
{
    if (dd == NULL) {
        return -1;
    }
    memset(dd, 0, sizeof(*dd));
    dd->segments = kind != NULL && strcmp(kind, WIRE_DRAIN_KIND_SEGMENTS) == 0;
    wr_begin(&dd->w, out, cap, WIRE_MSG_DRAIN_DATA);
    wr_key(&dd->w, "block");
    wr_int(&dd->w, (long long)block);
    wr_key(&dd->w, "kind");
    wr_json_string(&dd->w, or_empty(kind));
    wr_key(&dd->w, "columns");
    wr_char(&dd->w, '[');
    if (dd->segments) {
        /* `wire.DRAIN_SEGMENT_COLUMNS`, in that order. */
        static const char *const KEYS[] = {
            "seq",   "session",  "field",    "threshold",   "start_s", "stop_s",
            "first", "last",     "midpoint", "mean",        "variance", "minimum",
            "maximum", "points", "first_index", "last_index", "wrap",  "has_body",
        };
        for (size_t i = 0; i < sizeof(KEYS) / sizeof(KEYS[0]); i++) {
            if (i != 0) {
                wr_char(&dd->w, ',');
            }
            wr_json_string(&dd->w, KEYS[i]);
        }
    } else {
        /* `wire.DRAIN_READING_COLUMNS`: the session's name, then
         * `CSV_COLUMNS_RELATIVE`. Both clock columns are always present because
         * one block can hold pages of both kinds. */
        wr_json_string(&dd->w, "session");
        /* An extra cell would widen every row; DRAIN_READING_ROW_MAX is what the
         * frame leaves room for and test_wire.c measures the widest row against
         * it. Move both together. */
        wr_char(&dd->w, ',');
        wr_json_string(&dd->w, "index");
        wr_char(&dd->w, ',');
        wr_json_string(&dd->w, "timestamp");
        for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
            wr_char(&dd->w, ',');
            wr_json_string(&dd->w, SPS30_FIELD_NAMES[f]);
        }
        wr_char(&dd->w, ',');
        wr_json_string(&dd->w, "event");
        wr_char(&dd->w, ',');
        wr_json_string(&dd->w, WIRE_COL_REL_RUN);
        wr_char(&dd->w, ',');
        wr_json_string(&dd->w, WIRE_COL_REL_MS);
    }
    wr_char(&dd->w, ']');
    wr_key(&dd->w, "rows");
    wr_char(&dd->w, '[');
    return dd->w.ok ? 0 : -1;
}

/**
 * The bytes a drained reading row can need, worst case.
 *
 * **Measured, not estimated**: the widest row this encoder can produce is 208
 * bytes - the longest session name, a seven-digit index, a full timestamp, ten
 * values at `-99999.99`, an event and both clock cells, with the quotes and
 * commas. This is 288 for the margin, because getting it wrong is not a
 * truncated message: an over-long row is written into the buffer, the writer
 * marks itself failed, the frame is dropped, and the block it belonged to never
 * completes - so the copy stalls on a block the sensor is waiting to be told
 * about, with nothing on either side saying why.
 *
 * A drained row is wider than a `file_data` one - it has the session's name in
 * front and always carries both clock cells, where that one adds them only for a
 * relative session - so it gets its own bound rather than borrowing that one's
 * and hoping. `test_drain_messages_match_python` fills a frame with the widest
 * rows there are and holds this to it.
 */
#define DRAIN_READING_ROW_MAX 288

/**
 * And a summary row, which is wider again: 322 bytes measured.
 *
 * Eight exact float cells rather than ten fixed-point ones, and each of them can
 * be seventeen significant digits and an exponent - `1.0/3.0` is twenty
 * characters where `%.2f` would be four.
 */
#define DRAIN_SEGMENT_ROW_MAX 420

bool wire_drain_data_room(const wire_drain_data_t *dd)
{
    if (dd == NULL || !dd->w.ok) {
        return false;
    }
    const size_t row = dd->segments ? DRAIN_SEGMENT_ROW_MAX : DRAIN_READING_ROW_MAX;
    return dd->w.len + row + FILE_DATA_TAIL_MAX < dd->w.cap;
}

bool wire_drain_data_row(wire_drain_data_t *dd, const char *session, uint32_t index,
                         const char *timestamp, const sps30_measurement_t *values,
                         const char *event, bool relative, uint32_t rel_run, uint32_t rel_ms)
{
    if (dd == NULL || dd->segments || values == NULL || !wire_drain_data_room(dd)) {
        return false;
    }
    if (dd->rows != 0) {
        wr_char(&dd->w, ',');
    }
    char tmp[32];
    wr_char(&dd->w, '[');
    wr_json_string(&dd->w, or_empty(session));
    wr_char(&dd->w, ',');
    wire_format_index(index, tmp, sizeof(tmp));
    wr_json_string(&dd->w, tmp);
    wr_char(&dd->w, ',');
    wr_json_string(&dd->w, or_empty(timestamp));
    for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
        wr_char(&dd->w, ',');
        /* Two decimals, three for the particle size: exactly the store's own
         * quantum, which is what lets the digest be taken over the integers and
         * still agree with what a reader parses out of the cell. */
        const unsigned decimals = f == SPS30_MEASUREMENT_FIELDS - 1 ? 3u : 2u;
        if (!wire_format_fixed(values->values[f], decimals, tmp, sizeof(tmp))) {
            snprintf(tmp, sizeof(tmp), decimals == 3u ? "%.3f" : "%.2f",
                     (double)values->values[f]);
        }
        wr_json_string(&dd->w, tmp);
    }
    wr_char(&dd->w, ',');
    wr_json_string(&dd->w, or_empty(event));
    /* Empty rather than zero on a dated reading: zero is a run marker a board
     * can genuinely have, and a cell that says "not applicable" must not be a
     * value a reader could act on. */
    wr_char(&dd->w, ',');
    if (relative) {
        snprintf(tmp, sizeof(tmp), "%" PRIu32, rel_run);
        wr_json_string(&dd->w, tmp);
        wr_char(&dd->w, ',');
        snprintf(tmp, sizeof(tmp), "%" PRIu32, rel_ms);
        wr_json_string(&dd->w, tmp);
    } else {
        wr_json_string(&dd->w, "");
        wr_char(&dd->w, ',');
        wr_json_string(&dd->w, "");
    }
    wr_char(&dd->w, ']');
    dd->rows++;
    return dd->w.ok;
}

bool wire_drain_data_segment(wire_drain_data_t *dd, const wire_alert_segment_t *segment,
                             int64_t start_s, int64_t stop_s)
{
    if (dd == NULL || !dd->segments || segment == NULL || !wire_drain_data_room(dd)) {
        return false;
    }
    if (dd->rows != 0) {
        wr_char(&dd->w, ',');
    }
    char tmp[32];
    wr_char(&dd->w, '[');
    snprintf(tmp, sizeof(tmp), "%" PRIu32, segment->seq);
    wr_json_string(&dd->w, tmp);
    wr_char(&dd->w, ',');
    wr_json_string(&dd->w, or_empty(segment->session));
    wr_char(&dd->w, ',');
    wr_json_string(&dd->w, or_empty(segment->field));
    wr_char(&dd->w, ',');
    wr_exact_cell(&dd->w, segment->threshold);
    wr_char(&dd->w, ',');
    snprintf(tmp, sizeof(tmp), "%lld", (long long)start_s);
    wr_json_string(&dd->w, tmp);
    wr_char(&dd->w, ',');
    snprintf(tmp, sizeof(tmp), "%lld", (long long)stop_s);
    wr_json_string(&dd->w, tmp);
    const double stats[7] = {
        segment->first, segment->last,    segment->midpoint, segment->mean,
        segment->variance, segment->minimum, segment->maximum,
    };
    for (size_t i = 0; i < 7; i++) {
        wr_char(&dd->w, ',');
        wr_exact_cell(&dd->w, stats[i]);
    }
    const uint32_t counts[4] = {
        segment->points, segment->first_index, segment->last_index, segment->wrap,
    };
    for (size_t i = 0; i < 4; i++) {
        wr_char(&dd->w, ',');
        snprintf(tmp, sizeof(tmp), "%" PRIu32, counts[i]);
        wr_json_string(&dd->w, tmp);
    }
    wr_char(&dd->w, ',');
    wr_json_string(&dd->w, segment->has_body ? "1" : "");
    wr_char(&dd->w, ']');
    dd->rows++;
    return dd->w.ok;
}

int wire_drain_data_close(wire_drain_data_t *dd, bool done)
{
    if (dd == NULL) {
        return -1;
    }
    wr_char(&dd->w, ']');
    wr_key(&dd->w, "done");
    wr_bool(&dd->w, done);
    return wr_finish(&dd->w);
}

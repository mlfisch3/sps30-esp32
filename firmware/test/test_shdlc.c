/**
 * @file test_shdlc.c
 * @brief Host tests for the pure SHDLC codec.
 *
 * Two kinds of test live here.
 *
 * 1. **Cross-checks against sps30.py.** `vectors_generated.h` is produced by
 *    running the real, field-proven Python implementation, so these assert the
 *    C port is byte-for-byte identical to the code that has actually been
 *    reading this sensor. This is the part worth trusting.
 *
 * 2. **Malformed-input tests**, written here rather than generated, because the
 *    Python signals those by raising and there are no bytes to compare. Each
 *    one names the Python behaviour it mirrors.
 *
 * Builds and runs on the host with no ESP-IDF and no hardware: see the Makefile.
 */

#include <stdio.h>
#include <string.h>

#include "shdlc.h"
#include "sps30_codec.h"
#include "vectors_generated.h"

static int g_checks = 0;
static int g_failures = 0;

/* The message is passed straight to printf so that a check with no format
 * arguments stays valid under -Wpedantic, which rejects the GNU zero-argument
 * __VA_ARGS__ extension. */
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

static void print_hex(const char *label, const uint8_t *b, size_t n)
{
    printf("    %s [%zu]:", label, n);
    for (size_t i = 0; i < n; i++) {
        printf(" %02x", b[i]);
    }
    printf("\n");
}

/** Compare against expected bytes, dumping both on a mismatch. */
static void check_bytes(const char *what, const uint8_t *got, size_t got_len,
                        const uint8_t *want, size_t want_len)
{
    g_checks++;
    if (got_len == want_len && (want_len == 0 || memcmp(got, want, want_len) == 0)) {
        return;
    }
    g_failures++;
    printf("  FAIL %s: bytes differ\n", what);
    print_hex("got ", got, got_len);
    print_hex("want", want, want_len);
}

/* --- 1. cross-checks against the Python reference ------------------------- */

static void test_checksums_match_python(void)
{
    printf("checksum: %zu vectors from sps30.checksum()\n",
           sizeof(CHECKSUM_VECTORS) / sizeof(CHECKSUM_VECTORS[0]));

    for (size_t i = 0; i < sizeof(CHECKSUM_VECTORS) / sizeof(CHECKSUM_VECTORS[0]); i++) {
        const checksum_vector_t *v = &CHECKSUM_VECTORS[i];
        uint8_t got = shdlc_checksum(v->input, v->input_len);
        CHECK(got == v->expected, "checksum vector %zu: got 0x%02x want 0x%02x", i,
              got, v->expected);
    }
}

static void test_stuffing_matches_python(void)
{
    size_t n = sizeof(STUFF_VECTORS) / sizeof(STUFF_VECTORS[0]);
    printf("stuffing: %zu vectors from sps30.stuff()/unstuff()\n", n);

    for (size_t i = 0; i < n; i++) {
        const stuff_vector_t *v = &STUFF_VECTORS[i];
        uint8_t buf[SHDLC_MAX_FRAME];
        size_t len = 0;

        CHECK(shdlc_stuff(v->raw, v->raw_len, buf, sizeof(buf), &len) == SHDLC_OK,
              "%s: stuff failed", v->name);
        check_bytes(v->name, buf, len, v->stuffed, v->stuffed_len);

        /* And back again, to the byte. */
        uint8_t back[SHDLC_MAX_FRAME];
        size_t back_len = 0;
        CHECK(shdlc_unstuff(v->stuffed, v->stuffed_len, back, sizeof(back), &back_len) == SHDLC_OK,
              "%s: unstuff failed", v->name);
        check_bytes(v->name, back, back_len, v->raw, v->raw_len);
    }
}

static void test_mosi_frames_match_python(void)
{
    size_t n = sizeof(MOSI_VECTORS) / sizeof(MOSI_VECTORS[0]);
    printf("MOSI frames: %zu vectors from sps30.build_frame()\n", n);

    for (size_t i = 0; i < n; i++) {
        const mosi_vector_t *v = &MOSI_VECTORS[i];
        uint8_t buf[SHDLC_MAX_FRAME];
        size_t len = 0;

        shdlc_err_t err = shdlc_frame(v->cmd, v->data, v->data_len, buf, sizeof(buf), &len);
        CHECK(err == SHDLC_OK, "%s: shdlc_frame failed: %s", v->name, shdlc_strerror(err));
        check_bytes(v->name, buf, len, v->frame, v->frame_len);

        /* The frame we just built must survive our own reader. */
        uint8_t inner[SHDLC_MAX_INNER];
        size_t inner_len = 0;
        err = shdlc_unframe(buf, len, inner, sizeof(inner), &inner_len);
        CHECK(err == SHDLC_OK, "%s: unframe of own frame failed: %s", v->name,
              shdlc_strerror(err));
        CHECK(inner_len == 4 + v->data_len, "%s: inner length %zu", v->name, inner_len);
        CHECK(inner[1] == v->cmd, "%s: cmd echo", v->name);
        CHECK(inner[2] == v->data_len, "%s: length field", v->name);
        if (v->data_len > 0) {
            check_bytes(v->name, &inner[3], v->data_len, v->data, v->data_len);
        }
    }
}

static void test_miso_responses_match_python(void)
{
    size_t n = sizeof(MISO_VECTORS) / sizeof(MISO_VECTORS[0]);
    printf("MISO frames: %zu vectors validated by sps30.parse_response()\n", n);

    for (size_t i = 0; i < n; i++) {
        const miso_vector_t *v = &MISO_VECTORS[i];
        uint8_t inner[SHDLC_MAX_INNER];
        size_t inner_len = 0;
        shdlc_response_t resp;

        shdlc_err_t err = shdlc_unframe(v->frame, v->frame_len, inner, sizeof(inner), &inner_len);
        CHECK(err == SHDLC_OK, "%s: unframe failed: %s", v->name, shdlc_strerror(err));
        if (err != SHDLC_OK) {
            continue;
        }

        err = shdlc_parse_response(inner, inner_len, &resp);
        CHECK(err == SHDLC_OK, "%s: parse failed: %s", v->name, shdlc_strerror(err));
        if (err != SHDLC_OK) {
            continue;
        }

        CHECK(resp.cmd == v->cmd, "%s: cmd got 0x%02x want 0x%02x", v->name, resp.cmd, v->cmd);
        CHECK(resp.state == v->state, "%s: state got 0x%02x want 0x%02x", v->name,
              resp.state, v->state);
        check_bytes(v->name, resp.data, resp.data_len, v->data, v->data_len);
    }
}

/**
 * The first milestone, decoded end to end from bytes alone: the serial number
 * and firmware version this exact sensor is known to return.
 */
static void test_first_milestone_decoding(void)
{
    printf("first milestone: decode the known serial number and firmware\n");

    for (size_t i = 0; i < sizeof(MISO_VECTORS) / sizeof(MISO_VECTORS[0]); i++) {
        const miso_vector_t *v = &MISO_VECTORS[i];
        uint8_t inner[SHDLC_MAX_INNER];
        size_t inner_len = 0;
        shdlc_response_t resp;

        if (shdlc_unframe(v->frame, v->frame_len, inner, sizeof(inner), &inner_len) != SHDLC_OK) {
            continue;
        }
        if (shdlc_parse_response(inner, inner_len, &resp) != SHDLC_OK) {
            continue;
        }

        if (strcmp(v->name, "serial_number_E3164D2544338487") == 0) {
            /* The device NUL-terminates inside the payload, as _ascii() assumes. */
            char text[64] = {0};
            size_t copy = resp.data_len < sizeof(text) - 1 ? resp.data_len : sizeof(text) - 1;
            memcpy(text, resp.data, copy);
            CHECK(strcmp(text, "E3164D2544338487") == 0,
                  "serial number decoded as \"%s\"", text);
            printf("    serial number  : %s\n", text);
        }

        if (strcmp(v->name, "firmware_version_2_1") == 0) {
            CHECK(resp.data_len >= 2, "firmware payload too short");
            CHECK(resp.data[0] == 2 && resp.data[1] == 1, "firmware got %u.%u want 2.1",
                  resp.data[0], resp.data[1]);
            printf("    firmware       : %u.%u\n", resp.data[0], resp.data[1]);
        }
    }
}

/* --- 2. malformed input --------------------------------------------------- */

static void test_malformed_input(void)
{
    printf("malformed input: the cases sps30.py raises SPS30Error on\n");

    uint8_t out[SHDLC_MAX_FRAME];
    size_t out_len = 0;
    shdlc_response_t resp;

    /* A known-good frame to mutate: the serial-number response. */
    const miso_vector_t *good = &MISO_VECTORS[0];
    uint8_t frame[SHDLC_MAX_FRAME];
    size_t frame_len = good->frame_len;
    memcpy(frame, good->frame, frame_len);

    /* Sanity: unmutated, it parses. */
    CHECK(shdlc_unframe(frame, frame_len, out, sizeof(out), &out_len) == SHDLC_OK,
          "control frame should unframe");

    /* Truncated frame - the STOP delimiter is simply gone. */
    CHECK(shdlc_unframe(frame, frame_len - 1, out, sizeof(out), &out_len)
              == SHDLC_ERR_BAD_DELIMITER,
          "truncated frame should be rejected");

    /* Missing STOP: right length, wrong last byte. */
    uint8_t no_stop[SHDLC_MAX_FRAME];
    memcpy(no_stop, frame, frame_len);
    no_stop[frame_len - 1] = 0x00;
    CHECK(shdlc_unframe(no_stop, frame_len, out, sizeof(out), &out_len)
              == SHDLC_ERR_BAD_DELIMITER,
          "missing STOP should be rejected");

    /* Missing START. */
    uint8_t no_start[SHDLC_MAX_FRAME];
    memcpy(no_start, frame, frame_len);
    no_start[0] = 0x00;
    CHECK(shdlc_unframe(no_start, frame_len, out, sizeof(out), &out_len)
              == SHDLC_ERR_BAD_DELIMITER,
          "missing START should be rejected");

    /* Nothing but delimiters, and a single byte. */
    const uint8_t empty_frame[] = {0x7E, 0x7E};
    CHECK(shdlc_unframe(empty_frame, sizeof(empty_frame), out, sizeof(out), &out_len) == SHDLC_OK,
          "an empty frame un-frames to nothing");
    CHECK(out_len == 0, "empty frame yields no inner bytes");
    CHECK(shdlc_parse_response(out, out_len, &resp) == SHDLC_ERR_TOO_SHORT,
          "empty inner is too short to parse");

    const uint8_t one_byte[] = {0x7E};
    CHECK(shdlc_unframe(one_byte, sizeof(one_byte), out, sizeof(out), &out_len)
              == SHDLC_ERR_TOO_SHORT,
          "a lone delimiter is too short");

    /* Bad checksum: flip the last inner byte. */
    uint8_t inner[SHDLC_MAX_INNER];
    size_t inner_len = 0;
    CHECK(shdlc_unframe(frame, frame_len, inner, sizeof(inner), &inner_len) == SHDLC_OK,
          "setup for checksum test");
    inner[inner_len - 1] ^= 0xFF;
    CHECK(shdlc_parse_response(inner, inner_len, &resp) == SHDLC_ERR_BAD_CHECKSUM,
          "corrupted checksum should be rejected");

    /* Every single-bit corruption of the inner bytes must be caught, either by
     * the checksum or by the length test. A silent accept here would mean the
     * driver acting on a corrupt reading. */
    CHECK(shdlc_unframe(frame, frame_len, inner, sizeof(inner), &inner_len) == SHDLC_OK,
          "setup for bit-flip sweep");
    uint8_t pristine[SHDLC_MAX_INNER];
    memcpy(pristine, inner, inner_len);
    int undetected = 0;
    int flips = 0;
    for (size_t i = 0; i < inner_len; i++) {
        for (int bit = 0; bit < 8; bit++) {
            memcpy(inner, pristine, inner_len);
            inner[i] ^= (uint8_t)(1u << bit);
            flips++;
            if (shdlc_parse_response(inner, inner_len, &resp) == SHDLC_OK) {
                /* The address byte is not covered by anything but the checksum,
                 * so every flip should be caught. */
                undetected++;
            }
        }
    }
    CHECK(undetected == 0, "%d of %d single-bit corruptions went undetected",
          undetected, flips);
    printf("    %d single-bit corruptions, all rejected\n", flips);

    /* ESC at the very end, with nothing to escape. */
    const uint8_t trailing_esc[] = {0x00, 0xD0, 0x00, 0x7D};
    CHECK(shdlc_unstuff(trailing_esc, sizeof(trailing_esc), out, sizeof(out), &out_len)
              == SHDLC_ERR_BAD_STUFFING,
          "a trailing ESC should be rejected");

    /* An ESC inside a frame with nothing after it. */
    const uint8_t frame_trailing_esc[] = {0x7E, 0x00, 0xD0, 0x7D, 0x7E};
    CHECK(shdlc_unframe(frame_trailing_esc, sizeof(frame_trailing_esc), out, sizeof(out), &out_len)
              == SHDLC_ERR_BAD_STUFFING,
          "a frame ending in ESC should be rejected");

    /* ESC followed by a byte that is not one of the four escape codes. */
    for (int b = 0; b < 256; b++) {
        if (b == 0x5E || b == 0x5D || b == 0x31 || b == 0x33) {
            continue;
        }
        uint8_t bad[] = {0x00, 0x7D, (uint8_t)b};
        CHECK(shdlc_unstuff(bad, sizeof(bad), out, sizeof(out), &out_len)
                  == SHDLC_ERR_BAD_STUFFING,
              "ESC 0x%02x should be rejected", b);
    }

    /* Declared length overruns the frame. */
    const uint8_t overrun[] = {0x00, 0xD0, 0x00, 0x40, 0x01, 0x02};
    CHECK(shdlc_parse_response(overrun, sizeof(overrun), &resp) == SHDLC_ERR_BAD_LENGTH,
          "a length past the end should be rejected");

    /* Declared length leaves no room for the checksum byte - one short. */
    const uint8_t no_room_for_checksum[] = {0x00, 0xD0, 0x00, 0x02, 0x01, 0x02};
    CHECK(shdlc_parse_response(no_room_for_checksum, sizeof(no_room_for_checksum), &resp)
              == SHDLC_ERR_BAD_LENGTH,
          "a length leaving no checksum byte should be rejected");

    /* Inner shorter than the five-byte minimum. */
    for (size_t n = 0; n < 5; n++) {
        const uint8_t short_inner[5] = {0x00, 0xD0, 0x00, 0x00, 0xFF};
        CHECK(shdlc_parse_response(short_inner, n, &resp) == SHDLC_ERR_TOO_SHORT,
              "inner of %zu bytes should be too short", n);
    }

    /* Destination too small, at each layer. */
    uint8_t tiny[2];
    size_t tiny_len = 0;
    const uint8_t needs_room[] = {0x7E, 0x7E, 0x7E};
    CHECK(shdlc_stuff(needs_room, sizeof(needs_room), tiny, sizeof(tiny), &tiny_len)
              == SHDLC_ERR_BUFFER_TOO_SMALL,
          "stuff into a short buffer should be rejected");
    CHECK(shdlc_frame(0xD0, NULL, 0, tiny, sizeof(tiny), &tiny_len)
              == SHDLC_ERR_BUFFER_TOO_SMALL,
          "frame into a short buffer should be rejected");
    CHECK(shdlc_unframe(good->frame, good->frame_len, tiny, sizeof(tiny), &tiny_len)
              == SHDLC_ERR_BUFFER_TOO_SMALL,
          "unframe into a short buffer should be rejected");

    /* Data longer than the one-byte length field can express. */
    uint8_t big[SHDLC_MAX_DATA + 1] = {0};
    CHECK(shdlc_frame(0xD0, big, sizeof(big), out, sizeof(out), &out_len)
              == SHDLC_ERR_DATA_TOO_LONG,
          "data over 255 bytes should be rejected");
}

/* --- 3. exhaustive round trips -------------------------------------------- */

static void test_round_trip_every_byte(void)
{
    printf("round trip: every byte value as a single-byte payload\n");

    for (int b = 0; b < 256; b++) {
        uint8_t data = (uint8_t)b;
        uint8_t buf[SHDLC_MAX_FRAME];
        uint8_t inner[SHDLC_MAX_INNER];
        size_t len = 0, inner_len = 0;

        CHECK(shdlc_frame(0x80, &data, 1, buf, sizeof(buf), &len) == SHDLC_OK,
              "frame with data 0x%02x", b);
        CHECK(shdlc_unframe(buf, len, inner, sizeof(inner), &inner_len) == SHDLC_OK,
              "unframe with data 0x%02x", b);
        /* inner is [ADDR, cmd, length, data, checksum] - the payload is at 3. */
        CHECK(inner_len == 5 && inner[3] == data, "round trip of data 0x%02x", b);
    }
}

/**
 * Every combination of length 1..4 over the bytes that matter: the four that
 * get escaped, the four escape codes they turn into (which must *not* be
 * escaped when they appear on their own), and two ordinary bytes.
 */
static void test_round_trip_combinations(void)
{
    static const uint8_t alphabet[] = {0x7E, 0x7D, 0x11, 0x13, 0x5E,
                                       0x5D, 0x31, 0x33, 0x00, 0xFF};
    const size_t n = sizeof(alphabet);
    size_t cases = 0;

    uint8_t raw[4];
    for (size_t len = 1; len <= 4; len++) {
        size_t total = 1;
        for (size_t k = 0; k < len; k++) {
            total *= n;
        }
        for (size_t combo = 0; combo < total; combo++) {
            size_t rest = combo;
            for (size_t k = 0; k < len; k++) {
                raw[k] = alphabet[rest % n];
                rest /= n;
            }

            uint8_t stuffed[SHDLC_MAX_FRAME];
            uint8_t back[SHDLC_MAX_FRAME];
            size_t stuffed_len = 0, back_len = 0;

            if (shdlc_stuff(raw, len, stuffed, sizeof(stuffed), &stuffed_len) != SHDLC_OK ||
                shdlc_unstuff(stuffed, stuffed_len, back, sizeof(back), &back_len) != SHDLC_OK ||
                back_len != len || memcmp(back, raw, len) != 0) {
                g_checks++;
                g_failures++;
                printf("  FAIL stuff/unstuff round trip failed\n");
                print_hex("raw", raw, len);
                print_hex("stuffed", stuffed, stuffed_len);
                return;
            }

            /* No escaped byte may survive stuffing raw. */
            for (size_t k = 0; k < stuffed_len; k++) {
                if (stuffed[k] == 0x7E || stuffed[k] == 0x11 || stuffed[k] == 0x13) {
                    g_checks++;
                    g_failures++;
                    printf("  FAIL stuffed output still contains 0x%02x\n", stuffed[k]);
                    print_hex("raw", raw, len);
                    return;
                }
            }
            cases++;
        }
    }

    g_checks++;
    printf("round trip: %zu stuffing combinations over the tricky alphabet, all clean\n", cases);
}

/* --- measurement decoding ------------------------------------------------- */

/**
 * The ten floats, compared as bit patterns against sps30.decode_measurement().
 *
 * Bit patterns rather than a tolerance: the failure this is guarding against is
 * a byte-order mistake, and half of those still agree to six significant
 * figures. An exact comparison either matches or it does not.
 */
static void test_measurement_decoding_matches_python(void)
{
    const size_t n = sizeof(MEASUREMENT_VECTORS) / sizeof(MEASUREMENT_VECTORS[0]);
    for (size_t i = 0; i < n; i++) {
        const measurement_vector_t *v = &MEASUREMENT_VECTORS[i];
        sps30_measurement_t m;

        const sps30_decode_t rc =
            sps30_decode_measurement(v->payload, v->payload_len, &m);
        CHECK(rc == SPS30_DECODE_OK, "%s: decode returned %d", v->name, (int)rc);
        if (rc != SPS30_DECODE_OK) {
            continue;
        }

        for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
            uint32_t got_bits;
            memcpy(&got_bits, &m.values[f], sizeof(got_bits));
            CHECK(got_bits == v->expected[f],
                  "%s field %zu (%s): got 0x%08x want 0x%08x", v->name, f,
                  SPS30_FIELD_NAMES[f], got_bits, v->expected[f]);
        }
    }
    printf("measurement: %zu payloads decoded bit-for-bit as sps30.py does\n", n);
}

/** The field order is a contract shared across four implementations. */
static void test_field_order_matches_python(void)
{
    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        CHECK(strcmp(SPS30_FIELD_NAMES[i], PYTHON_FIELD_ORDER[i]) == 0,
              "field %zu: C says %s, sps30.py says %s", i, SPS30_FIELD_NAMES[i],
              PYTHON_FIELD_ORDER[i]);
    }

    /* The named fields and values[] must be the same storage, or the wire order
     * and the named access could drift apart without anything noticing. */
    sps30_measurement_t m;
    memset(&m, 0, sizeof(m));
    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        m.values[i] = (float)(i + 1);
    }
    CHECK(m.pm1_0 == 1.0f, "pm1_0 does not overlay values[0]");
    CHECK(m.pm2_5 == 2.0f, "pm2_5 does not overlay values[1]");
    CHECK(m.pm4_0 == 3.0f, "pm4_0 does not overlay values[2]");
    CHECK(m.pm10 == 4.0f, "pm10 does not overlay values[3]");
    CHECK(m.nc0_5 == 5.0f, "nc0_5 does not overlay values[4]");
    CHECK(m.nc1_0 == 6.0f, "nc1_0 does not overlay values[5]");
    CHECK(m.nc2_5 == 7.0f, "nc2_5 does not overlay values[6]");
    CHECK(m.nc4_0 == 8.0f, "nc4_0 does not overlay values[7]");
    CHECK(m.nc10 == 9.0f, "nc10 does not overlay values[8]");
    CHECK(m.typical_size == 10.0f, "typical_size does not overlay values[9]");

    printf("field order: matches sps30.py's Measurement, and values[] overlays it\n");
}

/**
 * The lengths the Python treats differently, which is the easy thing to break.
 *
 * An empty payload means "no new value ready yet" and `decode_measurement`
 * returns None for it - not an error. Any other wrong length *is* an error and
 * raises. Collapsing the two would either tear down a healthy session several
 * times a second or swallow a genuine protocol fault.
 */
static void test_measurement_lengths(void)
{
    sps30_measurement_t m;
    uint8_t buf[64];
    memset(buf, 0, sizeof(buf));

    CHECK(sps30_decode_measurement(buf, 0, &m) == SPS30_DECODE_NOT_READY,
          "empty payload should mean not-ready, as sps30.py returns None");
    CHECK(sps30_decode_measurement(NULL, 0, &m) == SPS30_DECODE_NOT_READY,
          "not-ready is signalled by length alone, so NULL data is fine there");

    for (size_t len = 1; len <= sizeof(buf); len++) {
        if (len == SPS30_MEASUREMENT_BYTES) {
            CHECK(sps30_decode_measurement(buf, len, &m) == SPS30_DECODE_OK,
                  "%zu bytes should decode", len);
        } else {
            CHECK(sps30_decode_measurement(buf, len, &m) == SPS30_DECODE_BAD_LENGTH,
                  "%zu bytes should be rejected, as sps30.py raises", len);
        }
    }

    CHECK(sps30_decode_measurement(buf, SPS30_MEASUREMENT_BYTES, NULL) ==
              SPS30_DECODE_BAD_LENGTH,
          "a NULL destination must not be written through");

    printf("measurement lengths: empty is not-ready, 40 decodes, everything else rejected\n");
}

/** A single big-endian float, over the whole 32-bit space in coarse steps. */
static void test_float_byte_order(void)
{
    /* 1.0f is 0x3F800000 big-endian; getting this backwards is the classic
     * port bug and it is worth one explicit, readable case. */
    const uint8_t one[4] = {0x3F, 0x80, 0x00, 0x00};
    CHECK(sps30_decode_float_be(one) == 1.0f, "0x3F800000 should decode to 1.0");

    const uint8_t reversed[4] = {0x00, 0x00, 0x80, 0x3F};
    CHECK(sps30_decode_float_be(reversed) != 1.0f,
          "little-endian bytes must not decode to 1.0");

    /* Every bit pattern the payload could carry, sampled: assemble the bits the
     * way the sensor sends them and check the decode agrees with the bits. */
    size_t cases = 0;
    for (uint64_t bits = 0; bits <= 0xFFFFFFFFull; bits += 0x00010001ull) {
        const uint32_t b = (uint32_t)bits;
        const uint8_t bytes[4] = {
            (uint8_t)(b >> 24), (uint8_t)(b >> 16), (uint8_t)(b >> 8), (uint8_t)b,
        };
        const float f = sps30_decode_float_be(bytes);
        uint32_t round;
        memcpy(&round, &f, sizeof(round));
        if (round != b) {
            CHECK(false, "bit pattern 0x%08x did not survive the decode", b);
            return;
        }
        cases++;
    }
    g_checks++;
    printf("float order: %zu big-endian bit patterns decoded exactly\n", cases);
}

/** Plausibility rejects what a corrupt frame produces, not what air produces. */
static void test_plausibility(void)
{
    sps30_measurement_t m;

    /* Real indoor air, and the datasheet's full scale: both must pass. */
    const float indoor[10] = {1.02f, 1.18f, 1.24f, 1.27f, 6.94f,
                              8.03f, 8.19f, 8.22f, 8.23f, 0.48f};
    memcpy(m.values, indoor, sizeof(indoor));
    CHECK(sps30_measurement_plausible(&m), "typical indoor air must be plausible");

    const float zero[10] = {0};
    memcpy(m.values, zero, sizeof(zero));
    CHECK(sps30_measurement_plausible(&m), "all-zero (clean air) must be plausible");

    const float full[10] = {1000.0f, 1000.0f, 1000.0f, 1000.0f, 3000.0f,
                            3000.0f, 3000.0f, 3000.0f, 3000.0f, 10.0f};
    memcpy(m.values, full, sizeof(full));
    CHECK(sps30_measurement_plausible(&m), "datasheet full scale must be plausible");

    /* What a corrupted float actually looks like. */
    memcpy(m.values, indoor, sizeof(indoor));
    m.pm2_5 = -1.0f;
    CHECK(!sps30_measurement_plausible(&m), "a negative concentration is impossible");

    memcpy(m.values, indoor, sizeof(indoor));
    m.pm10 = 1e30f;
    CHECK(!sps30_measurement_plausible(&m), "1e30 ug/m3 is impossible");

    memcpy(m.values, indoor, sizeof(indoor));
    m.nc0_5 = 1e30f;
    CHECK(!sps30_measurement_plausible(&m), "1e30 #/cm3 is impossible");

    memcpy(m.values, indoor, sizeof(indoor));
    m.typical_size = 1000.0f;
    CHECK(!sps30_measurement_plausible(&m), "a 1000 um particle is impossible");

    /* NaN and the infinities, which no comparison catches by accident. */
    const uint8_t nan_be[4] = {0x7F, 0xC0, 0x00, 0x00};
    const uint8_t inf_be[4] = {0x7F, 0x80, 0x00, 0x00};
    memcpy(m.values, indoor, sizeof(indoor));
    m.pm1_0 = sps30_decode_float_be(nan_be);
    CHECK(!sps30_measurement_plausible(&m), "NaN must be rejected");
    memcpy(m.values, indoor, sizeof(indoor));
    m.pm1_0 = sps30_decode_float_be(inf_be);
    CHECK(!sps30_measurement_plausible(&m), "infinity must be rejected");

    CHECK(!sps30_measurement_plausible(NULL), "NULL is not plausible");

    /* Every generated vector that is meant to be real data must pass, so the
     * bounds cannot quietly start rejecting readings the sensor really sends. */
    for (size_t i = 0; i < 4; i++) {
        const measurement_vector_t *v = &MEASUREMENT_VECTORS[i];
        sps30_measurement_t decoded;
        if (sps30_decode_measurement(v->payload, v->payload_len, &decoded) ==
            SPS30_DECODE_OK) {
            CHECK(sps30_measurement_plausible(&decoded),
                  "%s is real sensor data and must pass plausibility", v->name);
        }
    }

    printf("plausibility: real readings pass, corrupt floats and NaN are rejected\n");
}

int main(void)
{
    printf("SHDLC codec host tests\n");
    printf("expectations generated from the field-proven sps30.py\n\n");

    test_checksums_match_python();
    test_stuffing_matches_python();
    test_mosi_frames_match_python();
    test_miso_responses_match_python();
    test_first_milestone_decoding();
    test_malformed_input();
    test_round_trip_every_byte();
    test_round_trip_combinations();
    test_measurement_decoding_matches_python();
    test_field_order_matches_python();
    test_measurement_lengths();
    test_float_byte_order();
    test_plausibility();

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    if (g_failures == 0) {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL\n");
    return 1;
}

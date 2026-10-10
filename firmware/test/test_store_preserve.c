/**
 * @file test_store_preserve.c
 * @brief What survives a wrap, and what it says about itself afterwards.
 *
 * The captain's storage-wrap design has one claim worth proving and it is not
 * "alert data is copied somewhere". It is: **as the ring destroys ordinary
 * history, alert segments survive it, and their headers survive even the loss of
 * their own samples.** That is a statement about what is left after a ring has
 * turned over, which is the one thing you cannot check by inspecting the code.
 *
 * So this suite fills the preservation area, wraps both of its rings, and reads
 * back what is there. `store_preserve.c` reaches flash through three function
 * pointers rather than through `esp_partition`, which is what makes that
 * possible here at all - wrapping the body ring on the board takes two days of
 * continuous 1 Hz recording.
 *
 * Build and run with `make -C firmware/test`. No ESP-IDF, no board, no flash.
 */

#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "store_alert.h"
#include "store_codec.h"
#include "store_preserve.h"

static int failures = 0;
static int checks = 0;

#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        checks++;                                                              \
        if (!(cond)) {                                                         \
            failures++;                                                        \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);                      \
            printf(__VA_ARGS__);                                               \
            printf("\n");                                                      \
        }                                                                      \
    } while (0)

#define CLOSE(a, b, eps) (fabs((double)(a) - (double)(b)) <= (eps))

/* --- a flash part that behaves like one ------------------------------------ */

/**
 * Small on purpose: four sectors of headers and eight of samples.
 *
 * The shipped area is sixty-four sectors and would take an unreasonable number
 * of segments to turn over. The code does not know the difference - every bound
 * in it comes from ::store_preserve_dev_t - so a small one wraps in a few
 * hundred writes and proves the same thing.
 */
#define SIM_HEADER_SECTORS 2
#define SIM_BODY_SECTORS 4
#define SIM_SECTORS (SIM_HEADER_SECTORS + SIM_BODY_SECTORS)
#define SIM_BYTES (SIM_SECTORS * STORE_PRESERVE_SECTOR_BYTES)
#define SIM_HEADER_BYTES (SIM_HEADER_SECTORS * STORE_PRESERVE_SECTOR_BYTES)

typedef struct {
    uint8_t bytes[SIM_BYTES];
    uint32_t erases;
    uint32_t writes;
    bool refuse_writes;
} sim_flash_t;

static sim_flash_t sim;

static bool sim_read(void *ctx, size_t offset, uint8_t *out, size_t len)
{
    (void)ctx;
    if (offset + len > SIM_BYTES) {
        return false;
    }
    memcpy(out, sim.bytes + offset, len);
    return true;
}

static bool sim_write(void *ctx, size_t offset, const uint8_t *data, size_t len)
{
    (void)ctx;
    if (sim.refuse_writes || offset + len > SIM_BYTES) {
        return false;
    }
    memcpy(sim.bytes + offset, data, len);
    sim.writes++;
    return true;
}

static bool sim_erase(void *ctx, size_t offset, size_t len)
{
    (void)ctx;
    if (offset + len > SIM_BYTES || offset % STORE_PRESERVE_SECTOR_BYTES != 0) {
        return false;
    }
    /* Erased NOR flash reads as 0xFF, not as zero. Getting this wrong would make
     * a blank sector decode as a plausible record in half the tests here. */
    memset(sim.bytes + offset, 0xFF, len);
    sim.erases++;
    return true;
}

static const store_preserve_dev_t SIM_DEV = {
    .size = SIM_BYTES,
    .header_bytes = SIM_HEADER_BYTES,
    .ctx = NULL,
    .read = sim_read,
    .write = sim_write,
    .erase = sim_erase,
};

static void sim_reset(void)
{
    memset(&sim, 0xFF, sizeof(sim.bytes));
    sim.erases = 0;
    sim.writes = 0;
    sim.refuse_writes = false;
}

/* --- readings -------------------------------------------------------------- */

static store_reading_t reading_at(uint32_t index, float pm2_5)
{
    store_reading_t r;
    memset(&r, 0, sizeof(r));
    r.index = index;
    r.epoch_ms = 1700000000000LL + (int64_t)(index - 1) * 1000;
    for (int f = 0; f < STORE_FIELDS; f++) {
        r.values[f] = 1.0f + (float)f * 0.5f;
    }
    r.values[STORE_ALERT_FIELD_PM2_5] = pm2_5;
    return r;
}

/* --- the policy ------------------------------------------------------------ */

static void test_the_shipped_line_is_the_app_default(void)
{
    printf("the line a board with no phone preserves against is the app's own default\n");
    store_alert_policy_t p;
    store_alert_policy_default(&p);
    CHECK(p.threshold[STORE_ALERT_FIELD_PM2_5] == 20.0f, "pm2_5 default is not 20");
    for (int f = 0; f < STORE_FIELDS; f++) {
        if (f == STORE_ALERT_FIELD_PM2_5) {
            continue;
        }
        CHECK(p.threshold[f] == 0.0f, "field %d is watched and should not be", f);
    }
    CHECK(store_alert_policy_watches(&p), "the default policy watches nothing");

    const store_reading_t under = reading_at(1, 19.99f);
    const store_reading_t on = reading_at(2, 20.0f);
    CHECK(store_alert_trip(&p, under.values) < 0, "19.99 tripped a 20 line");
    CHECK(store_alert_trip(&p, on.values) == STORE_ALERT_FIELD_PM2_5,
          "20.0 did not trip a 20 line; the app fires at or above, so this must too");
}

static void test_a_line_that_is_not_a_number_watches_nothing(void)
{
    printf("a threshold of NaN, infinity or zero stops watching rather than watching always\n");
    store_alert_policy_t p;
    store_alert_policy_default(&p);

    CHECK(store_alert_policy_set(&p, 0, (float)NAN), "setting a NaN line was refused outright");
    const store_reading_t r = reading_at(1, 1.0f);
    /* `NaN >= x` is false, so a stored NaN would watch nothing while the policy
     * claimed to watch something. Folded to "not watched" instead. */
    CHECK(p.threshold[0] == 0.0f, "a NaN line was kept");
    CHECK(store_alert_trip(&p, r.values) < 0, "a low reading tripped");

    CHECK(store_alert_policy_set(&p, 3, INFINITY), "setting an infinite line was refused");
    CHECK(p.threshold[3] == 0.0f, "an infinite line was kept");

    CHECK(!store_alert_policy_set(&p, STORE_FIELDS, 1.0f), "a field past the end was accepted");
    CHECK(!store_alert_policy_set(&p, -1, 1.0f), "a negative field was accepted");

    store_alert_policy_set(&p, STORE_ALERT_FIELD_PM2_5, 0.0f);
    CHECK(!store_alert_policy_watches(&p), "clearing the only line still watches something");
}

static void test_the_lowest_field_wins_when_several_trip(void)
{
    printf("when two fields are over their lines the earlier one names the segment\n");
    store_alert_policy_t p;
    store_alert_policy_default(&p);
    store_alert_policy_set(&p, 0, 0.5f);  /* pm1_0, which is 1.0 in every reading */
    const store_reading_t r = reading_at(1, 99.0f);
    CHECK(store_alert_trip(&p, r.values) == 0,
          "pm2_5 named the segment when pm1_0 was also over; wire.FIELDS order decides");
}

/* --- the segment ----------------------------------------------------------- */

/** A segment over the values in @p pm, opened on the first one. */
static void accumulate(store_alert_seg_t *seg, const float *pm, uint32_t n)
{
    store_alert_policy_t p;
    store_alert_policy_default(&p);
    const store_reading_t first = reading_at(1, pm[0]);
    store_alert_seg_begin(seg, STORE_ALERT_FIELD_PM2_5, 20.0f, 7, 1700000000000LL, -300, false,
                          &first);
    for (uint32_t i = 1; i < n; i++) {
        const store_reading_t r = reading_at(i + 1, pm[i]);
        store_alert_seg_add(seg, &r);
    }
}

static void test_the_header_carries_every_figure_the_captain_listed(void)
{
    printf("start, stop, first, last, midpoint, mean, variance, min, max and count\n");
    const float pm[] = {25.0f, 30.0f, 40.0f, 22.0f, 33.0f};
    store_alert_seg_t seg;
    accumulate(&seg, pm, 5);

    store_alert_header_t h;
    store_alert_seg_header(&seg, &h);

    CHECK(h.points == 5, "points is %u, not 5", (unsigned)h.points);
    CHECK(h.first == 25.0f, "first is %f", (double)h.first);
    CHECK(h.last == 33.0f, "last is %f", (double)h.last);
    CHECK(h.minimum == 22.0f, "minimum is %f", (double)h.minimum);
    CHECK(h.maximum == 40.0f, "maximum is %f", (double)h.maximum);
    CHECK(CLOSE(h.mean, 30.0, 1e-4), "mean is %f, not 30", (double)h.mean);
    /* Population variance of {25,30,40,22,33} about 30: (25+0+100+64+9)/5. */
    CHECK(CLOSE(h.variance, 39.6, 1e-3), "variance is %f, not 39.6", (double)h.variance);
    CHECK(h.start_ms == 1700000000000LL, "start time is wrong");
    CHECK(h.stop_ms == 1700000004000LL, "stop time is wrong");
    CHECK(h.first_index == 1 && h.last_index == 5, "the index range is wrong");
    CHECK(h.field == STORE_ALERT_FIELD_PM2_5, "the header does not name the field");
    CHECK(h.threshold == 20.0f, "the header does not carry the line it was kept for");
    CHECK(h.session == 7 && h.session_start_ms == 1700000000000LL,
          "the header does not carry both halves of the session identity");
    CHECK(h.utc_offset_min == -300, "the offset in force was not carried");
}

static void test_the_variance_survives_a_series_that_barely_moves(void)
{
    printf("variance of a series near 20 with a spread of hundredths\n");
    float pm[200];
    for (int i = 0; i < 200; i++) {
        pm[i] = 20.0f + ((i % 2) ? 0.01f : -0.01f);
    }
    store_alert_seg_t seg;
    accumulate(&seg, pm, 200);
    store_alert_header_t h;
    store_alert_seg_header(&seg, &h);
    /* Exactly 0.0001. A naive sum of squares in single precision returns zero or
     * something negative here, which is why the accumulator is Welford's. */
    CHECK(CLOSE(h.variance, 1e-4, 2e-5), "variance is %g, not 1e-4", (double)h.variance);
    CHECK(h.variance > 0.0f, "the variance collapsed to zero");
}

static void test_hovering_on_the_line_is_one_segment_not_hundreds(void)
{
    printf("hysteresis: a value oscillating across the threshold stays one segment\n");
    store_alert_seg_t seg;
    const store_reading_t first = reading_at(1, 21.0f);
    store_alert_seg_begin(&seg, STORE_ALERT_FIELD_PM2_5, 20.0f, 1, 0, 0, false, &first);

    /* 19.0 is below the line and above the re-arm line (20 * 0.9 = 18). */
    for (uint32_t i = 2; i <= 100; i++) {
        const store_reading_t r = reading_at(i, (i % 2) ? 21.0f : 19.0f);
        store_alert_seg_add(&seg, &r);
        CHECK(!store_alert_seg_closing(&seg), "closed at reading %u while still hovering", i);
    }
    CHECK(seg.points == 100, "the hovering readings were not all kept");
}

static void test_a_segment_ends_once_the_air_is_properly_clear(void)
{
    printf("and it ends after a run below the re-arm line, keeping the shoulder\n");
    store_alert_seg_t seg;
    const store_reading_t first = reading_at(1, 50.0f);
    store_alert_seg_begin(&seg, STORE_ALERT_FIELD_PM2_5, 20.0f, 1, 0, 0, false, &first);

    uint32_t i = 2;
    for (; i < 2 + STORE_ALERT_HANG_POINTS - 1; i++) {
        const store_reading_t r = reading_at(i, 5.0f);
        store_alert_seg_add(&seg, &r);
        CHECK(!store_alert_seg_closing(&seg), "closed after only %u clear readings", i - 1);
    }
    const store_reading_t last = reading_at(i, 5.0f);
    store_alert_seg_add(&seg, &last);
    CHECK(store_alert_seg_closing(&seg), "never closed after a full clear run");
    CHECK(seg.points == STORE_ALERT_HANG_POINTS + 1,
          "the trailing clear readings were dropped; they are what shows it came back down");
    CHECK(seg.last == 5.0f, "the last value is not the one it ended on");
}

static void test_an_excursion_that_never_ends_is_still_headered(void)
{
    printf("and a segment that runs on for ever is capped rather than left unheadered\n");
    store_alert_seg_t seg;
    const store_reading_t first = reading_at(1, 50.0f);
    store_alert_seg_begin(&seg, STORE_ALERT_FIELD_PM2_5, 20.0f, 1, 0, 0, false, &first);
    for (uint32_t i = 2; i <= STORE_ALERT_MAX_POINTS; i++) {
        const store_reading_t r = reading_at(i, 50.0f);
        store_alert_seg_add(&seg, &r);
    }
    CHECK(store_alert_seg_closing(&seg), "a segment at the cap did not close");
}

static void test_a_gap_or_a_new_session_ends_the_segment(void)
{
    printf("a segment is one continuous run: a hole or another session ends it\n");
    store_alert_seg_t seg;
    const store_reading_t first = reading_at(10, 50.0f);
    store_alert_seg_begin(&seg, STORE_ALERT_FIELD_PM2_5, 20.0f, 7, 1700000000000LL, 0, false, &first);

    const store_reading_t next = reading_at(11, 50.0f);
    CHECK(store_alert_seg_continues(&seg, 7, 1700000000000LL, &next), "the next index did not");
    const store_reading_t hole = reading_at(13, 50.0f);
    CHECK(!store_alert_seg_continues(&seg, 7, 1700000000000LL, &hole), "a hole continued it");
    /* The session number restarts at 1 on every boot, so the start time is the
     * other half of the identity - and matching on the number alone is exactly
     * how readings get filed under the wrong recording. */
    CHECK(!store_alert_seg_continues(&seg, 7, 1699999999000LL, &next),
          "the same session number with a different start time continued it");
    CHECK(!store_alert_seg_continues(&seg, 8, 1700000000000LL, &next),
          "a different session continued it");
}

static void test_the_midpoint_is_the_middle_not_the_whole(void)
{
    printf("the midpoint averages a neighbourhood at the middle, not the segment\n");
    /* A ramp: the mean of the whole is 50, and so is the middle - so a ramp
     * cannot tell the two apart. A step can. */
    float values[101];
    for (int i = 0; i < 101; i++) {
        values[i] = i < 50 ? 0.0f : 100.0f;
    }
    const float mid = store_alert_midpoint(values, 101, 101, 0);
    /* Centred on position 50, span 9: positions 46..54, of which 46..49 are 0
     * and 50..54 are 100. */
    CHECK(CLOSE(mid, 500.0 / 9.0, 1e-3), "midpoint is %f, not %f", (double)mid, 500.0 / 9.0);

    /* A window that only covers part of the neighbourhood still answers from
     * what it has, which is what lets the flash pass read one page not eighty. */
    const float window[] = {100.0f, 100.0f, 100.0f};
    CHECK(CLOSE(store_alert_midpoint(window, 3, 101, 50), 100.0, 1e-6),
          "a partial window did not average what it had");
    CHECK(isnan(store_alert_midpoint(window, 3, 101, 0)),
          "a window nowhere near the middle produced a number anyway");
    CHECK(isnan(store_alert_midpoint(NULL, 0, 0, 0)), "an empty segment produced a number");
}

/* --- the header record ----------------------------------------------------- */

static store_alert_header_t sample_header(void)
{
    store_alert_header_t h;
    memset(&h, 0, sizeof(h));
    h.session = 3;
    h.session_start_ms = 1700000000000LL;
    h.first_index = 100;
    h.last_index = 199;
    h.utc_offset_min = -240;
    h.start_ms = 1700000099000LL;
    h.stop_ms = 1700000198000LL;
    h.first = 21.5f;
    h.last = 19.25f;
    h.midpoint = 44.125f;
    h.mean = 33.75f;
    h.variance = 12.5f;
    h.minimum = 18.0f;
    h.maximum = 91.5f;
    h.points = 100;
    h.field = STORE_ALERT_FIELD_PM2_5;
    h.threshold = 20.0f;
    h.seq = 42;
    h.wrap = 11;
    h.body_seq = 7;
    h.body_pages = 3;
    return h;
}

static void test_a_header_survives_the_round_trip(void)
{
    printf("a header record encodes and decodes to the same numbers\n");
    const store_alert_header_t in = sample_header();
    uint8_t raw[STORE_ALERT_HEADER_BYTES];
    store_alert_header_encode(&in, raw);

    store_alert_header_t out;
    CHECK(store_alert_header_decode(raw, &out), "a freshly written header did not decode");
    CHECK(memcmp(&in.session, &out.session, sizeof(in.session)) == 0, "session changed");
    CHECK(in.session_start_ms == out.session_start_ms, "session start changed");
    CHECK(in.first_index == out.first_index && in.last_index == out.last_index,
          "the index range changed");
    CHECK(in.utc_offset_min == out.utc_offset_min, "the offset changed");
    CHECK(in.start_ms == out.start_ms && in.stop_ms == out.stop_ms, "the times changed");
    CHECK(in.first == out.first && in.last == out.last, "first/last changed");
    CHECK(in.midpoint == out.midpoint, "the midpoint changed");
    CHECK(in.mean == out.mean && in.variance == out.variance, "mean/variance changed");
    CHECK(in.minimum == out.minimum && in.maximum == out.maximum, "the extremes changed");
    CHECK(in.points == out.points, "the point count changed");
    CHECK(in.field == out.field && in.threshold == out.threshold, "the reason changed");
    CHECK(in.seq == out.seq && in.wrap == out.wrap, "the sequence or wrap changed");
    CHECK(in.body_seq == out.body_seq && in.body_pages == out.body_pages,
          "the body location changed");
}

static void test_a_torn_header_is_simply_not_a_header(void)
{
    printf("and one the power cut through fails its CRC rather than half-decoding\n");
    const store_alert_header_t in = sample_header();
    uint8_t raw[STORE_ALERT_HEADER_BYTES];
    store_alert_header_encode(&in, raw);

    store_alert_header_t out;
    for (size_t byte = 0; byte < STORE_ALERT_HEADER_BYTES; byte++) {
        uint8_t torn[STORE_ALERT_HEADER_BYTES];
        memcpy(torn, raw, sizeof(torn));
        torn[byte] ^= 0x40u;
        CHECK(!store_alert_header_decode(torn, &out), "byte %u could be flipped undetected",
              (unsigned)byte);
    }

    uint8_t blank[STORE_ALERT_HEADER_BYTES];
    memset(blank, 0xFF, sizeof(blank));
    CHECK(!store_alert_header_decode(blank, &out), "erased flash decoded as a header");
    memset(blank, 0x00, sizeof(blank));
    CHECK(!store_alert_header_decode(blank, &out), "a zeroed record decoded as a header");
}

static void test_a_header_naming_a_field_that_does_not_exist_is_refused(void)
{
    printf("a valid CRC over an impossible field index is still refused\n");
    store_alert_header_t in = sample_header();
    in.field = STORE_FIELDS + 3;
    uint8_t raw[STORE_ALERT_HEADER_BYTES];
    store_alert_header_encode(&in, raw);
    store_alert_header_t out;
    CHECK(!store_alert_header_decode(raw, &out),
          "a field index off the end of wire.FIELDS was accepted");

    in = sample_header();
    in.points = 0;
    store_alert_header_encode(&in, raw);
    CHECK(!store_alert_header_decode(raw, &out), "a segment of no points was accepted");
}

/* --- the area -------------------------------------------------------------- */

/**
 * Preserve one segment of @p points readings, and return its header sequence.
 *
 * Writes the samples as pages first and the header last, which is the order
 * `store.c` uses and for the reason given there.
 */
static uint32_t preserve_one(store_preserve_t *p, uint32_t session, uint32_t first_index,
                             uint32_t points, float level, uint32_t wrap)
{
    store_alert_seg_t seg;
    store_writer_t w;
    const uint32_t body_seq = store_preserve_next_body_seq(p);
    uint32_t pages = 0;
    bool open = false;

    for (uint32_t i = 0; i < points; i++) {
        const store_reading_t r = reading_at(first_index + i, level + (float)(i % 5));
        if (i == 0) {
            store_alert_seg_begin(&seg, STORE_ALERT_FIELD_PM2_5, 20.0f, session,
                                  1700000000000LL, 0, false, &r);
        } else {
            store_alert_seg_add(&seg, &r);
        }
        if (!open) {
            store_writer_init(&w, store_preserve_next_body_seq(p), session, 1700000000000LL,
                              1000, 0, false);
            open = true;
        }
        if (!store_writer_add(&w, &r)) {
            uint8_t page[STORE_PAGE_BYTES];
            if (store_writer_seal(&w, page, true) && store_preserve_body_write(p, page)) {
                pages++;
            }
            store_writer_init(&w, store_preserve_next_body_seq(p), session, 1700000000000LL,
                              1000, 0, false);
            (void)store_writer_add(&w, &r);
        }
    }
    if (open && !store_writer_empty(&w)) {
        uint8_t page[STORE_PAGE_BYTES];
        if (store_writer_seal(&w, page, false) && store_preserve_body_write(p, page)) {
            pages++;
        }
    }

    store_alert_header_t h;
    store_alert_seg_header(&seg, &h);
    h.wrap = wrap;
    h.body_seq = body_seq;
    h.body_pages = pages;
    store_page_t scratch;
    (void)store_preserve_fill_midpoint(p, &h, &scratch);
    return store_preserve_header_write(p, &h) ? h.seq : 0;
}

static void test_a_preserved_segment_reads_back_whole(void)
{
    printf("a segment written to the area comes back with every sample\n");
    sim_reset();
    store_preserve_t p;
    CHECK(store_preserve_begin(&p, &SIM_DEV), "the area would not start");

    const uint32_t seq = preserve_one(&p, 1, 1, 200, 30.0f, 0);
    CHECK(seq == 1, "the first segment is not sequence 1");

    store_alert_header_t h;
    CHECK(store_preserve_find(&p, seq, &h), "the segment could not be found again");
    CHECK(h.points == 200, "it says %u points, not 200", (unsigned)h.points);
    CHECK((h.flags & STORE_ALERT_FLAG_BODY_GONE) == 0, "its samples were reported missing");

    store_preserve_cursor_t cursor;
    CHECK(store_preserve_open(&p, &h, &cursor), "its samples would not open");
    uint32_t n = 0;
    uint32_t expect = 1;
    store_reading_t r;
    bool ordered = true;
    while (store_preserve_next(&p, &cursor, &r)) {
        if (r.index != expect++) {
            ordered = false;
        }
        n++;
    }
    CHECK(n == 200, "%u samples came back, not 200", (unsigned)n);
    CHECK(ordered, "the samples came back out of order");
}

static void test_the_midpoint_is_computed_from_the_samples_on_flash(void)
{
    printf("and its midpoint comes from the samples, not from the running mean\n");
    sim_reset();
    store_preserve_t p;
    store_preserve_begin(&p, &SIM_DEV);

    /* preserve_one's values cycle 30,31,32,33,34, so the mean is 32 and any
     * nine consecutive points also average 32 - useless as a discriminator. So
     * check the weaker but real property: it is a number, and it is one the
     * samples support. */
    const uint32_t seq = preserve_one(&p, 1, 1, 300, 30.0f, 0);
    store_alert_header_t h;
    CHECK(store_preserve_find(&p, seq, &h), "not found");
    CHECK(h.midpoint >= 30.0f && h.midpoint <= 34.0f, "midpoint %f is outside the data",
          (double)h.midpoint);

    /* And that it fails honestly rather than inventing one. */
    store_alert_header_t orphan = h;
    orphan.body_pages = 0;
    store_page_t scratch;
    CHECK(!store_preserve_fill_midpoint(&p, &orphan, &scratch),
          "a segment with no samples produced a midpoint");
}

static void test_the_headers_outlive_their_samples(void)
{
    printf("THE CLAIM: when the sample ring wraps, the summaries stay\n");
    sim_reset();
    store_preserve_t p;
    store_preserve_begin(&p, &SIM_DEV);

    /* Enough segments to turn the body ring over several times. */
    uint32_t first_seq = 0;
    const uint32_t count = 40;
    for (uint32_t i = 0; i < count; i++) {
        const uint32_t seq = preserve_one(&p, 1, 1 + i * 300, 300, 30.0f, i);
        if (i == 0) {
            first_seq = seq;
        }
        CHECK(seq != 0, "segment %u would not write its header", (unsigned)i);
    }

    store_preserve_stats_t st;
    store_preserve_get_stats(&p, &st);
    CHECK(st.bodies_reclaimed > 0, "the body ring never turned over; the test proves nothing");
    CHECK(st.segments == count, "%u headers survive, not %u", (unsigned)st.segments,
          (unsigned)count);

    store_alert_header_t headers[64];
    const size_t n = store_preserve_list(&p, headers, 64, 0);
    CHECK(n == count, "%u headers listed, not %u", (unsigned)n, (unsigned)count);

    /* Oldest first, sequence order, and every one of them still carrying the
     * whole summary even where the samples have gone. */
    bool ordered = true;
    uint32_t gone = 0;
    for (size_t i = 0; i < n; i++) {
        if (i > 0 && headers[i].seq <= headers[i - 1].seq) {
            ordered = false;
        }
        CHECK(headers[i].points == 300, "header %u lost its point count", (unsigned)i);
        CHECK(headers[i].maximum >= headers[i].minimum, "header %u lost its extremes",
              (unsigned)i);
        CHECK(headers[i].wrap == (uint32_t)i, "header %u lost the wrap it was written at",
              (unsigned)i);
        if (headers[i].flags & STORE_ALERT_FLAG_BODY_GONE) {
            gone++;
        }
    }
    CHECK(ordered, "the headers did not come back oldest first");
    CHECK(gone > 0, "no segment reported its samples reclaimed, yet the ring wrapped");
    CHECK(gone < n, "every segment lost its samples; the newest should still have them");

    /* And the oldest one, whose samples are certainly gone, still answers. */
    store_alert_header_t oldest;
    CHECK(store_preserve_find(&p, first_seq, &oldest), "the first segment's header is gone");
    CHECK(oldest.flags & STORE_ALERT_FLAG_BODY_GONE, "it claims to still have its samples");
    CHECK(oldest.points == 300 && oldest.mean > 0.0f,
          "it lost the summary that is the whole point of keeping it");
    store_preserve_cursor_t cursor;
    CHECK(!store_preserve_open(&p, &oldest, &cursor),
          "samples opened for a segment whose samples are gone");
}

static void test_the_area_is_found_again_after_a_reboot(void)
{
    printf("and a rescan of the same bytes finds the same segments\n");
    sim_reset();
    store_preserve_t p;
    store_preserve_begin(&p, &SIM_DEV);
    for (uint32_t i = 0; i < 12; i++) {
        preserve_one(&p, 1, 1 + i * 100, 100, 30.0f, i);
    }
    store_preserve_stats_t before;
    store_preserve_get_stats(&p, &before);

    /* The reboot: nothing but the bytes carries over. */
    store_preserve_t q;
    CHECK(store_preserve_begin(&q, &SIM_DEV), "the area would not start from its own bytes");
    store_preserve_stats_t after;
    store_preserve_get_stats(&q, &after);
    CHECK(after.segments == before.segments, "%u segments after a reboot, %u before",
          (unsigned)after.segments, (unsigned)before.segments);
    CHECK(store_preserve_seen_wrap(&q) == 11,
          "the wrap count did not survive; it read %u", (unsigned)store_preserve_seen_wrap(&q));

    /* The next segment must not reuse a sequence, or the ordering collapses. */
    const uint32_t seq = preserve_one(&q, 1, 5000, 50, 30.0f, 12);
    CHECK(seq == before.headers_written + 1, "the sequence restarted after a reboot: %u",
          (unsigned)seq);
}

static void test_the_header_ring_wraps_without_losing_its_place(void)
{
    printf("and when even the summaries run out, the newest are the ones kept\n");
    sim_reset();
    store_preserve_t p;
    store_preserve_begin(&p, &SIM_DEV);

    const uint32_t capacity = SIM_HEADER_BYTES / STORE_ALERT_HEADER_BYTES;
    const uint32_t written = capacity + capacity / 2;
    for (uint32_t i = 0; i < written; i++) {
        preserve_one(&p, 1, 1 + i * 20, 20, 30.0f, i);
    }

    store_alert_header_t headers[512];
    const size_t n = store_preserve_list(&p, headers, 512, 0);
    CHECK(n > 0 && n <= capacity, "%u headers survive a region that holds %u", (unsigned)n,
          (unsigned)capacity);
    CHECK(headers[n - 1].seq == written, "the newest segment is not the last one written");
    /* Oldest-first, and contiguous: the ones that went are the oldest, in order. */
    for (size_t i = 1; i < n; i++) {
        CHECK(headers[i].seq == headers[i - 1].seq + 1, "a gap at %u", (unsigned)i);
    }
}

static void test_a_write_that_flash_refuses_is_counted_not_hidden(void)
{
    printf("a flash refusal is reported and counted; nothing waits on it\n");
    sim_reset();
    store_preserve_t p;
    store_preserve_begin(&p, &SIM_DEV);
    sim.refuse_writes = true;

    store_alert_header_t h = sample_header();
    CHECK(!store_preserve_header_write(&p, &h), "a refused write reported success");
    uint8_t page[STORE_PAGE_BYTES];
    memset(page, 0, sizeof(page));
    CHECK(!store_preserve_body_write(&p, page), "a refused body write reported success");

    store_preserve_stats_t st;
    store_preserve_get_stats(&p, &st);
    CHECK(st.write_failures == 2, "%u failures counted, not 2", (unsigned)st.write_failures);
    CHECK(st.segments == 0, "a refused write still counted a segment");
}

static void test_coverage_answers_for_a_range_not_one_reading(void)
{
    printf("the sweep's question - already preserved? - answers for a range at a time\n");
    sim_reset();
    store_preserve_t p;
    store_preserve_begin(&p, &SIM_DEV);
    preserve_one(&p, 1, 100, 50, 30.0f, 0);   /* indices 100..149 */
    preserve_one(&p, 1, 400, 50, 30.0f, 0);   /* indices 400..449 */

    uint32_t through = 0;
    CHECK(store_preserve_covers(&p, 1, 1700000000000LL, 120, &through), "120 is not covered");
    CHECK(through == 149, "the verdict holds to %u, not 149", (unsigned)through);

    CHECK(!store_preserve_covers(&p, 1, 1700000000000LL, 200, &through), "200 is covered");
    CHECK(through == 399, "the negative verdict holds to %u, not 399", (unsigned)through);

    CHECK(!store_preserve_covers(&p, 1, 1700000000000LL, 500, &through), "500 is covered");
    CHECK(through == UINT32_MAX, "nothing follows 500, so the verdict should hold for ever");

    /* The identity again: the same index of a *different* recording is not it. */
    CHECK(!store_preserve_covers(&p, 1, 1699999999000LL, 120, &through),
          "a different session's index 120 was called preserved");
    CHECK(!store_preserve_covers(&p, 2, 1700000000000LL, 120, &through),
          "a different session number's index 120 was called preserved");
}

static void test_pressure_is_the_sample_region_only(void)
{
    printf("pressure is measured on the samples, which is what rate reduction answers\n");
    sim_reset();
    store_preserve_t p;
    store_preserve_begin(&p, &SIM_DEV);
    CHECK(store_preserve_pressure(&p) == 0.0f, "an empty area is under pressure");

    for (uint32_t i = 0; i < 6; i++) {
        preserve_one(&p, 1, 1 + i * 100, 100, 30.0f, 0);
    }
    const float mid = store_preserve_pressure(&p);
    CHECK(mid > 0.0f && mid <= 1.0f, "pressure is %f", (double)mid);

    for (uint32_t i = 6; i < 60; i++) {
        preserve_one(&p, 1, 1 + i * 100, 100, 30.0f, 0);
    }
    CHECK(store_preserve_pressure(&p) > mid - 1e-6f, "pressure fell as the area filled");
    CHECK(store_preserve_pressure(&p) <= 1.0f, "pressure exceeded 1");
}

/* --- the rate ladder ------------------------------------------------------- */

static void test_the_rate_only_ever_falls_for_the_preservation_area(void)
{
    printf("the ladder: full rate, one a minute, one every fifteen\n");
    CHECK(store_alert_rate_floor_ms(0) == 0, "level 0 imposes something");
    CHECK(store_alert_rate_floor_ms(1) == 60000u, "level 1 is not one a minute");
    CHECK(store_alert_rate_floor_ms(2) == 900000u, "level 2 is not one per fifteen minutes");

    /* Rising. */
    CHECK(store_alert_rate_level(0, 0.10f) == 0, "an empty area reduced the rate");
    CHECK(store_alert_rate_level(0, 0.59f) == 0, "reduced below the first line");
    CHECK(store_alert_rate_level(0, 0.60f) == 1, "did not reduce at the first line");
    CHECK(store_alert_rate_level(1, 0.84f) == 1, "skipped a rung below the second line");
    CHECK(store_alert_rate_level(1, 0.85f) == 2, "did not reduce again at the second line");
    CHECK(store_alert_rate_level(2, 1.00f) == 2, "went past the last rung");

    /* Holding, in the gap. A level that tracked the fraction exactly would step
     * up and down every few readings and rewrite the profile each time. */
    CHECK(store_alert_rate_level(2, 0.57f) == 2, "released the moment it dipped");
    CHECK(store_alert_rate_level(1, 0.57f) == 1, "released the moment it dipped");

    /* And coming back down, which is the rule "recording beats preserving"
     * applied to the reduction itself. */
    CHECK(store_alert_rate_level(2, 0.50f) == 1, "never came back down from the second rung");
    CHECK(store_alert_rate_level(1, 0.30f) == 0, "never came back to full rate");
    CHECK(store_alert_rate_level(2, 0.30f) == 0, "did not come all the way back");

    CHECK(store_alert_rate_level(1, (float)NAN) == 1, "a NaN pressure changed the rate");
}

int main(void)
{
    printf("preserved alert data host tests\n");
    printf("what survives the ring wrapping, and what it says about itself\n\n");

    test_the_shipped_line_is_the_app_default();
    test_a_line_that_is_not_a_number_watches_nothing();
    test_the_lowest_field_wins_when_several_trip();

    test_the_header_carries_every_figure_the_captain_listed();
    test_the_variance_survives_a_series_that_barely_moves();
    test_hovering_on_the_line_is_one_segment_not_hundreds();
    test_a_segment_ends_once_the_air_is_properly_clear();
    test_an_excursion_that_never_ends_is_still_headered();
    test_a_gap_or_a_new_session_ends_the_segment();
    test_the_midpoint_is_the_middle_not_the_whole();

    test_a_header_survives_the_round_trip();
    test_a_torn_header_is_simply_not_a_header();
    test_a_header_naming_a_field_that_does_not_exist_is_refused();

    test_a_preserved_segment_reads_back_whole();
    test_the_midpoint_is_computed_from_the_samples_on_flash();
    test_the_headers_outlive_their_samples();
    test_the_area_is_found_again_after_a_reboot();
    test_the_header_ring_wraps_without_losing_its_place();
    test_a_write_that_flash_refuses_is_counted_not_hidden();
    test_coverage_answers_for_a_range_not_one_reading();
    test_pressure_is_the_sample_region_only();

    test_the_rate_only_ever_falls_for_the_preservation_area();

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

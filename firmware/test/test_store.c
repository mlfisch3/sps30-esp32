/**
 * @file test_store.c
 * @brief The page format, checked against the specification that defines it.
 *
 * Three claims, in order of how much they are worth:
 *
 * 1. **The C emits exactly what `store.py` emits.** Not equivalent bytes -
 *    identical ones, page for page, including the CRC. That is the check that a
 *    port has not drifted, and it is why the vectors are generated rather than
 *    written.
 * 2. **The C decodes what `store.py` encoded**, back to the same values within
 *    the quantum. A format that only round-trips its own output would pass a
 *    test suite and still be wrong.
 * 3. **A page that has been damaged is rejected**, wherever the damage is. This
 *    is the one that matters after a power cut, and it is asserted by flipping
 *    every byte of a real page in turn.
 *
 * Build and run with `make -C firmware/test`. No ESP-IDF, no board.
 */

#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "store_codec.h"
#include "store_vectors_generated.h"

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

/** Compare one sealed page with the specification's, and say where it differs. */
static void expect_page(const char *name, unsigned page, const uint8_t *got,
                        const uint8_t *want)
{
    checks++;
    if (memcmp(got, want, STORE_PAGE_BYTES) == 0) {
        return;
    }
    failures++;
    printf("  FAIL %s page %u differs from store.py\n", name, page);
    for (int b = 0; b < STORE_PAGE_BYTES; b++) {
        if (got[b] != want[b]) {
            printf("       first at byte %d: got 0x%02x want 0x%02x\n", b, got[b],
                   want[b]);
            break;
        }
    }
}

/* The layout the header must have, asserted here so a change to either side
 * of the port shows up as a test failure rather than as unreadable flash. */
static void test_layout(void)
{
    printf("layout\n");
    CHECK(STORE_PAGE_BYTES == STORE_VEC_PAGE_BYTES, "page size %d != %d",
          STORE_PAGE_BYTES, STORE_VEC_PAGE_BYTES);
    CHECK(STORE_HEADER_BYTES == STORE_VEC_HEADER_BYTES, "header %d != %d",
          STORE_HEADER_BYTES, STORE_VEC_HEADER_BYTES);
    CHECK(STORE_FIELDS == STORE_VEC_FIELDS, "fields %d != %d", STORE_FIELDS,
          STORE_VEC_FIELDS);
}

static void test_varints(void)
{
    printf("varints\n");
    const int64_t values[] = {0, 1, -1, 63, 64, -64, -65, 127, 128, -128,
                              8191, 8192, -8192, 1 << 20, -(1 << 20),
                              INT32_MAX, INT32_MIN, 4000, -4000};
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); i++) {
        uint8_t buf[16];
        const size_t n = store_put_varint(buf, sizeof(buf), store_zigzag(values[i]));
        CHECK(n > 0, "put_varint refused %lld", (long long)values[i]);
        uint64_t raw = 0;
        const size_t m = store_get_varint(buf, n, &raw);
        CHECK(m == n, "get_varint read %zu of %zu bytes", m, n);
        CHECK(store_unzigzag(raw) == values[i], "%lld round-tripped to %lld",
              (long long)values[i], (long long)store_unzigzag(raw));
    }

    /* A varint that runs off the end must be refused, not read past. */
    const uint8_t truncated[] = {0x80, 0x80, 0x80};
    uint64_t out = 0;
    CHECK(store_get_varint(truncated, sizeof(truncated), &out) == 0,
          "a truncated varint was accepted");
    /* And one with no room to write must report that rather than overflow. */
    uint8_t tiny[1];
    CHECK(store_put_varint(tiny, sizeof(tiny), 300) == 0,
          "put_varint wrote past its buffer");
}

static void test_quantise(void)
{
    printf("quantise\n");
    /* Every expectation here was read off store.py rather than reasoned about,
     * because float32 makes the obvious answer wrong surprisingly often: 0.015f
     * is really 0.0149999996, so it quantises to 1 and not to 2. That is the
     * whole reason the two sides divide in double - see store_codec.c. */
    CHECK(store_quantise(3.13f, 0) == 313, "3.13 -> %d", store_quantise(3.13f, 0));
    CHECK(store_quantise(0.015f, 0) == 1, "0.015 -> %d", store_quantise(0.015f, 0));
    CHECK(store_quantise(-0.015f, 0) == -1, "-0.015 -> %d", store_quantise(-0.015f, 0));
    CHECK(store_quantise(0.025f, 0) == 3, "0.025 -> %d", store_quantise(0.025f, 0));
    /* Half away from zero, not half to even, and symmetric about zero. */
    CHECK(store_quantise(0.0125f, STORE_FIELDS - 1) == 13, "0.0125 -> %d",
          store_quantise(0.0125f, STORE_FIELDS - 1));
    CHECK(store_quantise(-0.0125f, STORE_FIELDS - 1) == -13, "-0.0125 -> %d",
          store_quantise(-0.0125f, STORE_FIELDS - 1));
    /* The last field uses the finer quantum. */
    CHECK(store_quantise(0.461f, STORE_FIELDS - 1) == 461, "size 0.461 -> %d",
          store_quantise(0.461f, STORE_FIELDS - 1));
    CHECK(fabsf(store_dequantise(313, 0) - 3.13f) < 1e-5f, "dequantise");
}

/** The C must produce byte-identical pages to the specification's. */
static void test_encode_matches_python(void)
{
    printf("encoding matches store.py\n");
    for (unsigned c = 0; c < STORE_VEC_CASE_COUNT; c++) {
        const store_vec_case_t *tc = &store_vec_cases[c];
        store_writer_t w;
        store_writer_init(&w, 0, STORE_VEC_SESSION, STORE_VEC_SESSION_START_MS,
                          STORE_VEC_CADENCE_MS, STORE_VEC_UTC_OFFSET_MIN, false);
        unsigned page = 0;

        for (unsigned i = 0; i < tc->reading_count; i++) {
            const store_vec_reading_t *v = &tc->readings[i];
            store_reading_t r = {.index = v->index, .epoch_ms = v->epoch_ms,
                                 .settling = v->settling != 0};
            memcpy(r.values, v->values, sizeof(r.values));

            if (!store_writer_add(&w, &r)) {
                uint8_t sealed[STORE_PAGE_BYTES];
                CHECK(store_writer_seal(&w, sealed, true), "%s: seal failed", tc->name);
                CHECK(page < tc->page_count, "%s: produced more pages than python",
                      tc->name);
                if (page < tc->page_count) {
                    expect_page(tc->name, page,
                                sealed, tc->pages + (size_t)page * STORE_PAGE_BYTES);
                }
                page++;
                store_writer_init(&w, page, STORE_VEC_SESSION, STORE_VEC_SESSION_START_MS,
                                  STORE_VEC_CADENCE_MS, STORE_VEC_UTC_OFFSET_MIN, false);
                CHECK(store_writer_add(&w, &r), "%s: reading %u did not fit an empty page",
                      tc->name, i);
            }
        }
        if (!store_writer_empty(&w)) {
            uint8_t sealed[STORE_PAGE_BYTES];
            CHECK(store_writer_seal(&w, sealed, false), "%s: final seal failed", tc->name);
            CHECK(page < tc->page_count, "%s: produced more pages than python", tc->name);
            if (page < tc->page_count) {
                expect_page(tc->name, page,
                            sealed, tc->pages + (size_t)page * STORE_PAGE_BYTES);
            }
            page++;
        }
        CHECK(page == tc->page_count, "%s: %u pages, python made %u", tc->name, page,
              tc->page_count);
    }
}

/** And it must read the specification's pages back to the original values. */
static void test_decode_matches_python(void)
{
    printf("decoding store.py's pages\n");
    for (unsigned c = 0; c < STORE_VEC_CASE_COUNT; c++) {
        const store_vec_case_t *tc = &store_vec_cases[c];
        unsigned seen = 0;
        for (unsigned p = 0; p < tc->page_count; p++) {
            store_page_t page;
            const uint8_t *raw = tc->pages + (size_t)p * STORE_PAGE_BYTES;
            if (!store_decode_page(raw, &page)) {
                failures++;
                checks++;
                printf("  FAIL %s page %u did not decode\n", tc->name, p);
                continue;
            }
            checks++;
            CHECK(page.session == STORE_VEC_SESSION, "%s: session %u", tc->name,
                  (unsigned)page.session);
            for (unsigned i = 0; i < page.count && seen < tc->reading_count; i++, seen++) {
                const store_vec_reading_t *want = &tc->readings[seen];
                const store_reading_t *got = &page.readings[i];
                CHECK(got->index == want->index, "%s: index %u != %u", tc->name,
                      got->index, want->index);
                CHECK(got->settling == (want->settling != 0), "%s: settling at %u",
                      tc->name, seen);
                /* Times are derived, so they are allowed to sit inside the
                 * tolerance the format documents - and no further. */
                const int64_t off = got->epoch_ms - want->epoch_ms;
                CHECK(off <= STORE_TIME_TOLERANCE_MS && off >= -STORE_TIME_TOLERANCE_MS,
                      "%s: reading %u is %lld ms out", tc->name, seen, (long long)off);
                for (int f = 0; f < STORE_FIELDS; f++) {
                    const float quantum = f == STORE_FIELDS - 1 ? STORE_QUANTUM_SIZE
                                                                : STORE_QUANTUM_DEFAULT;
                    const float error = fabsf(got->values[f] - want->values[f]);
                    CHECK(error <= quantum * 0.5f + 1e-6f,
                          "%s: reading %u field %d: %g vs %g (error %g)", tc->name, seen,
                          f, (double)got->values[f], (double)want->values[f],
                          (double)error);
                }
            }
        }
        CHECK(seen == tc->reading_count, "%s: decoded %u of %u readings", tc->name, seen,
              tc->reading_count);
    }
}

/**
 * Damage anywhere in a page must be caught.
 *
 * This is the power-cut test. Every byte is flipped in turn and the page must
 * be rejected - not decoded into something plausible, and above all not read
 * outside itself, which is what the sanitisers in the Makefile are watching for.
 */
static void test_damage_is_rejected(void)
{
    printf("damaged pages are rejected\n");
    const uint8_t *good = store_vec_cases[0].pages;
    store_page_t page;
    CHECK(store_decode_page(good, &page), "the undamaged page did not decode");

    unsigned accepted = 0;
    for (int b = 0; b < STORE_PAGE_BYTES; b++) {
        for (int bit = 0; bit < 8; bit += 3) {
            uint8_t copy[STORE_PAGE_BYTES];
            memcpy(copy, good, sizeof(copy));
            copy[b] ^= (uint8_t)(1u << bit);
            store_page_t out;
            if (store_decode_page(copy, &out)) {
                accepted++;
            }
        }
    }
    CHECK(accepted == 0, "%u damaged pages were accepted", accepted);

    /* A blank page - erased flash is all 0xFF - is not a page. */
    uint8_t blank[STORE_PAGE_BYTES];
    memset(blank, 0xFF, sizeof(blank));
    CHECK(!store_decode_page(blank, &page), "erased flash decoded as a page");
    memset(blank, 0x00, sizeof(blank));
    CHECK(!store_decode_page(blank, &page), "a zeroed page decoded as a page");
}

/** A dropout must break the page rather than silently closing the hole. */
static void test_gap_breaks_the_page(void)
{
    printf("a dropout ends the page\n");
    store_writer_t w;
    store_writer_init(&w, 0, 1, 1000, 1000, 0, false);
    store_reading_t r = {.index = 1, .epoch_ms = 1000, .settling = false};
    for (int f = 0; f < STORE_FIELDS; f++) {
        r.values[f] = 1.0f;
    }
    CHECK(store_writer_add(&w, &r), "first reading refused");
    r.index = 2;
    r.epoch_ms = 2000;
    CHECK(store_writer_add(&w, &r), "contiguous reading refused");
    r.index = 9; /* six missing */
    r.epoch_ms = 9000;
    CHECK(!store_writer_add(&w, &r), "a gap was folded into the page");
}

/**
 * A session recorded with no clock, byte for byte against `store.py`.
 *
 * The claim being tested is that relative time changes the *origin* and nothing
 * else: the same encoder, the same per-page rebase, the same time marker when
 * the cadence breaks. If a relative page ever needed its own code path, this is
 * where that would show up as a byte difference.
 */
static void test_relative_matches_python(void)
{
    printf("a clockless session matches store.py\n");
    store_writer_t w;
    store_writer_init(&w, 0, STORE_VEC_REL_SESSION, STORE_VEC_REL_SESSION_START,
                      STORE_VEC_CADENCE_MS, STORE_VEC_UTC_OFFSET_MIN, true);
    unsigned page = 0;
    for (unsigned i = 0; i < STORE_VEC_REL_READING_COUNT; i++) {
        const store_vec_reading_t *v = &store_vec_relative_readings[i];
        store_reading_t r = {.index = v->index, .epoch_ms = v->epoch_ms,
                             .settling = v->settling != 0};
        memcpy(r.values, v->values, sizeof(r.values));
        if (!store_writer_add(&w, &r)) {
            uint8_t sealed[STORE_PAGE_BYTES];
            CHECK(store_writer_seal(&w, sealed, true), "relative: seal failed");
            expect_page("relative", page,
                        sealed, store_vec_relative_pages + (size_t)page * STORE_PAGE_BYTES);
            page++;
            store_writer_init(&w, page, STORE_VEC_REL_SESSION, STORE_VEC_REL_SESSION_START,
                              STORE_VEC_CADENCE_MS, STORE_VEC_UTC_OFFSET_MIN, true);
            CHECK(store_writer_add(&w, &r), "relative: reading %u did not fit", i);
        }
    }
    if (!store_writer_empty(&w)) {
        uint8_t sealed[STORE_PAGE_BYTES];
        CHECK(store_writer_seal(&w, sealed, false), "relative: final seal failed");
        expect_page("relative", page,
                    sealed, store_vec_relative_pages + (size_t)page * STORE_PAGE_BYTES);
        page++;
    }
    CHECK(page == STORE_VEC_REL_PAGE_COUNT, "relative: %u pages, python made %u", page,
          (unsigned)STORE_VEC_REL_PAGE_COUNT);

    /* And it reads back as relative, carrying the run marker and the clock it
     * went in with - which is what makes the client's conversion reversible. */
    unsigned seen = 0;
    for (unsigned p = 0; p < STORE_VEC_REL_PAGE_COUNT; p++) {
        store_page_t decoded;
        const uint8_t *raw = store_vec_relative_pages + (size_t)p * STORE_PAGE_BYTES;
        CHECK(store_decode_page(raw, &decoded), "relative page %u did not decode", p);
        CHECK(decoded.relative, "relative page %u lost its flag", p);
        CHECK(store_rel_run(decoded.page_start_ms) == STORE_VEC_REL_RUN,
              "relative page %u: run %u", p, (unsigned)store_rel_run(decoded.page_start_ms));
        for (unsigned i = 0; i < decoded.count && seen < STORE_VEC_REL_READING_COUNT;
             i++, seen++) {
            const int64_t off =
                decoded.readings[i].epoch_ms - store_vec_relative_readings[seen].epoch_ms;
            CHECK(off <= STORE_TIME_TOLERANCE_MS && off >= -STORE_TIME_TOLERANCE_MS,
                  "relative reading %u is %lld ms out", seen, (long long)off);
        }
    }
    CHECK(seen == STORE_VEC_REL_READING_COUNT, "relative: decoded %u of %u", seen,
          (unsigned)STORE_VEC_REL_READING_COUNT);
}

/** The equivalence page: appended when a clock arrives, never a rewrite. */
static void test_anchor_page(void)
{
    printf("equivalence pages\n");
    store_anchor_t anchors[STORE_VEC_ANCHOR_COUNT];
    for (unsigned i = 0; i < STORE_VEC_ANCHOR_COUNT; i++) {
        anchors[i].rel_ms = store_vec_anchors[i].rel_ms;
        anchors[i].epoch_ms = store_vec_anchors[i].epoch_ms;
        anchors[i].utc_offset_min = store_vec_anchors[i].utc_offset_min;
    }

    uint8_t sealed[STORE_PAGE_BYTES];
    /* Session 0: an equivalence belongs to a run of the board, not to a session
     * within it, and `acq` numbers sessions from 1 so zero cannot collide. */
    CHECK(store_seal_anchor_page(sealed, STORE_VEC_ANCHOR_PAGE_SEQ, 0,
                                 store_rel_pack(STORE_VEC_REL_RUN, 0),
                                 store_rel_pack(STORE_VEC_REL_RUN, 3612400u),
                                 STORE_VEC_UTC_OFFSET_MIN, anchors,
                                 STORE_VEC_ANCHOR_COUNT),
          "sealing an anchor page failed");
    expect_page("anchor", 0, sealed, store_vec_anchor_page);

    /* It has to survive the scan, which only ever reads the header. A page the
     * scan throws away is a page the ring will happily overwrite. */
    store_header_t header;
    CHECK(store_peek_page(store_vec_anchor_page, &header), "anchor page failed the peek");
    CHECK(header.anchor, "anchor page not flagged as one");
    CHECK(header.relative, "anchor page not flagged relative");
    CHECK(header.count == 0, "anchor page claims %u readings", (unsigned)header.count);

    store_anchor_t back[STORE_VEC_ANCHOR_COUNT + 2];
    const size_t n = store_decode_anchors(store_vec_anchor_page, back, sizeof(back) /
                                          sizeof(back[0]));
    CHECK(n == STORE_VEC_ANCHOR_COUNT, "decoded %u equivalences, wrote %u", (unsigned)n,
          (unsigned)STORE_VEC_ANCHOR_COUNT);
    for (size_t i = 0; i < n && i < STORE_VEC_ANCHOR_COUNT; i++) {
        CHECK(back[i].rel_ms == store_vec_anchors[i].rel_ms, "equivalence %u rel", (unsigned)i);
        CHECK(back[i].epoch_ms == store_vec_anchors[i].epoch_ms, "equivalence %u epoch",
              (unsigned)i);
        CHECK(back[i].utc_offset_min == store_vec_anchors[i].utc_offset_min,
              "equivalence %u offset", (unsigned)i);
    }

    /* A reading page is not an anchor page, however much a caller wishes it. */
    CHECK(store_decode_anchors(store_vec_relative_pages, back, 2) == 0,
          "a reading page yielded equivalences");
    /* And an empty one is refused rather than burning a ring slot on nothing. */
    CHECK(!store_seal_anchor_page(sealed, 1, 1, 0, 0, 0, anchors, 0),
          "an empty anchor page was sealed");
}

/**
 * What a board does with the corrupt equivalences it already has on flash.
 *
 * A build before the seconds/minutes fix wrote the offset sixty times too large,
 * and updating the firmware does not clean the ring. This is the load-side half:
 * the value is held to what a real zone can be on its way out of the page.
 */
static void test_anchor_zone_is_held_to_a_real_one(void)
{
    printf("equivalences with an impossible zone\n");

    /* Left alone: every one of these is a zone somewhere. The two ends are Baker
     * Island and Kiritimati; the odd ones are India and Nepal, which is why the
     * rule cannot be "a whole number of hours". */
    const int16_t real[] = {0, -240, -720, 840, 330, 345, -570};
    for (size_t i = 0; i < sizeof(real) / sizeof(real[0]); i++) {
        int16_t offset = real[i];
        CHECK(store_repair_utc_offset_min(&offset), "%d refused, and it is a zone",
              (int)real[i]);
        CHECK(offset == real[i], "%d was changed to %d", (int)real[i], (int)offset);
    }

    /* Repaired: seconds in a minutes field, which is the defect this exists for.
     * -14400 is the value on the captain's board. */
    const int16_t seconds[] = {-14400, -18000, 19800, -3600, 7200};
    const int16_t minutes[] = {-240, -300, 330, -60, 120};
    for (size_t i = 0; i < sizeof(seconds) / sizeof(seconds[0]); i++) {
        int16_t offset = seconds[i];
        CHECK(store_repair_utc_offset_min(&offset), "%d was dropped, and it is -%d/60",
              (int)seconds[i], (int)seconds[i]);
        CHECK(offset == minutes[i], "%d became %d, not %d", (int)seconds[i], (int)offset,
              (int)minutes[i]);
    }

    /* Dropped: outside the range and not a whole number of minutes when read as
     * seconds either, so there is no one thing it could have meant. The first
     * two are one minute past each end of the range, which is where a bound
     * that had been left off by one would show. */
    const int16_t nonsense[] = {841, -721, 1000, -1000, 20000, 32767, -32768};
    for (size_t i = 0; i < sizeof(nonsense) / sizeof(nonsense[0]); i++) {
        int16_t offset = nonsense[i];
        CHECK(!store_repair_utc_offset_min(&offset), "%d was accepted as a zone",
              (int)nonsense[i]);
    }

    /* Now through a whole page, because that is where it has to happen: the scan
     * reads anchor pages and nothing else ever looks at these bytes again. */
    const store_anchor_t written[] = {
        {store_rel_pack(1202, 328315u), 1785746736267LL, -240},
        {store_rel_pack(3395, 159395u), 1785831331731LL, -14400}, /* the bad one */
        {store_rel_pack(3395, 259395u), 1785831431731LL, 1000},   /* unrecoverable */
    };
    uint8_t page[STORE_PAGE_BYTES];
    CHECK(store_seal_anchor_page(page, 7, 0, store_rel_pack(3395, 0),
                                 store_rel_pack(3395, 259395u), -240, written, 3),
          "sealing the mixed anchor page failed");

    store_anchor_t back[8];
    const size_t n = store_decode_anchors(page, back, sizeof(back) / sizeof(back[0]));
    CHECK(n == 2, "decoded %u equivalences, expected 2", (unsigned)n);
    if (n == 2) {
        CHECK(back[0].epoch_ms == written[0].epoch_ms, "the good equivalence moved");
        CHECK(back[0].utc_offset_min == -240, "the good zone became %d",
              (int)back[0].utc_offset_min);
        /* Repaired, not discarded: the epoch it carries is correct and is the
         * only thing that can place that run on a calendar. Only the zone the
         * board displays it in was ever wrong. */
        CHECK(back[1].rel_ms == written[1].rel_ms, "the repaired equivalence is not the one");
        CHECK(back[1].epoch_ms == written[1].epoch_ms, "the repaired epoch moved");
        CHECK(back[1].utc_offset_min == -240, "-14400 came back as %d, not -240",
              (int)back[1].utc_offset_min);
    }
    for (size_t i = 0; i < n; i++) {
        CHECK(back[i].utc_offset_min != 1000, "the unrecoverable equivalence was offered");
        CHECK(back[i].utc_offset_min >= STORE_UTC_OFFSET_MIN_WEST &&
                  back[i].utc_offset_min <= STORE_UTC_OFFSET_MIN_EAST,
              "equivalence %u left with an impossible zone %d", (unsigned)i,
              (int)back[i].utc_offset_min);
    }
}

/** The packing that lets a run marker share the field an epoch normally has. */
static void test_relative_clock_packing(void)
{
    printf("the relative clock packs and unpacks\n");
    const uint32_t runs[] = {0, 1, 854, 0x7FFFFFFFu};
    const uint32_t times[] = {0, 1, 6437, 3600000u, 0xFFFFFFFFu};
    for (size_t r = 0; r < sizeof(runs) / sizeof(runs[0]); r++) {
        for (size_t t = 0; t < sizeof(times) / sizeof(times[0]); t++) {
            const int64_t packed = store_rel_pack(runs[r], times[t]);
            CHECK(packed >= 0, "packing run %u went negative", (unsigned)runs[r]);
            CHECK(store_rel_run(packed) == runs[r], "run %u came back as %u",
                  (unsigned)runs[r], (unsigned)store_rel_run(packed));
            CHECK(store_rel_ms(packed) == times[t], "ms %u came back as %u",
                  (unsigned)times[t], (unsigned)store_rel_ms(packed));
        }
    }
    /* The property the per-page rebase depends on: adding the ordinary
     * within-page millisecond arithmetic must not disturb the run marker. */
    const int64_t base = store_rel_pack(854, 6437);
    const int64_t later = base + 45 * 1000;
    CHECK(store_rel_run(later) == 854, "the run marker moved under page arithmetic");
    CHECK(store_rel_ms(later) == 6437u + 45000u, "the millisecond count is wrong");
}

int main(void)
{
    printf("store codec tests\n\n");
    test_layout();
    test_varints();
    test_quantise();
    test_encode_matches_python();
    test_decode_matches_python();
    test_damage_is_rejected();
    test_gap_breaks_the_page();
    test_relative_clock_packing();
    test_relative_matches_python();
    test_anchor_page();
    test_anchor_zone_is_held_to_a_real_one();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

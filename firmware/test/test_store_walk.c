/**
 * @file test_store_walk.c
 * @brief What a `get_file` has to read to answer, and what it must answer with.
 *
 * `store_walk.c` is the read half of `get_file`, and it has two obligations that
 * pull against each other. It must return **exactly** the readings the old
 * whole-ring walk returned, in the same order, with the same values - a backfill
 * that is fast and short is not a faster backfill, it is data loss. And it must
 * stop reading 2.19 MB of flash to do it.
 *
 * So every test here does the same thing twice: once through
 * ::store_walk_next over a real ring image, and once through ::reference_walk
 * below, which is the shipping cursor exactly as it was before this change -
 * start at the oldest slot, read all 512 bytes of every one of the 4,480, decode
 * what matches. The rows must agree, reading for reading. The read counters then
 * say what the new one saved.
 *
 * The ring is built by the shipping page writer and driven exactly as `store.c`
 * drives it: a head that advances, a `page_seq` that advances with it, a sector
 * erased in front of the head when the ring wraps, and the session index folded
 * and unfolded on the same events. That is what makes "the wrap behaviour is
 * unchanged" a thing this file can check rather than assert.
 *
 * Build and run with `make -C firmware/test`. No ESP-IDF, no board, no flash.
 */

#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "store_sessions.h"
#include "store_walk.h"

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

/* --- a board, in an array -------------------------------------------------- */

/**
 * The shipping geometry: 2,293,760 B of ring at 512 B a slot.
 *
 * The real number rather than a convenient one, because the figures this suite
 * prints are meant to be compared with the ones measured on the board.
 */
#define SLOTS 4480u

/** Flash erases a sector at a time; a sector holds this many slots. */
#define SLOTS_PER_SECTOR 8u

/** A ring of real pages, plus everything `store.c` keeps alongside it. */
typedef struct {
    uint8_t slot[SLOTS][STORE_PAGE_BYTES];
    uint32_t head;
    uint32_t next_seq;
    bool wrapped;
    store_sessions_t sessions;

    /* the open page */
    store_writer_t writer;
    bool writer_open;
    uint32_t writer_session;
    int64_t writer_start_ms;
    char writer_name[32];

    /* what the reads cost */
    uint32_t reads;
    uint64_t bytes;
} board_t;

/** A board is 2.3 MB; one static one rather than 2.3 MB of stack. */
static board_t s_board;

static void board_reset(board_t *b)
{
    memset(b, 0, sizeof(*b));
    /* Erased flash is 0xFF, and "is this slot blank" is a real question here:
     * `store_peek_page` must reject an erased slot rather than read a page out
     * of it. Zeroing the ring would be a kinder start than a board ever gets. */
    memset(b->slot, 0xFF, sizeof(b->slot));
    b->next_seq = 1;
}

static store_ring_geometry_t geometry_of(const board_t *b)
{
    store_ring_geometry_t g;
    g.slots = SLOTS;
    g.head = b->head;
    g.next_seq = b->next_seq;
    g.wrapped = b->wrapped;
    return g;
}

/** `store.c`'s `write_page`: erase in front of the head, write, advance. */
static void write_page(board_t *b, const uint8_t page[STORE_PAGE_BYTES])
{
    if (b->head % SLOTS_PER_SECTOR == 0) {
        /* Read the headers before the erase and unfold them after, which is what
         * keeps the index an account of the ring rather than of everything ever
         * written. Exactly `read_doomed` / `forget_doomed`. */
        store_header_t doomed[SLOTS_PER_SECTOR];
        uint8_t count = 0;
        for (uint32_t i = 0; i < SLOTS_PER_SECTOR; i++) {
            if (store_peek_page(b->slot[b->head + i], &doomed[count])) {
                count++;
            }
        }
        memset(b->slot[b->head], 0xFF, (size_t)SLOTS_PER_SECTOR * STORE_PAGE_BYTES);
        for (uint8_t i = 0; i < count; i++) {
            store_sessions_forget(&b->sessions, &doomed[i]);
        }
    }
    memcpy(b->slot[b->head], page, STORE_PAGE_BYTES);
    b->head = (b->head + 1) % SLOTS;
    if (b->head == 0) {
        b->wrapped = true;
    }
    b->next_seq++;
}

/** `store.c`'s `seal_and_write`: freeze the open page and commit it. */
static void seal(board_t *b, bool full)
{
    if (!b->writer_open || store_writer_empty(&b->writer)) {
        b->writer_open = false;
        return;
    }
    uint8_t page[STORE_PAGE_BYTES];
    if (store_writer_seal(&b->writer, page, full)) {
        write_page(b, page);
        store_header_t header;
        if (store_peek_page(page, &header)) {
            store_sessions_note(&b->sessions, &header, b->writer_name);
        }
    }
    b->writer_open = false;
}

/** One reading of session @p session, index @p index. Values vary per reading. */
static store_reading_t reading_of(uint32_t session, uint32_t index)
{
    store_reading_t r;
    memset(&r, 0, sizeof(r));
    r.index = index;
    r.epoch_ms = 1754000000000LL + (int64_t)session * 86400000LL + (int64_t)index * 1000LL;
    for (int f = 0; f < STORE_FIELDS; f++) {
        r.values[f] = (float)((index * 13u + (uint32_t)f * 7u) % 997u) / 100.0f;
    }
    r.settling = (index % 97u) == 0u;
    return r;
}

/** Record @p count readings of session @p session, sealing pages as they fill. */
static void record(board_t *b, uint32_t session, int64_t start_ms, const char *name,
                   uint32_t count)
{
    for (uint32_t i = 1; i <= count; i++) {
        const store_reading_t r = reading_of(session, i);
        if (!b->writer_open) {
            store_writer_init(&b->writer, b->next_seq, session, start_ms, 1000, -240, false);
            b->writer_open = true;
            b->writer_session = session;
            b->writer_start_ms = start_ms;
            snprintf(b->writer_name, sizeof(b->writer_name), "%s", name);
        }
        if (!store_writer_add(&b->writer, &r)) {
            seal(b, true);
            store_writer_init(&b->writer, b->next_seq, session, start_ms, 1000, -240, false);
            b->writer_open = true;
            (void)store_writer_add(&b->writer, &r);
        }
    }
    seal(b, false); /* what `store_flush` does in front of every `get_file` */
}

/* --- the ring, as something a walk can read -------------------------------- */

static bool slot_read(void *ctx, uint32_t slot, uint8_t *out, size_t len)
{
    board_t *b = (board_t *)ctx;
    if (slot >= SLOTS || len > STORE_PAGE_BYTES) {
        return false;
    }
    b->reads++;
    b->bytes += len;
    memcpy(out, b->slot[slot], len);
    return true;
}

static store_slot_reader_t reader_of(board_t *b)
{
    store_slot_reader_t src;
    src.read = slot_read;
    src.ctx = b;
    src.slots = SLOTS;
    return src;
}

/* --- what the answer has to be --------------------------------------------- */

/** Rows a walk produced, kept whole so two walks can be compared exactly. */
typedef struct {
    store_reading_t *row;
    size_t count;
    size_t cap;
} rows_t;

static void rows_free(rows_t *r)
{
    free(r->row);
    r->row = NULL;
    r->count = 0;
    r->cap = 0;
}

static void rows_add(rows_t *r, const store_reading_t *reading)
{
    if (r->count == r->cap) {
        r->cap = r->cap == 0 ? 256 : r->cap * 2;
        r->row = (store_reading_t *)realloc(r->row, r->cap * sizeof(*r->row));
    }
    r->row[r->count++] = *reading;
}

/**
 * The cursor exactly as it shipped before `store_walk.c` existed.
 *
 * Kept here in full, and deliberately not refactored to share anything with the
 * code under test: its whole job is to be an independent statement of what the
 * answer was, so that "the same rows come back" is a comparison rather than a
 * tautology. Start at the oldest slot, read the whole of every slot in the ring,
 * skip what does not match, decode what does.
 */
static void reference_walk(board_t *b, uint32_t session, int64_t session_start_ms,
                           uint32_t since_index, rows_t *out, uint32_t *pages_read)
{
    uint32_t slot = b->wrapped ? b->head : 0;
    uint32_t remaining = SLOTS;
    store_page_t page;
    *pages_read = 0;
    while (remaining-- > 0) {
        const uint32_t at = slot;
        slot = (slot + 1) % SLOTS;

        uint8_t raw[STORE_PAGE_BYTES];
        memcpy(raw, b->slot[at], STORE_PAGE_BYTES); /* the whole 512, always */
        (*pages_read)++;

        store_header_t peek;
        if (!store_peek_page(raw, &peek) || peek.anchor || peek.session != session ||
            peek.session_start_ms != session_start_ms) {
            continue;
        }
        if (peek.first_index + peek.count - 1 <= since_index) {
            continue;
        }
        if (!store_decode_page(raw, &page)) {
            continue;
        }
        for (uint8_t i = 0; i < page.count; i++) {
            if (page.readings[i].index > since_index) {
                rows_add(out, &page.readings[i]);
            }
        }
    }
}

/** The walk under test, opened the way `store_cursor_open` opens it. */
static void walk(board_t *b, const store_session_t *summary, bool span_exact,
                 uint32_t since_index, rows_t *out)
{
    store_cursor_t cursor;
    memset(&cursor, 0, sizeof(cursor));
    cursor.session = summary->session;
    cursor.session_start_ms = summary->start_ms;
    cursor.since_index = since_index;
    const store_ring_geometry_t g = geometry_of(b);
    store_walk_plan(&cursor, &g, span_exact, summary->first_seq, summary->last_seq);
    cursor.open = true;

    const store_slot_reader_t src = reader_of(b);
    b->reads = 0;
    b->bytes = 0;
    store_reading_t reading;
    while (store_walk_next(&cursor, &src, &reading, NULL)) {
        rows_add(out, &reading);
    }
}

/** Whether two walks produced the same readings, in the same order. */
static bool rows_identical(const rows_t *a, const rows_t *b, size_t *differ_at)
{
    if (a->count != b->count) {
        return false;
    }
    for (size_t i = 0; i < a->count; i++) {
        if (a->row[i].index != b->row[i].index ||
            a->row[i].epoch_ms != b->row[i].epoch_ms ||
            a->row[i].settling != b->row[i].settling ||
            memcmp(a->row[i].values, b->row[i].values, sizeof(a->row[i].values)) != 0) {
            *differ_at = i;
            return false;
        }
    }
    return true;
}

static const store_session_t *summary_named(const board_t *b, const char *name)
{
    return store_sessions_find(&b->sessions, name);
}

/**
 * Run both walks and require they agree. @return the new walk's slot reads.
 *
 * The one assertion every test in this file is built out of.
 */
static uint32_t same_rows(board_t *b, const char *name, uint32_t since_index, bool span_exact,
                          const char *what)
{
    const store_session_t *summary = summary_named(b, name);
    CHECK(summary != NULL, "%s: the ring holds no session named %s", what, name);
    if (summary == NULL) {
        return 0;
    }
    rows_t was = {0};
    rows_t now = {0};
    uint32_t reference_pages = 0;
    reference_walk(b, summary->session, summary->start_ms, since_index, &was, &reference_pages);
    walk(b, summary, span_exact, since_index, &now);

    size_t at = 0;
    const bool same = rows_identical(&was, &now, &at);
    CHECK(same, "%s: %zu rows before, %zu after%s", what, was.count, now.count,
          was.count == now.count ? " - and they differ" : "");
    CHECK(reference_pages == SLOTS, "%s: the reference read %" PRIu32 " slots, not the ring",
          what, reference_pages);
    const uint32_t reads = b->reads;
    rows_free(&was);
    rows_free(&now);
    return reads;
}

/* --- the finding ----------------------------------------------------------- */

/**
 * A reconnect backfill: one live session, and the client wants its tail.
 *
 * This is the case the captain feels - the phone comes back after a couple of
 * minutes away and waits. Pass 4 recorded a real one: 153 s disconnected, 161
 * stored readings came back. The old walk read all 4,480 slots to find nine
 * pages, and about 63% of that backfill was the controller reading pages it then
 * threw away.
 */
static void test_a_reconnect_reads_the_sessions_pages_not_the_ring(void)
{
    printf("a reconnect backfill reads the session's pages, not the ring\n");
    board_t *b = &s_board;
    board_reset(b);
    record(b, 1, 1754000000000LL, "SPS30-A.csv", 12975);

    const store_session_t *summary = summary_named(b, "SPS30-A.csv");
    CHECK(summary != NULL, "the session was not indexed");
    if (summary == NULL) {
        return;
    }
    const uint32_t last = summary->last_index;
    const uint32_t since = last - 161u; /* pass 4's reconnect, to the reading */

    const uint32_t reads = same_rows(b, "SPS30-A.csv", since, true, "a 161-reading tail");
    printf("  161-reading tail: %" PRIu32 " slot reads over a %" PRIu32 "-page session,"
           " %" PRIu64 " B; was %u reads, %u B\n",
           reads, summary->pages, b->bytes, SLOTS, SLOTS * STORE_PAGE_BYTES);
    /*
     * The bound is the session's own span, so what it costs is the session's
     * pages and not the ring. That is the whole claim, and it is stated against
     * `pages` rather than against a number that would drift with the fixture:
     * a header for each page of the span, and a whole page for the handful the
     * watermark leaves.
     */
    CHECK(reads < summary->pages + 32u,
          "the tail cost %" PRIu32 " slot reads over %" PRIu32 " pages", reads, summary->pages);
    /* And in flash bytes, which is the figure the 0.7 s was measured in: under
     * 1% of the 2.19 MB every `get_file` used to read. */
    CHECK(b->bytes * 100u < (uint64_t)SLOTS * STORE_PAGE_BYTES,
          "the tail read %" PRIu64 " B, not under 1%% of the 2.19 MB it used to", b->bytes);
}

/**
 * The whole of a session, which is the case the fixed cost does not dominate.
 *
 * The rows still have to be identical, and the pages it actually needs still
 * have to be read whole - the saving here is only the slots it skips.
 */
static void test_a_whole_session_comes_back_whole(void)
{
    printf("a whole session comes back whole\n");
    board_t *b = &s_board;
    board_reset(b);
    record(b, 1, 1754000000000LL, "SPS30-A.csv", 4000);

    const uint32_t reads = same_rows(b, "SPS30-A.csv", 0, true, "the whole session");
    const store_session_t *summary = summary_named(b, "SPS30-A.csv");
    CHECK(summary != NULL && summary->readings == 4000,
          "the index lost readings: %" PRIu32, summary == NULL ? 0 : summary->readings);
    printf("  4,000 readings: %" PRIu32 " slot reads over %" PRIu32 " pages\n", reads,
           summary == NULL ? 0 : summary->pages);
    /* One header read per slot in the span, plus one whole-page read per page it
     * uses. Nothing else may be read. */
    CHECK(summary != NULL && reads == summary->pages * 2u,
          "%" PRIu32 " reads for %" PRIu32 " pages", reads,
          summary == NULL ? 0 : summary->pages);
}

/**
 * Several sessions, and the one asked for is the oldest still held.
 *
 * The bound has to reach *backwards* as well as forwards. A walk that started at
 * the session's pages and ran to the end of the ring would be correct and slow;
 * one that ran to the end of the session's pages and no further is the point.
 */
static void test_an_old_session_does_not_walk_the_new_ones(void)
{
    printf("an old session does not walk the sessions after it\n");
    board_t *b = &s_board;
    board_reset(b);
    record(b, 1, 1754000000000LL, "SPS30-A.csv", 900);
    for (uint32_t n = 2; n <= 12; n++) {
        record(b, n, 1754000000000LL + (int64_t)n * 86400000LL, "SPS30-x.csv", 900);
    }
    /* Every one of those took the same derived name here, so ask by the summary
     * rather than the name: what matters is the identity, which is the pair. */
    const store_session_t *oldest = &b->sessions.slot[0];
    rows_t was = {0};
    rows_t now = {0};
    uint32_t reference_pages = 0;
    reference_walk(b, oldest->session, oldest->start_ms, 0, &was, &reference_pages);
    walk(b, oldest, true, 0, &now);
    size_t at = 0;
    CHECK(rows_identical(&was, &now, &at), "the oldest session: %zu rows before, %zu after",
          was.count, now.count);
    CHECK(was.count == 900, "the oldest session held %zu readings, not 900", was.count);
    printf("  the oldest of twelve: %" PRIu32 " slot reads\n", b->reads);
    CHECK(b->reads < 100, "reading the oldest of twelve cost %" PRIu32 " slot reads", b->reads);
    rows_free(&was);
    rows_free(&now);
}

/**
 * A ring driven right through a wrap, which is where a slot bound can alias.
 *
 * `page_seq` and slot are the same number offset by a constant, and the constant
 * only survives if the walk is planned modulo the ring. A session whose pages
 * straddle slot zero is the case that catches getting that wrong.
 */
static void test_a_wrap_does_not_move_the_answer(void)
{
    printf("a wrap does not move the answer\n");
    board_t *b = &s_board;
    board_reset(b);

    /* Fill the ring nearly to the end, then record a session across the seam. */
    record(b, 1, 1754000000000LL, "SPS30-old.csv", (SLOTS - 20u) * 40u);
    CHECK(!b->wrapped, "the ring wrapped before the session under test was written");
    record(b, 2, 1754100000000LL, "SPS30-seam.csv", 3000);
    CHECK(b->wrapped, "the ring did not wrap");

    const store_session_t *seam = NULL;
    for (size_t i = 0; i < b->sessions.count; i++) {
        if (b->sessions.slot[i].start_ms == 1754100000000LL) {
            seam = &b->sessions.slot[i];
        }
    }
    CHECK(seam != NULL, "the session across the seam was not indexed");
    if (seam == NULL) {
        return;
    }
    CHECK(seam->first_seq > seam->last_seq || store_walk_slot_of_seq(&(store_ring_geometry_t)
              {.slots = SLOTS, .head = b->head, .next_seq = b->next_seq, .wrapped = b->wrapped},
              seam->first_seq) > b->head,
          "the session under test does not straddle slot zero, so this proves nothing");

    rows_t was = {0};
    rows_t now = {0};
    uint32_t reference_pages = 0;
    reference_walk(b, seam->session, seam->start_ms, 0, &was, &reference_pages);
    walk(b, seam, true, 0, &now);
    size_t at = 0;
    CHECK(rows_identical(&was, &now, &at), "across the wrap: %zu rows before, %zu after",
          was.count, now.count);
    CHECK(was.count == 3000, "the session across the seam held %zu readings, not 3000",
          was.count);
    printf("  3,000 readings across slot zero: %" PRIu32 " slot reads\n", b->reads);
    rows_free(&was);
    rows_free(&now);
}

/**
 * The ring eating the front of a session while its summary keeps the span.
 *
 * ::store_sessions_forget deliberately leaves `first_seq` where it was, so the
 * span outlives the pages at its edge. That is the safe direction and this is
 * what makes it a claim: the walk must still return exactly what is left, and
 * must not go looking in the slot that sequence now names, which belongs to
 * somebody else.
 */
static void test_a_span_that_outlives_its_pages_still_answers(void)
{
    printf("a span that outlives its pages still answers\n");
    board_t *b = &s_board;
    board_reset(b);

    record(b, 1, 1754000000000LL, "SPS30-A.csv", 40000);
    const store_session_t *before = summary_named(b, "SPS30-A.csv");
    CHECK(before != NULL, "the long session was not indexed");
    if (before == NULL) {
        return;
    }
    const uint32_t span_start = before->first_seq;

    /* Now overwrite its front by recording enough to wrap the head past it. */
    record(b, 2, 1754100000000LL, "SPS30-B.csv", 160000);
    CHECK(b->wrapped, "the ring did not wrap");

    const store_session_t *after = summary_named(b, "SPS30-A.csv");
    CHECK(after != NULL, "the first session was forgotten entirely");
    if (after == NULL) {
        return;
    }
    CHECK(after->first_seq == span_start,
          "`first_seq` narrowed to %" PRIu32 " from %" PRIu32, after->first_seq, span_start);
    CHECK(after->readings < 40000, "the ring reclaimed nothing, so this proves nothing");

    const uint32_t reads = same_rows(b, "SPS30-A.csv", 0, true, "a half-eaten session");
    printf("  %" PRIu32 " readings left of 40,000: %" PRIu32 " slot reads\n",
           after->readings, reads);
}

/**
 * A cache that has had to drop a summary, which is when the span may lie.
 *
 * A summary re-made from a later page starts too late, and too late loses
 * readings. `store.c` answers that by not vouching for the span, and this is
 * what it must cost: the whole ring, the same rows, and nothing missing.
 */
static void test_an_untrusted_span_walks_the_whole_ring(void)
{
    printf("an untrusted span walks the whole ring, and loses nothing\n");
    board_t *b = &s_board;
    board_reset(b);
    record(b, 1, 1754000000000LL, "SPS30-A.csv", 4000);

    const store_session_t *summary = summary_named(b, "SPS30-A.csv");
    CHECK(summary != NULL, "the session was not indexed");
    if (summary == NULL) {
        return;
    }
    /* The span a re-made summary would carry: the session's newest page only. */
    store_session_t lying = *summary;
    lying.first_seq = lying.last_seq;

    rows_t whole = {0};
    rows_t trusting = {0};
    rows_t honest = {0};
    uint32_t reference_pages = 0;
    reference_walk(b, summary->session, summary->start_ms, 0, &whole, &reference_pages);

    /* Trusting a span that starts too late is exactly the failure being guarded
     * against, and it has to be visible for the guard to mean anything. */
    walk(b, &lying, true, 0, &trusting);
    CHECK(trusting.count < whole.count,
          "a span starting at the last page returned all %zu rows, so the guard is untested",
          whole.count);

    walk(b, &lying, false, 0, &honest);
    size_t at = 0;
    CHECK(rows_identical(&whole, &honest, &at),
          "not vouching for the span cost rows: %zu of %zu", honest.count, whole.count);
    CHECK(b->reads >= SLOTS, "the fallback read %" PRIu32 " slots, not the ring", b->reads);
    printf("  the fallback: %" PRIu32 " slot reads, %" PRIu64 " B (the old walk read %u B)\n",
           b->reads, b->bytes, SLOTS * STORE_PAGE_BYTES);
    /* Even the fallback is cheaper than what shipped, because the skip path is
     * now forty-eight bytes rather than five hundred and twelve. */
    CHECK(b->bytes < (uint64_t)SLOTS * STORE_PAGE_BYTES / 8u,
          "the fallback read %" PRIu64 " B, not under an eighth of 2.19 MB", b->bytes);

    rows_free(&whole);
    rows_free(&trusting);
    rows_free(&honest);
}

/**
 * Every watermark a client can send, against the same ring.
 *
 * `since_index` decides which pages are skipped on their header and which page
 * is entered part way through, and both are places an off-by-one hides. Sweeping
 * it is cheap here and would need a phone and a stopwatch anywhere else.
 */
static void test_every_watermark_returns_what_it_used_to(void)
{
    printf("every watermark returns what it used to\n");
    board_t *b = &s_board;
    board_reset(b);
    record(b, 1, 1754000000000LL, "SPS30-A.csv", 2000);

    for (uint32_t since = 0; since <= 2001; since += 7) {
        same_rows(b, "SPS30-A.csv", since, true, "a swept watermark");
    }
    /* And the three that are not on the stride: the ends, and one past them. */
    same_rows(b, "SPS30-A.csv", 1, true, "watermark 1");
    same_rows(b, "SPS30-A.csv", 1999, true, "watermark 1999");
    same_rows(b, "SPS30-A.csv", 2000, true, "watermark 2000 - nothing is left");
    same_rows(b, "SPS30-A.csv", 4000, true, "a watermark past the end");
}

/**
 * Two boots that both call themselves session 1.
 *
 * The session number restarts at 1 on every boot, so the start time is the other
 * half of the identity - and a walk bounded by a span must not let the bound
 * stand in for the check. Both sessions are in the ring at once, adjacent, and
 * each must come back with only its own readings.
 */
static void test_two_boots_do_not_bleed_into_each_other(void)
{
    printf("two boots that both call themselves session 1\n");
    board_t *b = &s_board;
    board_reset(b);
    record(b, 1, 1754000000000LL, "SPS30-A.csv", 500);
    record(b, 1, 1754003600000LL, "SPS30-B.csv", 500); /* same number, later boot */

    for (size_t i = 0; i < b->sessions.count; i++) {
        const store_session_t *summary = &b->sessions.slot[i];
        rows_t was = {0};
        rows_t now = {0};
        uint32_t reference_pages = 0;
        reference_walk(b, summary->session, summary->start_ms, 0, &was, &reference_pages);
        walk(b, summary, true, 0, &now);
        size_t at = 0;
        CHECK(rows_identical(&was, &now, &at), "boot %zu: %zu rows before, %zu after", i,
              was.count, now.count);
        CHECK(was.count == 500, "boot %zu came back with %zu readings, not 500", i, was.count);
        rows_free(&was);
        rows_free(&now);
    }
}

/** An empty ring, and a session with a span nothing was ever written for. */
static void test_an_empty_ring_answers_nothing_rather_than_wandering(void)
{
    printf("an empty ring answers nothing rather than wandering\n");
    board_t *b = &s_board;
    board_reset(b);

    store_cursor_t cursor;
    memset(&cursor, 0, sizeof(cursor));
    cursor.session = 1;
    cursor.session_start_ms = 1754000000000LL;
    const store_ring_geometry_t g = geometry_of(b);
    store_walk_plan(&cursor, &g, true, 0, 0);
    cursor.open = true;
    const store_slot_reader_t src = reader_of(b);
    b->reads = 0;
    store_reading_t reading;
    CHECK(!store_walk_next(&cursor, &src, &reading, NULL), "an empty ring produced a reading");
    CHECK(!cursor.open, "the cursor was left open over an empty ring");
    CHECK(b->reads <= SLOTS, "an empty ring cost %" PRIu32 " reads", b->reads);
}

/**
 * The slot a sequence names, at the two ends of the live window and past them.
 *
 * The one piece of arithmetic everything else here depends on, checked directly
 * rather than only through its consequences.
 */
static void test_the_slot_a_sequence_names(void)
{
    printf("the slot a sequence names\n");
    store_ring_geometry_t g;
    g.slots = SLOTS;
    g.head = 100;
    g.next_seq = 100 + 1; /* an unwrapped ring: slot n holds seq n + 1 */
    g.wrapped = false;
    CHECK(store_walk_slot_of_seq(&g, 1) == 0, "seq 1 is in slot %" PRIu32,
          store_walk_slot_of_seq(&g, 1));
    CHECK(store_walk_slot_of_seq(&g, 100) == 99, "seq 100 is in slot %" PRIu32,
          store_walk_slot_of_seq(&g, 100));

    /* A wrapped ring: the head holds the oldest page and head-1 the newest. */
    g.head = 7;
    g.next_seq = 100000;
    g.wrapped = true;
    CHECK(store_walk_slot_of_seq(&g, g.next_seq - 1) == 6, "the newest page is in slot %" PRIu32,
          store_walk_slot_of_seq(&g, g.next_seq - 1));
    CHECK(store_walk_slot_of_seq(&g, g.next_seq - SLOTS) == 7,
          "the oldest page is in slot %" PRIu32, store_walk_slot_of_seq(&g, g.next_seq - SLOTS));

    /* And through the point where `next_seq` itself wraps past 2^32. */
    g.head = 3;
    g.next_seq = 2;
    CHECK(store_walk_slot_of_seq(&g, 1) == 2, "seq 1 before the wrap is in slot %" PRIu32,
          store_walk_slot_of_seq(&g, 1));
    /* Counting back from `next_seq` 2: seq 1 is in slot 2, seq 0 in slot 1, and
     * the sequence before *that* is 0xFFFFFFFF, in slot 0. */
    CHECK(store_walk_slot_of_seq(&g, 0u) == 1, "seq 0 is in slot %" PRIu32,
          store_walk_slot_of_seq(&g, 0u));
    CHECK(store_walk_slot_of_seq(&g, 0xFFFFFFFFu) == 0,
          "the sequence before that is in slot %" PRIu32,
          store_walk_slot_of_seq(&g, 0xFFFFFFFFu));
}

int main(void)
{
    printf("store walk tests\n\n");
    test_the_slot_a_sequence_names();
    test_a_reconnect_reads_the_sessions_pages_not_the_ring();
    test_a_whole_session_comes_back_whole();
    test_an_old_session_does_not_walk_the_new_ones();
    test_a_wrap_does_not_move_the_answer();
    test_a_span_that_outlives_its_pages_still_answers();
    test_an_untrusted_span_walks_the_whole_ring();
    test_every_watermark_returns_what_it_used_to();
    test_two_boots_do_not_bleed_into_each_other();
    test_an_empty_ring_answers_nothing_rather_than_wandering();

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}

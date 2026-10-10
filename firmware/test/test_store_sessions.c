/**
 * @file test_store_sessions.c
 * @brief The rule that a summary goes when its pages go, and at no other time.
 *
 * `store_sessions.c` is the index over the flash ring: it is what lets a client
 * be told what the board holds, ask for one of those by name, and be told how
 * much is stored. So a summary dropped while its pages are still in the ring is
 * not a display bug - those readings become unreachable, because nothing else
 * can name them, and the board under-reports itself by the same amount.
 *
 * That is what used to happen. The table was a fixed sixteen and a seventeenth
 * session shifted the oldest one out at **any** ring fullness, on the reasoning
 * that its pages were about to be overwritten anyway. Measured on the board on
 * 2026-08-04: one reboot made a seventeenth session, a 12,975-reading session
 * vanished from the listing, the reported total fell 84,801 to 73,098, and
 * `used_pages` rose from 2977 to 3020 - the ring one third empty and nothing
 * reclaimed at all.
 *
 * ::test_a_seventeenth_session_forgets_nothing is that morning, in a hundred
 * microseconds. It fails against the code as it was.
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

/* --- a ring made of headers ------------------------------------------------ */

/** Readings a full page holds here. Only the arithmetic has to be consistent. */
#define PER_PAGE 46u

/**
 * One page of session @p n, the @p ordinal'th page of it.
 *
 * Sessions are numbered from 1 and started an hour apart, which is what a board
 * rebooted repeatedly produces: the acquisition session number restarts at 1 on
 * every boot, so the start time is the other half of the identity.
 */
static store_header_t page_of(uint32_t n, uint32_t ordinal, uint32_t seq)
{
    store_header_t h;
    memset(&h, 0, sizeof(h));
    h.page_seq = seq;
    h.session = 1; /* every boot calls itself session 1 - the trap this keys past */
    h.session_start_ms = 1754000000000LL + (int64_t)n * 3600000LL;
    h.page_start_ms = h.session_start_ms + (int64_t)ordinal * 46000LL;
    h.cadence_ms = 1000;
    h.first_index = ordinal * PER_PAGE + 1u;
    h.utc_offset_min = -240;
    h.count = (uint8_t)PER_PAGE;
    h.full = true;
    return h;
}

/** Write @p pages pages of session @p n, continuing the ring's sequence. */
static void record(store_sessions_t *t, uint32_t n, uint32_t pages, uint32_t *seq)
{
    for (uint32_t i = 0; i < pages; i++) {
        const store_header_t h = page_of(n, i, (*seq)++);
        store_sessions_note(t, &h, NULL);
    }
}

/** The summary for session @p n, or NULL. */
static const store_session_t *summary_of(const store_sessions_t *t, uint32_t n)
{
    const int64_t start = 1754000000000LL + (int64_t)n * 3600000LL;
    for (size_t i = 0; i < t->count; i++) {
        if (t->slot[i].start_ms == start) {
            return &t->slot[i];
        }
    }
    return NULL;
}

/* --- the finding ----------------------------------------------------------- */

/**
 * The morning of 2026-08-04, with the ring nowhere near full.
 *
 * Sixteen sessions, then a seventeenth - and nothing has been reclaimed, because
 * nothing has been forgotten. Every one of the seventeen must still be
 * described, and the total must still be the sum of what was written.
 */
static void test_a_seventeenth_session_forgets_nothing(void)
{
    printf("a seventeenth session, with the ring two thirds empty\n");
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;

    /* The long one first, as it was: 12,975 readings is 283 pages here. */
    record(&t, 1, 283, &seq);
    for (uint32_t n = 2; n <= 16; n++) {
        record(&t, n, 4, &seq); /* fifteen short ones, a reboot each */
    }
    const uint32_t before = store_sessions_readings(&t);
    CHECK(t.count == 16, "sixteen sessions recorded, %u described", (unsigned)t.count);

    record(&t, 17, 4, &seq); /* the reboot that used to cost the long one */

    CHECK(t.forgotten == 0, "%" PRIu32 " summaries dropped with every page still live",
          t.forgotten);
    CHECK(summary_of(&t, 1) != NULL, "the longest session vanished from the listing");
    const store_session_t *longest = summary_of(&t, 1);
    if (longest != NULL) {
        CHECK(longest->readings == 283u * PER_PAGE, "it came back short: %" PRIu32,
              longest->readings);
    }
    CHECK(t.count == 17, "seventeen sessions held, %u described", (unsigned)t.count);
    CHECK(store_sessions_readings(&t) == before + 4u * PER_PAGE,
          "the reported total fell from %" PRIu32 " to %" PRIu32 " while the board took on "
          "more",
          before, store_sessions_readings(&t));
    CHECK(store_sessions_pages(&t) == 283u + 16u * 4u, "pages held do not add up: %" PRIu32,
          store_sessions_pages(&t));
}

/* --- what does remove a summary -------------------------------------------- */

/**
 * The ring reclaiming the last page of a session, and not one page before.
 *
 * The old comment's claim, now made true by construction rather than assumed:
 * the summary survives every page but the last, and the totals track what is
 * actually left at each step.
 */
static void test_a_summary_goes_with_its_last_page(void)
{
    printf("a summary dies with its last page, not before\n");
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    record(&t, 1, 3, &seq);
    record(&t, 2, 2, &seq);

    /* The ring reclaims in sequence order: session 1's pages go first. */
    for (uint32_t i = 0; i < 3; i++) {
        const store_header_t gone = page_of(1, i, i + 1);
        const bool last = store_sessions_forget(&t, &gone);
        CHECK(last == (i == 2), "page %u of three reported the wrong verdict", (unsigned)i);
        const store_session_t *entry = summary_of(&t, 1);
        if (i < 2) {
            CHECK(entry != NULL, "the summary went while %u pages were still held",
                  (unsigned)(3 - i - 1));
            if (entry != NULL) {
                CHECK(entry->readings == (3 - i - 1) * PER_PAGE,
                      "readings held say %" PRIu32 " with %u pages left", entry->readings,
                      (unsigned)(3 - i - 1));
                /* What a cursor promises: the lowest index still readable. */
                CHECK(entry->first_index == (i + 1) * PER_PAGE + 1u,
                      "first_index still claims %" PRIu32 " after %u pages went",
                      entry->first_index, (unsigned)(i + 1));
            }
        } else {
            CHECK(entry == NULL, "the summary outlived its last page");
        }
    }
    CHECK(t.count == 1, "%u summaries left, expected one", (unsigned)t.count);
    CHECK(store_sessions_readings(&t) == 2u * PER_PAGE,
          "the board over-reports what the ring still holds: %" PRIu32,
          store_sessions_readings(&t));
}

/* --- order ----------------------------------------------------------------- */

/**
 * The table is ordered by what the ring destroys next, not by what it saw first.
 *
 * The boot scan walks slots, and after a wrap slot zero holds a page newer than
 * the last slot - so "the first summary made" and "the oldest session" are only
 * accidentally the same thing. The listing is newest-first off the end of that
 * order, which is what makes a truncated list the useful half.
 */
static void test_order_follows_the_ring(void)
{
    printf("the order is the ring's, not the scan's\n");
    store_sessions_t t;
    store_sessions_reset(&t);

    /* Seen in slot order: session 3's pages are the oldest in the ring even
     * though its summary is made last. */
    const store_header_t a = page_of(1, 0, 900);
    const store_header_t b = page_of(2, 0, 950);
    const store_header_t c = page_of(3, 0, 100);
    store_sessions_note(&t, &a, NULL);
    store_sessions_note(&t, &b, NULL);
    store_sessions_note(&t, &c, NULL);

    CHECK(t.count == 3, "three sessions, %u described", (unsigned)t.count);
    CHECK(t.slot[0].start_ms == c.session_start_ms,
          "the session the ring reclaims first is not at the front");

    store_session_t list[STORE_SESSION_INDEX_MAX];
    const size_t n = store_sessions_list(&t, list, STORE_SESSION_INDEX_MAX);
    CHECK(n == 3, "listed %u of three", (unsigned)n);
    CHECK(list[0].start_ms == b.session_start_ms, "the listing does not start with the newest");
    CHECK(list[2].start_ms == c.session_start_ms, "the listing does not end with the oldest");
}

/* --- the ceiling, when it is genuinely reached ----------------------------- */

/**
 * ::STORE_SESSION_INDEX_MAX sessions live at once, and one more arrives.
 *
 * The table cannot describe them all and this is the one case where it must
 * still drop a live summary. Two things have to hold: the one it drops is the
 * session the ring will finish reclaiming first - not whichever happens to sit
 * at the front of the array - and it is **counted**, so a board holding
 * readings it cannot name says so instead of being quietly wrong.
 */
static void test_a_full_table_drops_the_next_to_die_and_counts_it(void)
{
    printf("a genuinely full table gives up the session the ring takes first\n");
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    for (uint32_t n = 1; n <= STORE_SESSION_INDEX_MAX; n++) {
        record(&t, n, 2, &seq);
    }
    CHECK(t.count == STORE_SESSION_INDEX_MAX, "%u sessions described, expected %u",
          (unsigned)t.count, (unsigned)STORE_SESSION_INDEX_MAX);
    CHECK(t.forgotten == 0, "something was dropped before the table was full");

    record(&t, STORE_SESSION_INDEX_MAX + 1u, 2, &seq);

    CHECK(t.forgotten == 1, "a live summary went unrecorded: forgotten is %" PRIu32,
          t.forgotten);
    CHECK(summary_of(&t, 1) == NULL, "the session nearest the erase head was kept");
    CHECK(summary_of(&t, 2) != NULL, "a session further from the erase head went instead");
    CHECK(summary_of(&t, STORE_SESSION_INDEX_MAX + 1u) != NULL, "the new session was not described");
    CHECK(t.count == STORE_SESSION_INDEX_MAX, "the table grew past its ceiling: %u",
          (unsigned)t.count);
}

/* --- imports, which do not arrive in ring order ---------------------------- */

/**
 * A page reclaimed from the middle of a session leaves `first_index` alone.
 *
 * An import appends pages for an old session at new sequences, so its pages are
 * not contiguous in the ring and the one the erase head reaches need not be the
 * session's oldest. Advancing `first_index` on that page would have it claim a
 * lower bound that outran the readings actually left.
 */
static void test_a_reclaimed_middle_page_does_not_move_the_floor(void)
{
    printf("reclaiming a page from the middle leaves the floor where it is\n");
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    record(&t, 1, 3, &seq); /* pages 0,1,2 - indices 1..138 */

    /* The erase head reaches the second page, not the first. */
    const store_header_t middle = page_of(1, 1, 2);
    (void)store_sessions_forget(&t, &middle);
    const store_session_t *entry = summary_of(&t, 1);
    CHECK(entry != NULL, "the summary went with a page it had two more of");
    if (entry != NULL) {
        CHECK(entry->first_index == 1u, "first_index moved to %" PRIu32 " past readings held",
              entry->first_index);
        CHECK(entry->readings == 2u * PER_PAGE, "readings held say %" PRIu32, entry->readings);
    }
}

/* --- identity -------------------------------------------------------------- */

/**
 * Two sessions numbered 1, told apart by when they started.
 *
 * The acquisition session number restarts at 1 on every boot, so keying on it
 * alone merged a session with the one before it and made a cursor over the old
 * one hand out readings from the new. Both halves of the key, or neither.
 */
static void test_two_boots_are_two_sessions(void)
{
    printf("two boots that both call themselves session 1\n");
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    record(&t, 1, 2, &seq);
    record(&t, 2, 2, &seq);
    CHECK(t.count == 2, "two boots became %u session(s)", (unsigned)t.count);
    CHECK(t.slot[0].session == t.slot[1].session,
          "the fixture stopped exercising the collision");
    CHECK(strcmp(t.slot[0].name, t.slot[1].name) != 0,
          "two sessions were given the same name: %s", t.slot[0].name);
}

/** A session can be reached by the name the listing gave it. */
static void test_a_session_is_reachable_by_name(void)
{
    printf("what the listing names, get_file can find\n");
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    record(&t, 7, 5, &seq);

    store_session_t list[STORE_SESSION_INDEX_MAX];
    const size_t n = store_sessions_list(&t, list, STORE_SESSION_INDEX_MAX);
    CHECK(n == 1, "listed %u of one", (unsigned)n);
    const store_session_t *found = store_sessions_find(&t, list[0].name);
    CHECK(found != NULL, "the name the listing gave could not be looked up: %s", list[0].name);
    if (found != NULL) {
        CHECK(found->readings == 5u * PER_PAGE, "found %" PRIu32 " readings", found->readings);
    }
    CHECK(store_sessions_find(&t, "SPS30-20200101_000000.csv") == NULL,
          "a session nobody holds was found");
}

/* --- the ring as the index ------------------------------------------------- */

/**
 * A ring of page headers, which is all the scan ever reads.
 *
 * `store.c` reads the same forty-eight bytes a slot out of flash. Nothing here
 * needs the bodies: a session's identity, shape and name are entirely in its
 * headers, which is the fact the whole slow path rests on.
 */
#define RING_SLOTS 4096u

typedef struct {
    store_header_t page[RING_SLOTS];
    bool live[RING_SLOTS];
    uint32_t used;
} ring_t;

static bool ring_read(void *ctx, uint32_t slot, store_header_t *out)
{
    const ring_t *ring = ctx;
    if (slot >= RING_SLOTS || !ring->live[slot]) {
        return false;
    }
    *out = ring->page[slot];
    return true;
}

static store_page_source_t source_over(ring_t *ring)
{
    store_page_source_t src;
    src.read = ring_read;
    src.ctx = ring;
    src.slots = RING_SLOTS;
    return src;
}

/** Put one page in the ring and offer it to the cache, as `store.c` does. */
static void ring_put(ring_t *ring, store_sessions_t *t, const store_header_t *page)
{
    if (ring->used < RING_SLOTS) {
        ring->page[ring->used] = *page;
        ring->live[ring->used] = true;
        ring->used++;
    }
    if (t != NULL) {
        store_sessions_note(t, page, NULL);
    }
}

/** Write @p pages pages of session @p n into both the ring and the cache. */
static void ring_record(ring_t *ring, store_sessions_t *t, uint32_t n, uint32_t pages,
                        uint32_t *seq)
{
    for (uint32_t i = 0; i < pages; i++) {
        const store_header_t h = page_of(n, i, (*seq)++);
        ring_put(ring, t, &h);
    }
}

/** The name session @p n is listed under, derived the way the store derives it. */
static void name_of(const ring_t *ring, uint32_t n, char *out, size_t cap)
{
    const int64_t start = 1754000000000LL + (int64_t)n * 3600000LL;
    store_sessions_t scratch;
    store_sessions_reset(&scratch);
    for (uint32_t slot = 0; slot < ring->used; slot++) {
        if (ring->page[slot].session_start_ms == start) {
            const store_session_t *made = store_sessions_note(&scratch, &ring->page[slot], NULL);
            if (made != NULL) {
                snprintf(out, cap, "%s", made->name);
                return;
            }
        }
    }
    out[0] = '\0';
}

/**
 * The thirty-third session, on 2026-08-04, with a quarter of the ring free.
 *
 * The finding this file's first test was written for, moved up a number. The
 * index was raised from sixteen to thirty-two and the demo board reached
 * thirty-two, so a restart made a thirty-third session,
 * `SPS30-20260803_002028.csv` was shifted out of the listing, and `export`
 * answered *"no stored session named ..."* while `used_pages` **rose** and
 * nothing was reclaimed.
 *
 * The index still has to give up a slot here - it is a fixed array and there is
 * no size at which it would not, since the ring can hold one session per page.
 * What must not happen any more is anything else: the session stays readable,
 * stays counted, and stays listed, because the ring holds its identity in every
 * page header and that is now what answers.
 */
static void test_a_session_past_the_index_is_still_the_boards(void)
{
    printf("a thirty-third session, with a quarter of the ring free\n");
    static ring_t ring;
    memset(&ring, 0, sizeof(ring));
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;

    /* The one that went: 353 readings, eight pages here. */
    ring_record(&ring, &t, 1, 8, &seq);
    for (uint32_t n = 2; n <= STORE_SESSION_INDEX_MAX; n++) {
        ring_record(&ring, &t, n, 2, &seq);
    }
    char lost[32];
    name_of(&ring, 1, lost, sizeof(lost));
    CHECK(store_sessions_find(&t, lost) != NULL, "the fixture did not start with it in RAM");

    ring_record(&ring, &t, STORE_SESSION_INDEX_MAX + 1u, 2, &seq); /* the restart */

    const store_page_source_t src = source_over(&ring);
    const uint32_t pages_held = 8u + (uint32_t)STORE_SESSION_INDEX_MAX * 2u;
    const uint32_t readings_held = pages_held * PER_PAGE;

    /* The index gave up a slot, and said so rather than pretending otherwise. */
    CHECK(t.incomplete, "the index dropped a live summary without admitting it");
    CHECK(store_sessions_find(&t, lost) == NULL, "the fixture stopped exercising the drop");

    /* Reachable. This is `get_file` on the session the board used to answer
     * "no stored session named ..." for. */
    store_session_t found;
    CHECK(store_sessions_scan_find(&src, lost, &found),
          "the session the index gave up cannot be found in the ring: %s", lost);
    CHECK(found.readings == 8u * PER_PAGE, "it came back with %" PRIu32 " of %u readings",
          found.readings, (unsigned)(8u * PER_PAGE));
    CHECK(found.first_index == 1u, "its readings start at %" PRIu32, found.first_index);
    CHECK(found.last_index == 8u * PER_PAGE, "its readings end at %" PRIu32, found.last_index);
    CHECK(strcmp(found.name, lost) == 0, "it came back under another name: %s", found.name);

    /* Counted. `storage.readings` fell by 12,975 the first time this happened
     * and did not fall the second time only because live acquisition outran the
     * loss; measured over the ring it cannot do either. */
    uint32_t pages = 0;
    CHECK(store_sessions_scan_readings(&src, &pages) == readings_held,
          "the ring says it holds %" PRIu32 " readings, not %" PRIu32,
          store_sessions_scan_readings(&src, NULL), readings_held);
    CHECK(pages == pages_held, "the ring says %" PRIu32 " pages, not %" PRIu32, pages,
          pages_held);
    CHECK(store_sessions_readings(&t) < readings_held,
          "the fixture stopped exercising the gap between RAM and the ring");

    /* Listed, which is what a backup walks. */
    store_session_t work[8];
    CHECK(store_sessions_scan_count(&src, work, 8) == STORE_SESSION_INDEX_MAX + 1u,
          "the ring holds %" PRIu32 " sessions, not %u",
          store_sessions_scan_count(&src, work, 8),
          (unsigned)(STORE_SESSION_INDEX_MAX + 1u));
}

/**
 * The scan pages through more sessions than any table could hold summaries for.
 *
 * The property that makes "whatever the table's size" true rather than a larger
 * number: two hundred sessions, enumerated eight at a time in constant memory,
 * each appearing exactly once with every one of its pages folded in. A scan that
 * skipped one would hide a session from a backup; a scan that repeated one would
 * make the board over-report itself.
 */
static void test_the_scan_pages_past_what_ram_could_hold(void)
{
    printf("two hundred sessions, enumerated eight at a time\n");
    static ring_t ring;
    memset(&ring, 0, sizeof(ring));
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    const uint32_t sessions = 200;
    for (uint32_t n = 1; n <= sessions; n++) {
        ring_record(&ring, &t, n, 3, &seq);
    }
    const store_page_source_t src = source_over(&ring);

    uint32_t seen[201];
    memset(seen, 0, sizeof(seen));
    store_session_t work[8];
    store_session_key_t after;
    bool have_after = false;
    uint32_t emitted = 0;
    uint32_t passes = 0;
    for (;;) {
        const size_t n = store_sessions_scan_window(&src, have_after ? &after : NULL, work, 8);
        if (n == 0) {
            break;
        }
        passes++;
        CHECK(passes <= sessions, "the enumeration did not terminate");
        if (passes > sessions) {
            break;
        }
        for (size_t i = 0; i < n; i++) {
            /* Which session this is, from the start time the fixture gave it. */
            const uint32_t which =
                (uint32_t)((work[i].start_ms - 1754000000000LL) / 3600000LL);
            CHECK(which >= 1 && which <= sessions, "a session the fixture never wrote: %u",
                  (unsigned)which);
            if (which >= 1 && which <= sessions) {
                seen[which]++;
                CHECK(work[i].readings == 3u * PER_PAGE,
                      "session %u came back with %" PRIu32 " readings", (unsigned)which,
                      work[i].readings);
            }
            emitted++;
        }
        /* Ascending, so the last is always the one to continue past. */
        for (size_t i = 0; i + 1 < n; i++) {
            CHECK(work[i].start_ms < work[i + 1].start_ms,
                  "the window came back out of order at %u", (unsigned)i);
        }
        after = store_sessions_key(&work[n - 1]);
        have_after = true;
        if (n < 8) {
            break;
        }
    }
    CHECK(emitted == sessions, "%" PRIu32 " sessions enumerated, expected %" PRIu32, emitted,
          sessions);
    uint32_t missed = 0;
    uint32_t doubled = 0;
    for (uint32_t n = 1; n <= sessions; n++) {
        if (seen[n] == 0) {
            missed++;
        } else if (seen[n] > 1) {
            doubled++;
        }
    }
    CHECK(missed == 0, "%" PRIu32 " sessions were never enumerated", missed);
    CHECK(doubled == 0, "%" PRIu32 " sessions were enumerated more than once", doubled);
    CHECK(store_sessions_scan_count(&src, work, 8) == sessions,
          "counted %" PRIu32 " of %" PRIu32, store_sessions_scan_count(&src, work, 8),
          sessions);
    CHECK(store_sessions_scan_readings(&src, NULL) == sessions * 3u * PER_PAGE,
          "the total over %" PRIu32 " sessions is %" PRIu32, sessions,
          store_sessions_scan_readings(&src, NULL));
}

/**
 * The two halves of the index answer the same thing.
 *
 * The cache is a cache, so on a board whose sessions fit it every summary it
 * holds must be exactly what the ring would produce for the same name. If they
 * could differ, a board would describe a session one way while it had a slot and
 * another way afterwards.
 */
static void test_the_cache_and_the_ring_agree(void)
{
    printf("what RAM says and what the ring says, session for session\n");
    static ring_t ring;
    memset(&ring, 0, sizeof(ring));
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    for (uint32_t n = 1; n <= 5; n++) {
        ring_record(&ring, &t, n, n, &seq); /* different lengths, so a mix-up shows */
    }
    const store_page_source_t src = source_over(&ring);
    CHECK(!t.incomplete, "the fixture overflowed the index");

    for (uint32_t n = 1; n <= 5; n++) {
        char name[32];
        name_of(&ring, n, name, sizeof(name));
        const store_session_t *cached = store_sessions_find(&t, name);
        store_session_t scanned;
        const bool have = store_sessions_scan_find(&src, name, &scanned);
        CHECK(cached != NULL && have, "session %u: RAM %s, ring %s", (unsigned)n,
              cached != NULL ? "has it" : "does not", have ? "has it" : "does not");
        if (cached == NULL || !have) {
            continue;
        }
        CHECK(cached->readings == scanned.readings && cached->pages == scanned.pages &&
                  cached->first_index == scanned.first_index &&
                  cached->last_index == scanned.last_index &&
                  cached->start_ms == scanned.start_ms && cached->session == scanned.session &&
                  cached->cadence_ms == scanned.cadence_ms &&
                  cached->relative == scanned.relative &&
                  strcmp(cached->name, scanned.name) == 0,
              "session %u is described differently by RAM and by the ring", (unsigned)n);
    }
    CHECK(store_sessions_scan_readings(&src, NULL) == store_sessions_readings(&t),
          "the totals disagree: ring %" PRIu32 ", RAM %" PRIu32,
          store_sessions_scan_readings(&src, NULL), store_sessions_readings(&t));
    store_session_t scanned;
    CHECK(!store_sessions_scan_find(&src, "SPS30-20200101_000000.csv", &scanned),
          "the ring found a session nobody wrote");
}

/**
 * A page the ring has reclaimed stops being part of the answer.
 *
 * The rule, checked against the ring rather than against the cache: what is not
 * in flash is not held, and a scan that read a stale slot would have the board
 * offer readings it can no longer produce.
 */
static void test_the_scan_only_counts_what_is_there(void)
{
    printf("what the ring has reclaimed, the scan does not offer\n");
    static ring_t ring;
    memset(&ring, 0, sizeof(ring));
    uint32_t seq = 1;
    ring_record(&ring, NULL, 1, 3, &seq);
    ring_record(&ring, NULL, 2, 2, &seq);
    const store_page_source_t src = source_over(&ring);
    char first[32];
    name_of(&ring, 1, first, sizeof(first));

    /* The ring reclaims the sector holding session 1's first two pages. */
    ring.live[0] = false;
    ring.live[1] = false;

    store_session_t found;
    CHECK(store_sessions_scan_find(&src, first, &found),
          "a session with one page left could not be found");
    if (found.readings != 0) {
        CHECK(found.readings == PER_PAGE, "it offers %" PRIu32 " readings, not %u",
              found.readings, (unsigned)PER_PAGE);
        CHECK(found.first_index == 2u * PER_PAGE + 1u,
              "it claims readings from %" PRIu32 ", which are gone", found.first_index);
    }
    store_session_t work[4];
    CHECK(store_sessions_scan_count(&src, work, 4) == 2, "sessions held: %" PRIu32,
          store_sessions_scan_count(&src, work, 4));
    CHECK(store_sessions_scan_readings(&src, NULL) == 3u * PER_PAGE,
          "the ring offers %" PRIu32 " readings after two pages went",
          store_sessions_scan_readings(&src, NULL));

    /* And when the last page goes, so does the session. */
    ring.live[2] = false;
    CHECK(!store_sessions_scan_find(&src, first, &found),
          "a session with no pages left was still offered");
    CHECK(store_sessions_scan_count(&src, work, 4) == 1, "sessions held: %" PRIu32,
          store_sessions_scan_count(&src, work, 4));
}

/**
 * Two sessions that derive the same name are not folded into one.
 *
 * A name is a function of the second a session started in, so two boots inside
 * one second name themselves the same thing. The cache has always answered such
 * a lookup with the first match and the scan must do the same - what it must not
 * do is add the two together, which would hand a client readings under a name
 * whose indices do not run.
 */
static void test_a_namesake_is_not_folded_in(void)
{
    printf("two sessions that would name themselves the same thing\n");
    static ring_t ring;
    memset(&ring, 0, sizeof(ring));
    uint32_t seq = 1;
    store_header_t a = page_of(1, 0, seq++);
    store_header_t b = page_of(1, 0, seq++);
    b.session = 2; /* a different run, the same wall-clock second */
    ring_put(&ring, NULL, &a);
    ring_put(&ring, NULL, &b);

    const store_page_source_t src = source_over(&ring);
    store_sessions_t t;
    store_sessions_reset(&t);
    const store_session_t *made = store_sessions_note(&t, &a, NULL);
    CHECK(made != NULL, "the fixture could not name the first session");
    char name[32];
    snprintf(name, sizeof(name), "%s", made != NULL ? made->name : "");

    store_session_t found;
    CHECK(store_sessions_scan_find(&src, name, &found), "the namesake pair could not be found");
    CHECK(found.readings == PER_PAGE,
          "two namesakes were added together: %" PRIu32 " readings under one name",
          found.readings);
    store_session_t work[4];
    CHECK(store_sessions_scan_count(&src, work, 4) == 2,
          "two namesakes counted as %" PRIu32 " session(s)",
          store_sessions_scan_count(&src, work, 4));
}

/**
 * What one `file_list` frame can carry - `net_server.c`'s `FILE_LIST_MAX`.
 *
 * Not included from there: that file is FreeRTOS and lwIP and does not compile
 * here. It is the number this test exists to stop being the ceiling, so it is
 * written out and named rather than inferred.
 */
#define ONE_FRAME 64u

/**
 * Eighty-four sessions, named sixty-four at a time - the demo board, walked.
 *
 * A frame holds what a frame holds, and on 2026-08-04 the board held 84 sessions
 * and could name 64 of them. The other 20 were not lost - it counted them, served
 * them and would have erased them - but nothing could learn their *names*, and a
 * name is the only handle the protocol has. So *Copy sessions to this device*
 * wrote 64 and said in red that it was not a backup, the erase gate could never
 * clear the board, and the shortfall grew by one on every restart.
 *
 * Raising the frame's capacity is the fix that already failed twice on the
 * session table, for the reason that applies here unchanged: any fixed number is
 * a number a board eventually holds more than. Paging is the fix that does not
 * have a number in it.
 *
 * This is the guard for that. It fails against a listing that answers only its
 * first page: 64 named of 84, and twenty sessions with no name at all.
 */
static void test_a_listing_longer_than_one_frame_names_everything(void)
{
    printf("eighty-four sessions, named sixty-four at a time\n");
    static ring_t ring;
    memset(&ring, 0, sizeof(ring));
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    const uint32_t sessions = 84;
    for (uint32_t n = 1; n <= sessions; n++) {
        ring_record(&ring, &t, n, 2, &seq);
    }
    const store_page_source_t src = source_over(&ring);

    static store_session_t page[ONE_FRAME];
    uint32_t seen[85];
    memset(seen, 0, sizeof(seen));
    uint32_t offset = 0;
    uint32_t named = 0;
    uint32_t pages = 0;
    int64_t previous = INT64_MAX; /* newest first, so start times descend */
    for (;;) {
        uint32_t total = 0;
        const size_t n = store_sessions_scan_newest(&src, offset, page, ONE_FRAME, &total);
        CHECK(total == sessions, "the walk was told the board holds %" PRIu32 ", not %u",
              total, (unsigned)sessions);
        if (n == 0) {
            break;
        }
        pages++;
        CHECK(pages <= 4, "the walk did not terminate");
        if (pages > 4) {
            break;
        }
        for (size_t i = 0; i < n; i++) {
            const uint32_t which =
                (uint32_t)((page[i].start_ms - 1754000000000LL) / 3600000LL);
            CHECK(which >= 1 && which <= sessions, "a session the fixture never wrote: %u",
                  (unsigned)which);
            if (which >= 1 && which <= sessions) {
                seen[which]++;
            }
            CHECK(page[i].start_ms < previous,
                  "the listing was not newest first at entry %u", (unsigned)named);
            previous = page[i].start_ms;
            named++;
        }
        offset += (uint32_t)n;
        if (offset >= total) {
            break;
        }
    }
    CHECK(named == sessions, "the walk named %" PRIu32 " of %u sessions", named,
          (unsigned)sessions);
    CHECK(pages == 2, "84 sessions 64 at a time took %" PRIu32 " page(s)", pages);
    for (uint32_t n = 1; n <= sessions; n++) {
        CHECK(seen[n] == 1, "session %u was named %" PRIu32 " times", (unsigned)n, seen[n]);
    }

    /* One page is still one frame's worth. If this ever came back as 84 the walk
     * above would be passing because the cap moved, which is the fix that has
     * already failed twice rather than this one. */
    CHECK(store_sessions_scan_newest(&src, 0, page, ONE_FRAME, NULL) == ONE_FRAME,
          "one page stopped being one frame's worth");
    CHECK(store_sessions_scan_newest(&src, ONE_FRAME, page, ONE_FRAME, NULL) ==
              sessions - ONE_FRAME,
          "the second page did not hold the remaining twenty");

    /* Past the end is an empty answer, not an error and not the oldest page
     * over again: a listing can shrink under a client part way through it. */
    CHECK(store_sessions_scan_newest(&src, sessions, page, ONE_FRAME, NULL) == 0,
          "an offset at the end named something");
    CHECK(store_sessions_scan_newest(&src, sessions + 1000u, page, ONE_FRAME, NULL) == 0,
          "an offset past the end named something");
}

/**
 * The same walk over the RAM cache, which is the path a board under its size uses.
 *
 * Two implementations of "newest first, from an offset" and one protocol over
 * them, so a board that crosses ::STORE_SESSION_INDEX_MAX mid-walk must not
 * change what the walk means. The cheap half is checked here against the same
 * property: every session named once, newest first, and nothing past the end.
 */
static void test_the_cached_listing_pages_the_same_way(void)
{
    printf("the cache pages newest-first from an offset too\n");
    static ring_t ring;
    memset(&ring, 0, sizeof(ring));
    store_sessions_t t;
    store_sessions_reset(&t);
    uint32_t seq = 1;
    const uint32_t sessions = 7;
    for (uint32_t n = 1; n <= sessions; n++) {
        ring_record(&ring, &t, n, 1, &seq);
    }
    CHECK(!t.incomplete, "the fixture outgrew the cache and is testing the wrong path");

    store_session_t whole[8];
    const size_t all = store_sessions_list(&t, whole, 8);
    CHECK(all == sessions, "the cache listed %u of %u", (unsigned)all, (unsigned)sessions);

    store_session_t page[3];
    uint32_t named = 0;
    for (uint32_t offset = 0; offset < sessions; offset += 3) {
        const size_t n = store_sessions_list_from(&t, offset, page, 3);
        CHECK(n > 0, "the cache gave up at offset %" PRIu32, offset);
        for (size_t i = 0; i < n; i++) {
            CHECK(page[i].start_ms == whole[offset + i].start_ms,
                  "entry %" PRIu32 " of the paged listing is not the same session",
                  offset + (uint32_t)i);
            named++;
        }
    }
    CHECK(named == sessions, "the paged cache named %" PRIu32 " of %u", named,
          (unsigned)sessions);
    CHECK(store_sessions_list_from(&t, sessions, page, 3) == 0,
          "an offset at the end named something");
    CHECK(store_sessions_list_from(&t, sessions + 100u, page, 3) == 0,
          "an offset past the end named something");
}

int main(void)
{
    printf("store session table tests\n\n");
    test_a_seventeenth_session_forgets_nothing();
    test_a_summary_goes_with_its_last_page();
    test_order_follows_the_ring();
    test_a_full_table_drops_the_next_to_die_and_counts_it();
    test_a_reclaimed_middle_page_does_not_move_the_floor();
    test_two_boots_are_two_sessions();
    test_a_session_is_reachable_by_name();
    test_a_session_past_the_index_is_still_the_boards();
    test_the_scan_pages_past_what_ram_could_hold();
    test_the_cache_and_the_ring_agree();
    test_the_scan_only_counts_what_is_there();
    test_a_namesake_is_not_folded_in();
    test_a_listing_longer_than_one_frame_names_everything();
    test_the_cached_listing_pages_the_same_way();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

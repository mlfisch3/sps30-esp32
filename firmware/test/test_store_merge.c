/**
 * @file test_store_merge.c
 * @brief The one claim worth proving: an import cannot cost a reading.
 *
 * `store_merge.c` is written so that the only route from imported data to flash
 * is a ::store_sink_t, and a sink that refuses ends the import. That makes the
 * safety argument checkable rather than rhetorical, and this is the check: a
 * simulated ring that **counts every write and refuses every overwrite**, driven
 * through the real page format by the real merge.
 *
 * Every test here ends by comparing the ring byte for byte with a snapshot taken
 * before the import. Not reading counts - bytes. A merge that quietly rewrote a
 * page with identical-looking values would pass a count check and fail this one.
 *
 * Build and run with `make -C firmware/test`. No ESP-IDF, no board, no flash.
 */

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "store_codec.h"
#include "store_merge.h"

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

/* --- a ring that tells on you ---------------------------------------------- */

#define SIM_SLOTS 512

/**
 * A ring with the store's shape and a conscience.
 *
 * Stricter than the real partition on purpose: it refuses to write over a slot
 * that holds a page at all, where flash would erase a whole sector to do it.
 * Anything that would have evicted therefore fails here loudly instead of
 * quietly costing eight pages, and `overwrites` is the number this suite exists
 * to keep at zero.
 */
typedef struct {
    uint8_t slot[SIM_SLOTS][STORE_PAGE_BYTES];
    bool used[SIM_SLOTS];
    uint32_t head;
    uint32_t next_seq;
    uint32_t writes;
    uint32_t overwrites; /**< attempts, refused; must stay zero */
    /** Slots the ring may use, so a test can set up a nearly-full one. */
    uint32_t limit;
} sim_ring_t;

static void sim_reset(sim_ring_t *ring)
{
    memset(ring, 0, sizeof(*ring));
    ring->next_seq = 1;
    ring->limit = SIM_SLOTS;
}

static uint32_t sim_next_seq(void *ctx)
{
    return ((sim_ring_t *)ctx)->next_seq;
}

static bool sim_commit(void *ctx, const uint8_t page[STORE_PAGE_BYTES])
{
    sim_ring_t *ring = ctx;
    if (ring->head >= ring->limit || ring->used[ring->head]) {
        ring->overwrites++;
        return false; /* full: evict nothing, and say so */
    }
    memcpy(ring->slot[ring->head], page, STORE_PAGE_BYTES);
    ring->used[ring->head] = true;
    ring->head++;
    ring->next_seq++;
    ring->writes++;
    return true;
}

static store_sink_t sim_sink(sim_ring_t *ring)
{
    return (store_sink_t){.next_seq = sim_next_seq, .commit = sim_commit, .ctx = ring};
}

/* --- recording, the way the firmware does ---------------------------------- */

/** A reading whose values are a function of its index, so drift is visible. */
static store_reading_t made(uint32_t index, bool settling)
{
    store_reading_t r = {
        .index = index,
        .epoch_ms = 1700000000000LL + (int64_t)index * 1000,
        .settling = settling,
    };
    for (int f = 0; f < STORE_FIELDS; f++) {
        r.values[f] = (float)((index % 97) + f) * 0.37f;
    }
    return r;
}

/**
 * Record `[first, last]` of @p session into the ring, as acquisition would.
 *
 * Stops the moment the ring refuses a page, which is how a test sets up a ring
 * that is full, or full but for four slots, without having to know how many
 * readings a page happens to hold.
 */
static void record(sim_ring_t *ring, uint32_t session, int64_t start_ms, uint32_t first,
                   uint32_t last)
{
    store_writer_t w;
    store_writer_init(&w, ring->next_seq, session, start_ms, 1000, 0, false);
    for (uint32_t i = first; i <= last; i++) {
        const store_reading_t r = made(i, i % 40 == 0);
        if (!store_writer_add(&w, &r)) {
            uint8_t page[STORE_PAGE_BYTES];
            if (store_writer_seal(&w, page, true) && !sim_commit(ring, page)) {
                return; /* the ring is as full as this test wanted it */
            }
            store_writer_init(&w, ring->next_seq, session, start_ms, 1000, 0, false);
            if (!store_writer_add(&w, &r)) {
                printf("  setup: reading %u did not fit an empty page\n", i);
                abort();
            }
        }
    }
    if (!store_writer_empty(&w)) {
        uint8_t page[STORE_PAGE_BYTES];
        if (store_writer_seal(&w, page, false)) {
            sim_commit(ring, page);
        }
    }
}

/** Every reading of @p session, in the ring's own page order. */
static size_t read_back(const sim_ring_t *ring, uint32_t session, int64_t start_ms,
                        store_reading_t *out, size_t cap)
{
    size_t n = 0;
    store_page_t page;
    for (uint32_t s = 0; s < SIM_SLOTS; s++) {
        if (!ring->used[s]) {
            continue;
        }
        store_header_t head;
        if (!store_peek_page(ring->slot[s], &head) || head.session != session ||
            head.session_start_ms != start_ms) {
            continue;
        }
        if (!store_decode_page(ring->slot[s], &page)) {
            continue;
        }
        for (uint8_t i = 0; i < page.count && n < cap; i++) {
            out[n++] = page.readings[i];
        }
    }
    return n;
}

/** Tell a merge what @p session already holds, from page headers alone. */
static bool note_held(store_merge_t *merge, const sim_ring_t *ring, uint32_t session,
                      int64_t start_ms)
{
    for (uint32_t s = 0; s < SIM_SLOTS; s++) {
        if (!ring->used[s]) {
            continue;
        }
        store_header_t head;
        if (!store_peek_page(ring->slot[s], &head) || head.session != session ||
            head.session_start_ms != start_ms) {
            continue;
        }
        if (!store_merge_note_held(merge, head.first_index, head.count)) {
            return false;
        }
    }
    return true;
}

/**
 * Run one whole import of @p rows into @p session.
 *
 * Exactly the sequence `store.c` performs against flash: note what is held from
 * the headers, offer every row, seal what is open.
 */
static store_merge_stats_t import(sim_ring_t *ring, uint32_t session, int64_t start_ms,
                                  const store_reading_t *rows, size_t count)
{
    store_merge_t merge;
    store_merge_begin(&merge, session, start_ms, 1000, 0);
    if (!note_held(&merge, ring, session, start_ms)) {
        printf("  setup: the held set overflowed\n");
        abort();
    }
    const store_sink_t sink = sim_sink(ring);
    for (size_t i = 0; i < count; i++) {
        store_merge_add(&merge, &rows[i], &sink);
    }
    store_merge_finish(&merge, &sink);
    return merge.stats;
}

/** Byte-for-byte, including which slots hold pages at all. */
static bool same_ring(const sim_ring_t *a, const sim_ring_t *b)
{
    for (uint32_t s = 0; s < SIM_SLOTS; s++) {
        if (a->used[s] != b->used[s]) {
            return false;
        }
        if (a->used[s] && memcmp(a->slot[s], b->slot[s], STORE_PAGE_BYTES) != 0) {
            return false;
        }
    }
    return true;
}

/* --- the held set ---------------------------------------------------------- */

static void test_held_spans(void)
{
    printf("held index spans\n");
    store_held_t held;
    store_held_reset(&held);

    CHECK(store_held_add(&held, 1, 40), "first span refused");
    CHECK(store_held_add(&held, 41, 80), "abutting span refused");
    CHECK(held.count == 1, "abutting spans stayed apart: %u", held.count);
    CHECK(store_held_readings(&held) == 80, "80 readings became %u",
          store_held_readings(&held));

    CHECK(store_held_add(&held, 200, 240), "disjoint span refused");
    CHECK(held.count == 2, "disjoint span merged: %u", held.count);
    CHECK(store_held_contains(&held, 1), "1 not held");
    CHECK(store_held_contains(&held, 80), "80 not held");
    CHECK(!store_held_contains(&held, 81), "81 held when it is a hole");
    CHECK(!store_held_contains(&held, 199), "199 held when it is a hole");
    CHECK(store_held_contains(&held, 240), "240 not held");
    CHECK(!store_held_contains(&held, 241), "241 held past the end");

    /* A span that bridges the hole swallows both sides. */
    CHECK(store_held_add(&held, 81, 199), "bridging span refused");
    CHECK(held.count == 1, "bridge left %u spans", held.count);
    CHECK(store_held_readings(&held) == 240, "bridge gave %u readings",
          store_held_readings(&held));

    /* Out-of-order insertion still sorts. */
    store_held_reset(&held);
    store_held_add(&held, 500, 510);
    store_held_add(&held, 100, 110);
    store_held_add(&held, 300, 310);
    CHECK(held.count == 3, "three spans became %u", held.count);
    CHECK(held.spans[0].first == 100 && held.spans[1].first == 300 &&
              held.spans[2].first == 500,
          "spans are not sorted");
}

static void test_held_overflow_is_reported(void)
{
    printf("an over-fragmented session is refused, not guessed at\n");
    store_held_t held;
    store_held_reset(&held);
    for (uint32_t i = 0; i < STORE_MERGE_MAX_SPANS; i++) {
        CHECK(store_held_add(&held, i * 10 + 1, i * 10 + 2), "span %u refused early", i);
    }
    CHECK(!store_held_add(&held, 100000, 100001), "the set took one span too many");
    CHECK(held.overflowed, "overflow was not reported");
}

/* --- the round trip -------------------------------------------------------- */

static void test_reimport_changes_nothing(void)
{
    printf("exporting a session and importing it back changes nothing\n");
    static sim_ring_t ring, before;
    sim_reset(&ring);
    record(&ring, 1, 1700000000000LL, 1, 500);

    static store_reading_t rows[600];
    const size_t exported = read_back(&ring, 1, 1700000000000LL, rows, 600);
    CHECK(exported == 500, "exported %zu of 500 readings", exported);

    const uint32_t pages_before = ring.writes;
    before = ring;

    store_merge_stats_t s = import(&ring, 1, 1700000000000LL, rows, exported);
    CHECK(s.added == 0, "a re-import added %u readings", s.added);
    CHECK(s.duplicate == 500, "a re-import saw %u duplicates, not 500", s.duplicate);
    CHECK(s.pages == 0, "a re-import wrote %u pages", s.pages);
    CHECK(ring.writes == pages_before, "the ring grew by %u pages",
          ring.writes - pages_before);
    CHECK(same_ring(&ring, &before), "the ring changed");

    /* And again, because idempotent means the second time too. */
    s = import(&ring, 1, 1700000000000LL, rows, exported);
    CHECK(s.added == 0, "a second re-import added %u readings", s.added);
    CHECK(s.duplicate == 500, "a second re-import saw %u duplicates", s.duplicate);
    CHECK(same_ring(&ring, &before), "the ring changed on the second import");

    const size_t after = read_back(&ring, 1, 1700000000000LL, rows, 600);
    CHECK(after == 500, "the session holds %zu readings after two imports", after);
}

static void test_missing_readings_are_added(void)
{
    printf("a hole is filled, and what was already there is untouched\n");
    static sim_ring_t ring, before;
    sim_reset(&ring);
    /* Two runs with a hundred missing between them, as a dropout would leave. */
    record(&ring, 7, 1700000000000LL, 1, 100);
    record(&ring, 7, 1700000000000LL, 201, 300);

    before = ring;
    const uint32_t pages_before = ring.writes;

    /* The file holds the whole session, as an earlier export would have. */
    static store_reading_t rows[300];
    for (uint32_t i = 1; i <= 300; i++) {
        rows[i - 1] = made(i, i % 40 == 0);
    }

    const store_merge_stats_t s = import(&ring, 7, 1700000000000LL, rows, 300);
    CHECK(s.added == 100, "%u readings were added, not the 100 missing", s.added);
    CHECK(s.duplicate == 200, "%u duplicates, not the 200 already held", s.duplicate);
    CHECK(s.rejected == 0, "%u readings were rejected", s.rejected);
    CHECK(ring.overwrites == 0, "the import tried to overwrite %u times", ring.overwrites);

    /* Every page that existed before is still there, unchanged and in place. */
    for (uint32_t slot = 0; slot < SIM_SLOTS; slot++) {
        if (!before.used[slot]) {
            continue;
        }
        CHECK(ring.used[slot], "slot %u lost its page", slot);
        CHECK(memcmp(ring.slot[slot], before.slot[slot], STORE_PAGE_BYTES) == 0,
              "slot %u was rewritten", slot);
    }
    CHECK(ring.writes > pages_before, "the added readings wrote no pages");

    static store_reading_t after[400];
    const size_t n = read_back(&ring, 7, 1700000000000LL, after, 400);
    CHECK(n == 300, "the session holds %zu readings, not 300", n);

    /* Every index once, and every value the one it was recorded with. */
    static bool seen[301];
    memset(seen, 0, sizeof(seen));
    for (size_t i = 0; i < n; i++) {
        CHECK(after[i].index >= 1 && after[i].index <= 300, "index %u is out of range",
              after[i].index);
        if (after[i].index < 1 || after[i].index > 300) {
            continue;
        }
        CHECK(!seen[after[i].index], "index %u appears twice", after[i].index);
        seen[after[i].index] = true;
        const store_reading_t want = made(after[i].index, after[i].index % 40 == 0);
        for (int f = 0; f < STORE_FIELDS; f++) {
            CHECK(store_quantise(after[i].values[f], f) == store_quantise(want.values[f], f),
                  "index %u field %d came back different", after[i].index, f);
        }
        CHECK(after[i].settling == want.settling, "index %u lost its settling flag",
              after[i].index);
    }
    for (uint32_t i = 1; i <= 300; i++) {
        CHECK(seen[i], "index %u is missing after the merge", i);
    }
}

static void test_unseen_session_is_created_whole(void)
{
    printf("a session this controller has never seen arrives complete\n");
    static sim_ring_t ring;
    sim_reset(&ring);
    record(&ring, 1, 1700000000000LL, 1, 120); /* an unrelated session */

    static store_reading_t rows[400];
    for (uint32_t i = 1; i <= 400; i++) {
        rows[i - 1] = made(i, false);
    }
    const int64_t other_start = 1600000000000LL;
    const store_merge_stats_t s = import(&ring, 42, other_start, rows, 400);
    CHECK(s.added == 400, "%u of 400 readings were added", s.added);
    CHECK(s.duplicate == 0, "%u duplicates in a session never seen", s.duplicate);
    CHECK(s.no_room == false, "the import ran out of room");

    static store_reading_t back[500];
    const size_t n = read_back(&ring, 42, other_start, back, 500);
    CHECK(n == 400, "the new session holds %zu readings, not 400", n);

    /* The session that was already there is untouched by a neighbour arriving. */
    const size_t original = read_back(&ring, 1, 1700000000000LL, back, 500);
    CHECK(original == 120, "the existing session holds %zu readings, not 120", original);
}

static void test_a_full_ring_refuses_and_evicts_nothing(void)
{
    printf("an import into a full ring stops, and takes nothing with it\n");
    static sim_ring_t ring, before;
    sim_reset(&ring);
    /* Offer far more than the ring can take; `record` stops when it refuses. */
    record(&ring, 1, 1700000000000LL, 1, SIM_SLOTS * 60);
    CHECK(ring.head == SIM_SLOTS, "the ring is not full: head at %u", ring.head);

    /* However many readings that turned out to be is the number that has to
     * survive. Naming a figure here would only test this test's arithmetic. */
    static store_reading_t held[SIM_SLOTS * 46];
    const size_t held_before = read_back(&ring, 1, 1700000000000LL, held, SIM_SLOTS * 46);
    CHECK(held_before > 0, "the setup stored nothing");

    before = ring;
    const uint32_t writes_before = ring.writes;

    static store_reading_t rows[200];
    for (uint32_t i = 1; i <= 200; i++) {
        rows[i - 1] = made(i, false);
    }
    const store_merge_stats_t s = import(&ring, 99, 1500000000000LL, rows, 200);

    CHECK(s.no_room, "a full ring did not report that it was full");
    CHECK(s.pages == 0, "%u pages were written into a full ring", s.pages);
    CHECK(ring.writes == writes_before, "the ring took %u more pages",
          ring.writes - writes_before);
    CHECK(same_ring(&ring, &before), "a refused import changed the ring");

    /* And the session that filled it is all still readable. */
    const size_t n = read_back(&ring, 1, 1700000000000LL, held, SIM_SLOTS * 46);
    CHECK(n == held_before, "the ring holds %zu readings, not the %zu it held", n,
          held_before);
}

static void test_a_partly_full_ring_stops_where_it_runs_out(void)
{
    printf("an import that outgrows the ring stops without evicting\n");
    static sim_ring_t ring, before;
    sim_reset(&ring);
    /* Fill all but four slots, by capping what the setup may use, then lift the
     * cap so the import meets a genuinely nearly-full ring. */
    ring.limit = SIM_SLOTS - 4;
    record(&ring, 1, 1700000000000LL, 1, SIM_SLOTS * 60);
    ring.limit = SIM_SLOTS;
    const uint32_t free_slots = SIM_SLOTS - ring.head;
    CHECK(free_slots == 4, "%u free slots is not the four the setup wanted", free_slots);

    before = ring;

    static store_reading_t rows[5000];
    for (uint32_t i = 1; i <= 5000; i++) {
        rows[i - 1] = made(i, false);
    }
    const store_merge_stats_t s = import(&ring, 55, 1400000000000LL, rows, 5000);

    CHECK(s.no_room, "the import did not notice it had run out");
    CHECK(s.added < 5000, "it claims to have added everything into a nearly-full ring");
    CHECK(ring.head == SIM_SLOTS, "it stopped before using the free slots");

    /* Every row offered is in exactly one of the three counts. A row that is
     * neither stored nor counted appears in nothing the sender is ever shown,
     * and a short `added` with a cheerful message is how readings go missing
     * without anyone noticing - which is the whole failure this path exists to
     * prevent. The rows after the ring filled used to be counted nowhere. */
    CHECK(s.added + s.duplicate + s.rejected == 5000,
          "%u added + %u already here + %u not placed = %u, and 5000 rows were offered",
          s.added, s.duplicate, s.rejected, s.added + s.duplicate + s.rejected);
    CHECK(s.rejected > 0, "a ring that ran out reported not one unplaced row");

    /* Nothing that was there before moved or changed. */
    for (uint32_t slot = 0; slot < SIM_SLOTS; slot++) {
        if (!before.used[slot]) {
            continue;
        }
        CHECK(ring.used[slot] &&
                  memcmp(ring.slot[slot], before.slot[slot], STORE_PAGE_BYTES) == 0,
              "slot %u changed when the import ran out of room", slot);
    }
}

static void test_repeated_rows_are_duplicates(void)
{
    printf("a file that lists a reading twice stores it once\n");
    static sim_ring_t ring;
    sim_reset(&ring);

    static store_reading_t rows[200];
    for (uint32_t i = 0; i < 100; i++) {
        rows[i] = made(i + 1, false);
        rows[100 + i] = made(i + 1, false); /* the same hundred again */
    }
    const store_merge_stats_t s = import(&ring, 3, 1300000000000LL, rows, 200);
    CHECK(s.added == 100, "%u readings were added for 100 distinct ones", s.added);
    CHECK(s.duplicate == 100, "%u duplicates for the repeated hundred", s.duplicate);

    static store_reading_t back[200];
    const size_t n = read_back(&ring, 3, 1300000000000LL, back, 200);
    CHECK(n == 100, "the session holds %zu readings, not 100", n);
}

static void test_rows_out_of_order_still_merge(void)
{
    printf("rows out of index order still merge, once each\n");
    static sim_ring_t ring;
    sim_reset(&ring);

    /* Reversed, which is the worst case for page density and the easiest way to
     * be sure nothing depends on the order a file happens to be in. */
    static store_reading_t rows[60];
    for (uint32_t i = 0; i < 60; i++) {
        rows[i] = made(60 - i, false);
    }
    const store_merge_stats_t s = import(&ring, 4, 1200000000000LL, rows, 60);
    CHECK(s.added == 60, "%u of 60 reversed readings were added", s.added);
    CHECK(s.rejected == 0, "%u reversed readings were rejected", s.rejected);

    static store_reading_t back[80];
    const size_t n = read_back(&ring, 4, 1200000000000LL, back, 80);
    CHECK(n == 60, "the session holds %zu readings, not 60", n);

    static bool seen[61];
    memset(seen, 0, sizeof(seen));
    for (size_t i = 0; i < n; i++) {
        CHECK(back[i].index >= 1 && back[i].index <= 60, "index %u out of range",
              back[i].index);
        if (back[i].index >= 1 && back[i].index <= 60) {
            CHECK(!seen[back[i].index], "index %u stored twice", back[i].index);
            seen[back[i].index] = true;
        }
    }
    for (uint32_t i = 1; i <= 60; i++) {
        CHECK(seen[i], "index %u missing", i);
    }
}

static void test_malformed_rows_are_rejected(void)
{
    printf("a row with no index is refused, and the rest still merge\n");
    static sim_ring_t ring;
    sim_reset(&ring);

    static store_reading_t rows[11];
    for (uint32_t i = 0; i < 10; i++) {
        rows[i] = made(i + 1, false);
    }
    rows[10] = made(1, false);
    rows[10].index = 0; /* what a truncated or corrupt row decodes to */

    const store_merge_stats_t s = import(&ring, 5, 1100000000000LL, rows, 11);
    CHECK(s.added == 10, "%u of the 10 good readings were added", s.added);
    CHECK(s.rejected == 1, "%u rows were rejected, not the one bad one", s.rejected);
}

/* --- the digest ------------------------------------------------------------ */

static void test_digest_is_order_independent(void)
{
    printf("the backup digest does not depend on the order readings are seen\n");
    store_reading_t rows[50];
    for (uint32_t i = 0; i < 50; i++) {
        rows[i] = made(i + 1, i % 7 == 0);
    }
    uint32_t forwards = 0;
    for (uint32_t i = 0; i < 50; i++) {
        forwards = store_digest_fold(forwards, store_digest_reading(&rows[i]));
    }
    uint32_t backwards = 0;
    for (uint32_t i = 50; i > 0; i--) {
        backwards = store_digest_fold(backwards, store_digest_reading(&rows[i - 1]));
    }
    CHECK(forwards == backwards, "order changed the digest: %08x vs %08x", forwards,
          backwards);

    /* One reading short, or one value off by a quantum, has to show. */
    uint32_t short_one = 0;
    for (uint32_t i = 0; i < 49; i++) {
        short_one = store_digest_fold(short_one, store_digest_reading(&rows[i]));
    }
    CHECK(short_one != forwards, "a missing reading left the digest alone");

    store_reading_t nudged = rows[10];
    nudged.values[0] += 0.01f;
    uint32_t changed = 0;
    for (uint32_t i = 0; i < 50; i++) {
        changed = store_digest_fold(changed,
                                    store_digest_reading(i == 10 ? &nudged : &rows[i]));
    }
    CHECK(changed != forwards, "a value one quantum out left the digest alone");
}

/**
 * The digest of a reading recorded with no clock, as a shared expected value.
 *
 * A relative page holds the packed `(run, ms)` pair in the same field an
 * absolute one holds an instant, so this digest is taken over the packed value.
 * The phone has to reach the same number from the CSV row - it is what the
 * controller's erase checks a backup against - and `SessionArchiveTest` carries
 * this exact reading and this exact digest for that reason. See AGENTS.md,
 * "The two digests have to agree".
 *
 * The reading is the first of run 1202, real output the board produced on
 * 2026-08-03, the same row the app's `board` test resources hold.
 */
static void test_a_clockless_reading_digests_over_its_packed_clock(void)
{
    printf("a reading with no clock digests over the packed (run, ms) pair\n");
    static const float VALUES[STORE_FIELDS] = {
        2.50f, 10.83f, 17.45f, 18.77f, 0.00f, 11.22f, 19.45f, 21.04f, 21.28f, 1.630f,
    };
    store_reading_t r = {
        .index = 1,
        .epoch_ms = store_rel_pack(1202u, 2081u),
        .settling = true,
    };
    for (int f = 0; f < STORE_FIELDS; f++) {
        r.values[f] = VALUES[f];
    }
    CHECK(store_digest_reading(&r) == 0xd5740e7bu,
          "the clockless digest moved: %08x - if this is deliberate, move "
          "SessionArchiveTest's copy with it",
          store_digest_reading(&r));

    /* And it is the run that separates them: the same millisecond count in a
     * different run of the board is a different reading, which is the whole
     * reason the run rides in the top half of the field. */
    store_reading_t other = r;
    other.epoch_ms = store_rel_pack(1196u, 2081u);
    CHECK(store_digest_reading(&r) != store_digest_reading(&other),
          "two runs digested the same");
}

static void test_digest_ignores_sub_second_time(void)
{
    printf("the digest survives the export dropping milliseconds\n");
    store_reading_t a = made(9, false);
    store_reading_t b = a;
    a.epoch_ms = 1700000000123LL;
    b.epoch_ms = 1700000000987LL; /* the same second, as the CSV would say */
    CHECK(store_digest_reading(&a) == store_digest_reading(&b),
          "two instants in one second digested differently");

    b.epoch_ms = 1700000001000LL;
    CHECK(store_digest_reading(&a) != store_digest_reading(&b),
          "a different second digested the same");
}

/**
 * Sending an archive back must not cost a ring page per session in it.
 *
 * The merge reads the held set out of page headers, so a reading still in the
 * page the sensor has open is invisible to it - which is why the open page is
 * sealed before an import begins. That argument is about **one** session, the
 * one the open page belongs to, and it was being applied to every import: an
 * archive is one import per session in it, so a sixty-four session file sent
 * back sealed sixty-four partial pages and consumed sixty-four ring slots for
 * readings that were already safely held. Re-sending what the board already has
 * is meant to cost it nothing.
 */
static void test_only_the_session_being_imported_needs_its_page_sealed(void)
{
    const char *const live = "SPS30-20260804_043849.csv";
    const char *const other = "SPS30-20260603_101500.csv";

    CHECK(store_merge_needs_seal(true, live, live),
          "the page being filled is this session's and is not being sealed - the merge cannot "
          "see those readings and will write a second copy of them");

    CHECK(!store_merge_needs_seal(true, live, other),
          "a page of a different session is being sealed for an import that cannot learn "
          "anything from it; that is a ring page per session in an archive");

    /* An archive of sixty-four sessions, exactly one of which is the one being
     * recorded. One seal, not sixty-four. */
    int seals = 0;
    for (int i = 0; i < 64; i++) {
        char name[32];
        snprintf(name, sizeof(name), "SPS30-2026060%d_1015%02d.csv", i % 10, i);
        if (store_merge_needs_seal(true, live, name)) {
            seals++;
        }
    }
    CHECK(seals == 0, "%d of 64 sessions that are not the live one asked for a seal", seals);
    CHECK(store_merge_needs_seal(true, live, live), "and the live one still asks for one");

    /* Nothing open is nothing to seal, whatever is arriving. */
    CHECK(!store_merge_needs_seal(false, live, live),
          "a closed writer is being sealed, which writes nothing and says it did");
    CHECK(!store_merge_needs_seal(true, NULL, live), "a nameless writer asked for a seal");
    CHECK(!store_merge_needs_seal(true, live, NULL), "a nameless import asked for a seal");
    CHECK(!store_merge_needs_seal(true, "", live), "an empty writer name asked for a seal");
    CHECK(!store_merge_needs_seal(true, live, ""), "an empty import name asked for a seal");
}

int main(void)
{
    printf("store merge tests\n\n");
    test_held_spans();
    test_held_overflow_is_reported();
    test_reimport_changes_nothing();
    test_missing_readings_are_added();
    test_unseen_session_is_created_whole();
    test_a_full_ring_refuses_and_evicts_nothing();
    test_a_partly_full_ring_stops_where_it_runs_out();
    test_repeated_rows_are_duplicates();
    test_rows_out_of_order_still_merge();
    test_malformed_rows_are_rejected();
    test_digest_is_order_independent();
    test_a_clockless_reading_digests_over_its_packed_clock();
    test_digest_ignores_sub_second_time();
    test_only_the_session_being_imported_needs_its_page_sealed();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

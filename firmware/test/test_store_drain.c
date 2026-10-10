/**
 * @file test_store_drain.c
 * @brief The one path in this firmware that deletes the captain's data on purpose.
 *
 * `store_drain.c` backs the board up and clears it, block by block: copy a
 * block, have the device prove it landed, free that block, move on. The happy
 * path is the least interesting thing about it. What has to be *checked* is
 * every way it can be interrupted, because the board it will run against is
 * recording an air-quality event its owner will not lose, and there is no second
 * chance to discover that a block was freed before its copy was safe.
 *
 * So the failure paths are covered harder than the happy one, and each of them
 * asserts the same invariant from a different direction: **nothing leaves flash
 * that has not been proved to be somewhere else first.**
 *
 *   - a confirmation whose digest does not match
 *   - a confirmation whose row count does not match
 *   - an interruption between the copy and the confirmation
 *   - an interruption between the confirmation and the erase
 *   - a cancel part way through
 *   - a board reboot part way through, resumed from flash alone
 *   - flash changing underneath a block between being sent and being confirmed
 *   - the wrap boundary, where oldest and newest share a sector
 *   - preserved alert segments, which live in another region entirely
 *   - the final block, kept rather than freed while the board is recording
 *   - the end state: genuinely blank, not merely emptied
 *
 * Build and run with `make -C firmware/test`. No ESP-IDF, no board, no flash.
 */

#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "store_alert.h"
#include "store_codec.h"
#include "store_drain.h"

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

/* --- a partition that behaves like one ------------------------------------- */

/**
 * Deliberately tiny: eight blocks of ring, one of segment headers, two of
 * preserved samples.
 *
 * The shipped partition is 560 ring blocks and 64 of preservation, and filling
 * one on the board is two days of continuous 1 Hz. Nothing in `store_drain.c`
 * knows the difference - every bound it uses comes out of ::store_drain_dev_t -
 * so a small one wraps in a few dozen pages and proves the same properties.
 */
#define SIM_RING_BLOCKS 8
#define SIM_HEADER_BLOCKS 1
#define SIM_BODY_BLOCKS 2

#define SIM_RING_BYTES ((size_t)SIM_RING_BLOCKS * STORE_DRAIN_BLOCK_BYTES)
#define SIM_PRESERVE_HEADER_BYTES ((size_t)SIM_HEADER_BLOCKS * STORE_DRAIN_BLOCK_BYTES)
#define SIM_PRESERVE_BYTES \
    ((size_t)(SIM_HEADER_BLOCKS + SIM_BODY_BLOCKS) * STORE_DRAIN_BLOCK_BYTES)
#define SIM_BYTES (SIM_RING_BYTES + SIM_PRESERVE_BYTES)

#define SIM_SLOTS (SIM_RING_BLOCKS * STORE_DRAIN_BLOCK_PAGES)

typedef struct {
    uint8_t bytes[SIM_BYTES];
    uint32_t erases;
    bool refuse_erase;
} sim_t;

static sim_t sim;

static bool sim_read(void *ctx, size_t offset, uint8_t *out, size_t len)
{
    (void)ctx;
    if (offset + len > SIM_BYTES) {
        return false;
    }
    memcpy(out, sim.bytes + offset, len);
    return true;
}

static bool sim_erase(void *ctx, size_t offset, size_t len)
{
    (void)ctx;
    if (sim.refuse_erase) {
        return false;
    }
    if (offset + len > SIM_BYTES || offset % STORE_DRAIN_BLOCK_BYTES != 0 ||
        len != STORE_DRAIN_BLOCK_BYTES) {
        return false;
    }
    /* Erased NOR reads as 0xFF, not as zero. A test that zeroed instead would
     * make a blank block decode as a plausible page in half of what follows. */
    memset(sim.bytes + offset, 0xFF, len);
    sim.erases++;
    return true;
}

static const store_drain_dev_t SIM_DEV = {
    .ctx = NULL,
    .read = sim_read,
    .erase = sim_erase,
    .ring_bytes = SIM_RING_BYTES,
    .preserve_base = SIM_RING_BYTES,
    .preserve_header_bytes = SIM_PRESERVE_HEADER_BYTES,
    .preserve_bytes = SIM_PRESERVE_BYTES,
};

static void sim_reset(void)
{
    memset(sim.bytes, 0xFF, sizeof(sim.bytes));
    sim.erases = 0;
    sim.refuse_erase = false;
}

/** True if every byte of a block is erased. "Clean", not merely "emptied". */
static bool block_is_blank(size_t offset)
{
    for (size_t i = 0; i < STORE_DRAIN_BLOCK_BYTES; i++) {
        if (sim.bytes[offset + i] != 0xFF) {
            return false;
        }
    }
    return true;
}

/* --- building pages -------------------------------------------------------- */

static const int64_t BASE_MS = 1754179200000LL; /* 2026-08-03T00:00:00Z */

/**
 * Write one page of @p count readings into @p slot.
 *
 * The values are derived from the index so that every reading in the simulated
 * board is distinct, which is what lets the completeness check below be a real
 * check rather than a count.
 */
static void write_page(size_t offset, uint32_t page_seq, uint32_t session,
                       int64_t session_start_ms, uint32_t first_index, uint8_t count,
                       bool relative)
{
    store_writer_t w;
    store_writer_init(&w, page_seq, session, session_start_ms, 1000, 0, relative);
    for (uint8_t i = 0; i < count; i++) {
        store_reading_t r;
        memset(&r, 0, sizeof(r));
        r.index = first_index + i;
        r.epoch_ms = session_start_ms + (int64_t)(r.index - 1) * 1000;
        for (int f = 0; f < STORE_FIELDS; f++) {
            r.values[f] = (float)((r.index % 97) + f) * 0.11f;
        }
        if (!store_writer_add(&w, &r)) {
            break;
        }
    }
    uint8_t raw[STORE_PAGE_BYTES];
    if (store_writer_seal(&w, raw, true)) {
        memcpy(sim.bytes + offset, raw, sizeof(raw));
    }
}

static void write_ring_page(uint32_t slot, uint32_t page_seq, uint32_t session,
                            int64_t session_start_ms, uint32_t first_index, uint8_t count)
{
    write_page((size_t)slot * STORE_PAGE_BYTES, page_seq, session, session_start_ms,
               first_index, count, false);
}

/** One page of preserved samples, into the body region. */
static void write_body_page(uint32_t body_slot, uint32_t page_seq, uint32_t session,
                            int64_t session_start_ms, uint32_t first_index, uint8_t count)
{
    write_page(SIM_RING_BYTES + SIM_PRESERVE_HEADER_BYTES +
                   (size_t)body_slot * STORE_PAGE_BYTES,
               page_seq, session, session_start_ms, first_index, count, false);
}

/** One preserved-segment summary, into the header region. */
static void write_segment(uint32_t record, uint32_t seq, uint32_t first_index, uint32_t points)
{
    store_alert_header_t h;
    memset(&h, 0, sizeof(h));
    h.session = 1;
    h.session_start_ms = BASE_MS;
    h.first_index = first_index;
    h.last_index = first_index + points - 1;
    h.utc_offset_min = 0;
    h.start_ms = BASE_MS + (int64_t)first_index * 1000;
    h.stop_ms = h.start_ms + (int64_t)points * 1000;
    h.first = 21.5f;
    h.last = 19.25f;
    h.midpoint = 23.125f;
    h.mean = 22.0f;
    h.variance = 1.5f;
    h.minimum = 19.0f;
    h.maximum = 25.75f;
    h.points = points;
    h.field = 1;
    h.threshold = 20.0f;
    h.seq = seq;
    h.wrap = 3;
    h.body_seq = 1;
    h.body_pages = 1;
    uint8_t raw[STORE_ALERT_HEADER_BYTES];
    store_alert_header_encode(&h, raw);
    memcpy(sim.bytes + SIM_RING_BYTES + (size_t)record * STORE_ALERT_HEADER_BYTES, raw,
           sizeof(raw));
}

/* --- a device that stores what it is sent ---------------------------------- */

/**
 * The far end of the wire, in as much detail as the contract needs.
 *
 * It keeps every row it is handed and computes the claim from *its own copy*,
 * never from what the controller said - which is the whole point. A test that
 * echoed the controller's digest back would prove that a number equals itself.
 */
#define DEVICE_MAX_ROWS 4096

typedef struct {
    char session[DEVICE_MAX_ROWS][32];
    uint32_t index[DEVICE_MAX_ROWS];
    uint32_t digest[DEVICE_MAX_ROWS];
    uint32_t count;
    /** Rows accepted for the block in flight, so a half-copy can be modelled. */
    uint32_t in_block;
    uint32_t block_digest;
    /** Stop accepting after this many rows of a block; 0 for no limit. */
    uint32_t accept_at_most;
} device_t;

static device_t device;

static void device_reset(void)
{
    memset(&device, 0, sizeof(device));
}

static void device_block_begin(void)
{
    device.in_block = 0;
    device.block_digest = 0;
}

static bool device_take_reading(void *ctx, const char *session, uint32_t page_seq,
                                const store_reading_t *reading, bool relative)
{
    (void)ctx;
    (void)page_seq;
    (void)relative;
    if (device.accept_at_most != 0 && device.in_block >= device.accept_at_most) {
        return false;
    }
    const uint32_t one = store_drain_digest_reading(session, reading);
    if (device.count < DEVICE_MAX_ROWS) {
        snprintf(device.session[device.count], sizeof(device.session[0]), "%s", session);
        device.index[device.count] = reading->index;
        device.digest[device.count] = one;
        device.count++;
    }
    device.block_digest = store_drain_digest_fold(device.block_digest, one);
    device.in_block++;
    return true;
}

static bool device_take_segment(void *ctx, const store_alert_header_t *header)
{
    (void)ctx;
    if (device.accept_at_most != 0 && device.in_block >= device.accept_at_most) {
        return false;
    }
    const uint32_t one = store_drain_digest_segment(header);
    if (device.count < DEVICE_MAX_ROWS) {
        snprintf(device.session[device.count], sizeof(device.session[0]), "segment");
        device.index[device.count] = header->seq;
        device.digest[device.count] = one;
        device.count++;
    }
    device.block_digest = store_drain_digest_fold(device.block_digest, one);
    device.in_block++;
    return true;
}

/** Copy one offered block onto the device, exactly as the wire would. */
static void device_copy(store_drain_t *d, const store_drain_block_t *block)
{
    static store_page_t scratch;
    device_block_begin();
    if (block->kind == STORE_DRAIN_SEGMENTS) {
        (void)store_drain_walk_segments(d, block, 0, device_take_segment, NULL);
    } else {
        (void)store_drain_walk_readings(d, block, 0, &scratch, device_take_reading, NULL);
    }
    /* What the controller records as sent is what its own walk produced, which
     * is the same walk. The device's claim is computed independently. */
    uint32_t rows = 0;
    uint32_t digest = 0;
    store_drain_measure(d, block, &scratch, &rows, &digest);
    store_drain_sent(d, rows, digest);
}

/* --- the drain, driven ----------------------------------------------------- */

/**
 * How many pages the ring holds, counted the way the drain counts them.
 *
 * Used to assert what is left rather than to decide anything, so it reads flash
 * directly instead of asking the code under test.
 */
static uint32_t ring_pages_present(void)
{
    uint32_t n = 0;
    for (uint32_t slot = 0; slot < SIM_SLOTS; slot++) {
        store_header_t peek;
        if (store_peek_page(sim.bytes + (size_t)slot * STORE_PAGE_BYTES, &peek)) {
            n++;
        }
    }
    return n;
}

static uint32_t preserve_records_present(void)
{
    uint32_t n = 0;
    for (uint32_t i = 0; i < STORE_DRAIN_BLOCK_HEADERS * SIM_HEADER_BLOCKS; i++) {
        store_alert_header_t h;
        if (store_alert_header_decode(
                sim.bytes + SIM_RING_BYTES + (size_t)i * STORE_ALERT_HEADER_BYTES, &h)) {
            n++;
        }
    }
    for (uint32_t i = 0; i < STORE_DRAIN_BLOCK_PAGES * SIM_BODY_BLOCKS; i++) {
        store_header_t peek;
        if (store_peek_page(sim.bytes + SIM_RING_BYTES + SIM_PRESERVE_HEADER_BYTES +
                                (size_t)i * STORE_PAGE_BYTES,
                            &peek)) {
            n++;
        }
    }
    return n;
}

/**
 * A board that has been recording: every ring slot full, the head mid-block.
 *
 * The head deliberately sits at slot 43, which is inside block 5 rather than on
 * its boundary. A full ring's oldest page and its newest page are then in the
 * *same* block, which is the case that decides whether the ordering rule is
 * right and the case a boundary-aligned fixture would never reach.
 */
#define FULL_HEAD_SLOT 43u

static void build_full_ring(void)
{
    sim_reset();
    /* page_seq ascends in ring order from the head, so the page at the head is
     * the oldest and the one before it is the newest - which is what a ring that
     * has wrapped actually looks like. */
    for (uint32_t i = 0; i < SIM_SLOTS; i++) {
        const uint32_t slot = (FULL_HEAD_SLOT + i) % SIM_SLOTS;
        const uint32_t seq = 1000 + i;
        write_ring_page(slot, seq, 1, BASE_MS, 1 + i * 40, 40);
    }
}

/** Run a whole drain to completion, confirming honestly every time. */
typedef struct {
    uint32_t blocks;
    uint32_t freed;
    uint32_t kept;
    uint32_t refused;
} run_t;

static run_t drain_to_completion(store_drain_t *d, uint32_t head_slot)
{
    static store_page_t scratch;
    run_t out;
    memset(&out, 0, sizeof(out));
    for (uint32_t guard = 0; guard < 256; guard++) {
        store_drain_block_t block;
        if (!store_drain_choose(d, SIM_SLOTS, head_slot, &block)) {
            break;
        }
        out.blocks++;
        device_copy(d, &block);
        const store_drain_verdict_t v =
            store_drain_confirm(d, block.id, device.in_block, device.block_digest, &scratch);
        if (v == STORE_DRAIN_FREED) {
            out.freed++;
        } else if (v == STORE_DRAIN_KEPT) {
            out.kept++;
            break;
        } else {
            out.refused++;
            break;
        }
    }
    return out;
}

/* --- 1. the whole thing, end to end ---------------------------------------- */

static void test_full_drain(void)
{
    printf("a full ring drains block by block, and what is left is the block being written\n");
    build_full_ring();
    device_reset();
    const uint32_t before = ring_pages_present();
    CHECK(before == SIM_SLOTS, "the fixture should fill the ring, got %" PRIu32, before);

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    CHECK(store_drain_begin(&d, &SIM_DEV, 7), "a drain should open on an idle board");
    CHECK(!store_drain_begin(&d, &SIM_DEV, 8), "a second drain should be refused");

    const run_t run = drain_to_completion(&d, FULL_HEAD_SLOT);
    CHECK(run.blocks == SIM_RING_BLOCKS, "every block should be offered once, got %" PRIu32,
          run.blocks);
    CHECK(run.freed == SIM_RING_BLOCKS - 1, "all but one block should be freed, got %" PRIu32,
          run.freed);
    CHECK(run.kept == 1, "exactly one block should be kept, got %" PRIu32, run.kept);
    CHECK(run.refused == 0, "nothing should be refused on an honest run");
    CHECK(d.finished, "the drain should say it has finished");

    /* Every reading that existed at the start is on the device, exactly once. */
    CHECK(device.count == SIM_SLOTS * 40, "every reading should have been copied, got %" PRIu32,
          device.count);
    uint32_t seen[SIM_SLOTS * 40 + 8];
    memset(seen, 0, sizeof(seen));
    bool duplicate = false;
    for (uint32_t i = 0; i < device.count; i++) {
        const uint32_t idx = device.index[i];
        if (idx < sizeof(seen) / sizeof(seen[0])) {
            if (seen[idx] != 0) {
                duplicate = true;
            }
            seen[idx]++;
        }
    }
    CHECK(!duplicate, "no reading should have been copied twice");
    bool complete = true;
    for (uint32_t idx = 1; idx <= SIM_SLOTS * 40; idx++) {
        if (seen[idx] != 1) {
            complete = false;
        }
    }
    CHECK(complete, "every index from 1 to %u should be on the device", SIM_SLOTS * 40);

    /* And the board is clean: one block of pages left, everything else blank. */
    const uint32_t left = ring_pages_present();
    CHECK(left == STORE_DRAIN_BLOCK_PAGES, "only the head's block should remain, got %" PRIu32,
          left);
    const uint32_t head_block = FULL_HEAD_SLOT / STORE_DRAIN_BLOCK_PAGES;
    for (uint32_t b = 0; b < SIM_RING_BLOCKS; b++) {
        const bool blank = block_is_blank((size_t)b * STORE_DRAIN_BLOCK_BYTES);
        CHECK(blank == (b != head_block),
              "block %" PRIu32 " should be %s", b, b == head_block ? "kept" : "erased");
    }
    store_drain_end(&d);
}

/* --- 2. the oldest block goes first, across the wrap ----------------------- */

static void test_oldest_first_across_the_wrap(void)
{
    printf("the oldest block is chosen first, and the head's block is chosen last\n");
    build_full_ring();
    device_reset();

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);

    const uint32_t head_block = FULL_HEAD_SLOT / STORE_DRAIN_BLOCK_PAGES;
    static store_page_t scratch;
    uint32_t previous_age = 0;
    for (uint32_t n = 0; n < SIM_RING_BLOCKS; n++) {
        store_drain_block_t block;
        CHECK(store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block),
              "a block should be offered on turn %" PRIu32, n);
        const uint32_t b = block.slot / STORE_DRAIN_BLOCK_PAGES;
        if (n < SIM_RING_BLOCKS - 1) {
            CHECK(b != head_block, "the head's block must not be offered before the end");
            const uint32_t age =
                (b + SIM_RING_BLOCKS - ((head_block + 1) % SIM_RING_BLOCKS)) % SIM_RING_BLOCKS;
            CHECK(n == 0 || age > previous_age, "blocks should be offered oldest first");
            previous_age = age;
            CHECK(!block.final, "only the last block is the final one");
        } else {
            CHECK(b == head_block, "the head's block should be offered last");
            CHECK(block.final, "the head's block should be marked final");
        }
        device_copy(&d, &block);
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    }
    store_drain_end(&d);
}

/* --- 3. a confirmation that does not match frees nothing ------------------- */

static void test_a_wrong_digest_frees_nothing(void)
{
    printf("a confirmation with the wrong digest frees nothing and ends the drain\n");
    build_full_ring();
    device_reset();
    static store_page_t scratch;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    store_drain_block_t block;
    CHECK(store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block), "a block should be offered");
    device_copy(&d, &block);

    const uint32_t before = ring_pages_present();
    const store_drain_verdict_t v =
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest ^ 1u, &scratch);
    CHECK(v == STORE_DRAIN_REFUSED, "a wrong digest should be refused");
    CHECK(sim.erases == 0, "a refused confirmation must not erase");
    CHECK(ring_pages_present() == before, "a refused confirmation must not lose a page");
    CHECK(d.blocks_refused == 1, "the refusal should be counted");

    /* One byte out in the row count is the same answer. */
    const store_drain_verdict_t v2 =
        store_drain_confirm(&d, block.id, device.in_block - 1, device.block_digest, &scratch);
    CHECK(v2 == STORE_DRAIN_REFUSED, "a short row count should be refused");
    CHECK(sim.erases == 0, "still nothing erased");

    /* And a confirmation for a block that was never offered. */
    const store_drain_verdict_t v3 =
        store_drain_confirm(&d, block.id + 99, device.in_block, device.block_digest, &scratch);
    CHECK(v3 == STORE_DRAIN_NOT_OFFERED, "an unknown block id should be refused");
    CHECK(sim.erases == 0, "still nothing erased");
    store_drain_end(&d);
}

/* --- 4. interrupted between the copy and the confirmation ------------------ */

static void test_interrupted_before_confirming(void)
{
    printf("interrupted between the copy and the confirmation: the block is still there\n");
    build_full_ring();
    device_reset();
    static store_page_t scratch;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    store_drain_block_t block;
    store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block);
    device_copy(&d, &block);

    /* The link drops, or the app is killed. Nothing has been confirmed. */
    store_drain_end(&d);
    CHECK(sim.erases == 0, "an abandoned drain must not have erased anything");
    CHECK(ring_pages_present() == SIM_SLOTS, "and must not have lost a page");

    /* The device comes back. The same block is offered again, because the
     * question "what is oldest" is asked of flash and flash has not changed. */
    store_drain_t again;
    memset(&again, 0, sizeof(again));
    store_drain_begin(&again, &SIM_DEV, 1);
    store_drain_block_t second;
    CHECK(store_drain_choose(&again, SIM_SLOTS, FULL_HEAD_SLOT, &second),
          "a resumed drain should offer a block");
    CHECK(second.offset == block.offset, "the same block should be offered again");
    CHECK(second.readings == block.readings, "and should hold the same readings");

    /* A confirmation carrying the *previous* drain's block id is not accepted:
     * ids do not survive a drain, and one that did would be a way to confirm a
     * block nobody sent. */
    const store_drain_verdict_t stale =
        store_drain_confirm(&again, block.id, device.in_block, device.block_digest, &scratch);
    CHECK(stale == STORE_DRAIN_NOT_OFFERED || sim.erases == 0,
          "a stale block id must not free anything");
    store_drain_end(&again);
}

/* --- 5. interrupted between the confirmation and the erase ----------------- */

static void test_interrupted_before_freeing(void)
{
    printf("interrupted between the confirmation and the erase: nothing is half-freed\n");
    build_full_ring();
    device_reset();
    static store_page_t scratch;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    store_drain_block_t block;
    store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block);
    device_copy(&d, &block);

    sim.refuse_erase = true;
    const store_drain_verdict_t v =
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    CHECK(v == STORE_DRAIN_ERASE_FAILED, "a refused erase should say so");
    CHECK(ring_pages_present() == SIM_SLOTS, "and should not have lost a page");
    CHECK(d.blocks_freed == 0, "and should not be counted as freed");
    sim.refuse_erase = false;

    /* The board reboots here. Everything about the drain is gone; flash is the
     * only record, and it says the block is still owed. */
    store_drain_t rebooted;
    memset(&rebooted, 0, sizeof(rebooted));
    store_drain_begin(&rebooted, &SIM_DEV, 1);
    store_drain_block_t after;
    CHECK(store_drain_choose(&rebooted, SIM_SLOTS, FULL_HEAD_SLOT, &after),
          "a rebooted board should offer a block");
    CHECK(after.offset == block.offset, "and it should be the same one, not the next one");
    store_drain_end(&rebooted);
}

/* --- 6. flash changing underneath a block --------------------------------- */

static void test_flash_changing_under_a_block(void)
{
    printf("a block that changed between being sent and being confirmed is refused\n");
    build_full_ring();
    device_reset();
    static store_page_t scratch;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    store_drain_block_t block;
    store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block);
    device_copy(&d, &block);

    /* Something wrote a page into the block after it was sent. The device's
     * claim is perfectly honest - it holds what it was sent - but what is on
     * flash is no longer that, so freeing it would lose the difference. */
    write_page(block.offset, 9000, 2, BASE_MS + 500000, 5000, 10, false);
    const store_drain_verdict_t v =
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    CHECK(v == STORE_DRAIN_REFUSED, "a block that changed underneath should be refused");
    CHECK(sim.erases == 0, "and must not be erased");
    store_drain_end(&d);
}

/* --- 7. cancelling part way through --------------------------------------- */

static void test_cancel_part_way_through(void)
{
    printf("cancelling part way through keeps what was freed freed and the rest intact\n");
    build_full_ring();
    device_reset();
    static store_page_t scratch;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    for (uint32_t n = 0; n < 3; n++) {
        store_drain_block_t block;
        store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block);
        device_copy(&d, &block);
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    }
    const uint32_t copied = device.count;
    store_drain_end(&d);

    CHECK(sim.erases == 3, "three blocks should have been freed, got %" PRIu32, sim.erases);
    CHECK(ring_pages_present() == SIM_SLOTS - 3 * STORE_DRAIN_BLOCK_PAGES,
          "and only those three should have gone");

    /* Resuming continues rather than starting over or redoing a deletion. */
    store_drain_t resumed;
    memset(&resumed, 0, sizeof(resumed));
    store_drain_begin(&resumed, &SIM_DEV, 1);
    const run_t rest = drain_to_completion(&resumed, FULL_HEAD_SLOT);
    CHECK(rest.blocks == SIM_RING_BLOCKS - 3, "the rest should be %u blocks, got %" PRIu32,
          SIM_RING_BLOCKS - 3, rest.blocks);
    CHECK(device.count == SIM_SLOTS * 40, "and every reading should be on the device by the end");
    CHECK(device.count > copied, "the resumed half should have copied something");
    store_drain_end(&resumed);
}

/* --- 8. preserved alert segments ------------------------------------------- */

/**
 * The part this feature could most easily have skipped in silence.
 *
 * Preserved data is addressed nothing like ring data - another region, its own
 * two rings, a 128-byte record format for the summaries - and the erase gate
 * never touches any of it by design. A "back up everything" that walked only the
 * ring would leave every preserved segment on the board and call it clear.
 */
static void test_preserved_segments_go_first_and_go_completely(void)
{
    printf("preserved alert data is drained before the ring, and all of it goes\n");
    build_full_ring();
    /* Two blocks of preserved samples and a block of summaries. */
    for (uint32_t i = 0; i < STORE_DRAIN_BLOCK_PAGES * SIM_BODY_BLOCKS; i++) {
        write_body_page(i, 500 + i, 1, BASE_MS, 100000 + i * 30, 30);
    }
    for (uint32_t i = 0; i < 5; i++) {
        write_segment(i, 10 + i, 100000 + i * 30, 30);
    }
    device_reset();

    CHECK(preserve_records_present() > 0, "the fixture should have put something to preserve");

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);

    static store_page_t scratch;
    uint32_t bodies = 0;
    uint32_t segment_blocks = 0;
    uint32_t ring_blocks = 0;
    uint32_t segment_rows = 0;
    bool order_ok = true;
    for (uint32_t guard = 0; guard < 256; guard++) {
        store_drain_block_t block;
        if (!store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block)) {
            break;
        }
        if (block.kind == STORE_DRAIN_BODY) {
            bodies++;
            if (ring_blocks > 0 || segment_blocks > 0) {
                order_ok = false;
            }
        } else if (block.kind == STORE_DRAIN_SEGMENTS) {
            segment_blocks++;
            segment_rows += block.segments;
            if (ring_blocks > 0) {
                order_ok = false;
            }
        } else {
            ring_blocks++;
        }
        device_copy(&d, &block);
        const store_drain_verdict_t v =
            store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
        if (v == STORE_DRAIN_KEPT) {
            break;
        }
        CHECK(v == STORE_DRAIN_FREED, "every block but the last should be freed");
    }

    CHECK(bodies == SIM_BODY_BLOCKS, "both sample blocks should be drained, got %" PRIu32,
          bodies);
    CHECK(segment_blocks == SIM_HEADER_BLOCKS, "the summary block should be drained, got %" PRIu32,
          segment_blocks);
    CHECK(order_ok, "samples then summaries then the ring, in that order");
    CHECK(segment_rows == 5, "all five summaries should have been in the block, got %" PRIu32,
          segment_rows);
    CHECK(d.segments_copied == 5, "and all five should be counted as copied, got %" PRIu32,
          d.segments_copied);

    /* Nothing preserved is left, and the region is blank rather than emptied. */
    CHECK(preserve_records_present() == 0, "no preserved record should survive the drain");
    for (uint32_t b = 0; b < SIM_HEADER_BLOCKS + SIM_BODY_BLOCKS; b++) {
        CHECK(block_is_blank(SIM_RING_BYTES + (size_t)b * STORE_DRAIN_BLOCK_BYTES),
              "preservation block %" PRIu32 " should be blank", b);
    }
    store_drain_end(&d);
}

/** A summary's digest has to survive the wire, which means the wire's numbers. */
static void test_a_segment_digest_is_over_what_crosses_the_wire(void)
{
    printf("a summary's digest is taken over the values a reader gets back\n");
    store_alert_header_t a;
    memset(&a, 0, sizeof(a));
    a.session_start_ms = BASE_MS;
    a.seq = 12;
    a.field = 1;
    a.start_ms = BASE_MS;
    a.stop_ms = BASE_MS + 60000;
    a.first_index = 5;
    a.last_index = 65;
    a.points = 61;
    a.wrap = 2;
    a.threshold = 20.0f;
    a.first = 21.5f;
    a.mean = 22.0f;

    store_alert_header_t b = a;
    const uint32_t base = store_drain_digest_segment(&a);
    CHECK(base == store_drain_digest_segment(&b), "the same summary should digest the same");

    b.flags = STORE_ALERT_FLAG_BODY_GONE;
    CHECK(store_drain_digest_segment(&b) == base,
          "whether the samples are still on flash must not change the digest");

    b = a;
    b.mean = 22.0001f;
    CHECK(store_drain_digest_segment(&b) != base, "a changed statistic should change it");

    b = a;
    b.midpoint = (float)NAN;
    const uint32_t with_nan = store_drain_digest_segment(&b);
    b.midpoint = 0.0f;
    CHECK(store_drain_digest_segment(&b) == with_nan,
          "a value with no cell on the wire should digest as zero, as a reader sees it");
}

/* --- 9. a reading's digest names its session ------------------------------- */

static void test_a_reading_digest_names_its_session(void)
{
    printf("a drained reading's digest says which session it belongs to\n");
    store_reading_t r;
    memset(&r, 0, sizeof(r));
    r.index = 41;
    r.epoch_ms = BASE_MS;
    r.values[0] = 1.23f;

    const uint32_t one = store_drain_digest_reading("SPS30-20260803_000000.csv", &r);
    const uint32_t other = store_drain_digest_reading("SPS30-20260803_010000.csv", &r);
    CHECK(one != other, "the same reading under two sessions should not digest the same");
    CHECK(one == store_drain_digest_reading("SPS30-20260803_000000.csv", &r),
          "and the same reading under the same session should");

    /* Sub-second precision is not on the wire, so it must not be in the digest:
     * a row that came back rounded to the second would otherwise fail a copy
     * that is perfectly complete. */
    store_reading_t rounded = r;
    rounded.epoch_ms += 400;
    CHECK(store_drain_digest_reading("SPS30-20260803_000000.csv", &rounded) == one,
          "the digest should be over whole seconds, as the wire carries them");

    /* Order must not matter: the controller reads pages in write order and the
     * device receives them in that order, but a device that sorted would still
     * be holding the same data. */
    const uint32_t forward = store_drain_digest_fold(store_drain_digest_fold(0, one), other);
    const uint32_t backward = store_drain_digest_fold(store_drain_digest_fold(0, other), one);
    CHECK(forward == backward, "folding should not depend on the order");
}

/* --- 9b. the digests the app has to agree with ----------------------------- */

/**
 * Shared expected values, carried on both sides rather than derived on either.
 *
 * This is the check the whole feature stands on. The controller frees a block
 * when the digest the app sends matches the one it computes itself, so if the
 * two implementations ever drift apart the drain silently stops working - or,
 * far worse, starts agreeing about data that is not the same data. A test on
 * either side alone cannot see that; only a shared expected value can.
 *
 * The eight readings are real board output, the same rows `SessionArchiveTest`
 * carries for the erase gate's digest, and the clockless one is the same reading
 * `test_store_merge.c` carries. `DeviceDrainTest` asserts these exact hex values
 * from the Kotlin. Change either side and move both.
 */
static void test_the_digests_the_app_must_agree_with(void)
{
    printf("the digests the app has to reproduce from what it received\n");

    static const char *const CELLS[8] = {
        "5.39,18.01,27.97,29.96,10.29,29.78,42.23,44.63,45.00,1.630",
        "2.19,5.84,8.68,9.25,7.31,13.69,17.28,17.97,18.08,0.851",
        "2.00,2.44,2.69,2.75,12.71,15.54,15.96,16.03,16.04,0.641",
        "2.03,2.27,2.36,2.38,13.32,15.98,16.20,16.23,16.24,0.584",
        "2.11,2.23,2.23,2.23,14.10,16.73,16.84,16.84,16.85,0.488",
        "2.19,2.32,2.32,2.32,14.66,17.40,17.52,17.52,17.53,0.439",
        "2.28,2.41,2.41,2.41,15.21,18.05,18.17,18.18,18.18,0.458",
        "2.34,2.47,2.47,2.47,15.63,18.55,18.67,18.68,18.69,0.485",
    };
    const char *session = "SPS30-20260802_184742.csv";
    const int64_t first_s = 1785710862LL; /* 2026-08-02T18:47:42-04:00 */

    uint32_t digest = 0;
    uint32_t first_row = 0;
    for (int i = 0; i < 8; i++) {
        store_reading_t r;
        memset(&r, 0, sizeof(r));
        r.index = (uint32_t)(i + 1);
        r.epoch_ms = (first_s + i) * 1000;
        r.settling = true;
        char buf[128];
        snprintf(buf, sizeof(buf), "%s", CELLS[i]);
        char *p = buf;
        for (int f = 0; f < STORE_FIELDS; f++) {
            char *comma = strchr(p, ',');
            if (comma != NULL) {
                *comma = '\0';
            }
            r.values[f] = (float)atof(p);
            p = comma != NULL ? comma + 1 : p;
        }
        const uint32_t one = store_drain_digest_reading(session, &r);
        if (i == 0) {
            first_row = one;
        }
        digest = store_drain_digest_fold(digest, one);
    }
    CHECK(first_row == 0xe32af258u, "the first row digests to %08" PRIx32 ", not e32af258",
          first_row);
    CHECK(digest == 0xd1ca7fb2u, "the eight rows digest to %08" PRIx32 ", not d1ca7fb2", digest);

    /* A reading recorded with no clock at all. Its packed board clock sits in
     * the field an absolute reading holds an instant in, and it is that packed
     * value that is digested - digesting the empty timestamp cell instead would
     * make every clockless block unprovable, and so undrainable. */
    store_reading_t rel;
    memset(&rel, 0, sizeof(rel));
    rel.index = 1;
    rel.epoch_ms = store_rel_pack(1202, 2081);
    rel.settling = true;
    const float vals[STORE_FIELDS] = {2.50f,  10.83f, 17.45f, 18.77f, 0.00f,
                                      11.22f, 19.45f, 21.04f, 21.28f, 1.630f};
    for (int f = 0; f < STORE_FIELDS; f++) {
        rel.values[f] = vals[f];
    }
    const uint32_t clockless =
        store_drain_digest_reading("SPS30-R000004B2-00000821.csv", &rel);
    CHECK(clockless == 0x9f3c822fu, "the clockless row digests to %08" PRIx32 ", not 9f3c822f",
          clockless);

    /* And a preserved segment summary, which is the other shape entirely. */
    store_alert_header_t h;
    memset(&h, 0, sizeof(h));
    h.session_start_ms = 1785706800000LL; /* SPS30-20260802_174000.csv */
    h.utc_offset_min = -240;
    h.seq = 42;
    h.field = 1;
    h.start_ms = 1785710862000LL;
    h.stop_ms = 1785710961000LL;
    h.first_index = 3745;
    h.last_index = 3844;
    h.points = 100;
    h.wrap = 11;
    h.threshold = 20.0f;
    h.first = 21.5f;
    h.last = 19.25f;
    h.midpoint = 44.125f;
    h.mean = 33.75f;
    h.variance = 12.5f;
    h.minimum = 18.0f;
    h.maximum = 91.5f;
    const uint32_t summary = store_drain_digest_segment(&h);
    CHECK(summary == 0x7b253807u, "the summary digests to %08" PRIx32 ", not 7b253807", summary);
}

/* --- 10. an empty and a nearly-empty board -------------------------------- */

static void test_a_board_with_almost_nothing(void)
{
    printf("a board that has just started, and one that holds nothing at all\n");
    sim_reset();
    device_reset();

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    store_drain_block_t block;
    CHECK(!store_drain_choose(&d, SIM_SLOTS, 0, &block),
          "a blank board should offer no block at all");
    CHECK(store_drain_blocks_left(&d) == 0, "and should say it has none left");
    store_drain_end(&d);

    /* One page written, the head just past it. The only block is the final one:
     * it is copied and confirmed and then kept, because the board is recording
     * into it. */
    sim_reset();
    write_ring_page(0, 1, 1, BASE_MS, 1, 12);
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    CHECK(store_drain_blocks_left(&d) == 1, "one block should be left");
    CHECK(store_drain_choose(&d, SIM_SLOTS, 1, &block), "and it should be offered");
    CHECK(block.final, "and it should be the final block");
    static store_page_t scratch;
    device_copy(&d, &block);
    const store_drain_verdict_t v =
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    CHECK(v == STORE_DRAIN_KEPT, "the final block should be kept rather than freed");
    CHECK(sim.erases == 0, "and nothing should have been erased");
    CHECK(ring_pages_present() == 1, "and the readings arriving now should still be there");
    CHECK(device.count == 12, "and they should also be on the device");
    store_drain_end(&d);
}

/* --- 11. a partial copy is not a copy -------------------------------------- */

static void test_a_partial_copy_frees_nothing(void)
{
    printf("a device that only took half a block cannot confirm it\n");
    build_full_ring();
    device_reset();
    static store_page_t scratch;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    store_drain_block_t block;
    store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block);

    /* The link drops half way through the block's rows. */
    device.accept_at_most = 17;
    device_copy(&d, &block);
    device.accept_at_most = 0;
    CHECK(device.in_block == 17, "the device should have taken only what it was given");

    const store_drain_verdict_t v =
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    CHECK(v == STORE_DRAIN_REFUSED, "half a block is not a block");
    CHECK(sim.erases == 0, "and must not free anything");
    CHECK(ring_pages_present() == SIM_SLOTS, "and must not lose a page");
    store_drain_end(&d);
}

/* --- 12. several sessions, and pages that are not pages -------------------- */

static void test_mixed_sessions_and_corrupt_pages(void)
{
    printf("a block of several sessions, and a page that fails its CRC\n");
    sim_reset();
    device_reset();
    static store_page_t scratch;

    /* Four pages of one session and two of another, in one block. */
    write_ring_page(0, 10, 1, BASE_MS, 1, 20);
    write_ring_page(1, 11, 1, BASE_MS, 21, 20);
    write_ring_page(2, 12, 2, BASE_MS + 3600000, 1, 20);
    write_ring_page(3, 13, 2, BASE_MS + 3600000, 21, 20);
    /* And one that a power cut interrupted: a plausible header over a body that
     * does not check out. It is not a page, and it must not be one on either
     * side of the confirmation. */
    write_ring_page(4, 14, 2, BASE_MS + 3600000, 41, 20);
    sim.bytes[4 * STORE_PAGE_BYTES + STORE_HEADER_BYTES + 3] ^= 0xFFu;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    store_drain_block_t block;
    /* The head is in the second block, so the first is an ordinary one. */
    CHECK(store_drain_choose(&d, SIM_SLOTS, STORE_DRAIN_BLOCK_PAGES, &block),
          "the first block should be offered");
    device_copy(&d, &block);
    CHECK(device.in_block == 80, "the corrupt page should contribute no rows, got %" PRIu32,
          device.in_block);

    bool two_sessions = false;
    for (uint32_t i = 1; i < device.count; i++) {
        if (strcmp(device.session[i], device.session[0]) != 0) {
            two_sessions = true;
        }
    }
    CHECK(two_sessions, "rows should carry the session they belong to, not the block's");

    const store_drain_verdict_t v =
        store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    CHECK(v == STORE_DRAIN_FREED, "a corrupt page must not stop a good copy being confirmed");
    store_drain_end(&d);
}

/* --- 13. what is left to do, counted from flash ---------------------------- */

static void test_blocks_left_comes_from_flash(void)
{
    printf("what is left to do is counted from flash, so it is right after an interruption\n");
    build_full_ring();
    device_reset();
    static store_page_t scratch;

    store_drain_t d;
    memset(&d, 0, sizeof(d));
    store_drain_begin(&d, &SIM_DEV, 1);
    CHECK(store_drain_blocks_left(&d) == SIM_RING_BLOCKS,
          "a full ring should have every block left");

    store_drain_block_t block;
    store_drain_choose(&d, SIM_SLOTS, FULL_HEAD_SLOT, &block);
    device_copy(&d, &block);
    store_drain_confirm(&d, block.id, device.in_block, device.block_digest, &scratch);
    CHECK(store_drain_blocks_left(&d) == SIM_RING_BLOCKS - 1,
          "and one fewer once a block has been freed");

    /* A fresh drain over the same flash agrees, which is what makes the figure
     * survive a reboot without anything being stored. */
    store_drain_t fresh;
    memset(&fresh, 0, sizeof(fresh));
    store_drain_begin(&fresh, &SIM_DEV, 2);
    CHECK(store_drain_blocks_left(&fresh) == SIM_RING_BLOCKS - 1,
          "a drain that remembers nothing should count the same");
    store_drain_end(&fresh);
    store_drain_end(&d);
}

/* --- 14. one drain at a time, and it belongs to somebody ------------------- */

static void test_one_drain_at_a_time(void)
{
    printf("a drain belongs to the device that started it, and there is one\n");
    build_full_ring();
    store_drain_t d;
    memset(&d, 0, sizeof(d));
    CHECK(store_drain_begin(&d, &SIM_DEV, 5), "the first drain should open");
    CHECK(store_drain_is_owner(&d, 5), "and should belong to who opened it");
    CHECK(!store_drain_is_owner(&d, 6), "and to nobody else");
    CHECK(!store_drain_begin(&d, &SIM_DEV, 6), "a second device should be refused");
    store_drain_end(&d);
    CHECK(!store_drain_is_owner(&d, 5), "and an ended drain belongs to nobody");
    CHECK(store_drain_begin(&d, &SIM_DEV, 6), "after which another device may start one");
    store_drain_end(&d);
}

int main(void)
{
    test_full_drain();
    test_oldest_first_across_the_wrap();
    test_a_wrong_digest_frees_nothing();
    test_interrupted_before_confirming();
    test_interrupted_before_freeing();
    test_flash_changing_under_a_block();
    test_cancel_part_way_through();
    test_preserved_segments_go_first_and_go_completely();
    test_a_segment_digest_is_over_what_crosses_the_wire();
    test_a_reading_digest_names_its_session();
    test_the_digests_the_app_must_agree_with();
    test_a_board_with_almost_nothing();
    test_a_partial_copy_frees_nothing();
    test_mixed_sessions_and_corrupt_pages();
    test_blocks_left_comes_from_flash();
    test_one_drain_at_a_time();

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}

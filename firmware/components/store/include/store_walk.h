/**
 * @file store_walk.h
 * @brief Reading one session back out of the ring, without reading the rest. Pure code.
 *
 * This is the read half of `get_file`, lifted out of `store.c` for the same
 * reason `store_sessions.c` and `net_time_offset.c` were: the rule it enforces
 * is worth checking, and it cannot be checked while it is tangled up with
 * `esp_partition`. Give it a ::store_slot_reader_t and it walks any ring - the
 * host pack gives it an array.
 *
 * ## What it is for
 *
 * A `get_file` used to read **the whole ring** - 4,480 slots, 512 bytes each,
 * 2.19 MB of flash - whether it was about to return twenty rows or twenty
 * thousand. That is a fixed cost of most of a second on every call, and a
 * reconnect backfill of a hundred and sixty readings spent about 63% of its time
 * in it. Worse, it is spent *before the first row*, inside one uninterruptible
 * `store_cursor_next`, which is what a task watchdog saw as `IDLE0` starved for
 * five seconds while a reconnect was served.
 *
 * Two things fix it, and both are here:
 *
 * 1. **Read forty-eight bytes to decide, five hundred and twelve to use.** The
 *    header alone answers "is this page mine, and is it past the client's
 *    watermark" - `store_peek_page` never looks further - so the body is read
 *    only for a page that is going to be decoded. On the pages a walk skips,
 *    which is nearly all of them, that is 90.6% less flash read. It is the same
 *    trade the boot scan already makes, and the same one `ring_header_at` makes;
 *    the `get_file` cursor was the one ring walk that did not.
 * 2. **Start at the session's own pages.** `page_seq` and ring slot are the same
 *    number offset by a constant (see ::store_walk_slot_of_seq), so a session
 *    that knows the span of sequences its pages occupy knows the span of slots
 *    too. The walk then covers that span instead of the ring.
 *
 * ## Why the span is only ever a hint
 *
 * ::store_walk_plan takes the span as a **bound that may be too wide and must
 * never be too narrow**, and everything it does is arranged around that: too
 * wide costs forty-eight-byte reads, too narrow loses readings. The per-slot
 * header check is unconditional and unchanged, so a slot inside the span that
 * has been overwritten by another session is rejected exactly as before, and an
 * open cursor is still not a lock. See ::store_session_t::first_seq for why the
 * summary's own span only ever widens, and ::store_walk_plan for what a caller
 * must do when it cannot vouch for one.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "store_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Where a walk reads slots from.
 *
 * The ring, on the board; an array, in the host pack. `len` is either
 * ::STORE_HEADER_BYTES or ::STORE_PAGE_BYTES and nothing else, which is the
 * distinction the whole file is about.
 *
 * Deliberately not ::store_page_source_t: that one hands back a decoded header
 * and can therefore only ever read forty-eight bytes, which is all a scan wants.
 * A walk also has to read a body.
 */
typedef struct {
    /**
     * Read the first @p len bytes of slot @p slot.
     * @return false if that read failed, which the walk skips.
     */
    bool (*read)(void *ctx, uint32_t slot, uint8_t *out, size_t len);
    void *ctx;
    uint32_t slots; /**< slots in the ring, `0 .. slots - 1` */
} store_slot_reader_t;

/**
 * @brief The ring's geometry at the moment a cursor opens.
 *
 * A snapshot, taken under the store's lock and then used without it. Nothing in
 * a walk depends on it staying true: it decides only where to start and how far
 * to go, and both are bounds rather than promises.
 */
typedef struct {
    uint32_t slots;    /**< slots in the ring */
    uint32_t head;     /**< slot the next page goes into */
    uint32_t next_seq; /**< `page_seq` the next page will carry */
    bool wrapped;      /**< the ring has filled at least once */
} store_ring_geometry_t;

/** @brief A walk over one session's stored readings. Open, next, done. */
typedef struct {
    uint32_t session;
    /** The other half of the session key: the number repeats across reboots. */
    int64_t session_start_ms;
    uint32_t since_index;
    uint32_t slot;      /**< next slot to examine */
    uint32_t remaining; /**< slots left to examine before giving up */
    store_page_t page;  /**< the page being handed out */
    uint8_t next;       /**< next reading within ::page */
    bool have_page;
    bool open;
} store_cursor_t;

/**
 * @brief The slot a page of sequence @p seq was written into.
 *
 * The ring's one structural identity: `page_seq` advances exactly once per page
 * written and the head advances exactly once with it, so
 * `slot = (head - (next_seq - seq)) mod slots` for every page still in the ring
 * - and `store.c`'s boot scan re-establishes it by putting the head one past the
 * slot holding the largest sequence it found.
 *
 * Meaningful only for `next_seq - slots <= seq < next_seq`. Older than that and
 * the page has been overwritten; the answer aliases onto whatever took its slot,
 * which is why ::store_walk_plan clamps before it calls this.
 */
uint32_t store_walk_slot_of_seq(const store_ring_geometry_t *g, uint32_t seq);

/**
 * @brief Aim a cursor at the slots that can hold @p first_seq .. @p last_seq.
 *
 * @param have_span false when the caller cannot vouch for the span - a session
 *        index that has had to drop a summary can hand back one that starts too
 *        late, and too late loses readings. The whole ring is then walked, which
 *        is what this always used to do and is now cheap enough to be the honest
 *        fallback rather than a penalty.
 *
 * The span is clamped to the pages the ring can still hold, so a `first_seq`
 * from before the last wrap starts the walk at the oldest live slot rather than
 * aliasing onto a slot that has been reused since.
 */
void store_walk_plan(store_cursor_t *cursor, const store_ring_geometry_t *g, bool have_span,
                     uint32_t first_seq, uint32_t last_seq);

/**
 * @brief The next reading, or false when there are no more.
 *
 * @param decode_failures if not NULL, incremented once per page that matched on
 *        its header and then failed its CRC.
 */
bool store_walk_next(store_cursor_t *cursor, const store_slot_reader_t *src,
                     store_reading_t *out, uint32_t *decode_failures);

#ifdef __cplusplus
}
#endif

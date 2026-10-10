/**
 * @file store_drain.h
 * @brief Back up everything and clear the board, one erase-unit at a time.
 *
 * A **separate** path from `store_erase_confirmed`, deliberately. That one takes
 * a manifest of whole sessions, checks them all, and then erases the sectors it
 * may - which is right for "clear what I already have" and wrong for "empty this
 * board", because a board that has been recording for days holds more than a
 * phone can copy in one held breath, and because nothing frees space until the
 * whole copy is done. This one interleaves: copy a block, prove it landed, free
 * that block, move on. Acquisition never stops and never loses a reading, since
 * space comes back ahead of the write head rather than after everything.
 *
 * The rule the erase gate is built on is not weakened anywhere here: **nothing
 * is freed that the asking device has not proved it holds**, and the proof is
 * the same recomputed-from-flash digest, per block, every single time.
 *
 * ## The block
 *
 * A block is one **flash erase unit** - a 4096-byte sector - because that is the
 * smallest thing that can actually be freed. Three kinds, and the order they are
 * drained in is the order in which losing one would hurt most:
 *
 * 1. ::STORE_DRAIN_BODY - a sector of preserved alert samples.
 * 2. ::STORE_DRAIN_SEGMENTS - a sector of preserved segment headers.
 * 3. ::STORE_DRAIN_RING - a sector of the ordinary ring, oldest first.
 *
 * The preservation area goes first because it is by construction the *oldest*
 * data the board holds: it is what was rescued from ring sectors that have since
 * been overwritten, so it is the part that exists nowhere else. Bodies before
 * headers, so that an interrupted drain leaves the summaries - which the design
 * requires to outlive their samples - rather than the other way round.
 *
 * Draining the preservation area at all is the thing this feature could most
 * easily have got wrong. Preserved data is addressed completely differently from
 * ring data: a different region, its own two rings, its own record format for
 * the headers, and `store_erase_confirmed` never touches any of it by design. A
 * "back up everything" that walked only the ring would silently leave every
 * preserved segment behind and call the board clear.
 *
 * ## The end of the sweep
 *
 * Ring sectors are drained oldest first, and the one sector that is never freed
 * is the one the write head is in - it holds the readings arriving right now.
 * Every other sector goes, so by the time the drain reaches the end the head has
 * moved on into space that was freed earlier, and the sector it was in when the
 * drain started is an ordinary block like any other. What remains is the block
 * being written, which is copied, confirmed and **kept**: it becomes the start
 * of storage. The ring is cyclic and its tail is derived from `page_seq` at scan
 * time, so nothing physically moves - only the bookkeeping.
 *
 * ## Crash safety
 *
 * There is no persistent drain state, and that is the design rather than an
 * omission. Progress *is* the contents of flash: a freed block is gone, so the
 * next "oldest block" is the next one, and a drain that is interrupted anywhere
 * - link lost, app killed, board rebooted, user cancelled - resumes by asking
 * the same question again. Nothing can be half-freed and unrecorded, because a
 * block is only ever freed after its copy has been proved, and a sector erase
 * that a power cut interrupts leaves pages that fail their CRC and are simply
 * not pages. Re-copying a block that was confirmed but not freed costs a second
 * and loses nothing.
 *
 * ## Purity
 *
 * Everything here is over a ::store_drain_dev_t - two function pointers - as
 * `store_preserve.h` is over its own device and `store_merge.h` is over a sink.
 * That is what lets `test_store_drain.c` drive a full ring, a wrap boundary, a
 * reboot and a failed confirmation on a workstation, which is the only place any
 * of this can be tested: the captain's board is recording and must not be swept.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "store_alert.h"
#include "store_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

/** @brief The erase unit, and therefore the block. */
#define STORE_DRAIN_BLOCK_BYTES 4096

/** @brief Pages in one block of the ring or of the preserved bodies. */
#define STORE_DRAIN_BLOCK_PAGES (STORE_DRAIN_BLOCK_BYTES / STORE_PAGE_BYTES)

/** @brief Segment headers in one block of the preserved header region. */
#define STORE_DRAIN_BLOCK_HEADERS (STORE_DRAIN_BLOCK_BYTES / STORE_ALERT_HEADER_BYTES)

/** @brief What a block is made of. */
typedef enum {
    STORE_DRAIN_NONE = 0,
    /** Preserved alert samples: ordinary pages, in the preservation area. */
    STORE_DRAIN_BODY,
    /** Preserved segment summaries: ::store_alert_header_t records. */
    STORE_DRAIN_SEGMENTS,
    /** Ordinary recorded readings: pages of the ring. */
    STORE_DRAIN_RING,
} store_drain_kind_t;

/** @brief One block, as offered to the device. */
typedef struct {
    /** Never repeats within a drain, and 0 means "no block". */
    uint32_t id;
    store_drain_kind_t kind;
    /** Byte offset of the sector within the store partition. */
    size_t offset;
    /** For ::STORE_DRAIN_RING, the first ring slot the block covers. */
    uint32_t slot;
    /** Pages, or header records, that decoded. */
    uint32_t records;
    /** Readings inside it; zero for ::STORE_DRAIN_SEGMENTS. */
    uint32_t readings;
    /** Segment summaries inside it; zero otherwise. */
    uint32_t segments;
    /** Lowest `page_seq` (or header `seq`) in it - what makes it the oldest. */
    uint32_t oldest_seq;
    /**
     * The block holding the ring's write head.
     *
     * Copied and confirmed like every other, and then **kept**: it carries the
     * readings arriving right now, and the captain was explicit that it is not
     * deleted while the board is still recording. See the file comment.
     */
    bool final;
} store_drain_block_t;

/** @brief What a confirmation did. */
typedef enum {
    /** The claim matched and the block's space is now free. */
    STORE_DRAIN_FREED = 0,
    /** The claim matched and the block was deliberately kept: it is the last. */
    STORE_DRAIN_KEPT,
    /** The claim did not match what the controller holds. Nothing was freed. */
    STORE_DRAIN_REFUSED,
    /** No block by that id is in flight. Nothing was freed. */
    STORE_DRAIN_NOT_OFFERED,
    /** The claim matched and flash would not erase. Nothing was freed. */
    STORE_DRAIN_ERASE_FAILED,
} store_drain_verdict_t;

/**
 * @brief Where a drain reads and erases: byte offsets into the store partition.
 *
 * Two calls and four numbers, which is all this needs to know about flash. There
 * is deliberately no write: a drain removes and never adds, so a defect in it
 * cannot corrupt a page it was supposed to be copying.
 */
typedef struct {
    void *ctx;
    /** @return false on any read failure; @p out is then not to be trusted. */
    bool (*read)(void *ctx, size_t offset, uint8_t *out, size_t len);
    /** @p offset and @p len are ::STORE_DRAIN_BLOCK_BYTES-aligned. */
    bool (*erase)(void *ctx, size_t offset, size_t len);

    /** Bytes of the partition the ring owns, from offset 0. */
    size_t ring_bytes;
    /** Byte offset of the preservation area. */
    size_t preserve_base;
    /** Bytes of the preservation area given to segment headers, from its base. */
    size_t preserve_header_bytes;
    /** Bytes of the whole preservation area. */
    size_t preserve_bytes;
} store_drain_dev_t;

/** @brief A drain in progress. Caller-owned; one per controller, never on a stack. */
typedef struct {
    store_drain_dev_t dev;
    bool open;
    /** The client that started it, so a disconnect ends its own drain only. */
    int owner;
    uint32_t next_id;

    /** The block offered and not yet answered for. */
    store_drain_block_t held;
    bool have_held;
    /** What was actually streamed for ::held, which is what a claim is held to. */
    uint32_t sent_rows;
    uint32_t sent_digest;
    bool sent;

    /* --- what has happened so far, for the wire and for the log ------------ */
    uint32_t blocks_offered;
    uint32_t blocks_freed;
    uint32_t blocks_kept;
    uint32_t blocks_refused;
    uint32_t readings_copied;
    uint32_t segments_copied;
    uint32_t readings_freed;
    /** True once the final block has been confirmed. */
    bool finished;
} store_drain_t;

/**
 * @brief Begin a drain owned by @p owner. Frees nothing and reads nothing.
 *
 * @return false if one is already open. One at a time for the whole controller,
 *         for the same reason an import is: two devices each freeing what only
 *         *they* have proved they hold would each be right and the board would
 *         still end up with data on neither.
 */
bool store_drain_begin(store_drain_t *d, const store_drain_dev_t *dev, int owner);

/** @brief Abandon a drain. Deletes nothing; safe at any moment. */
void store_drain_end(store_drain_t *d);

/** @brief Whether @p owner holds the open drain. */
bool store_drain_is_owner(const store_drain_t *d, int owner);

/**
 * @brief How many blocks still hold data, so a client can show a bar.
 *
 * Counted from flash, so it is right after a reboot, after a cancel, and after
 * somebody else's drain - there is no remembered total to go stale. The figure
 * includes the final block, which is copied but never freed, and it takes no
 * view of where the write head is: what is left to do is what still holds data,
 * and which block the head happens to be in changes the order rather than the
 * count.
 */
uint32_t store_drain_blocks_left(const store_drain_t *d);

/**
 * @brief Choose the oldest block that still holds data, and offer it.
 *
 * Re-derived from flash on every call rather than walked with a cursor, which is
 * what makes the whole operation resumable: the answer to "what is oldest" after
 * an interruption is simply the answer to the same question again.
 *
 * @param slots slots in the ring; @p head_slot the slot the next page goes into.
 * @return false when there is nothing left at all, which is the empty-board
 *         case. A board that is recording always has at least the final block.
 */
bool store_drain_choose(store_drain_t *d, uint32_t slots, uint32_t head_slot,
                        store_drain_block_t *out);

/**
 * @brief The rows of @p block, handed one at a time to @p emit.
 *
 * The controller's own account of the block, and the thing the digest is taken
 * over. @p emit sees exactly what goes on the wire; ::store_drain_measure
 * digests exactly the same rows in the same order, which is what makes "what was
 * sent" and "what is claimed" comparable at all.
 *
 * @param scratch caller-owned page buffer - ::store_page_t is 2.6 KB and a local
 *        one overflows the store task's stack. See `store_codec.h`.
 * @param emit called per row; returning false stops the walk early (a full wire
 *        frame). @p ctx is passed through.
 * @return how many rows were emitted.
 */
typedef bool (*store_drain_reading_fn)(void *ctx, const char *session, uint32_t page_seq,
                                       const store_reading_t *reading, bool relative);
typedef bool (*store_drain_segment_fn)(void *ctx, const store_alert_header_t *header);

/**
 * @brief Walk one block's readings. ::STORE_DRAIN_SEGMENTS blocks emit none.
 *
 * @param from_row skip this many rows first, so a wire that fits forty rows in a
 *        frame can send a block of three hundred across several frames without
 *        holding a cursor open across them.
 */
uint32_t store_drain_walk_readings(const store_drain_t *d, const store_drain_block_t *block,
                                   uint32_t from_row, store_page_t *scratch,
                                   store_drain_reading_fn emit, void *ctx);

/**
 * @brief Walk one block's segment summaries. Other kinds emit none.
 */
uint32_t store_drain_walk_segments(const store_drain_t *d, const store_drain_block_t *block,
                                   uint32_t from_row, store_drain_segment_fn emit, void *ctx);

/**
 * @brief Recompute @p block's `(rows, digest)` from flash.
 *
 * The same arithmetic the erase gate uses, over the block instead of over a
 * session: ::store_drain_digest_reading per reading (or
 * ::store_drain_digest_segment per summary), folded by addition so the order
 * pages are read in cannot change the answer.
 *
 * @return false if the block could not be read at all, which is never a reason
 *         to free anything.
 */
bool store_drain_measure(const store_drain_t *d, const store_drain_block_t *block,
                         store_page_t *scratch, uint32_t *rows, uint32_t *digest);

/** @brief Record what was actually streamed for the block in flight. */
void store_drain_sent(store_drain_t *d, uint32_t rows, uint32_t digest);

/**
 * @brief The device claims it holds block @p id. Free it if that is true.
 *
 * Three things must agree before a single byte is erased, and they are checked
 * in this order:
 *
 * 1. @p id is the block in flight, and it was actually streamed.
 * 2. The claim equals what was streamed - the device holds what it was sent.
 * 3. A **fresh** read of the block from flash still equals what was streamed -
 *    nothing has changed underneath, so what is about to be erased is what was
 *    proved.
 *
 * Any disagreement refuses, and a refusal frees nothing and ends the drain: an
 * ambiguous confirmation is the one situation in which carrying on would be
 * indefensible. ::STORE_DRAIN_KEPT is the final block, which passes the same
 * checks and is then deliberately left alone.
 *
 * @param scratch caller-owned; see ::store_drain_walk_readings.
 */
store_drain_verdict_t store_drain_confirm(store_drain_t *d, uint32_t id, uint32_t rows,
                                          uint32_t digest, store_page_t *scratch);

/* --- the canonical forms a claim is built from ----------------------------- */

/**
 * @brief One drained reading's digest: its session's name, then the reading.
 *
 * `store_digest_reading`'s canonical bytes with a CRC of the session name in
 * front of them. The name is there because a block is a slice of *flash* rather
 * than of one session and may hold pages of several, so a digest that ignored it
 * could not tell rows filed under the wrong session from rows filed right.
 *
 * Fixed-width little-endian throughout, so the bytes a phone builds from the row
 * it received and the bytes the controller builds from flash are the same bytes.
 */
uint32_t store_drain_digest_reading(const char *session, const store_reading_t *reading);

/**
 * @brief One preserved segment's digest, over the fields that cross the wire.
 *
 * The statistics go in as their raw IEEE-754 bit patterns rather than as
 * quantised integers, because a `float` written by `wire_alert_segment_line`
 * survives the wire exactly - the encoder prints the shortest form that reads
 * back as the same number - so both ends can reconstruct the identical float and
 * there is no rounding to disagree about. A value that is not finite digests as
 * zero, which is what a reader sees when the wire says `null`.
 */
uint32_t store_drain_digest_segment(const store_alert_header_t *header);

/** @brief Fold one row's digest into a running one. Addition; order-free. */
static inline uint32_t store_drain_digest_fold(uint32_t acc, uint32_t one)
{
    return acc + one;
}

#ifdef __cplusplus
}
#endif

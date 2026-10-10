/**
 * @file store_preserve.h
 * @brief The dedicated area alert-level readings are copied into.
 *
 * The captain's storage-wrap design, in one sentence: **recording incoming data
 * always beats protecting older data**, so the ring wraps and ordinary history is
 * allowed to go - but a reading that crossed the alert line is copied out of the
 * ring's way first, and never comes back under the write head.
 *
 * ## Where it lives
 *
 * At the **end of the `store` partition**, in the last ::STORE_PRESERVE_SECTORS
 * sectors, which the ring is told about and never writes into. Deliberately not a
 * new partition: a partition-table change has to be flashed, and flashing one at
 * a different offset is how a board's existing readings get orphaned. This costs
 * the ring about a tenth of its slots - 2.36 days of continuous 1 Hz becomes
 * 2.12 - and buys alert data that survives however many times it wraps.
 *
 * ## Two regions, ageing independently
 *
 * ```
 *  <---------------- store partition ------------------------------------>
 *  [ ring: pages, oldest reclaimed first ][ headers ][ bodies ]
 *                                          \___ preservation area ____/
 * ```
 *
 * - **Headers**: ::STORE_ALERT_HEADER_BYTES records, one per preserved segment.
 * - **Bodies**: the samples, as ordinary ::store_page_t pages, so they decode,
 *   export and CSV-format through exactly the code the ring's pages do.
 *
 * They are separate rings because of the captain's requirement that the header
 * "must survive even if everything else about the segment is eventually reduced
 * away". A header is 128 bytes and a segment's samples are typically thousands,
 * so the header region holds hundreds of segments' worth of summary in the space
 * a handful of segments' samples take. When the body ring must reclaim - and it
 * must, or a new alert could not be recorded, which is the one thing this design
 * refuses - the summaries stay.
 *
 * A header is **never rewritten** to say its body has gone. Flash cannot rewrite
 * a record in place and the ::store_alert_header_t CRC covers the flag, so
 * "reduced away" is worked out at read time by comparing against the oldest page
 * still on the body ring. That also keeps the rule the relative-time work states
 * plainly: the board appends, it does not go back and relabel what is stored.
 *
 * ## Power loss
 *
 * A header record and a body page are each written once, whole, with a CRC. One
 * interrupted by a power cut fails its CRC and is simply not a record. The most
 * that can be lost is the segment still being accumulated, and that loss is
 * bounded by ::store_preserve_body_write being called as the pages fill rather
 * than at the end.
 *
 * ## Purity
 *
 * Everything here is over a ::store_preserve_dev_t - three function pointers -
 * rather than over `esp_partition`, exactly as `store_merge.h` is over a
 * ::store_sink_t. That is what lets `test_store_preserve.c` drive a whole ring
 * through several wraps on a workstation and check what survived, instead of
 * waiting two days for a board to fill.
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

/** Flash erases this much at a time; both regions are aligned to it. */
#define STORE_PRESERVE_SECTOR_BYTES 4096

/**
 * @brief Sectors of the `store` partition kept back for preservation: 256 KB.
 *
 * Ten per cent of a 2.44 MB store. The ring gives up about six hours of
 * continuous 1 Hz for it, which is the trade the whole design is: ordinary
 * history is allowed to be lost, alert segments are not.
 */
#define STORE_PRESERVE_SECTORS 64

/**
 * @brief Of those, the ones holding headers: 64 KB, 512 segment summaries.
 *
 * A quarter of the area for the part that must outlive the rest. 512 segments is
 * years of ordinary use - the summaries are what a board falls back to, so the
 * region that holds them is sized to be the last thing to run short.
 */
#define STORE_PRESERVE_HEADER_SECTORS 16

/** @brief Somewhere to put bytes. Sector-aligned erases; whole-record writes. */
typedef struct {
    /** Bytes in the whole preservation area. A multiple of the sector size. */
    size_t size;
    /** Bytes of it given to headers, from offset 0. A multiple of the sector size. */
    size_t header_bytes;
    void *ctx;
    /** @return false on any read failure; @p out is then not to be trusted. */
    bool (*read)(void *ctx, size_t offset, uint8_t *out, size_t len);
    bool (*write)(void *ctx, size_t offset, const uint8_t *data, size_t len);
    /** @p offset and @p len are sector-aligned. */
    bool (*erase)(void *ctx, size_t offset, size_t len);
} store_preserve_dev_t;

/** @brief Counters, for the log line and for the wire. */
typedef struct {
    uint32_t headers_written;
    uint32_t bodies_written;   /**< pages of samples committed */
    /** Segments whose samples the body ring has since reclaimed. Headers survive. */
    uint32_t bodies_reclaimed;
    /** Segment headers dropped because the header ring itself wrapped. */
    uint32_t headers_reclaimed;
    /** Writes flash refused. Counted rather than hidden; acquisition never waits. */
    uint32_t write_failures;
    uint32_t segments;         /**< header records currently held */
    uint32_t header_slots;
    uint32_t body_slots;
    uint32_t body_used;
} store_preserve_stats_t;

/** @brief The preservation area's own state. Caller-owned; not on a small stack. */
typedef struct {
    store_preserve_dev_t dev;
    uint32_t header_slots;
    uint32_t body_slots;
    uint32_t header_head;  /**< record slot the next header goes into */
    uint32_t header_seq;   /**< seq for the next header; never repeats */
    uint32_t body_head;    /**< page slot the next body page goes into */
    uint32_t body_seq;     /**< page_seq for the next body page */
    /** Lowest body page_seq still on flash. Everything below it is reduced away. */
    uint32_t body_oldest_seq;
    uint32_t body_used;
    /** The largest ::store_alert_header_t::wrap any surviving header carries. */
    uint32_t seen_wrap;
    bool ready;
    store_preserve_stats_t stats;
} store_preserve_t;

/**
 * @brief Adopt @p dev and scan both regions for their write heads.
 *
 * Age is a sequence number, not a position, exactly as in the ring: the head is
 * the slot after the largest sequence found, so nothing has to remember where
 * either region started or how often it has turned over.
 *
 * @return false if the geometry is unusable, in which case the caller has a
 *         board that measures and serves but cannot preserve - which is reported,
 *         never hidden, because "no alert segments" must not be
 *         indistinguishable from "preservation is broken".
 */
bool store_preserve_begin(store_preserve_t *p, const store_preserve_dev_t *dev);

/**
 * @brief Commit one sealed page of a segment's samples.
 *
 * Erases the sector ahead when it has to, which reclaims the eight oldest body
 * pages. Those may belong to segments whose headers are still held; that is the
 * design, not a fault, and ::store_preserve_stats_t::bodies_reclaimed counts it.
 *
 * @return false if flash refused. The caller carries on: a failure to preserve
 *         must never stop a reading being recorded.
 */
bool store_preserve_body_write(store_preserve_t *p, const uint8_t page[STORE_PAGE_BYTES]);

/**
 * @brief The page_seq the next body page will carry.
 *
 * Taken before a segment's first page is written, so its header can say where
 * its samples begin.
 */
uint32_t store_preserve_next_body_seq(const store_preserve_t *p);

/**
 * @brief Commit one segment header. @p header's ::store_alert_header_t::seq is
 *        assigned here.
 *
 * @return false if flash refused.
 */
bool store_preserve_header_write(store_preserve_t *p, store_alert_header_t *header);

/**
 * @brief How full the body region is, 0 to 1.
 *
 * What ::store_alert_rate_level is measured against, and **only** what it is
 * measured against. The ring's own fullness never reaches it: pressure on the
 * ring is answered by wrapping.
 */
float store_preserve_pressure(const store_preserve_t *p);

/**
 * @brief Copy out segment headers with ::store_alert_header_t::seq > @p since_seq.
 *
 * Oldest first, which is the order a client wants to fold them in and the order
 * that makes a truncated read the beginning of the story rather than the end.
 * ::STORE_ALERT_FLAG_BODY_GONE is set on the copies whose samples have been
 * reclaimed; nothing on flash is modified.
 *
 * @return how many were written to @p out.
 */
size_t store_preserve_list(const store_preserve_t *p, store_alert_header_t *out, size_t cap,
                           uint32_t since_seq);

/** @brief Look one segment up by its header sequence. */
bool store_preserve_find(const store_preserve_t *p, uint32_t seq, store_alert_header_t *out);

/**
 * @brief Is reading @p index of this session already preserved, and for how far?
 *
 * One pass over the header region answers both, which is the point: the sweep in
 * front of an erase asks this about hundreds of readings in a row, and asking
 * per reading rather than per *transition* would turn a few milliseconds into
 * tens of megabytes of flash reads.
 *
 * @param through set to the highest index for which the answer is still the one
 *        returned - the covering segment's last index when covered, and one
 *        before the next segment's first index when not. `UINT32_MAX` when
 *        nothing further along covers it either.
 * @return true if some preserved segment of this exact session covers @p index.
 */
bool store_preserve_covers(const store_preserve_t *p, uint32_t session,
                           int64_t session_start_ms, uint32_t index, uint32_t *through);

/** @brief True if @p header's samples are still on the body ring. */
bool store_preserve_body_present(const store_preserve_t *p,
                                 const store_alert_header_t *header);

/**
 * @brief A walk over one preserved segment's samples.
 *
 * Carries the segment's whole identity, not just its page sequence window. Two
 * things write body pages - the live segment as it records, and the sweep that
 * rescues readings the ring is about to overwrite - so a segment's pages are
 * **not** guaranteed to be contiguous in sequence. Matching on the session and
 * the index range as well is what stops one segment's cursor handing out
 * another's readings when the two interleave.
 */
typedef struct {
    uint32_t session;
    int64_t session_start_ms;
    uint32_t first_index;
    uint32_t last_index;
    uint32_t body_seq;
    uint32_t body_end;   /**< one past the last page_seq of the segment */
    uint32_t slot;
    uint32_t remaining;
    store_page_t page;   /**< 2.6 KB; the cursor is never a local */
    uint8_t next;
    bool have_page;
    bool open;
} store_preserve_cursor_t;

/**
 * @brief Start walking @p header's samples.
 *
 * @return false if the segment has no samples left. An open cursor is not a
 *         lock - the body ring may reclaim under it, and ::store_preserve_next
 *         then stops early, which is the honest result.
 */
bool store_preserve_open(const store_preserve_t *p, const store_alert_header_t *header,
                         store_preserve_cursor_t *cursor);

/** @brief The next sample, or false when there are no more. */
bool store_preserve_next(const store_preserve_t *p, store_preserve_cursor_t *cursor,
                         store_reading_t *out);

/**
 * @brief Compute @p header's midpoint from the samples and write it back into it.
 *
 * The one statistic that cannot be accumulated in one pass: the middle of a
 * segment is not known until the segment ends, and holding every value to find
 * it later would make an hour-long excursion cost fourteen kilobytes of RAM. So
 * it is read back off flash once, from the one or two pages that actually cover
 * the middle rather than from all of them.
 *
 * @param scratch a caller-owned ::store_page_t, for the same reason the erase
 *        path has one: a local would overflow the store task's stack.
 * @return false if the samples could not be read, in which case @p header keeps
 *         the segment mean it was given by ::store_alert_seg_header.
 */
bool store_preserve_fill_midpoint(const store_preserve_t *p, store_alert_header_t *header,
                                  store_page_t *scratch);

/** @brief Copy out the counters. */
void store_preserve_get_stats(const store_preserve_t *p, store_preserve_stats_t *out);

/**
 * @brief The newest header sequence written, or 0 if there are none.
 *
 * A field read, deliberately: it is how a caller asks "is there anything past
 * my watermark?" without paying for ::store_preserve_list. That answer is needed
 * on every turn of every client loop, and answering it by scanning the header
 * region would be 64 KB of flash reads several times a second, taken under the
 * store's lock, against the task that writes the readings.
 */
uint32_t store_preserve_newest_seq(const store_preserve_t *p);

/**
 * @brief The largest ring wrap count any surviving header records.
 *
 * The preservation area is outside the ring and is not touched by an erase, so
 * this is how the wrap count survives one. See ::store_wraps.
 */
uint32_t store_preserve_seen_wrap(const store_preserve_t *p);

#ifdef __cplusplus
}
#endif

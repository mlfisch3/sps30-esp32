/**
 * @file store.h
 * @brief The board keeps the record: readings on flash, in a ring.
 *
 * Before this existed the firmware persisted nothing. Not a filesystem, not a
 * partition to hold one - the `store` partition did not exist and the default
 * table left three quarters of the 4 MB part unaddressed. Every reading lived in
 * RAM until a client took it, so anything that happened while no phone was
 * listening was gone, `get_file` answered "this controller has no stored
 * sessions; the phone is the record", and a reboot ended the record.
 *
 * `STORAGE_FORMAT.md` settled how to fix that and this implements it. See
 * `store_codec.h` for the page format and `store.py` for the specification.
 *
 * ## The ring
 *
 * Most of the partition is `STORE_PAGE_BYTES` slots, written strictly in order
 * and wrapping. Each page carries a `page_seq` that never repeats, so age is
 * total and the write head is found at boot by looking for where the sequence
 * wraps. When the ring is full the oldest sector is erased to make room, which is
 * what "the board keeps what it can" means in practice: **2.12 days of continuous
 * 1 Hz**, and months at anything slower.
 *
 * ## Wrapping, and what survives it
 *
 * Wrapping is allowed and expected. The rule that decides every question here is
 * the captain's: **recording incoming data always beats protecting older data.**
 * Nothing in this file stops, throttles or delays acquisition to keep history -
 * a ring that is full wraps, and the recording rate is *never* reduced because
 * the ring filled.
 *
 * What is not allowed to be lost is alert-level readings. Those are copied into
 * a dedicated area at the end of the partition, outside the write region, as
 * they are recorded; and if the ring is about to overwrite any that have not got
 * there yet, they are copied first. See `store_preserve.h` for the area and
 * `store_alert.h` for what "alert-level" means and where the definition came
 * from. The **one** thing that does reduce the recording rate is pressure on
 * that area, which is the last resort and is repeatable - see
 * ::store_set_rate_floor_cb.
 *
 * ## Power loss
 *
 * A page is written once, whole, with a CRC over all 512 bytes. A page that was
 * interrupted fails its CRC and is simply not a page. There is no shutdown
 * ritual to perform and nothing to repair on the way up; the most that can be
 * lost is the page still being filled, about 41 readings at 1 Hz.
 *
 * ## Threading
 *
 * ::store_submit is safe to call from the acquisition task and never blocks on
 * flash: it copies into a queue that the store's own task drains. Everything
 * else is for the network task and takes the store's lock.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "acq.h"
#include "esp_err.h"
#include "store_alert.h"
#include "store_codec.h"
#include "store_drain.h"
#include "store_map.h"
#include "store_merge.h"
#include "store_preserve.h"
#include "store_sessions.h"
#include "store_walk.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * ::store_session_t and the index that keeps them live in `store_sessions.h`.
 * Two things from that file are worth knowing from here:
 *
 * - **A summary goes when its last page goes, and at no other time.** A summary
 *   dropped while its pages are still in flash used to make those readings
 *   unreachable, because nothing else could name them, and made the board
 *   under-report itself by the same amount. That is what a seventeenth session
 *   did at any ring fullness, and then what a thirty-third did.
 * - **The ring is the index; RAM holds a cache of it.** A fixed table cannot
 *   describe every session the ring can hold at any affordable size, so it does
 *   not have to: every page header carries its session's whole identity, and
 *   ::STORE_SESSION_INDEX_MAX being reached makes lookups slower rather than
 *   answers wrong. Nothing below has a ceiling on how many sessions the board
 *   can hold, report, serve or erase.
 */

/** @brief Counters, for the log line and for judging whether it is working. */
typedef struct {
    uint32_t pages_written;
    uint32_t pages_erased;   /**< sectors reclaimed from the tail */
    uint32_t readings_stored;
    uint32_t readings_dropped; /**< the queue overflowed: flash could not keep up */
    uint32_t decode_failures;  /**< pages that did not survive their CRC */
    uint32_t slots;            /**< total slots in the ring */
    uint32_t slots_used;
    /**
     * Completed passes of the write head over the ring.
     *
     * The captain's wrap counter. `page_seq` was already monotonic across a wrap
     * and this does not change that; what it adds is a figure that is **counted
     * and reported** rather than left implicit, so relative-time indexing has
     * something to continue against and a reader can tell "the ring turned over
     * eleven times" from "the ring is on its first pass".
     *
     * It survives a ring erase, because every preserved segment header records
     * the wrap it was written at and the preservation area is outside the ring.
     * See ::store_wraps.
     */
    uint32_t wraps;
    /**
     * Summaries the in-RAM index had to give up its slot for.
     *
     * **Not readings lost, and no longer readings that cannot be named**: the
     * page headers carry every session's identity and the store reads them when
     * this is non-zero, so a session past the index is listed, counted, served
     * and erased exactly like any other. What this counts is how often that
     * slower path has been entered - a board with more than
     * ::STORE_SESSION_INDEX_MAX sessions live at once. Zero on every board so
     * far; see `store_sessions.h`.
     */
    uint32_t sessions_forgotten;
    bool ready;
} store_stats_t;

/**
 * @brief Find the partition, scan the ring and start the writer task.
 *
 * Call before ::acq_start. Failure is not fatal to the firmware - a board that
 * cannot store is still a board measuring and serving - and is reported rather
 * than hidden, because "no stored sessions" must never again be indistinguishable
 * from "storage is broken".
 */
esp_err_t store_start(void);

/**
 * @brief Hand one reading to the store. Never blocks; safe from the acq task.
 *
 * A full queue drops the reading and counts it rather than delaying acquisition,
 * which is the same trade the network transport makes. It should not happen: the
 * queue holds several pages' worth and flash keeps up with 1 Hz by a wide margin.
 */
void store_submit(const acq_reading_t *reading, const char *session_name,
                  int64_t epoch_ms, int16_t utc_offset_min, bool relative);

/* --- recording with no clock ------------------------------------------------ */
/*
 * A board that has never joined a network can still record. It counts on its own
 * clock and says so, and the equivalences below are how that becomes a calendar
 * time later - on the client, not here. This layer's whole contribution is that
 * it only ever **appends**: when a clock arrives, nothing already written is
 * touched. Rewriting stored readings would put data that is already safe back
 * under the write head, and a power cut part way through would corrupt an
 * archive that was fine.
 */

/**
 * @brief Which run of the board this is, for ::store_rel_pack.
 *
 * The page sequence at boot, which is monotonic for the life of the device and
 * recovered by the scan - so no new persistent counter is needed, and two runs
 * cannot collide unless one of them wrote nothing, in which case it left nothing
 * that could be confused. 0 until the scan has finished.
 */
uint32_t store_run_marker(void);

/**
 * @brief Append one equivalence: the board's clock and the world's, together.
 *
 * Written as a page of its own, so it is one slot and an ordinary CRC-protected
 * append. Repeatable on purpose - one per time source that turns up, not only
 * the first - because each is an independent measurement of the same offset and
 * a client can then use the one nearest a given block instead of extrapolating
 * across hours of crystal drift.
 *
 * @param rel_ms the board's clock, in milliseconds within this run.
 * @return ESP_ERR_INVALID_STATE if there is nothing recorded relative to anchor,
 *         which is the ordinary case on a board that has always had a network,
 *         or if one was recorded very recently (see ::STORE_ANCHOR_MIN_GAP_MS).
 */
esp_err_t store_add_anchor(uint32_t rel_ms, int64_t epoch_ms, int16_t utc_offset_min);

/**
 * @brief Least time between two equivalences worth a ring slot.
 *
 * Two measurements a few seconds apart say the same thing twice, and a client
 * that reconnected in a loop would otherwise spend the archive on saying it.
 * A minute is far below the interval at which crystal drift is worth
 * re-measuring and far above any reconnection storm.
 */
#define STORE_ANCHOR_MIN_GAP_MS 60000u

/**
 * @brief Copy out every equivalence the ring holds, oldest first.
 *
 * Includes equivalences from earlier runs, which is the point: they are what
 * lets a relative session recorded before the last reboot still be placed. An
 * equivalence only ever applies within its own run, which the run marker in
 * `rel_ms` makes checkable.
 *
 * @return how many were written.
 */
size_t store_list_anchors(store_anchor_t *out, size_t cap);

/**
 * @brief Seal whatever is in the page being filled, so a client can have it.
 *
 * Called before serving a session, so the readings of the last minute are not
 * missing from a backfill purely because the page they are in is not full yet.
 */
void store_flush(void);

/** @brief Copy out the newest session summaries, newest first. @return how many. */
size_t store_list_sessions(store_session_t *out, size_t cap);

/**
 * @brief One page of the listing, newest first, for a wire with finite frames.
 *
 * The board holds more sessions than any one `file_list` can name, and a session
 * nobody can name is one nobody can fetch, plot or offer to the erase gate. So
 * the listing is walked: ask with @p offset 0, then with @p offset advanced by
 * what came back, until the walk runs out.
 *
 * @param offset how many of the newest to step over; past the end writes nothing.
 * @param total_out if not NULL, receives what ::store_held would report for
 *        sessions - the same number, from the same code, because a client
 *        compares its archive against it to know whether the copy is complete.
 * @return how many were written.
 */
size_t store_list_sessions_page(store_session_t *out, size_t cap, uint32_t offset,
                                uint32_t *total_out);

/**
 * @brief What the board holds, in one call: readings and sessions.
 *
 * What `storage` is filled from. Separate from ::store_list_sessions because
 * the answer is two numbers and the list is 2.3 KB of a client task's stack -
 * and because the two must agree by construction. They did not: the totals used
 * to be summed over a list that had already had a summary shifted out of it, so
 * the readings the board reported fell by 12,975 at the moment it started
 * holding *more* of them.
 *
 * Both figures are now about the ring rather than about the index over it, so
 * they are right at any number of sessions. Either pointer may be NULL.
 */
void store_held(uint32_t *readings, uint32_t *sessions);

/** @brief Look one session up by its protocol name. */
bool store_find_session(const char *name, store_session_t *out);

/**
 * @brief Start walking the readings of @p name past @p since_index.
 *
 * ::store_cursor_t and the walk itself live in `store_walk.h`, which is where
 * the reason a `get_file` no longer reads the whole ring is written down. This
 * pair is the wiring: the partition, the lock and the session index.
 *
 * @return false if there is no such session. An open cursor is not a lock:
 *         the ring may overwrite pages under it, and ::store_cursor_next simply
 *         stops early if that happens, which is the honest result.
 */
bool store_cursor_open(store_cursor_t *cursor, const char *name, uint32_t since_index);

/** @brief The next reading, or false when there are no more. */
bool store_cursor_next(store_cursor_t *cursor, store_reading_t *out);

/** @brief Copy out the counters. */
void store_get_stats(store_stats_t *out);

/** @brief True once ::store_start has a partition and a scanned ring. */
bool store_ready(void);

/**
 * @brief How much of the ring holds pages, 0 to 1.
 *
 * What the auto-export threshold is measured against. Slots holding a page, not
 * bytes of reading: a slot is spoken for whether its page is full or not, and
 * the thing that runs out is slots.
 */
float store_fraction_used(void);

/* --- the wrap counter ------------------------------------------------------ */

/**
 * @brief Completed passes of the write head over the ring.
 *
 * Monotonic for the life of the board. Recovered at boot as the larger of what
 * the ring's own `page_seq` implies and the largest wrap any surviving preserved
 * segment header records - the second of which is why an erase of the ring does
 * not reset it, since the preservation area is not part of the ring and an erase
 * never touches it.
 *
 * The one case that does reset it is an override erase on a board that has never
 * preserved a segment, and resetting is the right answer there: that gesture
 * means "this board is being cleared".
 */
uint32_t store_wraps(void);

/**
 * @brief A position in the ring that only ever increases: `wraps * slots + head`.
 *
 * What "relative time indexing continues across wraps instead of resetting"
 * means concretely. A caller that needs to order two observations of the ring -
 * including one taken before a wrap and one after - compares these.
 */
uint64_t store_ring_epoch(void);

/* --- the occupancy map ------------------------------------------------------ */

/**
 * @brief Pages a `store` partition can hold: bounds ::store_map_report_t::cells.
 *
 * The shipping partition is 4,992 pages; this leaves room for the partition to
 * grow to 4 MB before the report struct has to. A bound rather than the exact
 * figure because the geometry is read from the partition table at boot, not
 * compiled in.
 */
#define STORE_MAP_MAX_PAGES 8192u

/** @brief One region of the partition, as the occupancy map reports it. */
typedef struct {
    const char *id;       /**< a `WIRE_MAP_SECTION_*` literal */
    uint32_t start;       /**< first page, in whole-partition page numbers */
    uint32_t pages;       /**< pages in this region */
    uint32_t head;        /**< page the next write goes into, within the region */
    uint32_t wraps;       /**< completed passes of the region's write position */
    const uint8_t *cells; /**< packed 2-bit states; points into the report */
} store_map_region_t;

/**
 * @brief The whole partition: geometry, write positions, a state per page.
 *
 * About 2.3 KB. Callers heap-allocate one per answer and free it with the
 * frame, the same borrow discipline as the big output buffer it is encoded
 * into - a static one would be shared state between two clients asking at
 * once, for a message that is asked for a few times a minute at most.
 */
typedef struct {
    uint32_t page_bytes;
    uint32_t pages; /**< pages in the whole partition */
    size_t region_count;
    store_map_region_t regions[3];
    uint8_t cells[STORE_MAP_CELLS_BYTES(STORE_MAP_MAX_PAGES)];
} store_map_report_t;

/**
 * @brief Classify every page of the partition for the occupancy map.
 *
 * A read of the ring that changes nothing: no flush, no seal, no erase - the
 * open page still being filled is RAM, so its slot honestly reads as whatever
 * the flash under it holds. The header walk runs without the store's lock,
 * exactly as a `get_file` walk does; a page the writer lands mid-pass reads as
 * whatever its header said at that moment, which the next refresh corrects.
 *
 * @return false before ::store_start has a partition and a scanned ring, or if
 *         the partition outgrew ::STORE_MAP_MAX_PAGES.
 */
bool store_map_report(store_map_report_t *out);

/* --- preserving alert-level readings --------------------------------------- */

/**
 * @brief Move the preservation line for one field.
 *
 * The **only** thing about alerts a client tells the controller, and it is a
 * retention policy rather than a notification setting: no rule identity, no
 * label, no reaction, nothing per user. A client with several enabled rules
 * sends the **minimum** of their thresholds for each field, because preserving a
 * superset of what any rule would fire on is right and preserving less is not.
 * See `store_alert.h` for the whole argument.
 *
 * A @p threshold of zero stops watching @p field. Takes effect on the next
 * reading; it does not reach back and re-file what is already stored - but it
 * does change what the sweep in front of the erase rescues, so lowering the line
 * still saves alert-level readings that are already in the ring and have not yet
 * been overwritten.
 */
esp_err_t store_alert_set_threshold(int field, float threshold);

/** @brief Copy out the policy in force. */
void store_alert_get_policy(store_alert_policy_t *out);

/** @brief Copy out the preservation area's counters. */
void store_alert_get_stats(store_preserve_stats_t *out);

/**
 * @brief Seal whatever segment is open, so a client can have it.
 *
 * The counterpart of ::store_flush, and called for the same reason: an excursion
 * that is still under way should not be invisible to a device that has connected
 * to ask about it. The segment stays open and keeps accumulating; what this does
 * is commit the samples so far and write a header describing them.
 */
void store_alert_flush(void);

/** @brief Copy out segment headers past @p since_seq, oldest first. @return how many. */
size_t store_alert_list(store_alert_header_t *out, size_t cap, uint32_t since_seq);

/** @brief Look one preserved segment up by its header sequence. */
bool store_alert_find(uint32_t seq, store_alert_header_t *out);

/**
 * @brief The newest preserved segment's header sequence, or 0 if there are none.
 *
 * Cheap, and that is the point: it is how a caller decides whether to call
 * ::store_alert_list at all. The list reads the whole header region, and a
 * client loop that called it every turn to discover it was owed nothing would
 * spend a large fraction of its time holding the store's lock against the task
 * that writes the readings.
 */
uint32_t store_alert_newest_seq(void);

/** @brief Start walking the samples of the segment with header sequence @p seq. */
bool store_alert_cursor_open(store_preserve_cursor_t *cursor, uint32_t seq);

/** @brief The next preserved sample, or false when there are no more. */
bool store_alert_cursor_next(store_preserve_cursor_t *cursor, store_reading_t *out);

/** @brief How full the preservation area's sample region is, 0 to 1. */
float store_alert_pressure(void);

/**
 * @brief The recording interval the store is currently insisting on, in ms.
 *
 * Zero when it is insisting on nothing, which is the normal state. Non-zero only
 * under pressure on the **preservation area**; a full ring never produces one.
 */
uint32_t store_rate_floor_ms(void);

/** @brief Which rung of ::store_alert_rate_floor_ms is in force, 0 for none. */
uint8_t store_rate_level(void);

/**
 * @brief Be told when the store needs the recording interval raised or released.
 *
 * The store decides *that* the rate must change and never decides *how*: raising
 * an interval is acquisition's business, and a storage component that reached
 * into the acquisition profile would be a second place that sets it. `main.c`
 * registers the one that does, which is also the only place that can honour
 * "never shorter than what the user asked for".
 *
 * @param fn called with the new floor in milliseconds, zero to release it.
 *        Runs on the store task, so it must not block on flash.
 */
typedef void (*store_rate_floor_fn)(uint32_t floor_ms, void *ctx);
void store_set_rate_floor_cb(store_rate_floor_fn fn, void *ctx);

/* --- taking a session back in ---------------------------------------------- */
/*
 * The import side. `store_merge.h` is the merge itself and carries the argument
 * for why it cannot lose a reading; this is the half that knows about flash, and
 * its whole job is to be the ::store_sink_t that **refuses** rather than the one
 * that makes room. Nothing here erases anything.
 *
 * One import at a time, for the whole controller rather than per client. An
 * import is a deliberate act on a shared archive, two at once on overlapping
 * sessions would each be working from a held set the other was invalidating, and
 * the working memory is 2 KB that would otherwise be multiplied by eight.
 */

/**
 * @brief Begin importing into the session called @p name.
 *
 * If the controller already holds a session by that name, its identity is used
 * and this becomes a merge into it. If it does not, @p session_start_ms and
 * @p utc_offset_min define a new one - and they must be the values that make
 * ::wire_csv_name produce @p name, because a session's name is re-derived from
 * its pages after every reboot and a name that cannot be re-derived is a session
 * that renames itself overnight.
 *
 * Checking the claim and taking it happen under one hold of the store's lock,
 * so two clients that arrive together cannot both be told they may proceed. One
 * of them gets ESP_ERR_INVALID_STATE and nothing of theirs is written.
 *
 * @param owner the client id, so a disconnect can abandon its own import and
 *              only its own.
 * @return ESP_ERR_INVALID_STATE if storage is not ready or another import is
 *         running, ESP_ERR_NO_MEM if the session is too fragmented for the held
 *         set (see ::STORE_MERGE_MAX_SPANS), otherwise ESP_OK.
 */
esp_err_t store_import_open(int owner, const char *name, int64_t session_start_ms,
                            int16_t utc_offset_min);

/**
 * @brief Offer one reading to the open import.
 *
 * ::STORE_MERGE_NOT_OPEN means @p owner holds no import and the reading was
 * never examined. It appears in no ::store_merge_stats_t, so the caller has to
 * count it or the row vanishes from the report as well as from the store.
 */
store_merge_verdict_t store_import_add(int owner, const store_reading_t *reading);

/** @brief Seal what is open and hand back the counters. @return false if not open. */
bool store_import_close(int owner, store_merge_stats_t *out);

/** @brief Drop an import without reporting, for a client that went away. */
void store_import_abandon(int owner);

/** @brief Whether @p owner has an import open. */
bool store_import_active(int owner);

/* --- erasing, and the proof it needs --------------------------------------- */

/**
 * @brief One session a device says it holds a complete copy of.
 *
 * `readings` and `digest` are the proof. The controller recomputes both from its
 * own pages and erases nothing whose claim does not match exactly.
 */
typedef struct {
    char name[32];
    uint32_t readings;
    uint32_t digest; /**< ::store_digest_fold over ::store_digest_reading */
} store_claim_t;

/** @brief What an erase did. */
typedef struct {
    uint32_t sessions_confirmed;
    uint32_t sessions_refused;
    uint32_t pages_erased;
    uint32_t readings_erased;
    /** Pages kept because their sector also held something unconfirmed. */
    uint32_t pages_kept_by_sector;
    char message[96];
} store_erase_result_t;

/**
 * @brief Erase what the requesting device can prove it already has.
 *
 * The only path in this firmware that removes a reading on purpose, and it is
 * built so that it cannot remove one that is not somewhere else first:
 *
 * 1. Each claim is checked by recomputing the digest and the count over the
 *    controller's own pages. A session whose claim does not match is refused and
 *    keeps every page it has.
 * 2. Flash erases a sector at a time, so a sector goes only when **every** page
 *    in it belongs to a confirmed session. A sector holding one page of a
 *    session that is still recording is kept whole, and the pages in it that
 *    were confirmed are kept with it. Erring towards keeping data is the only
 *    direction this is allowed to err in.
 *
 * @param override skip the proof entirely. The obscure gesture behind which
 *        `HANDOFF.md` item 13 puts an unconditional erase - there for a board
 *        that has to be cleared and cannot be backed up, and reachable only from
 *        a deliberate, hard-to-hit interaction.
 */
esp_err_t store_erase_confirmed(const store_claim_t *claims, size_t count, bool override,
                                store_erase_result_t *out);

/* --- backing everything up and clearing the board -------------------------- */
/*
 * A **separate** path from ::store_erase_confirmed, which is unchanged and
 * behaves exactly as it did. The rule it is built on is not weakened anywhere
 * here: nothing is freed that the asking device has not proved it holds. What is
 * different is the grain and the interleaving - a block at a time, freed as soon
 * as its own copy is proved, so acquisition carries on into space it would have
 * used anyway and there is no moment at which readings are dropped.
 *
 * The block rule, the order the three regions are drained in, and why there is
 * nothing persistent to resume from are all in `store_drain.h`. Everything below
 * is the partition, the lock, and keeping the rest of `store.c` honest about
 * what has just gone.
 *
 * One at a time for the whole controller, and it does not run alongside an
 * import: see ::store_drain_open.
 */

/** @brief Where a drain has got to, for the wire and for the log. */
typedef struct {
    bool open;
    bool finished;
    uint32_t blocks_left;
    uint32_t blocks_freed;
    uint32_t blocks_kept;
    uint32_t blocks_refused;
    uint32_t readings_copied;
    uint32_t segments_copied;
    uint32_t readings_freed;
} store_drain_progress_t;

/**
 * @brief Begin a drain for @p owner. Frees nothing; reads nothing.
 *
 * Seals the page being filled and closes any excursion still accumulating, so
 * everything the board holds is on flash before a single block is chosen.
 *
 * @return ESP_ERR_INVALID_STATE if storage is not ready, an import is running,
 *         or another device is already draining. ESP_OK for the owner asking
 *         again, which is what a reconnect looks like.
 */
esp_err_t store_drain_open(int owner);

/** @brief End @p owner's drain. Deletes nothing; safe at any moment. */
void store_drain_close(int owner);

/** @brief Whether @p owner holds the open drain. */
bool store_drain_running(int owner);

/** @brief Copy out where it has got to. Answers for a closed drain too. */
void store_drain_progress(store_drain_progress_t *out);

/** @brief Choose and offer the oldest block still held. @return false when none. */
bool store_drain_take(int owner, store_drain_block_t *out);

/**
 * @brief Hand @p block's rows to a callback, skipping the first @p from_row.
 *
 * One of @p on_reading and @p on_segment is used, by the block's kind. Returning
 * false from either stops the walk, which is how a full wire frame ends.
 *
 * @return how many rows were emitted.
 */
uint32_t store_drain_rows(int owner, const store_drain_block_t *block, uint32_t from_row,
                          store_drain_reading_fn on_reading, store_drain_segment_fn on_segment,
                          void *ctx);

/**
 * @brief Record what was streamed for @p block, measured from flash.
 *
 * Called once the last frame of a block has gone. A confirmation is held to
 * this, so a block that was never fully sent can never be confirmed.
 */
bool store_drain_seal(int owner, const store_drain_block_t *block, uint32_t *rows,
                      uint32_t *digest);

/**
 * @brief The device claims it holds block @p id. Free it if that is true.
 *
 * See ::store_drain_confirm for the three things that have to agree first.
 * ::STORE_DRAIN_KEPT is the last block, after which the board's whole account of
 * itself is rebuilt from flash - which is what makes the end state a properly
 * initialised store rather than an emptied one with stale metadata.
 */
store_drain_verdict_t store_drain_answer(int owner, uint32_t id, uint32_t rows,
                                         uint32_t digest);

#ifdef __cplusplus
}
#endif

/**
 * @file store_merge.h
 * @brief Taking readings back in without ever losing one. Pure code.
 *
 * The controller can hand a session to a phone (`get_file`) and the phone can
 * hand it back (`put_file`). Handing it back is the dangerous direction, and
 * this file is the whole of why it is safe.
 *
 * ## The rule
 *
 * **An import adds; it never removes and never overwrites.** A reading already
 * held wins over an identical-looking one arriving from a file, always. Nothing
 * is erased to make room. If the ring cannot take what is arriving, the import
 * stops and says so, with everything that was already there untouched.
 *
 * That is not a policy applied on top of a general-purpose merge - it is what
 * the code below can physically do. Existing pages are never opened for writing,
 * never rewritten, never erased by anything here. Imported readings can only
 * ever become **new pages in slots that hold no page**, and ::store_sink_t is
 * the only way one reaches flash. A sink that refuses is the end of the import,
 * not a prompt to try harder.
 *
 * ## What "already held" means
 *
 * Within a session a reading is identified by its `index`, 1-based and dense:
 * ::store_writer_add refuses a reading that is not the next one, so **every page
 * covers a contiguous run of indices**, `first_index` to `first_index + count -
 * 1`. The set of indices a session holds is therefore a handful of intervals,
 * not a bitmap, and ::store_held_t is that set. Containment is the duplicate
 * test, and it costs a walk over a few spans rather than a scan of flash per
 * reading.
 *
 * Index is the identity the whole system already uses: the app de-duplicates on
 * `(csv, index)` and the protocol's `since_index` watermark is a statement about
 * it. Timestamps agree with it by construction - within one session index
 * determines the instant - so "keep the one we have when two records claim the
 * same instant" and "keep the one we have when two records claim the same index"
 * are the same rule here, and the second is the one that can be checked without
 * reading every page back.
 *
 * The spans are built from page **headers**, which is what makes preparing a
 * merge cheap: forty-eight bytes a slot, the same read the boot scan already
 * does.
 *
 * ## Refusing rather than guessing
 *
 * ::STORE_MERGE_MAX_SPANS bounds how fragmented a session may be and still be
 * merged into. Overflow is reported, not absorbed: coalescing two spans across a
 * gap would claim readings are held that are not, and the import would then
 * skip exactly the readings it exists to restore. A refusal loses nothing and
 * can be retried; a silent under-import looks like success.
 *
 * ## Where the flash is
 *
 * Nowhere. No ESP-IDF, no partition, no lock, no clock - the caller supplies
 * page sequence numbers and takes sealed pages away through ::store_sink_t. That
 * is what lets `firmware/test/test_store_merge.c` run this exact translation
 * unit over a simulated ring and count, page by page, that an import of a
 * session the controller already holds writes nothing at all.
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
 * @brief Runs of held indices a session may have and still be merged into.
 *
 * One run per unbroken stretch of recording, so a session recorded without a
 * dropout is one span however long it is. A gap appears when acquisition
 * stalled, when a page failed its CRC, or when the ring overwrote the middle of
 * a session, and 128 of those is far past what any real session shows while
 * costing a kilobyte of the import's working memory.
 */
#define STORE_MERGE_MAX_SPANS 128

/** @brief An inclusive run of reading indices. */
typedef struct {
    uint32_t first;
    uint32_t last;
} store_span_t;

/**
 * @brief The set of indices one session already holds, as sorted spans.
 *
 * Kept sorted and disjoint by ::store_held_add so containment is a walk that
 * can stop early, and so two pages that happen to abut become one span rather
 * than two.
 */
typedef struct {
    store_span_t spans[STORE_MERGE_MAX_SPANS];
    uint16_t count;
    /** Set once a span could not be recorded. The set is now incomplete. */
    bool overflowed;
} store_held_t;

/** @brief Empty the set. */
void store_held_reset(store_held_t *held);

/**
 * @brief Record that `[first, last]` is held, merging into any span it touches.
 *
 * @return false if there was no room, in which case @p held is left consistent
 *         but incomplete and `overflowed` is set. A caller must treat that as a
 *         reason to refuse the import, never as a reason to carry on.
 */
bool store_held_add(store_held_t *held, uint32_t first, uint32_t last);

/** @brief Whether @p index is already held. */
bool store_held_contains(const store_held_t *held, uint32_t index);

/** @brief How many indices the set covers. */
uint32_t store_held_readings(const store_held_t *held);

/**
 * @brief Where a sealed page goes, and what number it carries.
 *
 * The one route from an import to flash, so "an import never evicts" is a
 * property of whoever implements this rather than a hope about the code above.
 * ::commit returning false means **there is no free slot**; the merge then stops
 * cleanly and reports it. It must never mean "I made room".
 */
typedef struct {
    /** The `page_seq` to stamp on the next page. Must increase. */
    uint32_t (*next_seq)(void *ctx);
    /** Take one sealed page. False = no free slot; nothing was evicted. */
    bool (*commit)(void *ctx, const uint8_t page[STORE_PAGE_BYTES]);
    void *ctx;
} store_sink_t;

/** @brief What became of one offered reading. */
typedef enum {
    STORE_MERGE_ADDED = 0,     /**< it was missing; it is now held */
    STORE_MERGE_DUPLICATE,     /**< already held; the held one was kept */
    STORE_MERGE_REJECTED,      /**< malformed, or not of this session */
    /**
     * There is no slot for it; the import must stop.
     *
     * Counted in `stats.rejected` like every other reading that was not stored,
     * so `added + duplicate + rejected` always accounts for every row offered.
     * It used to return without counting, which left the rows after the ring
     * filled in no number at all.
     */
    STORE_MERGE_NO_ROOM,
    /**
     * There was no import of this caller's to offer it to.
     *
     * Never returned by ::store_merge_add - it is ::store_import_add's answer
     * when the claim is not the caller's, and it exists because that is the one
     * outcome no `stats` can record. The reading was not examined, not counted
     * and not stored, so a caller that treats it as ::STORE_MERGE_REJECTED is
     * reporting a delivery that did not happen.
     */
    STORE_MERGE_NOT_OPEN,
} store_merge_verdict_t;

/** @brief Counters for the answer the user is owed. */
typedef struct {
    uint32_t added;
    uint32_t duplicate;
    uint32_t rejected;
    uint32_t pages;    /**< pages written; zero for a pure re-import */
    /** The ring had no free slot. Nothing was evicted; the import stopped. */
    bool no_room;
    /**
     * The held set overflowed ::STORE_MERGE_MAX_SPANS.
     *
     * Containment can no longer be trusted to say yes when it should, so the
     * merge stops rather than risk writing a second copy of a reading it can no
     * longer see. Everything accepted before this stays: it is all additive.
     */
    bool fragmented;
} store_merge_stats_t;

/** @brief One session's import in progress. */
typedef struct {
    store_held_t held;
    store_writer_t writer;
    bool writer_open;
    uint32_t session;
    int64_t session_start_ms;
    uint32_t cadence_ms;
    int16_t utc_offset_min;
    /** The last index admitted, so an out-of-order file is caught. */
    uint32_t last_added;
    store_merge_stats_t stats;
} store_merge_t;

/**
 * @brief Start a merge into the session identified by @p session and @p start.
 *
 * Both halves of the identity are needed: the session number restarts at 1 on
 * every boot, so the start instant is what stops one boot's session three from
 * being merged into another's.
 */
void store_merge_begin(store_merge_t *merge, uint32_t session, int64_t session_start_ms,
                       uint32_t cadence_ms, int16_t utc_offset_min);

/**
 * @brief Declare that @p count indices from @p first_index are already held.
 *
 * Called once per page of the session, from its header, before any reading is
 * offered. @return false on span overflow - refuse the import.
 */
bool store_merge_note_held(store_merge_t *merge, uint32_t first_index, uint32_t count);

/**
 * @brief Offer one reading.
 *
 * A reading already held is counted and dropped, which is the whole of "keep the
 * controller's copy". A reading that is missing is packed into the page being
 * built, and a full page is sealed through @p sink. A sink that refuses ends the
 * import: ::STORE_MERGE_NO_ROOM is returned, `stats.no_room` is set, and the
 * reading is **not** counted as added, because it was not.
 */
store_merge_verdict_t store_merge_add(store_merge_t *merge, const store_reading_t *reading,
                                      const store_sink_t *sink);

/**
 * @brief Seal whatever is still open. @return false if the sink refused it.
 *
 * Must be called even when the import is being abandoned, so the readings
 * already accepted are not left in RAM. There is nothing to roll back: what was
 * committed is committed, and every page of it is additive.
 */
bool store_merge_finish(store_merge_t *merge, const store_sink_t *sink);

/**
 * @brief Whether the page the sensor is still filling has to be sealed first.
 *
 * A merge builds its held set from page **headers**, so a reading that is in
 * RAM waiting for its page to fill is a reading the merge cannot see - and a
 * reading it cannot see is one it will write a second copy of. Sealing the open
 * page before the held set is built is the answer, and that is why
 * `store_import_open` does it.
 *
 * It only helps for **one** session, though: the one the open page belongs to.
 * Sealing it before an import of any *other* session proves nothing about that
 * import and costs a whole ring page for however few readings were in it - and
 * an archive is one import per session, so a sixty-four session file sent back
 * sealed sixty-four partial pages. Measured across the pass of 2026-08-04: the
 * ring went from 75% to 95% while the readings recorded accounted for barely
 * half of it, and the rest went during transfers.
 *
 * So the rule, and the reason it lives here rather than in `store.c`: it is a
 * precondition of the merge, it can be stated without a partition, and the
 * counting argument above is worth a test.
 *
 * @param writer_open whether the sensor has a page open at all. An empty writer
 *                    is nothing to seal and nothing to miss.
 * @param writer_name the session that open page belongs to; may be NULL.
 * @param import_name the session about to be merged into; may be NULL.
 */
bool store_merge_needs_seal(bool writer_open, const char *writer_name, const char *import_name);

/* --- proving a copy exists elsewhere --------------------------------------- */

/**
 * @brief A digest of one reading that a phone can compute from exported CSV.
 *
 * The erase gate turns on the two sides agreeing about what is held, so this
 * has to be computable from what actually crosses the wire. It therefore digests
 * the reading as **exported**: the index, the instant in whole **seconds**, the
 * settling flag, and the ten values as their quantised integers.
 *
 * Seconds and not milliseconds because the protocol's timestamp is ISO 8601 to
 * the second and there is nowhere in an exported row for the rest. Quanta and
 * not floats because `%.2f` (`%.3f` for particle size) is exactly the store's
 * own quantum, so the decimal in the file and the integer in flash are the same
 * number and the two digests cannot disagree over rounding.
 */
uint32_t store_digest_reading(const store_reading_t *reading);

/**
 * @brief Fold one reading's digest into a running one.
 *
 * Addition, so the result does not depend on the order readings are seen in.
 * The controller reads a session in page order and a phone reads its CSV in
 * index order; after an import those are not the same order, and a digest that
 * cared would refuse an erase that is perfectly well backed up.
 *
 * This is an integrity check against loss and corruption, not a signature: it
 * answers "does the phone hold this data" and nothing about who is asking.
 */
static inline uint32_t store_digest_fold(uint32_t acc, uint32_t one)
{
    return acc + one;
}

#ifdef __cplusplus
}
#endif

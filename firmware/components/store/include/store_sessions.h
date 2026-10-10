/**
 * @file store_sessions.h
 * @brief What the board says it holds: one summary per stored session. Pure code.
 *
 * The ring in `store.c` is the record; this is the index over it. A client asks
 * "what have you got" and gets this index, asks for one of them by name and gets
 * a cursor built from it, and is told how much is stored from its totals. So a
 * session this file cannot describe is not merely mis-described - it used to make
 * those readings unreachable, because nothing else could name them.
 *
 * ## The rule
 *
 * **A summary goes when its last page goes, and at no other time.** The ring
 * reclaims a sector, the pages in it are unfolded from the summaries they were
 * folded into, and a summary that reaches zero pages is dropped.
 *
 * That is not how it used to work. The table was a fixed sixteen and a
 * seventeenth session shifted the oldest summary out, on the reasoning that
 * "its pages are the ones the ring is about to reclaim, so nothing that will
 * still be here is being forgotten". That reasoning holds only when the ring is
 * near full. Measured on the board on 2026-08-04: one reboot made a seventeenth
 * session, a **12,975-reading session vanished from the listing**, the reported
 * total fell 84,801 to 73,098, and `used_pages` **rose** 2977 to 3020 with the
 * ring one third empty. The pages were never reclaimed; they were still in
 * flash, unreachable, and uncounted.
 *
 * ## Why there are two halves to this file
 *
 * Raising the table from sixteen to thirty-two moved that failure rather than
 * removing it: on 2026-08-04 the demo board reached thirty-two, a thirty-third
 * session shifted the oldest summary out, and `SPS30-20260803_002028.csv` became
 * unexportable with a quarter of the ring free. **A fixed table cannot fix this,
 * at any size.** The ring is ~4,480 slots and a session needs one page, so the
 * worst case is ~4,480 summaries - about 180 KB of a part that has under 100 KB
 * of DRAM free once Wi-Fi is up. There is no number that is both large enough
 * and affordable.
 *
 * So the summary lives where it *can* grow with the ring: **in the ring**. Every
 * page header already carries the whole identity and shape of its session -
 * `session`, `session_start_ms`, `utc_offset_min`, `cadence_ms`, `first_index`,
 * `count`, `relative` - which is exactly what the boot scan has always rebuilt
 * the summaries from and nothing else. The ring therefore *is* the session
 * index, and this file provides two views of it:
 *
 * - **The table** (::store_sessions_t): a bounded, in-RAM *cache*, folded as
 *   pages are written and unfolded as they are reclaimed. Complete on any board
 *   with at most ::STORE_SESSION_INDEX_MAX live sessions, which is every board
 *   anyone has run, and free to consult.
 * - **The scan** (::store_sessions_scan_find and friends): the same summaries
 *   derived straight from page headers, over any number of sessions, in constant
 *   memory. Slower - one pass over the headers - and used only when the cache
 *   says it is ::store_sessions_t::incomplete.
 *
 * The cache being full is then a performance event and not a correctness one. It
 * does not decide what the board holds, what it reports, or what it can serve.
 *
 * ## Order
 *
 * The table is kept sorted by the **newest page each session holds**, ascending.
 * That is the ring's own order of destruction: the session whose newest page is
 * oldest is the one the ring will finish reclaiming first. So `slot[0]` is
 * always the next session to go and the list runs newest-first off the end,
 * which is the order a truncated listing wants.
 *
 * Insertion order is not that order. The boot scan walks slots, and after a wrap
 * slot zero holds a page newer than the last slot, so "the first summary made"
 * and "the oldest session" were only accidentally the same thing.
 *
 * The scan cannot use that order - a page's sequence says where *it* sits in the
 * ring, not where its session's newest page does - so it orders by
 * ::store_session_key_t, when the session started. See that type for why the
 * order has to be readable off a single page header.
 *
 * Pure C over caller-owned memory: no flash, no lock, no clock. The host pack
 * drives both halves directly - see `test/test_store_sessions.c`.
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
 * @brief Sessions the in-RAM cache describes at once.
 *
 * **Not a ceiling on how many sessions the board can hold, report or serve.**
 * That was what it meant when this was the only index there was, and it is what
 * made a thirty-third session lose a session. It is now the size of a cache in
 * front of the ring: past it, ::store_sessions_t::incomplete goes true and
 * callers ask the ring instead, which has no such limit.
 *
 * Thirty-two, then, is chosen for how often the slow path runs rather than for
 * what is reachable. The ring holds about 2.12 days of continuous 1 Hz, so the
 * question is how many times a board can be restarted inside one ring lifetime;
 * thirty-two is two days of development restarts, and it costs 32 * ~72 B of the
 * store's BSS. A board past it is correct and a little slower, not lossy.
 */
#define STORE_SESSION_INDEX_MAX 32

/** @brief What the store knows about one stored session. */
typedef struct {
    uint32_t session;         /**< acquisition session number */
    int64_t start_ms;         /**< epoch ms of its first reading */
    int16_t utc_offset_min;   /**< offset in force when it started */
    uint32_t cadence_ms;
    uint32_t first_index;     /**< lowest index still held, not always 1 */
    uint32_t last_index;      /**< highest index held */
    uint32_t readings;        /**< how many are actually held */
    uint32_t pages;
    /**
     * The `page_seq` span its pages occupy: lowest folded in, highest folded in.
     *
     * **Where the session is in the ring**, which is the one thing the summary
     * used not to say. `page_seq` and ring slot are the same number offset by a
     * constant (see `store_walk.h`), so this is what lets `get_file` open at the
     * session's own pages instead of at the oldest slot in the ring - the
     * difference between reading 4,480 slots for twenty rows and reading nine.
     *
     * A **widening** bound, never a narrowing one, and every user must treat it
     * as one. ::store_sessions_forget deliberately leaves both alone when a page
     * goes, so the span outlives the pages at its edges rather than ever
     * excluding one that is still there. Its only correct use is skipping slots
     * that provably cannot hold a page of this session.
     */
    uint32_t first_seq;
    uint32_t last_seq;
    char name[32];            /**< "SPS30-YYYYmmdd_HHMMSS.csv", the protocol's key */
    /**
     * Its times are the board's own clock, not instants.
     *
     * True when the session was recorded with no clock. `start_ms` is then a
     * packed relative-clock reading (::store_rel_pack) and means nothing on a
     * calendar until it is put through an equivalence - see `store.h`'s
     * `store_list_anchors`. The name is `SPS30-Rxxxxxxxx-xxxxxxxx.csv` rather
     * than a date, because there is no date to write and a fake one reads as
     * 1970 forever after.
     */
    bool relative;
} store_session_t;

/**
 * @brief The cache. Caller-owned; ::store_sessions_reset before first use.
 *
 * The array is sorted by ::store_session_t::last_seq, which is a property of the
 * ring rather than of the session, and that is the point: the question the
 * ordering answers is "which of these does the ring destroy first", not "which
 * started first". `slot[0]` is therefore always the next summary to go.
 */
typedef struct {
    store_session_t slot[STORE_SESSION_INDEX_MAX];
    size_t count;
    /**
     * Summaries dropped while their pages were still in the ring.
     *
     * Kept as a counter because it is the one thing here worth noticing: it says
     * the board has had ::STORE_SESSION_INDEX_MAX sessions live at once and is
     * answering from the ring rather than from RAM. It is no longer a count of
     * readings lost - see ::incomplete.
     */
    uint32_t forgotten;
    /**
     * The ring may hold sessions this cache does not describe.
     *
     * Set the first time a summary has to be dropped while its pages are live,
     * and the whole contract of this flag is what a caller must do about it: a
     * ::store_sessions_find that answers NULL, a ::store_sessions_list that
     * comes up short and a ::store_sessions_readings total are all then "what
     * RAM knows", not "what the board holds". The ring is the authority and the
     * scan functions below read it.
     *
     * Cleared only by ::store_sessions_reset, which is what an erase and a boot
     * rebuild do: a cache that has been rebuilt from flash is complete again if
     * what is left fits.
     */
    bool incomplete;
} store_sessions_t;

/** @brief Empty the cache. */
void store_sessions_reset(store_sessions_t *t);

/**
 * @brief Fold one page's header in, making the summary if it is the first.
 *
 * Sessions are keyed on **number and start time together**. The number restarts
 * at 1 on every boot, so after a reboot the new session and the one before it
 * are both session 1 - and keying on the number alone merged them into one
 * summary and made a cursor over the old session hand out readings from the new.
 *
 * @param name the session's protocol name, or NULL to derive it from @p page.
 *             The derivation is a pure function of the header either way, which
 *             is what lets a name survive the reboot that re-derives it.
 * A full cache makes room by dropping the summary the ring will finish
 * reclaiming first and setting ::store_sessions_t::incomplete. That is a slower
 * lookup for the session dropped, not a lost one - see that flag.
 *
 * @return the summary the page went into, or NULL if @p page is an equivalence,
 *         which belongs to a run rather than to a session.
 */
const store_session_t *store_sessions_note(store_sessions_t *t,
                                           const store_header_t *page, const char *name);

/**
 * @brief Unfold a page the ring has just erased.
 *
 * The other half of ::store_sessions_note, and the only thing that removes a
 * summary while the cache has room. Call it for every page in a sector that has
 * been reclaimed, having read the headers **before** the erase.
 *
 * @return true if that was the session's last page and its summary went with it.
 */
bool store_sessions_forget(store_sessions_t *t, const store_header_t *page);

/**
 * @brief Copy summaries out, newest first. @return how many were written.
 *
 * Newest first is the order the app wants, and the order that makes a truncated
 * list the useful half rather than the stale half. Short of what the board holds
 * when ::store_sessions_t::incomplete - use ::store_sessions_scan_window then.
 */
size_t store_sessions_list(const store_sessions_t *t, store_session_t *out, size_t cap);

/**
 * @brief ::store_sessions_list from a newest-first offset, for a paged listing.
 *
 * @param skip how many of the newest to step over; past the end writes nothing,
 *        which is the honest answer rather than an error - a listing can shrink
 *        under a client that is part way through walking it.
 */
size_t store_sessions_list_from(const store_sessions_t *t, uint32_t skip,
                                store_session_t *out, size_t cap);

/**
 * @brief Look one up by its protocol name, or NULL.
 *
 * NULL means "not in RAM", which is only the same as "not held" while
 * ::store_sessions_t::incomplete is false. ::store_sessions_scan_find answers
 * the same question against the ring.
 */
const store_session_t *store_sessions_find(const store_sessions_t *t, const char *name);

/** @brief Readings held across every summary in the cache. */
uint32_t store_sessions_readings(const store_sessions_t *t);

/** @brief Pages held across every summary in the cache. */
uint32_t store_sessions_pages(const store_sessions_t *t);

/* --- the ring as the index ------------------------------------------------- */

/**
 * @brief Where page headers come from.
 *
 * The ring, on the board; an array, in the host pack. Nothing below cares which,
 * or in what order the slots arrive: a summary is folded from its pages
 * commutatively and every ordering decision is taken from a single header, so
 * the scan is correct whether it walks the ring from the head, from slot zero,
 * or in any order at all.
 */
typedef struct {
    /**
     * Read the header of slot @p slot.
     * @return false if that slot holds no page, which the scan skips.
     */
    bool (*read)(void *ctx, uint32_t slot, store_header_t *out);
    void *ctx;
    uint32_t slots; /**< slots to walk, `0 .. slots - 1` */
} store_page_source_t;

/**
 * @brief A session's place in a total order over sessions: when it started.
 *
 * The scan has to page through an unbounded number of sessions in bounded
 * memory, which means every pass must be able to say "this page's session is
 * behind the window I am filling" from **that page's header alone** - it cannot
 * keep the identities it has already emitted. Start time and session number are
 * on every page and together they *are* the session's identity, so ordering on
 * them is the one total order that is readable off a single header.
 *
 * Ring order would be the better order to show a user and is not available here:
 * a page's sequence says where that page sits in the ring, not where its
 * session's newest page does. Start order is the same thing for every board that
 * records forwards in time. A clockless session's `start_ms` is a packed
 * (run, ms) rather than an epoch (see ::STORE_FLAG_RELATIVE), so it sorts by run
 * and then by the board's own clock - correct among its own kind, and after the
 * dated ones rather than interleaved with them.
 */
typedef struct {
    int64_t start_ms;
    uint32_t session;
} store_session_key_t;

/** @brief The key of a summary, for continuing a ::store_sessions_scan_window. */
store_session_key_t store_sessions_key(const store_session_t *summary);

/**
 * @brief The name a session has, derived from any one page header of it.
 *
 * A pure function of the header, which is what lets a name be re-derived after
 * the reboot that rebuilds the cache: a name that could not be re-derived would
 * be a session that renamed itself overnight.
 *
 * Public because `store_drain.c` has to put the *same* name on a drained row
 * that `file_list` puts on the session, and two copies of this rule would be two
 * names for one session the first time either changed.
 */
void store_sessions_derive_name(const store_header_t *page, char *out, size_t cap);

/**
 * @brief Rebuild one session's summary from the ring itself.
 *
 * What makes a session reachable for exactly as long as the ring holds a page of
 * it, whatever the cache's size: `get_file` and the erase resolve a name to an
 * identity, and this answers that from flash when RAM cannot. One pass over the
 * headers, no allocation.
 *
 * Two sessions can derive the same name (the same second of two different
 * boots), so the pages of the **first** matching identity are folded in and any
 * later namesake is left alone - the same first-match rule
 * ::store_sessions_find has always applied.
 *
 * @return false if no page in the ring belongs to a session of that name.
 */
bool store_sessions_scan_find(const store_page_source_t *src, const char *name,
                              store_session_t *out);

/**
 * @brief Summaries for up to @p cap sessions, from the ring, in key order.
 *
 * The paging primitive: it fills @p out with the @p cap smallest-keyed sessions
 * whose key is greater than @p after, **ascending**, each folded from every page
 * of it in the ring. Call it again with the key of the last entry to get the next
 * @p cap, until it returns fewer than @p cap. Any number of sessions, one pass
 * each, and `cap * sizeof(store_session_t)` of the caller's memory.
 *
 * @param after the key to continue past, or NULL to start at the oldest.
 * @return how many were written, 0 when there are no more.
 */
size_t store_sessions_scan_window(const store_page_source_t *src,
                                  const store_session_key_t *after,
                                  store_session_t *out, size_t cap);

/**
 * @brief Sessions the ring holds, however many that is.
 *
 * ::store_sessions_scan_window run to exhaustion. @p work is scratch of
 * @p work_cap summaries; bigger means fewer passes and nothing else.
 */
uint32_t store_sessions_scan_count(const store_page_source_t *src, store_session_t *work,
                                   size_t work_cap);

/**
 * @brief One page of the listing, **newest first**, straight from the ring.
 *
 * What lets a board name every session it holds over a wire whose frames are
 * finite. ::store_sessions_scan_window is the primitive underneath and pages
 * *forwards* from the oldest, which is the wrong end for a listing, so this walks
 * to the right starting point and then reverses the window it fills.
 *
 * Newest-first is what makes the paging safe on a live board. A session starting
 * while a client is part way through the walk lands at the *front*, which pushes
 * an older one to a higher offset and shows it twice - and a name that arrives
 * twice costs a de-duplicated request, while a name that never arrives costs the
 * data. A session going the other way is one the ring has already reclaimed and
 * cannot serve anyway.
 *
 * @p out doubles as the scratch for the walk, so it needs no memory of its own
 * and a bigger @p cap means fewer passes.
 *
 * @param skip how many of the newest to step over; past the end writes nothing.
 * @param total_out if not NULL, receives the sessions the ring holds in all.
 * @return how many were written.
 */
size_t store_sessions_scan_newest(const store_page_source_t *src, uint32_t skip,
                                  store_session_t *out, size_t cap, uint32_t *total_out);

/**
 * @brief Readings the ring holds, exactly, in constant memory.
 *
 * A sum over page headers, so it owes nothing to any table and cannot drift from
 * one. This is what `storage.readings` must agree with.
 *
 * @param pages if not NULL, receives the pages those readings are in.
 */
uint32_t store_sessions_scan_readings(const store_page_source_t *src, uint32_t *pages);

#ifdef __cplusplus
}
#endif

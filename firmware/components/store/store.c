/**
 * @file store.c
 * @brief The flash ring: page slots, a write head, and a scan that finds it.
 *
 * See `store.h` for what this is for and `store_codec.h` for the page format.
 * The only things worth reading the code for are here:
 *
 * - **Age is a number, not a position.** Every page carries a `page_seq` that
 *   never repeats. The head is the slot after the largest one, so the scan does
 *   not need to know where the ring started or how many times it has wrapped.
 * - **Erase a sector before its first page, never later.** A sector holds eight
 *   slots. Losing power mid-erase loses the eight *oldest* pages, which were
 *   about to be overwritten anyway, so the failure mode is no failure at all.
 * - **What the erase destroys, the session table is told about.** The headers of
 *   a sector are read before it goes and unfolded from their summaries after,
 *   which is what makes the table an account of the ring rather than a tally of
 *   everything ever written. See `store_sessions.h` for why that is the whole
 *   difference between "the board holds this" and "the board says it holds this".
 * - **The writer task owns the writing.** Acquisition hands readings over and
 *   returns; a flash erase takes tens of milliseconds and an acquisition
 *   callback that blocks for that long shows up as a gap in the data.
 * - **Alert-level readings leave the ring before it can reach them.** Inline as
 *   they are recorded, and as a sweep in front of the erase for anything the
 *   inline path never saw. Neither can stall acquisition: a preservation failure
 *   is counted and the reading is still recorded, because recording incoming
 *   data beats protecting older data.
 */

#include "store.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_partition.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "wire.h"

static const char *TAG = "store";

/** The partition, by name, from `partitions.csv`. */
#define STORE_PARTITION_NAME "store"

/** Flash erases a sector at a time; a sector holds this many slots. */
#define SECTOR_BYTES 4096
#define SLOTS_PER_SECTOR (SECTOR_BYTES / STORE_PAGE_BYTES)

/**
 * Readings buffered between the acquisition task and the writer task.
 *
 * Three pages' worth. Flash keeps up with 1 Hz by orders of magnitude, so this
 * only has to cover the worst single stall - a sector erase - with room to
 * spare, and being generous costs 128 * 56 bytes.
 */
#define QUEUE_MAX 128

typedef struct {
    acq_reading_t reading;
    int64_t epoch_ms;
    int16_t utc_offset_min;
    bool relative;
    char name[WIRE_CSV_NAME_LEN];
} queued_t;

/**
 * Bytes of the partition kept back for preserved alert data, at the far end.
 *
 * The ring is told about it and never writes there; see `store_preserve.h` for
 * why it is a region of this partition rather than a partition of its own.
 */
#define PRESERVE_BYTES ((size_t)STORE_PRESERVE_SECTORS * STORE_PRESERVE_SECTOR_BYTES)
#define PRESERVE_HEADER_BYTES \
    ((size_t)STORE_PRESERVE_HEADER_SECTORS * STORE_PRESERVE_SECTOR_BYTES)

/**
 * One segment being accumulated, and the page its samples are going into.
 *
 * There are two of these and they must not share anything. `live` follows the
 * readings as they are recorded; `sweep` is the salvage pass in front of an
 * erase, and it runs *inside* a write that `live` may be halfway through. One
 * shared accumulator would splice a rescued reading from two days ago into the
 * excursion happening right now.
 */
typedef struct {
    store_alert_seg_t seg;
    store_writer_t writer;
    bool writer_open;
    uint32_t body_seq;   /**< page_seq of this segment's first preserved page */
    uint32_t body_pages;
} alert_track_t;

static struct {
    const esp_partition_t *partition;
    uint32_t slots;
    uint32_t head;      /**< slot the next page goes into */
    uint32_t next_seq;  /**< page_seq for the next page */
    bool wrapped;       /**< the ring has filled at least once */
    uint32_t wraps;     /**< completed passes of the head over the ring */

    /* --- preserving alert-level readings ---------------------------------- */
    size_t preserve_base;      /**< byte offset of the preservation area */
    store_preserve_t preserve;
    store_alert_policy_t policy;
    alert_track_t live;
    alert_track_t sweep;

    uint8_t rate_level;
    uint32_t rate_floor_ms;
    store_rate_floor_fn rate_cb;
    void *rate_ctx;

    store_writer_t writer;
    bool writer_open;
    uint32_t writer_session;
    /**
     * The session's start, computed once when the session first appears.
     *
     * Deliberately not re-derived per reading. The sensor's real cadence is
     * 1009 ms, so `epoch - (index - 1) * 1000` drifts about nine milliseconds a
     * reading - and since the start time is half the session's identity, that
     * made every page look like a new session. The list filled with one-reading
     * duplicates of the same name until it was pinned here.
     */
    int64_t writer_start_ms;
    bool writer_session_known;
    bool writer_relative;
    char writer_name[WIRE_CSV_NAME_LEN];

    /**
     * What the board says it holds, kept in step with what the ring holds.
     *
     * Its own translation unit because the rule it keeps - a summary goes when
     * its last page goes - is the difference between a session being readable
     * and being 12,975 readings sitting in flash that nothing can name, and
     * because that rule is then something the host pack can check. See
     * `store_sessions.h`.
     *
     * A **cache** over the ring, not the record of it. When it says
     * `incomplete`, every question about what is held goes to the page headers
     * instead - see ::ring_source and ::unnamed_refresh.
     */
    store_sessions_t sessions;

    /**
     * What the ring holds that ::sessions does not describe.
     *
     * Zero on any board whose sessions fit the cache, which is every board so
     * far. Past that these two are what keep `storage` honest: they are measured
     * from the page headers and **added** to the cache's own totals rather than
     * replacing them, so a figure that has to be fresh to the second - readings
     * being recorded right now - still comes from the cache, and only the part
     * that cannot change without a session appearing or disappearing is measured
     * the slow way.
     *
     * Recomputed when ::session_epoch moves, which is exactly when the set of
     * sessions in the ring can have changed: a summary made, a summary dropped
     * for want of a slot, a sector reclaimed, or a rescan.
     */
    uint32_t unnamed_sessions;
    uint32_t unnamed_readings;
    uint32_t session_epoch;
    uint32_t unnamed_epoch;
    bool unnamed_valid;

    /**
     * Which run of the board this is, and whether it has anything to anchor.
     *
     * `run` is the page sequence the scan left behind, so it is monotonic across
     * reboots for free. `relative_seen` gates ::store_add_anchor: a board that
     * has always had a network has nothing an equivalence would explain, and
     * writing one every time SNTP resynced would spend ring slots on saying
     * nothing.
     */
    uint32_t run;
    bool relative_seen;

    /**
     * An equivalence noted but not yet on flash.
     *
     * ::store_add_anchor is called from whichever task learned the time - the
     * SNTP callback runs on the TCP/IP task - and a page write can take a sector
     * erase with it, tens of milliseconds. Blocking the TCP/IP task for that
     * drops packets. So the value is captured under the lock and the writing is
     * left to the store's own task.
     *
     * Deferring costs nothing that matters: an equivalence is a pair of numbers
     * read at one instant, not an action that has to happen at that instant.
     * Written a second later it says exactly the same thing.
     */
    bool anchor_pending;

    /**
     * Every equivalence the ring holds, oldest first, across all runs.
     *
     * Rebuilt by the scan and added to as time sources turn up. Each new anchor
     * page carries the whole table, so the newest anchor page is a complete
     * record on its own and equivalences do not age out one at a time with the
     * pages that first carried them.
     */
    store_anchor_t anchors[STORE_MAX_ANCHORS];
    size_t anchor_count;

    /**
     * The one import allowed at a time, and who it belongs to.
     *
     * Here rather than per client because it is 2 KB of held-index spans and
     * page buffer, and because two imports at once into sessions that overlap
     * would each be deciding "already held?" from a set the other was changing.
     * `import_owner` is a client id, so a disconnect can end its own import and
     * leave anyone else's alone.
     */
    store_merge_t import;
    bool import_open;
    int import_owner;
    uint32_t import_session;
    int64_t import_start_ms;
    char import_name[WIRE_CSV_NAME_LEN];

    /**
     * The one back-up-and-clear in progress, and who it belongs to.
     *
     * Here rather than per client for the same two reasons the import is: it is
     * an operation on a shared archive, and two devices each freeing what only
     * *they* have proved they hold would each be right while the board ended up
     * with data on neither. See `store_drain.h`.
     */
    store_drain_t drain;

    SemaphoreHandle_t lock;
    QueueHandle_t queue;
    store_stats_t stats;
    bool ready;
} s;

static void lock(void) { xSemaphoreTake(s.lock, portMAX_DELAY); }
static void unlock(void) { xSemaphoreGive(s.lock); }

/* --- the session index ----------------------------------------------------- */

/**
 * Fold one page's header into the session cache.
 *
 * The cache itself is `store_sessions.c`; this is the part of it that has to
 * notice when the cache stops describing the whole ring, which cannot be logged
 * from pure code.
 */
static void note_page(const store_header_t *page, const char *name)
{
    const uint32_t forgotten = s.sessions.forgotten;
    const size_t count = s.sessions.count;
    (void)store_sessions_note(&s.sessions, page, name);
    if (s.sessions.count != count || s.sessions.forgotten != forgotten) {
        s.session_epoch++; /* the set of sessions changed; the tally is stale */
    }
    if (s.sessions.forgotten != forgotten) {
        ESP_LOGI(TAG,
                 "more than %u sessions have live pages, so the index no longer describes "
                 "all of them and listing, lookup and the totals read the ring itself "
                 "(%" PRIu32 " summaries have given up their slot)",
                 (unsigned)STORE_SESSION_INDEX_MAX, s.sessions.forgotten);
    }
}

/* --- the ring as the session index ----------------------------------------- */

/**
 * One slot's header, for ::store_page_source_t. Caller holds the lock.
 *
 * Forty-eight bytes a slot, which is what makes a pass over the whole ring a few
 * tens of milliseconds - the same read the boot scan does, and the reason the
 * ring can serve as an index at all.
 */
static bool ring_header_at(void *ctx, uint32_t slot, store_header_t *out)
{
    (void)ctx;
    uint8_t header[STORE_HEADER_BYTES];
    if (s.partition == NULL ||
        esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, header,
                           sizeof(header)) != ESP_OK) {
        return false;
    }
    return store_peek_page(header, out);
}

/** The ring, as something the pure scan can walk. Caller holds the lock. */
static store_page_source_t ring_source(void)
{
    store_page_source_t src;
    src.read = ring_header_at;
    src.ctx = NULL;
    src.slots = s.slots;
    return src;
}

/**
 * Scratch for a paging scan. Eight summaries, 576 bytes of a client's stack.
 *
 * Only the number of passes depends on it: each pass costs one walk of the
 * headers, so eight means a board holding forty sessions is counted in five.
 */
#define SCAN_WORK 8

/**
 * Measure what the ring holds that the cache does not describe.
 *
 * Caller holds the lock. Cheap to call: it does nothing at all unless the cache
 * has had to drop a summary, and nothing again until the set of sessions moves.
 */
static void unnamed_refresh(void)
{
    if (!s.sessions.incomplete) {
        s.unnamed_sessions = 0;
        s.unnamed_readings = 0;
        s.unnamed_valid = true;
        s.unnamed_epoch = s.session_epoch;
        return;
    }
    if (s.unnamed_valid && s.unnamed_epoch == s.session_epoch) {
        return;
    }
    const store_page_source_t src = ring_source();
    store_session_t work[SCAN_WORK];
    const uint32_t ring_readings = store_sessions_scan_readings(&src, NULL);
    const uint32_t ring_sessions = store_sessions_scan_count(&src, work, SCAN_WORK);
    const uint32_t named_readings = store_sessions_readings(&s.sessions);
    const uint32_t named_sessions = (uint32_t)s.sessions.count;
    /* The cache is a subset of the ring, so these cannot go negative - clamped
     * anyway rather than trusted, because an underflow here would be reported as
     * four billion readings. */
    s.unnamed_readings = ring_readings > named_readings ? ring_readings - named_readings : 0;
    s.unnamed_sessions = ring_sessions > named_sessions ? ring_sessions - named_sessions : 0;
    s.unnamed_valid = true;
    s.unnamed_epoch = s.session_epoch;
}

/**
 * Resolve a session name to what the board knows about it.
 *
 * The cache first, the ring if the cache cannot answer and is not describing the
 * whole of it. This is what makes a session reachable for exactly as long as the
 * ring holds a page of it: `get_file` and the erase both come through here, and
 * neither depends any more on the session having a slot in RAM.
 *
 * Caller holds the lock. The ring pass reads the headers with the lock held,
 * which stalls the writer task for a few tens of milliseconds - it cannot stall
 * acquisition, which hands readings to a queue holding two minutes of them, and
 * it only happens on a board past the index's size and only for a name the cache
 * does not have.
 */
static bool find_summary(const char *name, store_session_t *out, bool *span_exact)
{
    /*
     * Whether ::store_session_t::first_seq reaches back to the session's oldest
     * live page, which is the one thing a caller may not assume. A cache that has
     * had to drop a summary can re-make it from a later page while earlier ones
     * are still in the ring, and nothing on the summary says which kind it is -
     * so a cached answer is only vouched for while the cache describes the whole
     * ring. The ring pass below folds every matching page and is exact whatever
     * the cache has been through.
     */
    if (span_exact != NULL) {
        *span_exact = !s.sessions.incomplete;
    }
    const store_session_t *held = store_sessions_find(&s.sessions, name);
    if (held != NULL) {
        *out = *held;
        return true;
    }
    if (!s.sessions.incomplete) {
        return false;
    }
    const store_page_source_t src = ring_source();
    if (!store_sessions_scan_find(&src, name, out)) {
        return false;
    }
    if (span_exact != NULL) {
        *span_exact = true;
    }
    return true;
}

/* --- equivalences ---------------------------------------------------------- */

/**
 * Insert one equivalence, keeping the table sorted and free of duplicates.
 *
 * Sorted by the packed relative clock, which orders by run first and by the
 * board's own clock within a run - the order a client wants when it goes looking
 * for the equivalence nearest a block. Duplicates are expected rather than
 * exceptional: every anchor page repeats the whole table, so a scan sees the
 * same equivalence once per anchor page that survived.
 *
 * Caller holds the lock.
 */
static void note_anchor(const store_anchor_t *a)
{
    size_t at = 0;
    while (at < s.anchor_count && s.anchors[at].rel_ms < a->rel_ms) {
        at++;
    }
    if (at < s.anchor_count && s.anchors[at].rel_ms == a->rel_ms) {
        return; /* already held; the two agree by construction */
    }
    if (s.anchor_count == STORE_MAX_ANCHORS) {
        if (at == 0) {
            return; /* older than everything held, and there is no room */
        }
        memmove(&s.anchors[0], &s.anchors[1], sizeof(s.anchors[0]) * (STORE_MAX_ANCHORS - 1));
        s.anchor_count--;
        at--;
    }
    memmove(&s.anchors[at + 1], &s.anchors[at],
            sizeof(s.anchors[0]) * (s.anchor_count - at));
    s.anchors[at] = *a;
    s.anchor_count++;
}

/* --- the scan -------------------------------------------------------------- */

/**
 * Find the write head.
 *
 * Every slot is read, but only its header, and only to compare `page_seq`. The
 * head is the slot after the largest sequence found; an empty ring leaves it at
 * zero. This is O(slots) rather than a binary search on purpose: the session
 * table has to be built from the same pass anyway, and 4480 forty-eight-byte
 * reads is a few tens of milliseconds, once, on a task that is not holding
 * anything up.
 */
static void scan_ring(void)
{
    uint32_t best_seq = 0;
    uint32_t best_slot = 0;
    bool any = false;
    uint32_t used = 0;

    /* Only the header is read - forty-eight bytes a slot rather than five
     * hundred and twelve - which is what makes scanning four and a half thousand
     * slots a few tens of milliseconds instead of most of a second. */
    uint8_t header[STORE_HEADER_BYTES];
    store_header_t page;
    for (uint32_t slot = 0; slot < s.slots; slot++) {
        if (esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, header,
                               sizeof(header)) != ESP_OK) {
            continue;
        }
        if (!store_peek_page(header, &page)) {
            continue;
        }
        used++;
        if (!any || page.page_seq > best_seq) {
            any = true;
            best_seq = page.page_seq;
            best_slot = slot;
        }
        if (page.anchor) {
            /* Rare enough to be worth the second, whole-page read: this is the
             * only page kind whose payload the scan needs, and reading it here
             * is what lets a session recorded before the last reboot still be
             * placed on a calendar. */
            uint8_t whole[STORE_PAGE_BYTES];
            store_anchor_t found[STORE_MAX_ANCHORS];
            if (esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, whole,
                                   sizeof(whole)) == ESP_OK) {
                const size_t n = store_decode_anchors(whole, found, STORE_MAX_ANCHORS);
                for (size_t i = 0; i < n; i++) {
                    note_anchor(&found[i]);
                }
            }
        }
        note_page(&page, NULL);
    }

    if (any) {
        s.next_seq = best_seq + 1;
        s.head = (best_slot + 1) % s.slots;
        s.wrapped = used >= s.slots;
    } else {
        s.next_seq = 1;
        s.head = 0;
        s.wrapped = false;
    }

    /*
     * The wrap count, recovered rather than remembered.
     *
     * `page_seq` advances exactly once per page written and the head advances
     * exactly once with it, so for a ring that started blank at slot zero the
     * number of completed passes is just how many pages have been written
     * divided by how many slots there are. That is exact, needs nothing stored,
     * and is why the ring did not already need a counter.
     *
     * What it does not survive is an erase, which puts `next_seq` back to one.
     * So the larger of it and the highest wrap any preserved segment header
     * records is taken: the preservation area is not part of the ring and an
     * erase does not touch it, so a board that has ever preserved a segment
     * keeps its count through one. Monotonic either way - this only ever raises.
     */
    uint32_t derived = (s.next_seq - 1) / s.slots;
    const uint32_t seen = store_preserve_seen_wrap(&s.preserve);
    if (seen > derived) {
        derived = seen;
    }
    if (derived > s.wraps) {
        s.wraps = derived;
    }

    /*
     * The run marker, fixed for as long as this boot lasts.
     *
     * Total pages ever written, which is what `wraps * slots + next_seq` is. It
     * has to be monotonic across reboots or two runs of a clockless board would
     * carry the same marker and be presented as one stretch of recording.
     *
     * `next_seq` alone very nearly is - except that a full erase puts it back to
     * one. That is exactly the hole `wraps` fills, and filling it is what its own
     * documentation says it is for: the preservation area is outside the ring, an
     * erase does not touch it, and every preserved segment header records the
     * wrap it was written at. So the marker survives an erase by the same
     * mechanism the wrap count does, and this only ever increases.
     */
    s.run = (uint32_t)(((uint64_t)s.wraps * s.slots + s.next_seq) & 0x7FFFFFFFu);
    s.stats.slots = s.slots;
    s.stats.slots_used = used;
    s.stats.wraps = s.wraps;
    /* The scan has rebuilt the cache, so whatever was measured about the rest of
     * the ring describes a ring that no longer exists. */
    s.session_epoch++;
    s.unnamed_valid = false;
    unnamed_refresh();
    ESP_LOGI(TAG, "ring: %" PRIu32 " slots, %" PRIu32 " hold pages, head at %" PRIu32
                  ", next seq %" PRIu32 ", wrap %" PRIu32 ", %u sessions (%" PRIu32
                  " of them past the index), %u equivalences, run %" PRIu32,
             s.slots, used, s.head, s.next_seq, s.wraps,
             (unsigned)(s.sessions.count + s.unnamed_sessions), s.unnamed_sessions,
             (unsigned)s.anchor_count, s.run);
}

/* --- preserving alert-level readings --------------------------------------- */

/**
 * A decoded page, off the stack.
 *
 * ::store_page_t is 2.6 KB and a local one overflowed the store task's stack and
 * boot-looped the board once already - see `store_codec.h`. Two paths borrow
 * this one: the sweep in front of an erase, and the digest an erase takes.
 * **Both hold the store's lock for the whole of decode-and-use**, which is what
 * makes sharing it safe; nothing may decode into this and then let go of the
 * lock before it has finished reading it.
 */
static store_page_t s_scratch_page;

/**
 * A second one, for the midpoint pass, and it must **not** be ::s_scratch_page.
 *
 * The two nest. ::preserve_sweep_sector decodes a ring page into the scratch and
 * then walks its readings, and one of those readings can end a segment - which
 * closes it, which reads the preserved samples back to work out the midpoint.
 * Sharing the buffer would have that read land on top of the page still being
 * walked, and the rest of the loop would then be offering whatever the midpoint
 * pass happened to decode: readings of some other session, filed into the
 * segment being rescued. Not a lost reading - a reading that reappears in the
 * wrong recording, which is the failure this whole design is most careful about.
 *
 * 2.6 KB of BSS to make a nesting impossible rather than merely unlikely.
 */
static store_page_t s_midpoint_page;

static bool preserve_read(void *ctx, size_t offset, uint8_t *out, size_t len)
{
    (void)ctx;
    return esp_partition_read(s.partition, s.preserve_base + offset, out, len) == ESP_OK;
}

static bool preserve_write_bytes(void *ctx, size_t offset, const uint8_t *data, size_t len)
{
    (void)ctx;
    return esp_partition_write(s.partition, s.preserve_base + offset, data, len) == ESP_OK;
}

static bool preserve_erase(void *ctx, size_t offset, size_t len)
{
    (void)ctx;
    return esp_partition_erase_range(s.partition, s.preserve_base + offset, len) == ESP_OK;
}

/** Commit @p t's open page of samples. Caller holds the lock. */
static void track_seal_body(alert_track_t *t)
{
    if (!t->writer_open || store_writer_empty(&t->writer)) {
        t->writer_open = false;
        return;
    }
    uint8_t page[STORE_PAGE_BYTES];
    if (store_writer_seal(&t->writer, page, false) && store_preserve_body_write(&s.preserve, page)) {
        t->body_pages++;
    }
    t->writer_open = false;
}

/** Put one reading into @p t's page of samples, rolling the page as needed. */
static void track_add_body(alert_track_t *t, const store_reading_t *r, uint32_t cadence_ms)
{
    if (!t->writer_open) {
        const uint32_t seq = store_preserve_next_body_seq(&s.preserve);
        store_writer_init(&t->writer, seq, t->seg.session, t->seg.session_start_ms, cadence_ms,
                          t->seg.utc_offset_min, t->seg.relative);
        t->writer_open = true;
        if (t->body_pages == 0) {
            t->body_seq = seq;
        }
    }
    if (store_writer_add(&t->writer, r)) {
        return;
    }
    track_seal_body(t);
    const uint32_t seq = store_preserve_next_body_seq(&s.preserve);
    store_writer_init(&t->writer, seq, t->seg.session, t->seg.session_start_ms, cadence_ms,
                      t->seg.utc_offset_min, t->seg.relative);
    t->writer_open = true;
    if (t->body_pages == 0) {
        t->body_seq = seq;
    }
    (void)store_writer_add(&t->writer, r);
}

/**
 * Consider raising or lowering the recording rate, and say so if it changed.
 *
 * The **only** input is pressure on the preservation area. The ring's own
 * fullness is deliberately not consulted: a full ring wraps, and reducing the
 * rate because it filled is the behaviour the captain considered and rejected.
 */
static void preserve_review_rate(void)
{
    const float pressure = store_preserve_pressure(&s.preserve);
    const uint8_t level = store_alert_rate_level(s.rate_level, pressure);
    if (level == s.rate_level) {
        return;
    }
    s.rate_level = level;
    s.rate_floor_ms = store_alert_rate_floor_ms(level);
    ESP_LOGW(TAG,
             "preserved alert data is %d%% of its area; recording interval floor now %u ms",
             (int)(pressure * 100.0f), (unsigned)s.rate_floor_ms);
    if (s.rate_cb != NULL) {
        s.rate_cb(s.rate_floor_ms, s.rate_ctx);
    }
}

/**
 * Seal @p t's segment: commit its remaining samples, then write its header.
 *
 * The header goes last on purpose. A power cut between the samples and the
 * header leaves orphan sample pages, which cost space and nothing else; the
 * other order would leave a header pointing at samples that were never written,
 * which is a summary of an event the board cannot show you.
 */
static void track_close(alert_track_t *t)
{
    if (!t->seg.open) {
        return;
    }
    track_seal_body(t);

    store_alert_header_t header;
    store_alert_seg_header(&t->seg, &header);
    header.wrap = s.wraps;
    header.body_seq = t->body_seq;
    header.body_pages = t->body_pages;
    if (t->body_pages == 0) {
        header.flags |= STORE_ALERT_FLAG_BODY_GONE;
    } else {
        /* The one statistic that cannot be accumulated in a single pass. Read
         * back from the pages just written, which are still the newest thing on
         * the body ring and so certainly still there. */
        (void)store_preserve_fill_midpoint(&s.preserve, &header, &s_midpoint_page);
    }

    if (store_preserve_header_write(&s.preserve, &header)) {
        ESP_LOGI(TAG,
                 "preserved alert segment %" PRIu32 ": %s %" PRIu32 " points, mean %.2f, "
                 "max %.2f, wrap %" PRIu32,
                 header.seq, SPS30_FIELD_NAMES[header.field], header.points,
                 (double)header.mean, (double)header.maximum, header.wrap);
    } else {
        ESP_LOGW(TAG, "could not write the header for an alert segment of %" PRIu32 " points",
                 header.points);
    }
    memset(&t->seg, 0, sizeof(t->seg));
    t->body_pages = 0;
    t->body_seq = 0;
    preserve_review_rate();
}

/**
 * Offer one reading to @p t.
 *
 * Returns having done whatever the policy calls for and nothing more. It cannot
 * block, and a flash refusal anywhere below it is counted rather than retried:
 * this runs on the path a reading takes to the ring, and a reading that is not
 * recorded because preserving an older one was difficult is the exact trade the
 * captain's rule forbids.
 */
static void track_offer(alert_track_t *t, const store_reading_t *r, uint32_t session,
                        int64_t session_start_ms, int16_t utc_offset_min, bool relative,
                        uint32_t cadence_ms)
{
    if (t->seg.open) {
        if (store_alert_seg_continues(&t->seg, session, session_start_ms, r)) {
            store_alert_seg_add(&t->seg, r);
            track_add_body(t, r, cadence_ms);
            if (store_alert_seg_closing(&t->seg)) {
                track_close(t);
            }
            return;
        }
        /* A different session, or a hole in the indices. A segment is one
         * continuous run of readings; joining across a gap would put a hole
         * inside the one record that exists to be a complete account. */
        track_close(t);
    }

    const int field = store_alert_trip(&s.policy, r->values);
    if (field < 0) {
        return;
    }
    store_alert_seg_begin(&t->seg, field, s.policy.threshold[field], session, session_start_ms,
                          utc_offset_min, relative, r);
    t->body_pages = 0;
    t->body_seq = 0;
    track_add_body(t, r, cadence_ms);
}

/**
 * What the sweep already knows about which readings are in the preservation area.
 *
 * Without this the sweep would re-preserve every alert segment it walked past -
 * on every wrap, for ever - and the duplicates would fill the preservation area,
 * which would trigger the rate reduction, which would silently drop the
 * captain's sampling rate for no reason at all. So it is not an optimisation.
 *
 * The memo holds one verdict and the index range over which that verdict is
 * known to hold, so the header scan happens once per *transition* rather than
 * once per reading: a sector of 370 readings that are all already preserved
 * costs one scan, not 370.
 */
static struct {
    uint32_t session;
    int64_t session_start_ms;
    uint32_t through;  /**< the verdict holds for indices up to and including this */
    bool covered;
    bool valid;
} s_sweep_memo;

static void sweep_memo_clear(void) { s_sweep_memo.valid = false; }

/**
 * Whether this reading is already in the preservation area.
 *
 * Answers from the memo when it can, and otherwise reads the segment headers -
 * 64 KB, a few milliseconds - and works out both the answer and how far it can
 * be trusted before asking again.
 *
 * Caller holds the lock.
 */
static bool already_preserved(uint32_t session, int64_t session_start_ms, uint32_t index)
{
    if (s_sweep_memo.valid && s_sweep_memo.session == session &&
        s_sweep_memo.session_start_ms == session_start_ms && index <= s_sweep_memo.through) {
        return s_sweep_memo.covered;
    }

    uint32_t through = UINT32_MAX;
    const bool covered =
        store_preserve_covers(&s.preserve, session, session_start_ms, index, &through);

    s_sweep_memo.session = session;
    s_sweep_memo.session_start_ms = session_start_ms;
    s_sweep_memo.through = through;
    s_sweep_memo.covered = covered;
    s_sweep_memo.valid = true;
    return covered;
}

/**
 * Rescue anything alert-level in the sector about to be erased.
 *
 * The captain's "preservation before destruction". In the ordinary case it finds
 * nothing, because the inline path already copied those readings out as they
 * were recorded - and that is the point: this is the net under the case where it
 * did not. Two of those are real rather than theoretical:
 *
 * - **The line moved.** A phone connects and lowers the threshold, which makes
 *   readings already in the ring alert-level that were not when they were taken.
 * - **The readings never came through acquisition.** An imported session is
 *   written straight to the ring by `store_merge`, so nothing offered it to the
 *   live segment.
 *
 * Bounded by construction: one sector is eight pages, about 370 readings, and it
 * runs on the store task between the erase and the write. If flash refuses
 * anywhere in here the erase still happens - the new page is what matters.
 *
 * Caller holds the lock and is about to erase the sector starting at @p base.
 */
static void preserve_sweep_sector(uint32_t base)
{
    if (!s.preserve.ready || !store_alert_policy_watches(&s.policy)) {
        return;
    }
    sweep_memo_clear();
    uint8_t raw[STORE_PAGE_BYTES];
    for (uint32_t i = 0; i < SLOTS_PER_SECTOR; i++) {
        const uint32_t slot = (base + i) % s.slots;
        if (esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, raw,
                               sizeof(raw)) != ESP_OK) {
            continue;
        }
        store_header_t peek;
        if (!store_peek_page(raw, &peek)) {
            continue;
        }
        if (!store_decode_page(raw, &s_scratch_page)) {
            s.stats.decode_failures++;
            continue;
        }
        /* The page's identity, taken once into locals rather than read out of
         * the shared buffer on every iteration. ::s_midpoint_page is what keeps
         * the readings themselves safe across a ::track_close; this keeps the
         * header fields safe too, and costs four words. */
        const uint32_t session = s_scratch_page.session;
        const int64_t session_start_ms = s_scratch_page.session_start_ms;
        const int16_t utc_offset_min = s_scratch_page.utc_offset_min;
        const uint32_t cadence_ms = s_scratch_page.cadence_ms;
        /* Copied out with the rest: a preserved page has to be written with the
         * same time base as the page it came from, or a packed relative value
         * would be read back as a date. */
        const bool relative = s_scratch_page.relative;
        const uint8_t count = s_scratch_page.count;
        for (uint8_t k = 0; k < count; k++) {
            /* A copy, not a pointer into the shared page. */
            const store_reading_t r = s_scratch_page.readings[k];
            if (already_preserved(session, session_start_ms, r.index)) {
                /* Already out of the ring's way. Close whatever the sweep had
                 * open: the run continues in a segment that is already stored,
                 * and carrying on would file the same readings twice. */
                track_close(&s.sweep);
                continue;
            }
            track_offer(&s.sweep, &r, session, session_start_ms, utc_offset_min, relative,
                        cadence_ms);
        }
    }
    /* Whatever the sweep found ends here. The next sector it looks at is eight
     * pages further on and may be from another session entirely, and a segment
     * that spanned the two would be describing a run the board no longer holds
     * either end of. */
    track_close(&s.sweep);
}

/* --- writing --------------------------------------------------------------- */

/**
 * The headers of a sector, read before it is erased so they can be unfolded after.
 *
 * Eight of them, forty-eight bytes each, on the store task's stack for the
 * length of one erase. Read *before* because after the erase there is nothing
 * left to read, and applied *after* because a failed erase must leave the
 * account of what is held exactly as it was.
 */
typedef struct {
    store_header_t page[SLOTS_PER_SECTOR];
    uint8_t count;
} doomed_t;

/** Read the headers of the sector at @p base. Caller holds the lock. */
static void read_doomed(uint32_t base, doomed_t *out)
{
    out->count = 0;
    uint8_t header[STORE_HEADER_BYTES];
    for (uint32_t i = 0; i < SLOTS_PER_SECTOR; i++) {
        const uint32_t slot = (base + i) % s.slots;
        if (esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, header,
                               sizeof(header)) != ESP_OK) {
            continue;
        }
        if (!store_peek_page(header, &out->page[out->count])) {
            continue; /* an empty slot, or one that never held a page */
        }
        out->count++;
    }
}

/**
 * Take a reclaimed sector out of the account of what the board holds.
 *
 * The other half of ::note_page, and the reason the session table can be trusted
 * to describe the ring rather than everything ever written to it. Without this
 * a summary kept its readings after the pages behind them were gone, and the
 * only thing that ever corrected it was a reboot rescan.
 *
 * Caller holds the lock.
 */
static void forget_doomed(const doomed_t *gone)
{
    for (uint8_t i = 0; i < gone->count; i++) {
        if (store_sessions_forget(&s.sessions, &gone->page[i])) {
            ESP_LOGI(TAG, "the ring has reclaimed the last page of a session");
        }
    }
    if (gone->count > 0) {
        /* Pages went, so a session may have gone with them - including one the
         * cache never described, which `store_sessions_forget` cannot report. */
        s.session_epoch++;
    }
    /* Slots, not pages: what ran out is slots, and eight of them have just come
     * back. The write below takes one of them again. */
    if (s.stats.slots_used >= gone->count) {
        s.stats.slots_used -= gone->count;
    } else {
        s.stats.slots_used = 0;
    }
}

/** Put one sealed page into the head slot, erasing its sector first if needed. */
static bool write_page(const uint8_t page[STORE_PAGE_BYTES])
{
    if (s.head % SLOTS_PER_SECTOR == 0) {
        const size_t offset = (size_t)s.head * STORE_PAGE_BYTES;
        /* Before the erase, never after. This is the last moment at which those
         * readings exist. */
        preserve_sweep_sector(s.head);
        doomed_t gone;
        read_doomed(s.head, &gone);
        const esp_err_t err = esp_partition_erase_range(s.partition, offset, SECTOR_BYTES);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "erase at %u failed: %s", (unsigned)offset, esp_err_to_name(err));
            return false;
        }
        s.stats.pages_erased += SLOTS_PER_SECTOR;
        forget_doomed(&gone);
    }
    const esp_err_t err = esp_partition_write(s.partition, (size_t)s.head * STORE_PAGE_BYTES,
                                              page, STORE_PAGE_BYTES);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "write to slot %" PRIu32 " failed: %s", s.head, esp_err_to_name(err));
        return false;
    }
    s.head = (s.head + 1) % s.slots;
    if (s.head == 0) {
        s.wrapped = true;
        s.wraps++;
        s.stats.wraps = s.wraps;
    }
    s.next_seq++;
    s.stats.pages_written++;
    if (s.stats.slots_used < s.slots) {
        s.stats.slots_used++;
    }
    return true;
}

/** Seal the open page and commit it. Caller holds the lock. */
static void seal_and_write(bool full)
{
    if (!s.writer_open || store_writer_empty(&s.writer)) {
        s.writer_open = false;
        return;
    }
    uint8_t page[STORE_PAGE_BYTES];
    if (store_writer_seal(&s.writer, page, full)) {
        if (write_page(page)) {
            store_header_t header;
            if (store_peek_page(page, &header)) {
                note_page(&header, s.writer_name);
            } else {
                /* We just built it, so this cannot happen without a bug. Count
                 * it rather than trusting that. */
                s.stats.decode_failures++;
            }
        }
    }
    s.writer_open = false;
}

/** Put the pending equivalence on flash. Runs on the store task; holds the lock. */
static void write_pending_anchor(void);

/** Take one reading into the open page, opening or rolling one as required. */
static void ingest(const queued_t *item)
{
    store_reading_t reading = {
        .index = item->reading.sequence,
        .epoch_ms = item->epoch_ms,
        .settling = (item->reading.flags & ACQ_FLAG_SETTLING) != 0,
    };
    for (int f = 0; f < STORE_FIELDS; f++) {
        reading.values[f] = item->reading.values.values[f];
    }

    /* A new session, by `acq`'s numbering. Within one boot that number is
     * reliable; across a reboot it restarts at 1, which is why the start time
     * below - reset by the same reboot - is the other half of the identity.
     *
     * A change of time base counts as a new session too. It should not happen -
     * the server decides once, when the session opens, and never revisits it -
     * but half a session on one clock and half on another would be one session
     * whose readings could not be put in order, which is worse than two.
     */
    if (!s.writer_session_known || s.writer_session != item->reading.session ||
        s.writer_relative != item->relative) {
        if (s.writer_open) {
            seal_and_write(false); /* a new session always starts a new page */
        }
        /* Computed once, here. Index 1 is the start, and this reading knows how
         * far past it that is. */
        s.writer_start_ms = item->epoch_ms - (int64_t)(item->reading.sequence - 1) * 1000;
        s.writer_session = item->reading.session;
        s.writer_relative = item->relative;
        s.writer_session_known = true;
    }
    const int64_t session_start_ms = s.writer_start_ms;
    if (item->relative) {
        s.relative_seen = true;
    }

    if (!s.writer_open) {
        store_writer_init(&s.writer, s.next_seq, item->reading.session, session_start_ms,
                          1000, item->utc_offset_min, item->relative);
        snprintf(s.writer_name, sizeof(s.writer_name), "%s", item->name);
        s.writer_open = true;
    }

    if (!store_writer_add(&s.writer, &reading)) {
        seal_and_write(true);
        store_writer_init(&s.writer, s.next_seq, item->reading.session, session_start_ms,
                          1000, item->utc_offset_min, item->relative);
        snprintf(s.writer_name, sizeof(s.writer_name), "%s", item->name);
        s.writer_open = true;
        if (!store_writer_add(&s.writer, &reading)) {
            ESP_LOGW(TAG, "a reading did not fit an empty page; dropping it");
            s.stats.readings_dropped++;
            return;
        }
    }
    s.stats.readings_stored++;

    /*
     * The copy, taken **after** the reading is safely in the ring.
     *
     * That order is the governing rule in one line: the ring is where the
     * reading is recorded and the preservation area is where an old one is
     * protected, so the recording goes first and nothing preservation does can
     * cost it. Every failure below here is counted and returned; none of them
     * reaches back up to this point.
     */
    if (s.preserve.ready) {
        track_offer(&s.live, &reading, item->reading.session, session_start_ms,
                    item->utc_offset_min, item->relative, 1000);
    }
}

static void store_task(void *arg)
{
    (void)arg;

    lock();
    /* The preservation area comes up first, because the ring's scan asks it what
     * wrap count it has seen: the count survives an erase of the ring only
     * because the segment headers do, and they cannot answer before they have
     * been read. */
    const store_preserve_dev_t dev = {
        .size = PRESERVE_BYTES,
        .header_bytes = PRESERVE_HEADER_BYTES,
        .ctx = NULL,
        .read = preserve_read,
        .write = preserve_write_bytes,
        .erase = preserve_erase,
    };
    if (store_preserve_begin(&s.preserve, &dev)) {
        store_preserve_stats_t ps;
        store_preserve_get_stats(&s.preserve, &ps);
        ESP_LOGI(TAG,
                 "preserved alerts: %" PRIu32 " segments, %" PRIu32 "/%" PRIu32
                 " sample pages, watching %s at %.1f",
                 ps.segments, ps.body_used, ps.body_slots,
                 SPS30_FIELD_NAMES[STORE_ALERT_FIELD_PM2_5],
                 (double)s.policy.threshold[STORE_ALERT_FIELD_PM2_5]);
        preserve_review_rate();
    } else {
        /* Not fatal, and said out loud. A board that measures and serves but
         * cannot preserve is still worth having; a board that silently reports
         * no alert segments because preservation never started is not. */
        ESP_LOGE(TAG, "the preservation area would not start; alert data will not be kept");
    }
    scan_ring();
    s.ready = true;
    s.stats.ready = true;
    unlock();

    queued_t item;
    for (;;) {
        /* A timeout rather than portMAX_DELAY, so a pending equivalence is
         * written even when acquisition is paused and no reading is coming to
         * wake this task. One second is far below anything that cares. */
        const bool got = xQueueReceive(s.queue, &item, pdMS_TO_TICKS(1000)) == pdTRUE;
        lock();
        if (got) {
            ingest(&item);
        }
        write_pending_anchor();
        unlock();
    }
}

/* --- the public surface ---------------------------------------------------- */

esp_err_t store_start(void)
{
    if (s.lock != NULL) {
        return ESP_OK;
    }
    memset(&s, 0, sizeof(s));

    s.partition = esp_partition_find_first(ESP_PARTITION_TYPE_DATA,
                                           ESP_PARTITION_SUBTYPE_ANY, STORE_PARTITION_NAME);
    if (s.partition == NULL) {
        ESP_LOGE(TAG, "no '%s' partition; this build cannot store readings",
                 STORE_PARTITION_NAME);
        ESP_LOGE(TAG, "flash the partition table too: idf.py -p PORT flash");
        return ESP_ERR_NOT_FOUND;
    }
    /*
     * The ring gets the partition minus the preservation area at the far end,
     * and never learns the rest exists: every offset here is a slot index times
     * the page size, and `s.slots` is the only thing that bounds it.
     *
     * Rounded down to a whole sector so an erase at the last sector of the ring
     * cannot reach into the first sector of the preservation area - which would
     * take out preserved segments to make room for ordinary readings, the exact
     * inversion this design exists to prevent.
     */
    if (s.partition->size <= PRESERVE_BYTES + SECTOR_BYTES) {
        ESP_LOGE(TAG, "the '%s' partition is too small to hold a ring and a preservation area",
                 STORE_PARTITION_NAME);
        return ESP_ERR_INVALID_SIZE;
    }
    const size_t ring_bytes = ((s.partition->size - PRESERVE_BYTES) / SECTOR_BYTES) * SECTOR_BYTES;
    s.preserve_base = ring_bytes;
    s.slots = (uint32_t)(ring_bytes / STORE_PAGE_BYTES);
    store_alert_policy_default(&s.policy);
    ESP_LOGI(TAG,
             "partition '%s' at 0x%06" PRIx32 ", %" PRIu32 " B: %" PRIu32
             " ring pages + %u B preserved",
             s.partition->label, (uint32_t)s.partition->address, (uint32_t)s.partition->size,
             s.slots, (unsigned)(s.partition->size - ring_bytes));

    s.lock = xSemaphoreCreateMutex();
    s.queue = xQueueCreate(QUEUE_MAX, sizeof(queued_t));
    if (s.lock == NULL || s.queue == NULL) {
        ESP_LOGE(TAG, "out of memory starting the store");
        return ESP_ERR_NO_MEM;
    }
    /* 6 KB. The store task holds the open page writer and one sealed page; the
     * big struct - a decoded page, 2.6 KB - is deliberately not on any stack.
     * Priority below acquisition, which must never wait on flash. */
    if (xTaskCreate(store_task, "store", 6144, NULL, 4, NULL) != pdPASS) {
        ESP_LOGE(TAG, "could not start the store task");
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

void store_submit(const acq_reading_t *reading, const char *session_name, int64_t epoch_ms,
                  int16_t utc_offset_min, bool relative)
{
    if (s.queue == NULL || reading == NULL || epoch_ms <= 0) {
        /* An absolute reading with no clock behind it has no time to be stored
         * against. A relative one always has: its clock is the board's own, and
         * a packed relative reading is positive by construction. */
        return;
    }
    queued_t item = {
        .reading = *reading,
        .epoch_ms = epoch_ms,
        .utc_offset_min = utc_offset_min,
        .relative = relative,
    };
    if (session_name != NULL) {
        snprintf(item.name, sizeof(item.name), "%s", session_name);
    }
    if (xQueueSend(s.queue, &item, 0) != pdTRUE) {
        s.stats.readings_dropped++;
    }
}

void store_flush(void)
{
    if (!s.ready) {
        return;
    }
    lock();
    seal_and_write(false);
    /* The preserved copy of the same readings, for the same reason: a client
     * asking what is here should not find the last minute of an excursion
     * missing purely because the page it is in has not filled yet. */
    track_seal_body(&s.live);
    unlock();
}

bool store_ready(void) { return s.ready; }

uint32_t store_run_marker(void) { return s.run; }

esp_err_t store_add_anchor(uint32_t rel_ms, int64_t epoch_ms, int16_t utc_offset_min)
{
    if (!s.ready || epoch_ms <= 0) {
        return ESP_ERR_INVALID_STATE;
    }
    lock();
    if (!s.relative_seen) {
        /* Nothing here was recorded against the board's own clock, so there is
         * nothing an equivalence would explain. A board that has always had a
         * network would otherwise spend a ring slot on every SNTP resync. */
        unlock();
        return ESP_ERR_INVALID_STATE;
    }
    const store_anchor_t anchor = {
        .rel_ms = store_rel_pack(s.run, rel_ms),
        .epoch_ms = epoch_ms,
        .utc_offset_min = utc_offset_min,
    };
    /* Two measurements a minute apart are two measurements; two a second apart
     * are the same one written twice, and a client reconnecting in a loop would
     * spend the archive on saying so. */
    for (size_t i = s.anchor_count; i > 0; i--) {
        const store_anchor_t *held = &s.anchors[i - 1];
        if (store_rel_run(held->rel_ms) != s.run) {
            continue;
        }
        const int64_t gap = anchor.rel_ms - held->rel_ms;
        if (gap >= 0 && gap < (int64_t)STORE_ANCHOR_MIN_GAP_MS) {
            unlock();
            return ESP_ERR_INVALID_STATE;
        }
        break;
    }

    note_anchor(&anchor);
    s.anchor_pending = true;
    const size_t held = s.anchor_count;
    unlock();

    ESP_LOGI(TAG, "equivalence noted: run %" PRIu32 " clock %" PRIu32
                  " ms = epoch %lld ms (%u held); the store task will write it",
             s.run, rel_ms, (long long)epoch_ms, (unsigned)held);
    return ESP_OK;
}

/** Put the pending equivalence on flash. Runs on the store task; holds the lock. */
static void write_pending_anchor(void)
{
    if (!s.anchor_pending || s.anchor_count == 0) {
        s.anchor_pending = false;
        return;
    }
    s.anchor_pending = false;
    /* The page carries the whole table, earlier runs included, so the newest
     * anchor page is a complete record on its own and equivalences do not age
     * out one at a time with the pages that first carried them. */
    uint8_t page[STORE_PAGE_BYTES];
    const store_anchor_t *newest = &s.anchors[s.anchor_count - 1];
    if (store_seal_anchor_page(page, s.next_seq, 0, store_rel_pack(s.run, 0),
                               newest->rel_ms, newest->utc_offset_min, s.anchors,
                               s.anchor_count) &&
        write_page(page)) {
        ESP_LOGI(TAG, "equivalence page written: %u held", (unsigned)s.anchor_count);
    } else {
        ESP_LOGW(TAG, "could not write the equivalence page; it is still in RAM");
        /* Kept pending: the next turn of the store task tries again, and until
         * one lands the data stays relative rather than half-explained. */
        s.anchor_pending = true;
    }
}

size_t store_list_anchors(store_anchor_t *out, size_t cap)
{
    if (!s.ready || out == NULL) {
        return 0;
    }
    lock();
    size_t n = s.anchor_count < cap ? s.anchor_count : cap;
    for (size_t i = 0; i < n; i++) {
        out[i] = s.anchors[i];
    }
    unlock();
    return n;
}

/**
 * Whether the session being written has no sealed page yet. Caller holds the lock.
 *
 * Such a session is real and measuring and is in neither the cache nor the ring,
 * so it has to be added to a listing by hand. It is also the newest, which is why
 * one bool is enough: it can only ever be the first entry.
 */
static bool writer_unsealed_locked(void)
{
    if (!s.writer_open || s.writer.count == 0) {
        return false;
    }
    for (size_t i = 0; i < s.sessions.count; i++) {
        if (s.sessions.slot[i].session == s.writer_session &&
            s.sessions.slot[i].start_ms == s.writer.session_start_ms) {
            return false;
        }
    }
    return true;
}

/** Describe the session being written, for a listing. Caller holds the lock. */
static void writer_entry_locked(store_session_t *entry)
{
    memset(entry, 0, sizeof(*entry));
    entry->session = s.writer_session;
    entry->start_ms = s.writer.session_start_ms;
    entry->utc_offset_min = s.writer.utc_offset_min;
    entry->cadence_ms = s.writer.cadence_ms;
    entry->first_index = s.writer.first_index;
    entry->last_index = s.writer.first_index + s.writer.count - 1;
    entry->readings = s.writer.count;
    entry->relative = s.writer.relative;
    snprintf(entry->name, sizeof(entry->name), "%s", s.writer_name);
}

/** What the board holds, in readings and sessions. Caller holds the lock. */
static void held_locked(uint32_t *readings, uint32_t *sessions)
{
    /*
     * The cache's own totals plus whatever the ring holds beyond it.
     *
     * Both halves are needed and neither would do alone. The cache is exact
     * about the session being written this second, which is the figure a
     * phone watches; the measured part covers the sessions the cache has no
     * slot for. Together they are what the ring actually holds, which is what
     * `storage` has to say - it used to fall by 12,975 at the moment the
     * board started holding *more*.
     */
    unnamed_refresh();
    uint32_t held = store_sessions_readings(&s.sessions) + s.unnamed_readings;
    uint32_t count = (uint32_t)s.sessions.count + s.unnamed_sessions;
    /* The page still being filled, on the same terms ::store_list_sessions
     * counts it on: those readings are held, and a session whose first page
     * is not sealed yet is one the board is holding. */
    if (s.writer_open && s.writer.count > 0) {
        held += s.writer.count;
        if (writer_unsealed_locked()) {
            count++;
        }
    }
    if (readings != NULL) {
        *readings = held;
    }
    if (sessions != NULL) {
        *sessions = count;
    }
}

/**
 * One page of the stored listing, newest first. Caller holds the lock.
 *
 * From the ring when the cache does not describe all of it. `file_list` is the
 * enumeration a backup walks, so a session missing from it is a session the user
 * cannot retrieve - which is exactly what a full cache used to cause, and why
 * this path exists rather than a bigger cache.
 */
static size_t list_stored_locked(store_session_t *out, size_t cap, uint32_t skip)
{
    if (cap == 0) {
        return 0;
    }
    if (!s.sessions.incomplete) {
        return store_sessions_list_from(&s.sessions, skip, out, cap);
    }
    const store_page_source_t src = ring_source();
    return store_sessions_scan_newest(&src, skip, out, cap, NULL);
}

size_t store_list_sessions(store_session_t *out, size_t cap)
{
    return store_list_sessions_page(out, cap, 0, NULL);
}

size_t store_list_sessions_page(store_session_t *out, size_t cap, uint32_t offset,
                                uint32_t *total_out)
{
    if (total_out != NULL) {
        *total_out = 0;
    }
    if (!s.ready || out == NULL || cap == 0) {
        return 0;
    }
    lock();

    /* The same figure `storage.sessions` reports, from the same code, because a
     * client compares its archive against it to decide whether the copy it just
     * took is complete. Two counts that could disagree is how a board came to
     * report fewer readings at the moment it started holding more. */
    held_locked(NULL, total_out);

    size_t n = 0;
    uint32_t skip = offset;

    /*
     * Count the page still being filled, without sealing it.
     *
     * Sealing here instead would burn a page slot every time a client asked what
     * was available, and would write a one-reading page for every reconnect. The
     * readings are real either way; a client that asks for them triggers the
     * flush that makes them readable.
     */
    const bool unsealed = writer_unsealed_locked();
    if (unsealed) {
        /* Newest, so it heads the listing - and so it moves every stored session
         * one place along, which every later page has to allow for or it would
         * name the session before it twice and drop the last one. */
        if (offset == 0) {
            writer_entry_locked(&out[n++]);
        } else {
            skip = offset - 1;
        }
    }

    n += list_stored_locked(out + n, cap - n, skip);

    if (!unsealed && s.writer_open && s.writer.count > 0) {
        /* Its first page is sealed, so the ring names it; what the ring cannot
         * know is the readings still in the page being filled. */
        for (size_t i = 0; i < n; i++) {
            if (out[i].session == s.writer_session &&
                out[i].start_ms == s.writer.session_start_ms) {
                out[i].readings += s.writer.count;
                const uint32_t last = s.writer.first_index + s.writer.count - 1;
                if (last > out[i].last_index) {
                    out[i].last_index = last;
                }
                break;
            }
        }
    }
    unlock();
    return n;
}

void store_held(uint32_t *readings, uint32_t *sessions)
{
    uint32_t held = 0;
    uint32_t count = 0;
    if (s.ready) {
        lock();
        held_locked(&held, &count);
        unlock();
    }
    if (readings != NULL) {
        *readings = held;
    }
    if (sessions != NULL) {
        *sessions = count;
    }
}

/**
 * ::store_find_session, with the lock held and the span's provenance reported.
 *
 * @param span_exact if not NULL, receives whether @p out's `first_seq` may be
 *        used to skip slots. See ::find_summary.
 */
static bool find_session_locked(const char *name, store_session_t *out, bool *span_exact)
{
    /*
     * Looked up rather than filtered out of a copy of the whole list.
     *
     * This runs on a client task, and the list is about seventy bytes an entry;
     * copying it here to read one row put that on a stack that also has to
     * carry an import's claims and the frame being built. The lookup wants one
     * summary, so it takes one - from the cache, or from the ring when the cache
     * is not describing all of it. See ::find_summary: this is the call that
     * decides whether a session the index has no slot for can still be read.
     */
    bool have = find_summary(name, out, span_exact);
    /* The page still being filled, exactly as ::store_list_sessions counts it:
     * a session whose first page is not sealed yet is real and measuring, and
     * `get_file` on it must not answer "no such session". */
    if (s.writer_open && s.writer.count > 0 && strcmp(s.writer_name, name) == 0) {
        const uint32_t last = s.writer.first_index + s.writer.count - 1;
        if (have && out->session == s.writer_session &&
            out->start_ms == s.writer.session_start_ms) {
            out->readings += s.writer.count;
            if (last > out->last_index) {
                out->last_index = last;
            }
        } else if (!have) {
            memset(out, 0, sizeof(*out));
            out->session = s.writer_session;
            out->start_ms = s.writer.session_start_ms;
            out->utc_offset_min = s.writer.utc_offset_min;
            out->cadence_ms = s.writer.cadence_ms;
            out->first_index = s.writer.first_index;
            out->last_index = last;
            out->readings = s.writer.count;
            out->relative = s.writer.relative;
            snprintf(out->name, sizeof(out->name), "%s", s.writer_name);
            have = true;
        }
    }
    return have;
}

bool store_find_session(const char *name, store_session_t *out)
{
    if (!s.ready || name == NULL || out == NULL) {
        return false;
    }
    lock();
    const bool have = find_session_locked(name, out, NULL);
    unlock();
    return have;
}

/** One slot's first @p len bytes, for ::store_slot_reader_t. Takes the lock. */
static bool ring_slot_read(void *ctx, uint32_t slot, uint8_t *out, size_t len)
{
    (void)ctx;
    if (s.partition == NULL) {
        return false;
    }
    lock();
    const esp_err_t err =
        esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, out, len);
    unlock();
    return err == ESP_OK;
}

/** The ring, as something ::store_walk_next can read. */
static store_slot_reader_t ring_reader(void)
{
    store_slot_reader_t src;
    src.read = ring_slot_read;
    src.ctx = NULL;
    src.slots = s.slots;
    return src;
}

bool store_cursor_open(store_cursor_t *cursor, const char *name, uint32_t since_index)
{
    if (cursor == NULL || !s.ready || name == NULL) {
        return false;
    }
    /*
     * The summary and the ring geometry are taken under one hold of the lock, and
     * that is the point of doing the lookup here rather than through
     * ::store_find_session: ::store_walk_plan turns a `page_seq` into a slot using
     * `head` and `next_seq`, so a page written between reading the span and
     * reading the head would put the walk one slot out for the whole transfer.
     */
    store_session_t session;
    bool span_exact = false;
    lock();
    const bool have = find_session_locked(name, &session, &span_exact);
    const store_ring_geometry_t geometry = {
        .slots = s.slots,
        .head = s.head,
        .next_seq = s.next_seq,
        .wrapped = s.wrapped,
    };
    unlock();
    if (!have) {
        return false;
    }

    memset(cursor, 0, sizeof(*cursor));
    cursor->session = session.session;
    cursor->session_start_ms = session.start_ms;
    cursor->since_index = since_index;
    store_walk_plan(cursor, &geometry, span_exact, session.first_seq, session.last_seq);
    cursor->open = true;
    return true;
}

bool store_cursor_next(store_cursor_t *cursor, store_reading_t *out)
{
    if (!s.ready) {
        return false;
    }
    const store_slot_reader_t src = ring_reader();
    return store_walk_next(cursor, &src, out, &s.stats.decode_failures);
}

void store_get_stats(store_stats_t *out)
{
    if (out == NULL) {
        return;
    }
    *out = s.stats;
    /* Read from the table rather than mirrored into `s.stats` as it happens, so
     * the two cannot disagree - an erase rebuilds the table from flash, and a
     * counter left behind by that would keep reporting sessions the board is no
     * longer failing to name. */
    out->sessions_forgotten = s.sessions.forgotten;
}

/* --- how full the ring is -------------------------------------------------- */

/*
 * Deliberately the **ring's** fullness and not the partition's.
 *
 * This is what `HANDOFF.md` item 13's 85 % auto-export is measured against, and
 * the two features must not fight: preserved alert data accumulating in its own
 * area must never look like a ring that is filling up, or a board with a few
 * alert segments would tell every phone that connects to copy everything.
 * `s.slots` stops at the preservation area, so it cannot.
 */
float store_fraction_used(void)
{
    if (!s.ready || s.slots == 0) {
        return 0.0f;
    }
    lock();
    const float fraction = (float)s.stats.slots_used / (float)s.slots;
    unlock();
    return fraction > 1.0f ? 1.0f : fraction;
}

/* --- the wrap counter and the preserved alert data ------------------------- */

uint32_t store_wraps(void)
{
    if (s.lock == NULL) {
        return 0;
    }
    lock();
    const uint32_t wraps = s.wraps;
    unlock();
    return wraps;
}

uint64_t store_ring_epoch(void)
{
    if (s.lock == NULL || s.slots == 0) {
        return 0;
    }
    lock();
    const uint64_t epoch = (uint64_t)s.wraps * (uint64_t)s.slots + (uint64_t)s.head;
    unlock();
    return epoch;
}

/* --- the occupancy map ------------------------------------------------------ */

static bool map_read_ring_slot(void *ctx, uint32_t slot, uint8_t *out, size_t len)
{
    (void)ctx;
    return esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, out, len) == ESP_OK;
}

static bool map_read_body_slot(void *ctx, uint32_t slot, uint8_t *out, size_t len)
{
    (void)ctx;
    return esp_partition_read(s.partition,
                              s.preserve_base + PRESERVE_HEADER_BYTES +
                                  (size_t)slot * STORE_PAGE_BYTES,
                              out, len) == ESP_OK;
}

bool store_map_report(store_map_report_t *out)
{
    if (out == NULL || s.lock == NULL) {
        return false;
    }
    memset(out, 0, sizeof(*out));

    /* Snapshot the write positions under the lock, then walk without it, the
     * way a `get_file` cursor does: the positions and the cells are one
     * moment's answer each, and a page the writer lands between them shows up
     * on the next refresh. Holding the lock across a quarter-megabyte header
     * pass would instead stall the writer task for the length of it. */
    lock();
    const bool ready = s.stats.ready;
    const uint32_t slots = s.slots;
    const uint32_t head = s.head;
    const uint32_t wraps = s.wraps;
    const uint32_t header_slots = s.preserve.header_slots;
    const uint32_t header_head = s.preserve.header_head;
    const uint32_t header_seq = s.preserve.header_seq;
    const uint32_t body_slots = s.preserve.body_slots;
    const uint32_t body_head = s.preserve.body_head;
    const uint32_t body_seq = s.preserve.body_seq;
    unlock();
    if (!ready) {
        return false;
    }

    const uint32_t partition_pages = (uint32_t)(s.partition->size / STORE_PAGE_BYTES);
    const uint32_t header_pages = (uint32_t)(PRESERVE_HEADER_BYTES / STORE_PAGE_BYTES);
    const uint32_t body_pages = (uint32_t)((PRESERVE_BYTES - PRESERVE_HEADER_BYTES) /
                                           STORE_PAGE_BYTES);
    if (partition_pages > STORE_MAP_MAX_PAGES) {
        return false;
    }
    out->page_bytes = STORE_PAGE_BYTES;
    out->pages = partition_pages;
    out->region_count = 3;

    /* The ring: one cell per slot, from its own header and nowhere else. */
    uint8_t *cells = out->cells;
    const store_slot_reader_t ring_src = { map_read_ring_slot, NULL, slots };
    if (!store_map_scan(&ring_src, cells, STORE_MAP_CELLS_BYTES(slots))) {
        return false;
    }
    out->regions[0] = (store_map_region_t){
        .id = WIRE_MAP_SECTION_RING,
        .start = 0,
        .pages = slots,
        .head = head,
        .wraps = wraps,
        .cells = cells,
    };
    cells += STORE_MAP_CELLS_BYTES(slots);

    /* The preserved-segment header region holds 128-byte records, four to a
     * page, so a page there has no page header to peek: blank or not is all
     * the map can honestly say about it, and a whole-page read is what says
     * it. The write position translates to pages the same way. */
    const uint32_t records_per_page = STORE_PAGE_BYTES / STORE_ALERT_HEADER_BYTES;
    for (uint32_t page = 0; page < header_pages; page++) {
        uint8_t raw[STORE_PAGE_BYTES];
        uint8_t state = STORE_MAP_PAGE_BAD;
        if (esp_partition_read(s.partition, s.preserve_base + (size_t)page * STORE_PAGE_BYTES,
                               raw, sizeof(raw)) == ESP_OK) {
            state = store_map_classify_blank(raw, sizeof(raw));
        }
        store_map_cell_set(cells, page, state);
    }
    out->regions[1] = (store_map_region_t){
        .id = WIRE_MAP_SECTION_ALERT_HEADERS,
        .start = slots,
        .pages = header_pages,
        .head = header_slots > 0 ? header_head / records_per_page : 0,
        .wraps = header_slots > 0 ? (header_seq - 1) / header_slots : 0,
        .cells = cells,
    };
    cells += STORE_MAP_CELLS_BYTES(header_pages);

    /* The body region holds copied reading pages, so the ring's classifier
     * applies as written. */
    const store_slot_reader_t body_src = { map_read_body_slot, NULL, body_pages };
    if (!store_map_scan(&body_src, cells, STORE_MAP_CELLS_BYTES(body_pages))) {
        return false;
    }
    out->regions[2] = (store_map_region_t){
        .id = WIRE_MAP_SECTION_ALERT_BODIES,
        .start = slots + header_pages,
        .pages = body_pages,
        .head = body_slots > 0 ? body_head : 0,
        .wraps = body_slots > 0 ? (body_seq - 1) / body_slots : 0,
        .cells = cells,
    };
    return true;
}

esp_err_t store_alert_set_threshold(int field, float threshold)
{
    if (s.lock == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    lock();
    const bool ok = store_alert_policy_set(&s.policy, field, threshold);
    if (ok) {
        /* The memo describes what was already preserved under the *old* line and
         * says nothing about the new one. Dropping it costs one header scan. */
        sweep_memo_clear();
    }
    unlock();
    return ok ? ESP_OK : ESP_ERR_INVALID_ARG;
}

void store_alert_get_policy(store_alert_policy_t *out)
{
    if (out == NULL) {
        return;
    }
    if (s.lock == NULL) {
        store_alert_policy_default(out);
        return;
    }
    lock();
    *out = s.policy;
    unlock();
}

void store_alert_get_stats(store_preserve_stats_t *out)
{
    if (out == NULL) {
        return;
    }
    if (s.lock == NULL) {
        memset(out, 0, sizeof(*out));
        return;
    }
    lock();
    store_preserve_get_stats(&s.preserve, out);
    unlock();
}

void store_alert_flush(void)
{
    if (!s.ready) {
        return;
    }
    /*
     * Commits the samples so far and leaves the segment **open**.
     *
     * Deliberately not "close it so a client can have the header": splitting an
     * excursion because somebody connected would make the stored record depend
     * on when a phone happened to be in the room, and the readings of a segment
     * still in progress are in the ring anyway, where the ordinary backfill
     * serves them. ::STORE_ALERT_MAX_POINTS is what bounds how long a segment
     * can go unheadered.
     */
    lock();
    track_seal_body(&s.live);
    unlock();
}

size_t store_alert_list(store_alert_header_t *out, size_t cap, uint32_t since_seq)
{
    if (!s.ready || out == NULL) {
        return 0;
    }
    lock();
    const size_t n = store_preserve_list(&s.preserve, out, cap, since_seq);
    unlock();
    return n;
}

bool store_alert_find(uint32_t seq, store_alert_header_t *out)
{
    if (!s.ready || out == NULL) {
        return false;
    }
    lock();
    const bool found = store_preserve_find(&s.preserve, seq, out);
    unlock();
    return found;
}

uint32_t store_alert_newest_seq(void)
{
    if (!s.ready) {
        return 0;
    }
    lock();
    const uint32_t seq = store_preserve_newest_seq(&s.preserve);
    unlock();
    return seq;
}

bool store_alert_cursor_open(store_preserve_cursor_t *cursor, uint32_t seq)
{
    if (!s.ready || cursor == NULL) {
        return false;
    }
    /* One hold of the lock for both halves. ::store_alert_find takes it itself
     * and this is a plain mutex, not a recursive one, so calling it from inside
     * a hold would deadlock the client task against the store. */
    store_alert_header_t header;
    lock();
    const bool ok = store_preserve_find(&s.preserve, seq, &header) &&
                    store_preserve_open(&s.preserve, &header, cursor);
    unlock();
    return ok;
}

bool store_alert_cursor_next(store_preserve_cursor_t *cursor, store_reading_t *out)
{
    if (!s.ready || cursor == NULL) {
        return false;
    }
    /* Per page rather than for the whole walk, exactly as ::digest_session does:
     * a segment can be an hour long and holding the store's lock across all of
     * it would stall the writer task. */
    lock();
    const bool got = store_preserve_next(&s.preserve, cursor, out);
    unlock();
    return got;
}

float store_alert_pressure(void)
{
    if (!s.ready) {
        return 0.0f;
    }
    lock();
    const float p = store_preserve_pressure(&s.preserve);
    unlock();
    return p;
}

uint32_t store_rate_floor_ms(void) { return s.rate_floor_ms; }

uint8_t store_rate_level(void) { return s.rate_level; }

void store_set_rate_floor_cb(store_rate_floor_fn fn, void *ctx)
{
    s.rate_cb = fn;
    s.rate_ctx = ctx;
    /* Tell it at once what is already in force, so a board that came up under
     * pressure does not sit at full rate until the next segment closes. */
    if (fn != NULL) {
        fn(s.rate_floor_ms, ctx);
    }
}

/* --- taking a session back in ---------------------------------------------- */

/**
 * The high bit, on the session number of a session that arrived from a file.
 *
 * Acquisition numbers sessions from 1 within a boot, so the imported range can
 * never collide with a live one however long the board runs. The rest of the
 * number is derived from the session's start second, which makes it stable: the
 * same file imported twice computes the same number, and a reboot reads it back
 * off the page rather than inventing a new one.
 */
#define STORE_IMPORTED_SESSION 0x80000000u

/**
 * Whether a page can be written at the head without costing one that is there.
 *
 * The whole of "an import never evicts", in one function. Acquisition is allowed
 * to overwrite the oldest sector - that is what a ring is - but an import is
 * not, so the import's sink asks this first and gives up when the answer is no.
 *
 * The slot itself is checked always, and at a sector boundary so are the other
 * seven: ::write_page erases the whole sector before writing, so a boundary
 * write costs eight pages or none.
 *
 * Caller holds the lock.
 */
static bool head_is_free(void)
{
    uint8_t header[STORE_HEADER_BYTES];
    store_header_t peek;
    const uint32_t span = (s.head % SLOTS_PER_SECTOR == 0) ? SLOTS_PER_SECTOR : 1;
    for (uint32_t i = 0; i < span; i++) {
        const uint32_t slot = (s.head + i) % s.slots;
        if (esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, header,
                               sizeof(header)) != ESP_OK) {
            return false; /* cannot prove it is empty, so treat it as occupied */
        }
        if (store_peek_page(header, &peek)) {
            return false;
        }
    }
    return true;
}

/**
 * Whether an import may take one more slot at the ring's current fullness.
 *
 * The wrap design says recording always beats protecting older data, and that
 * is about readings the sensor is **producing**: at the moment one is taken it
 * exists nowhere else, so nothing may stand between it and a slot. A reading
 * arriving from a client is the opposite case by definition - the device
 * sending it has it - and the rule that follows is its mirror:
 *
 *   **An import may not take the space recording needs.**
 *
 * ::head_is_free already stops an import evicting anything. What it cannot see
 * is the slower version of the same harm: on a ring that has not yet wrapped,
 * an import is free to fill every remaining slot, and the sensor then wraps on
 * its next page - so old data handed back displaces the board's own newest
 * recording, which is exactly the trade the design forbids. At 96 % full, an
 * archive sent back would consume the last of the runway for readings the
 * tablet already holds and be the first thing overwritten.
 *
 * So the reserve is the same line the store already publishes as "take a copy
 * now", ::WIRE_STORE_WARN_FRACTION. Above it an import stores nothing new and
 * says so with counts; the readings it holds already are unaffected, because
 * those cost no slot at all (see ::store_merge_needs_seal and the duplicate
 * path in `store_merge.c`) - which is what keeps sending an archive back safe
 * to do on a full board, and useless to do destructively.
 *
 * Below the line an import fills freely, as it always did.
 *
 * Caller holds the lock, which is why the fraction is computed here rather than
 * through ::store_fraction_used.
 */
static bool import_may_take_a_slot(void)
{
    if (s.slots == 0) {
        return false;
    }
    const float fraction = (float)s.stats.slots_used / (float)s.slots;
    return fraction < (float)WIRE_STORE_WARN_FRACTION;
}

static uint32_t import_next_seq(void *ctx)
{
    (void)ctx;
    lock();
    const uint32_t seq = s.next_seq;
    unlock();
    return seq;
}

static bool import_commit(void *ctx, const uint8_t page[STORE_PAGE_BYTES])
{
    (void)ctx;
    lock();
    const bool ok = import_may_take_a_slot() && head_is_free() && write_page(page);
    if (ok) {
        /* Fold it into the session table, exactly as ::seal_and_write does for a
         * page the sensor produced.
         *
         * Without this an imported session is *on flash and invisible*: the next
         * import finds its pages by scanning headers and calls every reading a
         * duplicate, while `list_files` does not name it and `get_file` answers
         * "no stored session". The data is safe and unreachable, which is the
         * worst of both, and it only came right after a reboot rebuilt the table
         * from flash. Found on the board grafting a session it had never seen.
         *
         * The name is the import's, and it is the same name a reboot would
         * derive: ::store_import_open only accepts a start time that
         * ::wire_csv_name maps back onto this name. */
        store_header_t header;
        if (store_peek_page(page, &header)) {
            note_page(&header, s.import_name);
        } else {
            s.stats.decode_failures++;
        }
    }
    unlock();
    return ok;
}

static const store_sink_t s_import_sink = {
    .next_seq = import_next_seq,
    .commit = import_commit,
    .ctx = NULL,
};

esp_err_t store_import_open(int owner, const char *name, int64_t session_start_ms,
                            int16_t utc_offset_min)
{
    if (!s.ready || name == NULL || name[0] == '\0') {
        return ESP_ERR_INVALID_STATE;
    }

    /* Seal the page still being filled first - but only when it is this
     * session's page. Its readings are real and already held, and an import
     * that could not see them would write a second copy of the last minute of
     * the session every time; an import of any *other* session learns nothing
     * from sealing it and pays a whole ring page for the privilege. An archive
     * is one of these per session in it, so that was a page per session sent
     * back, on a board whose ring is the thing the whole feature exists to
     * protect. See ::store_merge_needs_seal.
     *
     * Outside the lock because ::store_flush takes it, and this one is a plain
     * mutex rather than a recursive one. Nothing is claimed yet, so a client
     * that is about to be refused has only flushed a page early. */
    lock();
    const bool seal_first = store_merge_needs_seal(s.writer_open, s.writer_name, name);
    unlock();
    if (seal_first) {
        store_flush();
    }

    lock();
    /*
     * The claim is checked and taken without ever letting go of the lock.
     *
     * This test used to sit above ::store_flush, outside the lock, and that was
     * the whole of the defect: client tasks are preemptive at equal priority
     * and `put_file` is deliberately not behind the control claim, so two of
     * them could both find `import_open` false and both proceed. The second
     * ::store_merge_begin resets the merge state the first is filling, and
     * `s.import_name` becomes the second client's - after which ::import_commit
     * files the first client's pages into the *second* client's session. That
     * is not a lost reading, it is a reading that reappears in the wrong
     * recording, and nothing looks wrong afterwards.
     */
    if (s.import_open && s.import_owner != owner) {
        unlock();
        return ESP_ERR_INVALID_STATE; /* someone else is importing */
    }
    /*
     * And not while the board is being backed up and cleared.
     *
     * The two would fight over the same slots: a drain frees a block only after
     * proving the device holds it, and an import writing into that block between
     * the proof and the erase would put readings there that nobody has a copy
     * of - which is exactly the state the whole design refuses. Refusing the
     * import is the cheap direction: nothing of it has been written, and sending
     * the same file again once the drain is over is already safe.
     */
    if (s.drain.open) {
        unlock();
        return ESP_ERR_INVALID_STATE;
    }

    uint32_t session = 0;
    int64_t start_ms = session_start_ms;
    int16_t offset_min = utc_offset_min;
    uint32_t cadence_ms = 1000;
    /* Through the ring when the cache has no slot for it. Asking RAM alone would
     * have a board past the index answer "never heard of it" about a session it
     * holds, and this branch would then invent a *second* session of that name
     * and file the archive into it - a session sent back twice, once under each
     * identity, from a transfer that reported success. */
    store_session_t held;
    const bool known = find_summary(name, &held, NULL);
    if (known) {
        session = held.session;
        start_ms = held.start_ms;
        offset_min = held.utc_offset_min;
        cadence_ms = held.cadence_ms ? held.cadence_ms : 1000u;
    } else {
        session = STORE_IMPORTED_SESSION |
                  (uint32_t)((uint64_t)(start_ms / 1000) & 0x7FFFFFFFu);
    }

    store_merge_begin(&s.import, session, start_ms, cadence_ms, offset_min);
    snprintf(s.import_name, sizeof(s.import_name), "%s", name);
    s.import_session = session;
    s.import_start_ms = start_ms;

    /* Everything this session already holds, from page headers alone. */
    bool ok = true;
    uint8_t header[STORE_HEADER_BYTES];
    store_header_t peek;
    for (uint32_t slot = 0; slot < s.slots && ok; slot++) {
        if (esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, header,
                               sizeof(header)) != ESP_OK) {
            continue;
        }
        if (!store_peek_page(header, &peek) || peek.session != session ||
            peek.session_start_ms != start_ms) {
            continue;
        }
        ok = store_merge_note_held(&s.import, peek.first_index, peek.count);
    }
    if (ok) {
        s.import_open = true;
        s.import_owner = owner;
    }
    unlock();

    if (!ok) {
        ESP_LOGW(TAG, "import of %s refused: too fragmented to merge safely", name);
        return ESP_ERR_NO_MEM;
    }
    ESP_LOGI(TAG, "import of %s open: %s session, %" PRIu32 " readings already held", name,
             known ? "known" : "new", store_held_readings(&s.import.held));
    return ESP_OK;
}

/** The claim, read as one. See ::store_import_open for why it is under the lock. */
static bool import_is_mine(int owner)
{
    /* A controller whose partition never came up has no mutex to take, and this
     * is reached without one: ::store_import_abandon runs on every disconnect,
     * and a transfer is now closed out and answered rather than refused per
     * chunk (`net_import.h`), so the end of one is reached on a board that never
     * had storage to offer. Nobody holds a claim there, which is the answer. */
    if (s.lock == NULL) {
        return false;
    }
    lock();
    const bool mine = s.import_open && s.import_owner == owner;
    unlock();
    return mine;
}

store_merge_verdict_t store_import_add(int owner, const store_reading_t *reading)
{
    if (!import_is_mine(owner)) {
        /*
         * Distinct from ::STORE_MERGE_REJECTED, which is a verdict the merge
         * reached about the reading itself and has already counted. This one
         * never reaches the merge at all, so it appears in no `stats` - and a
         * row that is dropped without appearing anywhere is a row the transfer
         * reports as delivered. The caller counts it; see `handle_put_file`.
         */
        return STORE_MERGE_NOT_OPEN;
    }
    /* Not under the lock: the merge only touches its own state, and the two
     * things that do reach flash - `next_seq` and `commit` - take it themselves.
     * Only the owner reaches here, and only the owner's own task can close or
     * abandon the import, so the claim cannot change underneath this. */
    return store_merge_add(&s.import, reading, &s_import_sink);
}

bool store_import_close(int owner, store_merge_stats_t *out)
{
    if (!import_is_mine(owner)) {
        return false;
    }
    store_merge_finish(&s.import, &s_import_sink);
    if (out != NULL) {
        *out = s.import.stats;
    }
    ESP_LOGI(TAG,
             "import of %s done: %" PRIu32 " added, %" PRIu32 " already held, %" PRIu32
             " rejected, %" PRIu32 " pages%s",
             s.import_name, s.import.stats.added, s.import.stats.duplicate,
             s.import.stats.rejected, s.import.stats.pages,
             s.import.stats.no_room ? " (the ring is full)" : "");
    lock();
    s.import_open = false;
    s.import_owner = 0;
    unlock();
    return true;
}

void store_import_abandon(int owner)
{
    if (!import_is_mine(owner)) {
        return;
    }
    /* Seal what was accepted rather than discarding it. Those readings were
     * missing from this controller a moment ago and are additive; throwing them
     * away because the connection dropped would be losing data to tidiness. */
    store_merge_finish(&s.import, &s_import_sink);
    ESP_LOGW(TAG, "import of %s abandoned after %" PRIu32 " readings", s.import_name,
             s.import.stats.added);
    lock();
    s.import_open = false;
    s.import_owner = 0;
    unlock();
}

bool store_import_active(int owner)
{
    return import_is_mine(owner);
}

/* --- erasing, and the proof it needs --------------------------------------- */

/** The confirmed sessions, by the identity a page header carries. */
typedef struct {
    uint32_t session;
    int64_t start_ms;
} erase_key_t;

/**
 * Recompute one session's digest and count from the pages the controller holds.
 *
 * Locks per page rather than for the whole walk: a full ring is thousands of
 * pages and holding the store's lock across all of them would stall the writer
 * task for seconds. A session that acquisition is actively extending can change
 * underneath this, which shows up as a digest that does not match the claim -
 * and a session still being written is exactly one that should not be erased.
 */
static void digest_session(uint32_t session, int64_t start_ms, uint32_t *digest,
                           uint32_t *readings)
{
    *digest = 0;
    *readings = 0;
    uint8_t raw[STORE_PAGE_BYTES];
    for (uint32_t slot = 0; slot < s.slots; slot++) {
        lock();
        const esp_err_t err = esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES,
                                                 raw, STORE_PAGE_BYTES);
        store_header_t peek;
        const bool mine = err == ESP_OK && store_peek_page(raw, &peek) &&
                          peek.session == session && peek.session_start_ms == start_ms;
        const bool decoded = mine && store_decode_page(raw, &s_scratch_page);
        if (decoded) {
            for (uint8_t i = 0; i < s_scratch_page.count; i++) {
                *digest = store_digest_fold(*digest,
                                            store_digest_reading(&s_scratch_page.readings[i]));
                (*readings)++;
            }
        }
        unlock();
    }
}

esp_err_t store_erase_confirmed(const store_claim_t *claims, size_t count, bool override,
                                store_erase_result_t *out)
{
    if (!s.ready || out == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    if (s.import_open) {
        memset(out, 0, sizeof(*out));
        snprintf(out->message, sizeof(out->message), "an import is running");
        return ESP_ERR_INVALID_STATE;
    }
    /* And not while a back-up-and-clear is running. The control claim already
     * makes it very hard to reach - both are behind it and there is one holder -
     * but "hard to reach" is a property of another file. Two paths freeing
     * sectors at once would each be right about what it had proved and the board
     * would still end up with readings on neither. */
    if (s.drain.open) {
        memset(out, 0, sizeof(*out));
        snprintf(out->message, sizeof(out->message),
                 "this sensor is being copied off and cleared");
        return ESP_ERR_INVALID_STATE;
    }
    memset(out, 0, sizeof(*out));

    /* The readings still in RAM belong to the record too. Sealing them first
     * means an erase cannot leave a page half-written behind it. */
    store_flush();

    erase_key_t confirmed[STORE_SESSION_INDEX_MAX];
    size_t confirmed_count = 0;

    if (!override) {
        /* Resolve every claim against what is actually here. */
        for (size_t c = 0; c < count && confirmed_count < STORE_SESSION_INDEX_MAX; c++) {
            uint32_t session = 0;
            int64_t start_ms = 0;
            store_session_t held;
            lock();
            /* Through the ring when the cache has no slot for it, so that a
             * session past the index can still be cleared once the device can
             * prove it has a copy. A session that cannot be erased is as stuck
             * as one that cannot be read. */
            const bool found = find_summary(claims[c].name, &held, NULL);
            if (found) {
                session = held.session;
                start_ms = held.start_ms;
            }
            unlock();
            if (!found) {
                /* A claim for something not here is not a refusal: the device
                 * holds a session this controller has already lost or never had,
                 * which is the backup working. It confirms nothing, though. */
                continue;
            }
            uint32_t digest = 0;
            uint32_t readings = 0;
            digest_session(session, start_ms, &digest, &readings);
            if (readings == claims[c].readings && digest == claims[c].digest) {
                confirmed[confirmed_count].session = session;
                confirmed[confirmed_count].start_ms = start_ms;
                confirmed_count++;
                out->sessions_confirmed++;
            } else {
                out->sessions_refused++;
                ESP_LOGW(TAG,
                         "erase: %s not confirmed (device claims %" PRIu32
                         " readings/%08" PRIx32 ", we hold %" PRIu32 "/%08" PRIx32 ")",
                         claims[c].name, claims[c].readings, claims[c].digest, readings,
                         digest);
            }
        }

        /*
         * Any session here that nobody claimed is a session the requesting
         * device does not hold. It stays, and it is why the erase is partial.
         *
         * Walked over the ring rather than over the cache, so a board holding
         * more sessions than the index describes does not under-report how much
         * it is keeping back - the count is what tells the user their erase was
         * partial, and a short one would read as a complete clear.
         */
        lock();
        {
            const store_page_source_t src = ring_source();
            store_session_t work[SCAN_WORK];
            store_session_key_t after;
            bool have_after = false;
            for (;;) {
                const size_t n = store_sessions_scan_window(&src, have_after ? &after : NULL,
                                                            work, SCAN_WORK);
                if (n == 0) {
                    break;
                }
                for (size_t i = 0; i < n; i++) {
                    bool claimed = false;
                    for (size_t c = 0; c < count; c++) {
                        if (strcmp(work[i].name, claims[c].name) == 0) {
                            claimed = true;
                            break;
                        }
                    }
                    if (!claimed && work[i].readings > 0) {
                        out->sessions_refused++;
                    }
                }
                after = store_sessions_key(&work[n - 1]);
                have_after = true;
                if (n < SCAN_WORK) {
                    break;
                }
            }
        }
        unlock();

        if (confirmed_count == 0) {
            snprintf(out->message, sizeof(out->message),
                     "no session could be confirmed backed up; nothing was erased");
            return ESP_OK;
        }
    }

    /* Sector by sector. A sector goes only when every page in it is confirmed,
     * so a session still recording keeps the whole sector its newest page is in,
     * and the confirmed pages sharing that sector are kept with it. */
    uint8_t header[STORE_HEADER_BYTES];
    store_header_t peek;
    for (uint32_t base = 0; base + SLOTS_PER_SECTOR <= s.slots; base += SLOTS_PER_SECTOR) {
        lock();
        uint32_t pages = 0;
        uint32_t readings = 0;
        bool all_confirmed = true;
        for (uint32_t i = 0; i < SLOTS_PER_SECTOR; i++) {
            const uint32_t slot = base + i;
            if (esp_partition_read(s.partition, (size_t)slot * STORE_PAGE_BYTES, header,
                                   sizeof(header)) != ESP_OK) {
                all_confirmed = false;
                break;
            }
            if (!store_peek_page(header, &peek)) {
                continue; /* blank: costs nothing either way */
            }
            pages++;
            readings += peek.count;
            if (override) {
                continue;
            }
            bool ok = false;
            for (size_t k = 0; k < confirmed_count; k++) {
                if (peek.session == confirmed[k].session &&
                    peek.session_start_ms == confirmed[k].start_ms) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                all_confirmed = false;
            }
        }

        if (pages > 0 && all_confirmed) {
            const size_t offset = (size_t)base * STORE_PAGE_BYTES;
            if (esp_partition_erase_range(s.partition, offset, SECTOR_BYTES) == ESP_OK) {
                out->pages_erased += pages;
                out->readings_erased += readings;
            } else {
                ESP_LOGE(TAG, "erase of sector at %u failed", (unsigned)offset);
            }
        } else if (pages > 0) {
            out->pages_kept_by_sector += pages;
        }
        unlock();
    }

    /* Rebuild everything from what is left on flash, which is the only account
     * of the ring that can be trusted after this. */
    lock();
    store_sessions_reset(&s.sessions);
    s.writer_open = false;
    s.writer_session_known = false;
    s.stats.slots_used = 0;
    scan_ring();
    unlock();

    if (override) {
        snprintf(out->message, sizeof(out->message),
                 "erased on the override, without checking for a backup");
    } else if (out->sessions_refused > 0) {
        snprintf(out->message, sizeof(out->message),
                 "erased %" PRIu32 " confirmed session(s); kept %" PRIu32 " unconfirmed",
                 out->sessions_confirmed, out->sessions_refused);
    } else {
        snprintf(out->message, sizeof(out->message), "erased %" PRIu32 " confirmed session(s)",
                 out->sessions_confirmed);
    }
    ESP_LOGW(TAG, "erase: %s (%" PRIu32 " pages, %" PRIu32 " readings)", out->message,
             out->pages_erased, out->readings_erased);
    return ESP_OK;
}

/* --- backing everything up and clearing the board -------------------------- */
/*
 * The wiring for `store_drain.h`, and nothing more than wiring: the rule about
 * what may be freed and when lives there, where the host pack can drive every
 * one of its failure paths. This half is the partition, the lock, and keeping
 * the rest of `store.c` honest about what has just gone.
 *
 * `store_erase_confirmed` above is untouched by all of this and behaves exactly
 * as it did. The captain asked for a separate control and a separate path, and
 * the reason is worth stating: the erase gate is the thing every other feature
 * has been tested against for weeks, and the way to not break it is to not
 * change it.
 */

static bool drain_read(void *ctx, size_t offset, uint8_t *out, size_t len)
{
    (void)ctx;
    return s.partition != NULL &&
           esp_partition_read(s.partition, offset, out, len) == ESP_OK;
}

static bool drain_erase(void *ctx, size_t offset, size_t len)
{
    (void)ctx;
    return s.partition != NULL &&
           esp_partition_erase_range(s.partition, offset, len) == ESP_OK;
}

/** Rescan the preservation area and let the rate floor answer to what is left. */
static void drain_represerve_locked(void)
{
    const store_preserve_dev_t dev = {
        .size = PRESERVE_BYTES,
        .header_bytes = PRESERVE_HEADER_BYTES,
        .ctx = NULL,
        .read = preserve_read,
        .write = preserve_write_bytes,
        .erase = preserve_erase,
    };
    if (!store_preserve_begin(&s.preserve, &dev)) {
        ESP_LOGE(TAG, "the preservation area would not restart after a drained block");
        return;
    }
    preserve_review_rate();
}

esp_err_t store_drain_open(int owner)
{
    if (!s.ready) {
        return ESP_ERR_INVALID_STATE;
    }
    lock();
    if (s.import_open) {
        unlock();
        return ESP_ERR_INVALID_STATE; /* see store_import_open for the other half */
    }
    if (s.drain.open && s.drain.owner != owner) {
        unlock();
        return ESP_ERR_INVALID_STATE;
    }
    if (s.drain.open) {
        unlock();
        return ESP_OK; /* the same device asking again; already theirs */
    }
    unlock();

    /* Everything still in RAM belongs to the record, so it is sealed before a
     * single block is chosen: readings in an unsealed page would otherwise be
     * missing from the copy and still be on the board afterwards. */
    store_flush();

    lock();
    /*
     * And the open excursion is closed, rather than left accumulating.
     *
     * A segment's header records where its samples are. If it stayed open across
     * a drain, the header written when it eventually closed would point into a
     * region this operation has since erased and rescanned - a summary of an
     * event the board cannot show you. Closing it now writes an honest header
     * over the samples that exist, and those samples are then backed up and
     * cleared with everything else.
     */
    track_close(&s.live);

    const store_drain_dev_t dev = {
        .ctx = NULL,
        .read = drain_read,
        .erase = drain_erase,
        .ring_bytes = (size_t)s.slots * STORE_PAGE_BYTES,
        .preserve_base = s.preserve_base,
        .preserve_header_bytes = PRESERVE_HEADER_BYTES,
        .preserve_bytes = PRESERVE_BYTES,
    };
    const bool ok = store_drain_begin(&s.drain, &dev, owner);
    unlock();
    if (!ok) {
        return ESP_ERR_INVALID_STATE;
    }
    ESP_LOGW(TAG, "client %d began a back-up-and-clear of this board", owner);
    return ESP_OK;
}

void store_drain_close(int owner)
{
    if (s.lock == NULL) {
        return;
    }
    lock();
    if (s.drain.open && s.drain.owner == owner) {
        ESP_LOGW(TAG,
                 "client %d ended its back-up-and-clear: %" PRIu32 " blocks freed, %" PRIu32
                 " readings copied, %" PRIu32 " freed",
                 owner, s.drain.blocks_freed, s.drain.readings_copied, s.drain.readings_freed);
        store_drain_end(&s.drain);
    }
    unlock();
}

bool store_drain_running(int owner)
{
    if (s.lock == NULL) {
        return false;
    }
    lock();
    const bool mine = store_drain_is_owner(&s.drain, owner);
    unlock();
    return mine;
}

void store_drain_progress(store_drain_progress_t *out)
{
    if (out == NULL) {
        return;
    }
    memset(out, 0, sizeof(*out));
    if (s.lock == NULL) {
        return;
    }
    lock();
    out->open = s.drain.open;
    out->finished = s.drain.finished;
    out->blocks_freed = s.drain.blocks_freed;
    out->blocks_kept = s.drain.blocks_kept;
    out->blocks_refused = s.drain.blocks_refused;
    out->readings_copied = s.drain.readings_copied;
    out->segments_copied = s.drain.segments_copied;
    out->readings_freed = s.drain.readings_freed;
    out->blocks_left = s.drain.open ? store_drain_blocks_left(&s.drain) : 0;
    unlock();
}

bool store_drain_take(int owner, store_drain_block_t *out)
{
    if (s.lock == NULL || out == NULL) {
        return false;
    }
    lock();
    const bool ok = store_drain_is_owner(&s.drain, owner) &&
                    store_drain_choose(&s.drain, s.slots, s.head, out);
    unlock();
    return ok;
}

uint32_t store_drain_rows(int owner, const store_drain_block_t *block, uint32_t from_row,
                          store_drain_reading_fn on_reading, store_drain_segment_fn on_segment,
                          void *ctx)
{
    if (s.lock == NULL || block == NULL) {
        return 0;
    }
    lock();
    uint32_t sent = 0;
    if (store_drain_is_owner(&s.drain, owner)) {
        if (block->kind == STORE_DRAIN_SEGMENTS) {
            sent = store_drain_walk_segments(&s.drain, block, from_row, on_segment, ctx);
        } else {
            /* ::s_scratch_page, under the lock for the whole of decode-and-use,
             * exactly as the erase's digest and the preservation sweep borrow
             * it. See the note on that buffer. */
            sent = store_drain_walk_readings(&s.drain, block, from_row, &s_scratch_page,
                                             on_reading, ctx);
        }
    }
    unlock();
    return sent;
}

bool store_drain_seal(int owner, const store_drain_block_t *block, uint32_t *rows,
                      uint32_t *digest)
{
    if (s.lock == NULL || block == NULL || rows == NULL || digest == NULL) {
        return false;
    }
    lock();
    bool ok = false;
    if (store_drain_is_owner(&s.drain, owner)) {
        ok = store_drain_measure(&s.drain, block, &s_scratch_page, rows, digest);
        if (ok) {
            store_drain_sent(&s.drain, *rows, *digest);
        }
    }
    unlock();
    return ok;
}

store_drain_verdict_t store_drain_answer(int owner, uint32_t id, uint32_t rows, uint32_t digest)
{
    if (s.lock == NULL) {
        return STORE_DRAIN_NOT_OFFERED;
    }
    lock();
    if (!store_drain_is_owner(&s.drain, owner) || !s.drain.have_held || s.drain.held.id != id) {
        unlock();
        return STORE_DRAIN_NOT_OFFERED;
    }
    const store_drain_block_t block = s.drain.held;

    /*
     * The account of what the board holds is read *before* the erase, because
     * afterwards there is nothing left to read, and applied *after* it, because
     * a confirmation that is refused must leave that account exactly as it was.
     * The same two halves ::write_page uses when the ring reclaims a sector -
     * which is the same event, arrived at from the other direction.
     */
    doomed_t gone;
    gone.count = 0;
    if (block.kind == STORE_DRAIN_RING) {
        read_doomed(block.slot, &gone);
    }

    const store_drain_verdict_t verdict =
        store_drain_confirm(&s.drain, id, rows, digest, &s_scratch_page);

    if (verdict == STORE_DRAIN_FREED) {
        s.stats.pages_erased += block.records;
        if (block.kind == STORE_DRAIN_RING) {
            forget_doomed(&gone);
        } else {
            /* The preservation area keeps its own heads and sequence numbers,
             * and they are recovered by scanning rather than remembered - so a
             * block of it going means reading them again. Cheap, and it is what
             * lets the rate floor come back down as the pressure this operation
             * removes actually goes. */
            drain_represerve_locked();
        }
    }
    unlock();

    if (verdict == STORE_DRAIN_KEPT) {
        /*
         * The readings that arrived during the copy, sealed before the account
         * of the ring is thrown away.
         *
         * ::scan_ring below rebuilds everything from flash, and part of that is
         * abandoning the page the writer is filling. Those readings are real and
         * are the newest thing the board has - they are precisely the "small
         * amount of data that arrived during the operation" this is meant to
         * keep - so they go onto flash first. Outside the lock, because
         * ::store_flush takes it and it is not a recursive mutex.
         */
        store_flush();

        /*
         * The end of the sweep, and the point at which the board becomes clean.
         *
         * Everything before this block has gone; this one is the readings that
         * arrived while the copy was running and is deliberately kept. What is
         * left to do is bookkeeping, and the honest way to do it is the way the
         * board does it on the way up: throw away every derived account of the
         * ring and rebuild it from what is actually on flash. That is what makes
         * the end state a properly initialised store rather than an emptied one
         * with stale metadata in front of it.
         *
         * What is deliberately *not* reset is `page_seq` and the wrap count.
         * They are monotonic for the life of the device by design, and
         * ::store_run_marker depends on that: two runs of a clockless board that
         * carried the same marker would be presented to a reader as one unbroken
         * stretch of recording. Because the newest block is kept, the sequence
         * lineage is intact and ::scan_ring re-derives the true wrap count from
         * it - so this is a store that has been emptied and re-initialised, on a
         * board that still knows how long it has been running.
         */
        lock();
        drain_represerve_locked();
        store_sessions_reset(&s.sessions);
        s.writer_open = false;
        s.writer_session_known = false;
        s.stats.slots_used = 0;
        /* Rebuilt by the scan from whatever anchor pages survived, which on a
         * cleared board is the ones in the block that was kept. `relative_seen`
         * is deliberately left alone: it says the board records against its own
         * clock, which is a fact about the board and not about its storage. */
        s.anchor_count = 0;
        scan_ring();
        const uint32_t freed = s.drain.blocks_freed;
        const uint32_t copied = s.drain.readings_copied;
        const uint32_t segments = s.drain.segments_copied;
        unlock();
        ESP_LOGW(TAG,
                 "back-up-and-clear finished: %" PRIu32 " blocks freed, %" PRIu32
                 " readings and %" PRIu32 " preserved segments copied off, and the block "
                 "being written kept as the new start of storage",
                 freed, copied, segments);
    }
    return verdict;
}

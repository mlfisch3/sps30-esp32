/**
 * @file store_sessions.c
 * @brief The session index: an in-RAM cache, and the ring it is a cache of.
 *
 * See `store_sessions.h` for what this is for and why a fixed table cannot be
 * the whole answer at any size. Four things are worth reading the code for:
 *
 * - **A summary is one definition.** ::summary_begin and ::summary_fold are used
 *   by the cache and by the scan alike, so "what the board says about a session"
 *   cannot come out differently depending on which one answered.
 * - **The cache array is sorted by `last_seq`, always.** Every path that
 *   changes a slot's sequence puts it back where it belongs before returning, so
 *   `slot[0]` is the next session the ring will finish reclaiming at every
 *   instant, including halfway through the boot scan.
 * - **Forgetting is exact, not approximate.** An erased page carries the count
 *   and the index range it held, so the summary is reduced by what actually
 *   went rather than re-derived by a second pass over flash.
 * - **The scan needs no memory of what it has emitted.** ::store_sessions_scan_window
 *   decides "behind the window", "in the window" or "ahead of the window" from
 *   the page in front of it, which is what lets it page through more sessions
 *   than RAM could hold summaries for. The argument that it cannot skip or
 *   double-count one is in that function.
 */

#include "store_sessions.h"

#include <stdio.h>
#include <string.h>

#include "wire.h"

/** Whether two headers describe the same session. */
static bool same_session(const store_session_t *summary, const store_header_t *page)
{
    return summary->session == page->session && summary->start_ms == page->session_start_ms;
}

/**
 * Put slot @p at back in sequence order, and say where it ended up.
 *
 * An insertion sort step over at most ::STORE_SESSION_INDEX_MAX entries, and in
 * the ordinary case it moves nothing: pages arrive in `page_seq` order and the
 * session being written already holds the largest one. It earns its keep on the
 * boot scan, which walks slots rather than sequences, and when an import writes
 * a page belonging to a session that is not the newest.
 */
static size_t resettle(store_sessions_t *t, size_t at)
{
    const store_session_t moving = t->slot[at];
    size_t i = at;
    while (i > 0 && t->slot[i - 1].last_seq > moving.last_seq) {
        t->slot[i] = t->slot[i - 1];
        i--;
    }
    while (i + 1 < t->count && t->slot[i + 1].last_seq < moving.last_seq) {
        t->slot[i] = t->slot[i + 1];
        i++;
    }
    t->slot[i] = moving;
    return i;
}

/** Drop slot @p at, closing the gap and keeping the order. */
static void drop(store_sessions_t *t, size_t at)
{
    if (at + 1 < t->count) {
        memmove(&t->slot[at], &t->slot[at + 1],
                sizeof(t->slot[0]) * (t->count - at - 1));
    }
    t->count--;
}

/**
 * Name a session from its header.
 *
 * A pure function of the header in both forms, which is what lets the name be
 * re-derived after the reboot that rebuilds the cache - and what lets the scan
 * recognise a name it was never told. A name that could not be re-derived would
 * be a session that renamed itself overnight.
 */
void store_sessions_derive_name(const store_header_t *page, char *out, size_t cap)
{
    if (page->relative) {
        /* No clock ran during this session, so there is no date to name it
         * after. The run and the position in it are what there is. */
        wire_rel_csv_name(store_rel_run(page->session_start_ms),
                          store_rel_ms(page->session_start_ms), out, cap);
    } else {
        wire_csv_name(page->session_start_ms / 1000, page->utc_offset_min * 60, out, cap);
    }
}

/** The name rule, under the short name the rest of this file has always used. */
static void derive_name(const store_header_t *page, char *out, size_t cap)
{
    store_sessions_derive_name(page, out, cap);
}

/* --- what a summary is ----------------------------------------------------- */

/** Start a summary from the first page of it seen. */
static void summary_begin(store_session_t *entry, const store_header_t *page, const char *name)
{
    memset(entry, 0, sizeof(*entry));
    entry->session = page->session;
    entry->start_ms = page->session_start_ms;
    entry->utc_offset_min = page->utc_offset_min;
    entry->cadence_ms = page->cadence_ms;
    entry->first_index = page->first_index;
    entry->last_index = page->first_index + page->count - 1;
    entry->readings = page->count;
    entry->pages = 1;
    entry->first_seq = page->page_seq;
    entry->last_seq = page->page_seq;
    entry->relative = page->relative;
    if (name != NULL && name[0] != '\0') {
        snprintf(entry->name, sizeof(entry->name), "%s", name);
    } else {
        derive_name(page, entry->name, sizeof(entry->name));
    }
}

/**
 * Fold one more page of the same session in.
 *
 * Commutative in the pages, deliberately: the cache is fed in write order and
 * the scan in slot order, and neither may produce a different summary from the
 * same set of pages.
 */
static void summary_fold(store_session_t *entry, const store_header_t *page)
{
    if (page->first_index < entry->first_index) {
        entry->first_index = page->first_index;
    }
    const uint32_t last = page->first_index + page->count - 1;
    if (last > entry->last_index) {
        entry->last_index = last;
    }
    entry->readings += page->count;
    entry->pages++;
    /* The span the cursor opens on. Both ends widen and neither ever narrows -
     * see ::store_session_t::first_seq for why that direction is the safe one. */
    if (page->page_seq < entry->first_seq) {
        entry->first_seq = page->page_seq;
    }
    if (page->page_seq > entry->last_seq) {
        entry->last_seq = page->page_seq;
    }
}

void store_sessions_reset(store_sessions_t *t)
{
    if (t == NULL) {
        return;
    }
    memset(t, 0, sizeof(*t));
}

const store_session_t *store_sessions_note(store_sessions_t *t, const store_header_t *page,
                                           const char *name)
{
    if (t == NULL || page == NULL) {
        return NULL;
    }
    if (page->anchor) {
        return NULL; /* an equivalence belongs to a run, not to a session */
    }

    for (size_t i = 0; i < t->count; i++) {
        if (!same_session(&t->slot[i], page)) {
            continue;
        }
        summary_fold(&t->slot[i], page);
        return &t->slot[resettle(t, i)];
    }

    if (t->count == STORE_SESSION_INDEX_MAX) {
        /*
         * Every summary in the cache has live pages, or forgetting them would
         * have dropped one already. So the cache is genuinely full, and what
         * used to happen here was the bug: the oldest summary was dropped and
         * its readings became unreachable, because this table was the only
         * thing that could name them.
         *
         * It is not any more. The ring carries the identity of every session in
         * every page header, ::store_sessions_scan_find reads it, and `store.c`
         * consults it whenever ::incomplete is set. So the drop below costs a
         * slower lookup and nothing else, and the new session - the one being
         * written, the one a client is most likely to ask for - keeps the slot.
         *
         * Which one goes is still slot zero, the session the ring will finish
         * reclaiming first, so the cache holds the sessions that will be around
         * longest.
         */
        t->forgotten++;
        t->incomplete = true;
        drop(t, 0);
    }

    summary_begin(&t->slot[t->count++], page, name);
    return &t->slot[resettle(t, t->count - 1)];
}

bool store_sessions_forget(store_sessions_t *t, const store_header_t *page)
{
    if (t == NULL || page == NULL || page->anchor) {
        return false;
    }
    for (size_t i = 0; i < t->count; i++) {
        if (!same_session(&t->slot[i], page)) {
            continue;
        }
        store_session_t *entry = &t->slot[i];
        entry->readings = entry->readings > page->count ? entry->readings - page->count : 0;
        if (entry->pages > 0) {
            entry->pages--;
        }
        if (entry->pages == 0) {
            drop(t, i);
            return true;
        }
        /*
         * The ring erases in sequence order, so the page that just went is
         * normally the session's oldest and what remains starts one past it.
         * Normally, not always: an import appends pages for an old session at
         * new sequences, so a page from the middle of a session can be reclaimed
         * while earlier ones are still held. Advancing only when this page
         * really was the front is what keeps `first_index` a lower bound on what
         * can still be read rather than a claim that outruns the pages.
         */
        if (page->first_index <= entry->first_index) {
            entry->first_index = page->first_index + page->count;
        }
        /*
         * `first_seq` and `last_seq` are deliberately not moved.
         *
         * They exist so a cursor can skip slots that cannot hold a page of this
         * session, and a bound that is too wide costs a few forty-eight-byte
         * reads while a bound that is too narrow loses readings. Narrowing them
         * correctly would need the *next* page's sequence, which an unfold does
         * not have. So they only ever widen, and the walk they plan is a superset
         * of the pages that are really there.
         */
        return false;
    }
    return false;
}

size_t store_sessions_list(const store_sessions_t *t, store_session_t *out, size_t cap)
{
    return store_sessions_list_from(t, 0, out, cap);
}

size_t store_sessions_list_from(const store_sessions_t *t, uint32_t skip,
                                store_session_t *out, size_t cap)
{
    if (t == NULL || out == NULL) {
        return 0;
    }
    if ((size_t)skip >= t->count) {
        return 0;
    }
    size_t n = 0;
    for (size_t i = t->count - (size_t)skip; i > 0 && n < cap; i--) {
        out[n++] = t->slot[i - 1];
    }
    return n;
}

const store_session_t *store_sessions_find(const store_sessions_t *t, const char *name)
{
    if (t == NULL || name == NULL) {
        return NULL;
    }
    for (size_t i = t->count; i > 0; i--) {
        if (strcmp(t->slot[i - 1].name, name) == 0) {
            return &t->slot[i - 1];
        }
    }
    return NULL;
}

uint32_t store_sessions_readings(const store_sessions_t *t)
{
    if (t == NULL) {
        return 0;
    }
    uint32_t total = 0;
    for (size_t i = 0; i < t->count; i++) {
        total += t->slot[i].readings;
    }
    return total;
}

uint32_t store_sessions_pages(const store_sessions_t *t)
{
    if (t == NULL) {
        return 0;
    }
    uint32_t total = 0;
    for (size_t i = 0; i < t->count; i++) {
        total += t->slot[i].pages;
    }
    return total;
}

/* --- the ring as the index ------------------------------------------------- */

store_session_key_t store_sessions_key(const store_session_t *summary)
{
    store_session_key_t key;
    key.start_ms = summary == NULL ? 0 : summary->start_ms;
    key.session = summary == NULL ? 0 : summary->session;
    return key;
}

/** The key a page's session sorts under. Readable off the header alone. */
static store_session_key_t key_of_page(const store_header_t *page)
{
    store_session_key_t key;
    key.start_ms = page->session_start_ms;
    key.session = page->session;
    return key;
}

/** Negative, zero or positive as @p a sorts before, with, or after @p b. */
static int key_cmp(const store_session_key_t *a, const store_session_key_t *b)
{
    if (a->start_ms != b->start_ms) {
        return a->start_ms < b->start_ms ? -1 : 1;
    }
    if (a->session != b->session) {
        return a->session < b->session ? -1 : 1;
    }
    return 0;
}

/** Whether the source can be walked at all. */
static bool source_ok(const store_page_source_t *src)
{
    return src != NULL && src->read != NULL;
}

bool store_sessions_scan_find(const store_page_source_t *src, const char *name,
                              store_session_t *out)
{
    if (!source_ok(src) || name == NULL || name[0] == '\0' || out == NULL) {
        return false;
    }
    bool have = false;
    store_header_t page;
    for (uint32_t slot = 0; slot < src->slots; slot++) {
        if (!src->read(src->ctx, slot, &page) || page.anchor) {
            continue;
        }
        if (have) {
            /* Both halves of the identity, not the name: two boots can produce
             * the same name and their readings must not be folded together. */
            if (same_session(out, &page)) {
                summary_fold(out, &page);
            }
            continue;
        }
        char derived[sizeof(out->name)];
        derive_name(&page, derived, sizeof(derived));
        if (strcmp(derived, name) != 0) {
            continue;
        }
        summary_begin(out, &page, name);
        have = true;
    }
    return have;
}

size_t store_sessions_scan_window(const store_page_source_t *src,
                                  const store_session_key_t *after,
                                  store_session_t *out, size_t cap)
{
    if (!source_ok(src) || out == NULL || cap == 0) {
        return 0;
    }
    size_t n = 0;
    store_header_t page;
    for (uint32_t slot = 0; slot < src->slots; slot++) {
        if (!src->read(src->ctx, slot, &page) || page.anchor) {
            continue;
        }
        const store_session_key_t key = key_of_page(&page);
        if (after != NULL && key_cmp(&key, after) <= 0) {
            continue; /* a session an earlier window already answered for */
        }
        bool folded = false;
        for (size_t i = 0; i < n; i++) {
            if (same_session(&out[i], &page)) {
                summary_fold(&out[i], &page);
                folded = true;
                break;
            }
        }
        if (folded) {
            continue;
        }
        /*
         * A session not in the window. Either it belongs here, or it belongs to
         * a later window - and this is the decision that has to be taken from
         * this page alone, because remembering the sessions already dealt with
         * is exactly the memory this function does not have.
         *
         * It can be, because `out` holds the smallest `n` keys seen so far and
         * its largest, `out[n - 1]`, only ever decreases: a session is displaced
         * only when a smaller-keyed one takes its place. So every key ever
         * displaced is greater than the current largest, which makes one
         * comparison separate the two cases that must be skipped - a session
         * later in the order, and a session displaced earlier in this pass -
         * from the case that must be taken.
         */
        if (n == cap) {
            const store_session_key_t largest = store_sessions_key(&out[n - 1]);
            if (key_cmp(&key, &largest) > 0) {
                continue;
            }
            n--; /* displaced; a later window will summarise it from scratch */
        }
        size_t at = n;
        while (at > 0) {
            const store_session_key_t before = store_sessions_key(&out[at - 1]);
            if (key_cmp(&key, &before) >= 0) {
                break;
            }
            out[at] = out[at - 1];
            at--;
        }
        summary_begin(&out[at], &page, NULL);
        n++;
    }
    return n;
}

uint32_t store_sessions_scan_count(const store_page_source_t *src, store_session_t *work,
                                   size_t work_cap)
{
    if (!source_ok(src) || work == NULL || work_cap == 0) {
        return 0;
    }
    uint32_t total = 0;
    store_session_key_t after;
    bool have_after = false;
    for (;;) {
        const size_t n =
            store_sessions_scan_window(src, have_after ? &after : NULL, work, work_cap);
        if (n == 0) {
            break;
        }
        total += (uint32_t)n;
        after = store_sessions_key(&work[n - 1]);
        have_after = true;
        if (n < work_cap) {
            break;
        }
    }
    return total;
}

size_t store_sessions_scan_newest(const store_page_source_t *src, uint32_t skip,
                                  store_session_t *out, size_t cap, uint32_t *total_out)
{
    if (total_out != NULL) {
        *total_out = 0;
    }
    if (!source_ok(src) || out == NULL || cap == 0) {
        return 0;
    }

    const uint32_t total = store_sessions_scan_count(src, out, cap);
    if (total_out != NULL) {
        *total_out = total;
    }
    if (skip >= total) {
        return 0; /* past the end of a listing that may have shrunk under us */
    }

    /*
     * Where the page begins, counted from the oldest.
     *
     * The scan is ascending and the listing is descending, so the page wanted -
     * newest-first entries `skip .. skip + want - 1` - is a contiguous run of
     * `want` sessions ending `skip` from the newest end. Counting from the
     * oldest, that run starts after `total - skip - want` of them.
     */
    const uint32_t left = total - skip;
    const size_t want = left < (uint32_t)cap ? (size_t)left : cap;
    uint32_t to_skip = total - skip - (uint32_t)want;

    store_session_key_t after;
    bool have = false;
    while (to_skip > 0) {
        const size_t step = to_skip < (uint32_t)cap ? (size_t)to_skip : cap;
        const size_t n = store_sessions_scan_window(src, have ? &after : NULL, out, step);
        if (n > 0) {
            after = store_sessions_key(&out[n - 1]);
            have = true;
            to_skip -= (uint32_t)n;
        }
        if (n < step) {
            /* The ring reclaimed pages while we walked, so there are fewer
             * sessions now than the count this page was planned against. Stop
             * where we got to and let the window below answer - a short page is
             * honest, where restarting from the oldest would hand back sessions
             * an earlier page already named as though they were new ones. */
            break;
        }
    }

    const size_t n = store_sessions_scan_window(src, have ? &after : NULL, out, want);
    for (size_t lo = 0, hi = n; lo + 1 < hi; lo++) {
        hi--;
        const store_session_t tmp = out[lo];
        out[lo] = out[hi];
        out[hi] = tmp;
    }
    return n;
}

uint32_t store_sessions_scan_readings(const store_page_source_t *src, uint32_t *pages)
{
    uint32_t readings = 0;
    uint32_t held = 0;
    if (source_ok(src)) {
        store_header_t page;
        for (uint32_t slot = 0; slot < src->slots; slot++) {
            if (!src->read(src->ctx, slot, &page) || page.anchor) {
                continue;
            }
            readings += page.count;
            held++;
        }
    }
    if (pages != NULL) {
        *pages = held;
    }
    return readings;
}

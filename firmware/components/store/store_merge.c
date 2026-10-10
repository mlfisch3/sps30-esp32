/**
 * @file store_merge.c
 * @brief The merge, and nothing but. See `store_merge.h` for why it is safe.
 */

#include "store_merge.h"

#include <string.h>

/* --- the held set ---------------------------------------------------------- */

void store_held_reset(store_held_t *held)
{
    memset(held, 0, sizeof(*held));
}

bool store_held_add(store_held_t *held, uint32_t first, uint32_t last)
{
    if (first > last) {
        return true; /* an empty page contributes nothing, and is not a failure */
    }

    /* Find where this run belongs, absorbing every span it touches on the way.
     * Touching includes abutting - [1,40] and [41,80] are one run of eighty, and
     * keeping them apart would cost a span for every page of a session. */
    uint16_t i = 0;
    while (i < held->count && held->spans[i].last + 1 < first) {
        i++;
    }
    uint16_t j = i;
    while (j < held->count && held->spans[j].first <= last + 1) {
        if (held->spans[j].first < first) {
            first = held->spans[j].first;
        }
        if (held->spans[j].last > last) {
            last = held->spans[j].last;
        }
        j++;
    }

    if (j > i) {
        /* Replace the absorbed run [i, j) with the one span it became. */
        held->spans[i].first = first;
        held->spans[i].last = last;
        const uint16_t removed = (uint16_t)(j - i - 1);
        if (removed > 0) {
            memmove(&held->spans[i + 1], &held->spans[j],
                    (size_t)(held->count - j) * sizeof(held->spans[0]));
            held->count = (uint16_t)(held->count - removed);
        }
        return true;
    }

    if (held->count >= STORE_MERGE_MAX_SPANS) {
        held->overflowed = true;
        return false;
    }
    memmove(&held->spans[i + 1], &held->spans[i],
            (size_t)(held->count - i) * sizeof(held->spans[0]));
    held->spans[i].first = first;
    held->spans[i].last = last;
    held->count++;
    return true;
}

bool store_held_contains(const store_held_t *held, uint32_t index)
{
    for (uint16_t i = 0; i < held->count; i++) {
        if (index < held->spans[i].first) {
            return false; /* sorted: nothing later can contain it either */
        }
        if (index <= held->spans[i].last) {
            return true;
        }
    }
    return false;
}

uint32_t store_held_readings(const store_held_t *held)
{
    uint32_t total = 0;
    for (uint16_t i = 0; i < held->count; i++) {
        total += held->spans[i].last - held->spans[i].first + 1u;
    }
    return total;
}

/* --- the merge ------------------------------------------------------------- */

void store_merge_begin(store_merge_t *merge, uint32_t session, int64_t session_start_ms,
                       uint32_t cadence_ms, int16_t utc_offset_min)
{
    memset(merge, 0, sizeof(*merge));
    merge->session = session;
    merge->session_start_ms = session_start_ms;
    merge->cadence_ms = cadence_ms ? cadence_ms : 1000u;
    merge->utc_offset_min = utc_offset_min;
}

bool store_merge_note_held(store_merge_t *merge, uint32_t first_index, uint32_t count)
{
    if (count == 0) {
        return true;
    }
    return store_held_add(&merge->held, first_index, first_index + count - 1u);
}

/** Seal the open page and hand it to the sink. False = the sink had no room. */
static bool seal_open_page(store_merge_t *merge, const store_sink_t *sink, bool full)
{
    if (!merge->writer_open || store_writer_empty(&merge->writer)) {
        merge->writer_open = false;
        return true;
    }
    uint8_t page[STORE_PAGE_BYTES];
    if (!store_writer_seal(&merge->writer, page, full)) {
        merge->writer_open = false;
        return true;
    }
    if (!sink->commit(sink->ctx, page)) {
        /* The page is lost, and with it the readings it held - but they are
         * readings that were *not* on this controller a moment ago, so nothing
         * the controller had has changed. That asymmetry is the whole design:
         * a failed import costs the import, never the archive. */
        merge->writer_open = false;
        merge->stats.no_room = true;
        return false;
    }
    merge->writer_open = false;
    merge->stats.pages++;
    return true;
}

/** Open a page for @p index. */
static void open_page(store_merge_t *merge, const store_sink_t *sink)
{
    store_writer_init(&merge->writer, sink->next_seq(sink->ctx), merge->session,
                      merge->session_start_ms, merge->cadence_ms, merge->utc_offset_min,
                      false);
    merge->writer_open = true;
}

store_merge_verdict_t store_merge_add(store_merge_t *merge, const store_reading_t *reading,
                                      const store_sink_t *sink)
{
    if (merge->stats.no_room) {
        /* Counted, not merely returned. A row that is neither stored nor
         * counted appears in no number the sender ever sees, and `added +
         * duplicate + rejected` short of the rows sent is exactly how readings
         * go missing with everybody believing they arrived. */
        merge->stats.rejected++;
        return STORE_MERGE_NO_ROOM;
    }
    /* The held set is incomplete, so "is this already here?" can now answer no
     * when the truth is yes. Adding on that answer would put a second copy of a
     * reading into the ring, so the import stops instead. */
    if (merge->stats.fragmented) {
        merge->stats.rejected++;
        return STORE_MERGE_REJECTED;
    }
    /* Indices are 1-based. A zero is a row this controller cannot place, and
     * placing it anyway would put a reading under an index another one owns. */
    if (reading->index == 0) {
        merge->stats.rejected++;
        return STORE_MERGE_REJECTED;
    }
    if (store_held_contains(&merge->held, reading->index)) {
        merge->stats.duplicate++;
        return STORE_MERGE_DUPLICATE;
    }
    /* The held set is grown as readings are accepted, below, so a file that
     * lists the same index twice sees the second one as the duplicate it now is.
     * There is no separate ordering rule: rows out of index order cost pages
     * (a page covers a contiguous run, so a gap seals one) but never
     * correctness, and refusing them would refuse a re-export of a session that
     * had itself been merged, which reads back in page order. */

    if (!merge->writer_open) {
        open_page(merge, sink);
    }
    if (!store_writer_add(&merge->writer, reading)) {
        /* Either the page filled or the index is not the next one - a gap, which
         * ends a page so the hole stays visible. Both mean: seal, start again. */
        if (!seal_open_page(merge, sink, true)) {
            merge->stats.rejected++; /* it was not stored; see the head of this function */
            return STORE_MERGE_NO_ROOM;
        }
        open_page(merge, sink);
        if (!store_writer_add(&merge->writer, reading)) {
            /* It did not fit an empty page, which cannot happen for a
             * well-formed reading. Count it rather than trusting that. */
            merge->stats.rejected++;
            return STORE_MERGE_REJECTED;
        }
    }
    merge->stats.added++;
    merge->last_added = reading->index;
    /* Held from here on. The reading is in the open page, and an open page is
     * sealed before the import ends, so counting it as held is true by the time
     * anyone can observe it - and it is what makes a repeated row a duplicate
     * rather than a second copy. */
    if (!store_held_add(&merge->held, reading->index, reading->index)) {
        merge->stats.fragmented = true;
    }
    return STORE_MERGE_ADDED;
}

bool store_merge_finish(store_merge_t *merge, const store_sink_t *sink)
{
    /* Not full: this page ended because the file did, not because it filled. */
    return seal_open_page(merge, sink, false);
}

bool store_merge_needs_seal(bool writer_open, const char *writer_name, const char *import_name)
{
    if (!writer_open || writer_name == NULL || import_name == NULL) {
        return false;
    }
    if (writer_name[0] == '\0' || import_name[0] == '\0') {
        return false;
    }
    /* By name, because a name is what both ends of this already agree on: the
     * archive's entry, the header a page carries, and the summary a lookup
     * finds are the same string. Comparing the session numbers instead would
     * ask the caller to resolve an identity it has not resolved yet - that is
     * what the merge is about to do - and would get it wrong for exactly the
     * session that matters, the one still being recorded. */
    return strcmp(writer_name, import_name) == 0;
}

/* --- the digest ------------------------------------------------------------ */

/** Floor division by 1000, so a pre-epoch instant does not round the wrong way. */
static int64_t floor_seconds(int64_t ms)
{
    return ms >= 0 ? ms / 1000 : -(((-ms) + 999) / 1000);
}

static void put_le32(uint8_t *out, uint32_t v)
{
    out[0] = (uint8_t)(v & 0xFFu);
    out[1] = (uint8_t)((v >> 8) & 0xFFu);
    out[2] = (uint8_t)((v >> 16) & 0xFFu);
    out[3] = (uint8_t)((v >> 24) & 0xFFu);
}

uint32_t store_digest_reading(const store_reading_t *reading)
{
    /* index | epoch seconds | settling | ten quantised values, little-endian.
     * Fixed width throughout, so the bytes a phone builds from a CSV row and the
     * bytes the controller builds from flash are the same bytes. */
    uint8_t canonical[4 + 8 + 1 + STORE_FIELDS * 4];
    put_le32(canonical, reading->index);
    const int64_t seconds = floor_seconds(reading->epoch_ms);
    put_le32(canonical + 4, (uint32_t)((uint64_t)seconds & 0xFFFFFFFFu));
    put_le32(canonical + 8, (uint32_t)(((uint64_t)seconds >> 32) & 0xFFFFFFFFu));
    canonical[12] = reading->settling ? 1u : 0u;
    for (int f = 0; f < STORE_FIELDS; f++) {
        put_le32(canonical + 13 + f * 4, (uint32_t)store_quantise(reading->values[f], f));
    }
    return store_crc32(canonical, sizeof(canonical));
}

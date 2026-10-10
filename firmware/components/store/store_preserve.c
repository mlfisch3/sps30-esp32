/**
 * @file store_preserve.c
 * @brief Two rings at the end of the partition: segment headers, and samples.
 *
 * See `store_preserve.h` for what this is for and how the two regions age apart.
 * The things worth reading the code for:
 *
 * - **Age is a sequence number in both regions**, so neither has to remember
 *   where it started or how often it has turned over. The head is the slot after
 *   the largest sequence found, and a blank region leaves it at zero.
 * - **Erase the sector before its first record, never later.** Losing power
 *   mid-erase loses the oldest records, which were about to go anyway.
 * - **Nothing here ever fails upwards.** Every write can refuse, and every
 *   refusal is counted and returned rather than retried or waited on: a
 *   preservation failure must not become an acquisition stall.
 */

#include "store_preserve.h"

#include <math.h>
#include <string.h>

#define HEADER_BYTES STORE_ALERT_HEADER_BYTES
#define SECTOR STORE_PRESERVE_SECTOR_BYTES

/* --- geometry -------------------------------------------------------------- */

static size_t header_offset(const store_preserve_t *p, uint32_t slot)
{
    (void)p;
    return (size_t)slot * HEADER_BYTES;
}

static size_t body_offset(const store_preserve_t *p, uint32_t slot)
{
    return p->dev.header_bytes + (size_t)slot * STORE_PAGE_BYTES;
}

/* --- the scan -------------------------------------------------------------- */

/**
 * Find a header region's write head, its record count and the wrap it has seen.
 *
 * Reads every record - 128 bytes each, 512 of them - which is 64 KB once, at
 * boot, on a task that is holding nothing up. A binary search would be faster
 * and would not also give the count, the oldest sequence and the largest wrap,
 * all of which have to come from a full pass anyway.
 */
static void scan_headers(store_preserve_t *p)
{
    uint8_t raw[HEADER_BYTES];
    store_alert_header_t header;
    uint32_t best_seq = 0;
    uint32_t best_slot = 0;
    bool any = false;
    uint32_t held = 0;

    for (uint32_t slot = 0; slot < p->header_slots; slot++) {
        if (!p->dev.read(p->dev.ctx, header_offset(p, slot), raw, sizeof(raw))) {
            continue;
        }
        if (!store_alert_header_decode(raw, &header)) {
            continue;
        }
        held++;
        if (header.wrap > p->seen_wrap) {
            p->seen_wrap = header.wrap;
        }
        if (!any || header.seq > best_seq) {
            any = true;
            best_seq = header.seq;
            best_slot = slot;
        }
    }

    if (any) {
        p->header_seq = best_seq + 1;
        p->header_head = (best_slot + 1) % p->header_slots;
    } else {
        p->header_seq = 1;
        p->header_head = 0;
    }
    p->stats.segments = held;
}

/** The same for the body region, which also needs the oldest surviving page. */
static void scan_bodies(store_preserve_t *p)
{
    uint8_t raw[STORE_HEADER_BYTES];
    store_header_t page;
    uint32_t best_seq = 0;
    uint32_t best_slot = 0;
    uint32_t oldest = 0;
    bool any = false;
    uint32_t used = 0;

    for (uint32_t slot = 0; slot < p->body_slots; slot++) {
        if (!p->dev.read(p->dev.ctx, body_offset(p, slot), raw, sizeof(raw))) {
            continue;
        }
        if (!store_peek_page(raw, &page)) {
            continue;
        }
        used++;
        if (!any || page.page_seq > best_seq) {
            best_seq = page.page_seq;
            best_slot = slot;
        }
        if (!any || page.page_seq < oldest) {
            oldest = page.page_seq;
        }
        any = true;
    }

    if (any) {
        p->body_seq = best_seq + 1;
        p->body_head = (best_slot + 1) % p->body_slots;
        p->body_oldest_seq = oldest;
    } else {
        p->body_seq = 1;
        p->body_head = 0;
        /* Nothing has been written, so nothing has been reduced away: the
         * threshold below which a body is gone is the very first sequence. */
        p->body_oldest_seq = 1;
    }
    p->body_used = used;
}

bool store_preserve_begin(store_preserve_t *p, const store_preserve_dev_t *dev)
{
    if (p == NULL || dev == NULL || dev->read == NULL || dev->write == NULL ||
        dev->erase == NULL) {
        return false;
    }
    memset(p, 0, sizeof(*p));
    p->dev = *dev;

    if (dev->size < 2 * SECTOR || dev->header_bytes == 0 ||
        dev->header_bytes >= dev->size || dev->size % SECTOR != 0 ||
        dev->header_bytes % SECTOR != 0) {
        return false;
    }
    p->header_slots = (uint32_t)(dev->header_bytes / HEADER_BYTES);
    p->body_slots = (uint32_t)((dev->size - dev->header_bytes) / STORE_PAGE_BYTES);
    if (p->header_slots == 0 || p->body_slots == 0) {
        return false;
    }

    scan_headers(p);
    scan_bodies(p);

    p->stats.header_slots = p->header_slots;
    p->stats.body_slots = p->body_slots;
    p->stats.body_used = p->body_used;
    p->ready = true;
    return true;
}

/* --- writing --------------------------------------------------------------- */

/**
 * Erase the sector at @p offset if @p slot is its first, and account for what
 * that reclaimed.
 *
 * `reclaimed` is how many records the sector held before it went, so the caller
 * can keep `segments`/`body_used` honest without a second pass.
 */
static bool erase_ahead(store_preserve_t *p, size_t offset, size_t record_bytes,
                        uint32_t *reclaimed)
{
    *reclaimed = 0;
    if (offset % SECTOR != 0) {
        return true; /* not the first record of a sector; nothing to erase */
    }

    /* Count what is about to go, before it goes. A blank sector - the common
     * case on a board that has not turned over yet - reclaims nothing. */
    const uint32_t per_sector = (uint32_t)(SECTOR / record_bytes);
    for (uint32_t i = 0; i < per_sector; i++) {
        uint8_t probe[STORE_HEADER_BYTES > HEADER_BYTES ? STORE_HEADER_BYTES : HEADER_BYTES];
        const size_t at = offset + (size_t)i * record_bytes;
        if (record_bytes == HEADER_BYTES) {
            store_alert_header_t decoded;
            if (p->dev.read(p->dev.ctx, at, probe, HEADER_BYTES) &&
                store_alert_header_decode(probe, &decoded)) {
                (*reclaimed)++;
            }
        } else {
            store_header_t decoded;
            if (p->dev.read(p->dev.ctx, at, probe, STORE_HEADER_BYTES) &&
                store_peek_page(probe, &decoded)) {
                (*reclaimed)++;
            }
        }
    }
    return p->dev.erase(p->dev.ctx, offset, SECTOR);
}

uint32_t store_preserve_next_body_seq(const store_preserve_t *p)
{
    return (p == NULL || !p->ready) ? 0 : p->body_seq;
}

bool store_preserve_body_write(store_preserve_t *p, const uint8_t page[STORE_PAGE_BYTES])
{
    if (p == NULL || !p->ready || page == NULL) {
        return false;
    }
    const size_t offset = body_offset(p, p->body_head);
    uint32_t reclaimed = 0;
    if (!erase_ahead(p, offset, STORE_PAGE_BYTES, &reclaimed)) {
        p->stats.write_failures++;
        return false;
    }
    if (!p->dev.write(p->dev.ctx, offset, page, STORE_PAGE_BYTES)) {
        p->stats.write_failures++;
        return false;
    }

    if (reclaimed > 0) {
        p->stats.bodies_reclaimed += reclaimed;
        p->body_used -= reclaimed < p->body_used ? reclaimed : p->body_used;
        /* Everything older than what is left has been reduced away. Recomputed
         * from flash rather than inferred: the arithmetic version would have to
         * assume the reclaimed pages were the oldest, and after a partial erase
         * failure they are not. */
        uint8_t raw[STORE_HEADER_BYTES];
        store_header_t peek;
        uint32_t oldest = 0;
        bool any = false;
        for (uint32_t slot = 0; slot < p->body_slots; slot++) {
            if (!p->dev.read(p->dev.ctx, body_offset(p, slot), raw, sizeof(raw))) {
                continue;
            }
            if (!store_peek_page(raw, &peek)) {
                continue;
            }
            if (!any || peek.page_seq < oldest) {
                oldest = peek.page_seq;
                any = true;
            }
        }
        p->body_oldest_seq = any ? oldest : p->body_seq;
    }

    p->body_head = (p->body_head + 1) % p->body_slots;
    p->body_seq++;
    p->body_used++;
    if (p->body_used > p->body_slots) {
        p->body_used = p->body_slots;
    }
    p->stats.bodies_written++;
    p->stats.body_used = p->body_used;
    return true;
}

bool store_preserve_header_write(store_preserve_t *p, store_alert_header_t *header)
{
    if (p == NULL || !p->ready || header == NULL) {
        return false;
    }
    header->seq = p->header_seq;

    uint8_t raw[HEADER_BYTES];
    store_alert_header_encode(header, raw);

    const size_t offset = header_offset(p, p->header_head);
    uint32_t reclaimed = 0;
    if (!erase_ahead(p, offset, HEADER_BYTES, &reclaimed)) {
        p->stats.write_failures++;
        return false;
    }
    if (!p->dev.write(p->dev.ctx, offset, raw, HEADER_BYTES)) {
        p->stats.write_failures++;
        return false;
    }

    if (reclaimed > 0) {
        p->stats.headers_reclaimed += reclaimed;
        p->stats.segments -= reclaimed < p->stats.segments ? reclaimed : p->stats.segments;
    }
    p->header_head = (p->header_head + 1) % p->header_slots;
    p->header_seq++;
    p->stats.segments++;
    p->stats.headers_written++;
    if (header->wrap > p->seen_wrap) {
        p->seen_wrap = header->wrap;
    }
    return true;
}

/* --- reading back ---------------------------------------------------------- */

float store_preserve_pressure(const store_preserve_t *p)
{
    if (p == NULL || !p->ready || p->body_slots == 0) {
        return 0.0f;
    }
    const float f = (float)p->body_used / (float)p->body_slots;
    return f > 1.0f ? 1.0f : f;
}

bool store_preserve_body_present(const store_preserve_t *p, const store_alert_header_t *header)
{
    if (p == NULL || !p->ready || header == NULL || header->body_pages == 0) {
        return false;
    }
    /* The whole segment, not part of it. A segment whose first pages have been
     * reclaimed is a segment with a hole at the front, and handing that out as
     * the event would be worse than handing out the header alone - which is
     * complete, and says so. */
    return header->body_seq >= p->body_oldest_seq && header->body_seq < p->body_seq;
}

size_t store_preserve_list(const store_preserve_t *p, store_alert_header_t *out, size_t cap,
                           uint32_t since_seq)
{
    if (p == NULL || !p->ready || out == NULL || cap == 0) {
        return 0;
    }
    size_t n = 0;
    uint8_t raw[HEADER_BYTES];
    store_alert_header_t header;

    /* Oldest first. The head is where the next record goes, so the record after
     * it is the oldest surviving one, and walking forward from there is
     * sequence order without having to sort anything. */
    for (uint32_t i = 0; i < p->header_slots && n < cap; i++) {
        const uint32_t slot = (p->header_head + i) % p->header_slots;
        if (!p->dev.read(p->dev.ctx, header_offset(p, slot), raw, sizeof(raw))) {
            continue;
        }
        if (!store_alert_header_decode(raw, &header)) {
            continue;
        }
        if (header.seq <= since_seq) {
            continue;
        }
        if (!store_preserve_body_present(p, &header)) {
            header.flags |= STORE_ALERT_FLAG_BODY_GONE;
        }
        out[n++] = header;
    }
    return n;
}

bool store_preserve_find(const store_preserve_t *p, uint32_t seq, store_alert_header_t *out)
{
    if (p == NULL || !p->ready || out == NULL || seq == 0) {
        return false;
    }
    uint8_t raw[HEADER_BYTES];
    store_alert_header_t header;
    for (uint32_t slot = 0; slot < p->header_slots; slot++) {
        if (!p->dev.read(p->dev.ctx, header_offset(p, slot), raw, sizeof(raw))) {
            continue;
        }
        if (!store_alert_header_decode(raw, &header) || header.seq != seq) {
            continue;
        }
        if (!store_preserve_body_present(p, &header)) {
            header.flags |= STORE_ALERT_FLAG_BODY_GONE;
        }
        *out = header;
        return true;
    }
    return false;
}

bool store_preserve_covers(const store_preserve_t *p, uint32_t session,
                           int64_t session_start_ms, uint32_t index, uint32_t *through)
{
    uint32_t horizon = UINT32_MAX;
    bool covered = false;
    if (p == NULL || !p->ready) {
        if (through != NULL) {
            *through = horizon;
        }
        return false;
    }

    uint8_t raw[HEADER_BYTES];
    store_alert_header_t header;
    for (uint32_t slot = 0; slot < p->header_slots && !covered; slot++) {
        if (!p->dev.read(p->dev.ctx, header_offset(p, slot), raw, sizeof(raw))) {
            continue;
        }
        if (!store_alert_header_decode(raw, &header)) {
            continue;
        }
        /* Both halves of the session identity. The acquisition session number
         * restarts at 1 on every boot, so matching on it alone would call a
         * reading preserved because a different recording's segment happens to
         * cover the same index - which is the misattribution this whole design
         * is most careful about. */
        if (header.session != session || header.session_start_ms != session_start_ms) {
            continue;
        }
        if (index >= header.first_index && index <= header.last_index) {
            covered = true;
            horizon = header.last_index;
        } else if (header.first_index > index && header.first_index - 1 < horizon) {
            horizon = header.first_index - 1;
        }
    }
    if (through != NULL) {
        *through = horizon;
    }
    return covered;
}

bool store_preserve_open(const store_preserve_t *p, const store_alert_header_t *header,
                         store_preserve_cursor_t *cursor)
{
    if (p == NULL || !p->ready || header == NULL || cursor == NULL) {
        return false;
    }
    if (!store_preserve_body_present(p, header)) {
        return false;
    }
    memset(cursor, 0, sizeof(*cursor));
    cursor->session = header->session;
    cursor->session_start_ms = header->session_start_ms;
    cursor->first_index = header->first_index;
    cursor->last_index = header->last_index;
    cursor->body_seq = header->body_seq;
    cursor->body_end = header->body_seq + header->body_pages;
    cursor->slot = 0;
    cursor->remaining = p->body_slots;
    cursor->open = true;
    return true;
}

/** Whether @p peek is one of the pages @p cursor is looking for. */
static bool page_is_segment(const store_preserve_cursor_t *cursor, const store_header_t *peek)
{
    if (peek->page_seq < cursor->body_seq || peek->page_seq >= cursor->body_end) {
        return false;
    }
    if (peek->session != cursor->session ||
        peek->session_start_ms != cursor->session_start_ms) {
        return false;
    }
    const uint32_t last = peek->first_index + peek->count - 1;
    return peek->first_index <= cursor->last_index && last >= cursor->first_index;
}

bool store_preserve_next(const store_preserve_t *p, store_preserve_cursor_t *cursor,
                         store_reading_t *out)
{
    if (p == NULL || !p->ready || cursor == NULL || !cursor->open || out == NULL) {
        return false;
    }
    for (;;) {
        if (cursor->have_page) {
            while (cursor->next < cursor->page.count) {
                const store_reading_t *r = &cursor->page.readings[cursor->next++];
                /* A page may straddle the segment's edges when the sweep rescued
                 * a partial one; only the readings inside it are this segment's. */
                if (r->index >= cursor->first_index && r->index <= cursor->last_index) {
                    *out = *r;
                    return true;
                }
            }
            cursor->have_page = false;
        }
        if (cursor->remaining == 0) {
            cursor->open = false;
            return false;
        }

        const uint32_t slot = cursor->slot;
        cursor->slot = (cursor->slot + 1) % p->body_slots;
        cursor->remaining--;

        /* The slots are walked in order and the pages filtered by sequence,
         * rather than the sequence being turned into a slot. The body ring is
         * written strictly in order so the arithmetic version would work today -
         * and would quietly hand out the wrong page the first time a write
         * failed and left a gap. */
        uint8_t raw[STORE_PAGE_BYTES];
        if (!p->dev.read(p->dev.ctx, body_offset(p, slot), raw, sizeof(raw))) {
            continue;
        }
        store_header_t peek;
        if (!store_peek_page(raw, &peek) || !page_is_segment(cursor, &peek)) {
            continue;
        }
        if (!store_decode_page(raw, &cursor->page)) {
            continue;
        }
        cursor->next = 0;
        cursor->have_page = true;
    }
}

bool store_preserve_fill_midpoint(const store_preserve_t *p, store_alert_header_t *header,
                                  store_page_t *scratch)
{
    if (p == NULL || !p->ready || header == NULL || scratch == NULL) {
        return false;
    }
    if (!store_preserve_body_present(p, header) || header->points == 0) {
        return false;
    }

    /* The window, in positions within the segment. */
    const uint32_t middle = header->points / 2;
    const uint32_t half = STORE_ALERT_MIDPOINT_SPAN / 2;
    const uint32_t lo = middle > half ? middle - half : 0;
    uint32_t hi = middle + half;
    if (hi >= header->points) {
        hi = header->points - 1;
    }
    /* And in session indices, which is what a page header carries. */
    const uint32_t lo_index = header->first_index + lo;
    const uint32_t hi_index = header->first_index + hi;

    float values[STORE_ALERT_MIDPOINT_SPAN];
    uint32_t at[STORE_ALERT_MIDPOINT_SPAN];
    size_t found = 0;

    uint8_t raw[STORE_PAGE_BYTES];
    for (uint32_t slot = 0; slot < p->body_slots && found < STORE_ALERT_MIDPOINT_SPAN; slot++) {
        if (!p->dev.read(p->dev.ctx, body_offset(p, slot), raw, sizeof(raw))) {
            continue;
        }
        store_header_t peek;
        if (!store_peek_page(raw, &peek) || peek.page_seq < header->body_seq ||
            peek.page_seq >= header->body_seq + header->body_pages ||
            peek.session != header->session ||
            peek.session_start_ms != header->session_start_ms) {
            continue;
        }
        /* Only the pages that actually cover the middle are decoded. That is the
         * whole point of doing this from flash rather than from RAM: an hour of
         * 1 Hz is eighty-eight pages and this reads one or two of them. */
        if (peek.first_index + peek.count - 1 < lo_index || peek.first_index > hi_index) {
            continue;
        }
        if (!store_decode_page(raw, scratch)) {
            continue;
        }
        for (uint8_t i = 0; i < scratch->count && found < STORE_ALERT_MIDPOINT_SPAN; i++) {
            const store_reading_t *r = &scratch->readings[i];
            if (r->index < lo_index || r->index > hi_index) {
                continue;
            }
            values[found] = r->values[header->field];
            at[found] = r->index - header->first_index;
            found++;
        }
    }
    if (found == 0) {
        return false;
    }

    /* The pages are walked in slot order, not sequence order, so the samples can
     * arrive out of order when the body ring has turned over between them.
     * Sorted by position before averaging, because ::store_alert_midpoint is
     * told where `values[0]` sits and takes the rest as contiguous. */
    for (size_t i = 1; i < found; i++) {
        for (size_t j = i; j > 0 && at[j - 1] > at[j]; j--) {
            const uint32_t ta = at[j - 1];
            at[j - 1] = at[j];
            at[j] = ta;
            const float tv = values[j - 1];
            values[j - 1] = values[j];
            values[j] = tv;
        }
    }

    const float mid = store_alert_midpoint(values, (uint32_t)found, header->points, at[0]);
    if (!isfinite(mid)) {
        return false;
    }
    header->midpoint = mid;
    return true;
}

void store_preserve_get_stats(const store_preserve_t *p, store_preserve_stats_t *out)
{
    if (out == NULL) {
        return;
    }
    if (p == NULL || !p->ready) {
        memset(out, 0, sizeof(*out));
        return;
    }
    *out = p->stats;
    out->body_used = p->body_used;
}

uint32_t store_preserve_newest_seq(const store_preserve_t *p)
{
    if (p == NULL || !p->ready || p->header_seq == 0) {
        return 0;
    }
    return p->header_seq - 1;
}

uint32_t store_preserve_seen_wrap(const store_preserve_t *p)
{
    return (p == NULL) ? 0 : p->seen_wrap;
}

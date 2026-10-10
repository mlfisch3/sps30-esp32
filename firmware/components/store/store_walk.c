/**
 * @file store_walk.c
 * @brief The `get_file` walk: bounded by the session, and paid for by the header.
 *
 * See `store_walk.h` for why this is a file of its own and what the two costs it
 * removes were. The code itself is short; what is worth reading is which of the
 * three reads a slot can cost is taken, and when:
 *
 *   - 48 bytes, always. Enough for `store_peek_page`, which is all the four
 *     rejections below need.
 *   - the remaining 464, only for a page that passed all four and is about to be
 *     decoded. Same 512 bytes in total as before for a page that is used.
 *   - nothing at all, for a slot outside the session's span.
 */

#include "store_walk.h"

#include <string.h>

uint32_t store_walk_slot_of_seq(const store_ring_geometry_t *g, uint32_t seq)
{
    if (g == NULL || g->slots == 0) {
        return 0;
    }
    /* How far back from the head this page sits, in whole pages. Unsigned
     * arithmetic wraps correctly through a `next_seq` that has passed 2^32. */
    const uint32_t back = g->next_seq - seq;
    return (uint32_t)(((uint64_t)g->head + g->slots - (back % g->slots)) % g->slots);
}

/** The oldest slot the ring can still be holding a page in. */
static uint32_t oldest_slot(const store_ring_geometry_t *g)
{
    return g->wrapped ? g->head : 0;
}

/** Slots between the oldest live one and @p slot, walking forward. */
static uint32_t distance_from_oldest(const store_ring_geometry_t *g, uint32_t slot)
{
    const uint32_t from = oldest_slot(g);
    return (uint32_t)(((uint64_t)slot + g->slots - from) % g->slots);
}

void store_walk_plan(store_cursor_t *cursor, const store_ring_geometry_t *g, bool have_span,
                     uint32_t first_seq, uint32_t last_seq)
{
    if (cursor == NULL || g == NULL || g->slots == 0) {
        return;
    }
    /*
     * The fallback, and the shape everything else is measured against: start at
     * the oldest slot, which is the head when the ring has wrapped and slot zero
     * when it has not, and walk the whole thing. Walking forward from there is
     * `page_seq` order.
     */
    cursor->slot = oldest_slot(g);
    cursor->remaining = g->slots;
    if (!have_span || last_seq < first_seq) {
        return;
    }

    /*
     * How many pages of the ring the span reaches back over. A `first_seq` older
     * than the ring can still hold names a page that has been overwritten, and
     * its slot now belongs to something else, so the walk starts at the oldest
     * live slot instead - never at the alias.
     */
    const uint32_t back = g->next_seq - first_seq;
    uint32_t start = (back == 0 || back > g->slots) ? oldest_slot(g)
                                                    : store_walk_slot_of_seq(g, first_seq);

    /*
     * And where it ends. `last_seq` is a bound that may be too high - a summary
     * keeps its span when the ring reclaims a page at either edge - so the span
     * is measured in slots from the start and clamped to the ring rather than
     * trusted to land inside it.
     *
     * A `last_seq` at or past the head means "as new as anything can be", which
     * is the live session: walk to the head and stop, because a page written
     * after this cursor opened is not part of the snapshot it was opened on.
     */
    uint32_t span;
    if (last_seq >= g->next_seq || (uint32_t)(g->next_seq - last_seq) > g->slots) {
        span = g->slots;
    } else {
        const uint32_t end = store_walk_slot_of_seq(g, last_seq);
        span = distance_from_oldest(g, end) + 1;
    }

    /* Both ends are now slot positions on the same forward walk, so the length
     * is the difference - and the start being ahead of the end can only mean the
     * clamping above moved one of them, which the whole-ring default covers. */
    const uint32_t from_oldest = distance_from_oldest(g, start);
    if (from_oldest >= span) {
        return;
    }
    cursor->slot = start;
    cursor->remaining = span - from_oldest;
}

bool store_walk_next(store_cursor_t *cursor, const store_slot_reader_t *src,
                     store_reading_t *out, uint32_t *decode_failures)
{
    if (cursor == NULL || !cursor->open || src == NULL || src->read == NULL ||
        src->slots == 0 || out == NULL) {
        return false;
    }
    for (;;) {
        if (cursor->have_page) {
            while (cursor->next < cursor->page.count) {
                const store_reading_t *r = &cursor->page.readings[cursor->next++];
                if (r->index > cursor->since_index) {
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

        uint8_t raw[STORE_PAGE_BYTES];
        const uint32_t slot = cursor->slot;
        cursor->slot = (cursor->slot + 1) % src->slots;
        cursor->remaining--;

        /* Forty-eight bytes to decide. `store_peek_page` reads no further than
         * this even when it is handed the whole page, so a slot that is about to
         * be rejected costs 90.6% less flash than it used to. */
        if (!src->read(src->ctx, slot, raw, STORE_HEADER_BYTES)) {
            continue;
        }
        store_header_t peek;
        /* Both halves of the key. The session number alone repeats across
         * reboots, so matching on it would serve the wrong session's readings
         * under the right session's name. */
        if (!store_peek_page(raw, &peek) || peek.anchor ||
            peek.session != cursor->session ||
            peek.session_start_ms != cursor->session_start_ms) {
            continue;
        }
        if (peek.first_index + peek.count - 1 <= cursor->since_index) {
            continue; /* wholly before the client's watermark */
        }
        /*
         * Only now the whole page - and the whole page rather than the 464 bytes
         * still missing, which would be the cheaper read.
         *
         * The CRC covers all 512 bytes, so a body read separately from its header
         * could be torn by the ring wrapping onto this slot between the two: the
         * page would then fail its CRC and be skipped, where today's single read
         * would have returned it. The window is microseconds wide and the page is
         * being destroyed either way, but "the same rows come back" is worth more
         * than the forty-eight bytes, and forty-eight bytes on the pages a
         * transfer actually uses is about 9% of a read it was already paying for.
         */
        if (!src->read(src->ctx, slot, raw, STORE_PAGE_BYTES)) {
            continue;
        }
        if (!store_decode_page(raw, &cursor->page)) {
            if (decode_failures != NULL) {
                (*decode_failures)++;
            }
            continue;
        }
        cursor->next = 0;
        cursor->have_page = true;
    }
}

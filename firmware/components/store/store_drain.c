/**
 * @file store_drain.c
 * @brief Back up everything and clear the board, one erase-unit at a time.
 *
 * See `store_drain.h` for what a block is, why the preservation area is drained
 * before the ring, and why there is deliberately nothing persistent to resume
 * from. Pure code over two function pointers: no ESP-IDF, no flash, no clock.
 */
#include "store_drain.h"

#include <string.h>

#include "store_sessions.h"
#include "wire.h"

/* --- the canonical forms --------------------------------------------------- */

static void put_le32(uint8_t *out, uint32_t v)
{
    out[0] = (uint8_t)(v & 0xFFu);
    out[1] = (uint8_t)((v >> 8) & 0xFFu);
    out[2] = (uint8_t)((v >> 16) & 0xFFu);
    out[3] = (uint8_t)((v >> 24) & 0xFFu);
}

static void put_le64(uint8_t *out, uint64_t v)
{
    put_le32(out, (uint32_t)(v & 0xFFFFFFFFu));
    put_le32(out + 4, (uint32_t)((v >> 32) & 0xFFFFFFFFu));
}

/**
 * Whole seconds, rounding towards negative infinity.
 *
 * `store_merge.c`'s own `floor_seconds`, repeated rather than exported, because
 * a truncating division disagrees with it before 1970 and the two digests have
 * to answer the same way on every value a reader could hand back.
 */
static int64_t floor_seconds(int64_t ms)
{
    int64_t seconds = ms / 1000;
    if (ms % 1000 != 0 && ms < 0) {
        seconds--;
    }
    return seconds;
}

/**
 * A float as the bits a reader would reconstruct from the wire.
 *
 * The encoder prints the shortest text that reads back as the same number, so a
 * float survives the round trip exactly and both ends can take its bit pattern.
 * Anything not finite is written as an absent cell and digests as zero, which is
 * what a reader has to make of one.
 */
static uint32_t float_bits(float v)
{
    /* A NaN or an infinity has no cell on the wire, so it has no bits here. */
    if (!(v == v) || v > 3.4e38f || v < -3.4e38f) {
        return 0;
    }
    uint32_t bits = 0;
    memcpy(&bits, &v, sizeof(bits));
    return bits;
}

uint32_t store_drain_digest_reading(const char *session, const store_reading_t *reading)
{
    if (reading == NULL) {
        return 0;
    }
    /* crc32(session) | index | epoch seconds | settling | ten quantised values.
     * Everything after the name is `store_digest_reading`'s own canonical form,
     * byte for byte, so the two agree about what a reading is and differ only in
     * that this one also says which session it belongs to. */
    uint8_t canonical[4 + 4 + 8 + 1 + STORE_FIELDS * 4];
    const size_t name_len = session != NULL ? strlen(session) : 0;
    put_le32(canonical, store_crc32((const uint8_t *)(session != NULL ? session : ""), name_len));
    put_le32(canonical + 4, reading->index);
    put_le64(canonical + 8, (uint64_t)floor_seconds(reading->epoch_ms));
    canonical[16] = reading->settling ? 1u : 0u;
    for (int f = 0; f < STORE_FIELDS; f++) {
        put_le32(canonical + 17 + f * 4, (uint32_t)store_quantise(reading->values[f], f));
    }
    return store_crc32(canonical, sizeof(canonical));
}

uint32_t store_drain_digest_segment(const store_alert_header_t *header)
{
    if (header == NULL) {
        return 0;
    }
    char name[WIRE_CSV_NAME_LEN];
    /* The same name `fill_alert_data` puts on the wire for a preserved segment,
     * so the cell the reader digests and the cell it was sent are one string. */
    wire_csv_name(header->session_start_ms / 1000, header->utc_offset_min * 60, name,
                  sizeof(name));

    uint8_t canonical[4 + 4 + 1 + 8 + 8 + 4 * 4 + 8 * 4];
    size_t at = 0;
    put_le32(canonical + at, store_crc32((const uint8_t *)name, strlen(name)));
    at += 4;
    put_le32(canonical + at, header->seq);
    at += 4;
    canonical[at++] = header->field;
    put_le64(canonical + at, (uint64_t)floor_seconds(header->start_ms));
    at += 8;
    put_le64(canonical + at, (uint64_t)floor_seconds(header->stop_ms));
    at += 8;
    put_le32(canonical + at, header->first_index);
    at += 4;
    put_le32(canonical + at, header->last_index);
    at += 4;
    put_le32(canonical + at, header->points);
    at += 4;
    put_le32(canonical + at, header->wrap);
    at += 4;
    /* The captain's statistics, as the bits a reader rebuilds. `flags` and
     * `has_body` are deliberately absent: whether the samples are still on flash
     * is worked out at read time and changes as the body region ages, so a
     * digest that included it would refuse a perfectly good copy an hour later. */
    const float stats[8] = {
        header->threshold, header->first,    header->last,    header->midpoint,
        header->mean,      header->variance, header->minimum, header->maximum,
    };
    for (size_t i = 0; i < 8; i++) {
        put_le32(canonical + at, float_bits(stats[i]));
        at += 4;
    }
    return store_crc32(canonical, at);
}

/* --- geometry -------------------------------------------------------------- */

/** Blocks in the ring. */
static uint32_t ring_blocks(const store_drain_t *d)
{
    return (uint32_t)(d->dev.ring_bytes / STORE_DRAIN_BLOCK_BYTES);
}

/** Blocks of the preservation area given to segment headers. */
static uint32_t header_blocks(const store_drain_t *d)
{
    return (uint32_t)(d->dev.preserve_header_bytes / STORE_DRAIN_BLOCK_BYTES);
}

/** Blocks of the preservation area given to samples. */
static uint32_t body_blocks(const store_drain_t *d)
{
    if (d->dev.preserve_bytes <= d->dev.preserve_header_bytes) {
        return 0;
    }
    return (uint32_t)((d->dev.preserve_bytes - d->dev.preserve_header_bytes) /
                      STORE_DRAIN_BLOCK_BYTES);
}

static size_t body_base(const store_drain_t *d)
{
    return d->dev.preserve_base + d->dev.preserve_header_bytes;
}

/* --- describing one block from its flash ----------------------------------- */

/**
 * Fill in what a sector of pages holds, without decoding a body.
 *
 * Forty-eight bytes a page rather than five hundred and twelve, which is the
 * same trade the boot scan and `store_walk` make: the header answers every
 * question about whether a block is interesting, and only a block that is going
 * to be sent has its readings decoded.
 */
static void survey_pages(const store_drain_t *d, size_t offset, store_drain_block_t *out)
{
    out->records = 0;
    out->readings = 0;
    out->segments = 0;
    out->oldest_seq = 0;
    bool any = false;
    for (uint32_t i = 0; i < STORE_DRAIN_BLOCK_PAGES; i++) {
        uint8_t header[STORE_HEADER_BYTES];
        store_header_t page;
        if (!d->dev.read(d->dev.ctx, offset + (size_t)i * STORE_PAGE_BYTES, header,
                         sizeof(header))) {
            continue;
        }
        if (!store_peek_page(header, &page)) {
            continue;
        }
        out->records++;
        /* An anchor page carries equivalences and no readings. It is counted as
         * a record so the report is honest about what went, and contributes no
         * rows and no digest - see `store_drain.h` and BACKUP_AND_CLEAR.md. */
        if (!page.anchor) {
            out->readings += page.count;
        }
        if (!any || page.page_seq < out->oldest_seq) {
            any = true;
            out->oldest_seq = page.page_seq;
        }
    }
}

/** The same, for a sector of ::store_alert_header_t records. */
static void survey_segments(const store_drain_t *d, size_t offset, store_drain_block_t *out)
{
    out->records = 0;
    out->readings = 0;
    out->segments = 0;
    out->oldest_seq = 0;
    bool any = false;
    for (uint32_t i = 0; i < STORE_DRAIN_BLOCK_HEADERS; i++) {
        uint8_t raw[STORE_ALERT_HEADER_BYTES];
        store_alert_header_t header;
        if (!d->dev.read(d->dev.ctx, offset + (size_t)i * STORE_ALERT_HEADER_BYTES, raw,
                         sizeof(raw))) {
            continue;
        }
        if (!store_alert_header_decode(raw, &header)) {
            continue;
        }
        out->records++;
        out->segments++;
        if (!any || header.seq < out->oldest_seq) {
            any = true;
            out->oldest_seq = header.seq;
        }
    }
}

/* --- choosing the oldest block --------------------------------------------- */

/**
 * The block of the ring the write head is in.
 *
 * Never freed while the board is recording: it holds the readings arriving right
 * now. Everything else is drained first, which is why by the end the head has
 * moved into space this drain freed and the block it started in is an ordinary
 * one.
 */
static uint32_t head_block_of(uint32_t head_slot)
{
    return head_slot / STORE_DRAIN_BLOCK_PAGES;
}

/**
 * Ring age order: how far a block is behind the head, in blocks.
 *
 * The block just after the head's is the oldest, because the head is about to
 * overwrite it. Ordering on `page_seq` instead would be wrong on a full ring,
 * where the head's own block holds both the very oldest pages and the very
 * newest - and that block is the one that must go last.
 */
static uint32_t ring_age(uint32_t block, uint32_t head_block, uint32_t blocks)
{
    return (block + blocks - ((head_block + 1) % blocks)) % blocks;
}

bool store_drain_choose(store_drain_t *d, uint32_t slots, uint32_t head_slot,
                        store_drain_block_t *out)
{
    if (d == NULL || !d->open || out == NULL) {
        return false;
    }
    store_drain_block_t best;
    memset(&best, 0, sizeof(best));
    bool found = false;

    /* 1. Preserved samples, oldest first. The preservation area is drained
     *    before the ring because it is what survived the ring turning over, so
     *    it is the data that exists nowhere else. */
    for (uint32_t i = 0; i < body_blocks(d); i++) {
        store_drain_block_t candidate;
        memset(&candidate, 0, sizeof(candidate));
        candidate.kind = STORE_DRAIN_BODY;
        candidate.offset = body_base(d) + (size_t)i * STORE_DRAIN_BLOCK_BYTES;
        survey_pages(d, candidate.offset, &candidate);
        if (candidate.records == 0) {
            continue;
        }
        if (!found || candidate.oldest_seq < best.oldest_seq) {
            best = candidate;
            found = true;
        }
    }

    /* 2. Then the summaries, so an interrupted drain leaves the record the
     *    design requires to outlive its samples rather than the other way up. */
    if (!found) {
        for (uint32_t i = 0; i < header_blocks(d); i++) {
            store_drain_block_t candidate;
            memset(&candidate, 0, sizeof(candidate));
            candidate.kind = STORE_DRAIN_SEGMENTS;
            candidate.offset = d->dev.preserve_base + (size_t)i * STORE_DRAIN_BLOCK_BYTES;
            survey_segments(d, candidate.offset, &candidate);
            if (candidate.records == 0) {
                continue;
            }
            if (!found || candidate.oldest_seq < best.oldest_seq) {
                best = candidate;
                found = true;
            }
        }
    }

    /* 3. Then the ring, oldest first, keeping the block the head is in for
     *    last. */
    if (!found) {
        const uint32_t blocks = ring_blocks(d);
        if (blocks == 0 || slots == 0) {
            return false;
        }
        const uint32_t head_block = head_block_of(head_slot % slots);
        uint32_t best_age = 0;
        for (uint32_t i = 0; i < blocks; i++) {
            if (i == head_block) {
                continue;
            }
            store_drain_block_t candidate;
            memset(&candidate, 0, sizeof(candidate));
            candidate.kind = STORE_DRAIN_RING;
            candidate.slot = i * STORE_DRAIN_BLOCK_PAGES;
            candidate.offset = (size_t)candidate.slot * STORE_PAGE_BYTES;
            survey_pages(d, candidate.offset, &candidate);
            if (candidate.records == 0) {
                continue;
            }
            const uint32_t age = ring_age(i, head_block, blocks);
            if (!found || age < best_age) {
                best = candidate;
                best_age = age;
                found = true;
            }
        }
        if (!found) {
            /* Only the head's block is left. It is copied and confirmed like
             * any other and then kept: it is the readings arriving now, and it
             * becomes the start of storage. */
            store_drain_block_t candidate;
            memset(&candidate, 0, sizeof(candidate));
            candidate.kind = STORE_DRAIN_RING;
            candidate.slot = head_block * STORE_DRAIN_BLOCK_PAGES;
            candidate.offset = (size_t)candidate.slot * STORE_PAGE_BYTES;
            candidate.final = true;
            survey_pages(d, candidate.offset, &candidate);
            if (candidate.records == 0) {
                return false; /* nothing anywhere: the board is already clear */
            }
            best = candidate;
            found = true;
        }
    }

    d->next_id++;
    best.id = d->next_id;
    d->held = best;
    d->have_held = true;
    d->sent = false;
    d->sent_rows = 0;
    d->sent_digest = 0;
    d->blocks_offered++;
    *out = best;
    return true;
}

uint32_t store_drain_blocks_left(const store_drain_t *d)
{
    if (d == NULL) {
        return 0;
    }
    uint32_t left = 0;
    store_drain_block_t probe;
    for (uint32_t i = 0; i < body_blocks(d); i++) {
        survey_pages(d, body_base(d) + (size_t)i * STORE_DRAIN_BLOCK_BYTES, &probe);
        if (probe.records > 0) {
            left++;
        }
    }
    for (uint32_t i = 0; i < header_blocks(d); i++) {
        survey_segments(d, d->dev.preserve_base + (size_t)i * STORE_DRAIN_BLOCK_BYTES, &probe);
        if (probe.records > 0) {
            left++;
        }
    }
    for (uint32_t i = 0; i < ring_blocks(d); i++) {
        survey_pages(d, (size_t)i * STORE_DRAIN_BLOCK_BYTES, &probe);
        if (probe.records > 0) {
            left++;
        }
    }
    return left;
}

/* --- walking a block ------------------------------------------------------- */

uint32_t store_drain_walk_readings(const store_drain_t *d, const store_drain_block_t *block,
                                   uint32_t from_row, store_page_t *scratch,
                                   store_drain_reading_fn emit, void *ctx)
{
    if (d == NULL || block == NULL || scratch == NULL || emit == NULL) {
        return 0;
    }
    if (block->kind != STORE_DRAIN_RING && block->kind != STORE_DRAIN_BODY) {
        return 0;
    }
    uint32_t seen = 0;
    uint32_t sent = 0;
    for (uint32_t i = 0; i < STORE_DRAIN_BLOCK_PAGES; i++) {
        uint8_t raw[STORE_PAGE_BYTES];
        const size_t at = block->offset + (size_t)i * STORE_PAGE_BYTES;
        if (!d->dev.read(d->dev.ctx, at, raw, sizeof(raw))) {
            continue;
        }
        store_header_t peek;
        if (!store_peek_page(raw, &peek)) {
            continue;
        }
        if (peek.anchor) {
            continue; /* equivalences, not readings; see survey_pages */
        }
        if (!store_decode_page(raw, scratch)) {
            /* A page that fails its CRC is not a page. It contributes nothing to
             * the rows and nothing to the digest, on both sides of the check, so
             * a corrupt page cannot make a good copy look wrong. */
            continue;
        }
        char name[WIRE_CSV_NAME_LEN];
        store_sessions_derive_name(&peek, name, sizeof(name));
        for (uint8_t r = 0; r < scratch->count; r++) {
            if (seen++ < from_row) {
                continue;
            }
            if (!emit(ctx, name, scratch->page_seq, &scratch->readings[r], scratch->relative)) {
                return sent;
            }
            sent++;
        }
    }
    return sent;
}

uint32_t store_drain_walk_segments(const store_drain_t *d, const store_drain_block_t *block,
                                   uint32_t from_row, store_drain_segment_fn emit, void *ctx)
{
    if (d == NULL || block == NULL || emit == NULL) {
        return 0;
    }
    if (block->kind != STORE_DRAIN_SEGMENTS) {
        return 0;
    }
    uint32_t seen = 0;
    uint32_t sent = 0;
    for (uint32_t i = 0; i < STORE_DRAIN_BLOCK_HEADERS; i++) {
        uint8_t raw[STORE_ALERT_HEADER_BYTES];
        store_alert_header_t header;
        if (!d->dev.read(d->dev.ctx, block->offset + (size_t)i * STORE_ALERT_HEADER_BYTES, raw,
                         sizeof(raw))) {
            continue;
        }
        if (!store_alert_header_decode(raw, &header)) {
            continue;
        }
        if (seen++ < from_row) {
            continue;
        }
        if (!emit(ctx, &header)) {
            return sent;
        }
        sent++;
    }
    return sent;
}

/* --- measuring a block ----------------------------------------------------- */

typedef struct {
    uint32_t rows;
    uint32_t digest;
} tally_t;

static bool tally_reading(void *ctx, const char *session, uint32_t page_seq,
                          const store_reading_t *reading, bool relative)
{
    (void)page_seq;
    (void)relative;
    tally_t *t = (tally_t *)ctx;
    t->digest = store_drain_digest_fold(t->digest, store_drain_digest_reading(session, reading));
    t->rows++;
    return true;
}

static bool tally_segment(void *ctx, const store_alert_header_t *header)
{
    tally_t *t = (tally_t *)ctx;
    t->digest = store_drain_digest_fold(t->digest, store_drain_digest_segment(header));
    t->rows++;
    return true;
}

bool store_drain_measure(const store_drain_t *d, const store_drain_block_t *block,
                         store_page_t *scratch, uint32_t *rows, uint32_t *digest)
{
    if (d == NULL || block == NULL || rows == NULL || digest == NULL) {
        return false;
    }
    tally_t t = {0, 0};
    if (block->kind == STORE_DRAIN_SEGMENTS) {
        (void)store_drain_walk_segments(d, block, 0, tally_segment, &t);
    } else {
        if (scratch == NULL) {
            return false;
        }
        (void)store_drain_walk_readings(d, block, 0, scratch, tally_reading, &t);
    }
    *rows = t.rows;
    *digest = t.digest;
    return true;
}

/* --- the drain itself ------------------------------------------------------ */

bool store_drain_begin(store_drain_t *d, const store_drain_dev_t *dev, int owner)
{
    if (d == NULL || dev == NULL || dev->read == NULL || dev->erase == NULL) {
        return false;
    }
    if (d->open) {
        return false;
    }
    memset(d, 0, sizeof(*d));
    d->dev = *dev;
    d->open = true;
    d->owner = owner;
    return true;
}

void store_drain_end(store_drain_t *d)
{
    if (d == NULL) {
        return;
    }
    /* Nothing to undo. A block that was freed was proved first, and a block that
     * was offered and not answered for is still exactly where it was. */
    memset(d, 0, sizeof(*d));
}

bool store_drain_is_owner(const store_drain_t *d, int owner)
{
    return d != NULL && d->open && d->owner == owner;
}

void store_drain_sent(store_drain_t *d, uint32_t rows, uint32_t digest)
{
    if (d == NULL || !d->open || !d->have_held) {
        return;
    }
    d->sent_rows = rows;
    d->sent_digest = digest;
    d->sent = true;
}

store_drain_verdict_t store_drain_confirm(store_drain_t *d, uint32_t id, uint32_t rows,
                                          uint32_t digest, store_page_t *scratch)
{
    if (d == NULL || !d->open || !d->have_held || d->held.id != id || id == 0 || !d->sent) {
        return STORE_DRAIN_NOT_OFFERED;
    }

    /* 1. The device holds what it was sent. */
    if (rows != d->sent_rows || digest != d->sent_digest) {
        d->blocks_refused++;
        return STORE_DRAIN_REFUSED;
    }

    /* 2. And what it was sent is still what is on flash, so what is about to be
     *    erased is what was proved. Re-read rather than trusted: this is the one
     *    check standing between the captain and losing the data. */
    uint32_t fresh_rows = 0;
    uint32_t fresh_digest = 0;
    if (!store_drain_measure(d, &d->held, scratch, &fresh_rows, &fresh_digest)) {
        d->blocks_refused++;
        return STORE_DRAIN_REFUSED;
    }
    if (fresh_rows != d->sent_rows || fresh_digest != d->sent_digest) {
        d->blocks_refused++;
        return STORE_DRAIN_REFUSED;
    }

    d->readings_copied += d->held.kind == STORE_DRAIN_SEGMENTS ? 0 : rows;
    d->segments_copied += d->held.kind == STORE_DRAIN_SEGMENTS ? rows : 0;

    if (d->held.final) {
        /* The captain was explicit: while the board is still recording, the
         * block holding the newest readings is copied and confirmed and then
         * left alone. The ring is cyclic and its tail is wherever the oldest
         * surviving page is, so this block becoming the start of storage costs
         * nothing but a rescan. */
        d->blocks_kept++;
        d->have_held = false;
        d->sent = false;
        d->finished = true;
        return STORE_DRAIN_KEPT;
    }

    if (!d->dev.erase(d->dev.ctx, d->held.offset, STORE_DRAIN_BLOCK_BYTES)) {
        return STORE_DRAIN_ERASE_FAILED;
    }
    d->blocks_freed++;
    d->readings_freed += d->held.readings;
    d->have_held = false;
    d->sent = false;
    return STORE_DRAIN_FREED;
}

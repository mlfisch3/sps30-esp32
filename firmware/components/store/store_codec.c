/**
 * @file store_codec.c
 * @brief The page format, in C. A port of `store.py`; see `store_codec.h`.
 */

#include "store_codec.h"

#include <math.h>
#include <string.h>

/* --- varints --------------------------------------------------------------- */

uint64_t store_zigzag(int64_t value)
{
    return ((uint64_t)value << 1) ^ (uint64_t)(value >> 63);
}

int64_t store_unzigzag(uint64_t value)
{
    return (int64_t)(value >> 1) ^ -(int64_t)(value & 1u);
}

size_t store_put_varint(uint8_t *out, size_t cap, uint64_t value)
{
    size_t n = 0;
    for (;;) {
        if (n >= cap) {
            return 0;
        }
        const uint8_t byte = (uint8_t)(value & 0x7Fu);
        value >>= 7;
        if (value != 0) {
            out[n++] = byte | 0x80u;
        } else {
            out[n++] = byte;
            return n;
        }
    }
}

size_t store_get_varint(const uint8_t *in, size_t len, uint64_t *value)
{
    uint64_t out = 0;
    unsigned shift = 0;
    for (size_t n = 0; n < len; n++) {
        const uint8_t byte = in[n];
        out |= (uint64_t)(byte & 0x7Fu) << shift;
        if ((byte & 0x80u) == 0) {
            *value = out;
            return n + 1;
        }
        shift += 7;
        if (shift > 63) {
            return 0; /* longer than any value we write */
        }
    }
    return 0; /* ran off the end */
}

/* --- quanta ---------------------------------------------------------------- */

/**
 * The quantum, as a `double`.
 *
 * Deliberately not a `float`. The reading arrives as float32 and the quantum is
 * 0.01, which no binary float represents exactly, so the division has to happen
 * somewhere - and where it happens changes the answer for values that land near
 * a rounding boundary. `store.py` divides in double, because Python has nothing
 * else; dividing in float here made real pages differ from the specification's
 * in the payload, which the vector test caught. Doing it in double on both sides
 * makes them agree by construction rather than by luck.
 */
static double quantum_for(int field)
{
    return field == STORE_FIELDS - 1 ? 0.001 : 0.01;
}

int32_t store_quantise(float value, int field)
{
    /* Half away from zero, matching `store.py`. Python's round() is half to
     * even and would disagree on exactly the values worth testing, so neither
     * side uses it. */
    const double scaled = (double)value / quantum_for(field);
    return (int32_t)(scaled >= 0 ? floor(scaled + 0.5) : ceil(scaled - 0.5));
}

float store_dequantise(int32_t quanta, int field)
{
    return (float)((double)quanta * quantum_for(field));
}

/* --- CRC-32 ---------------------------------------------------------------- */

/**
 * Bitwise CRC-32, the zlib polynomial, so `zlib.crc32` in the specification and
 * this agree. A table would be 1 KiB of flash to save microseconds on an
 * operation that happens once per 512 bytes written; the loop is the better
 * trade here and its cost was measured as noise against the flash write itself.
 */
uint32_t store_crc32_begin(void) { return 0xFFFFFFFFu; }

uint32_t store_crc32_feed(uint32_t crc, const uint8_t *data, size_t len)
{
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; bit++) {
            crc = (crc >> 1) ^ (0xEDB88320u & (uint32_t)(-(int32_t)(crc & 1u)));
        }
    }
    return crc;
}

uint32_t store_crc32_end(uint32_t crc) { return ~crc; }

uint32_t store_crc32(const uint8_t *data, size_t len)
{
    return store_crc32_end(store_crc32_feed(store_crc32_begin(), data, len));
}

/* --- header ---------------------------------------------------------------- */

static void put_u16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}
static void put_i64(uint8_t *p, int64_t v)
{
    const uint64_t u = (uint64_t)v;
    for (int i = 0; i < 8; i++) {
        p[i] = (uint8_t)(u >> (8 * i));
    }
}
static uint16_t get_u16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t get_u32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}
static int64_t get_i64(const uint8_t *p)
{
    uint64_t u = 0;
    for (int i = 0; i < 8; i++) {
        u |= (uint64_t)p[i] << (8 * i);
    }
    return (int64_t)u;
}

/* --- the writer ------------------------------------------------------------ */

void store_writer_init(store_writer_t *w, uint32_t page_seq, uint32_t session,
                       int64_t session_start_ms, uint32_t cadence_ms,
                       int16_t utc_offset_min, bool relative)
{
    memset(w, 0, sizeof(*w));
    w->page_seq = page_seq;
    w->session = session;
    w->session_start_ms = session_start_ms;
    w->cadence_ms = cadence_ms ? cadence_ms : 1000u;
    w->utc_offset_min = utc_offset_min;
    w->relative = relative;
}

/** Serialise an open settling run into @p out. @return bytes, 0 if none open. */
static size_t settle_run_bytes(const store_writer_t *w, uint8_t *out, size_t cap)
{
    if (w->settle_run == 0) {
        return 0;
    }
    size_t n = store_put_varint(out, cap, w->settle_at);
    if (n == 0 || n >= cap) {
        return 0;
    }
    out[n++] = STORE_MARK_SETTLING;
    const size_t r = store_put_varint(out + n, cap - n, w->settle_run);
    return r == 0 ? 0 : n + r;
}

/**
 * Marker bytes the page would hold if it were sealed right now.
 *
 * The open settling run counts: it has to be written on seal, so space for it
 * has to be reserved while readings are still being admitted. `store.py`'s
 * `_marker_bytes()` does exactly this, and the admission test only matches the
 * specification because this one does too.
 */
static size_t pending_marker_bytes(const store_writer_t *w)
{
    uint8_t scratch[12];
    return w->marker_len + settle_run_bytes(w, scratch, sizeof(scratch));
}

/** Flush an open settling run into the marker table. */
static bool close_settle_run(store_writer_t *w)
{
    if (w->settle_run == 0) {
        return true;
    }
    uint8_t scratch[12];
    const size_t n = settle_run_bytes(w, scratch, sizeof(scratch));
    if (n == 0 || w->marker_len + n > STORE_MARKER_BYTES) {
        return false;
    }
    memcpy(w->markers + w->marker_len, scratch, n);
    w->marker_len += (uint16_t)n;
    w->marker_count++;
    w->settle_run = 0;
    return true;
}

/** Append a marker with a raw payload. */
static bool put_marker(store_writer_t *w, uint16_t at, uint8_t tag, const uint8_t *payload,
                       size_t payload_len)
{
    uint8_t scratch[16];
    size_t n = store_put_varint(scratch, sizeof(scratch), at);
    if (n == 0 || n + 1 + payload_len > sizeof(scratch)) {
        return false;
    }
    scratch[n++] = tag;
    memcpy(scratch + n, payload, payload_len);
    n += payload_len;
    if (w->marker_len + n > STORE_MARKER_BYTES) {
        return false;
    }
    memcpy(w->markers + w->marker_len, scratch, n);
    w->marker_len += (uint16_t)n;
    w->marker_count++;
    return true;
}

/** The time a reader will derive for the reading at position @p at. */
static int64_t expected_ms(const store_writer_t *w, uint16_t at, int64_t actual_ms)
{
    return at == 0 ? actual_ms : w->page_start_ms + (int64_t)at * (int64_t)w->cadence_ms;
}

/**
 * Fold the markers @p r implies into @p w. Mirrors `store.py._apply_markers`.
 *
 * Called on a copy first, to price the reading, and the copy is what is kept if
 * it fits - so this runs exactly once per admitted reading either way.
 */
static bool apply_markers(store_writer_t *w, const store_reading_t *r, uint16_t at)
{
    if (r->settling) {
        if (w->settle_run != 0 && w->settle_at + w->settle_run == at) {
            w->settle_run++;
        } else {
            if (!close_settle_run(w)) {
                return false;
            }
            w->settle_at = at;
            w->settle_run = 1;
        }
    } else if (!close_settle_run(w)) {
        return false;
    }

    const int64_t drift = r->epoch_ms - expected_ms(w, at, r->epoch_ms);
    if (drift > STORE_TIME_TOLERANCE_MS || drift < -STORE_TIME_TOLERANCE_MS) {
        uint8_t payload[10];
        const size_t n = store_put_varint(payload, sizeof(payload), store_zigzag(drift));
        if (n != 0 && !put_marker(w, at, STORE_MARK_TIME, payload, n)) {
            return false;
        }
    }
    return true;
}

bool store_writer_add(store_writer_t *w, const store_reading_t *r)
{
    if (w->count == 0xFFu || w->count >= STORE_MAX_READINGS) {
        return false;
    }
    /* Indices are contiguous within a page: that is what lets the header carry
     * one `first_index` rather than every reading carrying its own. */
    if (w->count != 0 && r->index != w->first_index + w->count) {
        return false;
    }

    int32_t quanta[STORE_FIELDS];
    for (int f = 0; f < STORE_FIELDS; f++) {
        quanta[f] = store_quantise(r->values[f], f);
    }

    /* Build the record into scratch first: a reading is admitted whole or not
     * at all, and pricing it any other way would leave a half-written record in
     * the payload when it turned out not to fit. */
    uint8_t record[STORE_FIELDS * 10];
    size_t record_len = 0;
    for (int f = 0; f < STORE_FIELDS; f++) {
        const int64_t delta = w->count == 0 ? quanta[f] : (int64_t)quanta[f] - w->prev[f];
        const size_t n = store_put_varint(record + record_len, sizeof(record) - record_len,
                                          store_zigzag(delta));
        if (n == 0) {
            return false;
        }
        record_len += n;
    }

    /* Price the markers this reading would add, exactly - by applying them to a
     * copy and measuring. `store.py` prices the same way, and the two only
     * produce identical pages because of it: an approximation here would seal
     * pages a reading or two early and every byte after that would differ.
     *
     * The copy is 200-odd bytes of stack on a 4 KiB task, once per reading. */
    const uint16_t at = w->count;
    store_writer_t trial = *w;
    if (trial.count == 0) {
        trial.first_index = r->index;
        trial.page_start_ms = r->epoch_ms;
    }
    if (!apply_markers(&trial, r, at)) {
        return false;
    }
    const size_t markers_before = pending_marker_bytes(w);
    const size_t markers_after = pending_marker_bytes(&trial);
    const size_t free_bytes = STORE_BODY_BYTES - w->payload_len - markers_before;
    if (record_len + (markers_after - markers_before) > free_bytes) {
        return false;
    }

    *w = trial;
    memcpy(w->payload + w->payload_len, record, record_len);
    w->payload_len += (uint16_t)record_len;
    memcpy(w->prev, quanta, sizeof(quanta));
    w->count++;
    return true;
}

bool store_writer_seal(store_writer_t *w, uint8_t out[STORE_PAGE_BYTES], bool full)
{
    if (w->count == 0) {
        return false;
    }
    (void)close_settle_run(w);

    memset(out, 0, STORE_PAGE_BYTES);
    put_u16(out + 0, STORE_MAGIC);
    out[2] = STORE_VERSION;
    out[3] = (uint8_t)((full ? STORE_FLAG_FULL : 0u) |
                       (w->relative ? STORE_FLAG_RELATIVE : 0u));
    put_u32(out + 4, w->page_seq);
    put_u32(out + 8, w->session);
    put_i64(out + 12, w->session_start_ms);
    put_i64(out + 20, w->page_start_ms);
    put_u32(out + 28, w->cadence_ms);
    put_u32(out + 32, w->first_index);
    put_u16(out + 36, (uint16_t)w->utc_offset_min);
    out[38] = w->count;
    out[39] = w->marker_count;
    put_u16(out + 40, w->payload_len);
    put_u16(out + 42, w->marker_len);
    put_u32(out + STORE_CRC_OFFSET, 0);

    memcpy(out + STORE_HEADER_BYTES, w->payload, w->payload_len);
    if (w->marker_len) {
        memcpy(out + STORE_PAGE_BYTES - w->marker_len, w->markers, w->marker_len);
    }
    put_u32(out + STORE_CRC_OFFSET, store_crc32(out, STORE_PAGE_BYTES));
    return true;
}

bool store_seal_anchor_page(uint8_t out[STORE_PAGE_BYTES], uint32_t page_seq,
                            uint32_t session, int64_t session_start_ms,
                            int64_t page_start_ms, int16_t utc_offset_min,
                            const store_anchor_t *anchors, size_t count)
{
    if (anchors == NULL || count == 0) {
        return false;
    }
    /* Keep the newest, which is what makes an anchor page a complete record of
     * its run rather than a fragment of one. */
    if (count > STORE_MAX_ANCHORS) {
        anchors += count - STORE_MAX_ANCHORS;
        count = STORE_MAX_ANCHORS;
    }

    memset(out, 0, STORE_PAGE_BYTES);
    put_u16(out + 0, STORE_MAGIC);
    out[2] = STORE_VERSION;
    out[3] = (uint8_t)(STORE_FLAG_ANCHOR | STORE_FLAG_RELATIVE);
    put_u32(out + 4, page_seq);
    put_u32(out + 8, session);
    put_i64(out + 12, session_start_ms);
    put_i64(out + 20, page_start_ms);
    put_u32(out + 28, 0);
    put_u32(out + 32, 0);
    put_u16(out + 36, (uint16_t)utc_offset_min);
    out[38] = 0; /* no readings: the flag above is what makes that legal */
    out[39] = 0;
    put_u16(out + 40, (uint16_t)(count * STORE_ANCHOR_BYTES));
    put_u16(out + 42, 0);
    put_u32(out + STORE_CRC_OFFSET, 0);

    uint8_t *at = out + STORE_HEADER_BYTES;
    for (size_t i = 0; i < count; i++) {
        put_i64(at, anchors[i].rel_ms);
        put_i64(at + 8, anchors[i].epoch_ms);
        put_u16(at + 16, (uint16_t)anchors[i].utc_offset_min);
        at += STORE_ANCHOR_BYTES;
    }
    put_u32(out + STORE_CRC_OFFSET, store_crc32(out, STORE_PAGE_BYTES));
    return true;
}

/* --- decoding -------------------------------------------------------------- */

/**
 * Validate the header, and the CRC if asked, filling @p out.
 *
 * The CRC covers the whole page with its own field read as zero. Rather than
 * copy 512 bytes to zero four of them, it is taken over the three spans - up to
 * the field, four zeros, and the rest - which is the same number and the same
 * order of bytes. That matters because this runs on task stacks that a 512-byte
 * temporary would not fit alongside everything else.
 */
static bool read_header(const uint8_t *raw, store_header_t *out, uint16_t *payload_len,
                        uint16_t *marker_len, uint8_t *marker_count, bool verify_crc)
{
    if (get_u16(raw + 0) != STORE_MAGIC || raw[2] != STORE_VERSION) {
        return false;
    }
    const bool anchor = (raw[3] & STORE_FLAG_ANCHOR) != 0;
    const uint8_t count = raw[38];
    /* A zero count is corrupt on a reading page and correct on an anchor page.
     * The flag is the whole of that difference to a reader. */
    if ((count == 0 && !anchor) || count > STORE_MAX_READINGS) {
        return false;
    }
    const uint16_t plen = get_u16(raw + 40);
    const uint16_t mlen = get_u16(raw + 42);
    if (plen > STORE_BODY_BYTES || mlen > STORE_BODY_BYTES ||
        (uint32_t)plen + mlen > STORE_BODY_BYTES) {
        return false;
    }

    if (verify_crc) {
        static const uint8_t zeros[4] = {0, 0, 0, 0};
        uint32_t crc = store_crc32_begin();
        crc = store_crc32_feed(crc, raw, STORE_CRC_OFFSET);
        crc = store_crc32_feed(crc, zeros, sizeof(zeros));
        crc = store_crc32_feed(crc, raw + STORE_CRC_OFFSET + 4,
                               STORE_PAGE_BYTES - STORE_CRC_OFFSET - 4);
        if (store_crc32_end(crc) != get_u32(raw + STORE_CRC_OFFSET)) {
            return false;
        }
    }

    out->page_seq = get_u32(raw + 4);
    out->session = get_u32(raw + 8);
    out->session_start_ms = get_i64(raw + 12);
    out->page_start_ms = get_i64(raw + 20);
    out->cadence_ms = get_u32(raw + 28);
    out->first_index = get_u32(raw + 32);
    out->utc_offset_min = (int16_t)get_u16(raw + 36);
    out->count = count;
    out->full = (raw[3] & STORE_FLAG_FULL) != 0;
    out->relative = (raw[3] & STORE_FLAG_RELATIVE) != 0;
    out->anchor = anchor;
    *payload_len = plen;
    *marker_len = mlen;
    *marker_count = raw[39];
    return true;
}

bool store_peek_page(const uint8_t raw[STORE_HEADER_BYTES], store_header_t *out)
{
    uint16_t plen, mlen;
    uint8_t mcount;
    return read_header(raw, out, &plen, &mlen, &mcount, false);
}

bool store_repair_utc_offset_min(int16_t *offset_min)
{
    if (offset_min == NULL) {
        return false;
    }
    const int value = *offset_min;
    if (value >= STORE_UTC_OFFSET_MIN_WEST && value <= STORE_UTC_OFFSET_MIN_EAST) {
        return true;
    }
    /* The one wrong unit this field has ever been written in. Divisibility is
     * what makes the reading unambiguous rather than merely possible: a zone is
     * a whole number of minutes, so seconds carrying one are a multiple of 60. */
    if (value % 60 == 0) {
        const int as_minutes = value / 60;
        if (as_minutes >= STORE_UTC_OFFSET_MIN_WEST &&
            as_minutes <= STORE_UTC_OFFSET_MIN_EAST) {
            *offset_min = (int16_t)as_minutes;
            return true;
        }
    }
    return false;
}

size_t store_decode_anchors(const uint8_t raw[STORE_PAGE_BYTES], store_anchor_t *out,
                            size_t cap)
{
    store_header_t header;
    uint16_t payload_len, marker_len;
    uint8_t marker_count;
    if (out == NULL || cap == 0 ||
        !read_header(raw, &header, &payload_len, &marker_len, &marker_count, true) ||
        !header.anchor) {
        return 0;
    }
    size_t n = 0;
    for (size_t at = 0; at + STORE_ANCHOR_BYTES <= payload_len && n < cap;
         at += STORE_ANCHOR_BYTES) {
        const uint8_t *p = raw + STORE_HEADER_BYTES + at;
        store_anchor_t anchor = {
            .rel_ms = get_i64(p),
            .epoch_ms = get_i64(p + 8),
            .utc_offset_min = (int16_t)get_u16(p + 16),
        };
        if (!store_repair_utc_offset_min(&anchor.utc_offset_min)) {
            continue; /* no zone this could be; it is not offered */
        }
        out[n++] = anchor;
    }
    return n;
}

bool store_decode_page(const uint8_t raw[STORE_PAGE_BYTES], store_page_t *out)
{
    store_header_t header;
    uint16_t payload_len, marker_len;
    uint8_t marker_count;
    if (!read_header(raw, &header, &payload_len, &marker_len, &marker_count, true)) {
        return false;
    }
    out->page_seq = header.page_seq;
    out->session = header.session;
    out->session_start_ms = header.session_start_ms;
    out->page_start_ms = header.page_start_ms;
    out->cadence_ms = header.cadence_ms;
    out->first_index = header.first_index;
    out->utc_offset_min = header.utc_offset_min;
    out->count = header.count;
    out->full = header.full;
    out->relative = header.relative;

    /* An anchor page holds equivalences, not readings. It is decoded through
     * ::store_decode_anchors; here it is simply a page with nothing to plot. */
    if (header.anchor) {
        return true;
    }

    /* Markers first: a reading needs to know whether it was flagged before it
     * is built. They pack backwards from the end of the page. */
    bool settling[STORE_MAX_READINGS] = {false};
    int64_t drift[STORE_MAX_READINGS] = {0};
    const uint8_t *markers = raw + STORE_PAGE_BYTES - marker_len;
    size_t pos = 0;
    for (uint8_t m = 0; m < marker_count; m++) {
        uint64_t at = 0;
        size_t n = store_get_varint(markers + pos, marker_len - pos, &at);
        if (n == 0) {
            return false;
        }
        pos += n;
        if (pos >= marker_len) {
            return false;
        }
        const uint8_t tag = markers[pos++];
        if (tag == STORE_MARK_SETTLING) {
            uint64_t run = 0;
            n = store_get_varint(markers + pos, marker_len - pos, &run);
            if (n == 0) {
                return false;
            }
            pos += n;
            for (uint64_t i = at; i < at + run && i < STORE_MAX_READINGS; i++) {
                settling[i] = true;
            }
        } else if (tag == STORE_MARK_TIME) {
            uint64_t encoded = 0;
            n = store_get_varint(markers + pos, marker_len - pos, &encoded);
            if (n == 0) {
                return false;
            }
            pos += n;
            if (at < STORE_MAX_READINGS) {
                drift[at] = store_unzigzag(encoded);
            }
        } else if (tag == STORE_MARK_EVENT) {
            uint64_t length = 0;
            n = store_get_varint(markers + pos, marker_len - pos, &length);
            if (n == 0 || pos + n + length > marker_len) {
                return false;
            }
            pos += n + (size_t)length;
            /* The name is not carried into ::store_reading_t: this controller
             * only ever writes the settling flag, and inventing a field for a
             * cause it does not know would be a guess dressed as a fact. The
             * marker is still parsed so the format stays forward-compatible. */
            if (at < STORE_MAX_READINGS) {
                settling[at] = true;
            }
        } else {
            return false;
        }
    }

    const uint8_t *body = raw + STORE_HEADER_BYTES;
    int32_t prev[STORE_FIELDS] = {0};
    pos = 0;
    for (uint8_t i = 0; i < out->count; i++) {
        store_reading_t *reading = &out->readings[i];
        for (int f = 0; f < STORE_FIELDS; f++) {
            uint64_t encoded = 0;
            const size_t n = store_get_varint(body + pos, payload_len - pos, &encoded);
            if (n == 0) {
                return false;
            }
            pos += n;
            const int64_t delta = store_unzigzag(encoded);
            const int32_t value = i == 0 ? (int32_t)delta : (int32_t)(prev[f] + delta);
            prev[f] = value;
            reading->values[f] = store_dequantise(value, f);
        }
        reading->index = out->first_index + i;
        reading->epoch_ms =
            out->page_start_ms + (int64_t)i * (int64_t)out->cadence_ms + drift[i];
        reading->settling = settling[i];
    }
    return true;
}

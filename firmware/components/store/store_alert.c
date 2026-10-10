/**
 * @file store_alert.c
 * @brief The alert policy, the segment accumulator and the header record.
 *
 * See `store_alert.h` for what each of these is and where the definition of
 * "alert-level" came from. Nothing here allocates, blocks or knows what flash is.
 */

#include "store_alert.h"

#include <math.h>
#include <string.h>

/* --- little-endian primitives, matching store_codec.c ---------------------- */

static void put_u16(uint8_t *p, uint16_t v)
{
    p[0] = (uint8_t)(v & 0xFFu);
    p[1] = (uint8_t)((v >> 8) & 0xFFu);
}
static void put_u32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++) {
        p[i] = (uint8_t)((v >> (8 * i)) & 0xFFu);
    }
}
static void put_i64(uint8_t *p, int64_t v)
{
    const uint64_t u = (uint64_t)v;
    for (int i = 0; i < 8; i++) {
        p[i] = (uint8_t)((u >> (8 * i)) & 0xFFu);
    }
}
static void put_f32(uint8_t *p, float v)
{
    uint32_t bits;
    memcpy(&bits, &v, sizeof(bits));
    put_u32(p, bits);
}
static uint16_t get_u16(const uint8_t *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
static uint32_t get_u32(const uint8_t *p)
{
    uint32_t v = 0;
    for (int i = 0; i < 4; i++) {
        v |= (uint32_t)p[i] << (8 * i);
    }
    return v;
}
static int64_t get_i64(const uint8_t *p)
{
    uint64_t u = 0;
    for (int i = 0; i < 8; i++) {
        u |= (uint64_t)p[i] << (8 * i);
    }
    return (int64_t)u;
}
static float get_f32(const uint8_t *p)
{
    const uint32_t bits = get_u32(p);
    float v;
    memcpy(&v, &bits, sizeof(v));
    return v;
}

/* --- the policy ------------------------------------------------------------ */

void store_alert_policy_default(store_alert_policy_t *out)
{
    if (out == NULL) {
        return;
    }
    memset(out, 0, sizeof(*out));
    out->threshold[STORE_ALERT_FIELD_PM2_5] = STORE_ALERT_DEFAULT_PM2_5;
    out->version = 1;
}

bool store_alert_policy_set(store_alert_policy_t *policy, int field, float threshold)
{
    if (policy == NULL || field < 0 || field >= STORE_FIELDS) {
        return false;
    }
    /* A line that is not a positive real number is not a line. Folding NaN and
     * infinity to "not watched" here rather than letting them reach
     * ::store_alert_trip matters: `NaN >= x` is false, so a NaN threshold would
     * silently watch nothing while the policy claimed to watch something. */
    const float clean = (isfinite(threshold) && threshold > 0.0f) ? threshold : 0.0f;
    if (policy->threshold[field] == clean) {
        return true;
    }
    policy->threshold[field] = clean;
    policy->version++;
    return true;
}

bool store_alert_policy_watches(const store_alert_policy_t *policy)
{
    if (policy == NULL) {
        return false;
    }
    for (int f = 0; f < STORE_FIELDS; f++) {
        if (policy->threshold[f] > 0.0f) {
            return true;
        }
    }
    return false;
}

int store_alert_trip(const store_alert_policy_t *policy, const float values[STORE_FIELDS])
{
    if (policy == NULL || values == NULL) {
        return -1;
    }
    for (int f = 0; f < STORE_FIELDS; f++) {
        const float line = policy->threshold[f];
        if (line > 0.0f && isfinite(values[f]) && values[f] >= line) {
            return f;
        }
    }
    return -1;
}

float store_alert_rearm(float threshold) { return threshold * (1.0f - STORE_ALERT_HYSTERESIS); }

/* --- accumulating a segment ------------------------------------------------ */

void store_alert_seg_begin(store_alert_seg_t *seg, int field, float threshold, uint32_t session,
                           int64_t session_start_ms, int16_t utc_offset_min, bool relative,
                           const store_reading_t *reading)
{
    if (seg == NULL || reading == NULL || field < 0 || field >= STORE_FIELDS) {
        return;
    }
    memset(seg, 0, sizeof(*seg));
    seg->open = true;
    seg->field = (uint8_t)field;
    seg->threshold = threshold;
    seg->session = session;
    seg->session_start_ms = session_start_ms;
    seg->utc_offset_min = utc_offset_min;
    seg->relative = relative;
    seg->first_index = reading->index;
    seg->start_ms = reading->epoch_ms;
    seg->first = reading->values[field];
    /* NAN, not the first value: ::store_alert_seg_add is the only place the
     * extremes are decided, so a first reading that is NaN cannot pin them
     * there. Folding the opening reading in through that same call is also what
     * stops it being counted twice or not at all. */
    seg->minimum = NAN;
    seg->maximum = NAN;
    store_alert_seg_add(seg, reading);
}

void store_alert_seg_add(store_alert_seg_t *seg, const store_reading_t *reading)
{
    if (seg == NULL || reading == NULL || !seg->open) {
        return;
    }
    const float v = reading->values[seg->field];

    seg->last_index = reading->index;
    seg->stop_ms = reading->epoch_ms;
    seg->last = v;
    seg->points++;

    if (isfinite(v)) {
        if (v < seg->minimum || !isfinite(seg->minimum)) {
            seg->minimum = v;
        }
        if (v > seg->maximum || !isfinite(seg->maximum)) {
            seg->maximum = v;
        }
        /* Welford. The running mean is subtracted before it is updated, which is
         * what makes this stable where a sum of squares is not: a series sitting
         * near 20 with a spread of tenths loses every significant digit of its
         * variance to cancellation in single precision, and the variance is one
         * of the numbers the header exists to carry. */
        const double d = (double)v - seg->mean;
        seg->mean += d / (double)seg->points;
        seg->m2 += d * ((double)v - seg->mean);
    }

    if (isfinite(v) && v >= store_alert_rearm(seg->threshold)) {
        seg->clear_run = 0;
    } else if (seg->clear_run < UINT16_MAX) {
        seg->clear_run++;
    }
}

bool store_alert_seg_closing(const store_alert_seg_t *seg)
{
    if (seg == NULL || !seg->open) {
        return false;
    }
    return seg->clear_run >= STORE_ALERT_HANG_POINTS || seg->points >= STORE_ALERT_MAX_POINTS;
}

bool store_alert_seg_continues(const store_alert_seg_t *seg, uint32_t session,
                               int64_t session_start_ms, const store_reading_t *reading)
{
    if (seg == NULL || reading == NULL || !seg->open) {
        return false;
    }
    /* Both halves of the session identity, for the same reason ::store_cursor_next
     * checks both: the acquisition session number restarts at 1 on every boot, so
     * matching on it alone would splice the readings of two different recordings
     * into one segment and file them under one header. */
    if (seg->session != session || seg->session_start_ms != session_start_ms) {
        return false;
    }
    return reading->index == seg->last_index + 1;
}

void store_alert_seg_header(const store_alert_seg_t *seg, store_alert_header_t *out)
{
    if (seg == NULL || out == NULL) {
        return;
    }
    memset(out, 0, sizeof(*out));
    out->session = seg->session;
    out->session_start_ms = seg->session_start_ms;
    out->first_index = seg->first_index;
    out->last_index = seg->last_index;
    out->utc_offset_min = seg->utc_offset_min;
    out->start_ms = seg->start_ms;
    out->stop_ms = seg->stop_ms;
    out->first = seg->first;
    out->last = seg->last;
    out->minimum = seg->minimum;
    out->maximum = seg->maximum;
    out->points = seg->points;
    out->field = seg->field;
    out->threshold = seg->threshold;
    out->mean = (float)seg->mean;
    out->variance = seg->points > 0 ? (float)(seg->m2 / (double)seg->points) : 0.0f;
    /* The mean until the caller can re-read the samples and do better. See the
     * declaration for why not zero. */
    out->midpoint = out->mean;
}

float store_alert_midpoint(const float *values, uint32_t count, uint32_t total,
                           uint32_t first_at)
{
    if (values == NULL || count == 0 || total == 0) {
        return NAN;
    }
    const uint32_t middle = total / 2;
    const uint32_t half = STORE_ALERT_MIDPOINT_SPAN / 2;
    const uint32_t lo = middle > half ? middle - half : 0;
    uint32_t hi = middle + half;
    if (hi >= total) {
        hi = total - 1;
    }

    double sum = 0.0;
    uint32_t n = 0;
    for (uint32_t i = 0; i < count; i++) {
        const uint32_t at = first_at + i;
        if (at < lo || at > hi) {
            continue;
        }
        if (!isfinite(values[i])) {
            continue;
        }
        sum += (double)values[i];
        n++;
    }
    return n == 0 ? NAN : (float)(sum / (double)n);
}

/* --- the header record on flash -------------------------------------------- */

/*
 * The layout, little-endian throughout, matching the page format's conventions:
 *
 *   0   u16  magic            30  f32  first
 *   2   u8   version          34  f32  last
 *   3   u8   flags            38  f32  midpoint
 *   4   u32  seq              42  f32  mean
 *   8   u32  session          46  f32  variance
 *  12   i64  session_start_ms 50  f32  minimum
 *  20   u32  first_index      54  f32  maximum
 *  24   u32  last_index       58  f32  threshold
 *  28   u16  utc_offset_min   62  u8   field
 *                             63  u8   (reserved, zero)
 *  64   i64  start_ms         88  u32  wrap
 *  72   i64  stop_ms          92  u32  body_seq
 *  80   u32  points           96  u32  body_pages
 *  84   u32  (reserved)      100 .. 123 reserved, zero
 *                            124  u32  crc32 over the record with these 4 as 0
 */
#define ALERT_CRC_OFFSET 124

void store_alert_header_encode(const store_alert_header_t *header,
                               uint8_t out[STORE_ALERT_HEADER_BYTES])
{
    if (header == NULL || out == NULL) {
        return;
    }
    memset(out, 0, STORE_ALERT_HEADER_BYTES);
    put_u16(out + 0, STORE_ALERT_MAGIC);
    out[2] = STORE_ALERT_VERSION;
    out[3] = header->flags;
    put_u32(out + 4, header->seq);
    put_u32(out + 8, header->session);
    put_i64(out + 12, header->session_start_ms);
    put_u32(out + 20, header->first_index);
    put_u32(out + 24, header->last_index);
    put_u16(out + 28, (uint16_t)header->utc_offset_min);
    put_f32(out + 30, header->first);
    put_f32(out + 34, header->last);
    put_f32(out + 38, header->midpoint);
    put_f32(out + 42, header->mean);
    put_f32(out + 46, header->variance);
    put_f32(out + 50, header->minimum);
    put_f32(out + 54, header->maximum);
    put_f32(out + 58, header->threshold);
    out[62] = header->field;
    put_i64(out + 64, header->start_ms);
    put_i64(out + 72, header->stop_ms);
    put_u32(out + 80, header->points);
    put_u32(out + 88, header->wrap);
    put_u32(out + 92, header->body_seq);
    put_u32(out + 96, header->body_pages);
    put_u32(out + ALERT_CRC_OFFSET, store_crc32(out, STORE_ALERT_HEADER_BYTES));
}

bool store_alert_header_decode(const uint8_t raw[STORE_ALERT_HEADER_BYTES],
                               store_alert_header_t *out)
{
    if (raw == NULL || out == NULL) {
        return false;
    }
    if (get_u16(raw) != STORE_ALERT_MAGIC || raw[2] != STORE_ALERT_VERSION) {
        return false;
    }
    /* The stored CRC read as zero, without copying 128 bytes to zero four of
     * them - the same trick ::store_decode_page uses. */
    const uint32_t stored = get_u32(raw + ALERT_CRC_OFFSET);
    uint32_t crc = store_crc32_begin();
    crc = store_crc32_feed(crc, raw, ALERT_CRC_OFFSET);
    const uint8_t zeros[4] = {0, 0, 0, 0};
    crc = store_crc32_feed(crc, zeros, sizeof(zeros));
    crc = store_crc32_feed(crc, raw + ALERT_CRC_OFFSET + 4,
                           STORE_ALERT_HEADER_BYTES - ALERT_CRC_OFFSET - 4);
    if (store_crc32_end(crc) != stored) {
        return false;
    }

    memset(out, 0, sizeof(*out));
    out->flags = raw[3];
    out->seq = get_u32(raw + 4);
    out->session = get_u32(raw + 8);
    out->session_start_ms = get_i64(raw + 12);
    out->first_index = get_u32(raw + 20);
    out->last_index = get_u32(raw + 24);
    out->utc_offset_min = (int16_t)get_u16(raw + 28);
    out->first = get_f32(raw + 30);
    out->last = get_f32(raw + 34);
    out->midpoint = get_f32(raw + 38);
    out->mean = get_f32(raw + 42);
    out->variance = get_f32(raw + 46);
    out->minimum = get_f32(raw + 50);
    out->maximum = get_f32(raw + 54);
    out->threshold = get_f32(raw + 58);
    out->field = raw[62];
    out->start_ms = get_i64(raw + 64);
    out->stop_ms = get_i64(raw + 72);
    out->points = get_u32(raw + 80);
    out->wrap = get_u32(raw + 88);
    out->body_seq = get_u32(raw + 92);
    out->body_pages = get_u32(raw + 96);
    /* A record whose field index is out of range would index off the end of
     * `wire.FIELDS` in every consumer. It passed its CRC, so this is not
     * corruption in flight - it is a record written by something that does not
     * agree with this build, and refusing it is the only safe reading. */
    return out->field < STORE_FIELDS && out->points > 0;
}

/* --- rate reduction -------------------------------------------------------- */

uint32_t store_alert_rate_floor_ms(uint8_t level)
{
    switch (level) {
    case 1:
        return 60u * 1000u;       /* one a minute:      ~14 kB a day */
    case 2:
        return 15u * 60u * 1000u; /* one per 15 minutes: ~1.2 kB a day */
    default:
        return 0u;                /* impose nothing */
    }
}

uint8_t store_alert_rate_level(uint8_t level, float pressure)
{
    if (!isfinite(pressure)) {
        return level;
    }
    if (level >= STORE_ALERT_RATE_LEVELS) {
        level = STORE_ALERT_RATE_LEVELS - 1;
    }
    /* Up quickly, down slowly, and never within the same reading of the same
     * fraction: the raise lines are 0.60 and 0.85, the release lines 0.35 and
     * 0.55, so a board hovering at 0.6 does not rewrite the acquisition profile
     * every few seconds. */
    if (pressure >= 0.85f) {
        return 2;
    }
    if (pressure >= 0.60f) {
        return level > 1 ? level : 1;
    }
    if (pressure >= 0.55f) {
        return level; /* the gap: hold whatever is in force */
    }
    if (pressure >= 0.35f) {
        return level > 1 ? 1 : level;
    }
    return 0;
}

/**
 * @file store_codec.h
 * @brief Packing readings into flash pages. Pure code: no ESP-IDF, no flash.
 *
 * A C port of `store.py`, which is the specification, which in turn implements
 * `STORAGE_FORMAT.md`. `firmware/test/gen_store_vectors.py` runs the real
 * Python and writes what it emits into a header the tests here assert against
 * byte for byte, so the two cannot drift - the same arrangement `gen_vectors.py`
 * uses for the sensor codec and `gen_wire_vectors.py` for the protocol.
 *
 * Read `store.py`'s module docstring for why the format is what it is. The short
 * version: ten fields quantised to 0.01 (0.001 for particle size), delta-coded
 * against the previous reading, zigzag varint - ten bytes a reading measured on
 * real data - packed into 512-byte pages that each carry enough header to be
 * decoded entirely on their own.
 *
 * Nothing here allocates, blocks or touches hardware, which is what lets it be
 * tested on a workstation with `make -C firmware/test`.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** The ten SPS30 outputs, in `wire.FIELDS` order. */
#define STORE_FIELDS 10

/**
 * @brief Bytes per page: the unit of writing, of CRC cover, and of loss.
 *
 * 512 was measured against 256, 1024, 2048 and 4096 on real recorded sessions.
 * 512 and 1024 store identically well (12.5 bytes a reading); larger pages get
 * *worse*, because a page holds at most 255 readings and a dropout that ends a
 * page early wastes more of a big one. 512 therefore gives the best density
 * available and halves what a power cut costs, so it wins outright.
 */
#define STORE_PAGE_BYTES 512

/** Bytes of header at the front of every page. See `store.py` for the layout. */
#define STORE_HEADER_BYTES 48

/** Offset of the `crc32` field, zeroed while the CRC over the page is taken. */
#define STORE_CRC_OFFSET 44

/** Bytes the readings and markers share. */
#define STORE_BODY_BYTES (STORE_PAGE_BYTES - STORE_HEADER_BYTES)

/**
 * @brief The most readings a valid page can hold.
 *
 * The base costs at least ten bytes (ten varints, one byte minimum each) and so
 * does every reading after it, so this is an arithmetic bound rather than a
 * chosen limit. A page claiming more than this is corrupt whatever its CRC says.
 */
#define STORE_MAX_READINGS (1 + (STORE_BODY_BYTES - STORE_FIELDS) / STORE_FIELDS)

/** Bytes of marker table a writer will keep. Markers are rare; this is ample. */
#define STORE_MARKER_BYTES 96

/** `store.MAGIC`, "S3" little-endian. */
#define STORE_MAGIC 0x3353u
/** `store.VERSION`. */
#define STORE_VERSION 1u
/** The page was sealed because it filled, not because something cut it short. */
#define STORE_FLAG_FULL 0x01u

/**
 * @brief This page's times are the board's own clock, not instants.
 *
 * A controller that has never joined a network has nothing honest to put in
 * `session_start_ms`. A zero epoch would be a lie that reads as 1970 everywhere
 * downstream, so the page says so instead: with this set, `session_start_ms` and
 * `page_start_ms` are packed relative-clock readings (::store_rel_pack) and a
 * reader must not present them as instants until it has an equivalence.
 *
 * Nothing else about the page changes. The per-page rebase still does the work -
 * `page_start_ms` is this page's own first reading and the deltas within it are
 * `i * cadence` - so a real cadence of 1009 ms is absorbed by ::STORE_MARK_TIME
 * exactly as it is for an absolute page. This flag changes what the origin
 * *means*, not how a page keeps time inside itself.
 */
#define STORE_FLAG_RELATIVE 0x02u

/** @brief This page carries equivalences and no readings. See ::store_anchor_t. */
#define STORE_FLAG_ANCHOR 0x04u

/** Marker tags. See `store.py`. */
#define STORE_MARK_SETTLING 0x01u
#define STORE_MARK_TIME 0x02u
#define STORE_MARK_EVENT 0x03u

/**
 * @brief How far a derived timestamp may sit from the real one, in ms.
 *
 * The sensor's real cadence is about 1009 ms, not 1000, so a derived time drifts.
 * Each page rebases on its own first reading, which bounds the drift to well
 * under this, and so the common case writes no time markers at all.
 */
#define STORE_TIME_TOLERANCE_MS 500

/** Quanta, from STORAGE_FORMAT.md. Index 9 is `typical_size`. */
#define STORE_QUANTUM_DEFAULT 0.01f
#define STORE_QUANTUM_SIZE 0.001f

/* --- the relative clock ----------------------------------------------------- */
/*
 * `wire.rel_pack`, in C. The board's clock is a pair, (run, ms): a reboot
 * restarts the count, and two runs must never be presented as one unbroken
 * stretch. The page header has one int64 for a time and no spare field, so the
 * run marker rides in its top half and the milliseconds in the bottom 32 bits -
 * 49 days of uninterrupted running, far past the point at which the ring has
 * overwritten itself several times over. Because the run lives above bit 32,
 * `page_start_ms + i * cadence_ms` works on a packed value untouched, which is
 * exactly what lets the per-page rebase carry over unchanged.
 */

/** @brief Pack a run marker and a millisecond count into one time field. */
static inline int64_t store_rel_pack(uint32_t run, uint32_t ms)
{
    return ((int64_t)(run & 0x7FFFFFFFu) << 32) | (int64_t)ms;
}

/** @brief Which run of the board a packed relative time belongs to. */
static inline uint32_t store_rel_run(int64_t packed)
{
    return (uint32_t)((packed >> 32) & 0x7FFFFFFF);
}

/** @brief Milliseconds within its run. */
static inline uint32_t store_rel_ms(int64_t packed)
{
    return (uint32_t)(packed & 0xFFFFFFFF);
}

/* --- equivalences ----------------------------------------------------------- */

/**
 * @brief One equivalence: the board's clock and the world's, at one instant.
 *
 * The board never rewrites a stored reading when a clock finally arrives -
 * rewriting would put safe data back under the write head, and a power cut part
 * way through would corrupt an archive that was fine. It appends one of these
 * instead, and the client does the conversion. That also keeps it reversible: a
 * better time source later can simply be applied again, which is impossible once
 * a timestamp is baked into flash.
 */
typedef struct {
    int64_t rel_ms;         /**< packed (run, ms); see ::store_rel_pack */
    int64_t epoch_ms;       /**< what the world clock read at that moment */
    int16_t utc_offset_min; /**< the zone the supplied time was in */
} store_anchor_t;

/** Bytes one equivalence occupies in an anchor page. `store.ANCHOR_STRUCT`. */
#define STORE_ANCHOR_BYTES 18

/**
 * @brief Equivalences one anchor page carries.
 *
 * Each anchor page repeats every equivalence known for its run, so the newest is
 * a complete record on its own and losing the oldest pages to the ring does not
 * lose the oldest equivalences with them. Sixteen is 288 bytes of a 464-byte
 * body.
 */
#define STORE_MAX_ANCHORS 16

/** @brief One reading, as the store cares about it. */
typedef struct {
    uint32_t index;   /**< 1-based within its session */
    float values[STORE_FIELDS];
    int64_t epoch_ms; /**< when it was taken */
    bool settling;    /**< the measurement was disturbed */
} store_reading_t;

/** @brief Accumulates readings, then seals them into exactly one page. */
typedef struct {
    uint32_t page_seq;
    uint32_t session;
    int64_t session_start_ms;
    int64_t page_start_ms;
    uint32_t cadence_ms;
    uint32_t first_index;
    int16_t utc_offset_min;
    uint8_t count;
    /** Times here are the board's own clock. See ::STORE_FLAG_RELATIVE. */
    bool relative;

    int32_t prev[STORE_FIELDS];
    uint8_t payload[STORE_BODY_BYTES];
    uint16_t payload_len;
    uint8_t markers[STORE_MARKER_BYTES];
    uint16_t marker_len;
    uint8_t marker_count;

    /* An open run of settling readings, not yet written into `markers`. */
    uint16_t settle_at;
    uint16_t settle_run;
} store_writer_t;

/** @brief A decoded page. */
typedef struct {
    uint32_t page_seq;
    uint32_t session;
    int64_t session_start_ms;
    int64_t page_start_ms;
    uint32_t cadence_ms;
    uint32_t first_index;
    int16_t utc_offset_min;
    uint8_t count;
    bool full;
    bool relative;
    store_reading_t readings[STORE_MAX_READINGS];
} store_page_t;

/** @brief Start a page. @p page_seq orders pages for the life of the device. */
void store_writer_init(store_writer_t *w, uint32_t page_seq, uint32_t session,
                       int64_t session_start_ms, uint32_t cadence_ms,
                       int16_t utc_offset_min, bool relative);

/**
 * @brief Freeze a run's equivalences into one page.
 *
 * The same 512 bytes, the same header and the same CRC as a reading page, so the
 * ring, the boot scan and the power-fail story all work on it unchanged - but
 * flagged ::STORE_FLAG_ANCHOR and holding `count == 0` readings. That is the one
 * place in this format where a zero count is valid, and the flag is what makes
 * it valid rather than corrupt.
 *
 * @param count equivalences in @p anchors; the newest ::STORE_MAX_ANCHORS are
 *        kept if there are more.
 * @return false if @p count is zero - a page saying nothing is not worth a slot.
 */
bool store_seal_anchor_page(uint8_t out[STORE_PAGE_BYTES], uint32_t page_seq,
                            uint32_t session, int64_t session_start_ms,
                            int64_t page_start_ms, int16_t utc_offset_min,
                            const store_anchor_t *anchors, size_t count);

/**
 * @brief Westmost real zone, in minutes east of UTC: UTC-12:00, Baker Island.
 */
#define STORE_UTC_OFFSET_MIN_WEST (-720)

/**
 * @brief Eastmost real zone, in minutes east of UTC: UTC+14:00, Kiritimati.
 */
#define STORE_UTC_OFFSET_MIN_EAST (840)

/**
 * @brief Hold one stored UTC offset to what a real zone can be.
 *
 * A board that ran a build before the seconds/minutes fix has anchors on flash
 * whose `utc_offset_min` is sixty times too large, and a firmware update does
 * not clean data. Nothing rewrites flash - the repair is applied to the value on
 * its way out of a page, every time it is read - so the ring stays exactly the
 * append-only record it was and a backup taken before the update still decodes
 * the same way.
 *
 * A value already inside the range is left alone. One outside it that is a whole
 * number of minutes when read as seconds, and lands inside the range then, is
 * unambiguously that: -14400 is -240 in the only unit the field has ever meant.
 * Anything else has no single meaning to recover and the caller drops it.
 *
 * @param offset_min repaired in place when it can be.
 * @return false when the value is not a zone and cannot be made into one.
 */
bool store_repair_utc_offset_min(int16_t *offset_min);

/**
 * @brief Read the equivalences out of an anchor page.
 *
 * Every equivalence is put through ::store_repair_utc_offset_min, and one that
 * cannot be is left out rather than handed on: an anchor whose zone is nonsense
 * places a session at a nonsense time, which is worse than the session staying
 * unplaced and saying so.
 *
 * @return how many were written to @p out, or 0 if @p raw is not an anchor page.
 */
size_t store_decode_anchors(const uint8_t raw[STORE_PAGE_BYTES], store_anchor_t *out,
                            size_t cap);

/**
 * @brief Try to pack one reading in.
 *
 * @return false when it does not fit, or when its index is not the next one -
 *         a dropout ends a page so the hole stays visible. Seal, start a new
 *         page, and offer the same reading again; it is guaranteed to fit an
 *         empty one.
 */
bool store_writer_add(store_writer_t *w, const store_reading_t *r);

/** @brief True once the writer holds nothing. */
static inline bool store_writer_empty(const store_writer_t *w) { return w->count == 0; }

/**
 * @brief Freeze the page into @p out.
 *
 * @param full whether it is being sealed because it filled.
 * @return false if there is nothing to seal.
 */
bool store_writer_seal(store_writer_t *w, uint8_t out[STORE_PAGE_BYTES], bool full);

/**
 * @brief Decode one page into @p out.
 *
 * Never reads outside @p raw: a page that was half written when the power went
 * is simply not a page, and saying so is the whole of the recovery story.
 *
 * @p out is written into as decoding proceeds and is **not** restored on
 * failure, because keeping it pristine would mean a 2.6 KB temporary on a stack
 * that cannot hold one. A caller must therefore ignore @p out when this returns
 * false, which every caller does anyway.
 *
 * @return false if @p raw is blank, corrupt, or not a page of this version.
 */
bool store_decode_page(const uint8_t raw[STORE_PAGE_BYTES], store_page_t *out);

/**
 * @brief A page's header, without its readings.
 *
 * Separate from ::store_page_t because the difference in size decides where the
 * code can run. A ::store_page_t is about 2.6 KB - forty-six readings of ten
 * floats - and a local one is more than a FreeRTOS task stack can spare. The
 * scan and the cursor only ever need the header to decide whether a page is
 * interesting, so they take one of these and the big struct is filled once, into
 * storage that is not the stack.
 *
 * That is not a micro-optimisation: putting a ::store_page_t on the store task's
 * stack overflowed it and the board boot-looped.
 */
typedef struct {
    uint32_t page_seq;
    uint32_t session;
    int64_t session_start_ms;
    int64_t page_start_ms;
    uint32_t cadence_ms;
    uint32_t first_index;
    int16_t utc_offset_min;
    uint8_t count;
    bool full;
    /** Times here are the board's own clock. See ::STORE_FLAG_RELATIVE. */
    bool relative;
    /** This page holds equivalences, not readings. See ::STORE_FLAG_ANCHOR. */
    bool anchor;
} store_header_t;

/**
 * @brief Read a page's header only, without checking the CRC.
 *
 * For the boot-time scan, which reads every slot in the ring to find the write
 * head and rebuild the session list. Checking five thousand CRCs there would
 * cost most of a second to establish something the scan does not rely on: a page
 * with a plausible header and a bad body simply fails ::store_decode_page later
 * and is skipped, which is the same outcome by a cheaper route.
 *
 * Only the first ::STORE_HEADER_BYTES of @p raw are read.
 */
bool store_peek_page(const uint8_t raw[STORE_HEADER_BYTES], store_header_t *out);

/* --- the pieces, exposed because the tests pin them down ------------------ */

/** @brief Zigzag: map a signed int onto an unsigned one, small stays small. */
uint64_t store_zigzag(int64_t value);
/** @brief The inverse of ::store_zigzag. */
int64_t store_unzigzag(uint64_t value);
/** @brief Append an LEB128 varint. @return bytes written, 0 if it did not fit. */
size_t store_put_varint(uint8_t *out, size_t cap, uint64_t value);
/** @brief Read a varint. @return bytes consumed, 0 on a malformed or short one. */
size_t store_get_varint(const uint8_t *in, size_t len, uint64_t *value);
/** @brief One field's value as an integer count of its quantum. */
int32_t store_quantise(float value, int field);
/** @brief The inverse of ::store_quantise. */
float store_dequantise(int32_t quanta, int field);
/** @brief CRC-32 (the zlib/Ethernet polynomial), so Python and C agree. */
uint32_t store_crc32(const uint8_t *data, size_t len);

/* The same CRC in spans, so a page can be checked with its own CRC field read
 * as zero without copying the page to zero four bytes of it. */
uint32_t store_crc32_begin(void);
uint32_t store_crc32_feed(uint32_t crc, const uint8_t *data, size_t len);
uint32_t store_crc32_end(uint32_t crc);

#ifdef __cplusplus
}
#endif

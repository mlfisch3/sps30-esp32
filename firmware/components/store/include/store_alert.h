/**
 * @file store_alert.h
 * @brief What counts as alert-level, and the header that outlives the samples.
 *
 * Pure code: no ESP-IDF, no flash, no clock, so all of it runs under
 * `make -C firmware/test`. `store_preserve.h` is the area it gets written into
 * and `store.h` wires the two to the ring.
 *
 * ## What "alert-level" means here, and where the definition came from
 *
 * It is **not** a new idea invented for storage. The app already decides that a
 * reading is worth telling the user about, in `data/Alerts.kt`: an ::AlertRule
 * watches one field and fires at or above a threshold, and the shipped default
 * is `pm2_5 >= 20.0` - the number the captain named in
 * `sensirion_project_tasks_20260728.md` and the only rule enabled out of the box.
 *
 * That same document says thresholds "shouldn't be saved locally on the sensor
 * because that info only is useful for notifying the user", and `Alerts.kt`
 * repeats it. Nothing here contradicts that. The controller is **not** given the
 * user's rules: not their labels, not whether they notify, vibrate, sound or
 * reach a watch, not their escalation order, not how many there are. It is given
 * **one number per field** - the lowest line below which a reading is not worth
 * keeping - and that is a retention policy, not a notification setting. It has to
 * exist on the controller because the whole point of preservation is the case
 * where **no phone is present** to hold an opinion.
 *
 * A connected app may move the line by sending the minimum of its *enabled*
 * rules' thresholds per field. Taking the minimum is deliberate: preserving a
 * superset of what any rule would fire on is correct, and preserving less than
 * some rule would fire on is not.
 *
 * ## Hysteresis, and why a segment is not one reading
 *
 * ::STORE_ALERT_HYSTERESIS is `AlertEngine.HYSTERESIS_FRACTION` and is here for
 * the same reason it is there: air hovering on a threshold is the normal case,
 * and without it a single excursion shatters into hundreds of one-point segments
 * that each cost a header and a flash page. A segment therefore opens on the
 * first reading at or above the line and closes only once the reading has been
 * clear of `threshold * (1 - HYSTERESIS)` for ::STORE_ALERT_HANG_POINTS in a row.
 *
 * ## The header
 *
 * ::store_alert_header_t is the record the captain specified, and the reason it
 * is a fixed 128 bytes in its own region rather than a field of the page format
 * is his requirement that it **survive even if everything else about the segment
 * is eventually reduced away**. Headers and samples therefore age independently:
 * see `store_preserve.h`.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "store_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Index of `pm2_5` in `wire.FIELDS`, which is the field a rule watches by default. */
#define STORE_ALERT_FIELD_PM2_5 1

/**
 * @brief The shipped line: 20 ug/m3 of PM2.5.
 *
 * `AlertEngine.defaults()`'s only enabled rule, and the figure the captain
 * offered ("maybe 20 micrograms. You can decide that."). A board that has never
 * met a phone preserves against this, which is the case preservation exists for.
 */
#define STORE_ALERT_DEFAULT_PM2_5 20.0f

/** `AlertEngine.HYSTERESIS_FRACTION`. Keep the two equal. */
#define STORE_ALERT_HYSTERESIS 0.10f

/**
 * @brief Readings that must be clear of the re-arm line before a segment closes.
 *
 * Thirty seconds at 1 Hz. Hysteresis stops a value sitting *on* the line from
 * chattering; this stops a genuine excursion that dips for a few seconds from
 * being filed as two events with two headers and two part-used pages.
 */
#define STORE_ALERT_HANG_POINTS 30

/**
 * @brief The most points one segment may hold: an hour of continuous 1 Hz.
 *
 * A cap, not a target. A segment's header is written when the segment ends, so
 * an excursion that ran for a day would have a day of samples on flash and no
 * header describing them until it finished - and a power cut in the middle would
 * leave exactly that: sample pages nothing points at. Capping it turns a long
 * excursion into a run of adjacent segments, each of which is complete and
 * headered the moment it ends, which is also a more useful thing to read than
 * one summary averaged over a day.
 */
#define STORE_ALERT_MAX_POINTS 3600

/**
 * @brief Points averaged for the midpoint value, centred on the segment's middle.
 *
 * The captain asked for "a midpoint value averaged over a local neighbourhood".
 * Nine points - four either side - is wide enough that one noisy sample does not
 * become the summary and narrow enough to still describe the middle rather than
 * the whole, which is what ::store_alert_header_t::mean is already for. A
 * segment shorter than this averages whatever it has.
 */
#define STORE_ALERT_MIDPOINT_SPAN 9

/** Bytes per stored header record. Divides a 4096-byte sector exactly. */
#define STORE_ALERT_HEADER_BYTES 128

/** `"SA"` little-endian, so a header record cannot be mistaken for a page. */
#define STORE_ALERT_MAGIC 0x4153u
#define STORE_ALERT_VERSION 1u

/** The samples this header describes are no longer on the board. */
#define STORE_ALERT_FLAG_BODY_GONE 0x01u
/** The segment was still open when the board lost power; it is still a segment. */
#define STORE_ALERT_FLAG_TRUNCATED 0x02u

/**
 * @brief The line, per field. A field with a threshold <= 0 is not watched.
 *
 * Ten floats and a version. Small enough to pass by value, which is why nothing
 * here needs a lock.
 */
typedef struct {
    float threshold[STORE_FIELDS];
    /** Bumped whenever the policy changes, so a listener can notice. */
    uint32_t version;
} store_alert_policy_t;

/**
 * @brief One preserved segment, as the header records it.
 *
 * Every field the captain listed is here: start and stop times, first and last
 * values, the midpoint averaged over a neighbourhood, the average and variance,
 * the minimum and maximum, and the number of points.
 *
 * The statistics are of the **tripping field**, named by ::field, because that
 * is the series the segment is a segment *of*. The samples keep all ten; if they
 * are reduced away, what survives is a complete account of the one series that
 * caused the event, which is what the header is for.
 */
typedef struct {
    /* --- identity: which readings these were ------------------------------- */
    uint32_t session;          /**< acquisition session number */
    int64_t session_start_ms;  /**< the other half of the identity; see store.c */
    uint32_t first_index;      /**< 1-based index of the first point, in its session */
    uint32_t last_index;
    int16_t utc_offset_min;

    /* --- the captain's list ------------------------------------------------ */
    int64_t start_ms;   /**< start time */
    int64_t stop_ms;    /**< stop time */
    float first;        /**< first value */
    float last;         /**< last value */
    float midpoint;     /**< midpoint, averaged over ::STORE_ALERT_MIDPOINT_SPAN */
    float mean;         /**< segment average */
    float variance;     /**< segment variance (population) */
    float minimum;
    float maximum;
    uint32_t points;    /**< number of points */

    /* --- why it was kept --------------------------------------------------- */
    uint8_t field;      /**< index into `wire.FIELDS` of the series that tripped */
    float threshold;    /**< the line in force at the time, so the header explains itself */

    /* --- where it sits ----------------------------------------------------- */
    uint32_t seq;       /**< header record sequence; never repeats, orders records */
    uint32_t wrap;      /**< ::store_wraps at the time, so relative time keeps counting */
    uint32_t body_seq;  /**< page_seq of this segment's first preserved page */
    uint32_t body_pages;
    uint8_t flags;
} store_alert_header_t;

/**
 * @brief A segment being accumulated, one reading at a time.
 *
 * Fixed size and self-contained: a segment may be hours long and nothing here
 * grows with it. Variance is Welford's, in double, because the naive sum of
 * squares loses every significant digit on a series that sits near 20 with a
 * spread of tenths - which is exactly the series this measures.
 */
typedef struct {
    bool open;
    uint8_t field;
    float threshold;
    uint32_t session;
    int64_t session_start_ms;
    int16_t utc_offset_min;
    /**
     * Its session recorded against the board's own clock, not a wall clock.
     *
     * Carried so a preserved page is written with the same time base as the
     * page it was copied out of. Without it a packed relative value would be
     * written into a page flagged absolute and read back as a date - which is
     * the one thing relative time exists to stop. See `store_codec.h`.
     */
    bool relative;
    uint32_t first_index;
    uint32_t last_index;
    int64_t start_ms;
    int64_t stop_ms;
    float first;
    float last;
    float minimum;
    float maximum;
    uint32_t points;
    double mean;
    double m2;
    /** Consecutive readings clear of the re-arm line. See ::STORE_ALERT_HANG_POINTS. */
    uint16_t clear_run;
} store_alert_seg_t;

/* --- the policy ------------------------------------------------------------ */

/** @brief The shipped policy: PM2.5 at ::STORE_ALERT_DEFAULT_PM2_5, nothing else. */
void store_alert_policy_default(store_alert_policy_t *out);

/**
 * @brief Set one field's line. A @p threshold <= 0 (or not finite) stops watching it.
 * @return false if @p field is not a field.
 */
bool store_alert_policy_set(store_alert_policy_t *policy, int field, float threshold);

/** @brief True if any field is watched at all. */
bool store_alert_policy_watches(const store_alert_policy_t *policy);

/**
 * @brief Which field, if any, this reading trips.
 *
 * @return the field index, or -1. When more than one is over its line the
 *         **lowest field index** wins, which puts the PM series before the
 *         number-concentration series and PM2.5 before PM10 - the order
 *         `wire.FIELDS` is already in, and the order of health relevance.
 */
int store_alert_trip(const store_alert_policy_t *policy, const float values[STORE_FIELDS]);

/** @brief The line a reading must fall below for an open segment to start closing. */
float store_alert_rearm(float threshold);

/* --- accumulating a segment ------------------------------------------------ */

/**
 * @brief Open a segment on @p reading, which must have tripped @p field.
 * @param relative its session recorded against the board's own clock, so a
 *        page preserved from it is written with the same time base.
 */
void store_alert_seg_begin(store_alert_seg_t *seg, int field, float threshold,
                           uint32_t session, int64_t session_start_ms, int16_t utc_offset_min,
                           bool relative, const store_reading_t *reading);

/**
 * @brief Fold one more reading in.
 *
 * Every reading offered while the segment is open is admitted, including the
 * ones under the line: the shoulders of an excursion are part of it, and a
 * header whose first and last values were both exactly the threshold would
 * describe nothing. ::store_alert_seg_closing decides when to stop offering.
 */
void store_alert_seg_add(store_alert_seg_t *seg, const store_reading_t *reading);

/**
 * @brief True once the run has been clear of the re-arm line long enough to end.
 *
 * Call after ::store_alert_seg_add. The trailing clear readings stay in the
 * segment - they are what shows it came back down.
 */
bool store_alert_seg_closing(const store_alert_seg_t *seg);

/**
 * @brief True if @p reading belongs to the same run @p seg is accumulating.
 *
 * A reading from a different session, or one whose index is not the next, ends
 * the segment wherever it had got to: a segment is a contiguous run, and joining
 * two stretches across a gap would put a hole inside something whose whole
 * purpose is to be a complete account of one event.
 */
bool store_alert_seg_continues(const store_alert_seg_t *seg, uint32_t session,
                               int64_t session_start_ms, const store_reading_t *reading);

/**
 * @brief Fill in @p out from @p seg. ::store_alert_header_t::midpoint is left at
 *        the segment mean.
 *
 * The true midpoint needs the samples either side of the middle, which are on
 * flash by the time the segment ends; ::store_alert_midpoint computes it from
 * them and the caller overwrites the field. Defaulting it to the mean rather
 * than to zero means a header written when the samples cannot be re-read still
 * carries a defensible number rather than a false one.
 */
void store_alert_seg_header(const store_alert_seg_t *seg, store_alert_header_t *out);

/**
 * @brief The neighbourhood mean at the middle of @p count values.
 *
 * @param values the segment's tripping-field values, in order. May be a window
 *        rather than the whole segment; @p first_at says which point
 *        `values[0]` is, so a caller that only re-read the pages around the
 *        middle gets the same answer as one that re-read all of them.
 * @param count how many are in @p values.
 * @param total the segment's full point count, which is what the middle is of.
 * @param first_at the segment position of `values[0]`.
 * @return the mean over the ::STORE_ALERT_MIDPOINT_SPAN points centred on
 *         `total / 2`, clipped to what is available. NAN if nothing usable.
 */
float store_alert_midpoint(const float *values, uint32_t count, uint32_t total,
                           uint32_t first_at);

/* --- the header record on flash -------------------------------------------- */

/** @brief Serialise @p header into exactly ::STORE_ALERT_HEADER_BYTES bytes. */
void store_alert_header_encode(const store_alert_header_t *header,
                               uint8_t out[STORE_ALERT_HEADER_BYTES]);

/**
 * @brief Read one back. @return false if it is blank, corrupt or the wrong version.
 *
 * The same bargain the page format makes: a record interrupted by a power cut
 * fails its CRC and is simply not a record, so there is nothing to repair on the
 * way up.
 */
bool store_alert_header_decode(const uint8_t raw[STORE_ALERT_HEADER_BYTES],
                               store_alert_header_t *out);

/* --- rate reduction, when the preservation area comes under pressure ------- */

/**
 * @brief The rungs, in milliseconds between recorded readings.
 *
 * The captain's own figures: full rate, then one a minute, then one every
 * fifteen minutes - "about 1.2 kB a day, so this buys a great deal". Level 0
 * imposes nothing at all.
 *
 * This is a **floor on the recording interval, not a mode change**. It does not
 * stop the fan, does not enter duty cycle and does not touch the sensor, so a
 * reduction cannot put a spike or a settling window into the captain's data -
 * and, critically, it is only ever reached for pressure on the **preservation
 * area**. Pressure on the ring is answered by wrapping, which is what a ring is
 * for; see `store.h`.
 */
#define STORE_ALERT_RATE_LEVELS 3
uint32_t store_alert_rate_floor_ms(uint8_t level);

/**
 * @brief The level @p pressure calls for, given the level in force.
 *
 * Raises a rung at 60 % of the preservation body region used and again at 85 %,
 * and lowers one when the pressure falls well clear of the rung below - the gap
 * is deliberate, because a level that tracked the fraction exactly would step up
 * and down every few readings and rewrite the profile each time.
 *
 * It **does** come back down, which the captain's note does not say in so many
 * words. It follows from the rule that governs all of this: recording incoming
 * data beats protecting older data. A board left at one sample per fifteen
 * minutes for ever because of one smoky afternoon is protecting old data at the
 * cost of new, which is the trade this whole design refuses everywhere else.
 *
 * @param pressure 0..1, the fraction of the preservation body region in use.
 */
uint8_t store_alert_rate_level(uint8_t level, float pressure);

#ifdef __cplusplus
}
#endif

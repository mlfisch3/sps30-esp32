package com.sensirion.sps30.data

/**
 * Progress of loading sessions stored on this device into the plot.
 *
 * The archives are hundreds of megabytes and take tens of seconds to read, so
 * this is a state a screen can render rather than a call that either returns or
 * does not. The end states say what was *not* loaded as well as what was: a
 * summary that only counts successes reads as "all of it" when it was not.
 */
sealed interface StoredImport {
    data object Idle : StoredImport

    /** Looking for stored files, before anything is known about their size. */
    data object Scanning : StoredImport

    /**
     * Reading, with [sessionsTotal] counting only the files that carry readings.
     *
     * Two thirds of the stored files are a header and nothing else, and while
     * they were in this denominator the bar spent most of its length on them
     * and then crawled through the real data at the far right. [emptySkipped]
     * is what was left out, shown alongside so the shorter total is explained
     * rather than merely smaller.
     */
    data class Loading(
        val session: String,
        val sessionsDone: Int,
        val sessionsTotal: Int,
        val points: Int,
        /** Files already known to hold no readings, excluded from the total. */
        val emptySkipped: Int = 0,
    ) : StoredImport {
        val fraction: Float
            get() = if (sessionsTotal > 0) {
                (sessionsDone.toDouble() / sessionsTotal).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    data class Loaded(
        val sessions: Int,
        val points: Int,
        val rows: Long,
        /**
         * Files that held a header and no readings.
         *
         * Skipped, not removed: they are still where they were. See
         * `SessionPlan` for what one of them contains and why there are so many.
         */
        val empty: Int,
        /** Files whose header this build could not make sense of. */
        val unrecognised: Int,
        /** Sessions an overlapping archive, or an earlier run, already supplied. */
        val duplicate: Int,
        val bucketSeconds: Double,
        val start: Double,
        val end: Double,
        /** True when the point budget ran out before the data did. */
        val truncated: Boolean,
    ) : StoredImport

    data class Failed(val reason: String) : StoredImport

    companion object {
        /**
         * Most averaged points to hold from stored data.
         *
         * Bounds memory and keeps panning responsive. About 150,000 points is
         * some 15 MB in the columnar store and still an order of magnitude more
         * than any window can draw, so the averaging this forces is invisible
         * until the user zooms a long way in - at which point the honest answer
         * really is that the phone is not holding every original reading.
         */
        const val MAX_POINTS = 150_000

        /**
         * Bytes per stored CSV row, measured across the recorded archive
         * (a 79,746-row file is 17.5 MB). Only used to choose an averaging
         * window before reading anything, so being roughly right is enough.
         */
        const val ROW_BYTES = 220.0

        /** Averaging windows to choose from - values a person would name. */
        val BUCKETS = listOf(
            1.0, 2.0, 5.0, 10.0, 15.0, 30.0,
            60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0,
        )

        /**
         * The fewest points an import is ever given, however full the plot is.
         *
         * [freePoints] would otherwise hand a plot that is already at the budget
         * a share of nothing, and an import that stores nothing is
         * indistinguishable from stored files that hold nothing. Twenty days of
         * recording at this many points is a reading every five minutes, which
         * is enough to see which days have data and what the air was doing, and
         * costs about a quarter of a megabyte. It is the one place the plot is
         * allowed to end up over [MAX_POINTS], and by this much at most.
         */
        const val MIN_POINTS = 5_000

        /**
         * What is left of the point budget for stored data, given a plot already
         * holding [heldPoints] of the sensor's own readings.
         *
         * The import does not own the whole budget and never did. Sizing the
         * averaging as if it did is what made adding stored history *remove*
         * history: the import filled its 150,000 on top of what the live stream
         * had already accumulated, the combined total went over the cap, and
         * [TimeSeriesHistory] evicts oldest-first - which is exactly the early
         * history the import was fetching. Measured on the captain's phone on
         * 2026-08-25: about 197,000 live points held, 477,000 stored readings
         * offered, and the plot's left edge moved *forward* by two days.
         *
         * Floored at [MIN_POINTS] rather than clamped to zero, so a full plot
         * still gets a coarse sketch of what is on disk.
         */
        fun freePoints(heldPoints: Int, maxPoints: Int = MAX_POINTS): Int =
            (maxPoints - heldPoints.coerceAtLeast(0)).coerceAtLeast(MIN_POINTS)

        /**
         * The coarsest-but-smallest averaging window that keeps an estimated
         * [totalBytes] of stored CSV inside [maxPoints].
         *
         * [maxPoints] is the budget the import may actually spend, which on a
         * plot that already holds live readings is [freePoints] and not
         * [MAX_POINTS]. Chosen up front from file sizes rather than adapted
         * while reading, because the bucket ordinal is also the de-duplication
         * key: changing it halfway would make the same session import twice.
         * Deciding it once from a figure read once keeps that true.
         */
        fun bucketFor(totalBytes: Long, maxPoints: Int = MAX_POINTS): Double {
            val rows = totalBytes / ROW_BYTES
            val needed = rows / maxPoints.coerceAtLeast(1)
            return BUCKETS.firstOrNull { it >= needed } ?: BUCKETS.last()
        }
    }
}

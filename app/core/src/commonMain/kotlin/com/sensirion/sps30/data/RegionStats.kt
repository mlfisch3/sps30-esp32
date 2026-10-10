package com.sensirion.sps30.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What a selected stretch of the record says about itself, computed from the
 * samples in that stretch - never from the points a chart happened to draw.
 *
 * That distinction is the reason this exists. At wide windows the drawn points
 * are bucket means, and the mean of a bucket is quietly smaller than the spike
 * inside it; a "maximum" read off the drawn curve would understate exactly the
 * excursion the user selected the stretch to study. So the inputs here are the
 * full-resolution samples, and every figure below is a statement about the
 * record.
 *
 * Pure arithmetic over parallel arrays, in commonMain, so the numbers are the
 * same on every platform and the tests run under Kotlin/Native as well as the
 * JVM.
 */
data class RegionStats(
    /** How many samples the stretch holds. */
    val count: Int,
    /** When the first and last sample in the stretch were taken. */
    val firstAt: Double,
    val lastAt: Double,
    /** The largest sample, and the moment it was taken (the first, if tied). */
    val max: Double,
    val maxAt: Double,
    /** Arithmetic mean of the samples. Deliberately not time-weighted: the
     *  record is near-uniformly sampled, and weighting across a recording gap
     *  would let unrecorded time speak. */
    val mean: Double,
    /** Sample standard deviation (n - 1), or null with fewer than two samples. */
    val stdDev: Double?,
    /** The peak's width at half its height, or null where no peak stands. */
    val fwhm: Fwhm?,
    /** Seconds at or above the threshold, or null when none was given. */
    val timeAboveSeconds: Double?,
) {

    /**
     * Full width at half maximum, measured directly from the samples.
     *
     * Directly, not by fitting a Gaussian: a fit is cheap but it asserts a
     * shape, and real pollution peaks - a pan left smoking, a door opened onto
     * traffic - are skewed, plateaued, anything but Gaussian. The measured
     * width assumes nothing.
     *
     * The height is taken **above the selection's own baseline** - its minimum
     * - so a peak riding on elevated background is measured as the peak it is,
     * not against an irrelevant zero. The half level is `baseline + (max -
     * baseline) / 2`, and the width runs between the two crossings of that
     * level either side of the maximum, linearly interpolated between the
     * samples that straddle each crossing.
     *
     * A crossing that cannot be measured is not invented:
     *
     *  - where the samples are still at or above the half level when the
     *    selection (or the walk's data) runs out, that side is **clipped**: the
     *    width is a lower bound, and the flag says which edge cut it short;
     *  - where the walk meets a recording gap while still above the half
     *    level, the same applies - interpolating a crossing across time nobody
     *    recorded would be fiction, so the width stops at the last sample
     *    before the gap and the side is marked clipped.
     */
    data class Fwhm(
        val seconds: Double,
        val clippedLeft: Boolean,
        val clippedRight: Boolean,
    ) {
        val clipped: Boolean get() = clippedLeft || clippedRight
    }

    companion object {

        /**
         * The statistics of the samples in ([epochs], [values]), or null when
         * there are none.
         *
         * @param epochs sample times, ascending; only the first
         *   `min(epochs.size, values.size)` entries are read.
         * @param threshold the level "time above" is measured against, or null
         *   when the series has none set - the figure is then null, not zero,
         *   because "no level to measure against" is not "never above it".
         * @param gapSeconds spacing beyond which two neighbouring samples are
         *   not treated as continuous recording (see `ChartView.gapThreshold`,
         *   which is where the plot derives the same rule). Null disables gap
         *   handling, which is only appropriate for data known to be unbroken.
         */
        fun compute(
            epochs: DoubleArray,
            values: DoubleArray,
            threshold: Double? = null,
            gapSeconds: Double? = null,
        ): RegionStats? {
            val n = minOf(epochs.size, values.size)
            if (n == 0) return null

            var max = values[0]
            var maxIdx = 0
            var min = values[0]
            var sum = 0.0
            for (i in 0 until n) {
                val v = values[i]
                if (v > max) {
                    max = v
                    maxIdx = i
                }
                if (v < min) min = v
                sum += v
            }
            val mean = sum / n

            val stdDev = if (n < 2) null else {
                var sq = 0.0
                for (i in 0 until n) {
                    val d = values[i] - mean
                    sq += d * d
                }
                sqrt(sq / (n - 1))
            }

            return RegionStats(
                count = n,
                firstAt = epochs[0],
                lastAt = epochs[n - 1],
                max = max,
                maxAt = epochs[maxIdx],
                mean = mean,
                stdDev = stdDev,
                fwhm = fwhm(epochs, values, n, maxIdx, max, min, gapSeconds),
                timeAboveSeconds = threshold?.let {
                    timeAbove(epochs, values, n, it, gapSeconds)
                },
            )
        }

        private fun fwhm(
            epochs: DoubleArray,
            values: DoubleArray,
            n: Int,
            maxIdx: Int,
            max: Double,
            baseline: Double,
            gapSeconds: Double?,
        ): Fwhm? {
            // A flat stretch has no peak to measure. Exact equality on purpose:
            // any rise at all defines a level to cross, however small the
            // number that comes out.
            if (max <= baseline) return null
            val half = baseline + (max - baseline) / 2.0

            var leftAt = epochs[0]
            var clippedLeft = true
            var i = maxIdx
            while (i > 0) {
                val dt = epochs[i] - epochs[i - 1]
                if (gapSeconds != null && dt > gapSeconds) {
                    // Still above the half level with nothing recorded further
                    // back: the crossing is somewhere in the gap, unknowable.
                    leftAt = epochs[i]
                    break
                }
                if (values[i - 1] < half) {
                    leftAt = crossing(epochs[i - 1], values[i - 1], epochs[i], values[i], half)
                    clippedLeft = false
                    break
                }
                i--
                if (i == 0) leftAt = epochs[0]
            }

            var rightAt = epochs[n - 1]
            var clippedRight = true
            var j = maxIdx
            while (j < n - 1) {
                val dt = epochs[j + 1] - epochs[j]
                if (gapSeconds != null && dt > gapSeconds) {
                    rightAt = epochs[j]
                    break
                }
                if (values[j + 1] < half) {
                    rightAt = crossing(epochs[j], values[j], epochs[j + 1], values[j + 1], half)
                    clippedRight = false
                    break
                }
                j++
                if (j == n - 1) rightAt = epochs[n - 1]
            }

            return Fwhm(
                seconds = (rightAt - leftAt).coerceAtLeast(0.0),
                clippedLeft = clippedLeft,
                clippedRight = clippedRight,
            )
        }

        /** Where the line from (t0,v0) to (t1,v1) crosses [level]. */
        private fun crossing(t0: Double, v0: Double, t1: Double, v1: Double, level: Double): Double {
            val rise = v1 - v0
            if (abs(rise) < 1e-12) return t0
            return t0 + (level - v0) / rise * (t1 - t0)
        }

        /**
         * Seconds at or above [threshold], summed over the recorded intervals.
         *
         * Each pair of neighbouring samples contributes the part of its
         * interval the linear interpolation between them spends at or above
         * the level: all of it with both at or above, none with both below,
         * and the interpolated fraction across a crossing. An interval wider
         * than [gapSeconds] contributes nothing - nothing was recorded there,
         * and unrecorded time cannot be counted as time above anything.
         *
         * A single sample therefore reports 0: one instant has no measurable
         * duration, however high it is. The sample still shows in the maximum.
         */
        private fun timeAbove(
            epochs: DoubleArray,
            values: DoubleArray,
            n: Int,
            threshold: Double,
            gapSeconds: Double?,
        ): Double {
            var s = 0.0
            for (i in 0 until n - 1) {
                val dt = epochs[i + 1] - epochs[i]
                if (dt <= 0.0) continue
                if (gapSeconds != null && dt > gapSeconds) continue
                val a = values[i]
                val b = values[i + 1]
                s += when {
                    a >= threshold && b >= threshold -> dt
                    a < threshold && b < threshold -> 0.0
                    else -> {
                        val f = (threshold - a) / (b - a)
                        if (a >= threshold) dt * f else dt * (1.0 - f)
                    }
                }
            }
            return s
        }
    }
}

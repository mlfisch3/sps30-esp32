package com.sensirion.sps30.data

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The statistics of a selected stretch, held to their definitions.
 *
 * These are the numbers the captain probes an excursion with, so every edge the
 * task named is pinned here: an empty selection, a single sample, a stretch
 * spanning a recording gap, a flat series with no peak to measure, and a peak
 * the selection edge cuts short. The raw-versus-drawn-buckets distinction is
 * exercised in :app (`RegionReportTest`), where the history that does the
 * bucketing lives.
 */
class RegionStatsTest {

    private fun secondsApart(values: DoubleArray, startEpoch: Double = 1_000.0): DoubleArray =
        DoubleArray(values.size) { startEpoch + it }

    private fun stats(
        values: DoubleArray,
        epochs: DoubleArray = secondsApart(values),
        threshold: Double? = null,
        gapSeconds: Double? = 60.0,
    ): RegionStats? = RegionStats.compute(epochs, values, threshold, gapSeconds)

    // --- nothing, and one thing ---------------------------------------------

    @Test
    fun anEmptySelectionHasNoStatistics() {
        assertNull(stats(doubleArrayOf()))
    }

    @Test
    fun aSingleSampleIsItsOwnMaximumAndMean() {
        val s = stats(doubleArrayOf(7.5), threshold = 5.0)
        assertNotNull(s)
        assertEquals(1, s.count)
        assertEquals(7.5, s.max, 0.0)
        assertEquals(1_000.0, s.maxAt, 0.0)
        assertEquals(7.5, s.mean, 0.0)
        // One sample has no spread to estimate and no width to measure.
        assertNull(s.stdDev)
        assertNull(s.fwhm)
        // And no duration: an instant above the level is not a length of time
        // above it. It still shows in the maximum.
        assertEquals(0.0, s.timeAboveSeconds!!, 0.0)
    }

    // --- maximum, mean, spread ----------------------------------------------

    @Test
    fun maximumAndItsMomentComeFromTheSamples() {
        val s = stats(doubleArrayOf(1.0, 9.0, 3.0))!!
        assertEquals(9.0, s.max, 0.0)
        assertEquals(1_001.0, s.maxAt, 0.0)
    }

    @Test
    fun theFirstOfTiedMaximaIsNamed() {
        val s = stats(doubleArrayOf(1.0, 9.0, 9.0, 1.0))!!
        assertEquals(1_001.0, s.maxAt, 0.0)
    }

    @Test
    fun meanAndSampleStandardDeviationAreExact() {
        val s = stats(doubleArrayOf(1.0, 2.0, 3.0, 4.0))!!
        assertEquals(2.5, s.mean, 1e-12)
        // Sample (n - 1) deviation: sqrt((2.25 + 0.25 + 0.25 + 2.25) / 3).
        assertEquals(sqrt(5.0 / 3.0), s.stdDev!!, 1e-12)
    }

    @Test
    fun firstAndLastMomentsAreTheStretchsOwn() {
        val s = stats(doubleArrayOf(2.0, 2.0, 2.0))!!
        assertEquals(1_000.0, s.firstAt, 0.0)
        assertEquals(1_002.0, s.lastAt, 0.0)
    }

    // --- FWHM ----------------------------------------------------------------

    @Test
    fun fwhmOfASymmetricTriangleIsItsHalfHeightWidth() {
        // Baseline 0, peak 4, half level 2 - crossed exactly at the samples
        // reading 2, four seconds apart.
        val s = stats(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0, 0.0))!!
        val w = s.fwhm!!
        assertEquals(4.0, w.seconds, 1e-9)
        assertFalse(w.clipped)
    }

    @Test
    fun fwhmInterpolatesBetweenSamples() {
        // 0 -> 4 -> 0 over two seconds: the half level 2 is crossed midway
        // through each interval, so the width is one second.
        val s = stats(doubleArrayOf(0.0, 4.0, 0.0))!!
        assertEquals(1.0, s.fwhm!!.seconds, 1e-9)
    }

    @Test
    fun fwhmIsMeasuredAboveTheSelectionsOwnBaseline() {
        // A peak riding on background of 10. Against zero the half level would
        // be 7 and the whole stretch would be "the peak"; against the
        // selection's baseline it is 12, and the width is one second.
        val s = stats(doubleArrayOf(10.0, 14.0, 10.0))!!
        assertEquals(1.0, s.fwhm!!.seconds, 1e-9)
    }

    @Test
    fun fwhmIsUndefinedOnAFlatStretch() {
        assertNull(stats(doubleArrayOf(3.3, 3.3, 3.3, 3.3))!!.fwhm)
    }

    @Test
    fun aPeakStillAboveHalfAtTheEdgeReportsAClippedWidth() {
        // Rising to the selection's right edge: the right crossing never
        // happens, so the width is a lower bound and says so.
        val s = stats(doubleArrayOf(0.0, 2.0, 4.0))!!
        val w = s.fwhm!!
        assertEquals(1.0, w.seconds, 1e-9)
        assertFalse(w.clippedLeft)
        assertTrue(w.clippedRight)
    }

    @Test
    fun aPeakClippedOnTheLeftSaysWhichSide() {
        val s = stats(doubleArrayOf(4.0, 2.0, 0.0))!!
        val w = s.fwhm!!
        assertEquals(1.0, w.seconds, 1e-9)
        assertTrue(w.clippedLeft)
        assertFalse(w.clippedRight)
    }

    @Test
    fun aGapInsideThePeakClipsTheWidthAtTheGap() {
        // Above the half level on both sides of 99 unrecorded seconds. The
        // crossing lies somewhere in the gap; the width must stop at the last
        // recorded sample rather than interpolate across time nobody measured.
        val epochs = doubleArrayOf(0.0, 1.0, 100.0, 101.0)
        val values = doubleArrayOf(1.0, 5.0, 5.0, 1.0)
        val w = RegionStats.compute(epochs, values, null, gapSeconds = 60.0)!!.fwhm!!
        // Left crossing interpolated at 0.5; right clipped at the gap edge, 1.0.
        assertEquals(0.5, w.seconds, 1e-9)
        assertTrue(w.clippedRight)
        assertFalse(w.clippedLeft)
    }

    @Test
    fun withoutAGapRuleTheSameShapeMeasuresWhole() {
        val epochs = doubleArrayOf(0.0, 1.0, 100.0, 101.0)
        val values = doubleArrayOf(1.0, 5.0, 5.0, 1.0)
        val w = RegionStats.compute(epochs, values, null, gapSeconds = null)!!.fwhm!!
        assertFalse(w.clipped)
        // 0.5 to 100.5: the crossings interpolated as though recording never
        // stopped - which is exactly why the gap rule exists.
        assertEquals(100.0, w.seconds, 1e-9)
    }

    // --- time above the alert level -----------------------------------------

    @Test
    fun timeAboveCountsWholeIntervalsAtOrAboveTheLevel() {
        val s = stats(doubleArrayOf(30.0, 30.0, 30.0, 30.0, 30.0), threshold = 20.0)!!
        assertEquals(4.0, s.timeAboveSeconds!!, 1e-9)
    }

    @Test
    fun timeAboveInterpolatesTheCrossings() {
        // 0 -> 10 across one second, level 5: above for the second half.
        val rising = stats(doubleArrayOf(0.0, 10.0), threshold = 5.0)!!
        assertEquals(0.5, rising.timeAboveSeconds!!, 1e-9)
        // And the mirror image on the way down.
        val falling = stats(doubleArrayOf(10.0, 0.0), threshold = 5.0)!!
        assertEquals(0.5, falling.timeAboveSeconds!!, 1e-9)
    }

    @Test
    fun timeAboveSkipsRecordingGaps() {
        // Above the level on both sides of a 998-second hole: only the two
        // recorded seconds count. Counting the hole would report a quarter of
        // an hour of bad air nobody measured.
        val epochs = doubleArrayOf(0.0, 1.0, 999.0, 1_000.0)
        val values = doubleArrayOf(30.0, 30.0, 30.0, 30.0)
        val s = RegionStats.compute(epochs, values, 20.0, gapSeconds = 60.0)!!
        assertEquals(2.0, s.timeAboveSeconds!!, 1e-9)
    }

    @Test
    fun timeAboveIsNullWithoutAThreshold() {
        // "No level set" is not "never above it": the figure is absent, not 0.
        assertNull(stats(doubleArrayOf(30.0, 30.0))!!.timeAboveSeconds)
    }

    @Test
    fun timeBelowThroughoutIsZero() {
        val s = stats(doubleArrayOf(1.0, 2.0, 1.0), threshold = 20.0)!!
        assertEquals(0.0, s.timeAboveSeconds!!, 0.0)
    }

    // --- a stretch spanning a gap -------------------------------------------

    @Test
    fun statisticsSpanAGapHonestly() {
        // The maximum is found on the far side of the gap, every sample counts
        // once, and the mean is over the samples - not weighted by a hole that
        // recorded nothing.
        val epochs = doubleArrayOf(0.0, 1.0, 2.0, 5_000.0, 5_001.0)
        val values = doubleArrayOf(2.0, 2.0, 2.0, 8.0, 2.0)
        val s = RegionStats.compute(epochs, values, null, gapSeconds = 60.0)!!
        assertEquals(5, s.count)
        assertEquals(8.0, s.max, 0.0)
        assertEquals(5_000.0, s.maxAt, 0.0)
        assertEquals(3.2, s.mean, 1e-9)
    }
}

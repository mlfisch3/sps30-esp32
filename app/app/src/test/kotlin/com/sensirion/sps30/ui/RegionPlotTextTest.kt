package com.sensirion.sps30.ui

import com.sensirion.sps30.data.RegionStats
import com.sensirion.sps30.data.compactDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection's words, held to what they must and must not say - now as the
 * few plot-text lines the captain asked for, plus the prose behind the "?".
 *
 * Two promises matter most, unchanged from when these words lived on a card.
 * A peak width that could not be fully measured must not pose as the width -
 * at readout size that is the "≥" mark, with the why in the prose. And the
 * prose must say the figures come from the raw samples, because the drawn
 * curve may be averages and that honesty note is deliberate: it survived the
 * card it used to live on.
 */
class RegionPlotTextTest {

    private fun stats(
        fwhm: RegionStats.Fwhm? = RegionStats.Fwhm(380.0, false, false),
        stdDev: Double? = 9.87,
        timeAbove: Double? = 665.0,
    ) = RegionStats(
        count = 2_070,
        firstAt = 1_770_000_000.0,
        lastAt = 1_770_002_070.0,
        max = 48.3,
        maxAt = 1_770_000_665.0,
        mean = 12.4,
        stdDev = stdDev,
        fwhm = fwhm,
        timeAboveSeconds = timeAbove,
    )

    private fun report(
        stats: RegionStats? = stats(),
        threshold: Double? = 20.0,
        canReturn: Boolean = false,
    ) = RegionReport(
        start = 1_770_000_000.0,
        end = 1_770_002_070.0,
        field = "pm2_5",
        threshold = threshold,
        stats = stats,
        canReturn = canReturn,
    )

    // --- the lines on the plot -----------------------------------------------

    @Test
    fun theSeriesAndTheDurationLeadTheLines() {
        assertEquals("PM2.5 · ${compactDuration(2_070.0)}", regionPlotLines(report()).first())
    }

    @Test
    fun theSpanLineFollowsWithBothStamps() {
        val span = regionPlotLines(report())[1]
        assertTrue(span, span.contains(" to "))
    }

    @Test
    fun theMaximumLineCarriesTheValueAndItsMoment() {
        val lines = regionPlotLines(report())
        assertTrue(lines.any { it.startsWith("max 48.30 at ") })
    }

    @Test
    fun theThresholdLineAppearsOnlyWhereALevelIsSet() {
        assertTrue(regionPlotLines(report()).any { it.startsWith("above 20.00 for ") })
        assertFalse(regionPlotLines(report(threshold = null)).any { it.startsWith("above") })
    }

    @Test
    fun theMeanLineCarriesTheSpreadAndAdmitsWhenItCannot() {
        assertTrue(regionPlotLines(report()).any { it == "mean 12.40 · sd 9.87" })
        val one = report(stats = stats(stdDev = null).copy(count = 1))
        assertTrue(regionPlotLines(one).any { it == "mean 12.40 · sd n/a" })
    }

    /**
     * Readout type is generous and the portrait plot is narrow: a line that
     * grows past about twenty characters becomes a block the placement cannot
     * find a clear corner for. Held across the wordiest shapes the lines take.
     */
    @Test
    fun everyLineStaysNarrowEnoughForAPhoneCorner() {
        val wordy = listOf(
            report(),
            report(stats = stats(fwhm = RegionStats.Fwhm(380.0, true, true))),
            report(stats = null),
            // A stretch crossing midnight names its days in the stamps.
            RegionReport(
                start = 1_770_000_000.0,
                end = 1_770_100_000.0,
                field = "pm2_5",
                threshold = 20.0,
                stats = stats(),
                canReturn = false,
            ),
        )
        for (r in wordy) {
            for (line in regionPlotLines(r)) {
                assertTrue("too wide for a plot corner: \"$line\"", line.length <= 28)
            }
        }
    }

    @Test
    fun aMeasuredWidthIsStatedPlainly() {
        assertEquals("peak width ${compactDuration(380.0)}", fwhmLine(RegionStats.Fwhm(380.0, false, false)))
    }

    @Test
    fun aClippedWidthWearsTheLowerBoundMark() {
        assertEquals(
            "peak width ≥ ${compactDuration(380.0)}",
            fwhmLine(RegionStats.Fwhm(380.0, false, true)),
        )
    }

    @Test
    fun aFlatStretchSaysThereIsNoPeakRatherThanShowingZero() {
        val line = fwhmLine(null)
        assertTrue(line, line.contains("n/a"))
        assertTrue(line, line.contains("flat"))
    }

    @Test
    fun anEmptyStretchSaysSoInsteadOfShowingNumbers() {
        val lines = regionPlotLines(report(stats = null))
        assertEquals(3, lines.size)
        assertEquals("no samples in selection", lines[2])
    }

    @Test
    fun theLinesFollowTheCaptainsPriorityOrder() {
        val lines = regionPlotLines(report())
        assertEquals(6, lines.size)
        assertTrue(lines[2].startsWith("max"))
        assertTrue(lines[3].startsWith("above"))
        assertTrue(lines[4].startsWith("mean"))
        assertTrue(lines[5].startsWith("peak width"))
    }

    // --- the prose behind the "?" --------------------------------------------

    @Test
    fun theExplanationSaysWhatTheWidthMeans() {
        val prose = regionExplanation(report(), null)
        assertTrue(prose.contains("FWHM"))
        assertTrue(prose.contains("half its rise"))
    }

    @Test
    fun theExplanationNamesTheRawSamplesAsTheSource() {
        val prose = regionExplanation(report(), null)
        assertTrue(prose.contains("2070 raw samples"))
        assertTrue(prose.contains("not the drawn points"))
    }

    @Test
    fun anAveragedChartIsCalledOut() {
        assertTrue(regionExplanation(report(), 60.0).contains("60s averages"))
        // And an un-averaged one is not accused of averaging.
        assertFalse(regionExplanation(report(), null).contains("averages"))
    }

    @Test
    fun aClippedWidthGetsItsWhyInTheProse() {
        val clipped = report(stats = stats(fwhm = RegionStats.Fwhm(380.0, false, true)))
        val prose = regionExplanation(clipped, null)
        assertTrue(prose.contains("lower bound"))
        assertTrue(prose.contains("on the right"))
        // An unclipped width needs no apology.
        assertFalse(regionExplanation(report(), null).contains("lower bound"))
    }

    @Test
    fun anEmptyStretchExplainsItselfWithoutInventingFigures() {
        assertEquals(
            "The selected stretch holds no samples.",
            regionExplanation(report(stats = null), 60.0),
        )
    }
}

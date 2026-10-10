package com.sensirion.sps30.ui

import com.sensirion.sps30.data.AlertRule
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.TimeSeriesHistory
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection's report is about the record, not about the picture.
 *
 * At wide windows the plot draws bucket means, and the mean of the bucket a
 * spike fell into is quietly lower than the spike - which is exactly the value
 * the captain must not be handed as "the maximum" of an excursion he selected
 * in order to study. So the report is computed from the raw history, and the
 * first test here holds the two apart on the same data: the drawn maximum
 * smoothed, the reported maximum true.
 */
class RegionReportTest {

    // Well after ChartView.EARLIEST_PLAUSIBLE_EPOCH, well before "now".
    private val base = 1_770_000_000.0
    private val now = base + 10_000.0

    private fun stamp(epoch: Double): String =
        Instant.ofEpochSecond(epoch.toLong()).atOffset(ZoneOffset.UTC).toString()

    /** 1 Hz pm2_5 for [seconds], flat at [level] except [spikes] (second -> value). */
    private fun history(
        seconds: Int,
        level: Double = 2.0,
        spikes: Map<Int, Double> = emptyMap(),
        events: Map<Int, String> = emptyMap(),
    ): TimeSeriesHistory {
        val h = TimeSeriesHistory(nowSeconds = { now })
        for (i in 0 until seconds) {
            val v = spikes[i] ?: level
            h.add(
                csv = "test.csv",
                index = i.toString(),
                timestamp = stamp(base + i),
                values = mapOf("pm1_0" to v / 2, "pm2_5" to v, "pm4_0" to v, "pm10" to v),
                event = events[i] ?: "",
            )
        }
        return h
    }

    @Test
    fun theMaximumComesFromRawSamplesNotFromDrawnBuckets() {
        val h = history(600, spikes = mapOf(300 to 80.0))

        // What the plot draws at this window: 60-second bucket means. The
        // spike's bucket averages to about 3.3 - the smoothed curve.
        val drawn = h.window(base, base + 600, maxPoints = null, bucketSeconds = 60.0)
        val drawnMax = drawn.series["pm2_5"]!!.max()
        assertTrue("the drawn curve must have smoothed the spike", drawnMax < 10.0)

        // What the report says: the spike itself, at its moment.
        val report = buildRegionReport(h, base, base + 600, AppConfig(), canReturn = false)
        val stats = report.stats
        assertNotNull(stats)
        assertEquals(80.0, stats!!.max, 0.0)
        assertEquals(base + 300, stats.maxAt, 0.0)
        assertEquals(600, stats.count)
    }

    @Test
    fun theReportCoversOnlyTheSelectedStretch() {
        val h = history(600, spikes = mapOf(300 to 80.0))
        // A selection that stops short of the spike must not see it. Both
        // bounds are inclusive, as the store's window is: 201 samples.
        val report = buildRegionReport(h, base, base + 200, AppConfig(), canReturn = false)
        assertEquals(2.0, report.stats!!.max, 0.0)
        assertEquals(201, report.stats!!.count)
    }

    @Test
    fun anEmptyStretchReportsNoSamplesRatherThanInventingAny() {
        val h = history(100)
        val report = buildRegionReport(
            h, base + 5_000, base + 6_000, AppConfig(), canReturn = false,
        )
        assertNull(report.stats)
        assertEquals(base + 5_000, report.start, 0.0)
        assertEquals(base + 6_000, report.end, 0.0)
    }

    @Test
    fun flaggedReadingsFollowThePlotsExclusionSetting() {
        val h = history(10, spikes = mapOf(5 to 90.0), events = mapOf(5 to "clean"))
        // The plot excludes the cleaning spike, so the numbers about the plot
        // must too - a maximum the picture refuses to draw would be a number
        // about a different chart.
        val excluded = buildRegionReport(
            h, base, base + 10, AppConfig(excludeFlagged = true), canReturn = false,
        )
        assertEquals(2.0, excluded.stats!!.max, 0.0)
        assertEquals(9, excluded.stats!!.count)
        // And with the exclusion off, the reading is a reading.
        val included = buildRegionReport(
            h, base, base + 10, AppConfig(excludeFlagged = false), canReturn = false,
        )
        assertEquals(90.0, included.stats!!.max, 0.0)
    }

    @Test
    fun theDefaultAlertLevelFeedsTimeAbove() {
        // The default config's lowest enabled rule is Elevated at 20 on pm2_5.
        val h = history(11, spikes = (3..7).associateWith { 30.0 })
        val report = buildRegionReport(h, base, base + 11, AppConfig(), canReturn = false)
        assertEquals(20.0, report.threshold!!, 0.0)
        // Above from the crossing before sample 3 to the one after sample 7:
        // interpolation puts each crossing most of the way through its
        // interval ((30-20)/(30-2) short of the high sample).
        val expectedEdge = 10.0 / 28.0
        assertEquals(4.0 + 2 * expectedEdge, report.stats!!.timeAboveSeconds!!, 1e-9)
    }

    @Test
    fun theWatchedSeriesWinsWhileItIsVisible() {
        assertEquals("pm2_5", statsField(listOf("pm1_0", "pm2_5"), AppConfig().alerts))
    }

    @Test
    fun aHiddenWatchedSeriesGivesWayToAVisibleOne() {
        // pm2_5 switched off: the report must describe a curve the user can see.
        assertEquals("pm1_0", statsField(listOf("pm1_0"), AppConfig().alerts))
    }

    @Test
    fun theLowestEnabledRuleForTheFieldSetsTheThreshold() {
        val alerts = listOf(
            AlertRule(id = "a", label = "High", threshold = 55.0),
            AlertRule(id = "b", label = "Low", threshold = 20.0),
            AlertRule(id = "c", label = "Off", threshold = 5.0, enabled = false),
            AlertRule(id = "d", label = "Other", threshold = 1.0, field = "pm1_0"),
        )
        assertEquals(20.0, regionThreshold("pm2_5", alerts)!!, 0.0)
    }

    @Test
    fun noEnabledRuleMeansNoThresholdRatherThanZero() {
        val alerts = listOf(
            AlertRule(id = "a", label = "Off", threshold = 20.0, enabled = false),
        )
        assertNull(regionThreshold("pm2_5", alerts))
    }

    @Test
    fun aSelectionSpanningARecordingGapCountsOnlyRecordedTime() {
        // 60 s recorded, a 30-minute hole, 60 s recorded - all above the level.
        val h = TimeSeriesHistory(nowSeconds = { now })
        for (i in 0 until 60) {
            h.add("a.csv", i.toString(), stamp(base + i), mapOf("pm2_5" to 30.0))
        }
        for (i in 0 until 60) {
            h.add("b.csv", i.toString(), stamp(base + 1_860 + i), mapOf("pm2_5" to 30.0))
        }
        val report = buildRegionReport(h, base, base + 2_000, AppConfig(), canReturn = false)
        val above = report.stats!!.timeAboveSeconds!!
        // Two 59-second recorded stretches; the hole contributes nothing.
        assertEquals(118.0, above, 1e-9)
        assertEquals(120, report.stats!!.count)
    }
}

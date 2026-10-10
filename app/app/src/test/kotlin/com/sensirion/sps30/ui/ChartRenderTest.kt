package com.sensirion.sps30.ui

import com.sensirion.sps30.data.AlertRule
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "save as image" is allowed to be a picture of.
 *
 * The image export draws from [ChartRender] and nothing else, so these are the
 * checks that stop it drifting away from the screen again: the exported picture
 * showed no crosshair, a linear axis where the screen had a log one, and none of
 * the annotations, because the export was handed its inputs separately from the
 * Canvas and had simply been given fewer of them.
 */
class ChartRenderTest {

    private fun chart(
        sampleCount: Int = 3,
        spanSeconds: Double = 600.0,
        xFrozen: Boolean = false,
        timeZoneLabel: String = "EDT",
    ) = ChartData(
        x = doubleArrayOf(100.0, 200.0, 300.0),
        series = mapOf("pm2_5" to doubleArrayOf(1.0, 2.0, 3.0)),
        start = 100.0,
        end = 300.0,
        xTicks = doubleArrayOf(100.0, 300.0),
        xLabels = listOf("a", "b"),
        timeZoneLabel = timeZoneLabel,
        dataYMin = 1.0,
        dataYMax = 3.0,
        spanSeconds = spanSeconds,
        sampleCount = sampleCount,
        mode = ChartMode.MOVING,
        viewMode = ViewMode.LIVE,
        windowSeconds = 600.0,
        yAuto = true,
        yMin = null,
        yMax = null,
        xFrozen = xFrozen,
        hasReferencePin = false,
    )

    private fun state(
        chart: ChartData? = chart(),
        config: AppConfig = AppConfig(),
        windowSeconds: Double = 600.0,
        gaps: RecordGapReport = RecordGapReport(),
    ) = DashboardUiState(chart = chart, config = config, windowSeconds = windowSeconds, gaps = gaps)

    @Test
    fun `carries the crosshair when one is showing`() {
        val render = state().chartRender(inspectedEpoch = 250.0)
        assertEquals(250.0, render.inspectedEpoch!!, 1e-9)
    }

    @Test
    fun `carries no crosshair when none is showing`() {
        assertNull(state().chartRender(inspectedEpoch = null).inspectedEpoch)
    }

    /** The window is the view: whatever the plot is showing is what gets drawn. */
    @Test
    fun `carries the windowed data, not the whole history`() {
        val windowed = chart(sampleCount = 3, spanSeconds = 600.0)
        val render = state(chart = windowed).chartRender(null)
        assertEquals(windowed, render.data)
        assertEquals(3, render.data?.sampleCount)
    }

    @Test
    fun `carries the axis type the user is looking at`() {
        val linear = state(config = AppConfig(logY = false)).chartRender(null)
        val log = state(config = AppConfig(logY = true)).chartRender(null)
        assertEquals(false, linear.logY)
        assertEquals(true, log.logY)
    }

    @Test
    fun `carries only the series the user has switched on`() {
        val config = AppConfig().toggleVisible("pm1_0")
        val render = state(config = config).chartRender(null)
        assertEquals(listOf("pm2_5"), render.visible)
    }

    @Test
    fun `never carries PM4 or PM10, whatever the config says`() {
        val stale = AppConfig(visiblePm = listOf("pm1_0", "pm2_5", "pm4_0", "pm10"))
        val render = state(config = stale.normalized()).chartRender(null)
        assertEquals(listOf("pm1_0", "pm2_5"), render.visible)
    }

    @Test
    fun `carries the lowest alert that asked to be drawn`() {
        val config = AppConfig(
            alerts = listOf(
                AlertRule("hi", "high", threshold = 50.0, field = "pm10"),
                AlertRule("lo", "low", threshold = 12.0, field = "pm2_5"),
            ),
        )
        val render = state(config = config).chartRender(null)
        assertEquals(12.0, render.alertThreshold!!, 1e-9)
    }

    @Test
    fun `draws no alert line when no rule asked for one`() {
        val config = AppConfig(
            alerts = listOf(
                AlertRule("off", "off", threshold = 12.0, enabled = false),
                AlertRule("quiet", "quiet", threshold = 20.0, highlightPlot = false),
            ),
        )
        val render = state(config = config).chartRender(null)
        assertNull(render.alertThreshold)
        assertTrue("a fill reached the plot from rules that asked not to be drawn",
            render.alertFills.isEmpty())
    }

    /**
     * The excursion shading is per series: each watched field carries its own
     * level, so a breach on one series fills against that series' line and not
     * against whichever rule happens to be lowest overall.
     */
    @Test
    fun `carries each series' own fill level, the lowest rule watching it`() {
        val config = AppConfig(
            alerts = listOf(
                AlertRule("a", "pm2.5 high", threshold = 35.0, field = "pm2_5"),
                AlertRule("b", "pm2.5 low", threshold = 20.0, field = "pm2_5"),
                AlertRule("c", "pm1 low", threshold = 15.0, field = "pm1_0"),
                AlertRule("d", "silent", threshold = 5.0, field = "pm1_0",
                    highlightPlot = false),
            ),
        )
        val fills = state(config = config).chartRender(null).alertFills
        assertEquals(mapOf("pm2_5" to 20.0, "pm1_0" to 15.0), fills)
    }

    @Test
    fun `carries the annotations drawn inside the plot`() {
        val render = state(chart = chart(sampleCount = 1305, spanSeconds = 129_600.0))
            .chartRender(null)
        // The compact form itself is `CompactDurationTest`'s subject; what is
        // checked here is that the readout - and so the saved picture, which
        // draws from this same value - is written in it.
        assertEquals("window 1d 12h  ·  1305 pts", render.viewInfo)
        assertEquals("EDT", render.timeZoneLabel)
    }

    @Test
    fun `says so when the view is frozen`() {
        val render = state(chart = chart(xFrozen = true)).chartRender(null)
        assertTrue(render.viewInfo, render.viewInfo.endsWith("frozen"))
    }

    /**
     * Before any data arrives the plot draws "connecting…", and the export has to
     * be able to say the same rather than fail.
     */
    @Test
    fun `survives having no chart at all`() {
        val render = state(chart = null, windowSeconds = 300.0).chartRender(null)
        assertNull(render.data)
        assertEquals("window 5m", render.viewInfo)
        assertEquals("", render.timeZoneLabel)
    }

    // --- the gap marking is a preference; detection is not ---------------------

    private fun reportWithAHole() = RecordGapReport(
        gaps = listOf(RecordGap(150.0, 250.0, GapStanding.Lost, expectedReadings = 100)),
        sweptAtMs = 1L,
    )

    @Test
    fun `hands the plot no gaps unless the marking is switched on`() {
        // Off is the default, and this is the one gate: both the screen and the
        // exported image draw from this render, so neither can mark a gap the
        // setting said not to.
        val render = state(gaps = reportWithAHole()).chartRender(null)
        assertTrue("gaps reached the plot with the marking off", render.gaps.isEmpty())
    }

    @Test
    fun `hands the plot the detected gaps once the marking is on`() {
        val report = reportWithAHole()
        val render = state(config = AppConfig(markGaps = true), gaps = report).chartRender(null)
        assertEquals(report.gaps, render.gaps)
    }

    @Test
    fun `the marking never touches what detection found`() {
        // The other half of the bargain, and the half that is not allowed to be a
        // preference: the report the Data tab reads is the same object whichever
        // way the switch points, so its account of what is missing, recoverable
        // or gone cannot be softened from the Display tab.
        val report = reportWithAHole()
        for (marking in listOf(false, true)) {
            val ui = state(config = AppConfig(markGaps = marking), gaps = report)
            ui.chartRender(null)
            assertEquals(report, ui.gaps)
            assertEquals(1, ui.gaps.lost)
            assertEquals(100, ui.gaps.readingsLost)
        }
    }
}

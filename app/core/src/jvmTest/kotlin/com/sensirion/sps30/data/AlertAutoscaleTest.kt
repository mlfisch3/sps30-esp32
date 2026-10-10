package com.sensirion.sps30.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the alert level takes part in the automatic y range is a setting,
 * and the setting is off by default.
 *
 * An alert is routinely set far above what clean air ever reads - 20 ug/m3
 * over a trace idling at 2 - and an autoscale obliged to reach it would spend
 * most of the plot on empty air with the data pressed into the bottom. So the
 * default fit answers to the data alone, and pulling the axis up to the alert
 * line is an explicit choice ("Autoscale includes alert level", on the Display
 * tab).
 *
 * The rules are exercised on ranges through [ChartView.resolveYRange], the way
 * [ChartYFloorTest] does: they are rules about ranges, not about readings. The
 * setting reaches the chart as [ChartView.alertY], which `MonitorSession`
 * refreshes from [AppConfig.autoscaleAlertLevel] before every compute - the
 * mapping from config to level is covered here too, as is the setting
 * surviving storage.
 */
class AlertAutoscaleTest {

    private fun rule(
        threshold: Double,
        enabled: Boolean = true,
        highlightPlot: Boolean = true,
    ) = AlertRule(
        id = "r$threshold",
        label = "test",
        threshold = threshold,
        enabled = enabled,
        highlightPlot = highlightPlot,
    )

    // --- the fit -------------------------------------------------------------

    @Test
    fun `off - an alert far above the data leaves the range alone`() {
        val plain = ChartView().resolveYRange(1.0, 5.0)
        val withAlert = ChartView().apply { alertY = null }.resolveYRange(1.0, 5.0)
        assertEquals("a null level must be a no-op", plain, withAlert)
        assertTrue(
            "the fit stretched to somewhere no data is: top ${plain.second}",
            plain.second < 40.0,
        )
    }

    @Test
    fun `on - the fitted range reaches the alert level`() {
        val view = ChartView().apply { alertY = 40.0 }
        val (lo, hi) = view.resolveYRange(1.0, 5.0)
        assertTrue("top $hi does not reach the alert at 40", hi >= 40.0)
        assertTrue("the bottom left the data: $lo", lo <= 1.0)
        assertTrue("the floor at zero still holds: $lo", lo >= 0.0)
    }

    @Test
    fun `on - a level below the data max changes nothing`() {
        val plain = ChartView().resolveYRange(1.0, 5.0)
        val below = ChartView().apply { alertY = 3.0 }.resolveYRange(1.0, 5.0)
        assertEquals("a level inside the data may only be reached, never fit to", plain, below)
    }

    @Test
    fun `on - with no data at all the level is still in view`() {
        val (_, hi) = ChartView().apply { alertY = 40.0 }.resolveYRange(null, null)
        assertTrue("empty plot ignored the level: top $hi", hi >= 40.0)
    }

    @Test
    fun `a non-finite level is refused, not fit to`() {
        val plain = ChartView().resolveYRange(1.0, 5.0)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val range = ChartView().apply { alertY = bad }.resolveYRange(1.0, 5.0)
            assertEquals("level $bad leaked into the fit", plain, range)
        }
    }

    // --- the paths that are not the fit --------------------------------------

    @Test
    fun `a manual range is the user's own numbers, level or no level`() {
        val view = ChartView().apply { alertY = 40.0 }
        view.setYRange(0.0, 10.0)
        val (lo, hi) = view.resolveYRange(1.0, 5.0)
        assertEquals(0.0, lo, 1e-9)
        assertEquals("the alert level moved a range the user typed", 10.0, hi, 1e-9)
    }

    @Test
    fun `a gesture override is not widened to the level`() {
        val view = ChartView().apply { alertY = 40.0 }
        view.resolveYRange(1.0, 5.0)
        // Two fingers ask for a range; the alert line off the top of it is
        // visible as absent, which is information, not an error to correct.
        view.zoomY(0.1, 1.0, 5.0)
        assertTrue("the gesture did not take", view.yOverridden())
        val (_, hi) = view.resolveYRange(1.0, 5.0)
        assertTrue("the override was stretched to the alert at 40: top $hi", hi < 40.0)
    }

    @Test
    fun `switching autoscale off freezes the range the level was part of`() {
        val view = ChartView().apply { alertY = 40.0 }
        val fitted = view.resolveYRange(1.0, 5.0)
        view.yAutoscale(false, 1.0, 5.0)
        assertEquals("the switch-off did not freeze what was drawn", fitted, view.resolveYRange(1.0, 5.0))
    }

    // --- config to level -----------------------------------------------------

    @Test
    fun `the level is null while the setting is off, whatever the rules say`() {
        val config = AppConfig(alertInAutoscale = false, alerts = listOf(rule(20.0)))
        assertNull(config.autoscaleAlertLevel)
    }

    @Test
    fun `the level is the lowest enabled rule that is drawn on the plot`() {
        val config = AppConfig(
            alertInAutoscale = true,
            alerts = listOf(
                rule(50.0),
                rule(20.0),
                rule(10.0, enabled = false),
                rule(5.0, highlightPlot = false),
            ),
        )
        assertEquals(
            "the level must match the line the plot draws - see ChartRender",
            20.0,
            config.autoscaleAlertLevel!!,
            1e-9,
        )
    }

    @Test
    fun `no drawable rule means no level, even with the setting on`() {
        val config = AppConfig(
            alertInAutoscale = true,
            alerts = listOf(rule(20.0, enabled = false)),
        )
        assertNull(config.autoscaleAlertLevel)
    }

    // --- storage -------------------------------------------------------------

    /** Byte-for-byte the `Json` in `com.sensirion.sps30.config.ConfigStore`. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `the setting survives a round trip through storage`() {
        for (on in listOf(true, false)) {
            val saved = AppConfig(alertInAutoscale = on)
            val loaded = json.decodeFromString<AppConfig>(json.encodeToString(saved)).normalized()
            assertEquals("alertInAutoscale=$on did not come back from storage", on, loaded.alertInAutoscale)
            assertEquals("the rest of the config changed on the way", saved, loaded)
        }
    }

    @Test
    fun `a config written before the setting existed loads with it off`() {
        val old = json.encodeToString(AppConfig()).replace(",\"alertInAutoscale\":false", "")
        assertTrue("the fixture failed to strip the field", "alertInAutoscale" !in old)
        val loaded = json.decodeFromString<AppConfig>(old).normalized()
        assertEquals("an old config must open with the fit answering to data alone",
            false, loaded.alertInAutoscale)
    }
}

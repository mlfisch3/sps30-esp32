package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tick-density control's arithmetic: the steps it cycles through are
 * derived from the window, not from a fixed global list.
 *
 * The request that created it: the captain was looking at a stretch where each
 * tick was six hours and wanted finer gradations over a ninety-minute event,
 * without opening options and without navigating away. So the first test is
 * literally that case, and the property the rest pin is relativity - from days
 * the steps are hours, from an hour they are minutes, always one rung of
 * [TimeTicks.LADDER] at a time from wherever the window's own interval sits.
 */
class TickDensityTest {

    /** A 2026 instant: [ChartView.sane] refuses ranges before this project
     *  existed, so a 1970-style `now` would clamp every window to nothing. */
    private val REAL_NOW = 1_786_000_000.0


    /** The captain's case: six-hourly ticks step to 3 h, 2 h, 1 h. */
    @Test
    fun `from six-hour ticks the finer steps are three two and one hours`() {
        // A 36 h window: ideal = 6 h exactly, so the base rung is 6 h.
        val span = 36.0 * 3600.0
        assertEquals(6L * TimeTicks.HOUR, TimeTicks.finerInterval(span, 0))
        assertEquals(3L * TimeTicks.HOUR, TimeTicks.finerInterval(span, 1))
        assertEquals(2L * TimeTicks.HOUR, TimeTicks.finerInterval(span, 2))
        assertEquals(1L * TimeTicks.HOUR, TimeTicks.finerInterval(span, 3))
    }

    /** From an hour-long window the useful steps are minutes, by construction. */
    @Test
    fun `from an hour window the finer steps are minutes`() {
        val span = 3600.0
        val base = TimeTicks.finerInterval(span, 0)
        assertEquals(10L * TimeTicks.MINUTE, base)
        assertEquals(5L * TimeTicks.MINUTE, TimeTicks.finerInterval(span, 1))
        assertEquals(2L * TimeTicks.MINUTE, TimeTicks.finerInterval(span, 2))
        assertEquals(1L * TimeTicks.MINUTE, TimeTicks.finerInterval(span, 3))
    }

    /** Zero notches is exactly the axis' own choice - the control at rest changes nothing. */
    @Test
    fun `zero notches is the window's own interval at every span`() {
        for (span in listOf(60.0, 3600.0, 86400.0, 5 * 86400.0, 90 * 86400.0)) {
            assertEquals(TimeTicks.interval(span), TimeTicks.finerInterval(span, 0))
        }
    }

    /** The walk clamps at the ladder's finest rung rather than running off it. */
    @Test
    fun `the finest rung is the floor`() {
        val span = 12.0 // a 12 s window: base rung 2 s
        assertEquals(2L, TimeTicks.finerInterval(span, 0))
        assertEquals(1L, TimeTicks.finerInterval(span, 1))
        assertEquals(1L, TimeTicks.finerInterval(span, 5))
    }

    /**
     * The cycle's length is only as long as the rungs it can actually reach,
     * so no two stops of the cycle draw the same axis - a press that changes
     * nothing teaches the user the control is broken.
     */
    @Test
    fun `density stops never exceed the reachable rungs`() {
        // 12 s window: the base rung is 2 s, one rung above the floor, so the
        // cycle is just the default and one finer stop.
        assertEquals(2, TimeTicks.densityStops(12.0))
        // A generous window has the full cycle.
        assertEquals(TimeTicks.DENSITY_MAX + 1, TimeTicks.densityStops(36.0 * 3600.0))
        // A window so short its base is the finest rung has nothing to offer.
        assertEquals(1, TimeTicks.densityStops(4.0))
    }

    /** The view's cycle: finer, finer, finer, back to the window's own. */
    @Test
    fun `cycling wraps back to the default`() {
        val view = ChartView(windowSeconds = 36.0 * 3600.0)
        val now = REAL_NOW
        assertEquals(0, view.tickDensity)
        view.cycleTickDensity(now)
        assertEquals(1, view.tickDensity)
        view.cycleTickDensity(now)
        view.cycleTickDensity(now)
        assertEquals(3, view.tickDensity)
        view.cycleTickDensity(now)
        assertEquals("the cycle must wrap back to the window's own spacing", 0, view.tickDensity)
    }

    /** What compute hands the renderer: the resolved step and the density, together. */
    @Test
    fun `compute carries the step its density produced`() {
        val history = TimeSeriesHistory()
        val view = ChartView(windowSeconds = 36.0 * 3600.0)
        val now = REAL_NOW
        val before = view.compute(history, now)
        assertEquals(6L * TimeTicks.HOUR, before.tickStepSeconds)
        assertEquals(0, before.tickDensity)

        view.cycleTickDensity(now)
        val after = view.compute(history, now)
        assertEquals(3L * TimeTicks.HOUR, after.tickStepSeconds)
        assertEquals(1, after.tickDensity)
        assertTrue(
            "a finer step must draw more ticks",
            after.xTicks.size > before.xTicks.size,
        )
    }

    /**
     * Density survives navigation and stays *relative*: the same one-rung-finer
     * preference lands on hours over days and on minutes over an hour, so the
     * control never pins minutes onto a month.
     */
    @Test
    fun `density is relative to whatever window comes next`() {
        val view = ChartView(windowSeconds = 36.0 * 3600.0)
        val now = REAL_NOW
        view.cycleTickDensity(now)
        assertEquals(3L * TimeTicks.HOUR, TimeTicks.finerInterval(36.0 * 3600.0, view.tickDensity))
        view.setWindow(3600.0)
        val history = TimeSeriesHistory()
        val data = view.compute(history, now)
        assertEquals(1, data.tickDensity)
        assertEquals(5L * TimeTicks.MINUTE, data.tickStepSeconds)
    }
}

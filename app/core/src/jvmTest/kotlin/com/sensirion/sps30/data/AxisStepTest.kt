package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of the plot a fitted axis is allowed to waste.
 *
 * Fitting the data is only half of "the curve should fill the plot". The other
 * half is snapping: the axis is rounded outward to round gridline steps so it
 * carries a labelled line top and bottom, and that rounding costs height. At a
 * whole-number step a range of 1.24 rounds out to 0..3 and the trace is left
 * occupying a third of the frame - the same thin band, arrived at from the other
 * direction.
 *
 * So the step steps down the 1-2-5 ladder until the snapped axis is within half
 * again of the range asked for. These are the cases that used to be worst:
 * indoor air, which sits at 1-3 ug/m3 and moves in tenths.
 */
class AxisStepTest {

    /** The ladder itself. Getting a rung wrong here shows up as odd axis labels. */
    @Test
    fun `the step ladder descends 1-2-5`() {
        assertEquals(5.0, ChartView.finerStep(10.0), 1e-9)
        assertEquals(2.0, ChartView.finerStep(5.0), 1e-9)
        assertEquals(1.0, ChartView.finerStep(2.0), 1e-9)
        assertEquals(0.5, ChartView.finerStep(1.0), 1e-9)
        assertEquals(0.2, ChartView.finerStep(0.5), 1e-9)
        assertEquals(0.1, ChartView.finerStep(0.2), 1e-9)
        assertEquals(0.05, ChartView.finerStep(0.1), 1e-9)
    }

    /**
     * Every fitted range keeps at least two thirds of the plot for the data.
     *
     * Swept across the concentrations this instrument actually reports rather
     * than argued from one example: the failure was never at one particular
     * range, it was wherever a range happened to straddle a round step.
     */
    @Test
    fun `snapping never costs more than half the range again`() {
        var worst = 0.0
        var worstAt = ""
        for (tenths in 1..2000) {
            val lo = tenths / 10.0
            for (spanTenths in 1..500) {
                val hi = lo + spanTenths / 10.0
                val (fitLo, fitHi) = ChartView.autoYRange(lo, hi)
                val span = fitHi - fitLo
                val step = ChartView.fittingStep(fitLo, fitHi)
                val bottom = Math.floor(fitLo / step) * step
                val top = Math.ceil(fitHi / step) * step
                val drawn = maxOf(top - bottom, step * 2)
                val inflation = drawn / span
                if (inflation > worst) {
                    worst = inflation
                    worstAt = "$lo..$hi (step $step -> $bottom..$top)"
                }
            }
        }
        // Two intervals are the floor - an axis with one gridline is not an
        // axis - so a very narrow range can still be doubled. Everything else
        // stays inside the target.
        assertTrue("snapping wasted too much of the plot at $worstAt", worst <= 2.0)
    }

    /** The case from the request: readings around 3, on an axis that fits them. */
    @Test
    fun `indoor air gets a step it can be read at`() {
        val (lo, hi) = ChartView.autoYRange(2.9, 3.1)
        val step = ChartView.fittingStep(lo, hi)
        assertTrue("a step of $step cannot resolve a range of 0.2", step <= 0.1)
        val bottom = Math.floor(lo / step) * step
        val top = Math.ceil(hi / step) * step
        assertTrue("the axis spans $bottom..$top for data of 2.9..3.1", top - bottom <= 0.5)
    }
}

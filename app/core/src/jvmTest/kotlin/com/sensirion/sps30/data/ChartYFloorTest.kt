package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zero is the bottom of the y axis, by every route there is to setting it.
 *
 * A particle concentration cannot be negative, so an axis reaching below zero
 * spends the plot's height on a region no reading can occupy, and implies the
 * instrument might report one. The user may still zoom *in* until the lowest
 * value on screen is well above zero - that is a different thing and has to keep
 * working.
 *
 * ## Why this is a file of its own rather than one more case
 *
 * The rule is not "the pinch clamps"; it is that **no path** puts the bound below
 * zero. There are six of them - the fit, the snap that rounds the fit outward,
 * the pinch, the drag, the range dialog, and the setting being switched off while
 * a gesture is in force - and each was written at a different time by someone
 * thinking about something else. Two of them already carried a clamp that only
 * applied "if the data was non-negative", which is a condition that reads as
 * caution and is really a hole. So each route is walked here by name, and
 * [everyRoute] is what a seventh has to be added to.
 */
class ChartYFloorTest {

    /** Assert on the range the axis will actually be drawn with. */
    private fun ChartView.drawn(dataMin: Double? = null, dataMax: Double? = null) =
        resolveYRange(dataMin, dataMax)

    private fun assertFloored(where: String, range: Pair<Double, Double>) {
        assertTrue(
            "$where produced a lower bound of ${range.first}",
            range.first >= 0.0,
        )
        assertTrue("$where produced an empty range: $range", range.second > range.first)
    }

    // --- route by route ------------------------------------------------------

    /** The fit, with data hugging zero: the margin below must not go under it. */
    @Test
    fun `the fit does not reach below zero`() {
        assertFloored("autoYRange at zero", ChartView.autoYRange(0.0, 3.0))
        assertFloored("autoYRange on a flat zero trace", ChartView.autoYRange(0.0, 0.0))
        assertFloored("autoYRange with nothing in view", ChartView.autoYRange(null, null))
    }

    /**
     * The fit again, handed a negative reading.
     *
     * The clamp used to be conditional on the data itself being non-negative, so
     * a single corrupt value below zero disabled it and took the axis down with
     * it. A reading the sensor cannot produce is not a reason to draw an axis
     * that says it can.
     */
    @Test
    fun `a negative reading does not drag the axis below zero`() {
        assertFloored("autoYRange with a negative minimum", ChartView.autoYRange(-4.0, 3.0))
        assertFloored("autoYRange wholly negative", ChartView.autoYRange(-9.0, -5.0))
    }

    /** The outward snap to round gridlines, which is its own way under zero. */
    @Test
    fun `snapping to round steps does not reach below zero`() {
        val snapped = ChartView.snapToSteps(0.2, 1.4, 1.0)
        assertFloored("snapToSteps", snapped)
        // The height the snap wanted is kept, it is just all above zero.
        assertEquals(0.0, snapped.first, 1e-9)
        assertTrue(snapped.second >= 1.4)
    }

    /** A pinch that opens the axis wider than the data sits above zero. */
    @Test
    fun `pinching the axis open stops at zero`() {
        val view = ChartView()
        repeat(6) { view.zoomY(4.0, 2.9, 3.1) }
        assertFloored("a pinch on the value axis", view.drawn(2.9, 3.1))
    }

    /**
     * And the pinch still delivers the height it asked for.
     *
     * Clamping the bottom alone would have cut every zoom-out short near zero,
     * so each successive pinch would widen the axis less than the last for no
     * reason the user could see. The range slides up instead, keeping its span.
     */
    @Test
    fun `a pinch at the floor keeps the height it asked for`() {
        val view = ChartView()
        view.setYRange(0.0, 10.0)
        view.zoomY(2.0, null, null)
        assertEquals(0.0, view.yMin!!, 1e-9)
        assertEquals("the span the fingers asked for", 20.0, view.yMax!! - view.yMin!!, 1e-9)
    }

    /** A drag downwards runs the range into the floor and stops there. */
    @Test
    fun `dragging the axis past zero stops at zero`() {
        val view = ChartView()
        view.setYRange(2.0, 12.0)
        view.panY(-1.0) // shift the range down by a whole plot height
        assertEquals(0.0, view.yMin!!, 1e-9)
        // A pan is a translation, so it keeps its span: the plot scrolls to the
        // end of the axis rather than silently rescaling under the finger.
        assertEquals(10.0, view.yMax!! - view.yMin!!, 1e-9)
    }

    /** Repeated drags cannot walk it under, either. */
    @Test
    fun `no amount of dragging gets below zero`() {
        val view = ChartView()
        repeat(30) { view.panY(-0.4, 2.9, 3.1) }
        assertFloored("thirty drags downwards", view.drawn(2.9, 3.1))
    }

    /**
     * The range dialog. A stated range keeps the top it states - the user typed
     * both numbers and only the impossible one should move.
     */
    @Test
    fun `a range set by hand has its bottom lifted to zero`() {
        val view = ChartView()
        view.setYRange(-5.0, 20.0)
        assertEquals(0.0, view.yMin!!, 1e-9)
        assertEquals("the stated top must be honoured", 20.0, view.yMax!!, 1e-9)
        assertFloored("setYRange with a negative bottom", view.drawn(1.0, 2.0))
    }

    /** Switching autoscale off adopts what is on screen, floor included. */
    @Test
    fun `switching autoscale off cannot adopt a negative bottom`() {
        val view = ChartView()
        repeat(4) { view.zoomY(4.0, 2.9, 3.1) }
        view.resolveYRange(2.9, 3.1)
        view.yAutoscale(false, 2.9, 3.1)
        assertTrue("adopted ${view.yMin}", view.yMin!! >= 0.0)
    }

    /** Data below an overridden bottom widens it - down to zero and no further. */
    @Test
    fun `widening for a low reading stops at zero`() {
        val view = ChartView()
        view.panY(-0.5, 20.0, 22.0)
        view.zoomY(2.0, 20.0, 22.0)
        assertFloored("widening for a reading at zero", view.drawn(0.0, 22.0))
    }

    // --- the sweep -----------------------------------------------------------

    /**
     * Every route, driven hard, from a range that starts near the floor.
     *
     * The named cases above say which rule each route follows; this says that
     * none of them, in any order, ever gets underneath. A route added later that
     * does not clamp shows up here without anyone having to remember to add a
     * case for it - provided it is added to this list, which is the one thing
     * this test asks of whoever writes it.
     */
    @Test
    fun everyRoute() {
        val moves: List<Pair<String, (ChartView) -> Unit>> = listOf(
            "zoom out" to { v -> v.zoomY(3.0, 0.1, 0.4) },
            "zoom in" to { v -> v.zoomY(0.3, 0.1, 0.4) },
            "drag down" to { v -> v.panY(-0.7, 0.1, 0.4) },
            "drag up" to { v -> v.panY(0.7, 0.1, 0.4) },
            "set by hand" to { v -> v.setYRange(-2.0, 6.0) },
            "autoscale off" to { v -> v.yAutoscale(false, 0.1, 0.4) },
            "autoscale on" to { v -> v.yAutoscale(true) },
            "release" to { v -> v.releaseYOverride() },
            "lock" to { v -> v.setYLocked(true) },
        )
        for ((nameA, a) in moves) {
            for ((nameB, b) in moves) {
                val view = ChartView()
                a(view)
                assertFloored("$nameA", view.drawn(0.1, 0.4))
                b(view)
                assertFloored("$nameA then $nameB", view.drawn(0.1, 0.4))
                // And with a reading that arrives at the very bottom.
                assertFloored("$nameA then $nameB, reading at zero", view.drawn(0.0, 0.4))
            }
        }
    }

    /** Zooming *in* above zero still works - the floor is a floor, not a magnet. */
    @Test
    fun `zooming in keeps a bottom well above zero`() {
        val view = ChartView()
        view.setYRange(0.0, 100.0)
        view.panY(0.5)            // move the range up, away from the floor
        repeat(3) { view.zoomY(0.5, 48.0, 52.0) }
        assertTrue("the axis was pulled back to zero", view.yMin!! > 10.0)
        assertTrue(view.yMax!! > view.yMin!!)
    }
}

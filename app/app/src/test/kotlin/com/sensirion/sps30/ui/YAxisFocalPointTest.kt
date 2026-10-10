package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three small rules that let two fingers both shift and scale the y axis.
 *
 * [ChartYAnchorTest] settles what an anchor does to a range and
 * [YAxisShiftGestureTest] settles that the chart reaches for the right callback
 * with real pointers. These are the pieces between them - which point a pinch
 * scales about, how a canvas pixel becomes a fraction of the range, and where
 * the line between a drag and a pinch falls - and they are here rather than in
 * the Robolectric tests because they are arithmetic, and arithmetic should not
 * cost a simulated Android to check.
 */
class YAxisFocalPointTest {

    private fun spreads(vararg ys: Float) = spreadOf(ys)

    /** Two fingers stacked about [centre], [gap] px apart, as the chart sees them. */
    private fun PinchArbiter.stackedFrame(centre: Float, from: Float, to: Float): Float =
        scale(
            previousX = 2f,
            previousY = spreads(centre - from / 2f, centre + from / 2f),
            currentX = 2f,
            currentY = spreads(centre - to / 2f, centre + to / 2f),
            centreY = centre,
        )

    // --- the point a pinch is made about -------------------------------------

    /** Nothing is claimed before the pinch has a direction to claim it for. */
    @Test
    fun `a pinch with no spread yet has no focal point`() {
        val pinch = PinchArbiter()
        pinch.scale(1f, 1f, 1f, 1f, centreY = 500f)

        assertNull(pinch.axis)
        assertNull("a pinch that claimed no axis claimed a focal point", pinch.focusY)
    }

    /** And once it has, the focal point is where the fingers were centred. */
    @Test
    fun `the focal point is where the fingers were when the axis was settled`() {
        val pinch = PinchArbiter()
        pinch.stackedFrame(centre = 620f, from = 400f, to = 300f)

        assertEquals(PinchAxis.VALUE, pinch.axis)
        assertEquals(620f, pinch.focusY!!, 1e-6f)
    }

    /**
     * It is settled once and held, on the same reasoning as the axis itself.
     *
     * A pinch is never two fingers moving in perfect opposition, so its centre
     * wanders - and scales about two different points compose into a scale *and*
     * a shift, which would slide the range while the user was only setting its
     * height. Taking the focal point afresh each frame is that fault exactly, so
     * the first frame's answer is the gesture's answer.
     */
    @Test
    fun `the focal point does not move with the fingers`() {
        val pinch = PinchArbiter()
        pinch.stackedFrame(centre = 620f, from = 400f, to = 300f)
        // The hand slides a long way up the plot while it goes on squeezing.
        pinch.stackedFrame(centre = 300f, from = 300f, to = 200f)
        pinch.stackedFrame(centre = 120f, from = 200f, to = 120f)

        assertEquals("the pinch changed what it was scaling about", 620f, pinch.focusY!!, 1e-6f)
    }

    // --- canvas pixels into a fraction of the range --------------------------

    /**
     * The y axis runs the other way from canvas pixels, and this is the one
     * place that flip is written down: the bottom of the plot is 0, the top is 1.
     */
    @Test
    fun `the bottom of the plot is the bottom of the range`() {
        assertEquals(0.0, anchorFraction(720f, plotTop = 20f, plotBottom = 720f), 1e-9)
        assertEquals(1.0, anchorFraction(20f, plotTop = 20f, plotBottom = 720f), 1e-9)
        assertEquals(0.5, anchorFraction(370f, plotTop = 20f, plotBottom = 720f), 1e-9)
    }

    /**
     * Fingers that stray into the margins are a hand slightly off target, not a
     * gesture to refuse: they scale about the end they are nearest to.
     */
    @Test
    fun `fingers off the ends of the plot anchor on the nearest end`() {
        assertEquals(0.0, anchorFraction(900f, plotTop = 20f, plotBottom = 720f), 1e-9)
        assertEquals(1.0, anchorFraction(-40f, plotTop = 20f, plotBottom = 720f), 1e-9)
    }

    /** A plot with no height cannot divide by it, and must still answer. */
    @Test
    fun `a plot of no height still gives an anchor`() {
        val anchor = anchorFraction(20f, plotTop = 20f, plotBottom = 20f)
        assertTrue("anchor out of range: $anchor", anchor in 0.0..1.0)
    }

    // --- where a drag stops and a pinch starts -------------------------------

    /**
     * The deadzone, which is now what keeps the two gestures out of each other's
     * way. Fingers held at a fixed distance and moved together report a spread
     * ratio of very nearly 1; a real pinch reports one that is plainly not.
     */
    @Test
    fun `a drag's incidental spread is not a scale`() {
        assertFalse("a perfectly parallel drag", isScaling(1f))
        assertFalse("a hand wobbling by half a percent", isScaling(1.005f))
        assertFalse("and the other way", isScaling(0.995f))
    }

    @Test
    fun `a deliberate pinch is a scale`() {
        assertTrue("fingers closing by a tenth", isScaling(0.9f))
        assertTrue("fingers opening by a tenth", isScaling(1.1f))
    }

    /**
     * A ratio of zero or below is not a small scale, it is a frame with nothing
     * in it - two fingers landing on the same pixel, or a pointer already gone.
     */
    @Test
    fun `a ratio that means nothing is not a scale`() {
        assertFalse(isScaling(0f))
        assertFalse(isScaling(-1f))
    }

    // --- where a sideways drag stops and a vertical one starts ---------------

    /** A deliberate drag up or down the plot arms almost at once. */
    @Test
    fun `a drag down the plot arms the shift`() {
        val latch = YDragLatch(slopPx = 15f)
        assertFalse("armed before it had gone anywhere", latch.travelled(0f, 4f))
        latch.travelled(0f, 8f)
        assertTrue("a 20px drag straight down did not arm the shift", latch.travelled(0f, 8f))
    }

    /**
     * And a scroll through time does not, however far it runs. The hand drifts
     * down the glass as it travels sideways, and acting on that drift would take
     * the value axis off autoscale on a gesture that was only about time.
     */
    @Test
    fun `a sideways scroll never arms the shift, however long`() {
        val latch = YDragLatch(slopPx = 15f)
        // 800px sideways with 60px of drift down it - far more wander than a
        // hand produces, and still plainly a sideways drag.
        repeat(16) { latch.travelled(50f, 3.75f) }
        assertFalse("a sideways scroll shifted the y axis", latch.armed)
    }

    /**
     * Settled once and held: a drag that has declared itself vertical stays
     * vertical, or a gesture near the diagonal would flicker in and out of
     * shifting the axis several times a second and the range would judder.
     */
    @Test
    fun `an armed drag stays armed when the hand turns sideways`() {
        val latch = YDragLatch(slopPx = 15f)
        repeat(4) { latch.travelled(0f, 10f) }
        assertTrue(latch.armed)

        repeat(20) { latch.travelled(50f, 0f) }

        assertTrue("the drag stopped shifting the axis part-way through", latch.armed)
    }

    /**
     * A tie goes to sideways, which is what two fingers on this plot have always
     * done: an existing habit is never reinterpreted by a rounding accident.
     */
    @Test
    fun `a drag at 45 degrees is left as the sideways one it always was`() {
        val latch = YDragLatch(slopPx = 15f)
        repeat(10) { latch.travelled(20f, 20f) }
        assertFalse("a diagonal drag claimed the y axis", latch.armed)
    }
}

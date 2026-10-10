package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.YAxisControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setting the y axis with two fingers on the plot.
 *
 * The axis could already be overridden - the model has carried that since the
 * override was written - but only from the narrow gutter the tick values sit in,
 * which is a strip of the chart nobody finds by accident and a gesture nobody
 * guesses. On the plot itself, where the hand actually goes, a pinch meant time
 * and only time. So from where the user stands the feature did not exist.
 *
 * These tests run the pinch the way the chart does: real finger coordinates
 * through [spreadOf] and [PinchArbiter], and the factor that falls out of them
 * into a real [ChartView]. The arithmetic under test is the shipping arithmetic;
 * only the extraction of coordinates from a `PointerEvent` is left out, because
 * that is the one part with nothing in it to get wrong.
 */
class PinchYAxisTest {

    /** Two fingers, as canvas coordinates: ((x0, y0), (x1, y1)). */
    private data class Fingers(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
    ) {
        val xs get() = floatArrayOf(x0, x1)
        val ys get() = floatArrayOf(y0, y1)
    }

    /**
     * One frame of a pinch: hand the arbiter the spreads before and after, and
     * get back the factor the chart would apply.
     */
    private fun PinchArbiter.frame(before: Fingers, after: Fingers): Float =
        scale(spreadOf(before.xs), spreadOf(before.ys), spreadOf(after.xs), spreadOf(after.ys))

    /** What [PmChart] passes to `zoomY`/`zoom`: spreading the fingers zooms in. */
    private fun factorOf(zoom: Float): Double = 1.0 / zoom.toDouble()

    /** Fingers one above the other in the middle of the plot, [gap] px apart. */
    private fun stacked(gap: Float) = Fingers(600f, 400f - gap / 2f, 604f, 400f + gap / 2f)

    /** Fingers side by side, [gap] px apart. */
    private fun level(gap: Float) = Fingers(600f - gap / 2f, 400f, 600f + gap / 2f, 402f)

    // --- which axis a pinch is -----------------------------------------------

    @Test
    fun `fingers one above the other are pinching the y axis`() {
        val pinch = PinchArbiter()
        pinch.frame(stacked(400f), stacked(200f))
        assertEquals(PinchAxis.VALUE, pinch.axis)
    }

    @Test
    fun `fingers side by side still pinch time`() {
        val pinch = PinchArbiter()
        pinch.frame(level(400f), level(200f))
        assertEquals(PinchAxis.TIME, pinch.axis)
    }

    /**
     * A pinch at 45 degrees is not a new gesture, it is a sloppy old one - and
     * the old one scaled time. Anything else would reinterpret an existing habit
     * on a rounding accident.
     */
    @Test
    fun `a pinch on the diagonal is read as time`() {
        val pinch = PinchArbiter()
        val before = Fingers(500f, 300f, 700f, 500f)
        val after = Fingers(550f, 350f, 650f, 450f)
        pinch.frame(before, after)
        assertEquals(PinchAxis.TIME, pinch.axis)
    }

    /**
     * The reason the axis is decided once rather than every frame. A vertical
     * pinch whose fingers happen to pass through a level arrangement on the way
     * must not hand the gesture to the time axis halfway through - the chart
     * would shudder in both directions at once.
     */
    @Test
    fun `the axis is settled once and survives the fingers moving`() {
        val pinch = PinchArbiter()
        pinch.frame(stacked(400f), stacked(300f))
        assertEquals(PinchAxis.VALUE, pinch.axis)

        // The hand rotates until the fingers are level and then past it.
        pinch.frame(stacked(300f), level(300f))
        pinch.frame(level(300f), level(600f))

        assertEquals("a pinch may not change axis mid-gesture", PinchAxis.VALUE, pinch.axis)
    }

    /** Two fingers landing on the same spot say nothing about direction yet. */
    @Test
    fun `a pinch with no spread yet claims no axis and scales nothing`() {
        val pinch = PinchArbiter()
        val together = Fingers(600f, 400f, 601f, 400f)
        assertEquals(1f, pinch.frame(together, together), 1e-6f)
        assertNull(pinch.axis)
    }

    /**
     * Stacked fingers report a horizontal spread of a pixel or two, and a ratio
     * built on that is division by noise: 2 px against 1 px is a doubling of the
     * time axis that nobody's hand asked for.
     */
    @Test
    fun `a spread too small to mean anything does not become a scale factor`() {
        val pinch = PinchArbiter()
        // Deliberately settled on TIME first, then given a frame whose horizontal
        // spread has collapsed to nothing.
        pinch.frame(level(400f), level(300f))
        assertEquals(PinchAxis.TIME, pinch.axis)

        val factor = pinch.frame(Fingers(600f, 300f, 601f, 500f), Fingers(600f, 300f, 602f, 500f))

        assertEquals(1f, factor, 1e-6f)
    }

    // --- what the pinch does to the axis -------------------------------------

    /**
     * The captain's sense, verified end to end: squeezing the fingers together
     * vertically zooms the y axis *out*, giving a taller range with the curves
     * squashed into it.
     */
    @Test
    fun `squeezing vertically gives a taller range`() {
        val view = ChartView()
        val fitted = view.resolveYRange(2.9, 3.1)

        val pinch = PinchArbiter()
        val zoom = pinch.frame(stacked(400f), stacked(200f))
        view.zoomY(factorOf(zoom), 2.9, 3.1)

        val after = view.resolveYRange(2.9, 3.1)
        assertEquals(PinchAxis.VALUE, pinch.axis)
        assertTrue(
            "squeezing must widen the range: was ${span(fitted)}, now ${span(after)}",
            span(after) > span(fitted),
        )
    }

    /** And spreading them zooms in, on the same range. */
    @Test
    fun `spreading vertically gives a shorter range`() {
        val view = ChartView()
        val fitted = view.resolveYRange(2.9, 3.1)

        val pinch = PinchArbiter()
        val zoom = pinch.frame(stacked(200f), stacked(400f))
        view.zoomY(factorOf(zoom), 2.9, 3.1)

        val after = view.resolveYRange(2.9, 3.1)
        assertTrue(
            "spreading must narrow the range: was ${span(fitted)}, now ${span(after)}",
            span(after) < span(fitted),
        )
    }

    /**
     * Item 3 of the specification. The pinch puts the chart into the override
     * state, and the override lives *inside* autoscale-on: the setting is not
     * switched off, and nothing the user does with their fingers switches it off.
     */
    @Test
    fun `a pinch enters the override without switching autoscale off`() {
        val view = ChartView()
        assertEquals(YAxisControl.FITTED, view.yControl())

        view.zoomY(pinchFactor(400f, 150f), 2.9, 3.1)

        assertTrue("autoscale must still be the setting", view.yAuto)
        assertTrue(view.yOverridden())
        assertEquals(YAxisControl.OVERRIDDEN, view.yControl())
    }

    /**
     * Item 4. The bounds stay where the fingers left them, frame after frame,
     * with autoscale still on and the data sitting well inside them.
     */
    @Test
    fun `the pinched bounds are not pulled back by autoscale`() {
        val view = ChartView()
        view.zoomY(pinchFactor(400f, 40f), 2.9, 3.1)
        val chosen = view.resolveYRange(2.9, 3.1)
        assertTrue("this test needs a range far wider than the data", span(chosen) > 2.0)

        // Thirty seconds of readings arriving, all of them inside the range.
        repeat(30) { view.resolveYRange(2.92, 3.08) }

        assertEquals(chosen, view.resolveYRange(2.9, 3.1))
        assertEquals(YAxisControl.OVERRIDDEN, view.yControl())
    }

    /**
     * Item 5, the single exception: a reading above the top the user chose grows
     * the axis to fit it. Data going off the top of the chart is not acceptable,
     * so the axis heightens - and only the top moves, because re-fitting would
     * drag the bottom up and throw away the roomy axis the pinch existed to make.
     */
    @Test
    fun `a reading above the pinched top raises the top`() {
        val view = ChartView()
        view.zoomY(pinchFactor(400f, 100f), 2.9, 3.1)
        val before = view.resolveYRange(2.9, 3.1)

        val after = view.resolveYRange(2.9, before.second + 25.0)

        assertTrue("the reading must be on the chart", after.second >= before.second + 25.0)
        assertEquals("the bottom must stay where it was put", before.first, after.first, 1e-9)
        assertEquals(YAxisControl.OVERRIDDEN, view.yControl())
    }

    /**
     * The other end, which the specification did not name. Growing downward too
     * is the same rule read consistently - a reading below the bottom is off the
     * chart exactly as one above the top is - and it costs nothing, because each
     * end still moves outwards on its own or not at all.
     */
    @Test
    fun `a reading below the pinched bottom lowers the bottom`() {
        val view = ChartView()
        // Push the range up off zero first, so there is a bottom to lower.
        view.panY(-0.6, 20.0, 22.0)
        view.zoomY(pinchFactor(400f, 300f), 20.0, 22.0)
        val before = view.resolveYRange(20.0, 22.0)
        assertTrue("this test needs a bottom above zero", before.first > 1.0)

        val after = view.resolveYRange(before.first - 5.0, 22.0)

        assertTrue("the reading must be on the chart", after.first <= before.first - 5.0)
        assertEquals("the top must stay where it was put", before.second, after.second, 1e-9)
    }

    /**
     * Item 6. Release is the way out, and what it hands back is autoscale proper:
     * afterwards the axis is indistinguishable from one no finger ever touched.
     */
    @Test
    fun `release hands the axis back to autoscale`() {
        val untouched = ChartView().resolveYRange(2.9, 3.1)

        val view = ChartView()
        view.zoomY(pinchFactor(400f, 100f), 2.9, 3.1)
        assertTrue("the pinch changed nothing", view.resolveYRange(2.9, 3.1) != untouched)

        view.releaseYOverride()

        assertEquals(YAxisControl.FITTED, view.yControl())
        assertFalse(view.yOverridden())
        assertTrue(view.yAuto)
        assertEquals(untouched, view.resolveYRange(2.9, 3.1))
    }

    /**
     * And the axis released is the *fitted* one, not the grown-out range the
     * override was left holding - a release that handed back a range widened by
     * an earlier spike would look like nothing had happened.
     */
    @Test
    fun `release after the axis grew still fits the data`() {
        val view = ChartView()
        view.zoomY(pinchFactor(400f, 100f), 2.9, 3.1)
        view.resolveYRange(2.9, 90.0) // a spike grows the top

        view.releaseYOverride()

        assertEquals(ChartView().resolveYRange(2.9, 3.1), view.resolveYRange(2.9, 3.1))
    }

    /**
     * A level pinch still has to be a working time zoom. The chart routes on
     * [PinchArbiter.axis] alone - TIME to `onZoom`, VALUE to `zoomY` - so an
     * axis of TIME with a live factor is the whole of "time still works", and
     * the y axis is untouched by construction.
     */
    @Test
    fun `a level pinch is still a time zoom`() {
        val pinch = PinchArbiter()
        val zoom = pinch.frame(level(400f), level(200f))

        assertEquals(PinchAxis.TIME, pinch.axis)
        assertEquals("halving the gap halves the span", 0.5f, zoom, 1e-6f)
    }

    /** The factor the chart applies for a vertical pinch from [from] px to [to] px. */
    private fun pinchFactor(from: Float, to: Float): Double {
        val pinch = PinchArbiter()
        val zoom = pinch.frame(stacked(from), stacked(to))
        check(pinch.axis == PinchAxis.VALUE) { "this helper is for vertical pinches" }
        return factorOf(zoom)
    }

    private fun span(range: Pair<Double, Double>) = range.second - range.first
}

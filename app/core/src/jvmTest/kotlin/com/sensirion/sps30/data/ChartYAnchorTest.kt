package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pinch on the y axis holds the point it is made about, not the middle of the
 * view.
 *
 * ## The complaint
 *
 * Scaling about the centre moves both bounds by the same amount in opposite
 * directions, so there is no gesture that holds one end still. Autoscaling gave
 * the captain 8 to 10; he wanted 8 to 15 - the same bottom, more room above -
 * and there was no way to ask for it. Every factor that raised the top lowered
 * the bottom by as much, and chasing it with a drag afterwards only trades one
 * bound for the other.
 *
 * ## What replaces it
 *
 * The value under the middle of the fingers stays where it is on the glass, and
 * the range grows or shrinks away from it. That makes the anchor the control:
 * fingers at the bottom of the plot hold the bottom and move the top, fingers at
 * the top do the reverse, and fingers in the middle behave exactly as this
 * always has - so nothing anybody had got used to is reinterpreted.
 *
 * The anchor arrives as a fraction of the range measured from its **bottom**,
 * because the model has no pixels and the renderer has no ug/m3; [PmChart]'s
 * `anchorFraction` is the one place the flip between the two is written down.
 *
 * The floor is checked here too, and separately from [ChartYFloorTest], because
 * the anchor is a new way to reach it: an anchor near the top of the range is
 * exactly what drives the bottom below zero while the user is looking at the
 * other end.
 */
class ChartYAnchorTest {

    /** A view already holding a range by hand, so a gesture edits that range. */
    private fun heldAt(lo: Double, hi: Double) = ChartView().apply { setYRange(lo, hi) }

    private fun ChartView.range() = resolveYRange(null, null)

    // --- the missing control -------------------------------------------------

    /**
     * The captain's own example, end to end: 8 to 10, wanted as 8 to 15, with
     * the fingers at the bottom of the plot.
     *
     * Squeezing them together widens the range - the sense the time axis has
     * always had - and with the anchor at the bottom the whole of that widening
     * goes above.
     */
    @Test
    fun `pinching about the bottom raises only the top`() {
        val view = heldAt(8.0, 10.0)

        view.zoomY(factor = 3.5, anchor = 0.0)

        val (lo, hi) = view.range()
        assertEquals("the bottom must not have moved", 8.0, lo, 1e-9)
        assertEquals("the top carries the whole of the change", 15.0, hi, 1e-9)
    }

    /** And the mirror of it: fingers at the top hold the top. */
    @Test
    fun `pinching about the top lowers only the bottom`() {
        val view = heldAt(8.0, 10.0)

        view.zoomY(factor = 3.5, anchor = 1.0)

        val (lo, hi) = view.range()
        assertEquals("the top must not have moved", 10.0, hi, 1e-9)
        assertEquals("the bottom carries the whole of the change", 3.0, lo, 1e-9)
    }

    /**
     * Narrowing works the same way round, which is the half a "make it bigger"
     * feature quietly forgets: spreading the fingers at the bottom of the plot
     * has to leave the bottom where it is and bring the top down.
     */
    @Test
    fun `spreading about the bottom brings the top down and leaves the bottom`() {
        val view = heldAt(8.0, 16.0)

        view.zoomY(factor = 0.25, anchor = 0.0)

        val (lo, hi) = view.range()
        assertEquals(8.0, lo, 1e-9)
        assertEquals(10.0, hi, 1e-9)
    }

    /**
     * The habit that already exists is not reinterpreted: a pinch centred on the
     * middle of the plot still moves both ends by as much, exactly as it did
     * before there was an anchor at all.
     */
    @Test
    fun `pinching about the middle is what it always was`() {
        val anchored = heldAt(8.0, 10.0).apply { zoomY(factor = 3.0, anchor = 0.5) }
        val defaulted = heldAt(8.0, 10.0).apply { zoomY(factor = 3.0) }

        assertEquals(6.0 to 12.0, anchored.range())
        assertEquals(
            "the default anchor must be the centre, or every old call changes meaning",
            anchored.range(), defaulted.range(),
        )
    }

    /**
     * The value under the fingers is what is held, not a bound - so an anchor
     * part-way up holds a value part-way up, and both bounds move by different
     * amounts.
     */
    @Test
    fun `the value under the fingers is the one that stays put`() {
        val view = heldAt(10.0, 20.0)
        // A quarter of the way up the range is 12.5.
        view.zoomY(factor = 4.0, anchor = 0.25)

        val (lo, hi) = view.range()
        val stillAt = lo + (hi - lo) * 0.25
        assertEquals("the anchored value moved", 12.5, stillAt, 1e-9)
        assertEquals(40.0, hi - lo, 1e-9)
    }

    /**
     * A focal point off the ends of the range is a hand that has strayed into
     * the margins, not a reason to refuse the gesture: it scales about the end
     * it is nearest to.
     */
    @Test
    fun `an anchor outside the plot is pulled back to the end nearest it`() {
        val below = heldAt(8.0, 10.0).apply { zoomY(factor = 3.5, anchor = -2.0) }
        val above = heldAt(8.0, 10.0).apply { zoomY(factor = 3.5, anchor = 4.0) }

        assertEquals(8.0 to 15.0, below.range())
        assertEquals(3.0 to 10.0, above.range())
    }

    // --- the floor, reached by the new route ---------------------------------

    /**
     * An anchor at the top is the way a widening pinch drives the bottom under
     * zero. The span the gesture asked for is kept and the whole of it slides up,
     * which is what every other gesture route does - see [ChartYFloorTest].
     */
    @Test
    fun `widening about the top cannot take the bottom below zero`() {
        val view = heldAt(2.0, 4.0)

        view.zoomY(factor = 10.0, anchor = 1.0)

        val (lo, hi) = view.range()
        assertEquals("the floor must hold", 0.0, lo, 1e-9)
        assertEquals("and the span the gesture asked for is kept", 20.0, hi - lo, 1e-9)
    }

    /** The floor holds at every anchor, not just the one that was thought of. */
    @Test
    fun `the floor holds whatever the anchor is`() {
        for (tenth in 0..10) {
            val anchor = tenth / 10.0
            val view = heldAt(0.5, 1.5)
            repeat(6) { view.zoomY(factor = 4.0, anchor = anchor) }
            val (lo, hi) = view.range()
            assertTrue("anchor $anchor produced a lower bound of $lo", lo >= 0.0)
            assertTrue("anchor $anchor produced an empty range: $lo..$hi", hi > lo)
        }
    }

    /** And under a shift, at every distance, in both directions. */
    @Test
    fun `the floor holds however far the range is dragged down`() {
        val view = heldAt(0.5, 1.5)
        repeat(20) { view.panY(-0.7) }
        val (lo, hi) = view.range()
        assertEquals(0.0, lo, 1e-9)
        assertTrue("the span must survive being dragged into the floor", hi > lo)
    }

    // --- the two gestures do not do each other's work ------------------------

    /**
     * A shift moves the range and does not resize it, however far it goes and
     * whatever it runs into.
     */
    @Test
    fun `a shift never rescales`() {
        val view = heldAt(10.0, 14.0)
        val span = view.range().let { it.second - it.first }

        view.panY(-0.25)
        view.panY(-0.25)
        view.panY(0.5)

        val (lo, hi) = view.range()
        assertEquals("a shift changed the height of the range", span, hi - lo, 1e-9)
        assertEquals("and it came back to where it started", 10.0, lo, 1e-9)
    }

    /**
     * A scale resizes the range and does not slide it: the anchored value is
     * exactly where it was, however many frames the pinch took to get there.
     *
     * Scales about a common point compose by plain multiplication, which is what
     * lets [MonitorSession] coalesce a gesture's frames into one factor - so a
     * pinch delivered in twenty steps has to land where the same pinch delivered
     * in one does.
     */
    @Test
    fun `a scale never shifts the anchored value`() {
        val stepwise = heldAt(8.0, 10.0)
        repeat(20) { stepwise.zoomY(factor = 1.1, anchor = 0.0) }

        val atOnce = heldAt(8.0, 10.0)
        atOnce.zoomY(factor = Math.pow(1.1, 20.0), anchor = 0.0)

        assertEquals("the anchored bottom drifted", 8.0, stepwise.range().first, 1e-9)
        assertEquals(atOnce.range().first, stepwise.range().first, 1e-9)
        assertEquals(atOnce.range().second, stepwise.range().second, 1e-6)
    }

    // --- the override is the same one -----------------------------------------

    /**
     * Both gestures land in the temporary override, not in a second state of
     * their own, and Release hands the axis back from either.
     */
    @Test
    fun `shifting and scaling share the one override, and one Release`() {
        for ((name, gesture) in listOf<Pair<String, (ChartView) -> Unit>>(
            "a shift" to { v -> v.panY(-0.3, 2.9, 3.1) },
            "a scale" to { v -> v.zoomY(4.0, 2.9, 3.1, anchor = 0.0) },
        )) {
            val view = ChartView()
            view.resolveYRange(2.9, 3.1)
            gesture(view)

            assertEquals("$name did not enter the override", YAxisControl.OVERRIDDEN, view.yControl())
            assertTrue("$name switched autoscale off", view.yAuto)

            view.releaseYOverride()
            assertEquals("$name survived Release", YAxisControl.FITTED, view.yControl())
        }
    }

    /** And the hold stops both of them, which is the whole of what it is for. */
    @Test
    fun `a locked axis ignores both gestures`() {
        val view = ChartView()
        view.resolveYRange(2.9, 3.1)
        view.zoomY(4.0, 2.9, 3.1, anchor = 0.0)
        view.setYLocked(true)
        val held = view.resolveYRange(2.9, 3.1)

        view.panY(-0.5, 2.9, 3.1)
        view.zoomY(3.0, 2.9, 3.1, anchor = 0.0)

        assertEquals(held, view.resolveYRange(2.9, 3.1))
    }
}

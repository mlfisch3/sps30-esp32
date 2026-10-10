package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the x axis' hold is allowed to do.
 *
 * The y axis has had a hold for a while: a gesture takes temporary control of the
 * range, Lock pins it against further gestures, Release hands it back. The time
 * axis now has the same thing for the same reason - a stretch of time framed by
 * hand is work, and until now the only thing defending it was that no incoming
 * reading moves a frozen range, which covers the sensor and not the hand.
 *
 * Written against [ChartView] directly, because these are rules about state
 * transitions rather than about readings. The one thing that would be invisible
 * from the rules alone - a lock outliving the range it holds, leaving a plot that
 * refuses every gesture with no control on screen offering to undo it - is what
 * most of the second half is about.
 */
class ChartXAxisTest {

    private val now = 1_785_000_000.0

    /** A view with a range held by hand, which is the only state the hold exists in. */
    private fun heldView(): ChartView {
        val view = ChartView(windowSeconds = 300.0)
        view.pan(-600.0, now) // drag the plot sideways: the range freezes where it lands
        assertEquals(XAxisControl.HELD, view.xControl())
        return view
    }

    // --- the hold itself -----------------------------------------------------

    /** A live view holds nothing, so it offers nothing. */
    @Test
    fun `a live view is following, not holding`() {
        val view = ChartView(windowSeconds = 300.0)
        assertEquals(XAxisControl.FOLLOWING, view.xControl())
        assertFalse(view.xFrozen())
        assertFalse(view.xLocked)
    }

    /** Nor do Pin and Auto: their right edge grows with now by design. */
    @Test
    fun `pin and auto are following too`() {
        val pinned = ChartView(windowSeconds = 300.0)
        pinned.setMode(ChartMode.PINNED, now)
        assertEquals(XAxisControl.FOLLOWING, pinned.xControl())

        val all = ChartView(windowSeconds = 300.0)
        all.applyAll(now - 5_000.0, now)
        assertEquals(XAxisControl.FOLLOWING, all.xControl())
    }

    /**
     * Dragging time is what creates the hold, as a gesture on the value axis
     * creates the override there.
     *
     * A pinch on a live plot is the one gesture that does not, and deliberately:
     * it resizes the window and leaves the right edge on now, which is the whole
     * point of zooming a live chart. Nothing is being held, so there is nothing
     * for a Lock to pin - the drag that follows is what puts the axis in the
     * state the controls appear for.
     */
    @Test
    fun `dragging time holds the range, pinching a live window does not`() {
        val dragged = ChartView(windowSeconds = 300.0)
        dragged.pan(-90.0, now)
        assertEquals(XAxisControl.HELD, dragged.xControl())
        assertTrue(dragged.xFrozen())

        val pinched = ChartView(windowSeconds = 300.0)
        pinched.zoom(2.0, now)
        assertEquals(XAxisControl.FOLLOWING, pinched.xControl())
        assertEquals("the pinch should have resized the window", 600.0, pinched.windowSeconds, 1e-9)

        // And once a range is held, a pinch scales that range and it stays held.
        dragged.zoom(2.0, now)
        assertEquals(XAxisControl.HELD, dragged.xControl())
    }

    /** Time then passes, and the held range does not move with it. */
    @Test
    fun `a held range ignores the clock`() {
        val view = heldView()
        val before = view.visibleRange(now)
        val after = view.visibleRange(now + 3_600.0)
        assertEquals(before, after)
    }

    // --- lock ----------------------------------------------------------------

    /** Lock is about a stray finger, so it is gestures that it stops. */
    @Test
    fun `a locked range ignores further gestures`() {
        val view = heldView()
        val before = view.visibleRange(now)
        view.setXLocked(true)
        assertEquals(XAxisControl.LOCKED, view.xControl())

        view.pan(1_000.0, now)
        view.zoom(4.0, now)

        assertEquals(before, view.visibleRange(now))
    }

    /**
     * The buttons that frame a view are not gestures and stay live.
     *
     * A lock that could also refuse Live, Pin, Auto and the window presets would
     * be able to strand the user in a range they could not leave, which is a
     * worse fault than the stray touch it defends against. The y axis draws the
     * same line: its lock stops the pinch and not the range dialog.
     */
    @Test
    fun `the framing buttons still work while locked`() {
        for (frame in listOf<Pair<String, (ChartView) -> Unit>>(
            "Live" to { v -> v.goLive(now) },
            "a window preset" to { v -> v.applyWindow(60.0, now) },
            "Auto" to { v -> v.applyAll(now - 5_000.0, now) },
            "Pin" to { v -> v.pinTap(now) },
        )) {
            val view = heldView()
            val held = view.visibleRange(now)
            view.setXLocked(true)
            frame.second(view)
            assertNotEquals("${frame.first} was refused by the lock", held, view.visibleRange(now))
        }
    }

    /**
     * And none of them can leave a lock standing over a range that is gone.
     *
     * This is the fault the hold could most easily have shipped with: every one
     * of these clears the frozen range, the control that offers to release the
     * lock only appears while a range is held, and so a lock left behind by any
     * of them would refuse every gesture from then on with nothing on screen
     * saying why. Ten places clear the range; the guard is in the field itself
     * rather than in ten call sites (see `ChartView.xFixed`), and this is what
     * says so.
     */
    @Test
    fun `no way of leaving a held range leaves the lock behind`() {
        for (leave in listOf<Pair<String, (ChartView) -> Unit>>(
            "Release" to { v -> v.releaseXRange() },
            "Live" to { v -> v.goLive(now) },
            "a window preset" to { v -> v.applyWindow(60.0, now) },
            "Auto" to { v -> v.applyAll(now - 5_000.0, now) },
            "Pin" to { v -> v.pinTap(now) },
            "Unpin" to { v -> v.unpin(now) },
            "clearing the range" to { v -> v.clearXRange() },
            "autoscaling x" to { v -> v.xAutoscale(true, now) },
        )) {
            val view = heldView()
            view.setXLocked(true)
            leave.second(view)
            assertFalse(
                "${leave.first} left the lock on with no range to hold",
                view.xLocked,
            )
            assertEquals(
                "${leave.first} left the axis in a state that offers a lock",
                XAxisControl.FOLLOWING,
                view.xControl(),
            )
            // And the plot answers touch again afterwards.
            val before = view.visibleRange(now)
            view.pan(120.0, now)
            assertNotEquals("${leave.first} left the plot deaf to gestures", before, view.visibleRange(now))
        }
    }

    /** Release works whether or not the axis was locked, as the y axis' does. */
    @Test
    fun `release hands the axis back, locked or not`() {
        val loose = heldView()
        loose.releaseXRange()
        assertEquals(XAxisControl.FOLLOWING, loose.xControl())

        val locked = heldView()
        locked.setXLocked(true)
        locked.releaseXRange()
        assertEquals(XAxisControl.FOLLOWING, locked.xControl())
        assertFalse(locked.xLocked)
    }

    /** Released, the view follows the clock again - it leaves nothing behind. */
    @Test
    fun `after release the view follows now again`() {
        val view = heldView()
        view.releaseXRange()
        val early = view.visibleRange(now)
        val later = view.visibleRange(now + 600.0)
        assertEquals(600.0, later.second - early.second, 1e-6)
    }

    /** Locking means nothing without a held range, so it is ignored. */
    @Test
    fun `a following axis cannot be locked`() {
        val view = ChartView(windowSeconds = 300.0)
        view.setXLocked(true)
        assertFalse(view.xLocked)
        assertEquals(XAxisControl.FOLLOWING, view.xControl())
        // And it certainly must not have stopped answering gestures.
        val before = view.visibleRange(now)
        view.pan(60.0, now)
        assertNotEquals(before, view.visibleRange(now))
    }

    /** A range stated outright is a fresh intent and arrives unlocked. */
    @Test
    fun `setting a range by hand clears the lock`() {
        val view = heldView()
        view.setXLocked(true)
        view.setXRange(now - 900.0, now - 300.0)
        assertEquals(XAxisControl.HELD, view.xControl())
        assertFalse(view.xLocked)
    }

    /** The state reaches the drawable data, which is what the chips are built from. */
    @Test
    fun `compute reports how the x axis is being decided`() {
        val history = TimeSeriesHistory()
        val view = ChartView(windowSeconds = 300.0)
        assertEquals(XAxisControl.FOLLOWING, view.compute(history, now).xControl)

        view.pan(-600.0, now)
        assertEquals(XAxisControl.HELD, view.compute(history, now).xControl)
        assertTrue(view.compute(history, now).xFrozen)

        view.setXLocked(true)
        assertEquals(XAxisControl.LOCKED, view.compute(history, now).xControl)
    }

    /** The two axes hold independently: locking time leaves the value axis free. */
    @Test
    fun `locking time does not lock the value axis`() {
        val view = heldView()
        view.setXLocked(true)
        view.zoomY(4.0, 2.9, 3.1)
        assertEquals(YAxisControl.OVERRIDDEN, view.yControl())
        assertEquals(XAxisControl.LOCKED, view.xControl())
    }
}

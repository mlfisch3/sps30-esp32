package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Snapping the view to the crosshair: the crosshair's instant becomes one edge
 * of the visible window, and the window's duration is kept exactly.
 *
 * The captain's framing is the specification: snap-left says "start the view
 * here", snap-right says "end the view here", and both are a **pan, never a
 * zoom**. So every test here measures the span before and asserts it after,
 * whatever else the snap did - the promise most worth a test is the one about
 * what must *not* change.
 *
 * Written against [ChartView] directly, like [ChartXAxisTest], because these
 * are rules about ranges. Where the crosshair itself lands afterwards is the
 * cursor state's business (`CursorState.onViewSnapped`, tested in :app with
 * the rest of the cursor machine).
 */
class ChartXSnapTest {

    private val now = 1_785_000_000.0

    /** The record on the plot: an hour of data ending a minute ago. */
    private val dataStart = now - 3_660.0
    private val dataEnd = now - 60.0

    private fun view(windowSeconds: Double = 300.0) = ChartView(windowSeconds)

    private fun ChartView.range() = visibleRange(now)
    private fun ChartView.span() = range().let { it.second - it.first }

    // --- the basic promise ---------------------------------------------------

    @Test
    fun `snap-left makes the instant the left edge and keeps the span`() {
        val v = view(300.0)
        val at = now - 1_800.0
        v.snapWindowStart(at, dataStart, dataEnd, now)
        assertEquals(at, v.range().first, 1e-9)
        assertEquals(300.0, v.span(), 1e-9)
    }

    @Test
    fun `snap-right makes the instant the right edge and keeps the span`() {
        val v = view(300.0)
        val at = now - 1_800.0
        v.snapWindowEnd(at, dataStart, dataEnd, now)
        assertEquals(at, v.range().second, 1e-9)
        assertEquals(300.0, v.span(), 1e-9)
    }

    /** The span kept is the span *on screen*, not the stored window size. */
    @Test
    fun `snap keeps the displayed span after a zoom changed it`() {
        val v = view(300.0)
        v.pan(-600.0, now)   // hold the range
        v.zoom(2.0, now)     // now showing 600 s
        val span = v.span()
        assertEquals(600.0, span, 1e-9)
        v.snapWindowStart(now - 1_800.0, dataStart, dataEnd, now)
        assertEquals(span, v.span(), 1e-9)
    }

    /** The snapped view is the same held range a hand-panned view lands in. */
    @Test
    fun `snap lands in the held state every existing control recovers from`() {
        val v = view(300.0)
        v.snapWindowStart(now - 1_800.0, dataStart, dataEnd, now)
        assertEquals(ViewMode.LIVE_FROZEN, v.viewMode())
        assertEquals(XAxisControl.HELD, v.xControl())
        // Live still works afterwards, exactly as it does after a pan.
        v.goLive(now)
        assertEquals(ViewMode.LIVE, v.viewMode())
    }

    // --- the data boundaries -------------------------------------------------

    /**
     * Snap-left on the newest reading: a faithful window would hold one point
     * and emptiness after it. The clamp keeps a full span of record in view,
     * ending at the newest data.
     */
    @Test
    fun `snap-left at the far right of the data clamps to a full window of record`() {
        val v = view(300.0)
        v.snapWindowStart(dataEnd, dataStart, dataEnd, now)
        assertEquals(dataEnd, v.range().second, 1e-9)
        assertEquals(300.0, v.span(), 1e-9)
    }

    /** Snap-right on the oldest reading: mirror image, starting at the data. */
    @Test
    fun `snap-right at the far left of the data clamps to a full window of record`() {
        val v = view(300.0)
        v.snapWindowEnd(dataStart, dataStart, dataEnd, now)
        assertEquals(dataStart, v.range().first, 1e-9)
        assertEquals(300.0, v.span(), 1e-9)
    }

    /** Between the boundary and a clean snap, the clamp only takes what it must. */
    @Test
    fun `snap-left clamps by exactly the overhang, not to a preset place`() {
        val v = view(300.0)
        val at = dataEnd - 100.0 // 100 s of record right of the crosshair, 300 wanted
        v.snapWindowStart(at, dataStart, dataEnd, now)
        assertEquals(dataEnd - 300.0, v.range().first, 1e-9)
        assertEquals(dataEnd, v.range().second, 1e-9)
    }

    /**
     * A record shorter than the window: no placement can fill the span with
     * data, so the window starts (or ends) where the record does and the whole
     * record stays in view. The span is still kept - this must not become a
     * zoom-to-fit.
     */
    @Test
    fun `snap with a record shorter than the window keeps span and shows the record`() {
        val shortStart = now - 120.0
        val shortEnd = now - 20.0

        val left = view(300.0)
        left.snapWindowStart(shortEnd, shortStart, shortEnd, now)
        assertEquals(shortStart, left.range().first, 1e-9)
        assertEquals(300.0, left.span(), 1e-9)

        val right = view(300.0)
        right.snapWindowEnd(shortStart, shortStart, shortEnd, now)
        assertEquals(shortEnd, right.range().second, 1e-9)
        assertEquals(300.0, right.span(), 1e-9)
    }

    /** With no data at all there is nothing to clamp against: the ask is honoured. */
    @Test
    fun `snap without any data frames the asked-for window`() {
        val v = view(300.0)
        val at = now - 1_000.0
        v.snapWindowStart(at, null, null, now)
        assertEquals(at to at + 300.0, v.range())
        v.snapWindowEnd(at, null, null, now)
        assertEquals(at - 300.0 to at, v.range())
    }

    // --- where the snap starts from ------------------------------------------

    /** From Pin mode: the span shown is now - pinStart, and it is what is kept. */
    @Test
    fun `snap from a pinned view keeps that view's span and leaves pin mode`() {
        val v = view(300.0)
        v.setMode(ChartMode.PINNED, now)
        v.shiftPin(-600.0, now) // left edge at now - 900, so 900 s on screen
        val span = v.span()
        v.snapWindowStart(now - 1_800.0, dataStart, dataEnd, now)
        assertEquals(span, v.span(), 1e-9)
        assertEquals(ViewMode.LIVE_FROZEN, v.viewMode())
    }

    /** From the whole-session view: same rule, the displayed span is the span. */
    @Test
    fun `snap from the session view keeps the session span`() {
        val v = view(300.0)
        v.applyAll(dataStart, now)
        val span = v.span()
        v.snapWindowEnd(dataEnd, dataStart, dataEnd, now)
        assertEquals(span, v.span(), 1e-9)
        assertEquals(ViewMode.LIVE_FROZEN, v.viewMode())
    }

    /**
     * Snap is a button that frames a view, not a gesture at one, so like every
     * such button it applies under an x lock and releases it - a held range is
     * replaced, not defended, by a fresh statement of intent. See [setXRange].
     */
    @Test
    fun `snap under an x lock frames its view and releases the lock`() {
        val v = view(300.0)
        v.pan(-600.0, now)
        v.setXLocked(true)
        assertEquals(XAxisControl.LOCKED, v.xControl())
        val at = now - 1_800.0
        v.snapWindowStart(at, dataStart, dataEnd, now)
        assertEquals(at, v.range().first, 1e-9)
        assertFalse(v.xLocked)
    }
}

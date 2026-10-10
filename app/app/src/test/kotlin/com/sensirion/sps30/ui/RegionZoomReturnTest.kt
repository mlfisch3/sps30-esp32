package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Zooming to the selection, and the way back out of it.
 *
 * The zoom frames the selection as a held range - the same state a hand-panned
 * view lands in, so every existing exit still applies and nobody can be
 * stranded. "Back" then means *where he was*, which is mode-dependent: a live
 * window resumes following now at its old span, a pinned view still grows from
 * its old origin, a held range is exactly its range, and the whole-session
 * view is re-derived the way the All button derives it.
 */
class RegionZoomReturnTest {

    private val now = 1_770_000_000.0
    private val selStart = now - 2_000.0
    private val selEnd = now - 1_000.0

    @Test
    fun theZoomFramesTheSelectionAsAHeldRange() {
        val chart = ChartView(windowSeconds = 3_600.0)
        zoomViewToRegion(chart, selStart, selEnd, now)
        assertEquals(ViewMode.LIVE_FROZEN, chart.viewMode())
        assertEquals(selStart to selEnd, chart.visibleRange(now))
    }

    @Test
    fun aLiveViewReturnsToALiveWindowOfItsOldSpan() {
        val chart = ChartView(windowSeconds = 3_600.0)
        val saved = captureReturnView(chart, now)
        zoomViewToRegion(chart, selStart, selEnd, now)

        val later = now + 500.0
        restoreReturnView(chart, saved, null, later)
        assertEquals(ViewMode.LIVE, chart.viewMode())
        // Following again: the right edge is now, the span is the one he had.
        assertEquals(later - 3_600.0 to later, chart.visibleRange(later))
    }

    @Test
    fun aPinnedViewReturnsStillGrowingFromItsOldOrigin() {
        val chart = ChartView(windowSeconds = 300.0)
        chart.setMode(ChartMode.PINNED, now)
        val origin = chart.visibleRange(now).first
        val saved = captureReturnView(chart, now)
        zoomViewToRegion(chart, selStart, selEnd, now)

        val later = now + 500.0
        restoreReturnView(chart, saved, null, later)
        assertEquals(ViewMode.PIN, chart.viewMode())
        // The same origin, and a right edge that has kept up with the clock.
        assertEquals(origin to later, chart.visibleRange(later))
    }

    /**
     * A pin that carries a held frame - a window-size tap in Pin frames
     * forward from the mark now - returns to exactly that frame, pin intact,
     * rather than to a [pin, now] nobody was looking at.
     */
    @Test
    fun aPinnedFramedViewReturnsToItsFrameWithThePinIntact() {
        val chart = ChartView(windowSeconds = 3_600.0)
        chart.pan(-7_200.0, now)                       // held by the hand
        val mark = chart.visibleRange(now).first
        chart.pinLongPress(now)                        // the global pin time mark
        chart.applyWindow(1_800.0, now, now - 80_000.0, now)
        val saved = captureReturnView(chart, now)
        zoomViewToRegion(chart, selStart, selEnd, now)

        val later = now + 500.0
        restoreReturnView(chart, saved, null, later)
        assertEquals(ViewMode.PIN, chart.viewMode())
        assertEquals(mark to mark + 1_800.0, chart.visibleRange(later))
        assertEquals(mark, chart.referencePinTime!!, 0.0)
    }

    @Test
    fun aHeldRangeReturnsToExactlyThatRange() {
        val chart = ChartView(windowSeconds = 300.0)
        chart.setXRange(now - 9_000.0, now - 8_000.0)
        val saved = captureReturnView(chart, now)
        zoomViewToRegion(chart, selStart, selEnd, now)

        restoreReturnView(chart, saved, null, now + 500.0)
        assertEquals(ViewMode.LIVE_FROZEN, chart.viewMode())
        assertEquals(now - 9_000.0 to now - 8_000.0, chart.visibleRange(now + 500.0))
    }

    @Test
    fun theWholeSessionViewReturnsToTheWholeSession() {
        val chart = ChartView(windowSeconds = 300.0)
        val sessionStart = now - 40_000.0
        chart.applyAll(sessionStart, now)
        val saved = captureReturnView(chart, now)
        zoomViewToRegion(chart, selStart, selEnd, now)

        val later = now + 500.0
        restoreReturnView(chart, saved, sessionStart, later)
        assertEquals(ViewMode.ALL, chart.viewMode())
        assertEquals(sessionStart to later, chart.visibleRange(later))
    }

    @Test
    fun aBackwardsSelectionIsRefusedRatherThanObeyed() {
        val chart = ChartView(windowSeconds = 3_600.0)
        val before = chart.visibleRange(now)
        zoomViewToRegion(chart, selEnd, selStart, now)
        assertEquals(before, chart.visibleRange(now))
    }
}

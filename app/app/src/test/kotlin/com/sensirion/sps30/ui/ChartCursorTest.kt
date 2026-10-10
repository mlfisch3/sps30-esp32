package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The crosshair anchors to whatever the view is holding still.
 *
 * Live, the range moves on its own and the cursor keeps its place on the plot
 * so the readout follows the newest sample, the way `less +F` follows a file.
 * Anywhere else the range only moves because the user moved it, and then the
 * cursor must keep its **instant** - zooming and panning carry it along with
 * its own data point instead of sliding the data out from under it.
 */
class ChartCursorTest {

    private fun chart(start: Double, end: Double, mode: ViewMode) = ChartData(
        x = doubleArrayOf(start, (start + end) / 2, end),
        series = emptyMap(),
        start = start,
        end = end,
        xTicks = doubleArrayOf(),
        xLabels = emptyList(),
        dataYMin = 0.0,
        dataYMax = 10.0,
        spanSeconds = end - start,
        sampleCount = 3,
        mode = if (mode == ViewMode.PIN) ChartMode.PINNED else ChartMode.MOVING,
        viewMode = mode,
        windowSeconds = end - start,
        yAuto = true,
        yMin = null,
        yMax = null,
        xFrozen = mode == ViewMode.LIVE_FROZEN,
        hasReferencePin = false,
    )

    /** Where the crosshair is drawn, as a fraction of the plot's width. */
    private fun placeOf(cursor: ChartCursor, data: ChartData): Double {
        val epoch = cursor.epochIn(data)!!
        return (epoch - data.start) / (data.end - data.start)
    }

    @Test fun aPinnedCursorKeepsItsInstantAcrossAZoom() {
        val before = chart(1000.0, 2000.0, ViewMode.PIN)
        val cursor = anchorFor(1750.0, before, followsNow = false)
        // Zoom out 4x about the same centre: 500..2500.
        val after = chart(500.0, 2500.0, ViewMode.PIN)

        assertEquals(1750.0, cursor.epochIn(after)!!, 1e-9)
        // Same instant, different place: it travelled with its data.
        assertEquals(0.75, placeOf(cursor, before), 1e-9)
        assertEquals(0.625, placeOf(cursor, after), 1e-9)
    }

    @Test fun aPinnedCursorKeepsItsInstantAcrossAPan() {
        val before = chart(1000.0, 2000.0, ViewMode.LIVE_FROZEN)
        val cursor = anchorFor(1200.0, before, followsNow = false)
        val after = chart(1300.0, 2300.0, ViewMode.LIVE_FROZEN)
        assertEquals(1200.0, cursor.epochIn(after)!!, 1e-9)
        // Panned right by 30% of the span, so the cursor is 30% further left.
        assertEquals(0.2, placeOf(cursor, before), 1e-9)
        assertEquals(-0.1, placeOf(cursor, after), 1e-9)
    }

    @Test fun aFrozenViewIsNotFollowingNowSoItsCursorHoldsTheInstant() {
        // The bug: LIVE_FROZEN counted as "following now", so the cursor held a
        // pixel while a zoom moved the data past it.
        for (mode in listOf(ViewMode.LIVE_FROZEN, ViewMode.PIN, ViewMode.ALL)) {
            val data = chart(1000.0, 2000.0, mode)
            val cursor = anchorFor(1500.0, data, followsNow = false)
            assertTrue("$mode must hold an instant", cursor is ChartCursor.AtTime)
        }
    }

    @Test fun aLiveCursorHoldsItsPlaceSoTheDataFlowsPast() {
        val before = chart(1000.0, 2000.0, ViewMode.LIVE)
        val cursor = anchorFor(2000.0, before, followsNow = true)
        assertTrue(cursor is ChartCursor.AtPlace)
        // 25 s later the window has slid; parked at the right edge the cursor
        // keeps reporting the newest sample.
        val after = chart(1025.0, 2025.0, ViewMode.LIVE)
        assertEquals(2025.0, cursor.epochIn(after)!!, 1e-9)
    }

    @Test fun switchingAnchorNeverMovesTheCrosshair() {
        val data = chart(1000.0, 2000.0, ViewMode.LIVE)
        val live = anchorFor(1600.0, data, followsNow = true)
        val pinned = anchorFor(live.epochIn(data)!!, data, followsNow = false)
        assertTrue(abs(pinned.epochIn(data)!! - live.epochIn(data)!!) < 1e-9)
    }

    @Test fun anInstantOutsideTheWindowStaysAnInstantEvenWhenLive() {
        // Measured on the tablet: a live 60 s window zoomed down to 25 s put the
        // crosshair's instant 21 s before the left edge, and expressing that as
        // a fraction of the plot could only clamp it - which jumped the readout
        // from 03:32:19 to 03:32:40, onto a sample nobody had chosen.
        val zoomed = chart(1975.0, 2000.0, ViewMode.LIVE)
        val cursor = anchorFor(1953.6, zoomed, followsNow = true)
        assertTrue("must not become a place on the plot", cursor is ChartCursor.AtTime)
        assertEquals(1953.6, cursor.epochIn(zoomed)!!, 1e-9)
    }

    @Test fun anInstantOnTheEdgeStillCountsAsOnThePlot() {
        val data = chart(1000.0, 2000.0, ViewMode.LIVE)
        assertTrue(anchorFor(1000.0, data, followsNow = true) is ChartCursor.AtPlace)
        assertTrue(anchorFor(2000.0, data, followsNow = true) is ChartCursor.AtPlace)
    }

    // --- Pin (the plot's corner button) -------------------------------------

    @Test fun pinningMakesALiveCursorHoldItsInstantInstead() {
        val before = chart(1000.0, 2000.0, ViewMode.LIVE)
        // Unpinned, the live cursor holds its place and the data flows past it.
        val loose = anchorFor(1600.0, before, cursorFollowsNow(before, pinned = false))
        // Pinned, it holds the moment it was put on.
        val stuck = anchorFor(1600.0, before, cursorFollowsNow(before, pinned = true))
        assertTrue(loose is ChartCursor.AtPlace)
        assertTrue(stuck is ChartCursor.AtTime)

        // 200 s of new readings later, the window has slid that far left-to-right.
        val after = chart(1200.0, 2200.0, ViewMode.LIVE)
        assertEquals("held its place", 1800.0, loose.epochIn(after)!!, 1e-9)
        assertEquals("travelled with its data", 1600.0, stuck.epochIn(after)!!, 1e-9)
        // Which is the point: pinned, it drifts leftwards across the plot.
        assertEquals(0.6, placeOf(loose, before), 1e-9)
        assertEquals(0.6, placeOf(loose, after), 1e-9)
        assertEquals(0.4, placeOf(stuck, after), 1e-9)
    }

    @Test fun pinningChangesNothingInAFrozenView() {
        // Nothing flows past the crosshair in a fixed range, so it holds its
        // instant whatever the button says - which is why the button greys out
        // there, and only there. Pin and Auto used to be listed here too, on the
        // grounds that they show the past; they do not, and see
        // [CursorPinModeTest] for what that cost.
        val data = chart(1000.0, 2000.0, ViewMode.LIVE_FROZEN)
        assertTrue(!cursorFollowsNow(data, pinned = false))
        assertTrue(!cursorFollowsNow(data, pinned = true))
    }

    @Test fun pinningNeverMovesTheCrosshair() {
        val data = chart(1000.0, 2000.0, ViewMode.LIVE)
        val loose = anchorFor(1600.0, data, cursorFollowsNow(data, pinned = false))
        val stuck = anchorFor(loose.epochIn(data)!!, data, cursorFollowsNow(data, pinned = true))
        assertEquals(1600.0, stuck.epochIn(data)!!, 1e-9)
        // And back again, still without moving.
        val loosened = anchorFor(stuck.epochIn(data)!!, data, cursorFollowsNow(data, pinned = false))
        assertEquals(1600.0, loosened.epochIn(data)!!, 1e-9)
    }

    // --- Follow ------------------------------------------------------------

    @Test fun aFollowingCursorLandsOnTheNewestSample() {
        val cursor = ChartCursor.AtLatest
        assertEquals(2000.0, cursor.epochIn(chart(1000.0, 2000.0, ViewMode.LIVE))!!, 1e-9)
        // A second later there is a newer sample, and it moved to it by itself.
        assertEquals(2001.0, cursor.epochIn(chart(1001.0, 2001.0, ViewMode.LIVE))!!, 1e-9)
    }

    @Test fun aFollowingCursorIsNeverOffThePlot() {
        // Every view mode, including the ones a zoom or a pan produces: the
        // newest *drawn* sample is by definition inside the drawn range, which
        // is what an anchored instant cannot promise.
        for (mode in ViewMode.entries) {
            val data = chart(1000.0, 2000.0, mode)
            val epoch = ChartCursor.AtLatest.epochIn(data)!!
            assertTrue("$mode put the cursor off the plot", epoch in data.start..data.end)
        }
    }

    @Test fun aFollowingCursorOnAnEmptyPlotHasNowhereToBe() {
        val empty = chart(1000.0, 2000.0, ViewMode.LIVE).copy(x = doubleArrayOf())
        assertNull(ChartCursor.AtLatest.epochIn(empty))
        assertNull(ChartCursor.AtLatest.epochIn(null))
    }

    @Test fun theRightEdgeIsNotTheSameAsTheNewestSample() {
        // Why Follow is its own anchor rather than AtPlace(1.0). A live window
        // runs to *now*, and the newest sample is a little behind it - here a
        // 60 s window whose last point is 8 s old, as a duty-cycled or averaged
        // series routinely is.
        val data = chart(1000.0, 2000.0, ViewMode.LIVE).copy(x = doubleArrayOf(1900.0, 1992.0))
        assertEquals(1992.0, ChartCursor.AtLatest.epochIn(data)!!, 1e-9)
        assertEquals(2000.0, ChartCursor.AtPlace(1.0).epochIn(data)!!, 1e-9)
    }
}

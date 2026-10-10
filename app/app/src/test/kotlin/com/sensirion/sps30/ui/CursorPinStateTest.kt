package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cursor pin state machine: the free cursor, or one of three pins.
 *
 * ## The defect this design replaces
 *
 * Pinning used to be a boolean beside the anchor it implied, and the button
 * that set it greyed itself out in every view whose range did not run to now -
 * so it worked only when new data happened to sit at the far right of the
 * graph, which is what pressing Pin or Auto arranges. The machine has no such
 * gate: every pin can be engaged from every view, because a pin is always
 * meaningful - it fixes what the cursor holds, and it takes the single
 * finger's claim on the cursor away.
 *
 * These tests exercise the machine as plain values, the way [ChartTouchArbiter]
 * is tested: the states, the transitions, what each pin holds through pan, zoom
 * and arriving data, and the touch-movability rule. What the finger does to the
 * real composables is [CursorPinTouchTest]'s business.
 */
class CursorPinStateTest {

    /** A window of [n] points, one per second, ending at [end]. */
    private fun chart(
        end: Double,
        n: Int = 11,
        mode: ViewMode = ViewMode.LIVE,
        start: Double = end - (n - 1),
    ) = ChartData(
        x = DoubleArray(n) { end - (n - 1) + it },
        series = emptyMap(),
        start = start,
        end = end,
        xTicks = doubleArrayOf(),
        xLabels = emptyList(),
        dataYMin = 0.0,
        dataYMax = 10.0,
        spanSeconds = end - start,
        sampleCount = n,
        mode = if (mode == ViewMode.PIN) ChartMode.PINNED else ChartMode.MOVING,
        viewMode = mode,
        windowSeconds = end - start,
        yAuto = true,
        yMin = null,
        yMax = null,
        xFrozen = mode == ViewMode.LIVE_FROZEN,
        hasReferencePin = false,
    )

    // --- engagement: from any view, never moving the cursor ------------------

    @Test fun everyPinTypeEngagesInEveryViewMode() {
        for (mode in ViewMode.entries) {
            val data = chart(2000.0, mode = mode)
            val here = 1995.0
            assertEquals("$mode", 1995.0, CursorPin.ToTime(here).epochIn(data)!!, 1e-9)
            assertEquals("$mode", 1995.0, pinFromRight(data, here)!!.epochIn(data)!!, 1e-9)
            assertEquals("$mode", 1995.0, pinFromLeft(data, here)!!.epochIn(data)!!, 1e-9)
        }
    }

    @Test fun engagingAPinNeverMovesTheCursorOffItsSample() {
        // A cursor between two samples pins to the nearest one, exactly as the
        // crosshair snaps when drawn - not to an edge, not to a clamp.
        val data = chart(2000.0)
        val here = 1993.4
        assertEquals(1993.0, pinFromRight(data, here)!!.epochIn(data)!!, 1e-9)
        assertEquals(1993.0, pinFromLeft(data, here)!!.epochIn(data)!!, 1e-9)
    }

    @Test fun thereIsNothingToPinOnAnEmptyPlot() {
        val empty = chart(2000.0, n = 0, start = 1990.0)
        assertNull(pinFromRight(empty, 1995.0))
        assertNull(pinFromLeft(empty, 1995.0))
        assertNull(pinFromRight(null, 1995.0))
    }

    // --- pin to time: a fixed mark, whatever the view does -------------------

    @Test fun pinToTimeSurvivesPanZoomAndArrivingData() {
        val pin = CursorPin.ToTime(1995.0)
        // New data slides the window; the pinned instant does not move.
        assertEquals(1995.0, pin.epochIn(chart(2100.0))!!, 1e-9)
        // Pan and zoom change the drawn set entirely; still the same instant,
        // even once it is outside the window - the plot marks the edge it left
        // by rather than clamping it onto a sample nobody chose.
        assertEquals(1995.0, pin.epochIn(chart(1500.0, n = 5))!!, 1e-9)
    }

    // --- pin from right: a fixed offset from the right edge ------------------

    @Test fun pinFromRightRidesArrivingDataAtItsDistance() {
        val before = chart(2000.0)
        val pin = pinFromRight(before, 1997.0)!!
        assertEquals(CursorPin.FromRight(3), pin)
        // 50 s of new readings later the window has slid. Three points in from
        // the right edge is now a newer sample: the pin holds the distance,
        // not the moment.
        assertEquals(2047.0, pin.epochIn(chart(2050.0))!!, 1e-9)
    }

    @Test fun pinFromRightHoldsItsCountAcrossAPan() {
        val pin = CursorPin.FromRight(3)
        // Panned back in time: still three points in from whatever the right
        // edge shows now.
        assertEquals(1897.0, pin.epochIn(chart(1900.0))!!, 1e-9)
    }

    @Test fun pinFromRightClampsToTheOldestPointWhenTheViewIsTooNarrow() {
        val pin = CursorPin.FromRight(8)
        // Zoomed in to 4 points: 8-from-right does not exist, so the cursor
        // sits on the oldest visible point for as long as the view is this
        // narrow...
        assertEquals(1997.0, pin.epochIn(chart(2000.0, n = 4))!!, 1e-9)
        // ...and the stored count is untouched, so widening the view puts the
        // cursor straight back where it was pinned.
        assertEquals(1992.0, pin.epochIn(chart(2000.0, n = 11))!!, 1e-9)
    }

    // --- pin from left -------------------------------------------------------

    @Test fun pinFromLeftHoldsItsCountFromTheLeftEdge() {
        val before = chart(2000.0)
        val pin = pinFromLeft(before, 1992.0)!!
        assertEquals(CursorPin.FromLeft(2), pin)
        assertEquals(1892.0, pin.epochIn(chart(1900.0))!!, 1e-9)
    }

    @Test fun pinFromLeftClampsToTheNewestPointWhenTheViewIsTooNarrow() {
        val pin = CursorPin.FromLeft(8)
        assertEquals(2000.0, pin.epochIn(chart(2000.0, n = 4))!!, 1e-9)
        assertEquals(1998.0, pin.epochIn(chart(2000.0, n = 11))!!, 1e-9)
    }

    // --- the captain's note: how the two edge pins relate --------------------

    @Test fun inASlidingWindowFromLeftAndFromRightAreTheSamePlace() {
        // The x axis is NOT pinned to a mark: a live window slides, both edges
        // together, and the point count holds. An offset from either edge is
        // then the same place on the glass, holding the same age of data.
        val before = chart(2000.0, n = 11)
        val right = pinFromRight(before, 1996.0)!!
        val left = pinFromLeft(before, 1996.0)!!
        for (end in listOf(2010.0, 2050.0, 2100.0)) {
            val later = chart(end, n = 11)
            assertEquals(
                "the two edge pins split apart in a sliding window",
                right.epochIn(later)!!, left.epochIn(later)!!, 1e-9,
            )
        }
    }

    @Test fun inALeftPinnedViewTheTwoEdgePinsComeApart() {
        // The x axis IS pinned on the left (the view's Pin mode): the left edge
        // holds a mark and new points squeeze in from the right.
        val before = chart(2000.0, n = 11, mode = ViewMode.PIN, start = 1990.0)
        val right = pinFromRight(before, 1996.0)!!
        val left = pinFromLeft(before, 1996.0)!!
        // 10 s later the same origin holds 10 more points.
        val later = chart(2010.0, n = 21, mode = ViewMode.PIN, start = 1990.0)
        // From-left travels leftward with its own data point - the scrunch -
        // which makes it equivalent to a pin-to-time here...
        assertEquals(1996.0, left.epochIn(later)!!, 1e-9)
        // ...while from-right holds its distance from the growing edge.
        assertEquals(2006.0, right.epochIn(later)!!, 1e-9)
    }

    // --- touch: only the free cursor answers the finger ----------------------

    @Test fun touchMovesTheCursorOnlyWhenCrosshairIsOnAndNothingIsPinned() {
        assertTrue(cursorMovableByTouch(enabled = true, pin = CursorPin.FreeCursor))
        assertFalse(cursorMovableByTouch(enabled = false, pin = CursorPin.FreeCursor))
        for (pin in listOf(
            CursorPin.ToTime(1995.0),
            CursorPin.FromRight(3),
            CursorPin.FromLeft(2),
        )) {
            assertFalse("$pin must not be movable", cursorMovableByTouch(true, pin))
            assertFalse("$pin must not be movable", cursorMovableByTouch(false, pin))
        }
    }

    // --- snap to peak --------------------------------------------------------

    private fun charted(vararg series: Pair<String, DoubleArray>) =
        chart(2000.0, n = 11).copy(series = mapOf(*series))

    @Test fun thePeakIsTheHighestVisiblePointInTheWindow() {
        val data = charted(
            "pm2_5" to doubleArrayOf(1.0, 2.0, 9.0, 3.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
        )
        assertEquals(1992.0, peakEpoch(data, listOf("pm2_5"))!!, 1e-9)
    }

    @Test fun withBothSeriesPlottedTheOverallMaximumWins() {
        val data = charted(
            "pm2_5" to doubleArrayOf(1.0, 2.0, 9.0, 3.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
            "pm1_0" to doubleArrayOf(1.0, 1.0, 4.0, 1.0, 1.0, 1.0, 1.0, 12.0, 1.0, 1.0, 1.0),
        )
        // PM1.0's 12 beats PM2.5's 9: the highest-value point on the graph.
        assertEquals(1997.0, peakEpoch(data, listOf("pm2_5", "pm1_0"))!!, 1e-9)
    }

    @Test fun aHiddenSeriesCannotBeThePeak() {
        val data = charted(
            "pm2_5" to doubleArrayOf(1.0, 2.0, 9.0, 3.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
            "pm1_0" to doubleArrayOf(1.0, 1.0, 4.0, 1.0, 1.0, 1.0, 1.0, 12.0, 1.0, 1.0, 1.0),
        )
        // A snap onto a curve that is not on screen would land the readout on
        // nothing, so only the switched-on series compete.
        assertEquals(1992.0, peakEpoch(data, listOf("pm2_5"))!!, 1e-9)
    }

    @Test fun nothingDrawnMeansNoPeakToSnapTo() {
        assertNull(peakEpoch(null, listOf("pm2_5")))
        assertNull(peakEpoch(chart(2000.0, n = 0, start = 1990.0), listOf("pm2_5")))
        assertNull(peakEpoch(charted("pm2_5" to DoubleArray(11) { 1.0 }), emptyList()))
    }

    @Test fun snappingPinsAnOrdinaryPinToTimeThatStaysPutWhenThePeakMovesOn() {
        val data = charted(
            "pm2_5" to doubleArrayOf(1.0, 2.0, 9.0, 3.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
        )
        val pin = CursorPin.ToTime(peakEpoch(data, listOf("pm2_5"))!!)
        // The view moves on and a higher point enters the window. The cursor
        // holds the moment it snapped to: it is a pin-to-time, not the title
        // of "current peak".
        val later = chart(2050.0).copy(
            series = mapOf("pm2_5" to DoubleArray(11) { 20.0 }),
        )
        assertEquals(1992.0, pin.epochIn(later)!!, 1e-9)
    }

    // --- the free cursor, untouched by all of this ---------------------------

    /** Where the crosshair is drawn, as a fraction of the plot's width. */
    private fun placeOf(cursor: ChartCursor, data: ChartData): Double {
        val epoch = cursor.epochIn(data)!!
        return (epoch - data.start) / (data.end - data.start)
    }

    @Test fun theFreeCursorStillHoldsItsPlaceWhileAPinnedViewGrows() {
        // Carried over from the boolean era: in the view's Pin mode the window
        // grows to the right, and a free crosshair keeps the place the user
        // put it on while the data flows past.
        val before = chart(2000.0, mode = ViewMode.PIN, start = 1000.0)
        val cursor = anchorFor(1600.0, before, cursorFollowsNow(before, pinned = false))
        assertTrue(cursor is ChartCursor.AtPlace)
        val after = chart(3000.0, mode = ViewMode.PIN, start = 1000.0)
        assertEquals(0.6, placeOf(cursor, before), 1e-9)
        assertEquals("the free cursor left the place it was put", 0.6, placeOf(cursor, after), 1e-9)
    }

    @Test fun theFreeCursorStillHoldsItsInstantInAFrozenView() {
        val data = chart(2000.0, mode = ViewMode.LIVE_FROZEN, start = 1000.0)
        assertFalse(cursorFollowsNow(data, pinned = false))
        assertTrue(anchorFor(1600.0, data, followsNow = false) is ChartCursor.AtTime)
    }

    // --- surviving the screen turning ----------------------------------------

    @Test fun everyPinRoundTripsThroughItsSaver() {
        for (pin in listOf<CursorPin>(
            CursorPin.FreeCursor,
            CursorPin.ToTime(1995.5),
            CursorPin.FromRight(3),
            CursorPin.FromLeft(2),
        )) {
            @Suppress("UNCHECKED_CAST")
            val saved = with(CursorPinSaver) {
                object : androidx.compose.runtime.saveable.SaverScope {
                    override fun canBeSaved(value: Any) = true
                }.save(pin)
            }!!
            assertEquals(pin, CursorPinSaver.restore(saved))
        }
    }
}

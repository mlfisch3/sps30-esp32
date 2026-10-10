package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What snapping the view to the crosshair leaves the cursor holding.
 *
 * The view side of the snap is [ChartView]'s business, tested in :core
 * (`ChartXSnapTest`): the crosshair's instant becomes an edge of the window
 * and the duration is kept. This side is the cursor's: after the view moves,
 * the crosshair must still be on the moment the user snapped on - at the edge
 * they chose - and must still be pinned if it was pinned. The rule is stated
 * once, in [CursorState.onViewSnapped]; these tests hold each cursor state to
 * it.
 */
class CursorViewSnapTest {

    private val at = 1_754_000_123.0

    @Test
    fun aPlacedFreeCursorIsReanchoredToTheSnappedMoment() {
        val state = CursorState(cursor = ChartCursor.AtPlace(0.4))
        state.onViewSnapped(at)
        // The snapped view is a held range, where an instant is the honest
        // anchor - and the instant is the edge the user asked for.
        assertEquals(ChartCursor.AtTime(at), state.cursor)
        assertEquals("snapping must not engage a pin", CursorPin.FreeCursor, state.pin)
    }

    @Test
    fun aFreeCursorAlreadyOnItsInstantStaysOnIt() {
        val state = CursorState(cursor = ChartCursor.AtTime(at))
        state.onViewSnapped(at)
        assertEquals(ChartCursor.AtTime(at), state.cursor)
    }

    @Test
    fun aFollowingCursorIsLeftFollowing() {
        val state = CursorState(cursor = ChartCursor.AtLatest, follows = true)
        state.onViewSnapped(at)
        // Follow holds the newest sample by definition; the view move was the
        // whole of what was asked.
        assertEquals(ChartCursor.AtLatest, state.cursor)
        assertTrue(state.follows)
    }

    @Test
    fun aTimePinnedCursorStaysPinnedToItsMoment() {
        val state = CursorState(pin = CursorPin.ToTime(at))
        state.onViewSnapped(at)
        assertEquals(CursorPin.ToTime(at), state.pin)
    }

    /**
     * An edge pin's count is measured from an edge the snap has just moved, so
     * keeping the count would land the crosshair anywhere but the point the
     * user snapped on. It stays pinned - to the moment, the way Snap to peak
     * pins - because the snap was about that moment.
     */
    @Test
    fun anEdgePinnedCursorStaysPinnedOnTheSnappedMoment() {
        val fromRight = CursorState(pin = CursorPin.FromRight(5))
        fromRight.onViewSnapped(at)
        assertEquals(CursorPin.ToTime(at), fromRight.pin)

        val fromLeft = CursorState(pin = CursorPin.FromLeft(2))
        fromLeft.onViewSnapped(at)
        assertEquals(CursorPin.ToTime(at), fromLeft.pin)
    }

    @Test
    fun snappingNeverTouchesTheCrosshairSwitch() {
        val on = CursorState(cursor = ChartCursor.AtTime(at))
        on.onViewSnapped(at)
        assertTrue(on.enabled)
    }
}

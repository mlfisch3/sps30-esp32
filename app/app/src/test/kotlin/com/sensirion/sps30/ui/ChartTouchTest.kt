package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a touch on the plot is allowed to become, and - the whole point - when.
 *
 * Every two-finger gesture starts as one finger, so anything decided on that
 * first finger alone is decided about pinches too. These are the cases that
 * used to move the crosshair on the way into a zoom.
 */
class ChartTouchTest {

    private val slop = 15f

    private fun arbiter(inspectAllowed: Boolean = true) =
        ChartTouchArbiter(slopPx = slop, inspectAllowed = inspectAllowed)

    @Test fun aFirstFingerOnItsOwnDecidesNothing() {
        val a = arbiter()
        a.down(1000, 100f, 100f)
        assertEquals(ChartTouchRole.UNDECIDED, a.role)
        // Still nothing, several events in, while it neither moves nor waits long.
        assertEquals(ChartTouchRole.UNDECIDED, a.update(1008, 100f, 100f, 1))
        assertEquals(ChartTouchRole.UNDECIDED, a.update(1040, 102f, 101f, 1))
        assertEquals(ChartTouchRole.UNDECIDED, a.update(1100, 105f, 103f, 1))
    }

    @Test fun aSecondFingerArrivingInTimeMakesTheWholeGestureNavigation() {
        // The measured pinch onsets this has to cover, in milliseconds.
        for (gap in listOf(0L, 4L, 16L, 40L, 75L, 110L, CURSOR_HOLD_MS - 1)) {
            val a = arbiter()
            a.down(1000, 100f, 100f)
            a.update(1000 + gap, 100f, 100f, 2)
            assertEquals("gap ${gap}ms", ChartTouchRole.NAVIGATE, a.role)
        }
    }

    @Test fun holdingStillPastTheTimeoutIsACursorRequest() {
        val a = arbiter()
        a.down(1000, 100f, 100f)
        assertEquals(ChartTouchRole.UNDECIDED, a.update(1000 + CURSOR_HOLD_MS - 1, 100f, 100f, 1))
        assertEquals(ChartTouchRole.INSPECT, a.update(1000 + CURSOR_HOLD_MS, 100f, 100f, 1))
    }

    @Test fun crossingTouchSlopIsACursorRequestWithoutWaiting() {
        val a = arbiter()
        a.down(1000, 100f, 100f)
        assertEquals(ChartTouchRole.UNDECIDED, a.update(1008, 100f + slop - 1f, 100f, 1))
        assertEquals(ChartTouchRole.INSPECT, a.update(1016, 100f + slop, 100f, 1))
    }

    @Test fun slopIsMeasuredInBothDirections() {
        val a = arbiter()
        a.down(1000, 100f, 100f)
        // 12,12 is 16.97 px away: past slop, though neither axis is on its own.
        assertEquals(ChartTouchRole.INSPECT, a.update(1008, 112f, 112f, 1))
    }

    @Test fun aTapShorterThanTheHoldStillPlacesTheCursor() {
        // Otherwise a quick tap - well under 120 ms of contact - would do nothing
        // at all, which is a worse bug than the one being fixed.
        val a = arbiter()
        a.down(1000, 100f, 100f)
        a.update(1030, 100f, 100f, 1)
        assertEquals(ChartTouchRole.INSPECT, a.lifted())
    }

    @Test fun aSecondFingerAfterTheCursorHasCommittedStillWins() {
        // A slow pinch: the hold expires, then the second finger lands. The
        // gesture is a navigation after all, and the caller undoes the move.
        val a = arbiter()
        a.down(1000, 100f, 100f)
        assertEquals(ChartTouchRole.INSPECT, a.update(1000 + CURSOR_HOLD_MS, 100f, 100f, 1))
        assertEquals(ChartTouchRole.NAVIGATE, a.update(1000 + CURSOR_HOLD_MS + 40, 100f, 100f, 2))
    }

    @Test fun liftingOneFingerOfAPinchDoesNotHandTheCursorTheOther() {
        val a = arbiter()
        a.down(1000, 100f, 100f)
        a.update(1020, 100f, 100f, 2)
        assertEquals(ChartTouchRole.NAVIGATE, a.update(1200, 300f, 100f, 1))
        assertEquals(ChartTouchRole.NAVIGATE, a.lifted())
    }

    @Test fun withTheCursorSwitchedOffEveryTouchIsNavigationFromTheStart() {
        val a = arbiter(inspectAllowed = false)
        a.down(1000, 100f, 100f)
        assertEquals(ChartTouchRole.NAVIGATE, a.role)
        assertEquals(ChartTouchRole.NAVIGATE, a.update(1000 + CURSOR_HOLD_MS * 5, 100f, 100f, 1))
        assertEquals(ChartTouchRole.NAVIGATE, a.update(1900, 400f, 100f, 1))
        assertEquals(ChartTouchRole.NAVIGATE, a.lifted())
        assertEquals(ChartTouchRole.NAVIGATE, a.held())
    }

    @Test fun theHoldOutlastsTheDeviceMeasuredPinchOnsets() {
        // 72 two-finger gestures were replayed on the Tab S7+ at commanded gaps
        // up to 250 ms and the input path reproduced them to within 3 ms. The
        // hold has to be comfortably above the interval a hand actually
        // produces and comfortably below the platform gestures it must not
        // collide with (double tap 300 ms, long press 500 ms on that device).
        assert(CURSOR_HOLD_MS >= 3 * 40) { "below the platform's 40 ms simultaneity floor" }
        assert(CURSOR_HOLD_MS < 300) { "collides with the double-tap timeout" }
    }
}

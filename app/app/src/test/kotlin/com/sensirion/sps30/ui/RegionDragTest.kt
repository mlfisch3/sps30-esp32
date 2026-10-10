package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection drag's rules, on numbers.
 *
 * The band always runs in time order whichever way the hand went; a selection
 * only starts existing once the finger has plainly moved sideways; and a touch
 * that wandered vertically is neither a selection nor the tap that clears one.
 */
class RegionDragTest {

    private val slop = 15f

    @Test
    fun aStillFingerIsNeitherADragNorTravel() {
        val drag = RegionDrag(slop, 100f, 100f, 50.0)
        drag.update(105f, 103f, 50.5)
        assertFalse(drag.dragged)
        assertFalse(drag.travelled)
        assertEquals(50.0, drag.lo, 0.0)
    }

    @Test
    fun crossingSlopSidewaysStartsTheSelection() {
        val drag = RegionDrag(slop, 100f, 100f, 50.0)
        drag.update(130f, 100f, 53.0)
        assertTrue(drag.dragged)
        assertTrue(drag.travelled)
        assertEquals(50.0, drag.lo, 0.0)
        assertEquals(53.0, drag.hi, 0.0)
    }

    @Test
    fun draggingLeftwardsStillYieldsATimeOrderedBand() {
        val drag = RegionDrag(slop, 100f, 100f, 50.0)
        drag.update(40f, 100f, 44.0)
        assertEquals(44.0, drag.lo, 0.0)
        assertEquals(50.0, drag.hi, 0.0)
    }

    @Test
    fun theBandFollowsTheFingerBackAcrossItsAnchor() {
        val drag = RegionDrag(slop, 100f, 100f, 50.0)
        drag.update(160f, 100f, 56.0)
        drag.update(70f, 100f, 47.0)
        // The band is anchor-to-finger, not the widest it has ever been: that
        // is what lets a drag be adjusted before the finger lifts.
        assertEquals(47.0, drag.lo, 0.0)
        assertEquals(50.0, drag.hi, 0.0)
    }

    @Test
    fun aVerticalWanderTravelsWithoutSelecting() {
        val drag = RegionDrag(slop, 100f, 100f, 50.0)
        drag.update(104f, 160f, 50.4)
        assertFalse("no sideways travel, no selection", drag.dragged)
        assertTrue("but it is no longer a tap either", drag.travelled)
    }
}

package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No two of this screen's holds may be released by the same words.
 *
 * There are three of them now - the whole graph against touch, the y axis at a
 * range the fingers chose, the time axis at a range they framed - and each has a
 * way out. Left to themselves, all three would have been called "Release": it is
 * the obvious word, and each is obvious in isolation. Two chips a few millimetres
 * apart both offering to release something, with only their position to say
 * which, is a control the user has to experiment on, and experimenting costs them
 * the thing they just set up. That collision was avoided once by hand; this is
 * what stops it coming back the next time a hold is added.
 *
 * The positive half matters as much as the negative one. The two axis holds are
 * *meant* to look like each other - they are one idea applied to two axes, and
 * three unrelated-looking controls would be its own kind of confusion - so what
 * is asserted is that they share their shape and differ in what they name.
 */
class AxisHoldFamilyTest {

    /** Everything on this screen that offers to let something go. */
    private val releases = mapOf(
        "the graph lock" to AxisHoldLabels.RELEASE_GRAPH,
        "the y axis" to AxisHoldLabels.RELEASE_Y,
        "the x axis" to AxisHoldLabels.RELEASE_X,
        // Pin's own way out. Not spelled "Release" at all, which is the same
        // decision arrived at earlier and for the same reason.
        "pin" to "Unpin",
    )

    @Test
    fun `no two ways out are worded the same`() {
        val seen = mutableMapOf<String, String>()
        for ((owner, label) in releases) {
            val previous = seen.put(label, owner)
            assertEquals(
                "\"$label\" is what both $previous and $owner say; the user has only " +
                    "their position to tell them apart",
                null,
                previous,
            )
        }
    }

    /** And each of them says what it lets go of, rather than just letting go. */
    @Test
    fun `each way out names its object`() {
        for ((owner, label) in releases) {
            assertTrue(
                "$owner's way out reads \"$label\", which names nothing",
                label.trim().contains(' ') || label == "Unpin",
            )
        }
    }

    /**
     * The two axis holds are the same control twice, so they share their words
     * everywhere except where the object is named.
     */
    @Test
    fun `the two axis holds share their shape`() {
        assertTrue(AxisHoldLabels.RELEASE_Y.startsWith("Release"))
        assertTrue(AxisHoldLabels.RELEASE_X.startsWith("Release"))
        assertEquals("Y axis", AxisHoldLabels.Y_HEADING)
        assertEquals("X axis", AxisHoldLabels.X_HEADING)
        // One lock word between them: the heading beside it is what says which
        // axis it belongs to, and two different verbs would imply two different
        // things happening.
        assertEquals("Lock", AxisHoldLabels.LOCK)
        assertEquals("Locked", AxisHoldLabels.LOCKED)
    }

    /**
     * The graph lock is the odd one out and stays that way: it is not an axis, it
     * takes the whole plot away from touch, and its toggle says so in both states.
     */
    @Test
    fun `the graph lock names the graph in both states`() {
        assertTrue(AxisHoldLabels.LOCK_GRAPH.endsWith("graph"))
        assertTrue(AxisHoldLabels.RELEASE_GRAPH.endsWith("graph"))
        assertTrue(AxisHoldLabels.LOCK_GRAPH != AxisHoldLabels.LOCK)
    }

    /**
     * The sentence under the chips says what is holding the axis, and the two
     * axes are held by different things - autoscale on one, the frozen range on
     * the other - so it must not be the same sentence.
     */
    @Test
    fun `each axis explains its own hold`() {
        assertTrue(AxisHoldLabels.Y_HELD != AxisHoldLabels.X_HELD)
        // Locked reads the same on both, and should: at that point the thing
        // holding the axis really is the lock, on either one.
        assertTrue(AxisHoldLabels.HELD_AGAINST_TOUCH.isNotEmpty())
    }
}

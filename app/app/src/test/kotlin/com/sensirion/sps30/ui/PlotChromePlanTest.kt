package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure plans behind the plot's chrome: where the top strip's text goes,
 * and which ticks carry a label on which row.
 *
 * Both are measured against real plot widths in canvas pixels - the phone
 * portrait plot is ~846 px wide at the captain's density, the tablet's ~2500 -
 * because the faults these prevent are all overprints that only exist at real
 * sizes. Pure functions, so no renderer and no Robolectric.
 */
class PlotChromePlanTest {

    // Phone portrait at density 3: canvas 1008 px, plot 134..980.
    private val phoneLeft = 134f
    private val phoneRight = 980f

    // Measured shapes, approximated in px: the unit mark, the flag, and a
    // long view note ("window 1d 12h · 1305 pts · frozen").
    private val unitW = 130f
    private val flagW = 330f
    private val longInfoW = 700f
    private val shortInfoW = 380f
    private val reserve = 2 * 110f + 16f + 2 * 12f

    @Test
    fun `the flag sits beside the unit mark when it fits`() {
        val plan = topStripPlan(phoneLeft, phoneRight, unitW, flagW, shortInfoW, reserve)
        assertEquals(1, plan.flagLine)
        assertEquals(phoneLeft + 10f + unitW + 24f, plan.flagX)
    }

    @Test
    fun `a view note that would overprint the flag drops to line two`() {
        val plan = topStripPlan(phoneLeft, phoneRight, unitW, flagW, longInfoW, reserve)
        assertEquals("the flag keeps line 1 - it is the caveat", 1, plan.flagLine)
        assertEquals("the view note is the bookkeeping, so it yields", 2, plan.infoLine)
        // And on line 1, nothing overlaps: flag end vs the note's left edge is moot
        // once they are on different lines, but the flag must still clear the icons.
        assertTrue(plan.flagX + plan.flagWidth <= phoneRight - 8f - reserve)
    }

    @Test
    fun `without a flag a short note keeps line one`() {
        val plan = topStripPlan(phoneLeft, phoneRight, unitW, null, shortInfoW, reserve)
        assertEquals(null, plan.flagLine)
        assertEquals(1, plan.infoLine)
    }

    @Test
    fun `a flag too wide for line one goes under the unit not over the note`() {
        // A plot so narrow the flag cannot share line 1 with the unit at all.
        val narrowRight = phoneLeft + unitW + flagW + reserve - 60f
        val plan = topStripPlan(phoneLeft, narrowRight, unitW, flagW, shortInfoW, reserve)
        assertEquals(2, plan.flagLine)
        assertEquals("line 2 starts where the unit does", phoneLeft + 10f, plan.flagX)
        assertTrue("the note must not share line 2 with a flag it would overprint",
            plan.infoLine >= 2)
    }

    @Test
    fun `on the tablet everything shares line one`() {
        val plan = topStripPlan(134f, 2500f, unitW, flagW, longInfoW, reserve)
        assertEquals(1, plan.flagLine)
        assertEquals(1, plan.infoLine)
    }

    // --- the x labels' plan ---------------------------------------------------

    /** Ticks every [pitch] px from 300, phone-plot sized. */
    private fun ticksAt(pitch: Float, n: Int) = FloatArray(n) { 300f + it * pitch }

    @Test
    fun `single row thins by stride exactly as the axis always has`() {
        val xs = ticksAt(80f, 8)
        val widths = FloatArray(xs.size) { 130f }
        val plan = xLabelPlacement(xs, widths, stagger = false, datesInUse = false,
            left = 134f, right = 980f)
        // (130 + 24) / 80 -> stride 2: every second tick labelled, all on the tick row.
        for (i in xs.indices) {
            val expected = if (i % 2 == 0) X_LABEL_TICK_ROW else X_LABEL_SKIP
            assertEquals("tick $i", expected, plan[i])
        }
    }

    @Test
    fun `staggering carries twice the labels of a single row`() {
        val xs = ticksAt(80f, 8)
        val widths = FloatArray(xs.size) { 130f }
        val flat = xLabelPlacement(xs, widths, stagger = false, datesInUse = false,
            left = 134f, right = 980f)
        val staggered = xLabelPlacement(xs, widths, stagger = true, datesInUse = false,
            left = 134f, right = 980f)
        val flatCount = flat.count { it != X_LABEL_SKIP }
        val staggeredCount = staggered.count { it != X_LABEL_SKIP }
        assertEquals("with two rows every tick can carry a label here", xs.size, staggeredCount)
        assertTrue("staggering must beat the single row ($flatCount)",
            staggeredCount >= 2 * flatCount)
        // Unmistakably one sequence: strict alternation between the two rows.
        for (i in xs.indices) {
            assertEquals(if (i % 2 == 0) X_LABEL_TICK_ROW else X_LABEL_DATE_ROW, staggered[i])
        }
    }

    @Test
    fun `a date line in view keeps the second row for the dates`() {
        val xs = ticksAt(80f, 8)
        val widths = FloatArray(xs.size) { 130f }
        val plan = xLabelPlacement(xs, widths, stagger = true, datesInUse = true,
            left = 134f, right = 980f)
        assertTrue("no time label may take the date row while a date needs it",
            plan.none { it == X_LABEL_DATE_ROW })
    }

    @Test
    fun `labels never run under the on-axis controls`() {
        val xs = ticksAt(120f, 7)
        val widths = FloatArray(xs.size) { 130f }
        val left = 256f
        val right = 846f
        val plan = xLabelPlacement(xs, widths, stagger = false, datesInUse = false,
            left = left, right = right)
        for (i in xs.indices) {
            if (plan[i] == X_LABEL_SKIP) continue
            assertTrue("label $i encroaches on the reserved ground",
                xs[i] - widths[i] / 2f >= left && xs[i] + widths[i] / 2f <= right)
        }
        assertTrue("something must still be labelled", plan.any { it != X_LABEL_SKIP })
    }
}

package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the y axis is allowed to do.
 *
 * Two halves, and the split is deliberate.
 *
 * The first half is about which data the axis fits, so it is driven by data:
 * run 1202, the 1,000 readings an ESP32 recorded on 2026-08-03, placed at the
 * times the board's own equivalences say they happened. Same fixture and same
 * loading path as [BoardSessionsTest]; see `src/test/resources/board/README.md`.
 * Nothing here is synthetic, simulated or time-shifted, and no test in this file
 * invents a reading or an instant.
 *
 * The second half is about the *rules* - override, widen, lock, release - which
 * are rules about ranges, not about readings. They are exercised on ranges
 * directly through [ChartView.resolveYRange], because reaching for a history to
 * test them would mean making up data to put in it for no gain in coverage.
 */
class ChartYAxisTest {

    private val session = "SPS30-R000004B2-00000821"

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/board/$name")?.bufferedReader()?.readText()
            ?: throw AssertionError("missing test resource /board/$name")

    private fun rows(): Pair<List<String>, List<List<String>>> {
        val lines = resource("$session.wire.csv").trim('\n').split('\n')
        return lines.first().split(',') to lines.drop(1).map { it.split(',') }
    }

    /** Run 1202 in a history, at the real times the board's anchors give it. */
    private fun placedHistory(): TimeSeriesHistory {
        val basis = TimeBasis()
        val history = TimeSeriesHistory(retentionSeconds = 1_000_000.0).also { it.basis = basis }
        val (columns, data) = rows()
        history.addRows(session, columns, data, explicit = true)
        basis.accept(Messages.parse(resource("time_map.json").trim()) as ServerMessage.TimeMap)
        history.retime()
        return history
    }

    /**
     * The fields an axis may ever be fitted to.
     *
     * Not all four: PM4.0 and PM10 are recorded and exported but never drawn,
     * and the extent is bounded to what can appear on the plot.
     */
    private val fittable = PlottedSeries.FIELDS

    /**
     * The recorded extent of [fields] between two instants, read off the file.
     *
     * Across fields, not one at a time: the axis has to span every curve it
     * draws, so the number to check against is the min of the mins and the max
     * of the maxes. Checking only pm10 hid a genuine mismatch here - pm10's
     * minimum is not the lowest reading on the plot, pm1.0's is.
     */
    private fun extent(
        history: TimeSeriesHistory,
        fields: List<String>,
        from: Double,
        to: Double,
    ): Pair<Double, Double> {
        // Every reading of run 1202 is placed, so a null epoch here would be a
        // fault rather than a filter case - but the extent is still asked of
        // the placed ones only, which is what a y axis is fitted to.
        val values = history.snapshot()
            .filter { val at = it.epoch; at != null && at in from..to }
            .flatMap { s -> fields.mapNotNull { s.values[it] } }
        return values.min() to values.max()
    }

    // --- item 1: the fit follows the view -----------------------------------

    /**
     * The complaint that started this: zoom in and the axis still spans a peak
     * that is no longer on screen, so the curve sits in a thin band.
     */
    @Test
    fun `the fit follows the visible window, not the whole session`() {
        val history = placedHistory()
        val first = history.snapshot().first().epoch!!
        val last = history.latestEpoch()!!
        val view = ChartView()

        view.setXRange(first, last)
        val whole = view.compute(history, last)
        val (wholeLo, wholeHi) = extent(history, fittable, first, last)
        assertEquals(wholeLo, whole.dataYMin!!, 1e-9)
        assertEquals(wholeHi, whole.dataYMax!!, 1e-9)

        // The last two minutes of the same run. The run's peak is its very first
        // reading - the sensor settling - so the tail is quieter by a factor of
        // eight, which is exactly the situation the complaint describes.
        val from = last - 120.0
        val (lateLo, lateHi) = extent(history, fittable, from, last)
        assertTrue(
            "this fixture no longer has a quieter tail; the test needs rewriting",
            lateHi < wholeHi,
        )

        view.setXRange(from, last)
        val zoomed = view.compute(history, last)
        assertEquals(lateLo, zoomed.dataYMin!!, 1e-9)
        assertEquals(lateHi, zoomed.dataYMax!!, 1e-9)
        assertTrue(
            "the axis kept the whole session's peak after zooming in",
            zoomed.yCeil!! < whole.yCeil!!,
        )
    }

    /**
     * The PM cuts are cumulative, so an extent taken over all four is really
     * pm10's. Over this run pm10 reaches 18.77 and PM2.5 - the coarsest series
     * that is ever drawn - only 10.83, so an axis fitted to pm10 wastes nearly
     * half its height on a curve that is not on the plot, and zooming in on time
     * never helps because pm10 is high wherever the others are.
     *
     * Two rules combine here: the axis never reaches for a series that cannot be
     * drawn, and within those it follows the ones the user has switched on.
     */
    @Test
    fun `the fit follows the series being drawn`() {
        val history = placedHistory()
        val first = history.snapshot().first().epoch!!
        val last = history.latestEpoch()!!
        val view = ChartView()
        view.setXRange(first, last)

        val everything = view.compute(history, last)
        val onlyFine = view.compute(history, last, visible = listOf("pm1_0"))

        val (drawableLo, drawableHi) = extent(history, fittable, first, last)
        val (fineLo, fineHi) = extent(history, listOf("pm1_0"), first, last)
        assertEquals(drawableHi, everything.dataYMax!!, 1e-9)
        assertEquals(drawableLo, everything.dataYMin!!, 1e-9)
        assertEquals(fineHi, onlyFine.dataYMax!!, 1e-9)
        assertEquals(fineLo, onlyFine.dataYMin!!, 1e-9)

        // Never pm10's, whatever is switched on.
        val (_, coarseHi) = extent(history, listOf("pm4_0", "pm10"), first, last)
        assertTrue("this run no longer has a coarse peak to ignore", coarseHi > drawableHi)
        assertTrue("an undrawn series is setting the top of the axis", everything.yCeil!! < coarseHi)
        assertTrue(
            "switching PM2.5 off did not tighten the axis",
            onlyFine.yCeil!! < everything.yCeil!! / 2,
        )
    }

    /** Switching a series off is meant to change the axis. */
    @Test
    fun `the fit re-scales when a series is switched off`() {
        val history = placedHistory()
        val last = history.latestEpoch()!!
        val view = ChartView()
        view.setXRange(history.snapshot().first().epoch!!, last)

        val both = view.compute(history, last, visible = listOf("pm1_0", "pm2_5"))
        val fine = view.compute(history, last, visible = listOf("pm1_0"))
        assertTrue(both.dataYMax!! > fine.dataYMax!!)
    }

    /** A margin above the maximum and below the minimum, so nothing looks clipped. */
    @Test
    fun `the fit leaves room above and below`() {
        val (lo, hi) = ChartView.autoYRange(10.0, 20.0)
        assertTrue("no room below the minimum", lo < 10.0)
        assertTrue("no room above the maximum", hi > 20.0)
        // Small: the point is to unclip the trace, not to shrink it.
        assertTrue(lo > 8.0 && hi < 22.0)
    }

    /** Readings cannot be negative, so the axis does not invent room below zero. */
    @Test
    fun `the margin does not push the axis below zero`() {
        assertEquals(0.0, ChartView.autoYRange(0.0, 3.0).first, 1e-9)
    }

    /** Whatever the state, the axis always comes out with a range to draw. */
    @Test
    fun `compute always resolves a range`() {
        val empty = ChartView().compute(TimeSeriesHistory(), 0.0)
        assertNotNull(empty.yFloor)
        assertNotNull(empty.yCeil)
        assertTrue(empty.yFloor!! < empty.yCeil!!)
    }

    // --- items 5 and 6: the setting and the range dialog ---------------------

    /**
     * Turning autoscale off freezes what is on screen, which is what leaves the
     * range dialog holding the values autoscaling had chosen. The old code only
     * seeded a range the first time, so switching it off again restored a range
     * from some earlier session and the plot jumped.
     */
    @Test
    fun `switching autoscale off adopts the range on screen, every time`() {
        val view = ChartView()
        view.yAutoscale(false, 1.0, 3.0)
        assertEquals(ChartView.autoYRange(1.0, 3.0), view.yMin!! to view.yMax!!)

        view.yAutoscale(true)
        view.yAutoscale(false, 10.0, 20.0)
        assertEquals(ChartView.autoYRange(10.0, 20.0), view.yMin!! to view.yMax!!)
    }

    /** And an override in force is what gets adopted, not the fit underneath it. */
    @Test
    fun `switching autoscale off adopts an override rather than the fit`() {
        val view = ChartView()
        view.zoomY(10.0, 2.9, 3.1)
        val overridden = view.resolveYRange(2.9, 3.1)
        view.yAutoscale(false, 2.9, 3.1)
        assertEquals(overridden.first, view.yMin!!, 1e-9)
        assertEquals(overridden.second, view.yMax!!, 1e-9)
    }

    // --- item 7: the temporary override -------------------------------------

    /** A gesture on the axis takes control. The *setting* is untouched. */
    @Test
    fun `a gesture overrides the axis without switching autoscale off`() {
        val view = ChartView()
        assertEquals(YAxisControl.FITTED, view.yControl())

        view.zoomY(4.0, 2.9, 3.1)

        assertTrue("the setting must still say auto", view.yAuto)
        assertEquals(YAxisControl.OVERRIDDEN, view.yControl())
    }

    /**
     * The motivating case. Readings wobbling between 2.9 and 3.1 fill a tightly
     * fitted axis with drama; the user pinches the axis wide so it reads "about
     * 3, wiggling", and the next frame must not undo that.
     */
    @Test
    fun `autoscale does not snap an overridden range back`() {
        val view = ChartView()
        view.zoomY(20.0, 2.9, 3.1)
        val chosen = view.resolveYRange(2.9, 3.1)

        repeat(5) { view.resolveYRange(2.9, 3.1) }

        assertEquals(chosen, view.resolveYRange(2.9, 3.1))
        assertTrue("the range should be far wider than the data", chosen.second > 5.0)
    }

    /**
     * The rule he gave: anything that widens is honoured, nothing narrows back.
     * A full re-fit would drag the bottom up to meet the new top and throw away
     * exactly the room the override existed to keep.
     */
    @Test
    fun `data above the top raises the top and leaves the bottom alone`() {
        val view = ChartView()
        view.zoomY(20.0, 2.9, 3.1)
        val before = view.resolveYRange(2.9, 3.1)

        val after = view.resolveYRange(2.9, 80.0)

        assertEquals("the bottom must not move", before.first, after.first, 1e-9)
        assertTrue("the top must have made room for 80", after.second >= 80.0)
        assertEquals(YAxisControl.OVERRIDDEN, view.yControl())
    }

    /** Symmetrically, data below the bottom lowers the bottom and keeps the top. */
    @Test
    fun `data below the bottom lowers the bottom and leaves the top alone`() {
        val view = ChartView()
        view.panY(-0.5, 20.0, 22.0) // shift the axis up, away from zero
        view.zoomY(2.0, 20.0, 22.0)
        val before = view.resolveYRange(20.0, 22.0)
        assertTrue("this test needs a bottom above zero to lower", before.first > 0.5)

        val after = view.resolveYRange(0.5, 22.0)

        assertEquals("the top must not move", before.second, after.second, 1e-9)
        assertTrue("the bottom must have made room for 0.5", after.first <= 0.5)
    }

    /** And data well inside the range never pulls it in. */
    @Test
    fun `quiet data does not narrow an overridden range`() {
        val view = ChartView()
        view.zoomY(30.0, 3.0, 3.05)
        val before = view.resolveYRange(3.0, 3.05)
        assertEquals(before, view.resolveYRange(3.01, 3.04))
    }

    /** Lock is about a stray finger, so it stops gestures. */
    @Test
    fun `a locked range ignores further gestures`() {
        val view = ChartView()
        view.zoomY(10.0, 2.9, 3.1)
        val before = view.resolveYRange(2.9, 3.1)
        view.setYLocked(true)
        assertEquals(YAxisControl.LOCKED, view.yControl())

        view.panY(0.5, 2.9, 3.1)
        view.zoomY(4.0, 2.9, 3.1)

        assertEquals(before, view.resolveYRange(2.9, 3.1))
    }

    /**
     * But it does not stop data widening it. An axis that hid a reading in order
     * to stay put would be a worse failure than the stray touch lock prevents.
     */
    @Test
    fun `a locked range still widens for data outside it`() {
        val view = ChartView()
        view.zoomY(10.0, 2.9, 3.1)
        view.setYLocked(true)

        val after = view.resolveYRange(2.9, 90.0)

        assertTrue("a locked axis must not hide a reading", after.second >= 90.0)
        assertEquals(YAxisControl.LOCKED, view.yControl())
    }

    /** Release works whether or not the axis was locked. */
    @Test
    fun `release hands the axis back to autoscale, locked or not`() {
        val view = ChartView()
        view.zoomY(10.0, 2.9, 3.1)
        view.releaseYOverride()
        assertEquals(YAxisControl.FITTED, view.yControl())

        view.zoomY(10.0, 2.9, 3.1)
        view.setYLocked(true)
        view.releaseYOverride()
        assertEquals(YAxisControl.FITTED, view.yControl())
        assertFalse(view.yLocked)
    }

    /**
     * Released, the axis is indistinguishable from one that was never touched -
     * the override leaves nothing behind, not even a wider range that happens to
     * still contain the data.
     */
    @Test
    fun `after release the axis fits the data again`() {
        val untouched = ChartView().resolveYRange(2.9, 3.1)

        val view = ChartView()
        view.zoomY(20.0, 2.9, 3.1)
        assertTrue("the gesture did not widen anything", view.resolveYRange(2.9, 3.1) != untouched)
        view.releaseYOverride()

        assertEquals(untouched, view.resolveYRange(2.9, 3.1))
    }

    /** Neither control is offered when neither would mean anything. */
    @Test
    fun `no override, no controls`() {
        val view = ChartView()
        assertEquals(YAxisControl.FITTED, view.yControl())
        assertFalse(view.yOverridden())

        view.yAutoscale(false, 1.0, 2.0)
        assertEquals(YAxisControl.MANUAL, view.yControl())
        assertFalse(view.yOverridden())
    }

    /** An explicit range replaces the override rather than layering over it. */
    @Test
    fun `setting a range by hand clears the override`() {
        val view = ChartView()
        view.zoomY(10.0, 2.9, 3.1)
        view.setYRange(0.0, 50.0)
        assertEquals(YAxisControl.MANUAL, view.yControl())
        assertFalse(view.yAuto)
        assertFalse(view.yOverridden())
    }

    /** Turning the setting back on discards the override too - it is a fresh answer. */
    @Test
    fun `switching autoscale on clears the override`() {
        val view = ChartView()
        view.zoomY(10.0, 2.9, 3.1)
        view.yAutoscale(true)
        assertEquals(YAxisControl.FITTED, view.yControl())
    }

    /**
     * With autoscale off there is nothing to override: the range is already the
     * user's, so a gesture simply edits it and no controls appear.
     */
    @Test
    fun `a gesture with autoscale off edits the manual range in place`() {
        val view = ChartView()
        view.setYRange(2.0, 4.0)
        view.zoomY(2.0, 2.0, 4.0)
        assertEquals(YAxisControl.MANUAL, view.yControl())
        assertEquals(1.0, view.yMin!!, 1e-9)
        assertEquals(5.0, view.yMax!!, 1e-9)
    }

    /** Dragging the axis down carries the curve down with it: the range moves up. */
    @Test
    fun `dragging down moves the range up`() {
        val view = ChartView()
        view.setYRange(0.0, 10.0)
        view.panY(0.5)
        assertEquals(5.0, view.yMin!!, 1e-9)
        assertEquals(15.0, view.yMax!!, 1e-9)
    }

    /** A pinch cannot collapse the axis to nothing. */
    @Test
    fun `the axis cannot be pinched shut`() {
        val view = ChartView()
        view.setYRange(0.0, 10.0)
        repeat(20) { view.zoomY(0.1, null, null) }
        assertTrue(view.yMax!! - view.yMin!! > 0.0)
    }
}

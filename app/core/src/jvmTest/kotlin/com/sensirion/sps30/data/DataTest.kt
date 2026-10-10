package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationsTest {
    @Test fun bareNumberIsSeconds() {
        assertEquals(1800.0, Durations.parse("1800"), 0.0)
        assertEquals(0.5, Durations.parse("0.5"), 0.0)
    }

    @Test fun unitSuffixes() {
        assertEquals(1.0, Durations.parse("1s"), 0.0)
        assertEquals(60.0, Durations.parse("1m"), 0.0)
        assertEquals(3600.0, Durations.parse("1h"), 0.0)
        assertEquals(86400.0, Durations.parse("1d"), 0.0)
        assertEquals(7200.0, Durations.parse("2hours"), 0.0)
    }

    @Test fun combinationsAndSpaces() {
        assertEquals(5400.0, Durations.parse("1h30m"), 0.0)
        assertEquals(8130.0, Durations.parse("2h15m30s"), 0.0)
        assertEquals(43200.0, Durations.parse("12 hours"), 0.0)
        assertEquals(3600.0, Durations.parse("1 H"), 0.0) // case-insensitive
    }

    /** The units past a day, added when the 48 h window cap came out. */
    @Test fun longUnits() {
        assertEquals(4 * 86_400.0, Durations.parse("4d"), 0.0)
        assertEquals(3 * 604_800.0, Durations.parse("3w"), 0.0)
        assertEquals(604_800.0, Durations.parse("1week"), 0.0)
        assertEquals(2_592_000.0, Durations.parse("1M"), 0.0)
        assertEquals(2_592_000.0, Durations.parse("1mo"), 0.0)
        assertEquals(5_184_000.0, Durations.parse("2 months"), 0.0)
        assertEquals(31_536_000.0, Durations.parse("1y"), 0.0)
        assertEquals(63_072_000.0, Durations.parse("2 years"), 0.0)
    }

    /** "1M" is a month and "1m" a minute - the one place case decides. */
    @Test fun capitalMIsAMonthLowercaseMStaysMinutes() {
        assertEquals(2_592_000.0, Durations.parse("1M"), 0.0)
        assertEquals(60.0, Durations.parse("1m"), 0.0)
        // Every other unit stays case-insensitive.
        assertEquals(3600.0, Durations.parse("1H"), 0.0)
        assertEquals(86_400.0, Durations.parse("1D"), 0.0)
        assertEquals(2_592_000.0, Durations.parse("1MO"), 0.0)
    }

    /** The exact spans the cap used to refuse at its boundary. */
    @Test fun theOldCapBoundaryParses() {
        assertEquals(172_800.0, Durations.parse("2d"), 0.0)
        assertEquals(172_800.0, Durations.parse("48h"), 0.0)
        assertEquals(172_800.0, Durations.parse("2880m"), 0.0)
    }

    @Test fun rejectsBadInput() {
        for (bad in listOf("", "   ", "abc", "1x", "1h30", "0", "-5", "1h-2m")) {
            var threw = false
            try { Durations.parse(bad) } catch (e: IllegalArgumentException) { threw = true }
            assertTrue("should reject '$bad'", threw)
        }
    }

    /** A refusal names the token it refused and offers the units that work. */
    @Test fun rejectionSaysWhy() {
        val unknownUnit = runCatching { Durations.parse("5q") }.exceptionOrNull()!!
        assertTrue(unknownUnit.message!!.contains("'q'"))
        assertTrue(unknownUnit.message!!.contains(Durations.UNIT_HINT))
        val unreadable = runCatching { Durations.parse("abc") }.exceptionOrNull()!!
        assertTrue(unreadable.message!!.contains("'abc'"))
        val nonPositive = runCatching { Durations.parse("0h") }.exceptionOrNull()!!
        assertTrue(nonPositive.message!!.contains("positive"))
    }
}

class TimeSeriesHistoryTest {
    private fun r(csv: String?, index: String, second: Int, pm25: Double) =
        com.sensirion.sps30.wire.ServerMessage.Reading(
            csv, index, "2026-07-22T02:00:${"%02d".format(second)}-04:00",
            mapOf("pm1_0" to pm25, "pm2_5" to pm25, "pm4_0" to pm25, "pm10" to pm25),
        )

    @Test fun addAndDedupByCsvAndIndex() {
        val h = TimeSeriesHistory()
        assertTrue(h.add(r("a.csv", "0000001", 1, 1.0)))
        assertFalse("same (csv,index) is a dup", h.add(r("a.csv", "0000001", 1, 9.0)))
        assertEquals(1, h.size)
        // Same index under a DIFFERENT csv is kept (indices restart per file).
        assertTrue(h.add(r("b.csv", "0000001", 2, 2.0)))
        assertEquals(2, h.size)
    }

    @Test fun skipsUnparseableTimestamp() {
        val h = TimeSeriesHistory()
        val bad = com.sensirion.sps30.wire.ServerMessage.Reading("a", "1", "   ", mapOf("pm2_5" to 1.0))
        assertFalse(h.add(bad))
        assertEquals(0, h.size)
    }

    @Test fun windowSlicesByEpochInclusive() {
        val h = TimeSeriesHistory()
        for (s in 0..9) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        val base = TimeSeriesHistory.epochOf("2026-07-22T02:00:00-04:00")!!
        val w = h.window(base + 2, base + 5) // seconds :02..:05 inclusive
        assertEquals(4, w.epochs.size)
        assertEquals(doubleArrayOf(2.0, 3.0, 4.0, 5.0).toList(), w.series["pm2_5"]!!.toList())
    }

    @Test fun retentionTrimsOldPoints() {
        val h = TimeSeriesHistory(retentionSeconds = 3.0)
        for (s in 0..9) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        // latest is :09; cutoff = 09 - 3 = 06, so :06..:09 remain (4 points).
        assertEquals(4, h.size)
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(listOf(6.0, 7.0, 8.0, 9.0), w.series["pm2_5"]!!.toList())
    }

    @Test fun clearEmptiesAndAllowsReadd() {
        val h = TimeSeriesHistory()
        h.add(r("a.csv", "0000001", 1, 1.0))
        h.clear()
        assertEquals(0, h.size)
        assertNull(h.latestEpoch())
        assertTrue("cleared key can be re-added", h.add(r("a.csv", "0000001", 1, 1.0)))
    }

    @Test fun indexOfParsing() {
        assertEquals(7, TimeSeriesHistory.indexOf("0000007"))
        assertEquals(124, TimeSeriesHistory.indexOf("124"))
        assertEquals(0, TimeSeriesHistory.indexOf("  "))
        assertEquals(0, TimeSeriesHistory.indexOf("junk"))
    }

    @Test fun coverageIsPerCsvAndFollowsWhatIsActuallyHeld() {
        val h = TimeSeriesHistory(retentionSeconds = 3.0)
        assertTrue("unknown csv has no coverage (backfill from 0)", h.coverage().isEmpty())
        for (s in 0..9) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        h.add(r("b.csv", "0000002", 2, 2.0)) // older than the horizon; trimmed at once
        // Retention keeps :06..:09 of a.csv, and coverage shrinks with it: this
        // reports what is HELD, not what was ever seen, which is what makes it a
        // fair comparison against the controller's row count.
        val a = h.coverage()["a.csv"]!!
        assertEquals(9, a.contiguousTo)
        assertEquals(4, a.rows)
        assertEquals(a.rows, h.snapshot().count { it.csv == "a.csv" })
        assertNull("a session that has been trimmed away is not held at all",
            h.coverage()["b.csv"])
        h.clear()
        assertTrue(h.coverage().isEmpty())
    }

    @Test fun coverageStopsAtTheFirstGap() {
        // The whole reason it exists: a hole must not be stepped over. Indices
        // 1..3 and 8..9 held means the controller has to be asked from 3.
        val h = TimeSeriesHistory()
        for (s in listOf(1, 2, 3, 8, 9)) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        val c = h.coverage()["a.csv"]!!
        assertEquals(3, c.contiguousTo)
        assertEquals("and the row count says the copy is short", 5, c.rows)
    }

    @Test fun addRowsFoldsBackfillDedupsAndExtendsCoverage() {
        val h = TimeSeriesHistory()
        h.add(r("a.csv", "0000003", 3, 3.0)) // one live row already present
        val columns = listOf("index", "timestamp", "pm1_0", "pm2_5", "pm4_0", "pm10")
        fun row(sec: Int) = listOf(
            "%07d".format(sec), "2026-07-22T02:00:${"%02d".format(sec)}-04:00",
            "$sec.0", "$sec.0", "$sec.0", "$sec.0",
        )
        // Rows :01..:03 - :03 duplicates the live row, so only :01 and :02 are new.
        val added = h.addRows("a.csv", columns, listOf(row(1), row(2), row(3)))
        assertEquals(2, added)
        assertEquals(3, h.size)
        assertEquals(3, h.coverage()["a.csv"]!!.contiguousTo)
        // Folded in time order regardless of arrival.
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(listOf(1.0, 2.0, 3.0), w.series["pm2_5"]!!.toList())
    }

    @Test fun addRowsRejectsPayloadMissingIndexOrTimestamp() {
        val h = TimeSeriesHistory()
        val added = h.addRows("a.csv", listOf("pm2_5"), listOf(listOf("1.0")))
        assertEquals(0, added)
        assertEquals(0, h.size)
    }

    @Test fun excludeFlaggedFiltersWindowAndFirstStableSkipsSpike() {
        val h = TimeSeriesHistory()
        // :00..:02 are a flagged startup spike; :03..:05 are clean readings.
        for (s in 0..2) h.add(r("a.csv", "000000$s", s, 9.0).copy(event = "startup"))
        for (s in 3..5) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        // The session's true start is the first non-flagged point (:03), not :00.
        assertEquals(
            TimeSeriesHistory.epochOf("2026-07-22T02:00:03-04:00"),
            h.firstStableEpoch(),
        )
        val base = TimeSeriesHistory.epochOf("2026-07-22T02:00:00-04:00")!!
        val excl = h.window(base, base + 5, excludeFlagged = true)
        assertEquals(3, excl.epochs.size) // :00..:02 dropped
        assertEquals(listOf(3.0, 4.0, 5.0), excl.series["pm2_5"]!!.toList())
        assertEquals(6, h.window(base, base + 5, excludeFlagged = false).epochs.size)
    }

    @Test fun addRowsMapsEventColumnAsFlagged() {
        val h = TimeSeriesHistory()
        val columns = listOf("index", "timestamp", "pm1_0", "pm2_5", "pm4_0", "pm10", "event")
        fun row(sec: Int, ev: String) = listOf(
            "%07d".format(sec), "2026-07-22T02:00:${"%02d".format(sec)}-04:00",
            "$sec.0", "$sec.0", "$sec.0", "$sec.0", ev,
        )
        h.addRows("a.csv", columns, listOf(row(1, "clean"), row(2, "")))
        val base = TimeSeriesHistory.epochOf("2026-07-22T02:00:00-04:00")!!
        assertEquals(1, h.window(base, base + 5, excludeFlagged = true).epochs.size)
        assertEquals(2, h.window(base, base + 5, excludeFlagged = false).epochs.size)
    }
}

class ChartViewTest {
    private fun reading(second: Int, pm: Double) = com.sensirion.sps30.wire.ServerMessage.Reading(
        "a.csv", "%07d".format(second), "2026-07-22T02:00:${"%02d".format(second)}-04:00",
        mapOf("pm1_0" to pm, "pm2_5" to pm, "pm4_0" to pm, "pm10" to pm),
    )

    @Test fun movingWindowRangeAndSpan() {
        val cv = ChartView(windowSeconds = 120.0)
        val (start, end) = cv.visibleRange(t(1000.0))
        assertEquals(t(880.0), start, 0.0)
        assertEquals(t(1000.0), end, 0.0)
    }

    @Test fun computeReturnsWindowedSeriesAndYExtent() {
        val h = TimeSeriesHistory()
        for (s in 0..9) h.add(reading(s, s.toDouble()))
        val now = TimeSeriesHistory.epochOf("2026-07-22T02:00:09-04:00")!!
        val cv = ChartView(windowSeconds = 5.0) // last 5s -> :04..:09
        val data = cv.compute(h, now)
        assertEquals(6, data.x.size)               // :04..:09 inclusive
        assertEquals(4.0, data.dataYMin!!, 0.0)
        assertEquals(9.0, data.dataYMax!!, 0.0)
        assertEquals(data.xTicks.size, data.xLabels.size)
        assertTrue(data.xLabels.isNotEmpty())
        // A five-second window is ticked in whole seconds, so the labels carry
        // them. See [TimeTicksTest] for how the interval is chosen.
        assertTrue(data.xLabels.all { it.matches(Regex("""\d\d:\d\d:\d\d""")) })
    }

    @Test fun ticksLandOnRoundTimesAtAUniformInterval() {
        // Evenly spaced was always right; where they landed was not. Labels used
        // to sit at the centres of five equal bands, which put them on arbitrary
        // instants - 14:59, 20:20, 01:41 - so the phase is now taken from the
        // clock instead. A tick may fall on the very edge of the range as a
        // result; the renderer leaves out a label that would not fit there rather
        // than shifting it into its neighbour (see [labelFitsInPlot]).
        val h = TimeSeriesHistory()
        for (s in 0..59) h.add(reading(s, 1.0))
        val now = TimeSeriesHistory.epochOf("2026-07-22T02:00:59-04:00")!!
        val cv = ChartView(windowSeconds = 60.0)
        val data = cv.compute(h, now)

        assertTrue("ticks must be inside the range", data.xTicks.all {
            it >= data.start && it <= data.end
        })
        val gaps = data.xTicks.toList().zipWithNext { a, b -> b - a }
        assertTrue("ticks should be evenly spaced", gaps.all { kotlin.math.abs(it - gaps[0]) < 1e-6 })
        // And every one of them on a round boundary of the local day.
        val step = gaps.first().toLong()
        assertTrue("the interval should be a round one", step in TimeTicks.LADDER)
        assertTrue(
            "ticks are not on $step-second boundaries: ${data.xLabels}",
            data.xTicks.all {
                val local = java.time.Instant.ofEpochSecond(it.toLong())
                    .atZone(TimeSeriesHistory.EASTERN).toLocalTime()
                local.toSecondOfDay() % step == 0L
            },
        )
    }

    @Test fun decimationCapsSamplesKeepingEnds() {
        val h = TimeSeriesHistory()
        for (s in 0..59) h.add(reading(s, s.toDouble()))
        val now = TimeSeriesHistory.epochOf("2026-07-22T02:00:59-04:00")!!
        val cv = ChartView(windowSeconds = 100.0)
        val data = cv.compute(h, now, maxPoints = 10)
        assertTrue("decimated to <= 10", data.x.size in 2..10)
        // First and last samples preserved (values equal their second index here).
        assertEquals(0.0, data.series["pm2_5"]!!.first(), 0.0)
        assertEquals(59.0, data.series["pm2_5"]!!.last(), 0.0)
        assertEquals(60, data.sampleCount) // reports pre-decimation count
    }
}

/**
 * A plausible instant for the chart's own exercises, and seconds counted from it.
 *
 * [ChartView] refuses a visible range that begins before this project could have
 * recorded anything, so these tests are anchored on a real one rather than on
 * zero. What they are about is unchanged - a window is 100 s wide whatever year
 * it sits in - and the small numbers below are still that, read through [t].
 */
private const val AXIS_ORIGIN = 1_785_800_000.0

private fun t(seconds: Double) = AXIS_ORIGIN + seconds

class ChartViewInteractionTest {
    @Test fun toggleModePinsAtCurrentLeftEdge() {
        val cv = ChartView(windowSeconds = 120.0)
        assertEquals(ChartMode.PINNED, cv.toggleMode(t(1000.0)))
        assertEquals(t(880.0), cv.pinStart!!, 0.0)
        assertEquals(t(880.0) to t(1000.0), cv.visibleRange(t(1000.0)))
        // Right edge grows with now; left stays pinned.
        assertEquals(t(880.0) to t(1500.0), cv.visibleRange(t(1500.0)))
        assertEquals(ChartMode.MOVING, cv.toggleMode(t(1500.0))) // back to moving
        assertEquals(t(1380.0) to t(1500.0), cv.visibleRange(t(1500.0)))
    }

    @Test fun zoomMovingScalesWindow() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.zoom(0.5, t(1000.0)) // zoom in -> narrower
        assertEquals(50.0, cv.windowSeconds, 0.0)
        cv.zoom(4.0, t(1000.0)) // zoom out
        assertEquals(200.0, cv.windowSeconds, 0.0)
    }

    @Test fun zoomFrozenScalesAboutCenter() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setXRange(t(1000.0), t(2000.0)) // center 1500, half 500
        cv.zoom(0.5, t(9999.0))
        assertEquals(t(1250.0) to t(1750.0), cv.xFixed) // half -> 250 about 1500
    }

    @Test fun zoomPinnedPullsPinTowardNow() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setMode(ChartMode.PINNED, t(1000.0)) // pinStart = 900
        cv.zoom(0.5, t(1000.0))                 // now - (now-pin)*0.5 = 1000 - 50 = 950
        assertEquals(t(950.0), cv.pinStart!!, 0.0)
    }

    @Test fun panMovingFreezesShiftedRange() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pan(-30.0, t(1000.0)) // moving -> freeze (900,1000) shifted by -30
        assertTrue(cv.xFrozen())
        assertEquals(t(870.0) to t(970.0), cv.xFixed)
    }

    @Test fun panPinnedMovesPinStart() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setMode(ChartMode.PINNED, t(1000.0)) // pinStart 900
        cv.pan(50.0, t(1000.0))
        assertEquals(t(950.0), cv.pinStart!!, 0.0)
        assertFalse(cv.xFrozen())
    }

    @Test fun panFrozenShiftsBothEdges() {
        val cv = ChartView()
        cv.setXRange(t(1000.0), t(2000.0))
        cv.pan(100.0, t(9999.0))
        assertEquals(t(1100.0) to t(2100.0), cv.xFixed)
    }

    @Test fun shiftPinEntersPinnedThenMoves() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.shiftPin(-20.0, t(1000.0)) // enters pinned (pinStart 900) then -20 -> 880
        assertEquals(ChartMode.PINNED, cv.mode)
        assertEquals(t(880.0), cv.pinStart!!, 0.0)
    }

    @Test fun xAutoscaleFreezesAndResumes() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.xAutoscale(false, t(1000.0)) // freeze current (900,1000)
        assertTrue(cv.xFrozen())
        assertEquals(t(900.0) to t(1000.0), cv.xFixed)
        cv.xAutoscale(true, t(1000.0))  // resume live
        assertFalse(cv.xFrozen())
    }

    @Test fun yRangeAndAutoscaleReflectInCompute() {
        val cv = ChartView(windowSeconds = 100.0)
        assertTrue(cv.yAuto)
        cv.setYRange(0.0, 25.0)
        assertFalse(cv.yAuto)
        val data = cv.compute(TimeSeriesHistory(), t(1000.0))
        assertFalse(data.yAuto)
        assertEquals(0.0, data.yMin!!, 0.0)
        assertEquals(25.0, data.yMax!!, 0.0)
        cv.yAutoscale(true)
        assertTrue(cv.compute(TimeSeriesHistory(), t(1000.0)).yAuto)
    }

    /**
     * Switching autoscale off freezes the axis **as drawn**, which is the fit
     * including its margin - not the bare data extent.
     *
     * This used to assert the extent itself, and that made the switch move the
     * plot: the trace jumped out to touch the top and bottom of the frame the
     * instant it was flipped. It is also what the range dialog opens with, so
     * the numbers offered there have to be the ones the axis is really using.
     * See [ChartYAxisTest] for the rest of the y rules.
     */
    @Test fun yAutoscaleOffFreezesAtTheRangeOnScreen() {
        val cv = ChartView()
        cv.yAutoscale(false, dataMin = 2.0, dataMax = 8.0)
        assertFalse(cv.yAuto)
        val (lo, hi) = ChartView.autoYRange(2.0, 8.0)
        assertEquals(lo, cv.yMin!!, 0.0)
        assertEquals(hi, cv.yMax!!, 0.0)
        assertTrue("the frozen range must still contain the data", lo < 2.0 && hi > 8.0)
    }

    @Test fun invalidArgumentsThrow() {
        val cv = ChartView()
        assertThrows { cv.zoom(0.0, 1.0) }
        assertThrows { cv.zoom(-1.0, 1.0) }
        assertThrows { cv.setXRange(5.0, 5.0) }
        assertThrows { cv.setYRange(5.0, 1.0) }
        assertThrows { cv.setWindow(0.0) }
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try { block() } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("expected IllegalArgumentException", threw)
    }
}

class PinModeTest {
    @Test fun pinTapFromLiveEntersPinAtLeftEdge() {
        val cv = ChartView(windowSeconds = 100.0)
        assertEquals(ViewMode.LIVE, cv.viewMode())
        cv.pinTap(t(1000.0))
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals(t(900.0), cv.pinStart!!, 0.0) // left edge was now - window
        assertEquals(t(900.0) to t(1000.0), cv.visibleRange(t(1000.0)))
        assertEquals(t(900.0) to t(1500.0), cv.visibleRange(t(1500.0))) // right grows, left pinned
    }

    @Test fun pinTapFromLiveFrozenEntersPinAtFrozenLeftEdge() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pan(-10.0, t(1000.0)) // freeze at (890,990)
        assertEquals(ViewMode.LIVE_FROZEN, cv.viewMode())
        cv.pinTap(t(1000.0))
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals(t(890.0), cv.pinStart!!, 0.0)
    }

    @Test fun pinTapInPinCyclesRecentPinsThenLive() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0))      // enter pin at 900
        cv.goLive(t(1000.0))      // back to live, window 100
        cv.pinTap(t(2000.0))      // enter pin at 1900; history newest-first [1900, 900]
        assertEquals(t(1900.0), cv.pinStart!!, 0.0)
        cv.pinTap(t(2000.0))      // cycle -> 900
        assertEquals(t(900.0), cv.pinStart!!, 0.0)
        cv.pinTap(t(2000.0))      // past the oldest mark: the last stop is Live
        assertEquals(ViewMode.LIVE, cv.viewMode())
        cv.pinTap(t(2000.0))      // and round again to the newest
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals(t(1900.0), cv.pinStart!!, 0.0)
    }

    /**
     * The reason Live is a stop rather than an interception: stepping round the
     * cycle is navigation, so passing through Live must not manufacture a mark.
     * Were it to, one lap of the cycle would flush out the framings the user
     * deliberately chose.
     */
    @Test fun steppingThroughLiveCreatesNoMark() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0)); cv.goLive(t(1000.0))
        cv.pinTap(t(2000.0))                    // marks [1900, 900]
        repeat(6) { cv.pinTap(t(2000.0)) }      // two full laps
        // Still exactly the two deliberate marks, in the same order. (goLive
        // keeps the displayed span as the window, so ask for a clean one back.)
        cv.goLive(t(3000.0))
        cv.applyWindow(100.0, t(3000.0))
        cv.pinTap(t(3000.0))                    // a deliberate entry: marks [2900, 1900, 900]
        assertEquals(t(2900.0), cv.pinStart!!, 0.0)
        cv.setPinCycleCount(3)
        cv.pinTap(t(3000.0)); assertEquals(t(1900.0), cv.pinStart!!, 0.0)
        cv.pinTap(t(3000.0)); assertEquals(t(900.0), cv.pinStart!!, 0.0)
    }

    /** The captain's case: frame a view, zoom into an anomaly, one tap back. */
    @Test fun oneTapReturnsToTheFramingBeforeAZoom() {
        val cv = ChartView(windowSeconds = 600.0)
        cv.pan(-120.0, t(10_000.0))   // frame it by hand: live-frozen at (9280, 9880)
        cv.pinTap(t(10_000.0))        // pin that framing; mark at 9280
        assertEquals(t(9280.0), cv.pinStart!!, 0.0)
        cv.zoom(0.25, t(10_000.0))    // two-finger zoom into an anomaly
        cv.commitManualPin()          // finger up
        assertTrue("the zoom moved the view", cv.pinStart!! != t(9280.0))
        cv.pinTap(t(10_000.0))        // ONE tap
        assertEquals(t(9280.0), cv.pinStart!!, 0.0)
    }

    @Test fun manualPinDragReentersCycleOnCommit() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0)); cv.goLive(t(1000.0))
        cv.pinTap(t(2000.0))          // pin at 1900; set [1900, 900]
        cv.pan(50.0, t(2000.0))       // drag the curve -> pinStart 1950
        assertEquals(t(1950.0), cv.pinStart!!, 0.0)
        cv.commitManualPin()       // gesture end: 1950 becomes the newest cycle entry
        // Cycle length defaults to 2, so the stops are now {1950, 1900} then Live.
        cv.pinTap(t(2000.0))
        assertEquals(t(1900.0), cv.pinStart!!, 0.0)
        cv.pinTap(t(2000.0))
        assertEquals(ViewMode.LIVE, cv.viewMode())
        cv.pinTap(t(2000.0))
        assertEquals("committed dragged pin is the newest stop", t(1950.0), cv.pinStart!!, 0.0)
    }

    @Test fun manualPinDragForgottenWithoutCommit() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0)); cv.goLive(t(1000.0))
        cv.pinTap(t(2000.0))          // pin at 1900; set [1900, 900]
        cv.pan(50.0, t(2000.0))       // drag to 1950 but never commit
        cv.pinTap(t(2000.0))          // cycles the old set only
        assertTrue(cv.pinStart!! == t(900.0) || cv.pinStart!! == t(1900.0))
    }

    /**
     * A scrolling view advances its left edge with every reading, so marking it
     * would manufacture one a second and flush the deliberate ones out.
     */
    @Test fun scrollingNeverCreatesAMark() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0))                 // the one deliberate mark: 900
        cv.pinTap(t(1000.0))                 // only stop left is Live
        assertEquals(ViewMode.LIVE, cv.viewMode())
        // Time passes and the plot scrolls; nothing here is a decision.
        repeat(5) { cv.compute(TimeSeriesHistory(), t(1000.0) + it * 60.0) }
        cv.setPinCycleCount(5)
        cv.pinTap(t(1300.0))                 // back onto the marks, not a new one
        assertEquals(t(900.0), cv.pinStart!!, 0.0)
    }

    /** Panning after the cycle parked at Live is a new framing, so it marks. */
    @Test fun framingByHandAfterLiveMarksThatFraming() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0))          // mark 900
        cv.pinTap(t(1000.0))          // parked at Live
        cv.pan(-40.0, t(1000.0))      // the user frames a view by hand: (860, 960)
        cv.pinTap(t(1000.0))
        assertEquals("the hand-framed left edge is marked", t(860.0), cv.pinStart!!, 0.0)
        cv.setPinCycleCount(5)
        cv.pinTap(t(1000.0))          // and the earlier one is still a tap away
        assertEquals(t(900.0), cv.pinStart!!, 0.0)
    }

    // --- Unpin: the one-press way out of the cycle --------------------------

    /**
     * The complaint: the only way back to not-pinned was the cycle's last stop,
     * so it cost one press per saved mark. Unpin is that stop, reached directly.
     */
    @Test fun unpinLeavesPinInOnePressFromAnywhereInTheCycle() {
        val cv = ChartView(windowSeconds = 100.0)
        // Four deliberate marks, so the cycle is long enough for the difference
        // between one press and several to be the whole point. Only an entry from
        // a hand-framed view makes a mark, hence the trip back to Live each time.
        for (now in listOf(1000.0, 2000.0, 3000.0, 4000.0)) {
            cv.goLive(t(now))
            cv.applyWindow(100.0, t(now))
            cv.pinTap(t(now))
        }
        cv.setPinCycleCount(4)
        // From every stop on the cycle, including the newest and the oldest.
        for (step in 0 until 4) {
            repeat(step) { cv.pinTap(t(5000.0)) }
            assertEquals("stop $step should be in Pin", ViewMode.PIN, cv.viewMode())
            cv.unpin(t(5000.0))
            assertEquals("one press did not leave Pin from stop $step",
                ViewMode.LIVE, cv.viewMode())
            cv.pinTap(t(5000.0)) // back onto the marks for the next round
        }
    }

    /** It lands where the cycle's own Live stop lands, so Pin behaves the same after. */
    @Test fun unpinLandsOnTheCyclesLiveStop() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0))                       // mark 900
        cv.goLive(t(1000.0))
        cv.pinTap(t(2000.0))                       // mark 1900; marks [1900, 900]
        cv.unpin(t(2000.0))
        assertEquals(ViewMode.LIVE, cv.viewMode())
        // Parked at Live, as stepping round would have left it: the next press
        // picks the marks back up rather than manufacturing one from a view
        // nobody framed.
        cv.pinTap(t(2000.0))
        assertEquals(t(1900.0), cv.pinStart!!, 0.0)
    }

    /**
     * The reason he wants the escape hatch: the reference mark can only be set
     * from outside Pin, so reaching outside quickly is the whole point.
     */
    @Test fun theReferenceMarkCanStillBeSetAfterUnpinning() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0))                       // in Pin, where a long press cannot set it
        cv.unpin(t(1000.0))                        // one press back out
        assertEquals(ViewMode.LIVE, cv.viewMode())
        cv.applyWindow(50.0, t(1000.0))            // a clean live window: 950..1000
        cv.pinLongPress(t(1000.0))                 // sets the reference, does not pin
        assertEquals("setting the reference must not enter Pin", ViewMode.LIVE, cv.viewMode())
        assertTrue(cv.compute(TimeSeriesHistory(), t(1000.0)).hasReferencePin)
        // And it is the origin a long press in Pin then snaps back to.
        cv.pinTap(t(1000.0))
        assertEquals(t(950.0), cv.pinStart!!, 0.0)
        cv.pan(-30.0, t(1000.0))                   // drag the framing away from it
        assertEquals(t(920.0), cv.pinStart!!, 0.0)
        cv.pinLongPress(t(1000.0))
        assertEquals("the reference mark was not honoured", t(950.0), cv.pinStart!!, 0.0)
    }

    /** Unpin from a view that was never pinned is simply a no-op on the marks. */
    @Test fun unpinningFromLiveKeepsTheMarksIntact() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0))                       // mark 900
        cv.unpin(t(1000.0))
        cv.unpin(t(1000.0))                        // again, from Live
        assertEquals(ViewMode.LIVE, cv.viewMode())
        cv.pinTap(t(1000.0))
        assertEquals("the mark was lost", t(900.0), cv.pinStart!!, 0.0)
    }

    @Test fun cycleStopsAreShownOnlyWhenThereIsACycle() {
        val cv = ChartView(windowSeconds = 100.0)
        assertEquals("live, never pinned: nothing to show", 0, cv.pinCycleStops())
        cv.pinTap(t(1000.0))
        assertEquals("one mark is the plain toggle", 0, cv.pinCycleStops())
        cv.goLive(t(1000.0))
        cv.pinTap(t(2000.0))          // two marks: two stops plus Live
        assertEquals(3, cv.pinCycleStops())
        assertEquals(0, cv.pinCycleAt())
        cv.pinTap(t(2000.0))
        assertEquals(1, cv.pinCycleAt())
        cv.pinTap(t(2000.0))          // the Live stop is the last index
        assertEquals(3, cv.pinCycleStops())
        assertEquals(2, cv.pinCycleAt())
        cv.pan(-5.0, t(2000.0))       // off the cycle entirely
        assertEquals(0, cv.pinCycleStops())
    }

    @Test fun applyAllShowsWholeSessionThenClearsOnNavigation() {
        val cv = ChartView(windowSeconds = 60.0)
        cv.applyAll(t(1000.0), t(1500.0))
        assertEquals(ViewMode.ALL, cv.viewMode())
        assertEquals(t(1000.0) to t(1500.0), cv.visibleRange(t(1500.0)))
        assertEquals(t(1000.0) to t(2000.0), cv.visibleRange(t(2000.0))) // left pinned, right grows
        cv.goLive(t(2000.0))                                       // any nav leaves ALL
        assertEquals(ViewMode.LIVE, cv.viewMode())
    }

    @Test fun pinCycleCountLimitsCycle() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setPinCycleCount(1) // one mark, so the button is the plain Pin/Live toggle
        cv.pinTap(t(1000.0))
        cv.goLive(t(1000.0))
        cv.pinTap(t(2000.0))      // pin at 1900
        assertEquals(t(1900.0), cv.pinStart!!, 0.0)
        cv.pinTap(t(2000.0))      // the only other stop is Live; 900 stays out of reach
        assertEquals(ViewMode.LIVE, cv.viewMode())
        assertEquals(0, cv.pinCycleStops())
        cv.pinTap(t(2000.0))      // and back to the one mark it does keep
        assertEquals(t(1900.0), cv.pinStart!!, 0.0)
    }

    /**
     * In Pin mode a window size means "that much time, beginning at the pin".
     * It used to re-pin at now - w instead, which discarded the mark the user
     * had just placed in favour of "w ago" - a moment nobody chose. The full
     * contract, clamps and all, lives in PinWindowTest.
     */
    @Test fun windowButtonInPinFramesForwardFromThePin() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0))               // pin at 900
        cv.applyWindow(50.0, t(2000.0))    // 50 s, beginning at the pin
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals("the pin must survive the size tap", t(900.0), cv.pinStart!!, 0.0)
        assertEquals(t(900.0) to t(950.0), cv.visibleRange(t(2000.0)))
    }

    @Test fun windowButtonInLiveEntersCleanLive() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pan(-5.0, t(1000.0))            // live-frozen
        cv.applyWindow(300.0, t(1000.0))   // clean live, window 300
        assertEquals(ViewMode.LIVE, cv.viewMode())
        assertEquals(300.0, cv.windowSeconds, 0.0)
        assertEquals(t(700.0) to t(1000.0), cv.visibleRange(t(1000.0)))
    }

    @Test fun goLiveCapturesDisplayedSpanAsWindow() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setXRange(t(940.0), t(1000.0))     // frozen span 60
        cv.goLive(t(1000.0))
        assertEquals(ViewMode.LIVE, cv.viewMode())
        assertEquals(60.0, cv.windowSeconds, 0.0)
    }

    @Test fun longPressInLiveSetsReferenceOnlyNoModeSwitch() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinLongPress(t(1000.0))
        assertEquals(ViewMode.LIVE, cv.viewMode()) // did NOT switch to pin
        assertEquals(t(900.0), cv.referencePinTime!!, 0.0)
    }

    @Test fun longPressInPinSnapsToReference() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinLongPress(t(1000.0))   // set reference = 900 while live
        cv.pinTap(t(3000.0))         // enter pin at 2900
        assertEquals(t(2900.0), cv.pinStart!!, 0.0)
        cv.pinLongPress(t(3000.0))   // snap to reference
        assertEquals(t(900.0), cv.pinStart!!, 0.0)
    }

    /**
     * The global mark is a separate thing from the recent-mark cycle and must
     * stay that way: same gesture, meaning set-it while scrolling and go-to-it
     * while pinned, whatever the cycle is doing.
     */
    @Test fun theGlobalMarkSurvivesTheCycle() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinLongPress(t(5000.0))                      // scroll mode: sets the global mark, at 4900
        assertEquals(t(4900.0), cv.referencePinTime!!, 0.0)
        assertEquals("setting it does not enter pin mode", ViewMode.LIVE, cv.viewMode())

        cv.pinTap(t(6000.0))                            // mark 5900
        cv.goLive(t(6000.0))
        cv.applyWindow(100.0, t(7000.0))                // a clean 100 s live window again
        cv.pinTap(t(7000.0))                            // mark 6900; stops are [6900, 5900, Live]
        cv.pinTap(t(7000.0)); assertEquals(t(5900.0), cv.pinStart!!, 0.0)
        cv.pinTap(t(7000.0)); assertEquals(ViewMode.LIVE, cv.viewMode())  // the Live stop
        cv.pinTap(t(7000.0))                            // and round to the newest mark again
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals(t(6900.0), cv.pinStart!!, 0.0)

        // Untouched by any of that: in pin mode the long press still snaps to
        // the global mark, which is no stop on the cycle.
        cv.pinLongPress(t(7000.0))
        assertEquals(t(4900.0), cv.pinStart!!, 0.0)
        assertEquals(t(4900.0), cv.referencePinTime!!, 0.0)
    }

    @Test fun longPressInPinWithNoReferenceIsNoop() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(t(1000.0)) // pin at 900, no reference set
        cv.pinLongPress(t(1000.0))
        assertEquals(t(900.0), cv.pinStart!!, 0.0)
    }
}

/**
 * Guards the extraction path that made dragging a day-long chart lag: decimation
 * now happens *inside* [TimeSeriesHistory.window], so cost and allocation must stay
 * flat in history size while the emitted series stays correct.
 */
class WindowDecimationTest {
    private fun history(n: Int, retention: Double = 1e9): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = retention)
        for (i in 0 until n) {
            h.add("a.csv", "$i", isoAt(i), pm(i.toDouble()))
        }
        return h
    }

    /** All four PM series at the same value - the y scan covers every field,
     *  and an absent field reads as 0.0. */
    private fun pm(value: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { value }

    /** Epoch base + [second], as a wire-format Eastern timestamp. */
    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(BASE + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    @Test fun keepsFirstAndLastAndRespectsBudget() {
        val h = history(1000)
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, maxPoints = 10)
        assertTrue("within budget, got ${w.epochs.size}", w.epochs.size <= 10)
        assertEquals(0.0, w.series["pm2_5"]!!.first(), 0.0)
        assertEquals(999.0, w.series["pm2_5"]!!.last(), 0.0)
        assertEquals("totalCount reports pre-decimation size", 1000, w.totalCount)
    }

    @Test fun everySeriesIsDecimatedInLockstep() {
        val h = history(500)
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, maxPoints = 25)
        for (field in com.sensirion.sps30.wire.Wire.PM_FIELDS) {
            assertEquals(w.epochs.size, w.series[field]!!.size)
        }
        // pm2_5 == the point ordinal, so values must match the emitted epochs.
        val pm = w.series["pm2_5"]!!
        for (i in w.epochs.indices) assertEquals(w.epochs[i] - BASE, pm[i], 0.0)
    }

    @Test fun yRangeCoversTheWholeWindowNotJustEmittedPoints() {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until 101) {
            // A lone spike at :50, which a stride of 100 would step straight over.
            val v = if (i == 50) 999.0 else 1.0
            h.add("a.csv", "$i", isoAt(i), pm(v))
        }
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, maxPoints = 2)
        assertEquals(2, w.epochs.size)
        assertFalse("spike was decimated away", w.series["pm2_5"]!!.any { it == 999.0 })
        assertEquals("but autoscale still sees it", 999.0, w.yMax!!, 0.0)
        assertEquals(1.0, w.yMin!!, 0.0)
    }

    @Test fun budgetInteractsWithFlagFilter() {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until 100) {
            val event = if (i < 40) "settle" else ""
            h.add("a.csv", "$i", isoAt(i), pm(i.toDouble()), event)
        }
        val w = h.window(
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            excludeFlagged = true, maxPoints = 10,
        )
        assertEquals("60 unflagged points", 60, w.totalCount)
        assertTrue(w.epochs.size <= 10)
        assertEquals("first unflagged", 40.0, w.series["pm2_5"]!!.first(), 0.0)
        assertEquals(99.0, w.series["pm2_5"]!!.last(), 0.0)
        assertEquals("y range skips flagged points", 40.0, w.yMin!!, 0.0)
    }

    @Test fun undecimatedWhenNoBudgetGiven() {
        val h = history(300)
        assertEquals(300, h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).epochs.size)
    }

    @Test fun outOfOrderBackfillIsSortedNotQuadratic() {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        // Live data first, then a backfilled file that predates all of it.
        for (i in 500 until 600) h.add("a.csv", "$i", isoAt(i), pm(i.toDouble()))
        val columns = listOf("index", "timestamp") + com.sensirion.sps30.wire.Wire.PM_FIELDS
        val rows = (0 until 500).map { i ->
            listOf("$i", isoAt(i)) + com.sensirion.sps30.wire.Wire.PM_FIELDS.map { "${i.toDouble()}" }
        }
        assertEquals(500, h.addRows("a.csv", columns, rows))

        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(600, w.epochs.size)
        for (i in 1 until w.epochs.size) {
            assertTrue("epochs must stay ascending at $i", w.epochs[i] >= w.epochs[i - 1])
        }
        assertEquals(0.0, w.series["pm2_5"]!!.first(), 0.0)
        assertEquals(599.0, w.series["pm2_5"]!!.last(), 0.0)
    }

    /**
     * The actual regression guard. 48h at 1Hz is the retention ceiling, and the
     * chart recomputes on every gesture flush, so a full-window compute has to stay
     * in single-digit milliseconds. Pre-fix this materialised ~170k points x 4
     * fields through a hash lookup each and took orders of magnitude longer.
     */
    @Test fun fullWindowComputeStaysFastOnTwoDaysOfData() {
        val n = 172_800
        val h = history(n)
        assertEquals(n, h.size)
        val cv = ChartView(windowSeconds = 300.0)
        cv.applyAll(BASE.toDouble(), (BASE + n).toDouble())

        repeat(3) { cv.compute(h, (BASE + n).toDouble(), maxPoints = 1200) } // warm up JIT
        val started = System.nanoTime()
        val iterations = 20
        repeat(iterations) { cv.compute(h, (BASE + n).toDouble(), maxPoints = 1200) }
        val perComputeMs = (System.nanoTime() - started) / 1e6 / iterations

        val data = cv.compute(h, (BASE + n).toDouble(), maxPoints = 1200)
        assertTrue("decimated to <= 1200, got ${data.x.size}", data.x.size <= 1200)
        assertEquals(n, data.sampleCount)
        assertEquals((n - 1).toDouble(), data.dataYMax!!, 0.0)
        assertTrue(
            "full-window compute took %.1f ms/call over %d points".format(perComputeMs, n),
            perComputeMs < 50.0,
        )
    }

    private companion object {
        const val BASE = 1_770_000_000L
    }
}

/**
 * Averaging: the mechanism behind both the manual "avg" buttons and Auto Set.
 * Thinning by dropping samples would discard exactly the excursions worth
 * seeing, so buckets average instead - a spike must raise its bucket, not vanish.
 */
class PlotAveragingTest {
    private fun pm(v: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { v }

    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(BASE + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    private fun history(n: Int, value: (Int) -> Double): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until n) h.add("a.csv", "$i", isoAt(i), pm(value(i)))
        return h
    }

    @Test fun bucketsAverageRatherThanDrop() {
        // 0..59 in 10s buckets: means are 4.5, 14.5, 24.5, 34.5, 44.5, 54.5.
        val h = history(60) { it.toDouble() }
        val w = h.window(
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, bucketSeconds = 10.0,
        )
        assertEquals(6, w.epochs.size)
        val pm = w.series["pm2_5"]!!
        assertEquals(4.5, pm.first(), 1e-9)
        assertEquals(54.5, pm.last(), 1e-9)
    }

    @Test fun aSpikeSurvivesAveraging() {
        // One 500 spike among 1.0s. Dropping samples could step over it; the
        // bucket mean must still rise well clear of baseline.
        val h = history(60) { if (it == 33) 500.0 else 1.0 }
        val w = h.window(
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, bucketSeconds = 10.0,
        )
        val pm = w.series["pm2_5"]!!
        assertTrue("spike bucket should stand out, got ${pm.toList()}", pm.any { it > 40.0 })
        assertEquals("and autoscale must cover it", pm.max(), w.yMax!!, 1e-9)
    }

    @Test fun bucketsAreAlignedToAbsoluteTimeNotWindowStart() {
        // Same data, two different window starts -> identical bucket timestamps,
        // so points do not shuffle as the user pans.
        val h = history(120) { it.toDouble() }
        val a = h.window(BASE + 0.0, BASE + 119.0, bucketSeconds = 30.0)
        val b = h.window(BASE + 7.0, BASE + 119.0, bucketSeconds = 30.0)
        val shared = a.epochs.toList().intersect(b.epochs.toList().toSet())
        assertTrue("expected shared bucket centres, got ${a.epochs.toList()} vs ${b.epochs.toList()}",
            shared.size >= 3)
    }

    @Test fun rawWhenNoBucketRequested() {
        val h = history(50) { it.toDouble() }
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(50, w.epochs.size)
    }

    @Test fun autoIntervalFollowsTheVisibleSpanAndDecaysOnZoomIn() {
        val h = history(20_000) { 5.0 }
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()

        // Whole session on screen: averaging must kick in.
        cv.applyAll(BASE.toDouble(), (BASE + 20_000).toDouble())
        val wide = cv.compute(h, (BASE + 20_000).toDouble(), maxPoints = 600)
        assertTrue("expected averaging over a 20k-point span", wide.bucketSeconds != null)
        assertTrue(wide.autoInterval)

        // Zoomed right in: few enough points that averaging stops helping, and it
        // must fall away by itself without the user knowing it was ever on.
        cv.applyWindow(120.0, (BASE + 20_000).toDouble())
        val near = cv.compute(h, (BASE + 20_000).toDouble(), maxPoints = 600)
        assertNull("averaging should decay to raw when zoomed in", near.bucketSeconds)
    }

    @Test fun pickingAnIntervalByHandEndsAutoMode() {
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        assertTrue(cv.autoInterval)
        cv.setPlotInterval(30.0)
        assertFalse(cv.autoInterval)
        assertEquals(30.0, cv.plotIntervalSeconds!!, 0.0)
        // "raw" clears the bucket entirely.
        cv.setPlotInterval(null)
        assertNull(cv.plotIntervalSeconds)
    }

    private companion object {
        const val BASE = 1_770_000_000L
    }
}

/**
 * Auto works out the averaging the data actually wants, then hands it to the UI
 * as a button of its own. Constrained to a fixed ladder it kept landing one step
 * short of enough smoothing with the next step far too much.
 */
class AutoIntervalChoiceTest {
    private fun pm(v: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { v }

    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(1_770_000_000L + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    private fun history(n: Int): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until n) h.add("a.csv", "$i", isoAt(i), pm(5.0))
        return h
    }

    @Test fun autoPicksAWholeNumberOfSeconds() {
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        val h = history(20_000)
        cv.applyAll(1_770_000_000.0, 1_770_020_000.0)
        val bucket = cv.compute(h, 1_770_020_000.0, maxPoints = 600).bucketSeconds
        assertTrue("expected averaging over a 20k-point span", bucket != null)
        assertEquals("fractional seconds are noise in a button label",
            bucket!!, Math.floor(bucket), 0.0)
        assertTrue(bucket >= 2.0)
    }

    @Test fun autoSizesTheBucketToTheSpan() {
        val h = history(20_000)
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        cv.applyAll(1_770_000_000.0, 1_770_020_000.0)
        val wide = cv.compute(h, 1_770_020_000.0, maxPoints = 600).bucketSeconds!!
        // Roughly span/budget, not an arbitrary step off a ladder.
        assertEquals(20_000.0 / 600, wide, 2.0)
    }

    @Test fun autoFallsToRawWhenEverySampleAlreadyFits() {
        // 60s of 1Hz data against a 600-point budget: nothing to average away,
        // so auto must leave the data alone rather than smoothing it anyway.
        val cv = ChartView(windowSeconds = 60.0)
        cv.setAutoInterval()
        val h = history(120)
        cv.applyWindow(60.0, 1_770_000_120.0)
        val data = cv.compute(h, 1_770_000_120.0, maxPoints = 600)
        assertNull(data.bucketSeconds)
        assertNull("and offers no button for a value it did not pick", data.autoChosenSeconds)
    }

    @Test fun theChosenValueSurvivesSwitchingToRaw() {
        // The whole point of surfacing it as a button: raw and "what auto
        // decided" have to stay one tap apart.
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        val h = history(20_000)
        cv.applyAll(1_770_000_000.0, 1_770_020_000.0)
        val chosen = cv.compute(h, 1_770_020_000.0, maxPoints = 600).autoChosenSeconds
        assertTrue(chosen != null)

        cv.setPlotInterval(null) // user taps raw
        val raw = cv.compute(h, 1_770_020_000.0, maxPoints = 600)
        assertNull(raw.bucketSeconds)
        assertEquals("the button must still be offered", chosen, raw.autoChosenSeconds)

        cv.setPlotInterval(chosen) // user taps it back
        assertEquals(chosen, cv.compute(h, 1_770_020_000.0, maxPoints = 600).bucketSeconds)
    }
}

/**
 * A y axis has to carry labelled gridlines whatever the data happens to span.
 * Rounding the labels to whole numbers made that conditional: a range sitting
 * between two integers - 1.2 to 1.8 - contained no labelled value at all, and
 * the axis drew with no lines and no numbers.
 */
class YAxisRangeTest {
    private fun pm(v: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { v }

    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(1_770_000_000L + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    /** Values the drawing code would label, for a given data extent. */
    private fun labelledValues(lo: Double, hi: Double): List<Double> {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        h.add("a.csv", "0", isoAt(0), pm(lo))
        h.add("a.csv", "1", isoAt(1), pm(hi))
        val cv = ChartView(windowSeconds = 60.0)
        val d = cv.compute(h, 1_770_000_060.0)
        // Mirrors PmChart: round steps from the (padded) data extent.
        val min = d.dataYMin!!
        val max = d.dataYMax!!
        val step = maxOf(1.0, listOf(1.0, 2.0, 5.0, 10.0)
            .map { it * Math.pow(10.0, Math.floor(Math.log10(((max - min) / 4).coerceAtLeast(1e-9)))) }
            .firstOrNull { it >= (max - min) / 4 } ?: 1.0)
        val bottom = Math.floor(min / step) * step
        val top = maxOf(Math.ceil(max / step) * step, bottom + step * 2)
        return generateSequence(bottom) { it + step }.takeWhile { it <= top + 1e-9 }.toList()
    }

    @Test fun aRangeBetweenTwoIntegersStillGetsLabels() {
        val labels = labelledValues(1.2, 1.8)
        assertTrue("expected at least two labelled lines, got $labels", labels.size >= 2)
        assertTrue("labels should be whole numbers, got $labels",
            labels.all { it == Math.floor(it) })
    }

    @Test fun aFlatReadingStillGetsLabels() {
        val labels = labelledValues(1.5, 1.5)
        assertTrue("a flat trace still needs an axis, got $labels", labels.size >= 2)
    }

    @Test fun aWideRangeUsesRoundStepsNotHundredsOfLines() {
        val labels = labelledValues(0.0, 300.0)
        assertTrue("expected a handful of lines, got ${labels.size}", labels.size in 3..12)
        assertTrue(labels.all { it == Math.floor(it) })
    }
}

package com.sensirion.sps30.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A window far wider than the old 48 h cap still renders as a chart, not as a
 * hang or a smear.
 *
 * The cap's removal leans on three bounds that already existed, and this pins
 * each of them at the scale the cap used to forbid:
 *
 *  - the point budget: `compute` never emits more points than [ChartView]'s
 *    caller budgets, whatever the span holds;
 *  - auto averaging: the bucket grows with the span, so a year is drawn as a
 *    readable curve rather than every raw sample;
 *  - the axis: [TimeTicks] extends past its ladder in whole four-week blocks
 *    and hard-caps the count, so a huge span cannot spin the tick walk.
 */
class LongWindowTest {

    /** Late 2027: far enough past the axis's plausibility floor (2026-01-01,
     *  [ChartView.EARLIEST_PLAUSIBLE_EPOCH]) that a whole year is plausible. */
    private val now = 1_830_000_000.0

    /** [days] of duty-cycled readings, one per 15 min, ending at [now]. */
    private fun history(days: Int): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = 1e9, nowSeconds = { now })
        val step = 900
        val count = days * 96
        for (i in 0 until count) {
            val at = now - (count - 1 - i).toDouble() * step
            h.add(
                csv = "long.csv",
                index = i.toString(),
                timestamp = Instant.ofEpochSecond(at.toLong()).toString(),
                values = mapOf("pm2_5" to 5.0 + (i % 7) * 0.5),
            )
        }
        return h
    }

    @Test fun aYearWindowStaysInsideThePointBudget() {
        val year = 365 * 86_400.0
        val hist = history(days = 60)
        val view = ChartView()
        view.applyWindow(year, now)
        // The size asked for is the size held - no clamp back under 48 h.
        assertEquals(year, view.windowSeconds, 0.0)

        view.setAutoInterval()
        val budget = 902
        val data = view.compute(hist, now, maxPoints = budget, visible = listOf("pm2_5"))

        assertTrue("a year window drew nothing", data.x.isNotEmpty())
        assertTrue(
            "a year window emitted ${data.x.size} points, past the $budget budget",
            data.x.size <= budget,
        )
        // Auto picked a bucket sized to the span: at least span/budget, and
        // far coarser than raw - a year of raw samples is not a curve.
        val bucket = view.autoChosenSeconds
        assertTrue("auto chose no bucket over a year window", bucket != null)
        assertTrue(
            "auto chose ${bucket}s, finer than the ${ceil(year / budget)}s the span needs",
            bucket!! >= year / budget,
        )
        // The y extent fitted what was drawn, not garbage.
        val yMin = data.dataYMin
        val yMax = data.dataYMax
        assertTrue(
            "a year window fitted y to $yMin..$yMax",
            yMin != null && yMax != null && yMin.isFinite() && yMax.isFinite() && yMax >= yMin,
        )
    }

    /**
     * The one floor that remains, pinned so its removal is a decision: a window
     * reaching before the project's dawn is clamped, not refused - the user
     * gets the newest plausible span, and the clamp retires on its own as the
     * record ages past a year.
     */
    @Test fun aWindowReachingBeforeTheDawnClampsToThePlausibleSpan() {
        val year = 365 * 86_400.0
        // Mid-2026: less than a year after the plausibility floor.
        val recentNow = ChartView.EARLIEST_PLAUSIBLE_EPOCH + 120 * 86_400.0
        val view = ChartView()
        view.applyWindow(year, recentNow)
        assertEquals(year, view.windowSeconds, 0.0)
        val data = view.compute(history(days = 0), recentNow, visible = listOf("pm2_5"))
        assertEquals(ChartView.EARLIEST_PLAUSIBLE_EPOCH, data.start, 1.0)
        assertEquals(recentNow, data.end, 1.0)
    }

    @Test fun theAxisStaysBoundedAtAnySpan() {
        val zone = ZoneId.of("America/New_York")
        for (spanDays in listOf(2, 60, 365, 3_650)) {
            val span = spanDays * 86_400.0
            val step = TimeTicks.interval(span)
            val ticks = TimeTicks.ticks(now - span, now, zone, step)
            assertTrue("$spanDays days produced no ticks", ticks.isNotEmpty())
            assertTrue(
                "$spanDays days produced ${ticks.size} ticks - unreadable and slow",
                ticks.size <= 2 * TimeTicks.TARGET_TICKS,
            )
        }
    }
}

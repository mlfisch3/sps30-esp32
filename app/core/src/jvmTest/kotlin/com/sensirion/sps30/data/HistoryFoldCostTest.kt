package com.sensirion.sps30.data

import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding a frame of readings into a full plot costs the frame, not the plot.
 *
 * ## The fault this exists for
 *
 * On 2026-08-12 the app on the captain's tablet burned one whole coroutine
 * thread and garbage-collected about 250 times a minute, continuously, for as
 * long as the dashboard was on screen - 30-80 MB reclaimed roughly four times a
 * second, including large-object allocations of megabyte-scale arrays. The
 * window chips, the tick-density control and the options toggles all did
 * nothing when pressed and the PM readouts stood still for ten minutes, while
 * every control the chart owns itself went on answering: the model's single
 * dispatcher was saturated, so everything routed through it was queued behind
 * work that never ended.
 *
 * The cause was here. A `file_data` frame is fourteen readings; when any of
 * them landed out of time order, [TimeSeriesHistory.addRows] re-sorted the
 * **whole** buffer, and the re-sort allocated a fresh copy of every column -
 * eleven arrays at the buffer's capacity, about 30 MB once the plot holds its
 * cap of readings. Frames arrive many times a second. Nothing was wrong with
 * the answer; the only thing wrong was what one frame cost.
 *
 * ## Why the existing tests could not see it
 *
 * Every other test here folds tens of rows into tens of rows, where the same
 * code path costs microseconds and allocates kilobytes, and the dashboard's own
 * `DashboardControlsPressTest` presses the real controls against a history of
 * that size - so a press lands, the model answers, and the test is green. Cost
 * against history length is a dimension none of them has. That is what this
 * measures, and it is why it measures *allocation* rather than wall time:
 * allocation is what the garbage collector was answering, and it is the one
 * figure that does not depend on how busy the machine running the test is.
 *
 * Both cases below fail on the pre-fix implementation by two orders of
 * magnitude and pass comfortably after it.
 */
class HistoryFoldCostTest {

    private val columns =
        listOf("index", "timestamp", "pm1_0", "pm2_5", "pm4_0", "pm10")

    /** A row [sec] seconds after the epoch below, carrying [sec] as its value. */
    private fun row(sec: Int): List<String> {
        val stamp = java.time.Instant.ofEpochSecond(BASE + sec)
            .atZone(java.time.ZoneId.of("America/New_York"))
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val v = (sec % 50).toDouble() + 1.0
        return listOf("%07d".format(sec), stamp, "$v", "$v", "$v", "$v")
    }

    /** A plot holding [n] readings, folded in cheaply and in order. */
    private fun plotOf(n: Int): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = Double.MAX_VALUE)
        var sec = 0
        while (sec < n) {
            val batch = (sec until minOf(sec + 500, n)).map { row(it) }
            h.addRows("big.csv", columns, batch, explicit = true)
            sec += batch.size
        }
        return h
    }

    /** Bytes this thread allocated while running [body]. */
    private fun allocatedBy(body: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        // One warm-up: the scratch buffers are sized on first use by design, and
        // charging their one-off allocation to a steady-state frame would be
        // measuring the wrong thing.
        body()
        val before = bean.getThreadAllocatedBytes(id)
        body()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test fun aFrameLandingLateCostsTheFrameAndNotThePlot() {
        val h = plotOf(POINTS)
        assertEquals(POINTS, h.size)
        var late = POINTS
        val bytes = allocatedBy {
            // A frame whose rows are individually out of order - the shape that
            // set `outOfOrder` and re-sorted everything - appended at the end of
            // a full plot, which is where live and backfilled rows arrive.
            val frame = listOf(late + 3, late + 1, late + 4, late + 2, late).map(::row)
            h.addRows("big.csv", columns, frame, explicit = true)
            late += 10
        }
        assertTrue(
            "folding a frame into a $POINTS-point plot allocated $bytes bytes; " +
                "the whole-plot re-sort this replaced allocated tens of megabytes",
            bytes < BUDGET,
        )
    }

    @Test fun aFrameOfOlderReadingsCostsNoAllocationEither() {
        val h = plotOf(POINTS)
        // A gap fill: readings from the middle of the plot, arriving now. This
        // is the case that genuinely has to move data, and it still may not
        // allocate a copy of the plot to do it.
        var old = POINTS / 2
        val bytes = allocatedBy {
            val frame = listOf(old + 3, old + 1, old + 4, old + 2, old)
                .map { row(it) + listOf<String>() }
            h.addRows("gap.csv", columns, frame, explicit = true)
            old += 10
        }
        assertTrue(
            "folding an out-of-date frame allocated $bytes bytes",
            bytes < BUDGET,
        )
    }

    @Test fun theOrderIsStillRightAfterAFrameLandsOutOfOrder() {
        // The cost is only worth anything if the answer is unchanged, so the
        // ordering promise is asserted at the same scale: placed points
        // ascending, then the unplaced block, arrival order kept within it.
        val h = TimeSeriesHistory(retentionSeconds = Double.MAX_VALUE)
        h.addRows("big.csv", columns, (0 until 200).map(::row), explicit = true)
        h.addRows("big.csv", columns, listOf(row(305), row(301), row(304)), explicit = true)
        // A different session, so these are readings the plot does not hold
        // rather than duplicates: rows landing in the middle of the record is
        // what a gap fill does.
        h.addRows("gap.csv", columns, listOf(row(150), row(148)), explicit = true)
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val epochs = w.epochs.toList()
        assertEquals("every reading is held once", 205, epochs.size)
        assertEquals(
            "and the plot is in time order",
            epochs.sorted(),
            epochs,
        )
    }

    private companion object {
        /** 2026-07-22T02:00:00-04:00, the epoch the other data tests count from. */
        const val BASE = 1_784_620_800L

        /**
         * Big enough that a whole-plot re-sort is unmistakable against a frame,
         * small enough that building it keeps the standard pack quick. The
         * captain's tablet was holding 262,000.
         */
        const val POINTS = 120_000

        /**
         * Room for the frame itself - its strings, its boxed parse results and
         * the rows' own storage - and nothing like room for a copy of the plot.
         * A single column of 120,000 doubles is 960 KB on its own, and the
         * re-sort this replaced allocated eleven such arrays.
         */
        const val BUDGET = 300_000L
    }
}

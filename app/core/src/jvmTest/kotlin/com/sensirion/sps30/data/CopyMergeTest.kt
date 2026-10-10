package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copying stored sessions in must only ever ADD.
 *
 * This is the captain's hardest rule on this project and the one the first cut
 * of "Copy all sessions to this device" broke: it reported 2,590 readings
 * copied and left the plot holding less than it had before. These tests pin the
 * behaviour that was missing, in the shape the real path uses - live points
 * arriving first, then whole sessions arriving out of order behind them.
 */
class CopyMergeTest {

    private val columns = Wire.CSV_COLUMNS

    /** One `file_data` row, the way the controller sends them: every cell a string. */
    private fun row(index: Int, epochSeconds: Long, pm: Double): List<String> {
        val cells = ArrayList<String>(columns.size)
        for (c in columns) {
            cells.add(
                when (c) {
                    "index" -> "%07d".format(index)
                    "timestamp" -> iso(epochSeconds)
                    Wire.COL_EVENT -> ""
                    else -> "%.2f".format(pm)
                },
            )
        }
        return cells
    }

    private fun iso(epochSeconds: Long): String {
        val t = java.time.OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(epochSeconds),
            java.time.ZoneOffset.ofHours(-4),
        )
        return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
    }

    private fun liveReading(history: TimeSeriesHistory, csv: String, index: Int, epoch: Long) {
        history.add(
            csv,
            "%07d".format(index),
            iso(epoch),
            Wire.PM_FIELDS.associateWith { 3.0 },
            "",
        )
    }

    /** The exact shape of the bug: live points first, then an older session copied in. */
    @Test fun copyingAnOlderSessionKeepsTheLivePoints() {
        val history = TimeSeriesHistory(retentionSeconds = 172_800.0)
        val liveStart = 1_754_180_000L

        // Nine minutes of live streaming, as the app had before the copy.
        for (i in 1..540) liveReading(history, "SPS30-live.csv", i, liveStart + i)
        val liveCount = history.size
        assertEquals(540, liveCount)

        // Then the user taps Copy, and an hour-old session arrives - every row
        // older than everything already held, which is what makes it the hard case.
        val olderStart = liveStart - 3_600
        val older = (1..1800).map { row(it, olderStart + it, 2.0) }
        val added = history.addRows("SPS30-older.csv", columns, older, explicit = true)

        assertEquals("every copied row is new and must be stored", 1800, added)
        assertEquals(
            "the copy must ADD to the live points, never replace them",
            liveCount + 1800,
            history.size,
        )
        // And the live points must still be there, not merely counted.
        val names = history.snapshot().map { it.csv }.toSet()
        assertTrue("live session missing after the copy", "SPS30-live.csv" in names)
        assertTrue("copied session missing after the copy", "SPS30-older.csv" in names)
    }

    /**
     * Re-copying the live session must be a no-op, not a loss.
     *
     * "Copy all sessions" asks for every session from index 0, including the one
     * already streaming, so almost every row it returns is a duplicate. Those
     * must be skipped without disturbing what is held.
     */
    @Test fun recopyingTheSameSessionAddsNothingAndLosesNothing() {
        val history = TimeSeriesHistory(retentionSeconds = 172_800.0)
        val start = 1_754_180_000L
        for (i in 1..300) liveReading(history, "SPS30-live.csv", i, start + i)
        val before = history.size

        val same = (1..300).map { row(it, start + it, 3.0) }
        val added = history.addRows("SPS30-live.csv", columns, same, explicit = true)

        assertEquals("duplicates must not be re-added", 0, added)
        assertEquals("a duplicate copy must not remove anything", before, history.size)
    }

    /** Several sessions copied in, interleaved and out of order, all survive together. */
    @Test fun manySessionsCopiedInAnyOrderAllSurvive() {
        val history = TimeSeriesHistory(retentionSeconds = 172_800.0)
        val base = 1_754_180_000L

        // Live first.
        for (i in 1..120) liveReading(history, "SPS30-live.csv", i, base + i)

        // Then five stored sessions, newest first, as the controller lists them.
        var expected = history.size
        for (s in 1..5) {
            val sessionStart = base - s * 600
            val rows = (1..200).map { row(it, sessionStart + it, s.toDouble()) }
            val added = history.addRows("SPS30-stored-$s.csv", columns, rows, explicit = true)
            assertEquals("session $s should have contributed every row", 200, added)
            expected += 200
            assertEquals("after session $s the total must only have grown", expected, history.size)
        }

        assertEquals(120 + 5 * 200, history.size)

        // Time order must be restored, or every window afterwards reads the wrong slice.
        val epochs = history.snapshot().map { it.epoch!! }
        assertEquals("history must be sorted by time", epochs.sorted(), epochs)
    }

    /**
     * A copy spanning more than the retention horizon must not delete itself.
     *
     * Retention exists to bound what a live stream accumulates unattended. A
     * session the user explicitly asked for is not that, and trimming it as fast
     * as it arrives would make the button useless on exactly the archive it is
     * for.
     */
    @Test fun copiedPointsOlderThanRetentionAreKept() {
        val history = TimeSeriesHistory(retentionSeconds = 600.0) // ten minutes
        val now = 1_754_180_000L
        for (i in 1..60) liveReading(history, "SPS30-live.csv", i, now + i)

        // An hour old: far outside a ten-minute horizon.
        val old = (1..300).map { row(it, now - 3_600 + it, 1.0) }
        val added = history.addRows("SPS30-old.csv", columns, old, explicit = true)

        assertEquals(300, added)
        assertEquals(
            "an explicitly copied session must survive retention",
            60 + 300,
            history.size,
        )
    }

    /**
     * Retention still applies to what nobody asked for.
     *
     * The exemption above is deliberately narrow: it is for sessions the user
     * named. An unbounded live stream must still be bounded, or the exemption
     * would quietly turn retention off.
     */
    @Test fun retentionStillBoundsTheLiveStream() {
        val history = TimeSeriesHistory(retentionSeconds = 60.0)
        val start = 1_754_180_000L
        for (i in 1..300) liveReading(history, "SPS30-live.csv", i, start + i)

        assertTrue(
            "the live stream must still be trimmed to its horizon, got ${history.size}",
            history.size in 1..120,
        )
    }
}

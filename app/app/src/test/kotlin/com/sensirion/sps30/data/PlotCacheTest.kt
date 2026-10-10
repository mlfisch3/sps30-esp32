package com.sensirion.sps30.data

import com.sensirion.sps30.ui.backfillSinceFor
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The plot, kept on this device.
 *
 * The acceptance criteria this file exists for, in order:
 *
 * 1. Reopening shows what was there, with no transfer.
 * 2. The reconnect backfill still asks for exactly what is missing and no more.
 * 3. No write can lose what was already safe - a torn record, a corrupted one,
 *    or a compaction that cannot be written at all.
 * 4. A session recorded in relative time survives being re-anchored afterwards.
 * 5. The cache is bounded, and the bound drops the oldest.
 *
 * Everything here runs against a scratch directory. No session recorded by the
 * real hardware is read, written or invented.
 */
class PlotCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val session = "SPS30-20260801_100000.csv"

    private fun journal(): File = File(temp.root, PlotCache.FILE_NAME)

    /** A history wired to a basis, as [com.sensirion.sps30.ui.MonitorSession] wires it. */
    private class Model {
        val basis = TimeBasis()
        val history = TimeSeriesHistory(retentionSeconds = 1_000_000.0).also { it.basis = basis }
    }

    private fun open(
        model: Model,
        maxPoints: Int = PlotCache.MAX_POINTS,
        compactAtBytes: Long = PlotCache.COMPACT_AT_BYTES,
    ): PlotCache =
        PlotCache(journal(), model.history, model.basis, maxPoints, compactAtBytes)

    private fun stamp(epoch: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC).toString()

    private fun reading(index: Int, epoch: Long, pm: Double = 4.0) = ServerMessage.Reading(
        csv = session,
        index = Wire.formatIndex(index),
        timestamp = stamp(epoch),
        values = mapOf("pm1_0" to pm, "pm2_5" to pm + 0.5, "pm4_0" to pm + 0.7, "pm10" to pm + 0.9),
    )

    private fun relativeReading(boot: Int, relMs: Long, index: Int, pm: Double = 2.0) =
        ServerMessage.Reading(
            csv = "SPS30-R%08X-00001925.csv".format(boot),
            index = Wire.formatIndex(index),
            timestamp = "",
            values = mapOf(
                "pm1_0" to pm, "pm2_5" to pm + 0.5, "pm4_0" to pm + 0.7, "pm10" to pm + 0.9,
            ),
            relBoot = boot,
            relMs = relMs,
        )

    /** Stream [count] readings into a cache and let go of it, as closing the app does. */
    private fun record(count: Int, from: Long = 1_785_000_000L): Model {
        val model = Model()
        val cache = open(model)
        cache.load()
        for (i in 1..count) model.history.add(reading(i, from + i))
        cache.flush()
        cache.close()
        return model
    }

    // --- 1. reopening shows what was there -----------------------------------

    @Test
    fun `readings come back after the app is closed`() {
        val before = record(600)
        val after = Model()
        val cache = open(after)
        assertEquals(600, cache.load())

        assertEquals(before.history.size, after.history.size)
        val was = before.history.snapshot()
        val now = after.history.snapshot()
        for (i in was.indices) {
            assertEquals("index at $i", was[i].index, now[i].index)
            assertEquals("epoch at $i", was[i].epoch, now[i].epoch)
            assertEquals("csv at $i", was[i].csv, now[i].csv)
            for (f in Wire.PM_FIELDS) {
                assertEquals("$f at $i", was[i].values[f]!!, now[i].values[f]!!, 0.0)
            }
        }
    }

    @Test
    fun `a fresh install has no cache and says so quietly`() {
        val model = Model()
        val cache = open(model)
        assertEquals(0, cache.load())
        assertFalse(cache.degraded)
        assertEquals(0, model.history.size)
    }

    @Test
    fun `a reading that arrives out of order is cached as itself`() {
        val model = Model()
        val cache = open(model)
        cache.load()
        model.history.add(reading(10, 1_785_000_010L, pm = 10.0))
        model.history.add(reading(11, 1_785_000_011L, pm = 11.0))
        // A late frame from before those two: the store puts it in front of them,
        // and what is written out has to be that reading and not its neighbour.
        model.history.add(reading(9, 1_785_000_009L, pm = 9.0))
        cache.flush()
        cache.close()

        val after = Model()
        assertEquals(3, open(after).load())
        val back = after.history.snapshot()
        assertEquals(listOf(9, 10, 11), back.map { it.index })
        assertEquals(listOf(9.0, 10.0, 11.0), back.map { it.values["pm1_0"] })
    }

    @Test
    fun `an event flag survives, so a settling spike is still marked`() {
        val model = Model()
        val cache = open(model)
        cache.load()
        model.history.add(reading(1, 1_785_000_001L))
        model.history.add(
            session, Wire.formatIndex(2), stamp(1_785_000_002L),
            mapOf("pm2_5" to 900.0), event = "settling",
        )
        cache.flush()
        cache.close()

        val after = Model()
        open(after).load()
        assertEquals(listOf("", "settling"), after.history.snapshot().map { it.event })
    }

    // --- 2. reconciling with the board, not replacing it ---------------------

    @Test
    fun `a complete session restored from cache is not asked for again`() {
        val before = record(600)
        assertEquals(600, before.history.receivedRows(session))

        val after = Model()
        open(after).load()
        assertEquals(600, after.history.receivedRows(session))
        assertNull(
            "the controller holds 600 and so do we; there is nothing to ask for",
            backfillSinceFor(
                controllerRows = 600,
                receivedEver = after.history.receivedRows(session),
                held = after.history.coverage()[session],
            ),
        )
    }

    @Test
    fun `a session the controller has grown is asked for from where ours stops`() {
        record(600)
        val after = Model()
        open(after).load()
        assertEquals(
            600,
            backfillSinceFor(
                controllerRows = 900,
                receivedEver = after.history.receivedRows(session),
                held = after.history.coverage()[session],
            ),
        )
    }

    @Test
    fun `a hole in the cached copy is still a hole after a restart`() {
        val model = Model()
        val cache = open(model)
        cache.load()
        for (i in 1..100) model.history.add(reading(i, 1_785_000_000L + i))
        // A dropout, and then readings from after it - exactly what a transport
        // switch leaves behind.
        for (i in 140..200) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        cache.close()

        val after = Model()
        open(after).load()
        val held = after.history.coverage()[session]
        assertNotNull(held)
        assertEquals(100, held!!.contiguousTo)
        assertEquals(200, held.highestIndex)
        assertEquals(
            "the gap has to be asked for from where the copy stops being complete",
            100,
            backfillSinceFor(controllerRows = 200, receivedEver = 161, held = held),
        )
    }

    @Test
    fun `readings a bounded cache dropped are still counted as received`() {
        // Sixty readings arrive; the cache keeps only the last twenty of them.
        val model = Model()
        val cache = open(model, maxPoints = 20, compactAtBytes = 1L)
        cache.load()
        for (i in 1..60) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        cache.close()

        val after = Model()
        open(after, maxPoints = 20, compactAtBytes = 1L).load()
        assertEquals(20, after.history.size)
        assertEquals(
            "the forty that were dropped still happened, and the totals say so",
            60, after.history.receivedRows(session),
        )
        assertNull(
            "a session collected in full must not be fetched again every connect",
            backfillSinceFor(
                controllerRows = 60,
                receivedEver = after.history.receivedRows(session),
                held = after.history.coverage()[session],
            ),
        )
    }

    @Test
    fun `a session someone waited for is not trimmed away on the next start`() {
        val model = Model()
        val cache = open(model)
        cache.load()
        // An hour-old session, fetched by name, into a history that keeps ten
        // minutes. Without the retention floor it would be deleted on arrival.
        model.history.setRetention(600.0)
        val old = (1..50).map { i ->
            listOf(Wire.formatIndex(i), stamp(1_785_000_000L + i), "3.0", "3.5", "3.7", "3.9", "")
        }
        model.history.addRows(session, Wire.CSV_COLUMNS, old, explicit = true)
        assertEquals(50, model.history.size)
        cache.flush()
        cache.close()

        val after = Model()
        after.history.setRetention(600.0)
        open(after).load()
        assertEquals(50, after.history.size)
        // And a live reading an hour later must not sweep them away either.
        after.history.add(reading(9000, 1_785_003_600L))
        assertEquals(51, after.history.size)
    }

    @Test
    fun `sessions loaded from files on this device are not cached`() {
        val model = Model()
        val cache = open(model)
        cache.load()
        model.history.add(reading(1, 1_785_000_001L))
        model.history.addImported("SPS30-20260101_000000.csv", 1, 1_700_000_000.0,
            doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        model.history.finishImport()
        cache.flush()
        cache.close()

        val after = Model()
        assertEquals(
            "an import is a file that is still on this device; one copy is enough",
            1, open(after).load(),
        )
        assertEquals(0, after.history.importedPointCount())
    }

    // --- 3. no write loses what was already safe -----------------------------

    /**
     * Stream 599 readings and note the file length, then stream one more.
     *
     * Everything past the returned offset belongs to the six hundredth reading,
     * so damaging it there is exactly a crash part way through writing it - and
     * the 599 before it are known-good bytes that must survive whatever happens.
     */
    private fun recordWithOneMoreAfter(safeCount: Int = 599): Long {
        val model = Model()
        val cache = open(model)
        cache.load()
        for (i in 1..safeCount) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        val safe = journal().length()
        model.history.add(reading(safeCount + 1, 1_785_000_000L + safeCount + 1))
        cache.flush()
        cache.close()
        return safe
    }

    @Test
    fun `a write cut off half way costs that record and nothing else`() {
        val safe = recordWithOneMoreAfter()
        // A crash mid-append: the last reading reached the file in part.
        RandomAccessFile(journal(), "rw").use { it.setLength(safe + 1) }

        val after = Model()
        val cache = open(after)
        assertEquals(599, cache.load())
        assertEquals(599, after.history.size)
        assertEquals(599, after.history.receivedRows(session))
        assertFalse(cache.degraded)
        // And the file is usable again straight away.
        after.history.add(reading(600, 1_785_000_600L))
        cache.flush()
        cache.close()

        assertEquals(600, open(Model()).load())
    }

    @Test
    fun `a record corrupted in place is refused rather than believed`() {
        val safe = recordWithOneMoreAfter()
        RandomAccessFile(journal(), "rw").use { raf ->
            raf.seek(safe + 20)
            raf.write(0x5A)
        }

        val after = Model()
        val cache = open(after)
        assertEquals("everything before the damage survives", 599, cache.load())
        assertEquals(599, after.history.size)
        // The damaged tail is gone, so appending resumes cleanly.
        after.history.add(reading(600, 1_785_000_600L))
        cache.flush()
        cache.close()
        assertEquals(600, open(Model()).load())
    }

    @Test
    fun `garbage where the cache should be is discarded, not guessed at`() {
        journal().writeBytes(ByteArray(4096) { (it % 251).toByte() })
        val model = Model()
        val cache = open(model)
        assertEquals(0, cache.load())
        assertFalse(cache.degraded)
        model.history.add(reading(1, 1_785_000_001L))
        cache.flush()
        cache.close()
        assertEquals(1, open(Model()).load())
    }

    @Test
    fun `a compaction that cannot be written leaves the old cache intact`() {
        // Twenty is the cap, and compaction is triggered by the journal reaching
        // twice it - so thirty readings is a cache that is due one and has not
        // had it yet.
        val model = Model()
        val cache = open(model, maxPoints = 20)
        cache.load()
        for (i in 1..30) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        assertEquals(30, open(Model()).load())

        // Now make the directory unwritable, which is what a full disk looks
        // like to the one operation that has to create a file of its own.
        assertTrue(temp.root.setWritable(false))
        try {
            val canStillWrite = try {
                File(temp.root, "probe").createNewFile()
            } catch (e: java.io.IOException) {
                false
            }
            org.junit.Assume.assumeFalse("running as a user who can write anyway", canStillWrite)
            for (i in 31..60) model.history.add(reading(i, 1_785_000_000L + i))
            cache.flush()
            assertNotNull("the failure is recorded", cache.lastError)
            assertFalse("and appending is not given up on", cache.degraded)
        } finally {
            temp.root.setWritable(true)
        }
        cache.close()

        // The journal is exactly what it would have been with no compaction
        // attempted at all: sixty readings' worth of history, and no debris.
        val plain = Model()
        assertEquals(60, open(plain).load())
        assertEquals(60, plain.history.receivedRows(session))
        assertFalse(File(temp.root, PlotCache.FILE_NAME + ".new").exists())
    }

    @Test
    fun `clearing the plot clears what is kept for it`() {
        val model = Model()
        val cache = open(model)
        cache.load()
        for (i in 1..50) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        model.history.clear()
        cache.reset()
        model.history.add(reading(1, 1_785_100_000L))
        cache.flush()
        cache.close()

        val after = Model()
        assertEquals(1, open(after).load())
        assertEquals(1, after.history.receivedRows(session))
    }

    // --- 4. a run recorded in relative time ----------------------------------

    @Test
    fun `an unplaceable run comes back unplaceable, and can still be placed`() {
        val boot = 854
        val model = Model()
        val cache = open(model)
        cache.load()
        for (i in 1..300) model.history.add(relativeReading(boot, 6_437L + i * 1000L, i))
        assertEquals(300, model.history.size)
        assertTrue(
            "a run nothing can place was given a position",
            model.history.snapshot().all { it.epoch == null },
        )
        cache.flush()
        cache.close()

        val after = Model()
        assertEquals(300, open(after).load())
        assertTrue(after.history.hasRelativePoints())
        assertEquals(
            "a run nothing can place must not come back pretending to be dated",
            listOf(boot), after.basis.unanchoredBoots(),
        )
        assertTrue(
            "the cache handed back a position it had no business holding",
            after.history.snapshot().all { it.epoch == null },
        )
        assertEquals(300, after.history.unplacedCount())

        // The equivalence arrives after the restart, which is the case the raw
        // (boot, relMs) is kept for.
        val anchoredAt = 1_785_400_000_000L
        after.basis.accept(
            ServerMessage.TimeMap(
                boot = boot,
                anchors = listOf(
                    ServerMessage.TimeAnchor(boot, relMs = 6_437L, anchoredAt, -240),
                ),
            )
        )
        assertEquals(300, after.history.retime())
        assertTrue(after.basis.unanchoredBoots().isEmpty())
        val placed = after.history.snapshot()
        assertEquals(anchoredAt / 1000.0 + 1.0, placed.first().epoch!!, 1e-6)
        assertEquals(anchoredAt / 1000.0 + 300.0, placed.last().epoch!!, 1e-6)
        assertEquals(0, after.history.unplacedCount())
    }

    @Test
    fun `an equivalence learned before the restart is not forgotten by it`() {
        val boot = 91
        val anchoredAt = 1_785_400_000_000L
        val model = Model()
        val cache = open(model)
        cache.load()
        model.basis.accept(
            ServerMessage.TimeMap(
                boot = boot,
                anchors = listOf(ServerMessage.TimeAnchor(boot, 1_000L, anchoredAt, -240)),
            )
        )
        for (i in 1..120) model.history.add(relativeReading(boot, 1_000L + i * 1000L, i))
        val was = model.history.snapshot().map { it.epoch }
        cache.flush()
        cache.close()

        val after = Model()
        assertEquals(120, open(after).load())
        assertTrue(after.basis.isAnchored(boot))
        assertEquals(was, after.history.snapshot().map { it.epoch })
    }

    @Test
    fun `a hand-placed run keeps its origin even when its first readings are dropped`() {
        val boot = 77
        val placedAt = 1_785_500_000.0
        val model = Model()
        val cache = open(model, maxPoints = 20, compactAtBytes = 1L)
        cache.load()
        for (i in 1..60) model.history.add(relativeReading(boot, 5_000L + i * 1000L, i))
        model.basis.placeManually(boot, placedAt)
        model.history.retime()
        val last = model.history.snapshot().last().epoch
        cache.flush()
        cache.close()

        val after = Model()
        open(after, maxPoints = 20, compactAtBytes = 1L).load()
        assertEquals(20, after.history.size)
        assertEquals(placedAt, after.basis.manualOrigin(boot)!!, 0.0)
        assertEquals(
            "the origin belongs to the first reading of the run, which is the one " +
                "a bounded cache drops first",
            last!!, after.history.snapshot().last().epoch!!, 1e-6,
        )
    }

    // --- 5. the cache is bounded ---------------------------------------------

    @Test
    fun `the cap keeps the newest readings and drops the oldest`() {
        val model = Model()
        val cache = open(model, maxPoints = 40, compactAtBytes = 1L)
        cache.load()
        for (i in 1..250) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        cache.close()

        val after = Model()
        open(after, maxPoints = 40, compactAtBytes = 1L).load()
        assertEquals(40, after.history.size)
        val kept = after.history.snapshot().map { it.index }
        assertEquals((211..250).toList(), kept)
    }

    @Test
    fun `a journal larger than the cap is trimmed when it is read`() {
        // Written with no bound at all, then read by a build that has one.
        val model = Model()
        val cache = open(model)
        cache.load()
        for (i in 1..250) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        cache.close()

        val after = Model()
        val bounded = open(after, maxPoints = 40, compactAtBytes = 1L)
        assertEquals(40, bounded.load())
        assertEquals((211..250).toList(), after.history.snapshot().map { it.index })
        // And it compacts itself down on the way in, rather than staying oversized.
        bounded.close()
        assertEquals(40, open(Model(), maxPoints = 40, compactAtBytes = 1L).load())
    }

    @Test
    fun `compaction keeps the totals a backfill reasons from`() {
        val model = Model()
        val cache = open(model, maxPoints = 30, compactAtBytes = 1L)
        cache.load()
        for (i in 1..200) model.history.add(reading(i, 1_785_000_000L + i))
        cache.flush()
        cache.close()

        val after = Model()
        open(after, maxPoints = 30, compactAtBytes = 1L).load()
        assertEquals(200, after.history.receivedRows(session))
        assertEquals(30, after.history.size)
    }
}

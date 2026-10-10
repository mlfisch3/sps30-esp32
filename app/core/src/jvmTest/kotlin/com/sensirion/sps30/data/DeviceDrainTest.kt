package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import com.sensirion.sps30.wire.mustNotBeDropped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device half of backing everything up and clearing the sensor.
 *
 * The happy path is the least interesting thing here and it is covered in one
 * test. Everything else is a way of being interrupted, because the sensor this
 * will be run against is recording a record its owner will not lose, and the
 * question that matters is not "does it work" but "what does it leave behind
 * when it does not finish".
 *
 * The invariant every test below asserts, from a different direction: **nothing
 * is confirmed that is not written down here first, and the sensor frees nothing
 * that was not confirmed.** The [Sink] used throughout records every call in
 * order, so "sealed before confirmed" is checked as a sequence rather than
 * argued for.
 */
class DeviceDrainTest {

    /**
     * A sink that remembers what happened to it, in order.
     *
     * Deliberately not a stub that returns a fixed digest: it computes one over
     * the rows it was given, exactly as the real one computes it over the rows it
     * read back off disk, so a test that confirms the wrong block fails here
     * rather than passing quietly.
     */
    private class Sink(
        /** Rows to swallow before throwing, for the disk-full case. 0 for never. */
        val failWriteAfter: Int = 0,
        val failSeal: Boolean = false,
    ) : DrainSink {
        val calls = ArrayList<String>()
        val blocks = LinkedHashMap<Long, ArrayList<List<String>>>()
        val freed = ArrayList<Long>()
        var open: Long = 0
        var openKind: String = ""
        var written = 0
        var finished = false

        override val location: String = "/somewhere/sps30-clear-test"

        override fun open(block: Long, kind: String, columns: List<String>) {
            calls.add("open:$block")
            open = block
            openKind = kind
            blocks[block] = ArrayList()
        }

        override fun row(cells: List<String>) {
            if (failWriteAfter != 0 && written >= failWriteAfter) {
                throw java.io.IOException("no space left on device")
            }
            written++
            blocks[open]?.add(cells)
        }

        override fun seal(): DrainSink.Sealed {
            calls.add("seal:$open")
            if (failSeal) throw java.io.IOException("could not read the block back")
            val rows = blocks[open].orEmpty()
            var digest = 0
            var counted = 0
            for (row in rows) {
                val one = DeviceDrain.digestRow(openKind, row) ?: continue
                digest = DeviceDrain.fold(digest, one)
                counted++
            }
            return DrainSink.Sealed(counted, digest)
        }

        override fun freed(block: Long) {
            calls.add("freed:$block")
            freed.add(block)
        }

        override fun discard() {
            calls.add("discard:$open")
            blocks.remove(open)
            open = 0
        }

        override fun finish(): String {
            calls.add("finish")
            finished = true
            return location
        }

        override fun close() {
            calls.add("close")
        }
    }

    // --- fixtures --------------------------------------------------------------

    private val session = "SPS30-20260802_184742.csv"

    private fun readingRow(index: Int): List<String> = listOf(
        session,
        "%07d".format(index),
        "2026-08-02T18:47:%02d-04:00".format(42 + (index % 18)),
        "5.39", "18.01", "27.97", "29.96", "10.29", "29.78", "42.23", "44.63", "45.00", "1.630",
        "", "", "",
    )

    private fun data(
        block: Long,
        rows: List<List<String>>,
        done: Boolean,
        kind: String = Wire.DRAIN_KIND_RING,
    ) = ServerMessage.DrainData(
        block = block,
        kind = kind,
        columns = if (kind == Wire.DRAIN_KIND_SEGMENTS) {
            Wire.DRAIN_SEGMENT_COLUMNS
        } else {
            Wire.DRAIN_READING_COLUMNS
        },
        rows = rows,
        done = done,
    )

    private fun state(
        s: String,
        block: Long = 0,
        left: Int = 0,
        freed: Int = 0,
        message: String = "",
        readings: Int = 0,
        segments: Int = 0,
    ) = ServerMessage.DrainState(
        state = s,
        message = message,
        block = block,
        kind = Wire.DRAIN_KIND_RING,
        final = s == Wire.DRAIN_KEPT,
        rows = 0,
        digest = 0,
        blocksLeft = left,
        blocksFreed = freed,
        blocksKept = if (s == Wire.DRAIN_KEPT) 1 else 0,
        blocksRefused = if (s == Wire.DRAIN_REFUSED) 1 else 0,
        readingsCopied = readings,
        segmentsCopied = segments,
        readingsFreed = 0,
    )

    private fun cmd(line: String): String =
        Regex(""""cmd"\s*:\s*"([a-z_]+)"""").find(line)?.groupValues?.get(1) ?: "?"

    /** Drive one whole block through: rows, confirmation, the sensor's answer. */
    private fun copyBlock(
        drain: DeviceDrain,
        block: Long,
        rows: List<List<String>>,
        answer: String,
        left: Int = 0,
        freed: Int = 0,
    ): List<String> {
        drain.onData(data(block, rows.dropLast(1), done = false))
        val confirm = drain.onData(data(block, rows.takeLast(1), done = true))
        assertEquals(listOf(Wire.CMD_DRAIN_CONFIRM), confirm.map(::cmd))
        return drain.onState(state(answer, block = block, left = left, freed = freed))
    }

    // --- the whole thing -------------------------------------------------------

    @Test
    fun `a run copies every block, confirms each, and finishes on the kept one`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)

        assertEquals(listOf(Wire.CMD_DRAIN_BEGIN), drain.start().map(::cmd))
        assertEquals(DeviceDrain.Phase.STARTING, drain.status.phase)

        assertEquals(
            listOf(Wire.CMD_DRAIN_NEXT),
            drain.onState(state(Wire.DRAIN_IDLE, left = 3)).map(::cmd),
        )

        val next1 = copyBlock(drain, 1, (1..4).map(::readingRow), Wire.DRAIN_FREED, left = 2, freed = 1)
        assertEquals(listOf(Wire.CMD_DRAIN_NEXT), next1.map(::cmd))
        val next2 = copyBlock(drain, 2, (5..8).map(::readingRow), Wire.DRAIN_FREED, left = 1, freed = 2)
        assertEquals(listOf(Wire.CMD_DRAIN_NEXT), next2.map(::cmd))

        // The last block: copied and proved like the others, and then kept,
        // because it holds the readings arriving right now.
        val last = copyBlock(drain, 3, (9..10).map(::readingRow), Wire.DRAIN_KEPT, left = 0, freed = 2)
        assertEquals(listOf(Wire.CMD_DRAIN_CANCEL), last.map(::cmd))
        assertEquals(DeviceDrain.Phase.DONE, drain.status.phase)
        assertTrue(sink.finished)
        assertEquals(listOf(1L, 2L), sink.freed)
        assertEquals(10, drain.status.rowsWritten)

        // Every block was sealed before it was confirmed, and the two that were
        // freed were freed after their seal. That order is the contract.
        assertEquals(
            listOf(
                "open:1", "seal:1", "freed:1",
                "open:2", "seal:2", "freed:2",
                "open:3", "seal:3", "finish",
            ),
            sink.calls,
        )
    }

    // --- interrupted between the copy and the confirmation ---------------------

    @Test
    fun `an interruption before confirming discards the block and frees nothing`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 2))
        drain.onData(data(1, (1..4).map(::readingRow), done = false))

        // The link goes. The block was never sealed, so it was never confirmed,
        // so the sensor still has it.
        drain.interrupted("the connection dropped")

        assertEquals(DeviceDrain.Phase.FAILED, drain.status.phase)
        assertTrue(sink.freed.isEmpty())
        assertTrue("the half-copied block should be thrown away", sink.calls.contains("discard:1"))
        assertFalse("nothing may be sealed", sink.calls.any { it.startsWith("seal") })
    }

    // --- interrupted between the confirmation and the free ---------------------

    @Test
    fun `an interruption after confirming leaves the block written down here`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 2))
        drain.onData(data(1, (1..4).map(::readingRow), done = false))
        val confirm = drain.onData(data(1, listOf(readingRow(5)), done = true))
        assertEquals(listOf(Wire.CMD_DRAIN_CONFIRM), confirm.map(::cmd))

        // The sensor's answer never arrives. Whether it got as far as freeing the
        // block changes nothing: the block is durable here either way, which is
        // the whole point of sealing before confirming.
        drain.interrupted("the app was killed")
        assertEquals(DeviceDrain.Phase.FAILED, drain.status.phase)
        assertTrue("the block was sealed before the confirmation", sink.calls.contains("seal:1"))
        assertEquals(5, sink.blocks[1]?.size)
        assertEquals(5, drain.status.rowsWritten)
    }

    // --- a confirmation the sensor would not accept ----------------------------

    @Test
    fun `a refusal stops the run and says nothing more was removed`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 3))
        copyBlock(drain, 1, (1..4).map(::readingRow), Wire.DRAIN_FREED, left = 2, freed = 1)

        drain.onData(data(2, (5..8).map(::readingRow), done = false))
        drain.onData(data(2, listOf(readingRow(9)), done = true))
        val after = drain.onState(
            state(Wire.DRAIN_REFUSED, block = 2, left = 2, freed = 1, message = "did not match"),
        )

        // Never a `drain_next`. Carrying on past a block the sensor could not
        // confirm would mean skipping it and calling the sensor clear.
        assertEquals(listOf(Wire.CMD_DRAIN_CANCEL), after.map(::cmd))
        assertEquals(DeviceDrain.Phase.FAILED, drain.status.phase)
        assertEquals("did not match", drain.status.reason)
        assertEquals(listOf(1L), sink.freed)
    }

    // --- cancelling ------------------------------------------------------------

    @Test
    fun `cancelling mid-block is safe and keeps what was already freed`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 4))
        copyBlock(drain, 1, (1..4).map(::readingRow), Wire.DRAIN_FREED, left = 3, freed = 1)
        drain.onData(data(2, (5..7).map(::readingRow), done = false))

        val stop = drain.cancel()
        assertEquals(listOf(Wire.CMD_DRAIN_CANCEL), stop.map(::cmd))
        assertEquals(DeviceDrain.Phase.CANCELLED, drain.status.phase)
        // Block 1 stays copied and stays freed - the operation did part of its
        // job rather than leaving a half-finished state.
        assertEquals(listOf(1L), sink.freed)
        assertEquals(4, sink.blocks[1]?.size)
        // Block 2 was never confirmed, so it is thrown away here and is still on
        // the sensor.
        assertTrue(sink.calls.contains("discard:2"))
        assertNull(sink.blocks[2])

        // And a second cancel is not a second command.
        assertTrue(drain.cancel().isEmpty())
    }

    @Test
    fun `cancelling before anything has been copied sends one command and no more`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        assertEquals(listOf(Wire.CMD_DRAIN_CANCEL), drain.cancel().map(::cmd))
        assertEquals(DeviceDrain.Phase.CANCELLED, drain.status.phase)
        assertTrue(sink.freed.isEmpty())
        assertFalse(sink.calls.any { it.startsWith("seal") })
    }

    // --- a board reboot in the middle ------------------------------------------

    @Test
    fun `a resumed run continues rather than starting over`() {
        // The first run frees two blocks and is then cut off by a reboot.
        val first = Sink()
        val one = DeviceDrain(first)
        one.start()
        one.onState(state(Wire.DRAIN_IDLE, left = 5))
        copyBlock(one, 1, (1..4).map(::readingRow), Wire.DRAIN_FREED, left = 4, freed = 1)
        copyBlock(one, 2, (5..8).map(::readingRow), Wire.DRAIN_FREED, left = 3, freed = 2)
        one.interrupted("the sensor rebooted")

        // The second run starts from scratch on this side and is offered block
        // ids from scratch too - the sensor does not remember them either. What
        // it *is* offered is the next data, because the sensor's answer to "what
        // is oldest" comes from its own flash.
        val second = Sink()
        val two = DeviceDrain(second)
        two.start()
        assertEquals(
            listOf(Wire.CMD_DRAIN_NEXT),
            two.onState(state(Wire.DRAIN_IDLE, left = 3)).map(::cmd),
        )
        copyBlock(two, 1, (9..12).map(::readingRow), Wire.DRAIN_FREED, left = 2, freed = 1)
        assertEquals(listOf(1L), second.freed)
        // Nothing of the first run was redone: its blocks are on the device and
        // gone from the sensor, and the second run never saw them.
        assertEquals(setOf(1L, 2L), first.blocks.keys)
        assertEquals(setOf(1L), second.blocks.keys)
    }

    // --- a device that cannot write --------------------------------------------

    @Test
    fun `a device that cannot write the copy confirms nothing`() {
        val sink = Sink(failWriteAfter = 2)
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 2))
        val after = drain.onData(data(1, (1..4).map(::readingRow), done = false))

        assertEquals(listOf(Wire.CMD_DRAIN_CANCEL), after.map(::cmd))
        assertEquals(DeviceDrain.Phase.FAILED, drain.status.phase)
        assertTrue(drain.status.reason.contains("could not write"))
        assertTrue(drain.status.reason.contains("Nothing was removed"))
        assertFalse(sink.calls.any { it.startsWith("seal") })
    }

    @Test
    fun `a block that cannot be read back off this device confirms nothing`() {
        val sink = Sink(failSeal = true)
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 1))
        drain.onData(data(1, (1..3).map(::readingRow), done = false))
        val after = drain.onData(data(1, listOf(readingRow(4)), done = true))

        assertEquals(listOf(Wire.CMD_DRAIN_CANCEL), after.map(::cmd))
        assertEquals(DeviceDrain.Phase.FAILED, drain.status.phase)
        assertTrue(sink.freed.isEmpty())
    }

    // --- preserved alert segments ---------------------------------------------

    @Test
    fun `a preserved alert segment is copied and confirmed like any other block`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 2))

        val summary = segmentRow()
        drain.onData(data(1, listOf(summary), done = false, kind = Wire.DRAIN_KIND_SEGMENTS))
        val confirm = drain.onData(
            data(1, listOf(segmentRow(seq = 43)), done = true, kind = Wire.DRAIN_KIND_SEGMENTS),
        )
        assertEquals(listOf(Wire.CMD_DRAIN_CONFIRM), confirm.map(::cmd))
        // Two summaries went in and two came out of the seal, which is what the
        // sensor will be told - a segments block that quietly digested as zero
        // rows would be a block the sensor frees having proved nothing.
        assertTrue(confirm.first().contains("\"rows\":2"))
        assertEquals(2, sink.blocks[1]?.size)

        val after = drain.onState(
            state(Wire.DRAIN_FREED, block = 1, left = 1, freed = 1, segments = 2),
        )
        assertEquals(listOf(Wire.CMD_DRAIN_NEXT), after.map(::cmd))
        assertEquals(2, drain.status.segmentsCopied)
    }

    private fun segmentRow(seq: Int = 42): List<String> = listOf(
        "$seq", session, "pm2_5", "20.0", "1785710862", "1785710961",
        "21.5", "19.25", "44.125", "33.75", "12.5", "18.0", "91.5",
        "100", "3745", "3844", "11", "1",
    )

    // --- the digests, against the firmware's own -------------------------------

    /**
     * The digest agrees with the firmware's, on rows a real board measured.
     *
     * The eight rows are the same real board output `SessionArchiveTest` carries
     * for the erase gate, and `d1ca7fb2` is what `store_drain_digest_reading` and
     * `store_drain_digest_fold` produce for them in C - see
     * `test_the_digests_the_app_must_agree_with` in `firmware/test/test_store_drain.c`,
     * which carries the same rows and the same expected value.
     *
     * This is the check the whole feature stands on. The sensor frees a block when
     * the digest this app sends matches the one it computes itself, so if the two
     * drift apart the operation either stops working or - far worse - starts
     * agreeing about data that is not the same data. Neither side's tests can see
     * that alone; only a shared expected value can. Change either side and move
     * both.
     */
    @Test
    fun `the reading digest agrees with the firmware on real measured rows`() {
        val name = "SPS30-20260802_184742.csv"
        val cells = listOf(
            "5.39,18.01,27.97,29.96,10.29,29.78,42.23,44.63,45.00,1.630",
            "2.19,5.84,8.68,9.25,7.31,13.69,17.28,17.97,18.08,0.851",
            "2.00,2.44,2.69,2.75,12.71,15.54,15.96,16.03,16.04,0.641",
            "2.03,2.27,2.36,2.38,13.32,15.98,16.20,16.23,16.24,0.584",
            "2.11,2.23,2.23,2.23,14.10,16.73,16.84,16.84,16.85,0.488",
            "2.19,2.32,2.32,2.32,14.66,17.40,17.52,17.52,17.53,0.439",
            "2.28,2.41,2.41,2.41,15.21,18.05,18.17,18.18,18.18,0.458",
            "2.34,2.47,2.47,2.47,15.63,18.55,18.67,18.68,18.69,0.485",
        )
        val rows = cells.mapIndexed { i, values ->
            listOf(name, "%07d".format(i + 1), "2026-08-02T18:47:%02d-04:00".format(42 + i)) +
                values.split(',') + listOf("settling", "", "")
        }
        rows.forEach { assertEquals(Wire.DRAIN_READING_COLUMNS.size, it.size) }

        assertEquals("e32af258", Wire.digestHex(DeviceDrain.digestReadingRow(rows[0])!!))
        val digest = rows.fold(0) { acc, r ->
            DeviceDrain.fold(acc, DeviceDrain.digestReadingRow(r) ?: error("real row: $r"))
        }
        assertEquals("d1ca7fb2", Wire.digestHex(digest))

        // Order-independent, which is what makes it usable: the sensor reads its
        // pages in write order, and a device that re-read its own file in any
        // other order would still be holding the same data.
        val reversed = rows.reversed()
            .fold(0) { acc, r -> DeviceDrain.fold(acc, DeviceDrain.digestReadingRow(r)!!) }
        assertEquals(digest, reversed)

        // And the session's name really is in it. A block is a slice of the
        // sensor's flash, not of one session, so a digest that ignored the name
        // could not tell rows filed under the wrong session from rows filed right.
        val elsewhere = rows[0].toMutableList().also { it[0] = "SPS30-20260802_184743.csv" }
        assertNotEquals(
            DeviceDrain.digestReadingRow(rows[0]),
            DeviceDrain.digestReadingRow(elsewhere),
        )
    }

    @Test
    fun `the reading digest agrees with the firmware on a row recorded with no clock`() {
        val row = listOf("SPS30-R000004B2-00000821.csv", "0000001", "") +
            "2.50,10.83,17.45,18.77,0.00,11.22,19.45,21.04,21.28,1.630".split(',') +
            listOf("settling", "1202", "2081")
        assertEquals(Wire.DRAIN_READING_COLUMNS.size, row.size)
        assertEquals("9f3c822f", Wire.digestHex(DeviceDrain.digestReadingRow(row)!!))

        // The board's own clock is what is digested, not the empty timestamp
        // cell. A digest that used the instant would make every clockless block
        // unprovable and therefore undrainable.
        val otherRun = row.toMutableList().also { it[it.size - 2] = "1196" }
        assertNotEquals(DeviceDrain.digestReadingRow(row), DeviceDrain.digestReadingRow(otherRun))

        // A row with neither clock cannot be placed and is not counted, on this
        // side or the sensor's.
        val nowhere = row.toMutableList().also { it[it.size - 2] = ""; it[it.size - 1] = "" }
        assertNull(DeviceDrain.digestReadingRow(nowhere))
    }

    @Test
    fun `the segment digest agrees with the firmware on a preserved summary`() {
        val row = listOf(
            "42", "SPS30-20260802_174000.csv", "pm2_5", "20.0", "1785710862", "1785710961",
            "21.5", "19.25", "44.125", "33.75", "12.5", "18.0", "91.5",
            "100", "3745", "3844", "11", "1",
        )
        assertEquals(Wire.DRAIN_SEGMENT_COLUMNS.size, row.size)
        assertEquals("7b253807", Wire.digestHex(DeviceDrain.digestSegmentRow(row)!!))

        // `has_body` is deliberately not in it: whether the samples are still on
        // the sensor's flash is worked out when the record is read and changes as
        // its preservation area ages, so a digest that included it would refuse a
        // perfectly good copy an hour later.
        val reduced = row.toMutableList().also { it[it.size - 1] = "" }
        assertEquals(DeviceDrain.digestSegmentRow(row), DeviceDrain.digestSegmentRow(reduced))

        // Every statistic is, though.
        val nudged = row.toMutableList().also { it[9] = "33.76" }
        assertNotEquals(DeviceDrain.digestSegmentRow(row), DeviceDrain.digestSegmentRow(nudged))

        // A field this build does not know is not folded onto field zero: the
        // digest simply will not match, the sensor refuses, and nothing goes.
        val unknown = row.toMutableList().also { it[2] = "pm0_3" }
        assertNotEquals(DeviceDrain.digestSegmentRow(row), DeviceDrain.digestSegmentRow(unknown))
    }

    // --- the frames themselves -------------------------------------------------

    @Test
    fun `a drain frame is never dropped for a slow screen`() {
        val data = Messages.parse(
            """{"type":"drain_data","block":3,"kind":"ring","columns":[],"rows":[],"done":true}""",
        )!!
        val progress = Messages.parse(
            """{"type":"drain_state","state":"freed","message":"","block":3,"kind":"ring",""" +
                """"final":false,"rows":41,"digest":"b0361fd2","blocks_left":9}""",
        )!!
        assertTrue(data.mustNotBeDropped())
        assertTrue(progress.mustNotBeDropped())

        // The digest is read unsigned, so the top bit survives the trip. Reading
        // it as a signed quantity is exactly how a confirmation that should match
        // stops matching.
        val big = Messages.parse(
            """{"type":"drain_state","state":"offered","digest":"ffffffff"}""",
        ) as ServerMessage.DrainState
        assertEquals(-1, big.digest)
        assertEquals("ffffffff", Wire.digestHex(big.digest))
    }

    // --- what the screen is told ----------------------------------------------

    @Test
    fun `progress is measured against the most blocks ever outstanding`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        assertNull("nothing to measure against yet", drain.status.fraction)

        drain.onState(state(Wire.DRAIN_IDLE, left = 10))
        assertEquals(0f, drain.status.fraction!!, 1e-6f)

        copyBlock(drain, 1, listOf(readingRow(1)), Wire.DRAIN_FREED, left = 9, freed = 1)
        assertEquals(0.1f, drain.status.fraction!!, 1e-6f)

        // The sensor keeps recording throughout, so what is left can genuinely
        // grow. The bar must not go backwards over it.
        copyBlock(drain, 2, listOf(readingRow(2)), Wire.DRAIN_FREED, left = 11, freed = 2)
        assertEquals(11, drain.status.blocksAtStart)
        assertEquals(0f, drain.status.fraction!!, 1e-6f)
    }

    @Test
    fun `a finished run can be dismissed and a running one cannot`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 1))
        drain.dismiss()
        assertEquals("a running drain must not be cleared off the screen",
            DeviceDrain.Phase.COPYING, drain.status.phase)

        copyBlock(drain, 1, listOf(readingRow(1)), Wire.DRAIN_KEPT)
        assertEquals(DeviceDrain.Phase.DONE, drain.status.phase)
        drain.dismiss()
        assertEquals(DeviceDrain.Phase.IDLE, drain.status.phase)
    }

    @Test
    fun `a frame for a block this device is not writing does not splice into one it is`() {
        val sink = Sink()
        val drain = DeviceDrain(sink)
        drain.start()
        drain.onState(state(Wire.DRAIN_IDLE, left = 2))
        drain.onData(data(1, (1..3).map(::readingRow), done = false))
        // A stray frame for another block. The one in flight was never confirmed,
        // so it is thrown away rather than having someone else's rows appended.
        drain.onData(data(2, (4..5).map(::readingRow), done = false))

        assertNull(sink.blocks[1])
        assertEquals(2, sink.blocks[2]?.size)
        assertTrue(sink.calls.contains("discard:1"))
        assertTrue(sink.freed.isEmpty())
    }
}

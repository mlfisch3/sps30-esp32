package com.sensirion.sps30.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four commands and two messages of backing everything up and clearing the
 * sensor, held to what `wire.py` says they are.
 *
 * The lines here are the ones `firmware/test/gen_wire_vectors.py` generates from
 * `wire.py` and `firmware/test/test_wire.c` asserts the C emits byte for byte.
 * Carrying the same bytes on this side is what makes it three implementations of
 * one protocol rather than two that happen to agree.
 */
class DrainMessagesTest {

    private fun cmdOf(line: String): String =
        Regex(""""cmd"\s*:\s*"([a-z_]+)"""").find(line)?.groupValues?.get(1) ?: "?"

    @Test
    fun `the four commands are the names wire py gives them`() {
        assertEquals("drain_begin", cmdOf(Commands.drainBegin()))
        assertEquals("drain_next", cmdOf(Commands.drainNext()))
        assertEquals("drain_confirm", cmdOf(Commands.drainConfirm(17, 328, 0x0a1b2c3d)))
        assertEquals("drain_cancel", cmdOf(Commands.drainCancel()))
        // JSONL: one line, newline-terminated, like every other command.
        for (line in listOf(
            Commands.drainBegin(), Commands.drainNext(), Commands.drainCancel(),
            Commands.drainConfirm(1, 1, 1),
        )) {
            assertTrue(line.endsWith("\n"))
            assertEquals(1, line.count { it == '\n' })
        }
    }

    /**
     * The digest goes out as unsigned hex.
     *
     * A digest is a bit pattern rather than a quantity. Sent as a number, its top
     * bit becomes a sign somewhere between here and the controller's `strtoul`,
     * and a confirmation that should have matched stops matching - which reads,
     * from the sensor, as a device that does not hold the block.
     */
    @Test
    fun `a confirmation carries its digest as unsigned hex`() {
        val line = Commands.drainConfirm(9, 41, -1)
        assertTrue(line, line.contains(""""digest":"ffffffff""""))
        assertTrue(line, line.contains(""""block":9"""))
        assertTrue(line, line.contains(""""rows":41"""))

        // Eight digits, always, including the small ones.
        assertTrue(Commands.drainConfirm(1, 1, 0x1234).contains(""""digest":"00001234""""))
    }

    /**
     * The exact `drain_state` line `wire.py` produces, parsed.
     *
     * The same one `gen_wire_vectors.py` writes into `PY_DRAIN_STATE_LINE` and
     * the C is asserted against, so all three ends are reading one message.
     */
    @Test
    fun `a drain_state parses every field wire py puts in it`() {
        val line = """{"type":"drain_state","state":"offered","message":"","block":17,""" +
            """"kind":"ring","final":false,"rows":328,"digest":"0a1b2c3d",""" +
            """"blocks_left":461,"blocks_freed":99,"blocks_kept":0,"blocks_refused":0,""" +
            """"readings_copied":32472,"segments_copied":6,"readings_freed":32144}"""
        val msg = Messages.parse(line) as ServerMessage.DrainState

        assertEquals(Wire.DRAIN_OFFERED, msg.state)
        assertEquals(17L, msg.block)
        assertEquals(Wire.DRAIN_KIND_RING, msg.kind)
        assertEquals(false, msg.final)
        assertEquals(328, msg.rows)
        assertEquals(0x0a1b2c3d, msg.digest)
        assertEquals(461, msg.blocksLeft)
        assertEquals(99, msg.blocksFreed)
        assertEquals(32472, msg.readingsCopied)
        assertEquals(6, msg.segmentsCopied)
        assertEquals(32144, msg.readingsFreed)
    }

    /**
     * And the two shapes of `drain_data`.
     *
     * One column list has to describe a dated reading and a clockless one,
     * because one block of the sensor's flash can hold pages written either side
     * of the moment a clock arrived.
     */
    @Test
    fun `a drain_data of readings carries both kinds of row under one column list`() {
        val line = """{"type":"drain_data","block":17,"kind":"ring","columns":""" +
            """["session","index","timestamp","pm1_0","pm2_5","pm4_0","pm10","nc0_5",""" +
            """"nc1_0","nc2_5","nc4_0","nc10","typical_size","event","rel_boot","rel_ms"],""" +
            """"rows":[["SPS30-20260803_100000.csv","0003745","2026-08-03T11:04:05-04:00",""" +
            """"21.50","44.25","47.00","48.50","100.00","110.50","120.25","130.00",""" +
            """"140.75","0.750","","",""],["SPS30-R00000007-0012D450.csv","0003746","",""" +
            """"21.50","44.25","47.00","48.50","100.00","110.50","120.25","130.00",""" +
            """"140.75","0.750","settling","7","1235009"]],"done":true}"""
        val msg = Messages.parse(line) as ServerMessage.DrainData

        assertEquals(17L, msg.block)
        assertEquals(Wire.DRAIN_KIND_RING, msg.kind)
        assertEquals(Wire.DRAIN_READING_COLUMNS, msg.columns)
        assertEquals(2, msg.rows.size)
        assertTrue(msg.done)
        // Both digest, which is the point of the shared list: the dated row
        // through its timestamp and the clockless one through the board's clock.
        assertNotNull(com.sensirion.sps30.data.DeviceDrain.digestReadingRow(msg.rows[0]))
        assertNotNull(com.sensirion.sps30.data.DeviceDrain.digestReadingRow(msg.rows[1]))
    }

    @Test
    fun `a drain_data of preserved summaries carries whole seconds, not timestamps`() {
        val line = """{"type":"drain_data","block":18,"kind":"segments","columns":""" +
            """["seq","session","field","threshold","start_s","stop_s","first","last",""" +
            """"midpoint","mean","variance","minimum","maximum","points","first_index",""" +
            """"last_index","wrap","has_body"],"rows":[["42",""" +
            """"SPS30-20260803_100000.csv","pm2_5","20.0","1785769445","1785769544",""" +
            """"21.5","19.25","44.125","33.75","12.5","18.0","91.5","100","3745","3844",""" +
            """"11","1"]],"done":true}"""
        val msg = Messages.parse(line) as ServerMessage.DrainData

        assertEquals(Wire.DRAIN_KIND_SEGMENTS, msg.kind)
        assertEquals(Wire.DRAIN_SEGMENT_COLUMNS, msg.columns)
        // Seconds rather than ISO, deliberately: a segment of a session recorded
        // with no clock holds a packed board clock in those fields, and a date
        // built from one does not read back - which would fail the digest of a
        // perfectly good copy and stop the whole operation.
        assertEquals("1785769445", msg.rows[0][Wire.DRAIN_SEGMENT_COLUMNS.indexOf("start_s")])
        assertNotNull(com.sensirion.sps30.data.DeviceDrain.digestSegmentRow(msg.rows[0]))
    }

    @Test
    fun `the column lists are wire py's, in wire py's order`() {
        assertEquals(
            listOf("session") + Wire.CSV_COLUMNS_RELATIVE,
            Wire.DRAIN_READING_COLUMNS,
        )
        assertEquals(16, Wire.DRAIN_READING_COLUMNS.size)
        assertEquals(18, Wire.DRAIN_SEGMENT_COLUMNS.size)
        // The two the `alert_segment` message calls `start` and `stop`, in the
        // one unit that means the same thing on a board with a clock and a board
        // without one.
        assertTrue(Wire.DRAIN_SEGMENT_COLUMNS.contains("start_s"))
        assertTrue(Wire.DRAIN_SEGMENT_COLUMNS.contains("stop_s"))
    }

    @Test
    fun `a hex digest survives being written and read`() {
        for (value in listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x0a1b2c3d)) {
            val text = Wire.digestHex(value)
            assertEquals(8, text.length)
            assertEquals(value, java.lang.Long.parseLong(text, 16).toInt())
        }
    }
}

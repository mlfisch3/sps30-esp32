package com.sensirion.sps30.net

import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reassembler must recover whole JSONL lines from arbitrary byte chunking -
 * which is exactly what BLE notifications impose (MTU-capped, so a reading spans
 * several notifications and one notification can hold parts of two lines).
 */
class JsonlReassemblerTest {

    private fun collect(vararg chunks: String): List<ServerMessage> {
        val out = ArrayList<ServerMessage>()
        val r = JsonlReassembler { out.add(it) }
        for (c in chunks) r.feed(c.toByteArray(Charsets.UTF_8))
        return out
    }

    private val reading = """{"type":"reading","index":"0000001","timestamp":"t","pm2_5":1.5}"""
    private val hello = """{"type":"hello","product":"p","serial":"s","firmware":"f","state":"measuring","csv":"c","index":"1"}"""

    @Test fun wholeLineInOneChunk() {
        val m = collect(hello + "\n")
        assertEquals(1, m.size)
        assertTrue(m[0] is ServerMessage.Hello)
    }

    @Test fun lineSplitAcrossManyChunks() {
        // simulate BLE: a 200-byte line delivered in 20-byte notifications
        val full = reading + "\n"
        val chunks = full.chunked(20).toTypedArray()
        val m = collect(*chunks)
        assertEquals(1, m.size)
        assertEquals(1.5, (m[0] as ServerMessage.Reading).values["pm2_5"]!!, 1e-9)
    }

    @Test fun oneChunkHoldsPartsOfTwoLines() {
        // ...end of line A][newline][start of line B... within a single notification
        val stream = hello + "\n" + reading + "\n"
        val m = collect(stream.substring(0, 40), stream.substring(40))
        assertEquals(2, m.size)
        assertTrue(m[0] is ServerMessage.Hello)
        assertTrue(m[1] is ServerMessage.Reading)
    }

    @Test fun trailingPartialLineWaitsForMore() {
        val out = ArrayList<ServerMessage>()
        val r = JsonlReassembler { out.add(it) }
        r.feed((reading.substring(0, 30)).toByteArray())
        assertEquals(0, out.size) // no newline yet
        r.feed((reading.substring(30) + "\n").toByteArray())
        assertEquals(1, out.size)
    }

    @Test fun blankAndGarbageLinesAreSkipped() {
        val m = collect("\n\n", "not json\n", hello + "\n")
        assertEquals(1, m.size) // only the hello parses
    }

    @Test fun multipleReadingsBackToBack() {
        val stream = (1..5).joinToString("") {
            """{"type":"reading","index":"$it","timestamp":"t","pm2_5":$it.0}""" + "\n"
        }
        val m = collect(stream)
        assertEquals(5, m.size)
    }

    // --- a peer that never sends a newline -----------------------------------
    //
    // Nothing about the host is authenticated, so "the peer" is whoever answered
    // the discovery sweep first. Unbounded, a stream with no newline in it grows
    // the buffer until the app dies, and does it quadratically because every
    // chunk copies the whole partial line out again.

    @Test fun aLineOverTheCapIsDroppedRatherThanBuffered() {
        val out = ArrayList<ServerMessage>()
        val r = JsonlReassembler { out.add(it) }
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }

        // Twice the cap, in chunks, with no newline anywhere.
        repeat(2 * JsonlReassembler.MAX_LINE_BYTES / chunk.size) { r.feed(chunk) }
        assertEquals(0, out.size)

        // And the connection recovers: the rest of the over-long line is
        // discarded up to its newline, and the next whole line parses.
        r.feed(("junk\n" + hello + "\n").toByteArray())
        assertEquals(1, out.size)
        assertTrue(out[0] is ServerMessage.Hello)
    }

    @Test fun aLineJustUnderTheCapStillParses() {
        // The cap must clear the largest thing a host legitimately sends, which
        // is a `backup_chunk` carrying 256 KiB of base64.
        val padding = "y".repeat(400_000)
        val big = """{"type":"backup_chunk","seq":0,"data":"$padding","done":false}"""
        assertTrue(big.length < JsonlReassembler.MAX_LINE_BYTES)

        val m = collect(big + "\n")
        assertEquals(1, m.size)
        assertTrue(m[0] is ServerMessage.BackupChunk)
    }

    @Test fun anOverLongLineDoesNotSwallowTheNextOne() {
        val out = ArrayList<ServerMessage>()
        val r = JsonlReassembler { out.add(it) }
        // One feed carrying the end of the over-long line, its newline, and a
        // whole good line behind it.
        r.feed(ByteArray(JsonlReassembler.MAX_LINE_BYTES + 1) { 'x'.code.toByte() })
        r.feed(("\n" + hello + "\n").toByteArray())
        assertEquals(1, out.size)
        assertTrue(out[0] is ServerMessage.Hello)
    }

    @Test fun resetClearsAnOverrunSoTheNextConnectionStartsClean() {
        val out = ArrayList<ServerMessage>()
        val r = JsonlReassembler { out.add(it) }
        r.feed(ByteArray(JsonlReassembler.MAX_LINE_BYTES + 1) { 'x'.code.toByte() })
        r.reset()
        // Without the reset clearing it, this whole line would be eaten as the
        // tail of the dropped one.
        r.feed((hello + "\n").toByteArray())
        assertEquals(1, out.size)
    }
}

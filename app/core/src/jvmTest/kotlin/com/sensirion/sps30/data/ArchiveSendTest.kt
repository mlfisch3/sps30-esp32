package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Commands
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The one flag that decides whether a send back to the sensor ever finishes.
 *
 * The controller answers a transfer when it reads a frame marked `done`, and at
 * no moment of its own choosing. Without one it holds the import open until its
 * own fifteen-second idle close-out gives up on the sender - measured on the
 * board on 2026-08-04, with the same archive replayed twice over a plain socket:
 *
 * | last frame  | the answer arrived after |
 * |-------------|--------------------------|
 * | `done`      | **0.7 s**                |
 * | no `done`   | **15.7 s**               |
 *
 * Twenty-two times slower, and the slow path is the one that then collides with
 * every other fifteen-second timer in the system. So `done` going missing is not
 * a degradation, it is the difference between a feature that works and one that
 * races - and nothing in this app tested that it was there. The frames were
 * built inline inside a coroutine inside `MonitorSession.importArchive`, which
 * needs a `Context`, a `Uri` and a link, and so was reachable only from a
 * device. [ArchiveSend] exists to be reachable from here.
 *
 * The rule, stated once: **exactly one frame carries `done`, and it is the
 * last.**
 */
class ArchiveSendTest {

    private fun row(index: Int): List<String> {
        val stamp = java.time.OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(1_785_000_000L + index),
            java.time.ZoneOffset.ofHours(-4),
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
        val cells = ArrayList<String>()
        cells.add(Wire.formatIndex(index))
        cells.add(stamp)
        List(Wire.FIELDS.size) { f -> (index % 40 + f) * 0.37 }.forEachIndexed { f, v ->
            cells.add(if (f == Wire.FIELDS.size - 1) "%.3f".format(v) else "%.2f".format(v))
        }
        cells.add("")
        return cells
    }

    private fun archiveOf(sessions: Map<String, List<List<String>>>): ByteArray {
        val out = ByteArrayOutputStream()
        SessionArchive.Writer(out).use { writer ->
            for ((name, rows) in sessions) {
                writer.begin(name)
                rows.forEach { writer.row(it) }
            }
        }
        return out.toByteArray()
    }

    private fun chunksOf(bytes: ByteArray, displayName: String): List<ArchiveSend.Chunk> =
        runBlocking {
            val seen = ArrayList<ArchiveSend.Chunk>()
            ArchiveSend.eachChunk(
                open = { ByteArrayInputStream(bytes) },
                displayName = displayName,
            ) { seen.add(it) }
            seen
        }

    /** The frame as the controller reads it: a flat JSON object. */
    private fun parse(line: String): JsonObject {
        assertTrue("a frame must end in a newline: $line", line.endsWith("\n"))
        return Json.parseToJsonElement(line.trim()) as JsonObject
    }

    private fun rowsOf(frame: JsonObject): List<String> =
        frame["rows"]!!.jsonPrimitive.content.let { if (it.isEmpty()) emptyList() else it.split("\n") }

    // --- the rule -------------------------------------------------------------

    @Test fun `exactly one frame carries done and it is the last`() {
        val bytes = archiveOf(
            mapOf(
                "SPS30-20260804_074435.csv" to (1..37).map { row(it) },
                "SPS30-20260804_044020.csv" to (1..8).map { row(it) },
                "SPS30-20260802_205313.csv" to (1..101).map { row(it) },
            ),
        )
        val chunks = chunksOf(bytes, "sps30-export-20260804-080223.zip")

        assertEquals(
            "the frames marked done are not the single last one: " +
                chunks.withIndex().filter { it.value.done }.map { it.index },
            listOf(chunks.size - 1),
            chunks.withIndex().filter { it.value.done }.map { it.index },
        )
        assertTrue("nothing was sent at all", chunks.isNotEmpty())
    }

    @Test fun `the done flag is a JSON boolean, which is what the controller reads`() {
        // The controller reads `done` with `wire_json_bool`, which matches the
        // bare tokens `true` and `false` and nothing else. A quoted "true" is
        // the same silence as no flag at all, and that exact defect - a reader
        // with an integer path and a string path and no boolean path - once hung
        // a transfer on the board. See `test_boolean_fields` in
        // `firmware/test/test_wire.c`.
        val bytes = archiveOf(mapOf("SPS30-20260804_074435.csv" to (1..20).map { row(it) }))
        val chunks = chunksOf(bytes, "sps30-export-20260804-080223.zip")

        for ((i, chunk) in chunks.withIndex()) {
            val done = parse(chunk.line)["done"]
            assertTrue("frame $i has no `done` at all", done is JsonPrimitive)
            assertFalse(
                "frame $i sends `done` as a string, which the controller cannot read",
                (done as JsonPrimitive).isString,
            )
            assertEquals("frame $i disagrees with its own chunk", chunk.done, done.boolean)
        }
        assertTrue("the last frame is not marked done", parse(chunks.last().line)["done"]!!.jsonPrimitive.boolean)
    }

    @Test fun `the closing frame still goes when the last session came out even`() {
        // The batch is empty at that point, and "nothing to send" is the obvious
        // reading of an empty batch. It is wrong: the frame is not carrying rows,
        // it is carrying the flag, and dropping it here would lose `done` on
        // exactly one archive in eight and look like an intermittent fault.
        val even = ArchiveSend.ROWS_PER_FRAME * 3
        val bytes = archiveOf(mapOf("SPS30-20260804_074435.csv" to (1..even).map { row(it) }))
        val chunks = chunksOf(bytes, "sps30-export-20260804-080223.zip")

        assertEquals("the closing frame is missing", 4, chunks.size)
        assertTrue("the closing frame is not marked done", chunks.last().done)
        assertEquals("the closing frame invented rows", 0, chunks.last().rows)
        assertEquals(
            "the closing frame belongs to no session, so there is nothing to close",
            "SPS30-20260804_074435.csv",
            chunks.last().session,
        )
    }

    @Test fun `a lone session CSV ends the same way`() {
        // Not a zip: the picker accepts a single exported session too, and it
        // goes down the same path with one name and no archive around it.
        val header = Wire.CSV_COLUMNS.joinToString(",")
        val body = (1..5).joinToString("\n") { row(it).joinToString(",") }
        val csv = "$header\n$body\n".toByteArray()

        val chunks = chunksOf(csv, "SPS30-20260804_074435.csv")

        assertEquals(1, chunks.size)
        assertTrue("a single session was sent with no `done`", chunks.single().done)
        assertEquals("SPS30-20260804_074435.csv", chunks.single().session)
    }

    // --- and everything else about the frames is unchanged --------------------

    @Test fun `every row goes out once, in order, under its own session`() {
        val sessions = mapOf(
            "SPS30-20260804_074435.csv" to (1..37).map { row(it) },
            "SPS30-20260802_205313.csv" to (100..142).map { row(it) },
        )
        val chunks = chunksOf(archiveOf(sessions), "sps30-export-20260804-080223.zip")

        val rebuilt = LinkedHashMap<String, MutableList<String>>()
        for (chunk in chunks) {
            rebuilt.getOrPut(chunk.session) { ArrayList() }.addAll(rowsOf(parse(chunk.line)))
        }
        assertEquals(
            "the sessions on the wire are not the sessions in the archive",
            sessions.keys,
            rebuilt.keys,
        )
        for ((name, rows) in sessions) {
            assertEquals(
                "$name did not come back row for row",
                rows.map { it.joinToString(",") },
                rebuilt[name],
            )
        }
    }

    @Test fun `rows are batched to the frame size, with the remainder last`() {
        val chunks = chunksOf(
            archiveOf(mapOf("SPS30-20260804_074435.csv" to (1..37).map { row(it) })),
            "sps30-export-20260804-080223.zip",
        )
        // 37 rows: four full frames and a remainder of five, and the remainder
        // is the frame that carries `done`. A frame of its own is needed only
        // when the remainder is nothing, which is the case above this one.
        assertEquals(
            listOf(8, 8, 8, 8, 5),
            chunks.map { rowsOf(parse(it.line)).size },
        )
        assertEquals("the counter disagrees with the payload", listOf(8, 8, 8, 8, 5), chunks.map { it.rows })
        assertEquals("the remainder went without the flag", 5, chunks.last { it.done }.rows)
    }

    @Test fun `seq counts within a session, not within the transfer`() {
        // The controller reads `seq` as a position in the session it is filling.
        val chunks = chunksOf(
            archiveOf(
                mapOf(
                    "SPS30-20260804_074435.csv" to (1..17).map { row(it) },
                    "SPS30-20260802_205313.csv" to (1..9).map { row(it) },
                ),
            ),
            "sps30-export-20260804-080223.zip",
        )
        val bySession = LinkedHashMap<String, MutableList<Int>>()
        for (chunk in chunks) {
            bySession.getOrPut(chunk.session) { ArrayList() }.add(parse(chunk.line)["seq"]!!.jsonPrimitive.int)
        }
        for ((name, seqs) in bySession) {
            assertEquals("$name restarts its numbering somewhere other than 0", (0 until seqs.size).toList(), seqs)
        }
    }

    @Test fun `sessions already finished are counted as the transfer goes`() {
        val chunks = chunksOf(
            archiveOf(
                mapOf(
                    "SPS30-20260804_074435.csv" to (1..9).map { row(it) },
                    "SPS30-20260802_205313.csv" to (1..9).map { row(it) },
                ),
            ),
            "sps30-export-20260804-080223.zip",
        )
        // Two frames per session (eight rows then one), and the count only moves
        // once a session's last frame has actually gone.
        assertEquals(listOf(0, 0, 1, 1), chunks.map { it.sessionsDone })
    }

    // --- the contract with the board -----------------------------------------

    @Test fun `the closing frame is byte for byte what the controller parses`() {
        // The other half of this assertion is `test_boolean_fields` in
        // `firmware/test/test_wire.c`, which reads this exact line with the
        // firmware's own JSON reader and requires `done` to come back true. The
        // two literals are the contract; if either end changes its mind about
        // spelling, key order or types, one of the two suites goes red.
        assertEquals(
            """{"cmd":"put_file","name":"SPS30-20260802_142717.csv","rows":"1,x","seq":3,""" +
                """"done":true}""" + "\n",
            Commands.putFile("SPS30-20260802_142717.csv", "1,x", 3, true),
        )
    }
}

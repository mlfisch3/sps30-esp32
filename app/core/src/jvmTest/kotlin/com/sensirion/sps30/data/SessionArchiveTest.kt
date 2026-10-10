package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The archive format, from both ends.
 *
 * The claims worth making about it are: a session written and read back is the
 * same session; the digest does not depend on the order rows are seen in, which
 * is what lets the controller's page order and a file's index order agree; and a
 * file that has been damaged is **rejected before anything is sent**, which is
 * the whole of "a corrupt import changes nothing" on this side of the wire.
 *
 * The digest's agreement with the C is not asserted here - it cannot be, without
 * the firmware - but the shape it depends on is: the canonical byte layout, the
 * quantisation, and the second-resolution timestamp. `firmware/test/
 * test_store_merge.c` holds the other half to the same shape, and the on-device
 * round trip is what closes the loop.
 */
class SessionArchiveTest {

    private fun row(index: Int, epochSeconds: Long, values: List<Double>, event: String = ""):
        List<String> {
        val stamp = java.time.OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(epochSeconds),
            java.time.ZoneOffset.ofHours(-4),
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
        val cells = ArrayList<String>()
        cells.add(Wire.formatIndex(index))
        cells.add(stamp)
        values.forEachIndexed { f, v ->
            cells.add(if (f == Wire.FIELDS.size - 1) "%.3f".format(v) else "%.2f".format(v))
        }
        cells.add(event)
        return cells
    }

    private fun sample(index: Int): List<String> =
        row(index, 1_785_000_000L + index, List(Wire.FIELDS.size) { f -> (index % 40 + f) * 0.37 })

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

    @Test
    fun `a session written and read back is the same session`() = runBlocking {
        val rows = (1..200).map { sample(it) }
        val bytes = archiveOf(mapOf("SPS30-20260802_142717.csv" to rows))

        val seen = ArrayList<List<String>>()
        val names = ArrayList<String>()
        SessionArchive.eachSession(
            open = { ByteArrayInputStream(bytes) },
            displayName = "sps30-export-20260802-150000.zip",
            onSession = { names.add(it) },
            emit = { seen.add(it) },
        )
        assertEquals(listOf("SPS30-20260802_142717.csv"), names)
        assertEquals(200, seen.size)
        // Cell for cell, including the zero-padded index and the event column.
        assertEquals(rows, seen)
    }

    @Test
    fun `all ten values survive, including PM4 and PM10`() = runBlocking {
        // HANDOFF item 4 drops PM4.0 and PM10 from the numeric readout only. An
        // export that lost them would be losing measured data to a display
        // decision, which is why this is asserted rather than assumed.
        assertTrue("pm4_0" in Wire.CSV_COLUMNS)
        assertTrue("pm10" in Wire.CSV_COLUMNS)

        val original = row(1, 1_785_000_000L, listOf(1.11, 2.22, 3.33, 4.44, 5.5, 6.6, 7.7, 8.8, 9.9, 0.75))
        val bytes = archiveOf(mapOf("SPS30-20260802_142717.csv" to listOf(original)))
        val seen = ArrayList<List<String>>()
        SessionArchive.eachSession(
            open = { ByteArrayInputStream(bytes) },
            displayName = "x.zip",
            onSession = { },
            emit = { seen.add(it) },
        )
        assertEquals(1, seen.size)
        assertEquals("3.33", seen[0][Wire.CSV_COLUMNS.indexOf("pm4_0")])
        assertEquals("4.44", seen[0][Wire.CSV_COLUMNS.indexOf("pm10")])
    }

    @Test
    fun `the digest does not depend on the order rows are seen in`() {
        val rows = (1..50).map { sample(it) }
        val forwards = rows.fold(0) { acc, r -> SessionArchive.fold(acc, SessionArchive.digestRow(r)!!) }
        val backwards = rows.reversed()
            .fold(0) { acc, r -> SessionArchive.fold(acc, SessionArchive.digestRow(r)!!) }
        assertEquals(forwards, backwards)

        // A missing reading, or one value a quantum out, has to show.
        val short = rows.drop(1).fold(0) { acc, r -> SessionArchive.fold(acc, SessionArchive.digestRow(r)!!) }
        assertNotEquals(forwards, short)

        val nudged = rows.toMutableList()
        nudged[10] = nudged[10].toMutableList().also { it[2] = "%.2f".format(it[2].toDouble() + 0.01) }
        val changed = nudged.fold(0) { acc, r -> SessionArchive.fold(acc, SessionArchive.digestRow(r)!!) }
        assertNotEquals(forwards, changed)
    }

    /**
     * The digest agrees with the firmware's, on rows the sensor really measured.
     *
     * These eight rows and the value they digest to came out of
     * `firmware/test/replay_store --sample 8`, run over an image of a board's
     * `store` partition. Both halves are real: the rows are what the export
     * writes, and `b0361fd2` is what `store_digest_reading` and
     * `store_digest_fold` produce for them in C.
     *
     * This is the check the erase gate stands on. The controller will not erase
     * a session unless the digest this app sends matches the one it computes
     * itself, so if the two ever drift apart the erase silently stops working -
     * or, far worse, starts agreeing about data that is not the same data. A
     * test on either side alone cannot see that; only a shared expected value
     * can, which is why one is carried here rather than derived.
     */
    @Test
    fun `the digest agrees with the firmware on real measured rows`() {
        val rows = listOf(
            "0000001,2026-08-02T18:47:42-04:00,5.39,18.01,27.97,29.96,10.29,29.78,42.23,44.63,45.00,1.630,settling",
            "0000002,2026-08-02T18:47:43-04:00,2.19,5.84,8.68,9.25,7.31,13.69,17.28,17.97,18.08,0.851,settling",
            "0000003,2026-08-02T18:47:44-04:00,2.00,2.44,2.69,2.75,12.71,15.54,15.96,16.03,16.04,0.641,settling",
            "0000004,2026-08-02T18:47:45-04:00,2.03,2.27,2.36,2.38,13.32,15.98,16.20,16.23,16.24,0.584,settling",
            "0000005,2026-08-02T18:47:46-04:00,2.11,2.23,2.23,2.23,14.10,16.73,16.84,16.84,16.85,0.488,settling",
            "0000006,2026-08-02T18:47:47-04:00,2.19,2.32,2.32,2.32,14.66,17.40,17.52,17.52,17.53,0.439,settling",
            "0000007,2026-08-02T18:47:48-04:00,2.28,2.41,2.41,2.41,15.21,18.05,18.17,18.18,18.18,0.458,settling",
            "0000008,2026-08-02T18:47:49-04:00,2.34,2.47,2.47,2.47,15.63,18.55,18.67,18.68,18.69,0.485,settling",
        ).map { it.split(',') }

        val digest = rows.fold(0) { acc, r ->
            SessionArchive.fold(acc, SessionArchive.digestRow(r) ?: error("real row did not digest: $r"))
        }
        assertEquals("b0361fd2", SessionArchive.digestHex(digest))

        // And order-independently, which is what makes it usable at all: the
        // controller reads its pages in write order, this app reads a file in
        // index order, and after a merge those are not the same order.
        val reversed = rows.reversed()
            .fold(0) { acc, r -> SessionArchive.fold(acc, SessionArchive.digestRow(r)!!) }
        assertEquals(digest, reversed)
    }

    /**
     * And it agrees on a row the board recorded with no clock.
     *
     * A relative page holds the packed `(run, ms)` pair in the field an absolute
     * one holds an instant, so the digest is taken over that packed value and
     * not over the empty `timestamp` cell - which digests to nothing at all and
     * would have made every clockless backup unprovable, and so unerasable.
     *
     * The row is the first reading of run 1202, real board output from
     * 2026-08-03 and the same row `BoardSessionsTest`'s resources hold.
     * `d5740e7b` is what `store_digest_reading` produces for it in C - see
     * `test_a_clockless_reading_digests_over_its_packed_clock` in
     * `firmware/test/test_store_merge.c`, which carries the same reading.
     * Change either side and move both.
     */
    @Test
    fun `the digest agrees with the firmware on a row recorded with no clock`() {
        val row = ("0000001,,2.50,10.83,17.45,18.77,0.00,11.22,19.45,21.04,21.28,1.630," +
            "settling,1202,2081").split(',')
        assertEquals(Wire.CSV_COLUMNS_RELATIVE.size, row.size)
        assertEquals("d5740e7b", SessionArchive.digestHex(SessionArchive.digestRow(row)!!))

        // The run is what separates two readings that share a millisecond count,
        // which is why it rides in the top half of the packed field rather than
        // being left to the session name.
        val otherRun = row.toMutableList().also { it[it.size - 2] = "1196" }
        assertNotEquals(SessionArchive.digestRow(row), SessionArchive.digestRow(otherRun))

        // The same cells without the two columns - which is exactly what the old
        // export wrote - carry no time at all and cannot be digested. That is the
        // honest answer, and the reason the columns had to exist.
        assertNull(SessionArchive.digestRow(row.dropLast(2)))
    }

    @Test
    fun `a row that cannot be placed does not count towards a digest`() {
        assertNull(SessionArchive.digestRow(listOf("", "2026-08-02T14:27:17-04:00")))
        assertNull(SessionArchive.digestRow(listOf("0", "2026-08-02T14:27:17-04:00")))
        assertNull(SessionArchive.digestRow(listOf("5", "not-a-time")))
    }

    /**
     * Quantisation agrees with `store_quantise`, value for value.
     *
     * The expectations are **measured from the C**, not reasoned about. Two of
     * them are the reason: 0.015 quantises to 1 and 0.025 to 3, which looks like
     * inconsistent rounding and is not - it is that neither is representable as a
     * `Float`, so one lands just below its halfway point and the other just
     * above. Both sides do the arithmetic through a `Float` for exactly this
     * reason, so both get the same answer, and an "obviously correct" expectation
     * of 2 and 3 here would be asserting something no implementation does.
     *
     * These are the values that make the two digests comparable at all, so a
     * change on either side has to break this test.
     */
    @Test
    fun `quantisation matches the C, value for value`() {
        // Two decimals for the nine concentrations, three for particle size -
        // exactly the store's quantum, so a value that goes out and comes back is
        // the same integer rather than nearly it.
        assertEquals(1234, SessionArchive.quantise(12.34, 0))
        assertEquals(-1234, SessionArchive.quantise(-12.34, 0))
        assertEquals(750, SessionArchive.quantise(0.75, Wire.FIELDS.size - 1))
        assertEquals(12340, SessionArchive.quantise(12.34, Wire.FIELDS.size - 1))

        // Half away from zero, on the value as a Float. See above.
        assertEquals(1, SessionArchive.quantise(0.015, 0))
        assertEquals(-1, SessionArchive.quantise(-0.015, 0))
        assertEquals(3, SessionArchive.quantise(0.025, 0))
        assertEquals(-3, SessionArchive.quantise(-0.025, 0))
    }

    @Test
    fun `an intact archive surveys clean`() {
        val bytes = archiveOf(
            mapOf(
                "SPS30-20260802_142717.csv" to (1..120).map { sample(it) },
                "SPS30-20260801_090000.csv" to (1..40).map { sample(it) },
            ),
        )
        val survey = SessionArchive.survey({ ByteArrayInputStream(bytes) }, "export.zip").getOrThrow()
        assertTrue(survey.hadManifest)
        assertEquals(2, survey.sessions.size)
        assertEquals(160, survey.rows)
        assertEquals(0, survey.unreadable)
    }

    @Test
    fun `a truncated archive is rejected and names what is wrong`() {
        val bytes = archiveOf(mapOf("SPS30-20260802_142717.csv" to (1..300).map { sample(it) }))
        // Cut the file short, as an interrupted copy would. The zip's own entry
        // data is damaged, so this is the realistic shape of the failure.
        val cut = bytes.copyOf(bytes.size / 2)
        val result = SessionArchive.survey({ ByteArrayInputStream(cut) }, "export.zip")
        assertTrue("a truncated archive was accepted", result.isFailure)
        val reason = (result.exceptionOrNull() as SessionArchive.ArchiveRejected).rejection.reason
        assertTrue("unhelpful reason: $reason", reason.isNotEmpty())
    }

    @Test
    fun `an archive whose rows were tampered with fails its manifest`() {
        val bytes = archiveOf(mapOf("SPS30-20260802_142717.csv" to (1..100).map { sample(it) }))
        // Rebuild the zip with one row's value altered but the original manifest
        // left in place: exactly what a partially-written or edited file looks
        // like, and the case a row count alone would not catch.
        val doctored = rewriteFirstValue(bytes)
        val result = SessionArchive.survey({ ByteArrayInputStream(doctored) }, "export.zip")
        assertTrue("a doctored archive was accepted", result.isFailure)
    }

    @Test
    fun `a file that is not session data is refused`() {
        val result = SessionArchive.survey(
            { ByteArrayInputStream("hello".toByteArray()) },
            "notes.txt",
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `an empty archive is refused rather than sent as nothing`() {
        val bytes = archiveOf(mapOf("SPS30-20260802_142717.csv" to emptyList()))
        val result = SessionArchive.survey({ ByteArrayInputStream(bytes) }, "export.zip")
        assertTrue(result.isFailure)
    }

    @Test
    fun `a lone session CSV is accepted on its own`() {
        val rows = (1..30).map { sample(it) }
        val csv = buildString {
            append(SessionArchive.HEADER).append('\n')
            rows.forEach { append(it.joinToString(",")).append('\n') }
        }
        val survey = SessionArchive
            .survey({ ByteArrayInputStream(csv.toByteArray()) }, "SPS30-20260802_142717.csv")
            .getOrThrow()
        assertEquals(1, survey.sessions.size)
        assertEquals(30, survey.rows)
        // No manifest to check against, so it is accepted on structure alone.
        assertTrue(!survey.hadManifest)
    }

    @Test
    fun `the backup claim is what the controller asks for`() {
        val entries = listOf(
            SessionArchive.Entry("SPS30-20260802_142717.csv", 120, 0x1234ABCD),
            SessionArchive.Entry("SPS30-20260801_090000.csv", 0, 0),
        )
        val claim = SessionArchive.backupClaim(entries)
        // Sessions with no readings are left out: there is nothing to confirm.
        assertEquals("SPS30-20260802_142717.csv,120,1234abcd", claim)
    }

    @Test
    fun `a negative digest survives the hex round trip`() {
        // The digest is a bit pattern, not a count. Written unsigned, read back
        // through a Long, so the top bit is not lost to a sign somewhere.
        val entries = listOf(SessionArchive.Entry("SPS30-20260802_142717.csv", 5, -1))
        val parsed = SessionArchive.parseManifest(SessionArchive.manifestJson(entries))!!
        assertEquals(-1, parsed[0].digest)
    }

    @Test
    fun `a session name is recognised, and other names are not`() {
        assertTrue(SessionArchive.isSessionName("SPS30-20260802_142717.csv"))
        assertTrue(!SessionArchive.isSessionName("sps30-export-20260802-150000.zip"))
        assertTrue(!SessionArchive.isSessionName("MANIFEST.json"))
        assertTrue(!SessionArchive.isSessionName("sps30-20260802-150000.csv"))
    }

    /** Rebuild an archive with one value changed, leaving the manifest alone. */
    private fun rewriteFirstValue(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val content = zis.readBytes()
                    zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    if (entry.name == SessionArchive.MANIFEST) {
                        zos.write(content)
                    } else {
                        val text = content.decodeToString()
                        val lines = text.lines().toMutableList()
                        val cells = lines[1].split(',').toMutableList()
                        cells[2] = "%.2f".format(cells[2].toDouble() + 0.5)
                        lines[1] = cells.joinToString(",")
                        zos.write(lines.joinToString("\n").toByteArray())
                    }
                    zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}

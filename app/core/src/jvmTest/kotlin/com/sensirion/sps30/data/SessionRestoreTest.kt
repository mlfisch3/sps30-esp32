package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Putting readings back into the record from a file.
 *
 * The case this was built for is the captain's: a hole in the plot from 07:42 to
 * 11:19 on Aug 4, the 12,669 readings that belong in it alive only in a copy on
 * a laptop, and the sensor's ring long since wrapped past the window so gap-fill
 * correctly reports it unfillable. Everything here is about the two properties
 * that make such a restore safe to press rather than a gamble with a record:
 *
 * - **It can only add.** Overlap merges, a second run of the same file does
 *   nothing, and nothing already held is overwritten, reordered or dropped.
 * - **A file it should not read, it does not read.** A refusal changes nothing
 *   at all, which is why every refusal test asserts the store afterwards rather
 *   than only the message.
 */
class SessionRestoreTest {

    // --- building files the way the board writes them -------------------------

    private val header = Wire.CSV_COLUMNS.joinToString(",")

    /** Local wall clock of the session the captain lost, and of its file's name. */
    private val gapStart = LocalDateTime.of(2026, 8, 4, 7, 44, 35)
    private val gapSession = "SPS30-20260804_074435.csv"

    /** The board writes local time with an offset; Eastern in August is -04:00. */
    private fun stamp(at: LocalDateTime): String = at.toString().let {
        // LocalDateTime.toString() drops ":00" seconds, and a session row never
        // does, so the fixed-width form the fast parser wants is built by hand.
        "%04d-%02d-%02dT%02d:%02d:%02d-04:00".format(
            at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second,
        )
    }

    private fun epochOf(at: LocalDateTime): Double =
        at.toEpochSecond(ZoneOffset.ofHours(-4)).toDouble()

    /**
     * One session's CSV: [count] readings at 1 Hz from [from], indexed from
     * [firstIndex], with a value that is a function of the index so a reading
     * can be recognised again after a merge.
     */
    private fun sessionCsv(
        from: LocalDateTime = gapStart,
        count: Int = 10,
        firstIndex: Int = 1,
        event: (Int) -> String = { "" },
    ): String = buildString {
        append(header).append('\n')
        for (n in 0 until count) {
            val index = firstIndex + n
            append(Wire.formatIndex(index)).append(',')
            append(stamp(from.plusSeconds(n.toLong()))).append(',')
            append(Wire.FIELDS.joinToString(",") { "%.2f".format(index.toDouble() / 100.0) })
            append(',').append(event(index)).append('\n')
        }
    }

    private fun bytes(text: String): () -> InputStream =
        { ByteArrayInputStream(text.toByteArray()) }

    private fun history(retention: Double = 1_000_000.0) =
        TimeSeriesHistory(retentionSeconds = retention, nowSeconds = { epochOf(gapStart) + 86_400 })

    private fun restore(
        h: TimeSeriesHistory,
        text: String,
        name: String = gapSession,
    ): Result<SessionRestore.Report> = runBlocking {
        SessionRestore.restore(bytes(text), name, h)
    }

    private fun refusalOf(result: Result<SessionRestore.Report>): SessionRestore.Refusal {
        val e = result.exceptionOrNull()
        assertNotNull("expected a refusal, got ${result.getOrNull()}", e)
        assertTrue("expected a FileRefused, got $e", e is SessionRestore.FileRefused)
        return (e as SessionRestore.FileRefused).refusal
    }

    // --- 1. readings the record lacks are added, and exactly those -------------

    @Test fun `a file the record lacks contributes exactly its readings`() {
        val h = history()
        val report = restore(h, sessionCsv(count = 500)).getOrThrow()

        assertEquals(500, report.readings)
        assertEquals(500, report.added)
        assertEquals(0, report.alreadyHeld)
        assertEquals(0, report.undated)
        assertEquals(1, report.sessions)
        assertEquals(500, h.size)
        // Filed under the session its name names, not under the file's name.
        assertEquals(setOf(gapSession), h.snapshot().map { it.csv }.toSet())
        assertEquals((1..500).toList(), h.snapshot().map { it.index })
    }

    @Test fun `the readings that land are the readings the file held`() {
        val h = history()
        restore(h, sessionCsv(count = 20)).getOrThrow()
        val stored = h.snapshot()
        assertEquals(epochOf(gapStart), stored.first().epoch!!, 1e-6)
        assertEquals(epochOf(gapStart.plusSeconds(19)), stored.last().epoch!!, 1e-6)
        // Values are a function of the index, so this proves cell alignment.
        assertEquals(0.01, stored.first().values["pm2_5"]!!, 1e-9)
        assertEquals(0.20, stored.last().values["pm2_5"]!!, 1e-9)
    }

    // --- 2. the same file twice is harmless -----------------------------------

    @Test fun `restoring the same file twice changes nothing the second time`() {
        val h = history()
        val text = sessionCsv(count = 300)
        restore(h, text).getOrThrow()
        val before = h.snapshot()

        val second = restore(h, text).getOrThrow()

        assertEquals(300, second.readings)
        assertEquals("a re-run must add nothing", 0, second.added)
        assertEquals(300, second.alreadyHeld)
        assertEquals(300, h.size)
        // Not merely the same count: the same readings, in the same order, with
        // the same values. "Never overwrite" is a claim about these, not a count.
        val after = h.snapshot()
        assertEquals(before.map { it.csv to it.index }, after.map { it.csv to it.index })
        assertEquals(before.map { it.epoch }, after.map { it.epoch })
        assertEquals(before.map { it.values }, after.map { it.values })
    }

    @Test fun `a copy renamed on the way through is still the same session`() {
        val h = history()
        val text = sessionCsv(count = 120)
        restore(h, text, gapSession).getOrThrow()

        // What a file that has been to a host and back is actually called. The
        // merge de-duplicates on the session name, so reading this as a session
        // of its own would draw every one of these readings a second time.
        val again = restore(
            h, text, "SPS30-20260804_074435-recovered-gap.csv",
        ).getOrThrow()

        assertEquals(0, again.added)
        assertEquals(120, again.alreadyHeld)
        assertEquals(120, h.size)
        assertEquals(setOf(gapSession), h.snapshot().map { it.csv }.toSet())
    }

    @Test fun `a shouted extension and a copy number are still the same session`() {
        assertEquals(gapSession, SessionRestore.sessionKeyOf("SPS30-20260804_074435.CSV"))
        assertEquals(gapSession, SessionRestore.sessionKeyOf("SPS30-20260804_074435 (1).csv"))
        assertEquals(gapSession, SessionRestore.sessionKeyOf("SPS30-20260804_074435.csv"))
        assertEquals(
            "SPS30-R0000012A-0004ABCD.csv",
            SessionRestore.sessionKeyOf("SPS30-R0000012A-0004ABCD-copy.csv"),
        )
        assertNull(SessionRestore.sessionKeyOf("readings.csv"))
        assertNull(SessionRestore.sessionKeyOf("statement-2026.csv"))
        // A path is stripped before matching, so a zip entry is as safe as a name.
        assertEquals(gapSession, SessionRestore.sessionKeyOf("a/b/SPS30-20260804_074435.csv"))
        assertNull(SessionRestore.sessionKeyOf("../../SPS30.csv"))
    }

    // --- 3. overlap merges rather than doubling -------------------------------

    @Test fun `an overlapping range merges without duplication`() {
        val h = history()
        // What the device already had: the first half of the session.
        restore(h, sessionCsv(count = 500)).getOrThrow()

        // What the backup holds: the whole of it.
        val report = restore(h, sessionCsv(count = 1_000)).getOrThrow()

        assertEquals(1_000, report.readings)
        assertEquals("only the half it lacked", 500, report.added)
        assertEquals(500, report.alreadyHeld)
        assertEquals(1_000, h.size)
        assertEquals((1..1_000).toList(), h.snapshot().map { it.index })
    }

    @Test fun `a file that fills a hole in the middle merges into it`() {
        val h = history()
        // A record with a hole: 1..200 and 801..1000, nothing between.
        restore(h, sessionCsv(count = 200, firstIndex = 1)).getOrThrow()
        restore(
            h,
            sessionCsv(from = gapStart.plusSeconds(800), count = 200, firstIndex = 801),
        ).getOrThrow()
        assertEquals(400, h.size)

        val report = restore(h, sessionCsv(count = 1_000)).getOrThrow()

        assertEquals(600, report.added)
        assertEquals(400, report.alreadyHeld)
        assertEquals(1_000, h.size)
        // Ordered by time, with the middle now present. A merge that appended
        // would leave the tail out of order and every window would read wrong.
        assertEquals((1..1_000).toList(), h.snapshot().map { it.index })
    }

    @Test fun `restoring one session leaves another session untouched`() {
        val h = history()
        val other = "SPS30-20260803_120000.csv"
        restore(
            h,
            sessionCsv(from = LocalDateTime.of(2026, 8, 3, 12, 0, 0), count = 50),
            other,
        ).getOrThrow()
        val kept = h.snapshot().filter { it.csv == other }

        restore(h, sessionCsv(count = 50)).getOrThrow()

        val after = h.snapshot().filter { it.csv == other }
        assertEquals(kept.map { it.index }, after.map { it.index })
        assertEquals(kept.map { it.epoch }, after.map { it.epoch })
        assertEquals(kept.map { it.values }, after.map { it.values })
    }

    // --- 4. the counts are an account, not a flatter --------------------------

    @Test fun `the counts add up to the readings the file held`() {
        val h = history()
        restore(h, sessionCsv(count = 400)).getOrThrow()
        val report = restore(h, sessionCsv(count = 1_000)).getOrThrow()

        assertEquals(
            report.readings,
            report.added + report.alreadyHeld + report.undated,
        )
        assertTrue(report.allLanded)
    }

    @Test fun `added is what the record actually grew by`() {
        val h = history()
        restore(h, sessionCsv(count = 250)).getOrThrow()
        val before = h.size
        val report = restore(h, sessionCsv(count = 900)).getOrThrow()
        assertEquals(report.added, h.size - before)
    }

    @Test fun `a row nothing can date is counted as undated, not as already held`() {
        val h = history()
        // One row whose timestamp is not a time at all. It is the kind a
        // half-written line leaves, and calling it "already here" would be the
        // comfortable lie: it is data that did not make it.
        val text = sessionCsv(count = 5).lines().toMutableList().also {
            it[3] = it[3].replaceFirst(Regex("""\d{4}-\d{2}-\d{2}T\S+?,"""), "not-a-time,")
        }.joinToString("\n")

        val report = restore(h, text).getOrThrow()

        assertEquals(5, report.readings)
        assertEquals(4, report.added)
        assertEquals(0, report.alreadyHeld)
        assertEquals(1, report.undated)
        assertEquals(4, h.size)
        // The survey saw it too, and says so separately.
        assertEquals(1, report.unreadableRows)
    }

    @Test fun `the covered span is the range the file put back`() {
        val h = history()
        val report = restore(h, sessionCsv(count = 12_669)).getOrThrow()
        assertEquals(epochOf(gapStart), report.fileStart!!, 1e-6)
        assertEquals(epochOf(gapStart.plusSeconds(12_668)), report.fileEnd!!, 1e-6)
        assertEquals(report.fileStart, report.recordStart)
        assertEquals(report.fileEnd, report.recordEnd)
    }

    // --- 5. a file it should not read, it does not read ------------------------

    @Test fun `a file whose name carries no session is refused and changes nothing`() {
        val h = history()
        restore(h, sessionCsv(count = 10)).getOrThrow()

        val refusal = refusalOf(restore(h, sessionCsv(count = 10), "readings.csv"))

        assertTrue(refusal is SessionRestore.Refusal.NotASession)
        assertTrue("the reason must say how to fix it", refusal.reason.contains("Rename"))
        assertEquals("nothing may be added by a refusal", 10, h.size)
    }

    @Test fun `a foreign file that happens to be named like a session is refused`() {
        val h = history()
        val refusal = refusalOf(restore(h, "name,quantity,price\nwidget,2,3.50\n"))
        assertTrue(refusal is SessionRestore.Refusal.Damaged)
        assertEquals(0, h.size)
    }

    @Test fun `a session file holding only a header is refused`() {
        val h = history()
        val refusal = refusalOf(restore(h, "$header\n"))
        assertTrue(refusal is SessionRestore.Refusal.Damaged)
        assertTrue(refusal.reason.contains("no readings"))
        assertEquals(0, h.size)
    }

    @Test fun `the afternoon's session renamed onto the morning's name is refused`() {
        val h = history()
        // The mistake a person actually makes with a folder of backups: the
        // wrong whole session, given the name of the one they meant. It starts
        // at reading 1, and reading 1 is the reading the name was made from, so
        // the two disagreeing is proof rather than suspicion.
        val text = sessionCsv(from = LocalDateTime.of(2026, 8, 4, 14, 30, 0), count = 20)

        val refusal = refusalOf(restore(h, text, gapSession))

        assertTrue(refusal is SessionRestore.Refusal.WrongSession)
        assertTrue(refusal.reason.contains(gapSession))
        assertEquals(0, h.size)
    }

    @Test fun `a file whose readings start before its own name is refused`() {
        val h = history()
        val text = sessionCsv(from = gapStart.minusHours(3), count = 20)
        assertTrue(
            refusalOf(restore(h, text, gapSession)) is SessionRestore.Refusal.WrongSession,
        )
        assertEquals(0, h.size)
    }

    @Test fun `a partial copy that begins late is not mistaken for the wrong session`() {
        val h = history()
        // A copy fetched from part way through is still that session. It does
        // not start at reading 1, which is exactly what says the late start is
        // where the copy begins rather than where the session did.
        val text = sessionCsv(from = gapStart.plusSeconds(9_000), count = 30, firstIndex = 9_001)
        val report = restore(h, text, gapSession).getOrThrow()
        assertEquals(30, report.added)
    }

    @Test fun `a file that begins a moment either side of its name is still its own`() {
        val early = history()
        assertEquals(
            5,
            restore(early, sessionCsv(from = gapStart.minusSeconds(2), count = 5), gapSession)
                .getOrThrow().added,
        )
        val late = history()
        assertEquals(
            5,
            restore(late, sessionCsv(from = gapStart.plusSeconds(2), count = 5), gapSession)
                .getOrThrow().added,
        )
    }

    @Test fun `a truncated archive is refused whole rather than merged in part`() {
        val h = history()
        val intact = archiveOf(gapSession to sessionCsv(count = 40))
        // What a transfer cut short leaves: a zip that opens, entries that parse,
        // and a manifest that no longer describes them.
        val short = archiveOf(gapSession to sessionCsv(count = 40), manifestRows = 41)

        assertEquals(40, runBlocking {
            SessionRestore.restore({ ByteArrayInputStream(intact) }, "a.zip", h)
        }.getOrThrow().added)

        val fresh = history()
        val refusal = refusalOf(runBlocking {
            SessionRestore.restore({ ByteArrayInputStream(short) }, "b.zip", fresh)
        })
        assertTrue(refusal is SessionRestore.Refusal.Damaged)
        assertEquals("a damaged archive must merge nothing at all", 0, fresh.size)
    }

    @Test fun `a file that is not a zip at all is refused rather than half read`() {
        val h = history()
        val refusal = refusalOf(runBlocking {
            SessionRestore.restore({ ByteArrayInputStream("not a zip".toByteArray()) }, "x.zip", h)
        })
        assertTrue(refusal is SessionRestore.Refusal.Damaged)
        assertEquals(0, h.size)
    }

    // --- 6. an archive of sessions ---------------------------------------------

    @Test fun `an archive contributes every session in it, once`() {
        val h = history()
        val zip = archiveOf(
            gapSession to sessionCsv(count = 60),
            "SPS30-20260803_120000.csv" to
                sessionCsv(from = LocalDateTime.of(2026, 8, 3, 12, 0, 0), count = 40),
        )
        val first = runBlocking {
            SessionRestore.restore({ ByteArrayInputStream(zip) }, "backup.zip", h)
        }.getOrThrow()

        assertEquals(100, first.readings)
        assertEquals(100, first.added)
        assertEquals(2, first.sessions)

        val again = runBlocking {
            SessionRestore.restore({ ByteArrayInputStream(zip) }, "backup.zip", h)
        }.getOrThrow()
        assertEquals(0, again.added)
        assertEquals(100, again.alreadyHeld)
        assertEquals(100, h.size)
    }

    // --- 7. the clockless case, said plainly rather than mis-placed -------------

    @Test fun `a clockless session with nothing to place it is refused, not guessed`() {
        val h = history()
        h.basis = null
        val name = "SPS30-R0000012A-0004ABCD.csv"

        val refusal = refusalOf(restore(h, relativeCsv(count = 30), name))

        assertTrue(refusal is SessionRestore.Refusal.NoClock)
        assertTrue(
            "it must say why, and what would change it",
            refusal.reason.contains("no clock") && refusal.reason.contains("Connect"),
        )
        assertEquals("nothing may be placed at a time it was not taken", 0, h.size)
    }

    @Test fun `a clockless session is kept, and reported unplaced, when it can be`() {
        val h = history()
        val basis = TimeBasis()
        h.basis = basis
        val name = "SPS30-R0000012A-0004ABCD.csv"

        val report = restore(h, relativeCsv(count = 30), name).getOrThrow()

        assertEquals(30, report.added)
        assertEquals(30, h.size)
        // In the record, counted and exportable, and honestly reported as having
        // no date until the run is placed.
        assertEquals(30, report.unplaced)
        assertNull(report.fileStart)

        // And once something places the run, they take their place on the axis.
        basis.accept(
            ServerMessage.TimeMap(
                298,
                listOf(ServerMessage.TimeAnchor(298, 5_000L, 1_785_733_000_000L, -240)),
            ),
        )
        h.retime()
        assertEquals(0, h.unplacedCount())
    }

    @Test fun `a mixed archive keeps the dated sessions and says what it left out`() {
        val h = history()
        h.basis = null
        val zip = archiveOf(
            gapSession to sessionCsv(count = 25),
            "SPS30-R0000012A-0004ABCD.csv" to relativeCsv(count = 30),
        )

        val report = runBlocking {
            SessionRestore.restore({ ByteArrayInputStream(zip) }, "mixed.zip", h)
        }.getOrThrow()

        assertEquals(25, report.added)
        assertEquals(1, report.clocklessSkipped)
        assertEquals(25, h.size)
        assertEquals(setOf(gapSession), h.snapshot().map { it.csv }.toSet())
    }

    // --- 8. retention must not delete what was just asked for -------------------

    @Test fun `retention does not take back a morning restored into it`() {
        // A 48h store, a live reading now, and a restore of readings from three
        // days ago. Without the explicit floor the merge holds down, retention
        // deletes them on the way in - which is how a backup becomes worthless.
        val now = epochOf(gapStart) + 3 * 86_400
        val h = TimeSeriesHistory(retentionSeconds = 172_800.0, nowSeconds = { now })
        h.add(
            "SPS30-20260807_074435.csv", "0000001",
            stamp(gapStart.plusDays(3)), Wire.PM_FIELDS.associateWith { 1.0 },
        )

        val report = restore(h, sessionCsv(count = 200)).getOrThrow()

        assertEquals(200, report.added)
        assertEquals(200, h.snapshot().count { it.csv == gapSession })
        assertNotNull("the floor must be held down to cover it", h.retentionFloor())
    }

    // --- 9. the whole point: a gap on the plot closes ---------------------------

    @Test fun `the captain's morning closes the hole it was missing from`() {
        val h = history()
        // The record either side of the hole, at 1 Hz, as the plot had it.
        val before = LocalDateTime.of(2026, 8, 4, 7, 12, 18)
        restore(h, sessionCsv(from = before, count = 1_800), "SPS30-20260804_071218.csv")
            .getOrThrow()
        val after = LocalDateTime.of(2026, 8, 4, 11, 18, 52)
        restore(h, sessionCsv(from = after, count = 1_800), "SPS30-20260804_111852.csv")
            .getOrThrow()

        val retention = SensorRetention.UNKNOWN
        val holes = RecordGaps.of(h.placedEpochs(), cadenceSeconds = 1.0, retention = retention)
        assertEquals("the hole must be there to start with", 1, holes.size)

        // The 12,669 readings, from the host, under the name a person gave them.
        val report = restore(
            h,
            sessionCsv(from = gapStart, count = 12_669),
            "SPS30-20260804_074435-recovered-gap.csv",
        ).getOrThrow()

        assertEquals(12_669, report.added)
        assertTrue(report.allLanded)
        val closed = RecordGaps.of(h.placedEpochs(), cadenceSeconds = 1.0, retention = retention)
        assertTrue("the gap must be gone, not merely smaller: $closed", closed.isEmpty())
    }

    // --- helpers ---------------------------------------------------------------

    /** A clockless session's CSV: no timestamps, a board clock instead. */
    private fun relativeCsv(count: Int, boot: Int = 298): String = buildString {
        append(Wire.CSV_COLUMNS_RELATIVE.joinToString(",")).append('\n')
        for (n in 0 until count) {
            append(Wire.formatIndex(n + 1)).append(",,")
            append(Wire.FIELDS.joinToString(",") { "1.50" })
            append(",,").append(boot).append(',').append(5_000 + n * 1_000).append('\n')
        }
    }

    /**
     * A session archive, written by the writer that writes the real ones.
     *
     * [manifestRows] overstates one session's row count, which is exactly the
     * shape a transfer cut short leaves behind: everything parses and the
     * manifest is the only thing that knows.
     */
    private fun archiveOf(
        vararg sessions: Pair<String, String>,
        manifestRows: Int? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        SessionArchive.Writer(out).use { writer ->
            for ((name, csv) in sessions) {
                writer.begin(name)
                csv.lineSequence().drop(1).filter { it.isNotEmpty() }.forEach { line ->
                    writer.row(line.split(','))
                }
            }
        }
        val bytes = out.toByteArray()
        if (manifestRows == null) return bytes
        return rewriteManifest(bytes, manifestRows)
    }

    /** Re-zip with one manifest number changed, leaving the entries alone. */
    private fun rewriteManifest(zip: ByteArray, rows: Int): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { writer ->
            java.util.zip.ZipInputStream(ByteArrayInputStream(zip)).use { reader ->
                while (true) {
                    val entry = reader.nextEntry ?: break
                    val body = reader.readBytes()
                    writer.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    if (entry.name == SessionArchive.MANIFEST) {
                        writer.write(
                            String(body)
                                .replaceFirst(Regex(""""rows": \d+"""), """"rows": $rows""")
                                .toByteArray(),
                        )
                    } else {
                        writer.write(body)
                    }
                    writer.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test fun `the archive helper really does produce a readable archive`() {
        // A guard on the tests above rather than on the code: a helper that
        // quietly wrote something unreadable would make every archive assertion
        // pass for the wrong reason.
        val zip = archiveOf(gapSession to sessionCsv(count = 3))
        val survey = SessionArchive.survey({ ByteArrayInputStream(zip) }, "a.zip").getOrThrow()
        assertTrue(survey.hadManifest)
        assertEquals(3, survey.rows)
        assertFalse(survey.sessions.isEmpty())
    }
}

package com.sensirion.sps30.data

import com.sensirion.sps30.config.Exports
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the app does when the thing on the other end of the wire is not the
 * user's controller.
 *
 * That is not a hypothetical while `hello` is unauthenticated: discovery takes
 * the **first** host on the LAN that answers a parseable one, and the Bluetooth
 * scan takes the first advertiser of a public Nordic UUID that thousands of
 * unrelated products also use. Everything below therefore describes a peer the
 * app has already attached to and is already believing.
 *
 * The tests are about damage the app can stop on its own. Attaching to the
 * impostor in the first place is a different, larger change - a per-device key
 * and a handshake - and is not what these are for.
 */
class HostileControllerTest {

    // --- a file of the peer's choosing, in the user's Downloads folder --------
    //
    // The sharpest of them, because it reaches outside the app. `backup_meta`
    // used to be handled whether or not a backup had been asked for, and the
    // name it carried was handed straight to `Exports.writeArchive`. On API 29+
    // MediaStore sanitises DISPLAY_NAME; `minSdk` is 26, and the legacy path -
    // taken on Android 8, and whenever the MediaStore insert fails - is
    // `File(dir, name)` with nothing in between.

    @Test
    fun `a name off the wire never becomes a file name`() {
        val ours = Exports.archiveName("backup")
        val hostile = listOf(
            "../../../../data/data/com.sensirion.sps30.esp32/files/evil.zip",
            "/sdcard/Download/evil.zip",
            "..",
            ".",
            "",
            // A NUL: harmless in a JVM string, a terminator to anything native.
            "evil\u0000.zip",
            "evil space.zip",
            "sub/dir.zip",
            "back\\slash.zip",
            "a".repeat(400) + ".zip",
        )
        for (name in hostile) {
            assertEquals("\"$name\" was accepted as a file name", ours, Exports.safeName(name, ours))
        }
    }

    @Test
    fun `a name this app derived is left alone`() {
        val fallback = "unused.zip"
        for (name in listOf(
            Exports.archiveName("backup"),
            Exports.archiveName("export"),
            "sps30-20260803-101112.csv",
            "SPS30-20260803_101112.csv",
        )) {
            assertEquals(name, Exports.safeName(name, fallback))
        }
    }

    @Test
    fun `the names this app derives are distinguishable and safe`() {
        assertTrue(Exports.archiveName("backup").startsWith("sps30-backup-"))
        assertTrue(Exports.archiveName("export").startsWith("sps30-export-"))
        assertTrue(Exports.archiveName("export").endsWith(".zip"))
    }

    // --- a zip the user will open with something else -------------------------
    //
    // The app never extracts an archive by entry path, so it is not the victim
    // here. A file manager or a spreadsheet is, and the app is what would have
    // manufactured the file for them.

    @Test
    fun `a session name that could traverse never becomes a zip entry`() {
        val hostile = listOf(
            "../../../../etc/passwd",
            "../SPS30-20260802_142717.csv",
            "/absolute/SPS30-20260802_142717.csv",
            "dir/SPS30-20260802_142717.csv",
            "..\\..\\windows\\system32\\evil.csv",
            "SPS30-20260802_142717.csv\u0000.sh",
            "MANIFEST.json",
            "",
        )
        val out = ByteArrayOutputStream()
        val refused = ArrayList<String>()
        SessionArchive.Writer(out).use { writer ->
            for (name in hostile) {
                assertFalse("\"$name\" was written as an entry", writer.begin(name))
                refused.add(name)
            }
            // One good session, so the archive is not empty and the refusals
            // can be shown not to have broken the writer.
            assertTrue(writer.begin("SPS30-20260802_142717.csv"))
            writer.row(List(Wire.CSV_COLUMNS.size) { "" })
        }

        // The only entries are the one good session and the manifest the writer
        // itself adds. `MANIFEST.json` is in the hostile list above on purpose:
        // a session allowed to take that name would overwrite the manifest the
        // erase claim is checked against.
        val entries = entryNamesOf(out.toByteArray())
        assertEquals(setOf("SPS30-20260802_142717.csv", SessionArchive.MANIFEST), entries)
        assertEquals(hostile.toSet(), refused.toSet())
        assertFalse(entries.any { it.contains("..") || it.contains("/") || it.contains("\\") })
    }

    @Test
    fun `a refused session is reported rather than dropped silently`() {
        val out = ByteArrayOutputStream()
        val writer = SessionArchive.Writer(out)
        writer.begin("../evil.csv")
        writer.begin("SPS30-20260802_142717.csv")
        writer.close()

        assertEquals(listOf("../evil.csv"), writer.refusedNames)
        // And it is not in the manifest either, so the erase claim this archive
        // backs cannot name a session it does not contain.
        assertEquals(listOf("SPS30-20260802_142717.csv"), writer.sessions.map { it.name })
    }

    @Test
    fun `the strict name check is the one the writer uses`() {
        // `isSessionName` strips a path before matching, which is right for
        // reading an archive somebody else wrote and wrong for writing one.
        assertTrue(SessionArchive.isSessionName("../../SPS30-20260802_142717.csv"))
        assertFalse(SessionArchive.isSessionFileName("../../SPS30-20260802_142717.csv"))
        assertTrue(SessionArchive.isSessionFileName("SPS30-20260802_142717.csv"))
    }

    // --- a small zip that declares a very large manifest ----------------------

    @Test
    fun `a manifest past the cap is treated as absent rather than read`() {
        // A real session, so the archive is one the format would otherwise
        // accept and the manifest is the only thing under test.
        val session = "SPS30-20260802_142717.csv"
        val csv = sessionCsv(session, rows = 3)

        // Well past the cap, and compressible enough that the file carrying it
        // is small - which is the whole trick.
        val huge = buildString {
            append("[")
            repeat(SessionArchive.MANIFEST_MAX_BYTES / 8) { append("        ") }
            append("]")
        }
        assertTrue(huge.length > SessionArchive.MANIFEST_MAX_BYTES)

        val bytes = zipOf(
            mapOf(session to csv, SessionArchive.MANIFEST to huge.toByteArray()),
        )
        val survey = SessionArchive.survey({ ByteArrayInputStream(bytes) }, "hostile.zip")

        assertTrue(survey.exceptionOrNull()?.message ?: "", survey.isSuccess)
        // Read, it would have declared a manifest. Capped, the archive falls
        // back to being checked on structure alone, which the format already
        // does for a plain CSV somebody kept themselves.
        assertFalse(survey.getOrThrow().hadManifest)
        // The sessions themselves are read as normal: capping the manifest
        // costs the cross-check, not the data.
        assertEquals(listOf(session), survey.getOrThrow().sessions.map { it.name })
        assertTrue(survey.getOrThrow().rows > 0)
    }

    @Test
    fun `a manifest inside the cap is still honoured`() {
        val session = "SPS30-20260802_142717.csv"
        val csv = sessionCsv(session, rows = 3)

        // Written by the writer itself, so the digest in it is the real one.
        val real = ByteArrayOutputStream()
        val entries = SessionArchive.Writer(real).use { writer ->
            writer.begin(session)
            repeat(3) { i -> writer.row(sampleRow(i)) }
            writer.sessions
        }
        val manifest = "[" + entries.joinToString(",") {
            """{"name":"${it.name}","rows":${it.rows},"digest":"${SessionArchive.digestHex(it.digest)}"}"""
        } + "]"

        val bytes = zipOf(
            mapOf(session to csv, SessionArchive.MANIFEST to manifest.toByteArray()),
        )
        val survey = SessionArchive.survey({ ByteArrayInputStream(bytes) }, "ok.zip")
        assertTrue(survey.exceptionOrNull()?.message ?: "", survey.isSuccess)
        assertTrue(survey.getOrThrow().hadManifest)
    }

    @Test
    fun `a manifest under the cap is still read`() {
        val manifest = """[{"name":"SPS30-20260802_142717.csv","rows":3,"digest":"0000002a"}]"""
        assertEquals(1, SessionArchive.parseManifest(manifest)?.size)
        assertNull(SessionArchive.parseManifest(null))
    }

    // --- points the retention window will never take back ---------------------
    //
    // `file_data` is folded with `explicit = true` unconditionally, and explicit
    // rows drag the retention floor down to the oldest epoch received. A peer
    // streaming rows under distinct (csv, index) keys therefore accumulates
    // points that retention was never going to trim.

    @Test
    fun `retained points are capped however far the archive floor is dragged`() {
        val history = TimeSeriesHistory(retentionSeconds = 60.0)
        val columns = listOf("index", "timestamp") + Wire.PM_FIELDS

        // Deliberately more than the cap, arriving the way a hostile controller
        // would send them: named sessions, distinct indices, explicit.
        val batch = 25_000
        var index = 0
        var epoch = 1_600_000_000L
        while (index < TimeSeriesHistory.MAX_POINTS + 2 * batch) {
            val rows = (0 until batch).map {
                val cells = ArrayList<String>()
                cells.add(Wire.formatIndex(index))
                cells.add(isoAt(epoch + index))
                repeat(Wire.PM_FIELDS.size) { cells.add("1.00") }
                index++
                cells
            }
            history.addRows("stored:flood-${index / batch}", columns, rows, explicit = true)
            assertTrue(
                "history reached ${history.size}, past the cap",
                history.size <= TimeSeriesHistory.MAX_POINTS,
            )
        }
        assertEquals(TimeSeriesHistory.MAX_POINTS, history.size)
    }

    @Test
    fun `an ordinary import is not touched by the cap`() {
        val history = TimeSeriesHistory(retentionSeconds = 60.0)
        val columns = listOf("index", "timestamp") + Wire.PM_FIELDS
        // An hour of 1 Hz, timestamped well outside the retention window: the
        // case the archive floor exists for, and the one the cap must not break.
        val rows = (0 until 3600).map { i ->
            val cells = ArrayList<String>()
            cells.add(Wire.formatIndex(i))
            cells.add(isoAt(1_600_000_000L + i))
            repeat(Wire.PM_FIELDS.size) { cells.add("1.00") }
            cells
        }
        assertEquals(3600, history.addRows("stored:real", columns, rows, explicit = true))
        assertEquals(3600, history.size)
        assertNotEquals(0, history.size)
    }

    // --- helpers --------------------------------------------------------------

    /** One row in the archive's own column order. */
    private fun sampleRow(index: Int): List<String> {
        val cells = ArrayList<String>()
        cells.add(Wire.formatIndex(index))
        cells.add(isoAt(1_785_000_000L + index))
        Wire.FIELDS.forEachIndexed { f, _ ->
            val v = (index + f) * 0.37
            cells.add(if (f == Wire.FIELDS.size - 1) "%.3f".format(v) else "%.2f".format(v))
        }
        cells.add("")
        return cells
    }

    /** The bytes of one session CSV, header and all. */
    private fun sessionCsv(@Suppress("UNUSED_PARAMETER") name: String, rows: Int): ByteArray {
        val text = buildString {
            append(SessionArchive.HEADER).append('\n')
            repeat(rows) { i -> append(sampleRow(i).joinToString(",")).append('\n') }
        }
        return text.toByteArray()
    }

    private fun isoAt(epochSeconds: Long): String =
        java.time.OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(epochSeconds),
            java.time.ZoneOffset.ofHours(-4),
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entryNamesOf(bytes: ByteArray): Set<String> {
        val names = HashSet<String>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
            }
        }
        return names
    }
}

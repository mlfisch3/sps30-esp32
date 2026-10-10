package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Planning a run over a real zip, built to match the recorded archive.
 *
 * The corpus here is synthetic but its shape is not: the proportions, the file
 * names and the byte sizes are taken from `sps30-all-sessions-20260731.zip`,
 * every session the Pi ever recorded - 245 files, 190.5 MB of CSV, of which 163
 * are an 88-byte header and nothing else, written one per second across the 160
 * seconds of 2026-07-27 02:13:39 to 02:16:19.
 *
 * This goes through `java.util.zip` rather than a string, because the claim
 * being tested is that a header-only entry can be told apart from a real one for
 * the price of its own 88 bytes, and that is a property of reading the archive.
 */
class StoredArchivePlanTest {

    @get:Rule val temp = TemporaryFolder()

    private val header =
        "index,timestamp,pm1_0,pm2_5,pm4_0,pm10,nc0_5,nc1_0,nc2_5,nc4_0,nc10,typical_size,event\r\n"

    private fun rows(n: Int, startSecond: Long): String = buildString {
        append(header)
        for (i in 0 until n) {
            val s = startSecond + i
            append(
                "%07d,2026-07-28T%02d:%02d:%02d-04:00,7.2,9.1,10.3,10.5,45.6,56.2,58.0,58.3,58.4,0.62,\r\n"
                    .format(i + 1, (s / 3600) % 24, (s / 60) % 60, s % 60),
            )
        }
    }

    /** 163 header-only sessions and 82 with readings, as the real archive has. */
    private fun buildArchive(): File {
        val file = temp.newFile("sps30-sessions-20260731.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            var second = 2L * 3600 + 13 * 60 + 39
            for (i in 0 until 163) {
                zip.putNextEntry(ZipEntry("SPS30-20260727_%06d.csv".format(21339 + i)))
                zip.write(header.toByteArray())
                zip.closeEntry()
                second++
            }
            for (i in 0 until 82) {
                zip.putNextEntry(ZipEntry("SPS30-20260728_%06d.csv".format(10000 + i * 37)))
                zip.write(rows(600, i * 600L).toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    /** What `StoredSessions.plan` does to one archive, without a Context. */
    private fun planOf(file: File, alreadyImported: (String) -> Boolean = { false }) =
        ZipFile(file).use { zip ->
            val candidates = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".csv", ignoreCase = true) }
                .sortedByDescending { it.name }
                .map { entry ->
                    SessionPlan.Candidate(entry.name, entry.size, file) {
                        zip.getInputStream(entry)
                    }
                }
                .toList()
            SessionPlan.build(candidates, alreadyImported)
        }

    @Test fun aHeaderOnlySessionIsEightyEightBytesInTheArchiveToo() {
        ZipFile(buildArchive()).use { zip ->
            val empties = zip.entries().asSequence().filter { it.size == 88L }.toList()
            assertEquals(163, empties.size)
            assertEquals(
                header,
                zip.getInputStream(empties.first()).readBytes().decodeToString(),
            )
        }
    }

    @Test fun theProgressTotalCountsOnlyTheSessionsThatCarryReadings() {
        val plan = planOf(buildArchive())
        // Before: 245, of which 163 finished instantly. After: 82.
        assertEquals(82, plan.total)
        assertEquals(163, plan.empty)
        assertEquals(0, plan.unrecognised)
        assertEquals(0, plan.duplicate)
    }

    @Test fun theEmptiesAreAFractionOfTheFilesAndNothingOfTheWork() {
        val file = buildArchive()
        val plan = planOf(file)
        val allBytes = ZipFile(file).use { z -> z.entries().asSequence().sumOf { it.size } }
        val emptyBytes = allBytes - plan.bytes
        assertEquals(163 * 88L, emptyBytes)
        // Two thirds of the files and a fraction of a percent of the bytes: the
        // mismatch that made a file-counting bar meaningless. The sessions here
        // are 600 rows to keep the test quick; the recorded ones average 2.3 MB,
        // where the same 14,344 bytes of headers are 0.008% of 190.5 MB.
        assertTrue("$emptyBytes of $allBytes", emptyBytes.toDouble() / allBytes < 0.01)
        assertEquals(0.665, 163.0 / 245.0, 0.01)
    }

    @Test fun theOlderOfTwoOverlappingArchivesAddsOnlyItsNewSessions() {
        // Every archive holds every session recorded before it.
        val first = buildArchive()
        val already = HashSet<String>()
        ZipFile(first).use { z -> z.entries().asSequence().forEach { already.add(it.name) } }
        val plan = planOf(first) { it in already }
        assertEquals(0, plan.total)
        assertEquals(245, plan.duplicate)
    }

    @Test fun planningTheArchiveLeavesEveryByteOfItAlone() {
        val file = buildArchive()
        val before = file.readBytes()
        val stamp = file.lastModified()
        val plan = planOf(file)
        assertEquals(82, plan.total)
        assertTrue("the archive was modified", before.contentEquals(file.readBytes()))
        assertEquals(stamp, file.lastModified())
        assertTrue("the archive was removed", file.isFile)
    }
}

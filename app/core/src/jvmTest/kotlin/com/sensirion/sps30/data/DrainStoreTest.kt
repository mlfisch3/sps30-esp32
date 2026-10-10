package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Where a drained block is written down, and the proof that it landed.
 *
 * The sensor is told it may free a block the moment [DrainStore.seal] returns, so
 * what that call has done has to have survived the process being killed on the
 * next line. These tests are about that: what is on disk afterwards, whether the
 * digest is taken over what is on disk or over what was intended, and what a
 * block that was never confirmed leaves behind.
 *
 * The one thing they cannot check is `fsync` itself - no unit test can pull the
 * power - so what is checked instead is that the file is complete and reads back
 * as the same rows the moment the call returns, which is the observable half of
 * the same promise.
 */
class DrainStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val session = "SPS30-20260802_184742.csv"

    private fun readingRow(index: Int): List<String> = listOf(
        session,
        "%07d".format(index),
        "2026-08-02T18:47:%02d-04:00".format(42 + (index % 18)),
        "5.39", "18.01", "27.97", "29.96", "10.29", "29.78", "42.23", "44.63", "45.00", "1.630",
        "settling", "", "",
    )

    private fun store(root: File = temp.root) = DrainStore(root, "test")

    private fun folderOf(root: File = temp.root) =
        File(root, "${DrainStore.FOLDER_PREFIX}test")

    @Test
    fun `a sealed block is a whole file that reads back as the rows it was given`() {
        val store = store()
        store.open(1, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        val rows = (1..40).map(::readingRow)
        rows.forEach(store::row)
        val sealed = store.seal()

        assertEquals(40, sealed.rows)
        val file = File(folderOf(), DrainStore.blockFileName(1, Wire.DRAIN_KIND_RING))
        assertTrue("the block file should exist the moment seal returns", file.exists())

        val lines = file.readLines().filter { it.isNotEmpty() }
        assertEquals(Wire.DRAIN_READING_COLUMNS.joinToString(","), lines.first())
        assertEquals(41, lines.size)
        assertEquals(rows.map { it.joinToString(",") }, lines.drop(1))
    }

    /**
     * The digest is over what is on disk, not over what arrived.
     *
     * This is the difference between a proof and an intention. The sensor frees a
     * block on the strength of this number, so a short write has to be caught
     * here - where it can be reported as a device that could not write - rather
     * than by the sensor refusing a confirmation nobody can explain.
     */
    @Test
    fun `the digest is computed from the file, not from the rows that arrived`() {
        val store = store()
        store.open(7, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        val rows = (1..8).map(::readingRow)
        rows.forEach(store::row)
        val sealed = store.seal()

        val expected = rows.fold(0) { acc, r ->
            DeviceDrain.fold(acc, DeviceDrain.digestReadingRow(r)!!)
        }
        assertEquals(expected, sealed.digest)

        // And it really did come off the disk: re-digesting the file by hand
        // gives the same answer.
        val file = File(folderOf(), DrainStore.blockFileName(7, Wire.DRAIN_KIND_RING))
        val fromDisk = file.readLines().drop(1).filter { it.isNotEmpty() }
            .fold(0) { acc, line ->
                DeviceDrain.fold(
                    acc,
                    DeviceDrain.digestReadingRow(DrainStore.split(line))!!,
                )
            }
        assertEquals(sealed.digest, fromDisk)
    }

    @Test
    fun `a block of preserved segment summaries seals under its own column list`() {
        val store = store()
        store.open(3, Wire.DRAIN_KIND_SEGMENTS, Wire.DRAIN_SEGMENT_COLUMNS)
        val rows = (42..44).map { seq ->
            listOf(
                "$seq", session, "pm2_5", "20.0", "1785710862", "1785710961",
                "21.5", "19.25", "44.125", "33.75", "12.5", "18.0", "91.5",
                "100", "3745", "3844", "11", "1",
            )
        }
        rows.forEach(store::row)
        val sealed = store.seal()

        assertEquals(3, sealed.rows)
        val expected = rows.fold(0) { acc, r ->
            DeviceDrain.fold(acc, DeviceDrain.digestSegmentRow(r)!!)
        }
        assertEquals(expected, sealed.digest)

        val file = File(folderOf(), DrainStore.blockFileName(3, Wire.DRAIN_KIND_SEGMENTS))
        assertEquals(
            Wire.DRAIN_SEGMENT_COLUMNS.joinToString(","),
            file.readLines().first(),
        )
    }

    /**
     * A block that was never confirmed leaves nothing that looks like a copy.
     *
     * It is still on the sensor, which is what makes throwing it away right: a
     * half-written file in the folder would be indistinguishable from a block
     * that had been proved, and the next run would be copying it again anyway.
     */
    @Test
    fun `a discarded block leaves no file behind`() {
        val store = store()
        store.open(4, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        (1..5).map(::readingRow).forEach(store::row)
        store.discard()

        assertFalse(
            File(folderOf(), DrainStore.blockFileName(4, Wire.DRAIN_KIND_RING)).exists(),
        )
        assertTrue(store.blocks().isEmpty())
    }

    @Test
    fun `opening a second block discards the first if it was never sealed`() {
        val store = store()
        store.open(1, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        (1..3).map(::readingRow).forEach(store::row)
        store.open(2, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        (4..6).map(::readingRow).forEach(store::row)
        store.seal()

        assertEquals(
            listOf(DrainStore.blockFileName(2, Wire.DRAIN_KIND_RING)),
            store.blocks().map { it.name },
        )
    }

    /**
     * The manifest is the account of the run, and it survives being interrupted.
     *
     * A run that stops leaves a manifest that stops, which is the honest record
     * of what happened. It is appended to rather than rewritten, so a torn write
     * costs the last line rather than the index.
     */
    @Test
    fun `the manifest records every block sealed and every block freed`() {
        val store = store()
        for (block in 1L..3L) {
            store.open(block, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
            (1..2).map(::readingRow).forEach(store::row)
            val sealed = store.seal()
            assertEquals(2, sealed.rows)
            if (block < 3) store.freed(block)
        }

        val lines = store.manifestLines()
        assertEquals(DrainStore.MANIFEST_HEADER, lines.first())
        val sealedLines = lines.drop(1).filter { it.endsWith(",no") }
        val freedLines = lines.drop(1).filter { it.endsWith(",yes") }
        assertEquals(3, sealedLines.size)
        assertEquals(2, freedLines.size)
        // Block 3 was written down and the sensor never said it had freed it,
        // which is exactly the interrupted case - and the file is still here.
        assertTrue(sealedLines.last().startsWith("3,"))
        assertTrue(
            File(folderOf(), DrainStore.blockFileName(3, Wire.DRAIN_KIND_RING)).exists(),
        )
    }

    @Test
    fun `each run gets its own folder, so a resumed run cannot overwrite an earlier one`() {
        val first = DrainStore(temp.root, "20260806-101500")
        first.open(1, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        listOf(readingRow(1)).forEach(first::row)
        first.seal()

        val second = DrainStore(temp.root, "20260806-104200")
        second.open(1, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        listOf(readingRow(2)).forEach(second::row)
        second.seal()

        assertNotEquals(first.location, second.location)
        assertEquals(1, first.blocks().size)
        assertEquals(1, second.blocks().size)
        assertNotEquals(
            first.blocks().first().readText(),
            second.blocks().first().readText(),
        )
    }

    @Test
    fun `finish hands back where the copy is, and the folder is still there`() {
        val store = store()
        store.open(1, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        listOf(readingRow(1)).forEach(store::row)
        store.seal()

        assertEquals(folderOf().absolutePath, store.finish())
        assertTrue(folderOf().isDirectory)
    }

    /**
     * The folder is the copy, and tidying it up afterwards is allowed to fail.
     *
     * Every block file was forced to storage before the sensor was told it could
     * free that block, so a run whose final zip went wrong has still saved every
     * reading - and must say where they are rather than reporting a failure.
     */
    @Test
    fun `a publish step that throws does not lose the copy`() {
        val store = DrainStore(temp.root, "test") { error("no room in Downloads") }
        store.open(1, Wire.DRAIN_KIND_RING, Wire.DRAIN_READING_COLUMNS)
        listOf(readingRow(1)).forEach(store::row)
        store.seal()

        var threw = false
        try {
            store.finish()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("finish should not swallow it - the caller reports it", threw)
        assertTrue("and the copy is still on disk", store.blocks().isNotEmpty())
    }

    @Test
    fun `cells that would break a line survive the round trip`() {
        // Nothing the sensor sends contains one of these. This is the last point
        // at which a string becomes a line of a file that has to read back as the
        // same cells, so it is checked here rather than assumed upstream.
        val awkward = listOf("a,b", "he said \"no\"", "one\ntwo", "plain", "")
        val line = awkward.joinToString(",") { DrainStore.escape(it) }
        assertEquals(awkward, DrainStore.split(line))
    }
}

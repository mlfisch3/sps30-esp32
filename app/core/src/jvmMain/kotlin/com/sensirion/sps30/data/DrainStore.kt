package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import java.io.File
import java.io.FileOutputStream

/**
 * A folder of CSV files, one per block, that a drain writes into.
 *
 * The plain-file half of [DrainSink], and the reason it is plain files rather
 * than one growing zip: **the copy has to be durable a block at a time.** The
 * sensor is told it may free a block the moment [seal] returns, so what [seal]
 * has done has to have survived the process being killed on the next line. A zip
 * being appended to is not that - its directory is written at the end - and a
 * buffered stream is not that either. One file per block, flushed to the platter
 * and read back before a single word goes to the sensor, is.
 *
 * ## What the folder ends up holding
 *
 * ```
 * <root>/sps30-clear-<run>/
 *     MANIFEST.csv          block,kind,rows,digest,file,freed
 *     block-000001-body.csv
 *     block-000002-segments.csv
 *     block-000003-ring.csv
 *     ...
 * ```
 *
 * A block file is an ordinary CSV with the sensor's own column list at the top,
 * so every one of them opens in a spreadsheet with no tooling at all. The
 * manifest is the index: what was copied, what it digested to, and whether the
 * sensor went on to free it. A run that was interrupted leaves a manifest that
 * stops - which is the honest record of what happened, not a corrupt file.
 *
 * ## Reading it back
 *
 * The digest is computed from the file **as read back off disk**, not from the
 * rows as they arrived. A short write, a full disk or a truncated line is then
 * caught here, where it can be reported as what it is, rather than by the sensor
 * refusing a confirmation nobody can explain.
 */
class DrainStore(
    root: File,
    /** Names the run's folder. Passed in so tests are not at the mercy of a clock. */
    runId: String,
    /**
     * What to do with the folder once everything is in it, and what to call the
     * result.
     *
     * The Android build zips it into Downloads so it can be opened and shared;
     * the host tests hand back the folder. Deliberately **after** the last block
     * is confirmed and deliberately allowed to fail: the folder is already the
     * durable copy, and a run whose final tidying went wrong has still saved
     * every reading.
     */
    private val publish: ((File) -> String)? = null,
) : DrainSink {

    private val folder = File(root, "$FOLDER_PREFIX$runId")
    private val manifest = File(folder, MANIFEST)

    /** The block being written, before it is sealed. */
    private var openFile: File? = null
    private var openBlock: Long = 0
    private var openKind: String = ""
    private var openColumns: List<String> = emptyList()
    private var openRows: ArrayList<List<String>> = ArrayList()

    override val location: String get() = folder.absolutePath

    override fun open(block: Long, kind: String, columns: List<String>) {
        discard()
        if (!folder.exists() && !folder.mkdirs()) {
            throw java.io.IOException("could not make ${folder.absolutePath}")
        }
        openBlock = block
        openKind = kind
        openColumns = columns
        openRows = ArrayList()
        openFile = File(folder, blockFileName(block, kind))
    }

    override fun row(cells: List<String>) {
        if (openFile == null) return
        openRows.add(cells)
    }

    /**
     * Write the block, force it to storage, read it back, and digest what is
     * there.
     *
     * The whole block is held in memory until this point, and that is a bounded
     * amount rather than an unbounded one: a block is one 4 KiB flash sector, so
     * a few hundred readings, tens of kilobytes of text. It is what lets the
     * write be a single `write` of a whole file - a file that either exists whole
     * or does not exist - instead of an append that can be interrupted in the
     * middle of a line.
     */
    override fun seal(): DrainSink.Sealed {
        val file = openFile ?: throw IllegalStateException("no block is open")
        val text = buildString {
            append(openColumns.joinToString(","))
            append('\n')
            for (row in openRows) {
                append(row.joinToString(",") { escape(it) })
                append('\n')
            }
        }
        writeDurably(file, text)

        // Read back what is actually on disk, and digest that. Anything else
        // would be digesting an intention.
        val (rows, digest) = digestOf(file, openKind)
        if (rows != openRows.count { DeviceDrain.digestRow(openKind, it) != null }) {
            throw java.io.IOException(
                "the copy of block $openBlock read back as $rows rows, which is not what was " +
                    "written",
            )
        }
        appendManifest("$openBlock,$openKind,$rows,${Wire.digestHex(digest)},${file.name},no")
        openFile = null
        openRows = ArrayList()
        return DrainSink.Sealed(rows, digest)
    }

    override fun freed(block: Long) {
        // Recorded rather than acted on: the file is already here and already
        // whole. What this adds is the account of which blocks the sensor no
        // longer has, which is the difference between a copy and the record.
        appendManifest("$block,,,,,yes")
    }

    override fun discard() {
        val file = openFile ?: return
        // It was never confirmed, so the sensor still holds it. Leaving a
        // half-written file behind would leave something that looks like a copy.
        runCatching { if (file.exists()) file.delete() }
        openFile = null
        openBlock = 0
        openRows = ArrayList()
    }

    override fun finish(): String = publish?.invoke(folder) ?: folder.absolutePath

    override fun close() {
        discard()
    }

    /** Every block file this run wrote, oldest first. */
    fun blocks(): List<File> =
        folder.listFiles()?.filter { it.name != MANIFEST }?.sortedBy { it.name } ?: emptyList()

    fun manifestLines(): List<String> =
        if (manifest.exists()) manifest.readLines() else emptyList()

    /**
     * A single whole-file write that has reached storage before it returns.
     *
     * `FileOutputStream.fd.sync()` is the part that matters and the part that is
     * easy to leave out: without it the bytes are in the operating system's cache
     * and a power cut loses them, while this code has already told the sensor it
     * may erase the only other copy.
     */
    private fun writeDurably(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    /**
     * One line onto the end of the manifest, forced to storage.
     *
     * Appended rather than rewritten whole: a full ring is five hundred and sixty
     * blocks, and rewriting the index after each of them would be a megabyte of
     * writes to record a kilobyte of facts. A torn append leaves a manifest whose
     * last line is short, which is a record that stops - the block files are the
     * copy, and the manifest is the account of it.
     */
    private fun appendManifest(line: String) {
        val fresh = !manifest.exists()
        FileOutputStream(manifest, true).use { out ->
            if (fresh) out.write("$MANIFEST_HEADER\n".toByteArray(Charsets.UTF_8))
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    private fun digestOf(file: File, kind: String): Pair<Int, Int> {
        var rows = 0
        var digest = 0
        file.bufferedReader().useLines { lines ->
            for ((n, line) in lines.withIndex()) {
                if (n == 0) continue // the header
                if (line.isEmpty()) continue
                val one = DeviceDrain.digestRow(kind, split(line)) ?: continue
                digest = DeviceDrain.fold(digest, one)
                rows++
            }
        }
        return rows to digest
    }

    companion object {
        const val MANIFEST = "MANIFEST.csv"
        const val MANIFEST_HEADER = "block,kind,rows,digest,file,freed"
        const val FOLDER_PREFIX = "sps30-clear-"

        fun blockFileName(block: Long, kind: String): String =
            "block-%06d-%s.csv".format(block, if (kind.isEmpty()) "block" else kind)

        /**
         * A cell as CSV.
         *
         * Nothing the sensor sends contains a comma, a quote or a newline - the
         * cells are session names, indices, fixed-point numbers and ISO
         * timestamps - but this is where a string becomes a line of a file that
         * has to read back as the same cells, so it is quoted anyway rather than
         * on the strength of that.
         */
        fun escape(cell: String): String =
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + cell.replace("\"", "\"\"") + "\""
            } else {
                cell
            }

        /** [escape] read back. */
        fun split(line: String): List<String> {
            val out = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        cell.append('"'); i++
                    }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out.add(cell.toString()); cell.setLength(0) }
                    else -> cell.append(c)
                }
                i++
            }
            out.add(cell.toString())
            return out
        }
    }
}

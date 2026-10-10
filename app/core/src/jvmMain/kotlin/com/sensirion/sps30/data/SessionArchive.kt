package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The file a session becomes on the way off the controller, and back on.
 *
 * A **zip of ordinary session CSVs**, one per session, named the way the daemon
 * names its files, with the daemon's own thirteen columns - `index`,
 * `timestamp`, all ten values including PM4.0 and PM10, and `event`. That is a
 * deliberate choice over anything cleverer: it opens in a spreadsheet, it is the
 * same shape the Raspberry Pi has always written, and it can be read by anything
 * that ever read this project's data. The only addition is [MANIFEST], which
 * carries what is needed to tell an intact file from a truncated one.
 *
 * ## The manifest, and why a digest
 *
 * Two questions cannot be answered from CSV alone. *Is this file complete?* - a
 * transfer cut short leaves a file that parses perfectly and is missing its
 * tail. And *does this device really hold what the controller holds?*, which is
 * the question the controller's erase refuses to act without an answer to.
 *
 * Both are answered by a per-session `(rows, digest)`, computed identically
 * here and in `store_merge.c`. The digest is a sum of per-reading CRC-32s, so it
 * does not depend on the order rows are seen in - the controller reads its pages
 * in the order they were written, which after a merge is not index order, and a
 * digest that cared would call a perfectly good backup a mismatch.
 *
 * It is an integrity check, not a signature. It says the data is the same data;
 * it says nothing about who is asking, and it is not meant to.
 *
 * ## Precision
 *
 * The values are written with two decimals (three for particle size), which is
 * **exactly** the controller's storage quantum. A value that goes out and comes
 * back is therefore the same number rather than nearly it, and the digests on
 * the two sides cannot disagree over rounding.
 *
 * Timestamps are ISO 8601 to the second, as the protocol has always carried
 * them. A session the controller already holds keeps its own millisecond stamps
 * on re-import, because those readings are duplicates and are never rewritten.
 * A session arriving somewhere it has never been is stored to the second, which
 * is the one thing a round trip through this format does not preserve, and it is
 * a property of the wire timestamp rather than of the file.
 */
object SessionArchive {

    /** Where the per-session `(rows, digest)` lives inside the zip. */
    const val MANIFEST = "MANIFEST.json"

    /**
     * The most manifest this will read before giving up on it.
     *
     * One entry is about 70 bytes, so 4 MB is over fifty thousand sessions -
     * more than a controller could name in its lifetime, and small enough that
     * a zip declaring a huge one cannot exhaust the heap of the phone reading
     * it. Over the cap the manifest is treated as absent, which the format
     * already has an answer for: the archive is checked on structure alone.
     */
    const val MANIFEST_MAX_BYTES = 4 * 1024 * 1024

    /**
     * At most [limit] bytes of [input], decoded as text; null past the cap.
     *
     * `readBytes()` sizes its buffer from what the stream says it has, which
     * inside a zip is whatever the archive declared - so it is the attacker's
     * number, not the file's.
     */
    private fun readCapped(input: InputStream, limit: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** The header line an ordinary, clock-stamped session CSV starts with. */
    val HEADER: String = Wire.CSV_COLUMNS.joinToString(",")

    /**
     * The header line a session recorded without a clock starts with.
     *
     * [HEADER] plus `rel_boot,rel_ms`. A clockless reading's `timestamp` is
     * empty - the board refused to invent one - so those two cells are the only
     * thing that says where the reading sits, and a file written without them
     * holds readings nothing can ever place again.
     */
    val HEADER_RELATIVE: String = Wire.CSV_COLUMNS_RELATIVE.joinToString(",")

    /** The header [name]'s file needs. See `Wire.csvColumnsFor`. */
    fun headerFor(name: String): String =
        if (Wire.isRelativeSessionName(name)) HEADER_RELATIVE else HEADER

    /** Where the board's own clock sits in [Wire.CSV_COLUMNS_RELATIVE]. */
    private val REL_BOOT_AT = Wire.CSV_COLUMNS_RELATIVE.indexOf(Wire.COL_REL_BOOT)
    private val REL_MS_AT = Wire.CSV_COLUMNS_RELATIVE.indexOf(Wire.COL_REL_MS)

    /** What the manifest says about one session. */
    data class Entry(val name: String, val rows: Int, val digest: Int)

    /**
     * A bare file name this format will accept as a session, with no path.
     *
     * The strict form, and the one to use before a name becomes a `ZipEntry`.
     * [isSessionName] strips a leading path before matching, which is right for
     * reading an archive somebody else wrote and quite wrong for writing one:
     * `../../SPS30-20260101_000000.csv` passes that and is a traversal.
     *
     * Both of the protocol's two session-key shapes are accepted: the dated one
     * `wire.csv_filename` writes, and the `SPS30-Rxxxxxxxx-xxxxxxxx.csv` one
     * `wire.rel_session_name` writes for a session recorded without a clock.
     * The second was missing, and its absence meant the archive writer refused
     * every clockless session by name before the column gap could even matter.
     * Neither shape can hold a `/`, so neither is a traversal.
     */
    fun isSessionFileName(name: String): Boolean =
        Regex("""^SPS30-\d{8}_\d{6}\.csv$""").matches(name) || Wire.isRelativeSessionName(name)

    /** A file name this format will accept as a session, path and all. */
    fun isSessionName(name: String): Boolean =
        isSessionFileName(name.substringAfterLast('/'))

    // --- the digest, mirroring store_merge.c -------------------------------

    /**
     * One value as its whole number of storage quanta.
     *
     * The arithmetic is `store_quantise`'s, step for step, including the detour
     * through `Float`: the controller digests values it has read back out of
     * flash as 32-bit floats, and doing the sum in `Double` here would disagree
     * with it on the values worth disagreeing about. Half away from zero, which
     * is what both `store.py` and the C do and what Kotlin's `round` does not.
     */
    fun quantise(value: Double, field: Int): Int {
        val quantum = if (field == Wire.FIELDS.size - 1) 0.001 else 0.01
        val scaled = value.toFloat().toDouble() / quantum
        val rounded = if (scaled >= 0) Math.floor(scaled + 0.5) else Math.ceil(scaled - 0.5)
        return rounded.toInt()
    }

    /**
     * One reading's digest: index, second, settling flag and ten quanta.
     *
     * Fixed-width little-endian throughout, so the bytes built here and the
     * bytes `store_digest_reading` builds are the same bytes.
     */
    fun digestReading(index: Int, epochSeconds: Long, settling: Boolean, quanta: IntArray): Int {
        val canonical = ByteArray(4 + 8 + 1 + Wire.FIELDS.size * 4)
        putLe32(canonical, 0, index)
        putLe32(canonical, 4, (epochSeconds and 0xFFFFFFFFL).toInt())
        putLe32(canonical, 8, (epochSeconds ushr 32).toInt())
        canonical[12] = if (settling) 1 else 0
        for (f in quanta.indices) putLe32(canonical, 13 + f * 4, quanta[f])
        val crc = CRC32()
        crc.update(canonical)
        return crc.value.toInt()
    }

    private fun putLe32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        out[at + 2] = ((value ushr 16) and 0xFF).toByte()
        out[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /** Order-independent, so page order and index order give the same answer. */
    fun fold(accumulator: Int, one: Int): Int = accumulator + one

    /**
     * One CSV row's digest, or null when the row is not a reading.
     *
     * A null is a row that could not be placed - no index, and no time of either
     * kind. Counting it towards a digest would let a corrupt row look like a
     * complete backup.
     *
     * A clockless row has an empty `timestamp` and carries `(rel_boot, rel_ms)`
     * instead, and what the controller holds in the time field of such a reading
     * is the **packed** clock, not an instant - see `wire.rel_pack` and
     * `store_reading_t.epoch_ms`. Digesting it any other way would make a
     * perfectly good backup of a clockless session look like a mismatch to the
     * controller's erase, which is the one check standing between the user and
     * losing the data this whole path exists to save.
     */
    fun digestRow(cells: List<String>): Int? {
        val index = cells.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        if (index <= 0) return null
        val seconds = timeFieldSeconds(cells) ?: return null
        val quanta = IntArray(Wire.FIELDS.size) { f ->
            quantise(cells.getOrNull(2 + f)?.trim()?.toDoubleOrNull() ?: 0.0, f)
        }
        val settling = (cells.getOrNull(2 + Wire.FIELDS.size) ?: "").isNotEmpty()
        return digestReading(index, seconds, settling, quanta)
    }

    /**
     * Whole seconds of whichever clock the row carries, or null if it carries
     * neither. `floor_seconds` in `store_merge.c`, and a packed relative reading
     * is positive by construction so truncation and floor agree on it.
     */
    private fun timeFieldSeconds(cells: List<String>): Long? {
        val boot = cells.getOrNull(REL_BOOT_AT)?.trim()?.toIntOrNull()
        val ms = cells.getOrNull(REL_MS_AT)?.trim()?.toLongOrNull()
        if (boot != null && ms != null) return Wire.relPack(boot, ms) / 1000L
        val epoch = SessionCsv.parseEpoch(cells.getOrNull(1) ?: return null) ?: return null
        return Math.floor(epoch).toLong()
    }

    /** A running digest and count over the rows of one session. */
    class Tally {
        var digest: Int = 0
            private set
        var rows: Int = 0
            private set
        var unreadable: Int = 0
            private set

        fun add(cells: List<String>) {
            val one = digestRow(cells)
            if (one == null) {
                unreadable++
            } else {
                digest = fold(digest, one)
                rows++
            }
        }
    }

    // --- writing -----------------------------------------------------------

    /**
     * A zip being built one session at a time.
     *
     * Streaming, not assembled: a controller left running for weeks holds
     * hundreds of megabytes of sessions, and holding one on the heap to sort or
     * to count it would be a needless way to run a tablet out of memory. Rows go
     * into the entry as the frames carrying them arrive.
     *
     * The consequence is that rows are written in the order the controller
     * serves them, which is the order its pages were written. For a session that
     * has never been merged into, that is index order. For one that has, the
     * readings that filled a hole sit after the ones around them - correct,
     * complete, and not sorted. Nothing downstream minds: the merge accepts any
     * order, the digest does not depend on it, and a spreadsheet can sort.
     */
    class Writer(output: OutputStream) : AutoCloseable {
        private val zip = ZipOutputStream(java.io.BufferedOutputStream(output, 64 * 1024))
        private val entries = ArrayList<Entry>()
        private var open: String? = null
        private var tally = Tally()
        private val refused = LinkedHashSet<String>()

        val sessions: List<Entry> get() = entries
        val totalRows: Int get() = entries.sumOf { it.rows }

        /**
         * Session names this refused to write, if any.
         *
         * Empty against any controller behaving itself: `wire.csv_filename` is
         * the only thing that names a session and it produces exactly one shape.
         */
        val refusedNames: List<String> get() = refused.toList()

        /**
         * Start a session. Closes the one before it, if any.
         *
         * @return false if [name] is not one this format will write, in which
         *   case the session's rows are dropped rather than filed under it.
         *
         * The name comes off the wire and lands in `ZipEntry`, so it is a path
         * inside a file the user will hand to something else. This app never
         * extracts an archive by entry path, but a file manager or a spreadsheet
         * will, and `../` in an entry name is how a zip writes outside the
         * directory it was extracted into. Checked with [isSessionFileName],
         * which the format already owns, so what goes in is exactly what comes
         * back out on import.
         *
         * Refusing costs a session that a hostile or broken controller sent
         * under a name the format does not use. That loss is visible - the copy
         * reports how many of the controller's sessions it actually captured -
         * where writing the entry would not be.
         */
        fun begin(name: String): Boolean {
            if (open == name) return true
            if (!isSessionFileName(name)) {
                finishOpen()
                refused.add(name)
                return false
            }
            finishOpen()
            open = name
            tally = Tally()
            zip.putNextEntry(ZipEntry(name))
            // Widened for a clockless session, because its rows carry two more
            // cells and a header that did not name them would strand them.
            zip.write((headerFor(name) + "\n").toByteArray())
            return true
        }

        /** Append one row, as the cells the protocol names them in. */
        fun row(cells: List<String>) {
            if (open == null) return
            tally.add(cells)
            // Written whether or not it digested: a row this build cannot read
            // is still the user's data and still goes in the file. It is left
            // out of the digest, which is the honest place to record the doubt.
            zip.write(cells.joinToString(",").toByteArray())
            zip.write('\n'.code)
        }

        private fun finishOpen() {
            val name = open ?: return
            zip.closeEntry()
            entries.add(Entry(name, tally.rows, tally.digest))
            open = null
        }

        /** Close the last session, write the manifest and finish the file. */
        override fun close() {
            finishOpen()
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifestJson(entries).toByteArray())
            zip.closeEntry()
            zip.finish()
            zip.close()
        }
    }

    /**
     * The manifest, written by hand so the exact shape is visible here.
     *
     * `columns` names the classic thirteen and goes on doing so, because it
     * describes the shape every session in the archive shares. A clockless
     * session's file appends two more and says so in its own header line, which
     * is where a reader looks anyway - `readSession` maps by name, never by this.
     */
    fun manifestJson(entries: List<Entry>): String = buildString {
        append("{\n")
        append("  \"format\": \"sps30-session-archive\",\n")
        append("  \"version\": 1,\n")
        append("  \"columns\": [")
        append(Wire.CSV_COLUMNS.joinToString(", ") { "\"$it\"" })
        append("],\n")
        append("  \"sessions\": [\n")
        entries.forEachIndexed { i, e ->
            append("    {\"name\": \"${e.name}\", \"rows\": ${e.rows}, ")
            append("\"digest\": \"${digestHex(e.digest)}\"}")
            append(if (i == entries.size - 1) "\n" else ",\n")
        }
        append("  ]\n")
        append("}\n")
    }

    /**
     * Unsigned hex, because the digest is a bit pattern rather than a count.
     *
     * [Wire.digestHex]'s, so the manifest in a zip and the confirmation a drain
     * sends spell a digest the same way. They are read by the same controller.
     */
    fun digestHex(digest: Int): String = Wire.digestHex(digest)

    // --- reading -----------------------------------------------------------

    /** Why an archive cannot be imported. Every one of these changes nothing. */
    sealed interface Rejection {
        val reason: String

        data class NotAnArchive(override val reason: String) : Rejection
        data class Truncated(override val reason: String) : Rejection
        data class NoSessions(override val reason: String) : Rejection
    }

    /** What a file turned out to hold, once it has been read through once. */
    class Survey(
        val sessions: List<Entry>,
        /** Entries skipped because their name is not a session name. */
        val ignored: List<String>,
        /** Rows that could not be read as a reading. */
        val unreadable: Int,
        val hadManifest: Boolean,
    ) {
        val rows: Int get() = sessions.sumOf { it.rows }
    }

    /**
     * Read a file through without sending anything, and decide whether it is
     * safe to send.
     *
     * This is the whole of "a corrupt or truncated file changes nothing": the
     * controller is not told a single row until this has succeeded. A file that
     * carries a manifest is checked against it session by session, which is what
     * catches a transfer that was cut short - the CSV that is left parses
     * perfectly and is simply missing its end, and only the row count and the
     * digest can tell.
     *
     * A file with no manifest - a session CSV somebody has kept on their own -
     * is accepted on structure alone, because there is nothing to check it
     * against and refusing it would refuse the user their own data.
     */
    fun survey(open: () -> InputStream, displayName: String): Result<Survey> {
        val sessions = ArrayList<Entry>()
        val ignored = ArrayList<String>()
        var unreadable = 0
        var declared: List<Entry>? = null

        try {
            if (isZip(displayName)) {
                ZipInputStream(open()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if (name == MANIFEST) {
                            // Bounded, unlike `readBytes()`, which sizes itself
                            // from what the stream hands it. A few hundred bytes
                            // of zip can declare gigabytes of manifest, and this
                            // path takes a file the user picked off their own
                            // device - one they were sent, quite possibly.
                            declared = parseManifest(readCapped(zip, MANIFEST_MAX_BYTES))
                            continue
                        }
                        if (entry.isDirectory) continue
                        if (!isSessionName(name)) {
                            ignored.add(entry.name)
                            continue
                        }
                        val tally = tallySession(zip.bufferedReader())
                        unreadable += tally.unreadable
                        sessions.add(Entry(name, tally.rows, tally.digest))
                    }
                }
            } else {
                if (!isSessionName(displayName)) {
                    return Result.failure(
                        ArchiveRejected(
                            Rejection.NotAnArchive(
                                "'$displayName' is not a session file or an archive of them",
                            ),
                        ),
                    )
                }
                val tally = open().use { tallySession(it.bufferedReader()) }
                unreadable += tally.unreadable
                sessions.add(Entry(displayName, tally.rows, tally.digest))
            }
        } catch (e: java.util.zip.ZipException) {
            return Result.failure(
                ArchiveRejected(
                    Rejection.NotAnArchive("that file is not a readable archive: ${e.message}"),
                ),
            )
        } catch (e: java.io.EOFException) {
            // A zip cut off mid-entry. Nothing has been sent, so nothing changed.
            return Result.failure(
                ArchiveRejected(Rejection.Truncated("that archive is cut short and cannot be read")),
            )
        } catch (e: java.io.IOException) {
            return Result.failure(
                ArchiveRejected(Rejection.NotAnArchive("that file could not be read: ${e.message}")),
            )
        }

        if (sessions.none { it.rows > 0 }) {
            return Result.failure(
                ArchiveRejected(Rejection.NoSessions("there are no readings in that file")),
            )
        }

        declared?.let { manifest ->
            for (want in manifest) {
                val got = sessions.firstOrNull { it.name == want.name }
                if (got == null) {
                    return Result.failure(
                        ArchiveRejected(
                            Rejection.Truncated(
                                "${want.name} is named in the manifest but missing from the file",
                            ),
                        ),
                    )
                }
                if (got.rows != want.rows || got.digest != want.digest) {
                    return Result.failure(
                        ArchiveRejected(
                            Rejection.Truncated(
                                "${want.name} is damaged: the file holds ${got.rows} readings " +
                                    "where the manifest says ${want.rows}",
                            ),
                        ),
                    )
                }
            }
        }

        return Result.success(Survey(sessions, ignored, unreadable, declared != null))
    }

    /** Thrown out of [survey] so the reason travels with the failure. */
    class ArchiveRejected(val rejection: Rejection) : Exception(rejection.reason)

    /**
     * Which cell of the file feeds each column of the shape rows are read into.
     *
     * The file's own header decides, by name, so a file whose column order
     * differs - or which came from a build that wrote them differently - still
     * reads correctly. A header that names `rel_boot` and `rel_ms` is a session
     * recorded without a clock and is read into the wider shape; anything else
     * is read into the classic thirteen, exactly as before. Deciding from the
     * header rather than from the file's name means a session someone renamed
     * still gives up everything it holds.
     *
     * @return null when the header is not a session's at all.
     */
    private fun readOrder(header: String): List<Int>? {
        val names = header.split(',').map { it.trim().lowercase() }
        val shape = if (Wire.COL_REL_BOOT in names && Wire.COL_REL_MS in names) {
            Wire.CSV_COLUMNS_RELATIVE
        } else {
            Wire.CSV_COLUMNS
        }
        val order = shape.map { names.indexOf(it) }
        // No timestamp column: not a session file at all. A clockless session
        // still has the column, holding an empty cell in every row, because the
        // board declined to invent an instant rather than declining to say so.
        return if (order[1] < 0) null else order
    }

    /**
     * Read one session CSV, tallying it and handing every row to [emit].
     *
     * The rows [emit] sees are always in [Wire.CSV_COLUMNS] order, or in
     * [Wire.CSV_COLUMNS_RELATIVE] order when the file carries the board's own
     * clock, whatever the file's own order was. That normalisation is why the
     * controller can read a row by position and does not need a header parser of
     * its own, and the wider shape is a strict extension of the narrower one, so
     * a reader that only knows the thirteen reads the first thirteen unchanged.
     */
    suspend fun readSession(reader: BufferedReader, emit: suspend (List<String>) -> Unit): Tally {
        val tally = Tally()
        val header = reader.readLine() ?: return tally
        val order = readOrder(header) ?: return tally

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            val cells = line.split(',')
            val normalised = order.map { c -> if (c in cells.indices) cells[c].trim() else "" }
            tally.add(normalised)
            emit(normalised)
        }
        return tally
    }

    /** The same walk, tallying only. Used by [survey], which sends nothing. */
    private fun tallySession(reader: BufferedReader): Tally {
        val tally = Tally()
        val header = reader.readLine() ?: return tally
        val order = readOrder(header) ?: return tally
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            val cells = line.split(',')
            tally.add(order.map { c -> if (c in cells.indices) cells[c].trim() else "" })
        }
        return tally
    }

    /**
     * Walk an archive's sessions, handing each row to [emit].
     *
     * Streamed for the same reason [Writer] is: an archive of a month of
     * recording does not fit in a tablet's heap, and the caller only ever needs
     * a chunk of rows at a time to put on the wire.
     */
    suspend fun eachSession(
        open: () -> InputStream,
        displayName: String,
        onSession: suspend (String) -> Unit,
        emit: suspend (List<String>) -> Unit,
    ) {
        if (isZip(displayName)) {
            ZipInputStream(open()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name == MANIFEST || !isSessionName(name)) continue
                    onSession(name)
                    readSession(BufferedReader(InputStreamReader(zip)), emit)
                }
            }
        } else {
            onSession(displayName)
            open().use { readSession(BufferedReader(InputStreamReader(it)), emit) }
        }
    }

    private fun isZip(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

    /**
     * The `sessions` array of a manifest.
     *
     * Read with a regex rather than a JSON parser: the manifest is written by
     * [manifestJson] a few lines above and is the only thing that ever produces
     * one, so the shape is known exactly. A manifest that does not match is
     * treated as absent - the archive is then checked on structure alone, which
     * is a weaker check but never a wrong one.
     */
    fun parseManifest(text: String?): List<Entry>? {
        if (text == null) return null
        val pattern = Regex(
            """\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"rows"\s*:\s*(\d+)\s*,\s*"digest"\s*:\s*"([0-9a-fA-F]+)"\s*\}""",
        )
        val found = pattern.findAll(text).map { m ->
            Entry(
                name = m.groupValues[1],
                rows = m.groupValues[2].toInt(),
                digest = java.lang.Long.parseLong(m.groupValues[3], 16).toInt(),
            )
        }.toList()
        return if (found.isEmpty()) null else found
    }

    /** The `name,readings,digest` lines the controller's erase asks for. */
    fun backupClaim(entries: List<Entry>): String =
        entries.filter { it.rows > 0 }
            .joinToString("\n") { "${it.name},${it.rows},${digestHex(it.digest)}" }
}

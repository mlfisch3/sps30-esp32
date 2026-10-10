package com.sensirion.sps30.config

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sensirion.sps30.data.SessionCsv
import com.sensirion.sps30.data.SessionPlan
import com.sensirion.sps30.data.TimeSeriesHistory
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Finds the session data this device has already stored and reads it back.
 *
 * Everything here came out of **Downloads/SPS30**, written by this app: the
 * session archives pulled from the sensor, and any loose session CSVs beside
 * them. No storage permission is involved - an app can always read back the
 * MediaStore items it inserted - which is also the limit: files put there by
 * something else are invisible to us, and that is worth saying rather than
 * passing off as "nothing found".
 *
 * **This is read-only over stored data, on every path.** Sessions that hold no
 * readings are left out of an import and reported in its summary; they are not
 * deleted, moved or rewritten, and nothing here has the code to do so. The only
 * thing it ever removes is [releaseCache], which clears the app's own scratch
 * copies of archives out of `cacheDir`. Removing an empty session, if that is
 * ever wanted, is a decision for the person whose data it is.
 */
object StoredSessions {
    private const val SUBDIR = "SPS30"

    /** A candidate file in stored data, before anything has been read from it. */
    data class Source(
        val name: String,
        val uri: Uri?,
        val file: File?,
        val bytes: Long,
        val isArchive: Boolean,
    )

    /** What one import run did, in enough detail to say what it did not do. */
    class Tally {
        /** Files read that produced at least one point. */
        var sessions = 0
        var points = 0
        var rows = 0L
        /**
         * Files holding a header and no readings.
         *
         * Mostly settled at planning time, from the size and the header, before
         * anything was read. Files too large to classify that cheaply and found
         * empty on the way through are added here as well.
         */
        var empty = 0
        var unrecognised = 0
        /** Sessions already held, from an overlapping archive or an earlier run. */
        var duplicate = 0
        var truncated = false
    }

    /**
     * Every stored file worth reading, newest first.
     *
     * Newest first matters: each archive contains *every* session recorded up to
     * the moment it was made, so consecutive archives overlap almost entirely.
     * Taking the newest first means the older ones' duplicate sessions are
     * recognised by name and skipped before they are decompressed.
     *
     * The app's own plot exports (`sps30-*.csv`) are deliberately excluded. They
     * are pictures of a window that was already on screen, derived from the same
     * sessions, so importing them would draw parts of the record twice.
     */
    fun list(context: Context): List<Source> {
        val out = ArrayList<Source>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR%")
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    if (!isSessionData(name)) continue
                    val uri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(idCol).toString(),
                    )
                    out.add(Source(name, uri, null, c.getLong(sizeCol), isArchive(name)))
                }
            }
        }
        // Android 9 and older, and anything the MediaStore insert fell back to.
        File(context.getExternalFilesDir(null), "exports").listFiles()?.forEach { f ->
            if (f.isFile && isSessionData(f.name) && out.none { it.name == f.name }) {
                out.add(Source(f.name, null, f, f.length(), isArchive(f.name)))
            }
        }
        return out.sortedByDescending { it.name }
    }

    /**
     * Work out what is actually worth reading in [sources], before reading it.
     *
     * Classification is nearly free. An archive's entry sizes come from its own
     * directory, so a session that is plainly too small to hold a reading is
     * caught for the cost of inflating its 88-byte header rather than the cost
     * of parsing a file. Two thirds of the stored record is exactly that, and
     * leaving it out here is what makes the progress bar measure real work.
     *
     * The returned plan also carries the uncompressed bytes behind the sessions
     * that will be read - not every session found - so the averaging window is
     * sized from the data instead of from the duplicates and the empties.
     *
     * Blocking; call it off the main thread.
     */
    fun plan(
        context: Context,
        sources: List<Source>,
        history: TimeSeriesHistory,
    ): SessionPlan.Plan<Source> {
        // One open handle per archive, held for the whole of planning: a
        // candidate's open() reads through it, and closing it early would turn
        // "this file holds no readings" into "this file could not be opened",
        // which is a very different claim to make about someone's data.
        val handles = ArrayList<ZipFile>()
        try {
            val candidates = ArrayList<SessionPlan.Candidate<Source>>()
            for (source in sources) {
                if (!source.isArchive) {
                    candidates.add(
                        SessionPlan.Candidate(source.name, source.bytes, source) {
                            runCatching { openStream(context, source) }.getOrNull()
                        },
                    )
                    continue
                }
                val zip = runCatching { openArchive(context, source) }.getOrNull() ?: continue
                handles.add(zip)
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".csv", ignoreCase = true) }
                    .sortedByDescending { it.name }
                    .forEach { entry ->
                        candidates.add(
                            SessionPlan.Candidate(
                                entry.name, entry.size.coerceAtLeast(0L), source,
                            ) { runCatching { zip.getInputStream(entry) }.getOrNull() },
                        )
                    }
            }
            return SessionPlan.build(candidates) { history.hasImported(it) }
        } finally {
            handles.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Read every session in [plan] into [history], averaged to [bucketSeconds].
     *
     * [onSession] is called before each session is read, so a caller can show
     * progress and - since it may suspend - let other work on a shared dispatcher
     * run between sessions. Cancelling the calling coroutine stops the run and
     * still leaves the history consistent.
     *
     * Failure to read one file is counted, never thrown: the point of the
     * feature is to show whatever is there. Nothing is written, moved or removed
     * on any path through here.
     */
    suspend fun importInto(
        context: Context,
        plan: SessionPlan.Plan<Source>,
        history: TimeSeriesHistory,
        bucketSeconds: Double,
        maxPoints: Int,
        onSession: suspend (name: String, done: Int, total: Int, points: Int) -> Unit =
            { _, _, _, _ -> },
    ): Tally {
        val tally = Tally()
        // What planning already established. The empties and duplicates are not
        // read, but they are still part of an honest account of the run.
        tally.empty = plan.empty
        tally.unrecognised = plan.unrecognised
        tally.duplicate = plan.duplicate
        val total = plan.total
        var done = 0
        try {
            // groupBy keeps first-encounter order, and the candidates were built
            // archive by archive, so this is the reading order with one open
            // handle per archive.
            outer@ for ((source, entries) in plan.read.groupBy { it.source }) {
                val zip = if (source.isArchive) {
                    runCatching { openArchive(context, source) }.getOrNull()
                } else {
                    null
                }
                if (source.isArchive && zip == null) {
                    // Openable while planning, not now. Say so rather than
                    // letting the bar quietly stop short of its total.
                    tally.unrecognised += entries.size
                    done += entries.size
                    continue@outer
                }
                try {
                    for (candidate in entries) {
                        val entry = candidate.name
                        if (tally.points >= maxPoints) {
                            tally.truncated = true
                            break@outer
                        }
                        onSession(entry, done, total, tally.points)
                        done++
                        runCatching {
                            val stream = if (zip != null) {
                                zip.getEntry(entry)?.let { zip.getInputStream(it) }
                                    ?: throw NoSuchElementException(entry)
                            } else {
                                openStream(context, source)
                            }
                            stream.use { readOne(entry, it, history, bucketSeconds, maxPoints, tally) }
                        }.onFailure { tally.unrecognised++ }
                    }
                } finally {
                    zip?.close()
                }
            }
        } finally {
            // Even a cancelled run has to leave the store sorted, or every
            // window afterwards would read the wrong slice.
            history.finishImport()
        }
        return tally
    }

    private fun readOne(
        entry: String,
        stream: InputStream,
        history: TimeSeriesHistory,
        bucketSeconds: Double,
        maxPoints: Int,
        tally: Tally,
    ) {
        val reader = BufferedReader(InputStreamReader(stream), 64 * 1024)
        val result = SessionCsv.read(reader, bucketSeconds) { bucket, epoch, values, flagged ->
            if (tally.points >= maxPoints) {
                tally.truncated = true
            } else {
                // The bucket ordinal is absolute time, so re-reading this session
                // later produces the same keys and adds nothing.
                val added = history.addImported(
                    entry, bucket.toInt(), epoch, values, if (flagged) SETTLE else "",
                )
                if (added) tally.points++
            }
        }
        tally.rows += result.rowsRead
        when (result.skip) {
            SessionCsv.Skip.EMPTY -> tally.empty++
            SessionCsv.Skip.UNRECOGNISED -> tally.unrecognised++
            null -> tally.sessions++
        }
    }

    /**
     * Open [source] as a random-access zip, copying it into the cache first when
     * it is a MediaStore item.
     *
     * `ZipFile` needs a real file and a MediaStore item is a stream, so the copy
     * is unavoidable - but it pays for itself. Random access reads the archive's
     * directory without inflating it, and lets a session that is already loaded
     * be skipped outright instead of being decompressed only to be discarded.
     * Since consecutive archives are nearly identical, that is most of them.
     *
     * The copy is reused across calls in a run and cleared by [releaseCache].
     */
    private fun openArchive(context: Context, source: Source): ZipFile =
        ZipFile(source.file ?: cached(context, source))

    private fun cached(context: Context, source: Source): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val dest = File(dir, source.name)
        if (dest.isFile && dest.length() == source.bytes) return dest
        openStream(context, source).use { input ->
            dest.outputStream().use { input.copyTo(it, 256 * 1024) }
        }
        return dest
    }

    /**
     * Delete the scratch copies made while reading archives.
     *
     * `cacheDir` only, and only files this object put there. The archives and
     * session files in Downloads are never touched.
     */
    fun releaseCache(context: Context) {
        File(context.cacheDir, CACHE_DIR).deleteRecursively()
    }

    private fun openStream(context: Context, source: Source): InputStream =
        source.file?.inputStream()
            ?: source.uri?.let { context.contentResolver.openInputStream(it) }
            ?: throw java.io.FileNotFoundException(source.name)

    /** Session archives, and session CSVs named the way the sensor names them. */
    private fun isSessionData(name: String): Boolean =
        isArchive(name) || (name.startsWith("SPS30-") && name.endsWith(".csv", ignoreCase = true))

    private fun isArchive(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

    private const val CACHE_DIR = "stored-sessions"

    /** Marks a bucket made entirely of disturbed readings, as the daemon does. */
    private const val SETTLE = "settle"
}

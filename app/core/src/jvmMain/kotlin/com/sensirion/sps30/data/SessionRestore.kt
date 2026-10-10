package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime

/**
 * Putting readings back into this device's record from a file it is given.
 *
 * The way in. Everything else in this app moves data *out* - the plot CSV, the
 * session archive, the send back to the controller - and until this existed a
 * host backup preserved readings it could never return. That is a real hole in a
 * product whose whole value is a trustworthy record: the captain's Aug 4 morning
 * survived only in a copy on a laptop, the controller's ring had long since
 * wrapped past it, and there was no route by which those 12,669 readings could
 * reach the plot they were missing from.
 *
 * ## Nothing here is new code doing an old job
 *
 * There is exactly one CSV reader and exactly one merge in this project, and
 * this uses both rather than growing a second of either:
 *
 * - [SessionArchive.survey] decides whether the file is worth reading at all,
 *   and it is the same check that stands between a corrupt file and the
 *   controller. Not one reading is offered to the record until it has passed.
 * - [SessionArchive.eachSession] does the reading, so a file whose columns are
 *   ordered differently, or which predates a column, is read exactly as the send
 *   path reads it.
 * - [TimeSeriesHistory.addRows] does the merge. It is keyed on `(session,
 *   index)` and can only ever add, which is what makes importing the same file
 *   twice harmless and what makes an overlap merge instead of doubling.
 *
 * ## Why the session name is canonicalised rather than trusted
 *
 * The merge de-duplicates on the session name, so the name is not a label - it
 * is half of the identity of every reading in the file. A file that comes back
 * from a host has almost always been renamed on the way: `-recovered-gap`, or
 * ` (1)`, or a `.CSV` from a filesystem that shouts. Merging such a file under
 * the name it arrived with would file its readings under a session that never
 * existed, and every one of them would then be a *second* copy of a reading the
 * record may already hold, drawn twice on the plot and exported twice.
 *
 * So the name is reduced to the session key inside it ([sessionKeyOf]), and a
 * file with no session key in its name is refused rather than guessed at. The
 * price of that leniency is paid by [startsWhereItsNameSays]: a file may be
 * annotated, but it may not disagree with the session it claims to be.
 */
object SessionRestore {

    /**
     * Rows handed to the merge at a time.
     *
     * The merge re-sorts the whole store once per call when the rows interleave
     * with what is held, which restoring an old session always does, so a batch
     * too small pays for that sort over and over. A batch too large holds a
     * session's strings on the heap for no reason. Four thousand rows is about
     * an hour of recording at 1 Hz and well under a megabyte.
     */
    const val BATCH_ROWS = 4_096

    /**
     * How far before its own name a session's first reading may be stamped.
     *
     * A session file is named for the instant it started and its first reading
     * is stamped at that instant, so in the ordinary case this slack is not used
     * at all. It exists for rounding and for a board that stamped its first
     * reading a moment before it named the file, and it is deliberately far
     * smaller than any mistake worth catching: a file renamed onto the wrong
     * session is wrong by hours or by days, never by a minute.
     */
    const val NAME_SLACK_SECONDS = 60L

    /** Why a file was not read. Every one of these changes nothing at all. */
    sealed interface Refusal {
        val reason: String

        /** The name carries no session key, so its readings cannot be filed. */
        data class NotASession(override val reason: String) : Refusal

        /** The file disagrees with the session its name claims it is. */
        data class WrongSession(override val reason: String) : Refusal

        /** Recorded with no clock, and nothing on this device can place it. */
        data class NoClock(override val reason: String) : Refusal

        /** [SessionArchive.survey] read it and found it wanting. */
        data class Damaged(override val reason: String) : Refusal
    }

    /** Thrown out of [restore] so the reason travels with the failure. */
    class FileRefused(val refusal: Refusal) : Exception(refusal.reason)

    /**
     * What one restore actually did, in enough detail to prove it.
     *
     * The counts are exhaustive by construction: [readings] is what the file
     * offered and [added] + [alreadyHeld] + [undated] is what became of every
     * one of them. A summary that only counted successes would read as "all of
     * it" when it was not, and the whole point of this feature is that the
     * captain can see the gap close rather than assume it did.
     */
    data class Report(
        /** Readings found in the file and offered to the record. */
        val readings: Int,
        /** Readings the record did not have, now stored. */
        val added: Int,
        /** Readings the record already held. They were kept exactly as they were. */
        val alreadyHeld: Int,
        /** Readings nothing could give a time to, so nothing could store. */
        val undated: Int,
        /**
         * Rows the file holds that are not readings at all: no index, or no time
         * of either kind. Counted by [SessionArchive.survey] and reported
         * because a file with any of these is a file worth looking at.
         *
         * Not a partition of [readings] - a row can be both offered and
         * unreadable - so it is stated separately rather than added in.
         */
        val unreadableRows: Int,
        /** Sessions in the file that contributed at least one reading. */
        val sessions: Int,
        /**
         * Sessions left out because they were recorded without a clock and
         * nothing on this device can place them. Never silently: see [Refusal.NoClock].
         */
        val clocklessSkipped: Int,
        /**
         * Readings stored with no position on the axis yet.
         *
         * A clockless session whose run has not been placed. They are in the
         * record, counted and exported; they simply have no date until an
         * equivalence arrives or the user places the run by hand.
         */
        val unplaced: Int,
        /** Earliest and latest instant the file's dated readings cover. */
        val fileStart: Double?,
        val fileEnd: Double?,
        /** What the whole record spans once this is in it. */
        val recordStart: Double?,
        val recordEnd: Double?,
    ) {
        /** True when the record ended up holding every reading the file had. */
        val allLanded: Boolean get() = added + alreadyHeld == readings
    }

    /**
     * The session key inside a file name, or null if there is none.
     *
     * Both of the protocol's session-key shapes are recognised: the dated
     * `SPS30-20260804_074435.csv` that `wire.csv_filename` writes, and the
     * `SPS30-R0000012A-0004ABCD.csv` that `wire.rel_session_name` writes for a
     * run with no clock. Whatever a person appended on the way through - a note,
     * a copy number, a shouted extension - is dropped, because none of it is
     * part of what the reading is.
     *
     * A leading path is stripped first, so this can be handed a zip entry name
     * as safely as a picked file's name. Neither key shape can contain a `/`, so
     * what comes back is always a bare name.
     */
    fun sessionKeyOf(fileName: String): String? {
        val bare = fileName.substringAfterLast('/').substringAfterLast('\\')
        DATED_KEY.find(bare)?.let { return it.groupValues[1] + ".csv" }
        RELATIVE_KEY.find(bare)?.let { return it.groupValues[1] + ".csv" }
        return null
    }

    // Anchored at the start: the key names the file, it does not merely appear
    // somewhere inside it. `SPS30-` is the only prefix either shape has.
    private val DATED_KEY = Regex("""^(SPS30-\d{8}_\d{6})""")
    private val RELATIVE_KEY = Regex("""^(SPS30-R[0-9A-F]{8}-[0-9A-F]{8})""")

    /**
     * Read [open] into [history], and say what happened.
     *
     * Blocking on the reads; call it from a context where that is allowed. The
     * mutation goes into [history] directly, so this must run in whichever
     * single context owns it.
     *
     * [onProgress] is called as each session begins, and may suspend, which is
     * what lets a long file share a single-threaded dispatcher with the live
     * stream instead of starving it.
     *
     * @param displayName the file's real name, not a label: it decides whether
     *   this is an archive or a lone session, and for a lone session it is the
     *   only thing that says which session the readings belong to.
     */
    suspend fun restore(
        open: () -> InputStream,
        displayName: String,
        history: TimeSeriesHistory,
        onProgress: suspend (session: String, readings: Int) -> Unit = { _, _ -> },
    ): Result<Report> = try {
        Result.success(read(open, displayName, history, onProgress))
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Never a result. A cancelled restore has to unwind as a cancellation or
        // the coroutine that owns it goes on believing it is running.
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun read(
        open: () -> InputStream,
        displayName: String,
        history: TimeSeriesHistory,
        onProgress: suspend (session: String, readings: Int) -> Unit,
    ): Report {
        val archive = isZip(displayName)

        // For a lone session the name is the session key, so it is resolved and
        // checked before anything is read. For an archive the entry names come
        // from a file this app wrote out of the controller's own names, and the
        // manifest already stands behind them.
        val lone = if (archive) {
            null
        } else {
            sessionKeyOf(displayName) ?: throw FileRefused(
                Refusal.NotASession(
                    "'$displayName' does not name a session. A session file is named for " +
                        "when it started, like SPS30-20260804_074435.csv, and that name is " +
                        "how its readings are matched against the ones already here - so a " +
                        "file without it cannot be filed safely. Rename it to the name it " +
                        "had on the sensor and try again.",
                ),
            )
        }
        val surveyName = lone ?: displayName

        if (lone != null && !Wire.isRelativeSessionName(lone)) {
            startsWhereItsNameSays(open, lone)
        }

        val survey = SessionArchive.survey(open, surveyName).getOrElse { e ->
            throw FileRefused(
                Refusal.Damaged(
                    (e as? SessionArchive.ArchiveRejected)?.rejection?.reason
                        ?: e.message ?: "that file could not be read",
                ),
            )
        }

        // Sessions with no clock of their own, decided before a row is merged so
        // that "this cannot be placed" is a refusal rather than a silent nothing.
        // The merge drops such a row on the floor when there is no basis to
        // convert it through, which would report a clean zero added over a file
        // full of readings.
        val carrying = survey.sessions.filter { it.rows > 0 }
        val clockless = carrying.filter { Wire.isRelativeSessionName(it.name) }
        // Refused only when there is nothing else in the file to add. A mixed
        // archive keeps its dated sessions and reports what it left out, because
        // refusing all of it over part of it would lose readings this device can
        // place perfectly well.
        if (history.basis == null && clockless.isNotEmpty() && clockless.size == carrying.size) {
            throw FileRefused(
                Refusal.NoClock(
                    "that file holds ${plural(carrying.size, "session")} recorded while " +
                        "the sensor had no clock. Its readings are numbered from the " +
                        "sensor's own start rather than dated, and nothing on this device " +
                        "can yet say when that run happened - so nothing was added, " +
                        "rather than putting them at a time they were not taken. Connect " +
                        "to the sensor once, so it can say what its clock read when, and " +
                        "import this again.",
                ),
            )
        }
        val skipClockless = if (history.basis == null) {
            clockless.mapTo(HashSet()) { it.name }
        } else {
            emptySet()
        }

        val merge = Merge(history, skipClockless)
        val unplacedBefore = history.unplacedCount()
        SessionArchive.eachSession(
            open = open,
            displayName = surveyName,
            onSession = { name ->
                merge.finishSession()
                merge.begin(name)
                onProgress(name, merge.readings)
            },
            emit = { cells ->
                // Once per batch rather than once per session. A single file of
                // a morning's recording is one session and twelve thousand rows,
                // so reporting only between sessions would leave a still line on
                // screen and - worse - hand the dispatcher back only at the very
                // end, which on the model thread is the live stream stalling for
                // the length of the read.
                //
                // Asked of the row that was counted, not of the running total: a
                // session being skipped emits rows that change nothing, and a
                // total that happened to sit on a boundary would then report
                // itself once for every one of them.
                if (merge.row(cells) && merge.readings % BATCH_ROWS == 0) {
                    onProgress(merge.session, merge.readings)
                }
            },
        )
        merge.finishSession()

        return Report(
            readings = merge.readings,
            added = merge.added,
            alreadyHeld = merge.usable - merge.added,
            undated = merge.readings - merge.usable,
            unreadableRows = survey.unreadable,
            sessions = merge.sessions,
            clocklessSkipped = skipClockless.size,
            unplaced = (history.unplacedCount() - unplacedBefore).coerceAtLeast(0),
            fileStart = merge.earliest,
            fileEnd = merge.latest,
            recordStart = history.earliestEpoch(),
            recordEnd = history.latestEpoch(),
        )
    }

    /**
     * Batches one file's rows into the merge, one session at a time.
     *
     * The counting is done here rather than inferred afterwards because only
     * here is it exact. The merge reports how many rows it stored and says
     * nothing about why it did not store the rest, and the two reasons are not
     * interchangeable: a reading the record already held is the feature working,
     * and a reading nothing could date is data that did not make it. Reporting
     * the second as the first is exactly the comfortable lie this whole feature
     * exists to avoid.
     */
    private class Merge(
        private val history: TimeSeriesHistory,
        private val skip: Set<String>,
    ) {
        var readings = 0; private set
        /** Rows the merge could actually place, whether or not it had them already. */
        var usable = 0; private set
        var added = 0; private set
        var sessions = 0; private set
        var earliest: Double? = null; private set
        var latest: Double? = null; private set

        /** The session being read, for a progress line. Empty between sessions. */
        val session: String get() = name ?: ""

        private var name: String? = null
        private var skipping = false
        private var columns: List<String> = Wire.CSV_COLUMNS
        private var batch = ArrayList<List<String>>(BATCH_ROWS)
        private var contributed = false

        fun begin(session: String) {
            name = session
            skipping = session in skip
            contributed = false
        }

        /** @return false for a row this took no notice of. */
        fun row(cells: List<String>): Boolean {
            if (skipping || name == null) return false
            // The shape follows the file's own header, which is what decides
            // whether `readSession` normalised into the classic thirteen or into
            // the wider clockless shape. Taking it from the row rather than from
            // the session's name means a file whose name and header disagree
            // still has its cells read where they actually are.
            if (batch.isEmpty()) {
                columns = if (cells.size == Wire.CSV_COLUMNS_RELATIVE.size) {
                    Wire.CSV_COLUMNS_RELATIVE
                } else {
                    Wire.CSV_COLUMNS
                }
            }
            readings++
            if (datable(cells)) {
                usable++
                val epoch = SessionCsv.parseEpoch(cells.getOrElse(1) { "" })
                if (epoch != null) {
                    earliest = minOf(earliest ?: epoch, epoch)
                    latest = maxOf(latest ?: epoch, epoch)
                }
            }
            batch.add(cells)
            if (batch.size >= BATCH_ROWS) flush()
            return true
        }

        fun finishSession() {
            flush()
            if (contributed) sessions++
            name = null
            skipping = false
        }

        private fun flush() {
            val into = name ?: return
            if (batch.isEmpty()) return
            // `explicit`: a session the user asked for by name. Without it,
            // retention deletes a restored morning as fast as it arrives, which
            // is precisely the failure that makes a backup worthless.
            added += history.addRows(into, columns, batch, explicit = true)
            contributed = true
            batch = ArrayList(BATCH_ROWS)
        }

        /**
         * Whether the merge can place this row, asked exactly as the merge asks
         * it - including the basis, whose absence makes a clockless row
         * unstorable rather than merely undated.
         */
        private fun datable(cells: List<String>): Boolean {
            if (columns === Wire.CSV_COLUMNS_RELATIVE) {
                val boot = cells.getOrNull(BOOT_AT)?.trim()?.toIntOrNull()
                val ms = cells.getOrNull(MS_AT)?.trim()?.toLongOrNull()
                if (boot != null && ms != null) return history.basis != null
            }
            return SessionCsv.parseEpoch(cells.getOrElse(1) { "" }) != null
        }
    }

    private val BOOT_AT = Wire.CSV_COLUMNS_RELATIVE.indexOf(Wire.COL_REL_BOOT)
    private val MS_AT = Wire.CSV_COLUMNS_RELATIVE.indexOf(Wire.COL_REL_MS)

    /**
     * Refuse a file whose readings do not belong to the session it is named for.
     *
     * This is what pays for [sessionKeyOf]'s leniency. Allowing a file to be
     * annotated means allowing it to be *renamed*, and a file renamed onto
     * another session's name is the one mistake this path cannot take back: its
     * readings are filed under a session they are not part of, so the copy that
     * is correctly named can never afterwards be recognised as the same readings
     * and would be merged a second time, drawing every one of them twice.
     *
     * A session's name is its first reading's local wall clock, and both are
     * written by the board in the board's own local time, so the two compare
     * without either end having to know which zone that was.
     *
     * How tight the comparison can be depends on where the file starts:
     *
     * - **From index 1** it is a whole session, and index 1 *is* the reading the
     *   name was made from, so the two must agree. This is the case a renamed
     *   file is almost always in, and it is the one worth catching.
     * - **From a higher index** it is a copy fetched from part way through, which
     *   is still that session and legitimately starts late. Only the lower bound
     *   survives there: nothing begins before its own beginning.
     *
     * Anything this cannot read - an unrecognisable stamp, an unreadable index -
     * skips the check rather than failing it. There is then nothing to compare,
     * and refusing a file over a question that was never asked would refuse the
     * user their own data.
     */
    private fun startsWhereItsNameSays(open: () -> InputStream, key: String) {
        val named = localOfKey(key) ?: return
        val first = open().use { firstRow(BufferedReader(InputStreamReader(it))) } ?: return
        val early = first.at.isBefore(named.minusSeconds(NAME_SLACK_SECONDS))
        val late = first.index == 1 && first.at.isAfter(named.plusSeconds(NAME_SLACK_SECONDS))
        if (early || late) {
            throw FileRefused(
                Refusal.WrongSession(
                    "that file is named $key, so it should hold the session that began at " +
                        "${named.toLocalDate()} ${named.toLocalTime()} - but its readings " +
                        "start at ${first.at.toLocalDate()} ${first.at.toLocalTime()}. It is " +
                        "either not the session it is named for or it was renamed by mistake, " +
                        "and merging it would file its readings under a session they are not " +
                        "part of. Nothing was added. Give it the name it had on the sensor.",
                ),
            )
        }
    }

    /** The first row that carries both a reading number and a readable stamp. */
    private class FirstRow(val index: Int, val at: LocalDateTime)

    /** The instant a dated session key names, in the board's own local time. */
    private fun localOfKey(key: String): LocalDateTime? {
        val m = Regex("""^SPS30-(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})\.csv$""").find(key)
            ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return runCatching {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        }.getOrNull()
    }

    /**
     * The file's first dated reading, from as little of it as that takes.
     *
     * [SessionCsv.Layout] decides where the timestamp is, so this reads the same
     * cell the parser will even in a file whose columns are ordered differently.
     * The offset on the stamp is deliberately ignored: what is being compared is
     * the board's own local clock against the board's own name for it, and both
     * were written in whatever zone the board was keeping.
     */
    private fun firstRow(reader: BufferedReader): FirstRow? {
        val header = reader.readLine() ?: return null
        val layout = SessionCsv.Layout.of(header) ?: return null
        val indexCol = header.split(',').indexOfFirst { it.trim().equals("index", true) }
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) continue
            val cells = line.split(',')
            val cell = cells.getOrNull(layout.timestampCol)?.trim() ?: continue
            if (cell.length < 19) continue
            val at = runCatching { LocalDateTime.parse(cell.substring(0, 19)) }.getOrNull()
                ?: continue
            val index = cells.getOrNull(indexCol)?.trim()?.toIntOrNull() ?: -1
            return FirstRow(index, at)
        }
    }

    private fun isZip(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

    private fun plural(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"
}

package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Windowed slice: parallel epoch array and per-field value arrays, time-ordered.
 *
 * [epochs] and [series] are already decimated to the caller's `maxPoints` budget,
 * but [totalCount], [yMin] and [yMax] describe the *whole* window, so y-autoscale
 * still sees a spike that decimation happened to step over.
 *
 * [series] carries every PM field, because the record is every PM field. The y
 * extent does not: it covers [PlottedSeries.FIELDS] only, so the axis is fitted
 * to the curves that are actually on screen. PM mass concentrations are
 * cumulative and therefore non-decreasing across the cuts, so including PM10
 * meant the top of the axis was set by a trace nobody could see - and during a
 * coarse-dust event that leaves the drawn curves squashed into the bottom of a
 * plot for no visible reason. On the board session in `src/test/resources` pm10
 * reaches 18.77 where pm1.0 never passes 2.50.
 *
 * [fieldMin] and [fieldMax] carry the extent one field at a time, for the same
 * reason taken one step further: which of the plottable series are on screen is
 * the user's choice, so switching PM2.5 off has to re-fit the axis to PM1.0
 * alone. [yMin] and [yMax] are the aggregate over everything plottable, which is
 * what a caller that has no opinion should get.
 */
class WindowData(
    val epochs: DoubleArray,
    val series: Map<String, DoubleArray>,
    val totalCount: Int = epochs.size,
    val yMin: Double? = null,
    val yMax: Double? = null,
    val fieldMin: Map<String, Double> = emptyMap(),
    val fieldMax: Map<String, Double> = emptyMap(),
)

/**
 * Full-resolution multi-series store keyed on (csv, index), ordered by time -
 * a Kotlin port of `chartview.TimeSeriesHistory`. De-dup is by (csv, index) so
 * live readings (whose index restarts per CSV) never collide across files.
 * Points older than `latest - retentionSeconds` are trimmed to bound memory.
 *
 * Storage is columnar: parallel primitive arrays rather than a list of point
 * objects. A 48h history at 1Hz is ~170k points, and [window] has to touch every
 * value in range to autoscale y, so that walk must be plain array reads - one
 * hash lookup per point per field is what made dragging the chart lag.
 * A [head] offset makes retention-trimming O(1) instead of shifting every point
 * down once a second.
 *
 * Not thread-safe; confine mutation to a single (UI/VM) coroutine context.
 */
class TimeSeriesHistory(
    retentionSeconds: Double = 172_800.0, // 48h default
    /**
     * This device's own clock, in epoch seconds.
     *
     * Here so that [placeableAt] has something to disbelieve a controller
     * against, and injectable so a test can state what "now" is rather than
     * depend on the day it runs.
     */
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {

    var retentionSeconds: Double = retentionSeconds
        private set

    /** One point exposed for export. */
    data class Sample(
        val csv: String,
        val index: Int,
        /**
         * Where this reading sits on an axis of dates, or **null** when nothing
         * places it yet.
         *
         * Nullable so that "I do not know when this happened" cannot be spent as
         * if it were a time. It used to be a plain `Double` carrying elapsed
         * seconds for an unplaced reading, and every consumer read that as an
         * instant in 1970. Ask [relBoot]/[relMs] and [TimeBasis] for what such a
         * reading does know, which is its position within its own run.
         */
        val epoch: Double?,
        val values: Map<String, Double>,
        val event: String = "",
        /** The board's own clock, or null when the reading carried a real time. */
        val relBoot: Int? = null,
        val relMs: Long? = null,
    )

    private val fieldCount = Wire.PM_FIELDS.size

    /** Per column: does the y extent take this field into account? See [WindowData]. */
    private val tracksY = BooleanArray(fieldCount) { Wire.PM_FIELDS[it] in PlottedSeries.FIELDS }

    private var epochs = DoubleArray(INITIAL_CAPACITY)
    private var cols = Array(fieldCount) { DoubleArray(INITIAL_CAPACITY) }
    private var csvIds = IntArray(INITIAL_CAPACITY)
    private var indices = IntArray(INITIAL_CAPACITY)
    // Interned event id; 0 is always "", so `eventIds[p] != 0` is the flagged test.
    private var eventIds = IntArray(INITIAL_CAPACITY)

    // The board's own clock, for points a controller recorded with no clock of
    // its own. [NO_BOOT] means the point arrived with a real timestamp and none
    // of this applies to it. Kept per point rather than converted once and
    // forgotten, because an equivalence can arrive at any moment - and when one
    // does, every point that was placed without it has to be placed again. That
    // is only possible if what the board actually said is still here.
    private var relBoots = IntArray(INITIAL_CAPACITY) { NO_BOOT }
    private var relMsCol = LongArray(INITIAL_CAPACITY)

    private var head = 0 // physical index of logical 0
    private var tail = 0 // physical index one past the logical last

    // Re-ordering scratch, kept between calls. See [sortByEpoch]: allocating
    // these per call is what the 2026-08-12 garbage-collection storm was.
    private var orderBuf: IntArray? = null
    private var mergeBuf: IntArray? = null
    private var fieldBuf: DoubleArray? = null

    // --- the unplaced tail ---------------------------------------------------
    //
    // Readings from a clockless run that nothing can yet date are kept here like
    // any other, and their [epochs] cell holds [UNPLACED] - a NaN - rather than a
    // number pretending to be an instant. NaN sorts above every finite value, so
    // [sortByEpoch] gathers them into one contiguous block at the end of the live
    // range, and that is the invariant everything below leans on:
    //
    //   [head, head+placedEnd())   placed, ascending by epoch
    //   [head+placedEnd(), tail)   unplaced, insertion order
    //
    // The bisects then confine every windowed read to the placed prefix for free
    // (NaN compares false both ways, so the predicates stay monotone), which is
    // what keeps an undated reading off the axis without a filter in the hot loop.
    // They are still counted, exported, cached and re-placed; they simply have no
    // position until [retime] gives them one.

    /** True when [p] holds a reading nothing can date. */
    private fun unplacedAt(p: Int): Boolean = epochs[p].isNaN()

    /**
     * Logical index of the first unplaced point; equals [size] when all are placed.
     *
     * A binary search rather than a counter, because a counter is a second copy of
     * a fact the array already states and the two would drift.
     */
    private fun placedEnd(): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (unplacedAt(head + mid)) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** How many readings are held that nothing can yet date. */
    fun unplacedCount(): Int = size - placedEnd()

    private val csvTable = StringTable()
    private val eventTable = StringTable()
    // Packed (csvId, index) longs - avoids allocating a Pair per dedup check.
    private val seen = HashSet<Long>()

    /**
     * Distinct readings ever stored per session, which retention never takes back.
     *
     * [coverage] answers "what do I still hold", and that is the wrong question to
     * put next to the controller's row count: this device keeps 48 hours and the
     * board's ring holds about 56, so for a session older than the retention
     * window the two counts can never come out level however many times it is
     * fetched. Counting what arrived instead compares like with like, and is what
     * lets a session that has been collected in full stop being asked for again on
     * every connect.
     */
    private val receivedByCsv = HashMap<String, Int>()

    // --- imported archive ---------------------------------------------------
    // Points read from session files stored on this device, kept in the same
    // store as the live stream so windowing, autoscale, the crosshair and export
    // all work on them unchanged.

    /** Oldest imported epoch, below which retention must not trim. Null when none. */
    private var archiveFloorEpoch: Double? = null

    /** Interned csv ids belonging to imports, so they can be removed again exactly. */
    private val importedIds = HashSet<Int>()

    /** Per-segment extent, insertion-ordered, for reporting what was loaded. */
    private val importedByName = LinkedHashMap<String, ImportedSegment>()

    /** What one stored session contributed to the plot. */
    data class ImportedSegment(
        val name: String,
        var points: Int = 0,
        var start: Double = Double.NaN,
        var end: Double = Double.NaN,
    )

    val size: Int get() = tail - head
    fun isEmpty(): Boolean = size == 0

    /** What this device holds of one session on the controller. */
    data class Coverage(
        /** Readings held for it. */
        val rows: Int,
        /**
         * Highest index the copy reaches without a gap, counting up from the
         * lowest index held. Zero when nothing is held.
         */
        val contiguousTo: Int,
        /**
         * Highest index held at all, gap or no gap. Equal to [contiguousTo]
         * exactly when the copy has no hole in it, which is the only thing this
         * device can say about its own completeness without asking.
         */
        val highestIndex: Int = contiguousTo,
        /**
         * Lowest index held. Zero when the copy reaches the session's beginning.
         *
         * The half of "is this copy complete" that [contiguousTo] structurally
         * cannot answer, and the half that let the captain's three hours go
         * missing. [contiguousTo] counts up *from the lowest index held*, so a
         * copy that joined a session at index 12,000 and then received every
         * reading after it has no hole by that test - it merely starts late, and
         * `get_file since_index=contiguousTo` asks for exactly the readings it
         * already has. Non-zero here is what says the missing part is in front
         * of the copy rather than inside it; see `gapFillSince`.
         */
        val lowestIndex: Int = 0,
    )

    /**
     * Per-session coverage of what is held, in one pass.
     *
     * This is what a backfill asks from, and it is deliberately **not** the
     * highest index seen. The two differ exactly when there is a hole - a
     * transport switch, a dropout, a phone that was asleep - and that is the case
     * the backfill exists for: `get_file since_index` past the highest index seen
     * steps straight over the gap and fetches nothing, because the readings that
     * arrived *after* the gap have already pushed that mark past it. A high-water
     * mark is what this store used to keep, and it is why a seam could survive a
     * reconnect that was supposed to close it.
     *
     * [Coverage.rows] is a count and so is `file_list`'s `rows`, which makes
     * "do we already hold all of this session" a fair comparison. Comparing a
     * high-water index against a count says yes for a session with a hole in it.
     *
     * The cost of being right about this is re-transfer: `since_index` is the only
     * cursor the protocol has, so filling a hole means the controller re-sends
     * everything after it, including rows this device already holds. That is
     * bounded by the ring (2.36 days at 1 Hz, about half an hour at the measured
     * 120 readings/s) and the rows arrive de-duplicated, so it costs time and no
     * correctness. Asking from the highest index instead costs the data.
     *
     * Imported archive segments are left out: they are namespaced away from the
     * live key space ([IMPORT_PREFIX]) and are not sessions the controller can be
     * asked about.
     */
    fun coverage(): Map<String, Coverage> {
        if (isEmpty()) return emptyMap()
        // Two passes and one IntArray per session, rather than a list of boxed
        // indices per session: a 48h buffer is ~170k points and this runs on the
        // model thread on every connect.
        val counts = HashMap<Int, Int>()
        for (p in head until tail) {
            if (csvIds[p] in importedIds) continue
            counts[csvIds[p]] = (counts[csvIds[p]] ?: 0) + 1
        }
        val buckets = HashMap<Int, IntArray>(counts.size)
        val filled = HashMap<Int, Int>(counts.size)
        for ((id, n) in counts) buckets[id] = IntArray(n)
        for (p in head until tail) {
            val id = csvIds[p]
            val bucket = buckets[id] ?: continue
            val w = filled[id] ?: 0
            bucket[w] = indices[p]
            filled[id] = w + 1
        }
        val out = HashMap<String, Coverage>(buckets.size)
        for ((id, bucket) in buckets) {
            bucket.sort()
            var run = bucket[0]
            for (i in 1 until bucket.size) {
                val v = bucket[i]
                if (v == run || v == run + 1) run = v else break
            }
            out[csvTable.name(id)] = Coverage(
                rows = bucket.size,
                contiguousTo = run,
                highestIndex = bucket[bucket.size - 1],
                lowestIndex = bucket[0],
            )
        }
        return out
    }

    /**
     * When every dateable reading of the *live* record was taken, ascending.
     *
     * The input [RecordGaps] looks for holes in. A plain `DoubleArray` rather
     * than [snapshot], which allocates a [Sample] and a values map per point:
     * this is asked for over the whole record, a 48h buffer is ~170k points, and
     * a gap sweep must not cost what an export costs.
     *
     * Two exclusions, each of which would otherwise invent a gap that is not one:
     *
     * - **Readings nothing can date.** An unplaced reading has no position, so it
     *   can neither open a hole nor close one, and a clockless run is therefore
     *   incapable of producing a phantom. It is not missing data; it is data with
     *   no date, and the two must never be reported as the same thing.
     * - **Imported archive segments.** They are read back *averaged* - a point a
     *   minute, or on a large archive as coarse as one an hour ([StoredImport]
     *   picks the window from the file sizes) - so their spacing says nothing
     *   about whether readings are missing, and treating it as if it did would
     *   hatch a loaded archive from end to end. What they do prove is that
     *   something is on the plot for that stretch, and that is passed to
     *   [RecordGaps.of] separately, as [importedSegments], where it suppresses a
     *   gap rather than fabricating one.
     */
    fun placedEpochs(): DoubleArray {
        val end = placedEnd()
        var n = 0
        for (i in 0 until end) if (csvIds[head + i] !in importedIds) n++
        val out = DoubleArray(n)
        var w = 0
        for (i in 0 until end) {
            val p = head + i
            if (csvIds[p] in importedIds) continue
            out[w++] = epochs[p]
        }
        return out
    }

    /**
     * The stretches a loaded archive covers, for [RecordGaps.of] to discount.
     *
     * Segments with nothing dateable in them are left out rather than returned as
     * a NaN range: an empty range cannot cover anything, and a NaN one compares
     * false against everything, so the difference is only whether the caller has
     * to know that.
     */
    fun importedRanges(): List<ClosedFloatingPointRange<Double>> =
        importedByName.values.mapNotNull { segment ->
            if (segment.start.isNaN() || segment.end.isNaN()) null
            else segment.start..segment.end
        }

    /** Time-ordered snapshot of every buffered point (for CSV export). */
    fun snapshot(): List<Sample> = ArrayList<Sample>(size).also { out ->
        for (i in 0 until size) {
            val p = head + i
            val values = LinkedHashMap<String, Double>(fieldCount)
            for (f in 0 until fieldCount) values[Wire.PM_FIELDS[f]] = cols[f][p]
            out.add(
                Sample(
                    csvTable.name(csvIds[p]),
                    indices[p],
                    epochs[p].takeUnless { it.isNaN() },
                    values,
                    eventTable.name(eventIds[p]),
                    if (relBoots[p] == NO_BOOT) null else relBoots[p],
                    if (relBoots[p] == NO_BOOT) null else relMsCol[p],
                )
            )
        }
    }

    // Every question below is "when", so every one of them is answered from the
    // placed prefix alone. An undated reading has no earliest, no latest and no
    // claim to be where a session started.

    fun earliestEpoch(): Double? = if (placedEnd() == 0) null else epochs[head]

    fun latestEpoch(): Double? = placedEnd().takeIf { it > 0 }?.let { epochs[head + it - 1] }

    /**
     * Epoch of the earliest *stable* (non-flagged) point - the current acquisition
     * session's true start (settling spikes are skipped). Falls back to the earliest
     * point if every buffered point is flagged, and null if empty. Used by "all".
     */
    fun firstStableEpoch(): Double? {
        val end = head + placedEnd()
        for (p in head until end) if (eventIds[p] == 0) return epochs[p]
        return earliestEpoch()
    }

    /**
     * As [firstStableEpoch], but ignoring anything imported from stored files.
     *
     * "The whole session" and "everything I have" stop being the same question
     * once older sessions can be loaded from this device, and the two need
     * separate answers: framing the live session should not silently zoom out to
     * a fortnight because an archive is also on the plot.
     */
    fun firstStableLiveEpoch(): Double? {
        if (importedIds.isEmpty()) return firstStableEpoch()
        val end = head + placedEnd()
        for (p in head until end) {
            if (csvIds[p] in importedIds) continue
            if (eventIds[p] == 0) return epochs[p]
        }
        for (p in head until end) if (csvIds[p] !in importedIds) return epochs[p]
        return null
    }

    /**
     * Where relative readings get their times. Null until a controller sends one.
     *
     * Held rather than passed per call because it has to be consulted again -
     * every point already stored is re-placed when an equivalence arrives, and
     * that only works if this store knows where to ask.
     */
    var basis: TimeBasis? = null

    /** Add a live [ServerMessage.Reading]. Returns true if newly stored. */
    fun add(reading: ServerMessage.Reading): Boolean =
        add(reading.csv, reading.index, reading.timestamp, reading.values, reading.event,
            reading.relBoot, reading.relMs)

    /**
     * Add one reading by parts. Returns false if it duplicated an existing
     * (csv, index) or lacked any usable time at all. [event] non-blank marks it
     * flagged.
     *
     * A reading with no timestamp but a relative clock is **kept**. It used to be
     * dropped here - `epochOf("")` is null - which meant a controller that had
     * never seen a network produced readings the app discarded one by one, in
     * silence. Where such a reading is placed depends on what [basis] knows, and
     * that answer is allowed to change later; what may not change is whether the
     * reading exists.
     *
     * With no [basis] set at all such a reading is still refused, because there
     * is then nothing that could ever place it and pretending otherwise would
     * put it at an arbitrary point on the chart.
     *
     * A reading the basis cannot yet place is stored [UNPLACED]: kept, counted,
     * exported and cached, but with no position on the axis until [retime] can
     * give it one.
     */
    fun add(
        csv: String?,
        index: String,
        timestamp: String,
        values: Map<String, Double>,
        event: String = "",
        relBoot: Int? = null,
        relMs: Long? = null,
    ): Boolean {
        val name = csv ?: ""
        val csvId = csvTable.intern(name)
        val idx = indexOf(index)
        val key = keyOf(csvId, idx)
        if (key in seen) return false

        val epoch: Double
        if (relBoot != null && relMs != null) {
            val where = basis ?: return false
            where.noteBoot(relBoot)
            where.noteFirstRelMs(relBoot, relMs)
            epoch = placeableAt(where.epochOrNull(relBoot, relMs) ?: UNPLACED)
        } else {
            epoch = placeableAt(epochOf(timestamp) ?: return false)
        }
        // Unplaced readings belong at the end, where the block of them lives; they
        // have no epoch to bisect on and NaN would send the search to the front.
        val at = if (epoch.isNaN()) size else bisectRight(epoch)
        insertAt(at, epoch, csvId, idx, eventTable.intern(event), values,
                 relBoot ?: NO_BOOT, relMs ?: 0L)
        seen.add(key)
        countReceived(name, 1)
        // Before trim(), so a listener hears about a reading even in the moment
        // retention would immediately take it back again.
        notifyStored(name, idx, head + at, event, relBoot ?: NO_BOOT, relMs ?: 0L)
        trim()
        return true
    }

    /** Readings ever stored for [csv], whether or not retention still holds them. */
    fun receivedRows(csv: String): Int = receivedByCsv[csv] ?: 0

    /** The whole of it, for a cache that has to be able to give it back. */
    fun receivedCounts(): Map<String, Int> = LinkedHashMap(receivedByCsv)

    /**
     * The epoch retention is currently held down to, or null when it is not.
     *
     * A scalar rather than a flag per reading, which is all it has ever been:
     * "do not trim below here". It is [archiveFloorEpoch], exposed so a cache
     * can put it back - without it, a restored copy of a session someone waited
     * for would be trimmed away on the first live reading, which is the exact
     * failure `explicit` exists to prevent.
     */
    fun retentionFloor(): Double? = archiveFloorEpoch

    private fun countReceived(csv: String, rows: Int) {
        if (rows <= 0) return
        receivedByCsv[csv] = (receivedByCsv[csv] ?: 0) + rows
    }

    // --- telling a cache what arrived ----------------------------------------

    /**
     * Told about each reading the moment it is newly stored, in arrival order.
     *
     * Deliberately not "told about the store" - a listener that had to work out
     * what changed would have to diff a hundred thousand points a second. This
     * fires exactly once per reading that survived de-duplication, which is the
     * only event a durable copy of the plot needs to hear about. Retention
     * dropping a point later is not reported: a cache bounds itself, and the two
     * bounds are not the same size.
     *
     * [values] is in [Wire.PM_FIELDS] order and the array is **reused** between
     * calls, so a listener that keeps it must copy it.
     */
    fun interface StoredListener {
        fun onStored(
            csv: String,
            index: Int,
            epoch: Double,
            values: DoubleArray,
            event: String,
            relBoot: Int,
            relMs: Long,
        )
    }

    var onStored: StoredListener? = null

    /** Reused by [onStored]; see its contract. */
    private val notifyValues = DoubleArray(fieldCount)

    private fun notifyStored(
        csv: String,
        index: Int,
        physical: Int,
        event: String,
        relBoot: Int,
        relMs: Long,
    ) {
        val listener = onStored ?: return
        for (f in 0 until fieldCount) notifyValues[f] = cols[f][physical]
        listener.onStored(csv, index, epochs[physical], notifyValues, event, relBoot, relMs)
    }

    /**
     * Place every relative point again, because what is known about time changed.
     *
     * Called when an equivalence arrives or the user places a run by hand. The
     * readings themselves are untouched - only where they sit moves - which is
     * the client half of the board's rule that stored data is never rewritten.
     *
     * @return how many points moved.
     */
    fun retime(): Int {
        val where = basis ?: return 0
        var moved = 0
        for (p in head until tail) {
            if (relBoots[p] == NO_BOOT) continue
            val epoch = placeableAt(where.epochOrNull(relBoots[p], relMsCol[p]) ?: UNPLACED)
            // Total-order comparison, so that unplaced-staying-unplaced is not a
            // move: `NaN != NaN` is true under IEEE and would report every
            // undated reading as having shifted on every pass.
            val before = epochs[p]
            val same = if (epoch.isNaN() || before.isNaN()) {
                epoch.isNaN() && before.isNaN()
            } else {
                epoch == before
            }
            if (!same) {
                epochs[p] = epoch
                moved++
            }
        }
        if (moved > 0) {
            sortByEpoch()
            trim()
        }
        return moved
    }

    /**
     * The instant this reading may be drawn at, or [UNPLACED] if it cannot be.
     *
     * The single funnel every epoch entering [epochs] passes through, whichever
     * door it came in by: a live reading, a backfilled row, a cached one, or a
     * re-placement after an equivalence arrived.
     *
     * ## What this is defending against
     *
     * A controller's session header carried `5136780888` as its start, and the
     * board named the file `SPS30-21321011_113448.csv` to match: the year 2132.
     * That number is not an instant at all. It is `5136780888` **microseconds**
     * of uptime - one hour twenty-five minutes - written into a field read as
     * epoch seconds, which is the same unit disease as the UTC offset that was
     * recorded in minutes and formatted in seconds. Thirty-one readings came
     * back dated a hundred and six years out.
     *
     * Nothing downstream could survive that, and each step was correct given its
     * input: the store placed them where their timestamps said, `latestEpoch()`
     * reported the newest of them, [MonitorSession]'s "now" is the later of the
     * wall clock and that, and so the axis ran to 2132 and folded a real
     * recording into two averaged points. The x axis has a backstop for the
     * *left* edge already ([ChartView.EARLIEST_PLAUSIBLE_EPOCH]) and none for
     * the right, because until now nothing could be dated forwards.
     *
     * ## Why unplaced rather than dropped
     *
     * The readings are real measurements. Only the time is wrong, and this
     * project's rule is that a reading nothing can date is kept, counted and
     * exported with no position on the axis rather than either discarded or
     * drawn somewhere plausible. A relative one becomes placeable the moment a
     * good equivalence arrives; an absolute one stays visibly unplaced, which is
     * the honest report that the controller said something impossible.
     *
     * The future tolerance is a day, which is far past any clock skew between a
     * tablet and a board that has just been told the time, and far short of the
     * smallest unit slip that could produce one.
     */
    private fun placeableAt(epoch: Double): Double =
        if (epoch.isNaN() || plausibleInstant(epoch, nowSeconds())) epoch else UNPLACED

    /** True if any point held came from a controller with no clock. */
    fun hasRelativePoints(): Boolean {
        for (p in head until tail) if (relBoots[p] != NO_BOOT) return true
        return false
    }

    /** Fold in a batch; returns the number newly added. */
    fun merge(readings: Iterable<ServerMessage.Reading>): Int {
        var added = 0
        for (r in readings) if (add(r)) added++
        return added
    }

    /**
     * Fold a daemon `file_data` payload (backfill) tagged with [csv] into history.
     * [columns] names each cell (expects `index`, `timestamp`, and PM fields, per
     * [Wire.CSV_COLUMNS]); rows past the retention horizon still de-dup by (csv, index).
     * Returns the number of rows newly stored. Mirrors `chartview.from_rows`.
     *
     * Rows are appended in bulk and the store re-sorted once if they interleave with
     * existing points, rather than shifting the tail per row - importing a day-long
     * file that predates the live buffer would otherwise be quadratic.
     */
    /**
     * @param explicit true when the rows are a session the user asked for by
     *   name - a reconnect backfill or a "copy all sessions". Those are exempted
     *   from retention the same way a device-file import is, because retention
     *   exists to bound what a live stream accumulates unattended, and a session
     *   someone waited for is not that. Without this, copying an hour-old
     *   archive deleted it as fast as it arrived.
     */
    fun addRows(
        csv: String,
        columns: List<String>,
        rows: List<List<String>>,
        explicit: Boolean = false,
    ): Int {
        val idxCol = columns.indexOf("index")
        val tsCol = columns.indexOf("timestamp")
        if (idxCol < 0 || tsCol < 0) return 0
        val eventCol = columns.indexOf(Wire.COL_EVENT) // -1 for older files without it
        // Present only when the controller recorded this session with no clock.
        // Found by name, so a build that has never heard of them reads the same
        // rows and simply ignores two cells.
        val relBootCol = columns.indexOf(Wire.COL_REL_BOOT)
        val relMsCol0 = columns.indexOf(Wire.COL_REL_MS)
        val fieldCols = Wire.PM_FIELDS.map { columns.indexOf(it) }
        val csvId = csvTable.intern(csv)

        var added = 0
        var outOfOrder = false
        var oldest = Double.MAX_VALUE
        var last = latestEpoch() ?: Double.NEGATIVE_INFINITY
        ensureTailRoom(rows.size)

        for (row in rows) {
            if (idxCol >= row.size || tsCol >= row.size) continue
            val idx = indexOf(row[idxCol])
            val key = keyOf(csvId, idx)
            if (key in seen) continue

            val rowBoot = if (relBootCol in row.indices) row[relBootCol].trim().toIntOrNull()
                          else null
            val rowRelMs = if (relMsCol0 in row.indices) row[relMsCol0].trim().toLongOrNull()
                           else null
            val epoch: Double
            if (rowBoot != null && rowRelMs != null) {
                val where = basis ?: continue
                where.noteBoot(rowBoot)
                where.noteFirstRelMs(rowBoot, rowRelMs)
                epoch = placeableAt(where.epochOrNull(rowBoot, rowRelMs) ?: UNPLACED)
            } else {
                epoch = placeableAt(epochOf(row[tsCol]) ?: continue)
            }

            val p = tail
            epochs[p] = epoch
            relBoots[p] = rowBoot ?: NO_BOOT
            relMsCol[p] = rowRelMs ?: 0L
            for (f in 0 until fieldCount) {
                val ci = fieldCols[f]
                cols[f][p] = if (ci in row.indices) row[ci].toDoubleOrNull() ?: 0.0 else 0.0
            }
            csvIds[p] = csvId
            indices[p] = idx
            val event = if (eventCol in row.indices) row[eventCol] else ""
            eventIds[p] = eventTable.intern(event)
            tail++
            notifyStored(csv, idx, p, event, rowBoot ?: NO_BOOT, rowRelMs ?: 0L)

            // An unplaced row belongs after every placed one, and appending it
            // mid-batch is exactly what breaks that - so the batch is re-sorted
            // rather than trusted. It contributes to neither the ordering test
            // nor the retention floor: both are statements about *when*, and it
            // has no when.
            if (epoch.isNaN()) {
                outOfOrder = true
            } else {
                if (epoch < last) outOfOrder = true
                if (epoch < oldest) oldest = epoch
                last = epoch
            }
            seen.add(key)
            added++
        }

        if (added > 0) {
            countReceived(csv, added)
            // Hold the retention floor down to cover what was just asked for,
            // before trim() runs - otherwise trim deletes it on the way in.
            // Skipped when every row was undated: there is no epoch to hold down
            // to, and the floor used to be dragged to zero by exactly that case.
            if (explicit && oldest != Double.MAX_VALUE) {
                archiveFloorEpoch = minOf(archiveFloorEpoch ?: oldest, oldest)
            }
            // Only the rows this call appended can be out of place, so the
            // re-order is a merge of them into what was already ordered rather
            // than a sort of the whole plot. See [sortByEpoch] for what the
            // whole-plot version cost per frame.
            if (outOfOrder) sortByEpoch(sortedPrefix = size - added)
            trim()
        }
        return added
    }

    // --- coming back from a cache -------------------------------------------

    /**
     * Put one reading back from this device's own cache of the plot.
     *
     * Three things this is **not**, and each of them is the point of having a
     * separate door rather than reusing [add]:
     *
     * - It does not count towards [receivedRows]. Those totals are restored
     *   whole by [finishRestore], because they include readings the cache has
     *   already dropped and counting the survivors again would understate them -
     *   and an understated total is what makes a complete session get asked for
     *   on every single connect.
     * - It does not tell [onStored]. These readings are already in the cache;
     *   reporting them would write the whole file back into itself.
     * - It does not sort or trim per reading. [finishRestore] does both once.
     *
     * A reading recorded against a controller's own clock is **re-placed** here
     * rather than trusted to the epoch the cache carries: the cached epoch was
     * computed with whatever was known when it was written, and an equivalence
     * that has arrived since - or one restored a moment ago from the same cache
     * - places it better. The raw `(boot, relMs)` is what is authoritative, and
     * it is what survives.
     *
     * That holds when nothing can place it either: such a reading comes back
     * [UNPLACED], never at the [epoch] the cache happens to hold. With no [basis]
     * at all it is unplaced by the same rule - there is then nothing that could
     * place it, and a cached number is not a substitute for knowing.
     *
     * @return false only for a reading already held.
     */
    fun restore(
        csv: String,
        index: Int,
        epoch: Double,
        values: DoubleArray,
        event: String,
        relBoot: Int,
        relMs: Long,
    ): Boolean {
        val csvId = csvTable.intern(csv)
        val key = keyOf(csvId, index)
        if (key in seen) return false
        var at = placeableAt(epoch)
        if (relBoot != NO_BOOT) {
            val where = basis
            at = if (where == null) {
                UNPLACED
            } else {
                where.noteBoot(relBoot)
                where.noteFirstRelMs(relBoot, relMs)
                placeableAt(where.epochOrNull(relBoot, relMs) ?: UNPLACED)
            }
        }
        ensureTailRoom(1)
        val p = tail
        epochs[p] = at
        for (f in 0 until fieldCount) cols[f][p] = values.getOrElse(f) { 0.0 }
        csvIds[p] = csvId
        indices[p] = index
        eventIds[p] = eventTable.intern(event)
        relBoots[p] = relBoot
        relMsCol[p] = relMs
        tail++
        seen.add(key)
        return true
    }

    /**
     * Close a run of [restore] calls: restore the totals, the retention floor,
     * and time order.
     *
     * [received] is folded in as a maximum rather than assigned, so a cache read
     * that lands after a controller has already started streaming cannot walk a
     * live total backwards.
     *
     * The floor is taken as the *lower* of what is held and what the cache
     * carried, for the same reason: it is a promise not to trim below a point,
     * and the older promise is the one that still has to be kept.
     */
    fun finishRestore(received: Map<String, Int>, floor: Double?) {
        for ((csv, rows) in received) {
            val held = receivedByCsv[csv] ?: 0
            if (rows > held) receivedByCsv[csv] = rows
        }
        if (floor != null) {
            archiveFloorEpoch = minOf(archiveFloorEpoch ?: floor, floor)
        }
        if (size > 1) sortByEpoch()
        trim()
    }

    /**
     * Hand the newest [limit] live readings to [visit], oldest first.
     *
     * What a cache writes when it compacts itself. Imported segments are left
     * out: they were read from files that are still on this device and are put
     * back by asking for them again, not by a second copy inside the cache.
     *
     * [values] is reused between calls, exactly as in [StoredListener].
     */
    fun forEachCacheable(limit: Int, visit: StoredListener) {
        var live = 0
        for (p in head until tail) if (csvIds[p] !in importedIds) live++
        var skip = if (live > limit) live - limit else 0
        for (p in head until tail) {
            if (csvIds[p] in importedIds) continue
            if (skip > 0) {
                skip--
                continue
            }
            for (f in 0 until fieldCount) notifyValues[f] = cols[f][p]
            visit.onStored(
                csvTable.name(csvIds[p]), indices[p], epochs[p], notifyValues,
                eventTable.name(eventIds[p]), relBoots[p], relMsCol[p],
            )
        }
    }

    fun setRetention(seconds: Double) {
        retentionSeconds = seconds
        trim()
    }

    fun clear() {
        head = 0
        tail = 0
        basis?.clear()
        seen.clear()
        receivedByCsv.clear()
        importedIds.clear()
        importedByName.clear()
        archiveFloorEpoch = null
    }

    // --- importing stored sessions ------------------------------------------

    /** What each stored session contributed, in the order the segments arrived. */
    fun importedSegments(): List<ImportedSegment> = importedByName.values.toList()

    fun importedPointCount(): Int = importedByName.values.sumOf { it.points }

    /**
     * True once [segment] has been folded in.
     *
     * Consecutive archives overlap heavily - each is a copy of *every* session
     * recorded so far - so this lets a caller skip a session it already holds
     * instead of decompressing and parsing it a second time.
     */
    fun hasImported(segment: String): Boolean = segment in importedByName

    /**
     * Append one point read from a stored session file.
     *
     * [values] is in [Wire.PM_FIELDS] order and is copied, so one array may be
     * reused for every point. Returns false only for a duplicate.
     *
     * Segment names are namespaced here rather than by the caller, because
     * stored files carry the same `SPS30-*.csv` names the live stream uses and
     * an imported point's index is a time bucket, not the sensor's own counter.
     * Left un-namespaced the two would collide and silently discard each other.
     *
     * Points are appended unsorted; call [finishImport] once at the end.
     */
    fun addImported(
        segment: String,
        index: Int,
        epoch: Double,
        values: DoubleArray,
        event: String = "",
    ): Boolean {
        val csvId = csvTable.intern(IMPORT_PREFIX + segment)
        val key = keyOf(csvId, index)
        if (key in seen) return false
        ensureTailRoom(1)
        val p = tail
        epochs[p] = epoch
        for (f in 0 until fieldCount) cols[f][p] = values.getOrElse(f) { 0.0 }
        csvIds[p] = csvId
        indices[p] = index
        eventIds[p] = eventTable.intern(event)
        relBoots[p] = NO_BOOT
        relMsCol[p] = 0L
        tail++
        seen.add(key)
        importedIds.add(csvId)
        val seg = importedByName.getOrPut(segment) { ImportedSegment(segment) }
        seg.points++
        if (seg.start.isNaN() || epoch < seg.start) seg.start = epoch
        if (seg.end.isNaN() || epoch > seg.end) seg.end = epoch
        archiveFloorEpoch = minOf(archiveFloorEpoch ?: epoch, epoch)
        return true
    }

    /** Restore time order and re-apply retention after a run of [addImported]. */
    fun finishImport() {
        if (size > 1) sortByEpoch()
        trim()
    }

    /**
     * Drop every imported point, leaving the live stream untouched.
     *
     * A real removal rather than letting retention reclaim them: the user asked
     * for these and can ask for them to go, and "they will fade out eventually"
     * is not an answer to that.
     */
    fun clearImported() {
        if (importedIds.isEmpty()) return
        var w = head
        for (p in head until tail) {
            if (csvIds[p] in importedIds) {
                seen.remove(keyOf(csvIds[p], indices[p]))
                continue
            }
            if (w != p) copyPoint(p, w)
            w++
        }
        tail = w
        importedIds.clear()
        importedByName.clear()
        archiveFloorEpoch = null
        trim()
    }

    private fun copyPoint(from: Int, to: Int) {
        epochs[to] = epochs[from]
        for (f in 0 until fieldCount) cols[f][to] = cols[f][from]
        csvIds[to] = csvIds[from]
        indices[to] = indices[from]
        eventIds[to] = eventIds[from]
        relBoots[to] = relBoots[from]
        relMsCol[to] = relMsCol[from]
    }

    /**
     * (epochs, {field: values}) for points with start <= epoch <= end, in order.
     * When [excludeFlagged], disturbed settling/clean-spike points are omitted.
     *
     * [maxPoints] decimates *during* extraction - the result never allocates more
     * than that many values however long the window is. First and last points are
     * always kept, matching the stride rule the chart used to apply afterwards.
     */
    fun window(
        startEpoch: Double,
        endEpoch: Double,
        excludeFlagged: Boolean = false,
        maxPoints: Int? = null,
        bucketSeconds: Double? = null,
    ): WindowData {
        if (bucketSeconds != null && bucketSeconds > 0.0) {
            return bucketedWindow(startEpoch, endEpoch, excludeFlagged, maxPoints, bucketSeconds)
        }
        val lo = bisectLeft(startEpoch)
        val hi = bisectRight(endEpoch)

        // Pass 1: how many points survive the flag filter. An int-array scan, so
        // even a full 48h window costs microseconds.
        var total = 0
        if (excludeFlagged) {
            for (i in lo until hi) if (eventIds[head + i] == 0) total++
        } else {
            total = (hi - lo).coerceAtLeast(0)
        }
        if (total == 0) return emptyWindow()

        val budget = if (maxPoints == null || maxPoints <= 0) total else minOf(maxPoints, total)
        val onlyLast = budget < 2
        val stride = if (onlyLast || total <= budget) 1 else ceilDiv(total - 1, budget - 1)
        val emitted = when {
            onlyLast -> 1
            stride == 1 -> total
            else -> ceilDiv(total, stride) + if ((total - 1) % stride == 0) 0 else 1
        }

        // Pass 2: emit the strided subset while accumulating the exact y range over
        // every point in the window (not just the emitted ones).
        val outEpochs = DoubleArray(emitted)
        val outCols = Array(fieldCount) { DoubleArray(emitted) }
        val fLo = DoubleArray(fieldCount) { Double.POSITIVE_INFINITY }
        val fHi = DoubleArray(fieldCount) { Double.NEGATIVE_INFINITY }
        var ordinal = 0 // position among filter-surviving points
        var w = 0
        for (i in lo until hi) {
            val p = head + i
            if (excludeFlagged && eventIds[p] != 0) continue
            val emit = w < emitted &&
                if (onlyLast) ordinal == total - 1
                else ordinal % stride == 0 || ordinal == total - 1
            if (emit) outEpochs[w] = epochs[p]
            for (f in 0 until fieldCount) {
                val v = cols[f][p]
                if (v < fLo[f]) fLo[f] = v
                if (v > fHi[f]) fHi[f] = v
                if (emit) outCols[f][w] = v
            }
            if (emit) w++
            ordinal++
        }

        return WindowData(
            epochs = outEpochs,
            series = seriesMap(outCols),
            totalCount = total,
            yMin = extentOf(fLo, min = true),
            yMax = extentOf(fHi, min = false),
            fieldMin = extentMap(fLo),
            fieldMax = extentMap(fHi),
        )
    }

    /**
     * Mean of each field over fixed [bucketSeconds] windows.
     *
     * Days of 1 Hz data are unreadably dense, and thinning by simply dropping
     * samples throws away exactly the excursions worth seeing. Averaging within a
     * bucket keeps them - a spike raises its bucket's mean rather than vanishing
     * because the stride stepped over it - and yields a curve the eye can follow.
     *
     * Buckets are aligned to absolute multiples of [bucketSeconds] so the same
     * data always buckets the same way, rather than shifting as the window pans.
     * Each emitted point is timestamped at the mean of its samples, so it sits
     * where the data actually is.
     */
    private fun bucketedWindow(
        startEpoch: Double,
        endEpoch: Double,
        excludeFlagged: Boolean,
        maxPoints: Int?,
        bucketSeconds: Double,
    ): WindowData {
        val lo = bisectLeft(startEpoch)
        val hi = bisectRight(endEpoch)
        if (hi <= lo) return emptyWindow()

        val epochOut = ArrayList<Double>()
        val sums = Array(fieldCount) { ArrayList<Double>() }
        val fLo = DoubleArray(fieldCount) { Double.POSITIVE_INFINITY }
        val fHi = DoubleArray(fieldCount) { Double.NEGATIVE_INFINITY }

        var bucketId = Long.MIN_VALUE
        var epochSum = 0.0
        var count = 0
        val fieldSum = DoubleArray(fieldCount)

        fun flush() {
            if (count == 0) return
            epochOut.add(epochSum / count)
            for (f in 0 until fieldCount) {
                val mean = fieldSum[f] / count
                sums[f].add(mean)
                if (mean < fLo[f]) fLo[f] = mean
                if (mean > fHi[f]) fHi[f] = mean
            }
        }

        for (i in lo until hi) {
            val p = head + i
            if (excludeFlagged && eventIds[p] != 0) continue
            val id = Math.floorDiv(epochs[p].toLong(), bucketSeconds.toLong().coerceAtLeast(1L))
            if (id != bucketId) {
                flush()
                bucketId = id
                epochSum = 0.0
                count = 0
                java.util.Arrays.fill(fieldSum, 0.0)
            }
            epochSum += epochs[p]
            for (f in 0 until fieldCount) fieldSum[f] += cols[f][p]
            count++
        }
        flush()

        val total = epochOut.size
        if (total == 0) return emptyWindow()

        // A bucket per second over two days still outruns the renderer, so the
        // stride cap still applies on top of the averaging.
        val budget = if (maxPoints == null || maxPoints <= 0) total else minOf(maxPoints, total)
        val stride = if (budget < 2 || total <= budget) 1 else ceilDiv(total - 1, budget - 1)
        val kept = if (stride == 1) {
            (0 until total).toList()
        } else {
            buildList {
                var i = 0
                while (i < total) { add(i); i += stride }
                if (last() != total - 1) add(total - 1)
            }
        }

        val outEpochs = DoubleArray(kept.size) { epochOut[kept[it]] }
        val outCols = Array(fieldCount) { f -> DoubleArray(kept.size) { sums[f][kept[it]] } }
        return WindowData(
            epochs = outEpochs,
            series = seriesMap(outCols),
            totalCount = total,
            yMin = extentOf(fLo, min = true),
            yMax = extentOf(fHi, min = false),
            fieldMin = extentMap(fLo),
            fieldMax = extentMap(fHi),
        )
    }

    // --- storage internals --------------------------------------------------

    private fun seriesMap(arrays: Array<DoubleArray>): Map<String, DoubleArray> {
        val out = LinkedHashMap<String, DoubleArray>(fieldCount)
        for (f in 0 until fieldCount) out[Wire.PM_FIELDS[f]] = arrays[f]
        return out
    }

    /**
     * Per-field extent, restricted to the fields that may be plotted.
     *
     * PM4.0 and PM10 are recorded and exported but never drawn, so their extent
     * is not something any axis should be able to reach for by accident.
     * Fields that saw no point are left out rather than reported as infinite.
     */
    private fun extentMap(values: DoubleArray): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(fieldCount)
        for (f in 0 until fieldCount) {
            if (tracksY[f] && values[f].isFinite()) out[Wire.PM_FIELDS[f]] = values[f]
        }
        return out
    }

    /** The extent across every plottable field, or null when the window held nothing. */
    private fun extentOf(values: DoubleArray, min: Boolean): Double? {
        var best = if (min) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        for (f in 0 until fieldCount) {
            val v = values[f]
            if (!tracksY[f] || !v.isFinite()) continue
            if (min) { if (v < best) best = v } else if (v > best) best = v
        }
        return if (best.isFinite()) best else null
    }

    private fun emptyWindow() =
        WindowData(DoubleArray(0), seriesMap(Array(fieldCount) { DoubleArray(0) }), 0, null, null)

    private fun insertAt(
        pos: Int,
        epoch: Double,
        csvId: Int,
        index: Int,
        eventId: Int,
        values: Map<String, Double>,
        relBoot: Int = NO_BOOT,
        relMs: Long = 0L,
    ) {
        ensureTailRoom(1)
        val p = head + pos
        if (p < tail) {
            val n = tail - p
            System.arraycopy(epochs, p, epochs, p + 1, n)
            for (f in 0 until fieldCount) System.arraycopy(cols[f], p, cols[f], p + 1, n)
            System.arraycopy(csvIds, p, csvIds, p + 1, n)
            System.arraycopy(indices, p, indices, p + 1, n)
            System.arraycopy(eventIds, p, eventIds, p + 1, n)
            System.arraycopy(relBoots, p, relBoots, p + 1, n)
            System.arraycopy(relMsCol, p, relMsCol, p + 1, n)
        }
        epochs[p] = epoch
        for (f in 0 until fieldCount) cols[f][p] = values[Wire.PM_FIELDS[f]] ?: 0.0
        csvIds[p] = csvId
        indices[p] = index
        eventIds[p] = eventId
        relBoots[p] = relBoot
        relMsCol[p] = relMs
        tail++
    }

    private fun ensureTailRoom(extra: Int) {
        if (tail + extra <= epochs.size) return
        val needed = size + extra
        if (needed * 2 <= epochs.size) compact() else growTo(maxOf(needed * 2, INITIAL_CAPACITY))
    }

    /** Slide the live range down to index 0 in place. */
    private fun compact() {
        if (head == 0) return
        val n = size
        System.arraycopy(epochs, head, epochs, 0, n)
        for (f in 0 until fieldCount) System.arraycopy(cols[f], head, cols[f], 0, n)
        System.arraycopy(csvIds, head, csvIds, 0, n)
        System.arraycopy(indices, head, indices, 0, n)
        System.arraycopy(eventIds, head, eventIds, 0, n)
        System.arraycopy(relBoots, head, relBoots, 0, n)
        System.arraycopy(relMsCol, head, relMsCol, 0, n)
        head = 0
        tail = n
    }

    private fun growTo(capacity: Int) {
        val n = size
        epochs = epochs.copyOfRange(head, tail).copyOf(capacity)
        cols = Array(fieldCount) { f -> cols[f].copyOfRange(head, tail).copyOf(capacity) }
        csvIds = csvIds.copyOfRange(head, tail).copyOf(capacity)
        indices = indices.copyOfRange(head, tail).copyOf(capacity)
        eventIds = eventIds.copyOfRange(head, tail).copyOf(capacity)
        // copyOf pads with 0, which would read as boot 0 rather than "absolute",
        // so the tail past the live range is filled explicitly.
        relBoots = relBoots.copyOfRange(head, tail).copyOf(capacity)
        relBoots.fill(NO_BOOT, n, capacity)
        relMsCol = relMsCol.copyOfRange(head, tail).copyOf(capacity)
        head = 0
        tail = n
    }

    /**
     * Stable sort of the live range by epoch, permuting every column together.
     *
     * [UNPLACED] is a NaN, and `sortedBy` compares through `Double.compareTo`,
     * whose total order puts NaN above every finite value. So this is also what
     * gathers the unplaced readings into one block at the end and keeps them in
     * arrival order within it - the invariant the bisects rely on, established
     * here rather than by a separate pass.
     */
    private fun sortByEpoch(sortedPrefix: Int = 0) {
        val n = size
        if (n < 2) return
        // Work from index 0 so the permutation is over plain array positions.
        // In place and allocation-free; [compact] returns at once when head is
        // already 0, which after the first trim it usually is not.
        compact()
        val split = sortedPrefix.coerceIn(0, n)
        if (split >= n) return
        val order = orderScratch(n)
        val work = mergeScratch(n)
        for (i in 0 until n) order[i] = i
        // The suffix is what this call brought in - a frame of readings, not a
        // history - so sorting it is cheap whatever the prefix costs.
        mergeSortRange(order, work, split, n)
        if (split > 0) {
            // Everything below the newest row's insertion point is already in
            // its final place, so the merge starts there. That is what makes
            // the ordinary case - readings arriving after the ones held - cost
            // the length of the frame rather than the length of the history.
            val from = firstAbove(order, split, n)
            if (from < split) mergeRuns(order, work, from, split, n)
        }
        applyOrder(order, n)
    }

    /**
     * Re-establish the epoch order after a batch was appended, cheaply.
     *
     * ## Why this is not `sortByEpoch()` on its own
     *
     * This ran as a full re-sort that allocated a fresh copy of every column -
     * eleven arrays at the buffer's whole capacity, about 30 MB once the plot
     * holds its [MAX_POINTS] - **per arriving frame**. A frame is fourteen
     * readings. On the captain's tablet on 2026-08-12 that was 30-80 MB
     * reclaimed four times a second, for as long as rows kept arriving: one
     * coroutine thread and the heap daemon both pegged, the model's single
     * dispatcher saturated, and so every control routed through it - the window
     * chips, the tick density, the options toggles - dead for minutes at a
     * time, while the chart's own gestures went on answering. The readings were
     * never lost and nothing looked broken in any test, because the *only*
     * thing wrong was how much work one frame cost.
     *
     * The order is unchanged - same total order, same stability, same unplaced
     * block at the end - so what follows is an implementation note, not a
     * behaviour note. [sortedPrefix] is how many leading points were already in
     * order before this batch, which is every point the buffer held: the
     * invariant is maintained on every door in, so the only rows that can be
     * out of place are the ones just appended. That turns a sort of the whole
     * history into a merge of one frame into it, and a binary search then skips
     * the part of the history the frame cannot reach at all.
     *
     * The scratch buffers are kept and reused rather than allocated per call,
     * because allocation *was* the fault. Two int arrays at the buffer's
     * length, against eleven columns.
     */
    private fun orderScratch(n: Int): IntArray {
        var buf = orderBuf
        if (buf == null || buf.size < n) {
            buf = IntArray(scratchSizeFor(n))
            orderBuf = buf
        }
        return buf
    }

    private fun mergeScratch(n: Int): IntArray {
        var buf = mergeBuf
        if (buf == null || buf.size < n) {
            buf = IntArray(scratchSizeFor(n))
            mergeBuf = buf
        }
        return buf
    }

    /**
     * How long to make a scratch buffer for a live range of [n].
     *
     * The columns' own capacity, never exactly [n]: the range grows by a frame
     * at a time, so a buffer sized to fit today's is one that reallocates on
     * every frame - which is the fault this whole arrangement exists to remove,
     * reintroduced one array smaller. The columns grow by doubling, so this
     * settles.
     */
    private fun scratchSizeFor(n: Int): Int = maxOf(n, epochs.size, INITIAL_CAPACITY)

    /**
     * First position in `[0, split)` whose epoch sorts above the lowest of the
     * appended run - the earliest place the merge can disturb.
     */
    private fun firstAbove(order: IntArray, split: Int, n: Int): Int {
        val lowest = epochs[order[split]]
        var lo = 0
        var hi = split
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareEpochs(epochs[mid], lowest) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * The order two epochs sort in: `java.lang.Double.compare`'s total order,
     * which is what `sortedBy` used and what puts [UNPLACED] - a NaN - above
     * every real instant. Spelled out because Kotlin's `<` on two `Double`s is
     * IEEE comparison instead, where every test against a NaN is false and the
     * unplaced block would scatter through the placed one.
     */
    private fun compareEpochs(a: Double, b: Double): Int = java.lang.Double.compare(a, b)

    /** Stable bottom-up merge sort of `order[from, to)` by epoch. */
    private fun mergeSortRange(order: IntArray, work: IntArray, from: Int, to: Int) {
        val n = to - from
        if (n < 2) return
        var width = 1
        while (width < n) {
            var i = from
            while (i < to) {
                val mid = minOf(i + width, to)
                val end = minOf(i + 2 * width, to)
                if (mid < end) mergeInto(order, work, i, mid, end)
                i += 2 * width
            }
            width *= 2
        }
    }

    /** Merge the sorted runs `[from, mid)` and `[mid, to)`, in place in [order]. */
    private fun mergeRuns(order: IntArray, work: IntArray, from: Int, mid: Int, to: Int) =
        mergeInto(order, work, from, mid, to)

    private fun mergeInto(order: IntArray, work: IntArray, from: Int, mid: Int, to: Int) {
        var a = from
        var b = mid
        var w = from
        while (a < mid && b < to) {
            // <= keeps it stable, which is what holds arrival order among the
            // unplaced readings - they are all NaN and compare equal.
            work[w++] = if (compareEpochs(epochs[order[a]], epochs[order[b]]) <= 0) {
                order[a++]
            } else {
                order[b++]
            }
        }
        while (a < mid) work[w++] = order[a++]
        while (b < to) work[w++] = order[b++]
        System.arraycopy(work, from, order, from, to - from)
    }

    /**
     * Permute every column into [order], in place, by following cycles.
     *
     * [order] is consumed: each visited position is marked with -1. One
     * element's worth of values is held at a time, so this allocates nothing
     * beyond the small field temporary.
     */
    private fun applyOrder(order: IntArray, n: Int) {
        val temp = fieldTemp()
        for (start in 0 until n) {
            val first = order[start]
            if (first < 0) continue
            if (first == start) { order[start] = -1; continue }
            val tEpoch = epochs[start]
            for (f in 0 until fieldCount) temp[f] = cols[f][start]
            val tCsv = csvIds[start]
            val tIndex = indices[start]
            val tEvent = eventIds[start]
            val tBoot = relBoots[start]
            val tRelMs = relMsCol[start]
            var w = start
            while (true) {
                val s = order[w]
                order[w] = -1
                if (s == start) break
                epochs[w] = epochs[s]
                for (f in 0 until fieldCount) cols[f][w] = cols[f][s]
                csvIds[w] = csvIds[s]
                indices[w] = indices[s]
                eventIds[w] = eventIds[s]
                relBoots[w] = relBoots[s]
                relMsCol[w] = relMsCol[s]
                w = s
            }
            epochs[w] = tEpoch
            for (f in 0 until fieldCount) cols[f][w] = temp[f]
            csvIds[w] = tCsv
            indices[w] = tIndex
            eventIds[w] = tEvent
            relBoots[w] = tBoot
            relMsCol[w] = tRelMs
        }
    }

    private fun fieldTemp(): DoubleArray {
        var buf = fieldBuf
        if (buf == null || buf.size < fieldCount) {
            buf = DoubleArray(fieldCount)
            fieldBuf = buf
        }
        return buf
    }

    private fun trim() {
        if (isEmpty()) return
        // Retention exists to bound what a live stream accumulates on its own.
        // Imported points are not that: the user named them and waited for them,
        // and they are older than the horizon by definition, so applying
        // retention to them would delete the import as fast as it arrived.
        // Measured from the newest *placed* reading. Retention is an age, and an
        // undated reading has none - taking the cutoff from the end of the array
        // would take it from a NaN and turn the whole comparison off.
        val newest = latestEpoch() ?: Double.NEGATIVE_INFINITY
        val cutoff = minOf(newest - retentionSeconds, archiveFloorEpoch ?: Double.MAX_VALUE)
        // Retention never reaches an unplaced reading, and needs no guard saying
        // so: [bisectLeft] cannot walk past the placed prefix, because the
        // unplaced block sorts after it and compares false against every cutoff.
        // Which is the rule that guard existed for - a block waiting to be placed
        // is not what retention bounds, and its age is not "old", it is unknown.
        var drop = bisectLeft(cutoff)

        // The hard cap, applied *after* that and deliberately able to override
        // it. The archive floor above suspends retention and a host can
        // move it: `file_data` is folded with `explicit = true` unconditionally,
        // so a controller streaming rows under distinct (csv, index) keys drags
        // the floor down to whatever epoch it likes and nothing is ever trimmed
        // again. Readings with no known time would be a second way to the same
        // place, and "I cannot date it" must not become "I must keep it
        // forever" - a host would only have to withhold the equivalence. So the
        // bound that does not depend on the floor does not depend on this
        // either. Oldest first, the order retention drops them in.
        val over = size - drop - MAX_POINTS
        if (over > 0) drop += over

        if (drop <= 0) return
        for (i in 0 until drop) seen.remove(keyOf(csvIds[head + i], indices[head + i]))
        head += drop
        // The floor is only meaningful for points still held. Left where it was,
        // it would go on suspending retention for a window that has been dropped.
        archiveFloorEpoch?.let { floor ->
            val oldest = earliestEpoch()
            if (oldest != null && oldest > floor) archiveFloorEpoch = oldest
        }
        if (head > epochs.size / 2) compact()
    }

    // Both searches stay inside the placed prefix without being told to. Their
    // predicates are false for a NaN cell, and the unplaced block sits above the
    // placed one, so each predicate is still monotone across the whole live range
    // and the search simply converges at the first unplaced point. That is what
    // keeps an undated reading out of every window, autoscale and crosshair.

    /** First logical index whose epoch > [epoch] (ties keep insertion order). */
    private fun bisectRight(epoch: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (epochs[head + mid] <= epoch) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First logical index whose epoch >= [epoch]. */
    private fun bisectLeft(epoch: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (epochs[head + mid] < epoch) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Interns strings to dense ids so columns can stay primitive. Id 0 is "". */
    private class StringTable {
        private val ids = HashMap<String, Int>()
        private val names = ArrayList<String>()

        init {
            intern("")
        }

        fun intern(value: String): Int = ids.getOrPut(value) {
            names.add(value)
            names.size - 1
        }

        fun name(id: Int): String = names[id]
    }

    companion object {
        val EASTERN: ZoneId = ZoneId.of("America/New_York")

        /**
         * How far ahead of this device's clock a reading may still be believed.
         *
         * A day. Generous enough for any skew between a tablet and a controller
         * that has just been handed the time, and far below the smallest unit
         * slip that could date one: microseconds read as seconds put an
         * eighty-five minute uptime in the year 2132.
         */
        const val FUTURE_GRACE_SECONDS = 86_400.0

        /**
         * Whether [epoch] could be when a reading was taken.
         *
         * Forwards only. A reading cannot have been taken after tomorrow, and
         * that is the whole of the check.
         *
         * It deliberately does **not** bound the past. An old session is an
         * ordinary thing to import or copy in, and refusing one would break a
         * real use for the sake of a fault that has never come from that
         * direction. The left edge of the axis already has its own backstop in
         * [ChartView.EARLIEST_PLAUSIBLE_EPOCH], which is the right layer for it:
         * clamping a *view* costs nothing, whereas refusing to place a reading
         * discards what the user asked to see. See [placeableAt].
         */
        fun plausibleInstant(epoch: Double, now: Double): Boolean =
            epoch <= now + FUTURE_GRACE_SECONDS

        private const val INITIAL_CAPACITY = 1024

        /**
         * The most points held, whatever the retention window is suspended for.
         *
         * `retentionSeconds` bounds the live stream; the archive floor is
         * allowed to suspend it so that a session the user asked for is not
         * deleted on the way in. Nothing bounded the second case, and a host
         * decides what goes into it, so this does.
         *
         * 500,000 is set from what the hardware can actually hold: the ESP32's
         * `store` partition is 2.5 MB at 10 bytes a reading, so about 262,000
         * readings is the most any controller has to give. Twice that leaves
         * room for the live window on top of a full copy and still bounds the
         * arrays at roughly 50 MB, which a phone can carry. A point costs about
         * 52 bytes of columns plus its entry in `seen`.
         */
        const val MAX_POINTS = 500_000

        /** No relative clock: this point arrived with a real timestamp. */
        const val NO_BOOT = -1

        /**
         * The epoch column's "nothing places this reading yet".
         *
         * NaN rather than a sentinel number, and deliberately: a number would sit
         * on the axis somewhere, and every number that has ever been used for
         * this - zero most of all - is a real instant that a chart will happily
         * draw. NaN is the one value that cannot be plotted, cannot be compared
         * into a window, and sorts to a predictable end of the array. It never
         * escapes this class: [Sample.epoch] is null instead.
         */
        internal val UNPLACED = Double.NaN

        /** Keeps imported segment names out of the live stream's key space. */
        const val IMPORT_PREFIX = "stored:"

        private fun keyOf(csvId: Int, index: Int): Long =
            (csvId.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)

        // Ceiling of a/b for non-negative a and positive b. Kotlin Int division
        // truncates toward zero, so the -(-a/b) trick does NOT work here.
        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /** Parse a wire index ("0000007" or "7") into an int; junk/blank -> 0. */
        fun indexOf(value: String): Int {
            val t = value.trim()
            if (t.isEmpty()) return 0
            t.toIntOrNull()?.let { return it }
            return t.toDoubleOrNull()?.toInt() ?: 0
        }

        /** UTC epoch seconds from an ISO timestamp, or null if unusable. */
        fun epochOf(timestamp: String): Double? {
            val t = timestamp.trim()
            if (t.isEmpty()) return null
            return try {
                OffsetDateTime.parse(t).toEpochSecond().toDouble()
            } catch (e: Exception) {
                try {
                    LocalDateTime.parse(t).atZone(EASTERN).toEpochSecond().toDouble()
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}

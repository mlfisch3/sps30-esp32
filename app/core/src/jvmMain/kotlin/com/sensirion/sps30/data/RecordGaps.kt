package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage

/**
 * Holes in this device's own record, and whether the sensor can still fill them.
 *
 * ## The failure this exists to stop
 *
 * On 2026-08-04 the captain's tablet showed nothing between 07:42:18 and
 * 11:18:52. The board's own record of those hours was perfect - 188,251 readings
 * and not one gap over ten seconds - and the app simply was not connected to
 * receive them. The reconnect backfill then filled *forward* from where it
 * reattached and left the earlier hours behind, because the only completeness
 * test there is is [TimeSeriesHistory.Coverage.contiguousTo], which counts up
 * from the lowest index held: a copy that joined a session half way through has
 * no hole in it by that test, it just starts late. By the time anyone noticed,
 * the board's ring had wrapped past the window and its record began at 14:29 -
 * later than either side of the gap. The readings survive only because a host
 * backup had been taken that day.
 *
 * So there are two separate jobs, and neither of them existed:
 *
 *  1. **Know where the record is discontinuous**, in time, across every session,
 *     rather than per session by index. That is what the user sees, and it is
 *     the only view in which "the front of this session never arrived" and "the
 *     link died for an hour" look like the same thing, which they are.
 *  2. **Know, for each hole, whether it can still be had.** The board's record
 *     is contiguous, so that reduces to whether the hole falls inside the range
 *     the board still retains - and the answer has to be *said*, because a hole
 *     the sensor can supply and a hole lost to the ring look identical on a plot
 *     and call for opposite reactions.
 *
 * ## Why "lost" is never guessed at
 *
 * [GapStanding] has three arms rather than two, and the third one is the whole
 * point. Saying "there is nothing you can do about this, it does not exist
 * anymore" is only worth anything if it is never said about data that does. A
 * `file_list` is bounded by one frame and the automatic pass reads a single
 * page, so most of the time this device has been told about the newest sixty-odd
 * sessions and nothing older. Under those conditions a hole older than the
 * oldest *named* session is not lost; it is un-asked-about, and
 * [GapStanding.Unknown] is what that is. The reconciliation sweep walks the whole
 * listing precisely so that [GapStanding.Lost] can be trusted when it does
 * appear. This is the same rule [RecordCount.Unknown] enforces one screen up.
 */
object RecordGaps {

    /**
     * How many acquisition intervals of silence make a gap.
     *
     * The same factor the plot lifts its pen at ([ChartView] `gapThreshold`), so
     * a hole the trace visibly breaks across is a hole this names. Two different
     * answers to "is that a gap" on one screen would be worse than either.
     */
    const val GAP_FACTOR = 10.0

    /**
     * The shortest run of silence worth calling a gap, whatever the interval is.
     *
     * Five minutes, and deliberately far above the plot's own one-minute floor,
     * because this is not a drawing decision. A gap named here is a gap reported
     * to the user and a gap a session re-fetch may be spent on, and a dropout of
     * a few seconds is neither: a transport hiccup, a watchdog reconnect and a
     * phone's doze scheduling all cost about a minute and none of them is data
     * loss. A list that names every one of those is a list nobody reads, which is
     * how the three-hour hole went unnoticed in the first place.
     *
     * A loaded archive is coarser still - averaged as far as a point an hour on a
     * large one - and is deliberately not handled by raising this. Its spacing
     * says nothing about whether readings are missing, so it is not read as a
     * spacing at all; see [TimeSeriesHistory.placedEpochs].
     */
    const val MIN_GAP_SECONDS = 300.0

    /**
     * How many gaps are carried into the UI at once, newest first.
     *
     * A bound on what is *shown* - the list on the Data tab and the bands on the
     * plot - and never on what is detected or filled: the fill plan is built from
     * every gap found, before this applies. Same reasoning as
     * `MAX_ALERT_SEGMENTS` - a list hundreds long is not a thing anyone reads,
     * and a plot hatched from end to end says nothing at all.
     */
    const val MAX_REPORTED = 50

    /**
     * The interval the controller is recording at, or null when it has not said.
     *
     * A duty-cycled profile records a burst every [ServerMessage.AcquisitionProfile.cycleSeconds]
     * and is *silent* in between by design, so the cycle - not the within-burst
     * interval - is the spacing a gap has to be measured against. Reading the
     * interval there would call every ordinary sleep between bursts a gap, and a
     * detector that fires on the feature working is one that gets switched off.
     */
    fun cadenceSeconds(profile: ServerMessage.AcquisitionProfile?): Double? {
        val p = profile ?: return null
        val cadence = if (p.dutyCycling) maxOf(p.cycleSeconds, p.intervalSeconds) else p.intervalSeconds
        return cadence.takeIf { it > 0.0 && it.isFinite() }
    }

    /** How long a silence has to be, at [cadenceSeconds], before it is a gap. */
    fun thresholdSeconds(cadenceSeconds: Double?): Double {
        val cadence = cadenceSeconds?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        return maxOf(GAP_FACTOR * cadence, MIN_GAP_SECONDS)
    }

    /**
     * Every hole in [epochs], judged against what [retention] says the sensor has.
     *
     * [epochs] must be ascending and finite - the placed readings this device
     * holds, in order, which is exactly what [TimeSeriesHistory.placedEpochs]
     * gives. A reading nothing can date has no position and therefore cannot be
     * either side of a gap; leaving it out here is what stops a clockless run
     * inventing one. See [TimeSeriesHistory.placedEpochs].
     *
     * [cadenceSeconds] is the controller's own interval where it has stated one,
     * and the median spacing of the record where it has not. The median rather
     * than the mean, so that the very holes being looked for do not widen the
     * threshold that is meant to catch them.
     *
     * [covered] is what a loaded archive already puts on the plot, from
     * [TimeSeriesHistory.importedRanges]. A hole the user can see a trace across
     * is not a hole, whatever the live record alone says about it - and the
     * archive's own averaging is far too coarse to be read as a spacing, which is
     * why it arrives as ranges rather than as more epochs.
     *
     * Pass [SensorRetention.UNKNOWN] to detect without judging: every gap then
     * comes back [GapStanding.Unknown], which is the honest answer when nothing
     * has been asked.
     */
    fun of(
        epochs: DoubleArray,
        cadenceSeconds: Double?,
        retention: SensorRetention,
        covered: List<ClosedFloatingPointRange<Double>> = emptyList(),
    ): List<RecordGap> {
        if (epochs.size < 2) return emptyList()
        val cadence = cadenceSeconds ?: medianDelta(epochs) ?: return emptyList()
        val threshold = thresholdSeconds(cadence)
        val out = ArrayList<RecordGap>()
        for (i in 1 until epochs.size) {
            val before = epochs[i - 1]
            val after = epochs[i]
            // NaN compares false both ways, so an unplaced reading that somehow
            // reached this array still cannot open or close a gap.
            if (!(after - before > threshold)) continue
            // Wholly inside a loaded archive: there is a trace over it, so
            // calling it missing would be arguing with what is on the screen.
            // Only wholly - a hole an archive half covers is still a hole, and
            // over-reporting one is the safe way to be wrong about it.
            if (covered.any { before >= it.start && after <= it.endInclusive }) continue
            out.add(
                RecordGap(
                    startEpoch = before,
                    endEpoch = after,
                    standing = standingOf(before, after, retention),
                    expectedReadings = expectedReadings(before, after, cadence),
                )
            )
        }
        return out
    }

    /**
     * What can be said about one hole, given what the sensor has admitted to.
     *
     * The straddling case is a [GapStanding.Fillable] whose `fromEpoch` is later
     * than the gap's own start, and that is deliberately not a fourth arm: what
     * the app should *do* about a partly-recoverable hole is identical to what it
     * should do about a wholly recoverable one - ask for what is there. Whether
     * any of it was lost is a question for the sentence on screen, and
     * [RecordGap.partlyBeyondReach] answers it without inventing a state.
     */
    private fun standingOf(
        startEpoch: Double,
        endEpoch: Double,
        retention: SensorRetention,
    ): GapStanding {
        val reach = retention.startsAt
            ?: return GapStanding.Unknown("the sensor has not said what it still holds")
        // Wholly older than anything the sensor kept. The only case in which the
        // word "lost" may be used, and only then when the listing is settled.
        if (endEpoch <= reach) {
            return retention.unsettledBecause?.let { GapStanding.Unknown(it) } ?: GapStanding.Lost
        }
        return GapStanding.Fillable(fromEpoch = maxOf(startEpoch, reach))
    }

    /**
     * Roughly how many readings are missing, for saying so out loud.
     *
     * Deliberately an estimate and named as one. The exact figure is an index
     * arithmetic the app cannot do - `file_list` states a row count and no last
     * index - and a number computed from the interval is both close enough to be
     * useful and obviously approximate, which is the right shape for a figure
     * nobody should reconcile against anything.
     */
    private fun expectedReadings(startEpoch: Double, endEpoch: Double, cadence: Double): Int {
        if (cadence <= 0.0) return 0
        val n = ((endEpoch - startEpoch) / cadence) - 1.0
        return n.coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
    }

    /**
     * The middle spacing of a record, as the interval when nothing has said.
     *
     * Robust to the gaps themselves, which a mean is not: one three-hour hole in
     * a day of one-second readings drags a mean past a hundred seconds and would
     * raise the threshold above the very thing it is there to find.
     */
    internal fun medianDelta(epochs: DoubleArray): Double? {
        if (epochs.size < 2) return null
        val deltas = DoubleArray(epochs.size - 1) { epochs[it + 1] - epochs[it] }
        deltas.sort()
        return deltas[deltas.size / 2].takeIf { it > 0.0 && it.isFinite() }
    }
}

/**
 * What the sensor can still be asked for, as far as it has actually said.
 *
 * Built from `file_list` and nothing else, because `file_list` is the only
 * per-session account there is: `storage` gives totals, and a total cannot say
 * *when* the record it counts begins. See [DataInventory], which draws the same
 * distinction one row higher up.
 */
data class SensorRetention(
    /**
     * Where the sensor's own record starts, or null when it has not said.
     *
     * The oldest *dated* session it named. The board's record is contiguous from
     * there to now, which is what makes a single number enough to answer "can
     * this be had" for any instant.
     */
    val startsAt: Double?,
    /**
     * Why [startsAt] cannot be treated as the true floor, or null when it can.
     *
     * Non-null forbids the word "lost". It reads as a sentence, and there are
     * exactly two things it can say: the listing was not walked to its end, or
     * some of what the sensor holds carries no clock and therefore cannot be
     * placed relative to anything.
     */
    val unsettledBecause: String?,
    /** Named sessions the sensor recorded with no clock. Reported, never dated. */
    val undatedSessions: Int,
) {
    companion object {
        /** Before anything has been heard from a sensor at all. */
        val UNKNOWN = SensorRetention(
            startsAt = null,
            unsettledBecause = "the sensor has not said what it still holds",
            undatedSessions = 0,
        )

        /**
         * Read the retained range off an accumulated listing.
         *
         * [fullyNamed] is true only when the walk reached the end of the listing
         * - `file_list.next` came back null - so nothing the sensor holds is
         * unnamed. The automatic reconnect pass reads one page and leaves this
         * false on any board with more sessions than a frame can carry, which is
         * every board that has been running a while; the reconciliation sweep
         * walks the whole thing, and that walk is the only reason [GapStanding.Lost]
         * can ever be reached.
         *
         * A clockless session has no start to compare, so it cannot set the
         * floor. What it can do is sit *below* one: if an undated session is
         * older than the oldest dated one, the sensor's record reaches further
         * back than any date here can prove, and the floor is not a floor. The
         * listing is newest first, so "older than" is a position in it, and an
         * undated entry after the oldest dated entry is exactly that case.
         */
        fun of(
            listing: List<ServerMessage.FileEntry>,
            fullyNamed: Boolean,
        ): SensorRetention {
            var startsAt: Double? = null
            var oldestDatedAt = -1
            var undated = 0
            for ((position, entry) in listing.withIndex()) {
                val start = datedStart(entry)
                if (start == null) {
                    undated++
                    continue
                }
                if (startsAt == null || start < startsAt) {
                    startsAt = start
                    oldestDatedAt = position
                }
            }
            val undatedBelowTheFloor = listing.withIndex().count { (position, entry) ->
                position > oldestDatedAt && datedStart(entry) == null
            }
            val because = when {
                startsAt == null -> "the sensor has not named a session it can date"
                !fullyNamed -> "the sensor has not named everything it holds"
                undatedBelowTheFloor > 0 ->
                    "the sensor holds $undatedBelowTheFloor session(s) recorded with no clock, " +
                        "which could be older than anything it can date"
                else -> null
            }
            return SensorRetention(
                startsAt = startsAt,
                unsettledBecause = because,
                undatedSessions = undated,
            )
        }

        /**
         * When one listed session began, or null when nothing dates it.
         *
         * A session flagged `relative` never has one, and a `start` the parser
         * cannot read is treated the same way rather than skipped over: a date
         * this device cannot understand is not a date it may reason from, and
         * silently dropping such an entry would move the retained floor forward
         * onto a session that is not the oldest.
         */
        fun datedStart(entry: ServerMessage.FileEntry): Double? =
            entry.start
                ?.takeUnless { entry.relative }
                ?.let { TimeSeriesHistory.epochOf(it) }
    }
}

/** What can be done about one hole in the record. */
sealed interface GapStanding {
    /**
     * The sensor still holds this stretch and can be asked for it.
     *
     * [fromEpoch] is where its record starts *within* the gap, which is the gap's
     * own start in the ordinary case and later when the ring has already eaten
     * the front of it.
     */
    data class Fillable(val fromEpoch: Double) : GapStanding

    /**
     * The sensor's ring has wrapped past it. There is nothing left to ask.
     *
     * The one arm that says data is gone, and it is only ever reached from a
     * listing that was walked to its end. See [SensorRetention.unsettledBecause].
     */
    data object Lost : GapStanding

    /** [because] is shown in place of a verdict, and reads as a sentence. */
    data class Unknown(val because: String) : GapStanding
}

/**
 * One run of missing time in what this device holds.
 *
 * [startEpoch] and [endEpoch] are readings that *are* held - the last one before
 * the hole and the first one after it - so the missing stretch is strictly
 * between them. Stated that way rather than as a duration because it is what the
 * plot needs to draw a band and what a fill needs to name a session.
 */
data class RecordGap(
    val startEpoch: Double,
    val endEpoch: Double,
    val standing: GapStanding,
    /** About how many readings are missing. An estimate; see `expectedReadings`. */
    val expectedReadings: Int,
) {
    val seconds: Double get() = endEpoch - startEpoch

    /** Where a fill would have to start, or null when nothing can be asked for. */
    val fillableFrom: Double? get() = (standing as? GapStanding.Fillable)?.fromEpoch

    /**
     * True when the sensor can supply part of this and the rest is beyond it.
     *
     * Worth its own name because it is the case a single-word verdict gets
     * wrong: calling it "fillable" would promise the whole of it back, and
     * calling it "lost" would leave hours on the sensor that nothing goes for.
     */
    val partlyBeyondReach: Boolean get() = fillableFrom?.let { it > startEpoch } == true
}

/**
 * The gaps, and what the sweep that found them is doing.
 *
 * One value rather than four fields on the UI state, so that "there are no gaps"
 * and "nothing has looked yet" cannot be rendered the same way: [sweptAtMs] is
 * zero until a sweep has actually completed, and an empty [gaps] before then
 * means nothing at all.
 */
data class RecordGapReport(
    val gaps: List<RecordGap> = emptyList(),
    /** True while a sweep is asking the sensor what it still holds. */
    val sweeping: Boolean = false,
    /** When the last sweep finished, or 0 when none has. */
    val sweptAtMs: Long = 0L,
    /** Sessions a fill has been queued for by the most recent sweep. */
    val fetching: Int = 0,
) {
    val fillable: Int get() = gaps.count { it.standing is GapStanding.Fillable }
    val lost: Int get() = gaps.count { it.standing is GapStanding.Lost }
    val undecided: Int get() = gaps.count { it.standing is GapStanding.Unknown }

    /** Nothing is missing, and that is known rather than assumed. */
    val nothingMissing: Boolean get() = gaps.isEmpty() && sweptAtMs > 0L

    /** Readings that are missing and can never be had. The figure that matters. */
    val readingsLost: Int
        get() = gaps.filter { it.standing is GapStanding.Lost }.sumOf { it.expectedReadings }
}

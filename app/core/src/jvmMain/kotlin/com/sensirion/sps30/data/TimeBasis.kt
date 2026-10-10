package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire

/**
 * Turns a controller's own clock into a time, or says plainly that it cannot.
 *
 * ## The problem this exists for
 *
 * A controller with no network has no idea what the date is. It records anyway,
 * counting from its own start, and every reading carries `(boot, ms)` instead of
 * an instant. `boot` identifies one run of the board; a reboot restarts the count
 * by an amount nobody measured, which is why two runs may never be joined into
 * one and why an equivalence from one run says nothing about another.
 *
 * When a time source reaches the board it appends an **equivalence** - "my clock
 * read X when the world clock read Y" - and sends every one it holds. Converting
 * is then arithmetic, and it is done here rather than on the board so it stays
 * reversible: the raw readings are untouched, so a later or better time source
 * can simply be applied again.
 *
 * ## The three answers, and why the third is not a fourth
 *
 * - [Resolution.Derived] - an equivalence for this run exists. The nearest one is
 *   used, so the error is the board's crystal drift over the gap to it rather
 *   than over the whole run.
 * - [Resolution.Manual] - no equivalence, but the user has said where this run
 *   sits. Their answer, kept separately from measured ones and never mixed in.
 * - [Resolution.Unanchored] - no equivalence and no answer from the user. The
 *   block's *internal* timing is known and correct; only its position is not.
 *
 * There is deliberately no fourth case that guesses. A plausible timestamp is
 * indistinguishable from a measured one once it is on a chart or in an export,
 * and two fabricated sessions in this project's real record are the reason that
 * rule is written down rather than assumed.
 *
 * Not thread-safe; confine it to the same dispatcher as [TimeSeriesHistory].
 */
class TimeBasis {

    /**
     * What is known about when a relative reading happened.
     *
     * There is deliberately **no `epochSeconds` on this interface**. It used to be
     * here, and [Unanchored] satisfied it with elapsed-time-within-the-run - a
     * number that is not an instant and that reads as 1970 the moment anything
     * treats it as one. Four callers did exactly that, and a clockless session
     * landed on the plot at the epoch, stretching the x axis across five decades
     * and folding a real recording into two averaged points.
     *
     * Asking "where does this go on an axis of dates" is therefore a question
     * only [Placed] can answer, and the compiler is what enforces it: an
     * unanchored reading is not merely wrong to convert, it has nothing to
     * convert. Use [TimeBasis.epochOrNull] for the one honest conversion, whose
     * null is the caller's cue to hold the reading back rather than draw it.
     */
    sealed interface Resolution {
        /** What an export should say the time came from - see [Wire.TIME_MEASURED]. */
        val source: String

        /** A resolution that does put the reading at a real instant. */
        sealed interface Placed : Resolution {
            /** Epoch seconds this reading should be placed at. */
            val epochSeconds: Double
        }

        data class Derived(
            override val epochSeconds: Double,
            val utcOffsetMinutes: Int,
            /** Milliseconds between this reading and the equivalence used. */
            val distanceMs: Long,
        ) : Placed {
            override val source: String get() = Wire.TIME_DERIVED
        }

        data class Manual(override val epochSeconds: Double) : Placed {
            override val source: String get() = Wire.TIME_MANUAL
        }

        /**
         * Position unknown, and so no position at all.
         *
         * [elapsedSeconds] is time *within the run* - the block's internal timing,
         * which was never in doubt. It is named for what it is so that no caller
         * can reach for it expecting an instant, and it is the only thing this
         * case offers.
         */
        data class Unanchored(val elapsedSeconds: Double) : Resolution {
            override val source: String get() = Wire.TIME_UNANCHORED
        }
    }

    /** Equivalences the controller has sent, grouped by the run they belong to. */
    private val anchorsByBoot = LinkedHashMap<Int, MutableList<ServerMessage.TimeAnchor>>()

    /** Where the user has said an otherwise unplaceable run begins, epoch seconds. */
    private val manualOrigins = LinkedHashMap<Int, Double>()

    /** The controller's current run, so live readings can be told from stored ones. */
    var currentBoot: Int = 0
        private set

    /** Runs seen in the data, with how many readings each contributed. */
    private val seenBoots = LinkedHashMap<Int, Int>()

    val anchorCount: Int get() = anchorsByBoot.values.sumOf { it.size }

    /**
     * Fold in a `time_map`, keeping only the equivalences that can be true.
     *
     * See [plausibleUtcOffset] for what a refused one looks like and why the
     * whole anchor goes rather than just its offset.
     *
     * @return true if anything changed, which is the caller's cue to re-place
     *         every relative point it is holding.
     */
    fun accept(map: ServerMessage.TimeMap): Boolean {
        var changed = currentBoot != map.boot
        currentBoot = map.boot
        for (anchor in map.anchors) if (admit(anchor)) changed = true
        return changed
    }

    /**
     * Take one equivalence in, unless its own offset says it is not one.
     *
     * The single door into [anchorsByBoot], so that a second caller cannot let
     * in what the first refuses: both the live `time_map` and the restored cache
     * come through here.
     *
     * @return true if it was new and was kept.
     */
    private fun admit(anchor: ServerMessage.TimeAnchor): Boolean {
        if (!plausibleUtcOffset(anchor.utcOffsetMinutes)) return false
        val list = anchorsByBoot.getOrPut(anchor.boot) { mutableListOf() }
        if (list.any { it.relMs == anchor.relMs && it.epochMs == anchor.epochMs }) return false
        list.add(anchor)
        list.sortBy { it.relMs }
        return true
    }

    /** Note that a run has been seen in the data, whether or not it can be placed. */
    fun noteBoot(boot: Int) {
        seenBoots[boot] = (seenBoots[boot] ?: 0) + 1
    }

    /** Runs present in the data that still have no equivalence and no manual origin. */
    fun unanchoredBoots(): List<Int> =
        seenBoots.keys.filter { anchorsByBoot[it].isNullOrEmpty() && it !in manualOrigins }

    fun isAnchored(boot: Int): Boolean = !anchorsByBoot[boot].isNullOrEmpty()

    fun manualOrigin(boot: Int): Double? = manualOrigins[boot]

    /**
     * Place a run by hand: [epochSeconds] is when its very first reading happened.
     *
     * The one thing a user can contribute that no measurement can - "I remember
     * when I left the house". It shifts the whole block and leaves its internal
     * timing exactly as recorded, because that part was never in doubt.
     * A run that has a real equivalence ignores this; a measurement beats a
     * recollection, and quietly overriding one with the other would be the same
     * mistake as inventing a timestamp.
     */
    fun placeManually(boot: Int, epochSeconds: Double?) {
        if (epochSeconds == null) manualOrigins.remove(boot) else manualOrigins[boot] = epochSeconds
    }

    /** The earliest relative clock reading seen for [boot], for manual placement. */
    private val firstRelMs = LinkedHashMap<Int, Long>()

    fun noteFirstRelMs(boot: Int, relMs: Long) {
        val held = firstRelMs[boot]
        if (held == null || relMs < held) firstRelMs[boot] = relMs
    }

    /**
     * Where a relative reading belongs.
     *
     * The nearest equivalence in the same run wins. "Nearest" rather than "first"
     * is the whole reason more than one is recorded: the error is then the
     * crystal's drift across the gap to that equivalence instead of across the
     * entire run, which for a multi-day recording is the difference between
     * seconds and minutes.
     */
    fun resolve(boot: Int, relMs: Long): Resolution {
        val anchors = anchorsByBoot[boot]
        if (!anchors.isNullOrEmpty()) {
            var best = anchors[0]
            var bestDistance = Math.abs(relMs - best.relMs)
            for (candidate in anchors) {
                val distance = Math.abs(relMs - candidate.relMs)
                if (distance < bestDistance) {
                    best = candidate
                    bestDistance = distance
                }
            }
            val epochMs = best.epochMs + (relMs - best.relMs)
            return Resolution.Derived(epochMs / 1000.0, best.utcOffsetMinutes, bestDistance)
        }
        val origin = manualOrigins[boot]
        val base = firstRelMs[boot] ?: 0L
        if (origin != null) {
            return Resolution.Manual(origin + (relMs - base) / 1000.0)
        }
        // Elapsed time within the run, and nothing more - which is why it is not
        // called an epoch and why nothing can ask this case for one.
        return Resolution.Unanchored((relMs - base) / 1000.0)
    }

    /**
     * Where a relative reading goes on an axis of dates, or null if nowhere yet.
     *
     * The single conversion from the board's own clock to an instant, so there is
     * one place to be right rather than four. The null is not a failure: it is the
     * answer, and it means the reading is held unplaced until an equivalence or a
     * recollection arrives to place it. A caller that turns it into a number has
     * to write that number down, which is the point.
     */
    fun epochOrNull(boot: Int, relMs: Long): Double? =
        (resolve(boot, relMs) as? Resolution.Placed)?.epochSeconds

    // --- surviving the app closing -------------------------------------------

    /**
     * Everything this knows, flat enough to be written to a file.
     *
     * The equivalences and the user's own placements are the whole reason a
     * relative recording can be re-placed at all, so a cache of the plot that
     * did not carry them would come back holding readings it could no longer
     * date. [firstRelMs] is in here for a subtler reason: it is the origin a
     * manual placement measures from, and it belongs to the *earliest* reading
     * of the run, which is exactly the one a bounded cache drops first.
     * Re-deriving it from what survived would silently slide a hand-placed run
     * later by however much was dropped.
     *
     * `seenBoots` is deliberately absent. It is "which runs are in the data",
     * and after a restore the data is exactly what was restored, so it is
     * rebuilt from the readings themselves rather than remembered.
     */
    data class State(
        val currentBoot: Int,
        val anchors: List<ServerMessage.TimeAnchor>,
        val manualOrigins: Map<Int, Double>,
        val firstRelMs: Map<Int, Long>,
    )

    fun snapshot(): State = State(
        currentBoot = currentBoot,
        anchors = anchorsByBoot.values.flatten(),
        manualOrigins = LinkedHashMap(manualOrigins),
        firstRelMs = LinkedHashMap(firstRelMs),
    )

    /**
     * Put back a [snapshot], folding it in beside anything already known.
     *
     * Folding rather than replacing, because a controller may already have sent
     * a `time_map` by the time the cache is read, and an equivalence measured
     * this session is not worth throwing away for one remembered from the last.
     */
    fun restore(state: State) {
        if (currentBoot == 0) currentBoot = state.currentBoot
        for (anchor in state.anchors) admit(anchor)
        for ((boot, epoch) in state.manualOrigins) manualOrigins.putIfAbsent(boot, epoch)
        for ((boot, relMs) in state.firstRelMs) noteFirstRelMs(boot, relMs)
    }

    /** Reset everything, for a fresh connection to a different controller. */
    fun clear() {
        anchorsByBoot.clear()
        manualOrigins.clear()
        seenBoots.clear()
        firstRelMs.clear()
        currentBoot = 0
    }

    companion object {
        /**
         * The offsets a real zone can have, in minutes: UTC-12:00 to UTC+14:00.
         *
         * Not a style rule but the whole range the world uses, so nothing
         * legitimate is near either end. Baker Island is -720 and the Line
         * Islands are +840, and both are inside.
         */
        val PLAUSIBLE_UTC_OFFSET_MINUTES = -720..840

        /**
         * Whether an equivalence's offset could be a zone at all.
         *
         * A UTC offset is recorded in minutes and formatted in seconds, and
         * crossing those two is silent: a build that passed seconds to the
         * minutes field wrote -14400 where the zone was -240, which is EDT
         * multiplied by sixty and reads as an offset of ten days. It fits the
         * int16 it is stored in, it survives the wire, and every layer below
         * this one believed it.
         *
         * Refusing the whole anchor rather than only its offset is deliberate.
         * The offset is the one field that can be checked against the world, so
         * a wrong one is not a bad field beside good ones: it is the only
         * visible symptom of a writer that crossed its units, and the epoch it
         * was written next to came out of that same code path. Keeping the
         * epoch because it happens to look plausible would be believing the
         * half of a corrupt record that has no way of proving itself.
         *
         * A run left with no good anchor is then simply unplaced, which is what
         * [Resolution.Unanchored] is for and is the answer this class already
         * gives when nothing can date a reading. Anchors an affected build
         * wrote are still on the board's flash; this stops the app believing
         * them, it does not repair them.
         */
        fun plausibleUtcOffset(minutes: Int): Boolean =
            minutes in PLAUSIBLE_UTC_OFFSET_MINUTES
    }
}

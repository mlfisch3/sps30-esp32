package com.sensirion.sps30.data

/**
 * Work that is moving the record's own figures, or the absence of any.
 *
 * ## Why the panel needs this at all
 *
 * On 2026-08-06 the Data tab said "1 session(s) · 193,121 readings" on both
 * sides, "Not here yet: nothing", and "Everything the sensor is holding has
 * reached this device" - directly above a progress row that read "session 1 of 1
 * · 54,222 readings so far" and was visibly climbing past 66,900 and 75,000.
 *
 * Both statements were true. The copy had asked for `file_list`, the answer
 * refreshed the listing, and the inventory recomputed against it in the same
 * instant - so the gap really was nought. What was climbing underneath was the
 * *archive being written*, re-reading readings this device already held in order
 * to put them in a zip. Two honest meters of two different things, on one screen,
 * with nothing saying they were different things.
 *
 * That is what this fixes: the summary sentence is not allowed to be computed
 * from the inventory alone. If anything is running, the sentence says so, so a
 * settled-sounding claim can never sit on top of a moving number.
 *
 * ## Why one value rather than four booleans
 *
 * Because the sentence has to be chosen once, from one thing. Four fields on the
 * UI state is four chances for the summary to be written against three of them,
 * which is the shape of the fault being repaired rather than a fix for it.
 */
sealed interface RecordWork {
    /** Nothing is moving, so a figure may be stated as it stands. */
    data object None : RecordWork

    /** A copy has been asked for; the sensor has not yet said what it holds. */
    data object Asking : RecordWork

    /**
     * Readings are being read off the sensor right now.
     *
     * [readings] is what has gone into the archive file and climbs; [newHere] is
     * how much of that this device did not already have. The two are routinely
     * very different and the difference is the whole explanation of the fault
     * above: a copy of a session the plot already holds reads every row and adds
     * none, so [readings] races while the inventory does not move at all.
     */
    data class Copying(val session: Int, val ofSessions: Int, val readings: Long, val newHere: Long) :
        RecordWork

    /** Archives stored on this device are being read back into the record. */
    data object Loading : RecordWork

    /**
     * Holes are being looked for, or fetched.
     *
     * [sessions] is how many are being asked for; zero while the sweep is still
     * comparing and has not yet asked for anything.
     */
    data class ClosingGaps(val sessions: Int) : RecordWork

    /** Readings are going the other way, onto the sensor. */
    data object Sending : RecordWork

    companion object {
        /**
         * What is running, from the four states that can each move a figure here.
         *
         * Ordered by which one the reader most needs to hear about rather than
         * by which started first. In practice they exclude one another - the gap
         * sweep stands down whenever anything owns the controller's cursor, and
         * both copy buttons are disabled while a transfer is in flight - so the
         * order decides almost nothing; it is fixed anyway, because "almost"
         * is not a property a sentence about someone's data may rest on.
         */
        fun of(
            backup: SessionBackup,
            storedImport: StoredImport,
            transfer: SessionTransfer,
            gaps: RecordGapReport,
        ): RecordWork = when {
            backup is SessionBackup.Receiving -> Copying(
                // The row it replaces printed `received + 1` with no upper
                // guard, so a copy that had finished its last session showed
                // "session 3 of 2" for as long as the archive took to close.
                session = (backup.sessionsDone + 1).coerceAtMost(maxOf(backup.sessionsTotal, 1)),
                ofSessions = maxOf(backup.sessionsTotal, 1),
                readings = backup.rows,
                newHere = backup.newHere,
            )

            backup is SessionBackup.Requested -> Asking

            transfer is SessionTransfer.Checking ||
                transfer is SessionTransfer.Sending ||
                transfer is SessionTransfer.Awaiting -> Sending

            storedImport is StoredImport.Scanning || storedImport is StoredImport.Loading -> Loading

            gaps.sweeping -> ClosingGaps(gaps.fetching)
            gaps.fetching > 0 -> ClosingGaps(gaps.fetching)

            else -> None
        }
    }
}

/**
 * The whole account of the record, as one value, at one instant.
 *
 * ## What it is for
 *
 * There are two questions a worried user is asking on this tab and they are not
 * the same question:
 *
 *  1. **Does this device have everything the sensor is holding?** That is
 *     [inventory] - a comparison of counts against what the controller says it
 *     has right now. It is answerable, and its answer changes minute to minute.
 *  2. **Does this device have everything that was ever recorded?** That is
 *     [gaps] - and it is the one that matters, because the sensor's ring wraps
 *     and readings that fall off it are gone whatever the first answer says.
 *
 * The panel used to answer only the first and word the answer as though it were
 * the second ("Everything the sensor is holding has reached this device"). A
 * device that had lost three hours to a wrap could print that sentence with
 * every figure correct. So both are carried here and both are said out loud,
 * separately, in words that cannot be mistaken for one another.
 *
 * ## And the third thing, which is not a question
 *
 * [work] is what is happening *now*. It is here because either answer stated
 * while something is running is a claim with a shelf life, and the whole defect
 * this repairs was a settled sentence sitting on top of a climbing number. Any
 * sentence built from this value has to look at [work] before it says anything
 * is finished.
 */
data class RecordStanding(
    val inventory: DataInventory,
    val gaps: RecordGapReport,
    val work: RecordWork,
) {
    /** True when something is moving, so no figure here may be called settled. */
    val moving: Boolean get() = work != RecordWork.None

    /**
     * Everything the sensor holds is here - and nothing is in flight that could
     * make that untrue between reading it and believing it.
     *
     * Deliberately stricter than [DataInventory.everythingIsHere], which is the
     * arithmetic alone. This is the predicate a *sentence* may rest on.
     */
    val atParityAndSettled: Boolean get() = inventory.everythingIsHere && !moving

    /**
     * Readings that were recorded, never reached here, and no longer exist.
     *
     * Null when nothing has established it either way. Deliberately not folded
     * into [DataInventory.gap]: that row counts what the sensor still holds and
     * can still be asked for, and adding data the sensor has destroyed to a
     * figure whose remedy is a button would make the button a lie. These two are
     * different in kind, not in degree - one is waiting, the other is gone.
     */
    val readingsGoneForGood: Int?
        get() = when {
            gaps.sweptAtMs == 0L && gaps.gaps.isEmpty() -> null
            else -> gaps.readingsLost
        }

    /** Holes whose standing nothing has settled, so "gone" may not be said of them. */
    val holesNotJudged: Int get() = gaps.undecided

    /**
     * True only when the loss question has actually been answered, and "no".
     *
     * The same rule [RecordCount] enforces one row up and [GapStanding] enforces
     * one row down: an empty list of holes before anything has looked is not the
     * absence of holes, and rendering the two the same way is how three hours of
     * the captain's record went missing without a word on screen.
     */
    val nothingLost: Boolean get() = gaps.sweptAtMs > 0L && gaps.lost == 0 && gaps.undecided == 0
}

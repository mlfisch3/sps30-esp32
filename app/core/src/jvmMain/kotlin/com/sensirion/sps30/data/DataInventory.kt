package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage

/**
 * One side of the record, or the reason there is no number to give.
 *
 * The whole point of the type is that there is no third option. A count is
 * either something the sensor has actually said, or it is a sentence explaining
 * why nobody knows - and a zero is never allowed to stand in for the second.
 * That is the same rule `WatchNode.hasApp` is a `Boolean?` for, and it is here
 * for the same reason: every figure on this screen describes a device this phone
 * cannot see for itself, so "I have not asked" and "there is none" are different
 * answers that look identical once they are both rendered as `0`.
 */
sealed interface RecordCount {
    /** A figure the sensor has stated, or arithmetic over figures it stated. */
    data class Counted(val sessions: Int, val readings: Long) : RecordCount {
        val isNothing: Boolean get() = sessions == 0 && readings == 0L
    }

    /** [because] is shown in place of the number, and reads as a sentence. */
    data class Unknown(val because: String) : RecordCount
}

/**
 * What the sensor holds, what this device holds of it, and what is missing.
 *
 * ## Why a comparison rather than a total
 *
 * The Data tab used to report the result of a copy - "2 sessions, 148,893
 * readings, the sensor still has all of it" - which is a true and useful
 * sentence about one operation that has already finished, and no help at all
 * with the question actually being asked, which is *is my data here yet*.
 * Answering that needs both sides on screen at once and the difference between
 * them stated, so that the thing to do next is visible instead of inferred.
 *
 * The three figures are built to **add up**: [here] and [gap] sum to [sensor] on
 * both columns. That is not decoration; a comparison whose parts do not
 * reconcile is one the reader has to check, and a figure that has to be checked
 * is one that gets ignored.
 *
 * ## Which direction it serves
 *
 * Getting readings **off** the sensor and onto this device. That is the
 * direction that can lose data - the controller is a ring and it wraps - and it
 * is the one the captain named as mattering most. Sending data back is the other
 * way round by definition: the device doing the sending already has it. So this
 * has one action, and it is the copy.
 *
 * ## What it deliberately does not do
 *
 * It does not read the archives in `Downloads/SPS30`. Those are accounted for by
 * the stored-sessions overview further down the same tab, which reads them for
 * real; duplicating that here would be a second count of the same files, taken a
 * different way, free to disagree. What this uses instead is
 * [TimeSeriesHistory.receivedRows] - readings that have arrived from a named
 * session, which retention does not take back and `PlotCache` restores across a
 * restart - so the two views answer different questions and neither is a worse
 * copy of the other.
 */
data class DataInventory(
    /** Everything the sensor is holding, by its own account. */
    val sensor: RecordCount,
    /** How much of [sensor] has reached this device. */
    val here: RecordCount,
    /** How much of [sensor] has not. The copy is what closes it. */
    val gap: RecordCount,
    /**
     * True when [sensor] describes the sensor now rather than the last time this
     * phone heard from it.
     *
     * Nothing clears `storage` on a disconnect - the last known figures are
     * worth keeping - so it is the sentence, not the field, that has to change
     * tense. The sensor keeps recording while this phone is not listening, so a
     * count from an hour ago stated in the present tense is a number that is no
     * longer true.
     */
    val live: Boolean,
    /**
     * Sessions in the listing the sensor recorded with no clock.
     *
     * These are copied, plotted and exported like any other, and they are in the
     * totals above. What they are missing is a date, which is why they have to
     * be called out: the stored-sessions overview below reads its files through
     * [SessionCsv], which needs a parseable `timestamp` cell to bucket a reading
     * on, and a clockless row's is deliberately empty. So every one of them
     * reads back as a session holding no readings, and an inventory that said
     * nothing would be inviting the reader to reconcile two counts that cannot
     * be reconciled.
     */
    val clocklessSessions: Int,
    /**
     * Sessions the sensor says it holds that its listing has not named here.
     *
     * `file_list` is bounded by one frame and the automatic pass reads a single
     * page, so on a board holding more sessions than a page can name the
     * per-session comparison is short by the remainder. They are counted as
     * missing, which is the safe direction to be wrong in, and said out loud
     * rather than folded silently into the gap.
     */
    val unnamedSessions: Int,
) {
    /** Nothing is outstanding, and that is known rather than assumed. */
    val everythingIsHere: Boolean
        get() = gap is RecordCount.Counted && gap.isNothing && sensor is RecordCount.Counted

    /** There is a gap, so the copy has something to do. */
    val worthCopying: Boolean
        get() = gap is RecordCount.Counted && !gap.isNothing

    companion object {
        /** Before anything has been heard, from anywhere. */
        val UNKNOWN = of(connected = false, storage = null, listing = emptyList()) { 0 }

        /**
         * Work out the comparison from what the controller has said.
         *
         * [storage] is the sensor's own totals, which it sends unasked and which
         * are complete however long its listing is - so the sensor side is taken
         * from there and never from the sum of a page of entries.
         *
         * [listing] is what `file_list` named, accumulated across the pages that
         * have arrived. It is the only per-session information there is, so it is
         * what the device side is worked out from, and its incompleteness is
         * reported through [unnamedSessions] rather than hidden.
         *
         * [receivedRows] answers how many readings of one named session have ever
         * arrived here. It is a count and so is `file_list`'s `rows`, which is
         * what makes the subtraction fair - the comparison a high-water index
         * would get wrong for any session with a hole in it.
         */
        fun of(
            connected: Boolean,
            storage: ServerMessage.Storage?,
            listing: List<ServerMessage.FileEntry>,
            receivedRows: (String) -> Int,
        ): DataInventory {
            val unknown = sensorUnknown(connected, storage)
            if (unknown != null) {
                // With no sensor figure there is nothing to compare against, and
                // saying "0 readings are missing" would be the exact failure this
                // is being built to stop: claiming it is not there when the truth
                // is that nobody has looked.
                //
                // The two consequences are worded apart on purpose. They fall out
                // of the same missing fact, but a reader scanning three rows that
                // all say the same words reads one sentence repeated and stops
                // taking in which row it is against.
                return DataInventory(
                    sensor = unknown,
                    here = RecordCount.Unknown(
                        "not known until the sensor says what it has",
                    ),
                    gap = RecordCount.Unknown(
                        "nothing can be called missing before then",
                    ),
                    live = connected,
                    clocklessSessions = 0,
                    unnamedSessions = 0,
                )
            }
            // Not null: sensorUnknown answers for every case where it is.
            val store = storage!!
            val sensorSessions = store.sessions.coerceAtLeast(0)
            val sensorReadings = store.readings.toLong().coerceAtLeast(0L)

            var hereReadings = 0L
            var completeSessions = 0
            var clockless = 0
            for (entry in listing) {
                val rows = entry.rows.coerceAtLeast(0)
                val received = receivedRows(entry.name).coerceAtLeast(0)
                // Capped at what the sensor holds of that session: this device
                // may have received rows the ring has since dropped, and letting
                // those count would shrink a gap that is still there.
                hereReadings += minOf(received.toLong(), rows.toLong())
                if (rows > 0 && received >= rows) completeSessions++
                if (entry.relative) clockless++
            }
            // The sensor's own totals win over the sum of a listing that may be a
            // page short, and they are also a moving target - the live session
            // grows between the listing and the storage figure - so neither side
            // of the subtraction is allowed to run past the other.
            val held = minOf(hereReadings, sensorReadings)
            val heldSessions = minOf(completeSessions, sensorSessions)
            return DataInventory(
                sensor = RecordCount.Counted(sensorSessions, sensorReadings),
                here = RecordCount.Counted(heldSessions, held),
                gap = RecordCount.Counted(sensorSessions - heldSessions, sensorReadings - held),
                live = connected,
                clocklessSessions = clockless,
                unnamedSessions = (sensorSessions - listing.size).coerceAtLeast(0),
            )
        }

        /**
         * Why there is no sensor figure, or null when there is one.
         *
         * `ready` is part of it and not a detail: a controller that is still
         * bringing its store up answers `storage` with zeroes, and those zeroes
         * are the most convincing wrong number on this screen.
         */
        private fun sensorUnknown(
            connected: Boolean,
            storage: ServerMessage.Storage?,
        ): RecordCount.Unknown? = when {
            storage == null && connected ->
                RecordCount.Unknown("connected - waiting for the sensor to say")
            storage == null ->
                RecordCount.Unknown("no sensor has been reached yet, so nothing has been asked")
            !storage.ready ->
                RecordCount.Unknown("the sensor is still bringing its storage up")
            else -> null
        }
    }
}

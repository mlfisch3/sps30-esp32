package com.sensirion.sps30.data

/**
 * Progress of putting readings back into the record from a file.
 *
 * [Refused] is deliberately separate from [Failed], for the same reason
 * [SessionTransfer] separates them: a refused file was read, found wanting, and
 * **never merged**. Nothing in the record was touched, and saying so is the
 * difference between a user renaming a file and a user wondering what their plot
 * now contains.
 *
 * There is no "replacing" state because there is no replacing. The merge can
 * only add ([TimeSeriesHistory.addRows] is keyed on `(session, index)` and skips
 * what it already has), so the outcomes are *added some*, *added none because it
 * was all here already*, and *changed nothing and said why*.
 */
sealed interface RecordRestore {
    data object Idle : RecordRestore

    /**
     * Reading the file through.
     *
     * [readings] counts what has been offered to the record so far rather than a
     * fraction, because nothing knows the total until the file has been read:
     * the check that runs first counts sessions, not rows, and a count that
     * climbs is honest where a bar that guesses is not.
     */
    data class Reading(
        val file: String,
        val session: String,
        val readings: Int,
    ) : RecordRestore

    data class Done(val file: String, val report: SessionRestore.Report) : RecordRestore

    /** Read, found wanting, and not merged. The record is exactly as it was. */
    data class Refused(val file: String, val reason: String) : RecordRestore

    /** Something went wrong while reading. Whatever had already merged stayed. */
    data class Failed(val file: String, val reason: String) : RecordRestore
}

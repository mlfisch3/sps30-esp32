package com.sensirion.sps30.ui

/**
 * When the app copies the controller's store here without being asked.
 *
 * `HANDOFF.md` item 13: past the controller's own warn line - 85% of the ring,
 * `wire.STORE_WARN_FRACTION`, and the controller is the one that decides it has
 * been crossed - everything goes to the connected device, so the data leaves the
 * controller before there is ever pressure to delete it. Nothing here erases
 * anything - the
 * copy is the whole response, and the ring's own oldest-first reuse is what it is
 * getting ahead of.
 *
 * The rule is kept apart from [MonitorSession] so it can be stated, and tested,
 * without a Context, a radio or a board - the same reason `Backfill.kt` is.
 *
 * ## What was wrong with it
 *
 * It was meant to fire once per crossing and guarded with
 * `if (autoExportedAt == storage.usedPages) return`. `usedPages` climbs
 * continuously on a board that is recording, so the guard never held and the
 * condition it tested for never occurred. Measured on 2026-08-04 with the board
 * at 92%:
 *
 * ```
 * 05:18:47 store is 85% full; copying everything here
 * 05:23:04 store is 85% full; copying everything here
 * 05:38:10 store is 87% full; copying everything here
 * ```
 *
 * Each is a seven-minute, 1.7 MB copy writing a fresh archive into
 * `Downloads/SPS30`, and *Copy sessions* and *Send sessions back* are disabled
 * while one runs. Back to back for as long as the board stays above the line,
 * which on the captain's board is permanently, so the Data controls were
 * effectively never available.
 *
 * ## What replaced it
 *
 * A guard keyed on the **crossing** - a thing that happens - rather than on a
 * number that always moves. [AutoExportState.aboveLine] remembers which side of
 * the line the controller was on last time it said, and a copy is due when it
 * arrives on the far side of it.
 *
 * That alone is not enough, because a store that stays above the line for ever is
 * now the ordinary case rather than the exception, and a board that crossed once
 * a week ago and has been recording since has data that the crossing did not
 * cover. So there is a second, much slower trigger: once enough **new readings**
 * have been recorded since the last copy to be worth another. Not a timer, and
 * not a fraction of the ring - readings are what is at risk and readings are what
 * is counted.
 */

/**
 * How many new readings on the controller are worth another automatic copy.
 *
 * The thing being got ahead of is the ring wrapping. Above the warn line about
 * 15% of 4,480 pages are left, and a page is about 45 readings measured at rest,
 * so roughly 30,000 readings can arrive before the oldest are overwritten - and
 * fewer than that on a board already at 92%, which is where the demo board is.
 *
 * Ten thousand is comfortably inside that on any board past the line, and on a
 * controller recording once a second it is a copy every three hours rather than
 * the four an hour that was observed. It is deliberately a count of readings and
 * not an interval: a duty-cycled board recording once a minute is not accruing
 * risk at the rate a continuous one is, and should not be copied at the same
 * cadence for the same reason.
 */
const val AUTO_EXPORT_MIN_NEW_READINGS = 10_000

/**
 * The least time between two automatic copies, whatever the readings say.
 *
 * A floor rather than the rule, and it exists for the controllers this app does
 * not yet run against: a faster sensor, a larger store, an import that adds tens
 * of thousands of readings in one go. A copy takes minutes and holds the transfer
 * controls disabled while it runs, so two of them inside an hour is never right
 * however much arrived.
 */
const val AUTO_EXPORT_MIN_INTERVAL_MS = 60L * 60L * 1000L

/**
 * What the app remembers between two `storage` messages.
 *
 * [aboveLine] is the edge the guard is keyed on. [copiedAtReadings] and
 * [copiedAtMs] describe the last copy this rule started - null means none has,
 * which makes the first sighting of a full store a crossing in its own right.
 */
data class AutoExportState(
    val aboveLine: Boolean = false,
    val copiedAtReadings: Int? = null,
    val copiedAtMs: Long = 0L,
)

/** What one `storage` message means: the state to keep, and whether to copy now. */
data class AutoExportStep(
    val state: AutoExportState,
    val startCopy: Boolean,
)

/**
 * Fold one `storage` message into the rule.
 *
 * [copyInFlight] is a copy already running - the user's, or one this rule started
 * that has not finished. A due copy is *recorded* as taken while one is in flight
 * rather than deferred: the copy that is running collects everything the
 * controller holds, which is exactly what this one would have asked for, and
 * queuing a second identical copy behind it is the back-to-back behaviour this
 * whole rule exists to stop.
 */
fun autoExportStep(
    state: AutoExportState,
    warn: Boolean,
    readings: Int,
    copyInFlight: Boolean,
    nowMs: Long,
): AutoExportStep {
    // Below the line, or nothing recorded to copy. Below the line re-arms the
    // crossing, which is the only thing that does.
    if (!warn || readings <= 0) {
        return AutoExportStep(state.copy(aboveLine = warn), startCopy = false)
    }

    val due = when {
        // Never copied. The first full store this app sees is a crossing: it may
        // be one that happened while the app was closed, and the response to it
        // is the same either way.
        state.copiedAtReadings == null -> true
        // The crossing itself.
        !state.aboveLine -> true
        // Still above it, which is now the ordinary case. Enough new readings to
        // be worth another copy, and not sooner than the floor whatever arrived.
        else ->
            readings - state.copiedAtReadings >= AUTO_EXPORT_MIN_NEW_READINGS &&
                nowMs - state.copiedAtMs >= AUTO_EXPORT_MIN_INTERVAL_MS
    }

    if (!due) {
        return AutoExportStep(state.copy(aboveLine = true), startCopy = false)
    }
    return AutoExportStep(
        AutoExportState(aboveLine = true, copiedAtReadings = readings, copiedAtMs = nowMs),
        startCopy = !copyInFlight,
    )
}

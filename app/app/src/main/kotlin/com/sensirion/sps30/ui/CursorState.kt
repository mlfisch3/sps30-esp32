package com.sensirion.sps30.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Everything the crosshair is doing, held as one value that survives rotation.
 *
 * ## Why one holder rather than four `remember`s
 *
 * These four used to be separate `remember`/`rememberSaveable` declarations in
 * the dashboard, and the split is exactly how the captain lost the crosshair:
 * the pin was saved across a configuration change, the free cursor's position
 * was not, so rotating the phone kept an engaged pin but silently discarded a
 * crosshair that had merely been *placed* - which is the state the crosshair
 * is used in most. A crosshair the user set up is work, pinned or not, and
 * "the screen turned" is not an instruction to throw work away. Holding the
 * whole family in one saveable value means no part of it can be individually
 * forgotten by whoever adds the next piece.
 *
 * ## What restoration means for each part
 *
 *  - [cursor]: the free crosshair's anchor, back at the same data position -
 *    an [ChartCursor.AtTime] holds its instant, an [ChartCursor.AtPlace] its
 *    fraction of a view that itself survives (the chart view lives in the
 *    process-lifetime session), and [ChartCursor.AtLatest] is wherever the
 *    newest sample now is, which is what following means.
 *  - [enabled] and [follows]: modes you work in, not preferences - deliberately
 *    not in the saved config, but a rotation must not flip them either.
 *  - [pin]: the [CursorPin] state machine, anchor and all, through its own
 *    [CursorPinSaver]. "Once pinned, the cursor stays pinned" includes the
 *    screen turning.
 */
@Stable
class CursorState(
    cursor: ChartCursor? = null,
    enabled: Boolean = true,
    follows: Boolean = false,
    pin: CursorPin = CursorPin.FreeCursor,
) {
    /** The free crosshair's anchor, or null while none is placed. */
    var cursor by mutableStateOf(cursor)

    /** Whether touching the plot summons a crosshair at all. */
    var enabled by mutableStateOf(enabled)

    /** Follow: the crosshair rides the newest sample as a live readout. */
    var follows by mutableStateOf(follows)

    /** The pin state machine: free, or one of the three pins. */
    var pin by mutableStateOf(pin)

    /**
     * What snapping the view to the crosshair at [epoch] leaves the cursor
     * holding. The view has just been moved so that instant is an edge of it,
     * and the crosshair must stay on that edge:
     *
     *  - a pinned cursor stays pinned, as [CursorPin.ToTime] on that instant.
     *    An edge pin's count is measured from an edge the snap just moved, so
     *    keeping the count would land the crosshair anywhere but the point the
     *    user snapped on; the moment is the thing the snap was about, and
     *    holding a moment is the pin Snap to peak engages too.
     *  - a free cursor is re-anchored to the instant, exactly as a navigation
     *    gesture re-anchors it: the snapped view is a held range, where an
     *    instant and a place are the same thing.
     *  - a following cursor is left following - it holds the newest sample by
     *    definition, and the snap has already put the view where it was asked.
     */
    fun onViewSnapped(epoch: Double) {
        when {
            pin !is CursorPin.FreeCursor -> pin = CursorPin.ToTime(epoch)
            cursor is ChartCursor.AtLatest -> {}
            else -> cursor = ChartCursor.AtTime(epoch)
        }
    }

    companion object {
        /**
         * Saves the whole family across rotation. The [ChartCursor] is encoded
         * here; the pin delegates to [CursorPinSaver], which remains the one
         * statement of the pin machine's wire form.
         */
        val Saver: Saver<CursorState, Any> = listSaver(
            save = { state ->
                val (kind, value) = when (val c = state.cursor) {
                    null -> "none" to 0.0
                    is ChartCursor.AtTime -> "time" to c.epoch
                    is ChartCursor.AtPlace -> "place" to c.fraction
                    ChartCursor.AtLatest -> "latest" to 0.0
                }
                val pin = with(CursorPinSaver) { save(state.pin) }
                listOf(kind, value, state.enabled, state.follows, pin)
            },
            restore = { saved ->
                val value = saved.getOrNull(1) as? Double ?: 0.0
                CursorState(
                    cursor = when (saved.getOrNull(0) as? String) {
                        "time" -> ChartCursor.AtTime(value)
                        "place" -> ChartCursor.AtPlace(value)
                        "latest" -> ChartCursor.AtLatest
                        else -> null
                    },
                    enabled = saved.getOrNull(2) as? Boolean ?: true,
                    follows = saved.getOrNull(3) as? Boolean ?: false,
                    pin = saved.getOrNull(4)?.let { CursorPinSaver.restore(it) }
                        ?: CursorPin.FreeCursor,
                )
            },
        )
    }
}

/** The dashboard's cursor state, restored across configuration changes. */
@Composable
fun rememberCursorState(): CursorState =
    rememberSaveable(saver = CursorState.Saver) { CursorState() }

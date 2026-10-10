package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the crosshair toggle shows: U+2316, the position-indicator crosshair.
 *
 * A named constant because the layout tests find the chip by its face, the way
 * they find worded chips by their word.
 */
internal const val CROSSHAIR_GLYPH = "⌖"

/**
 * The controls that decide what the crosshair does, passed as one value.
 *
 * They belong together: whether there is a crosshair at all, whether it rides
 * the incoming data, and which pin - if any - is holding it. The pin is the
 * state machine in [CursorPin]: the free cursor, or pinned to its moment, or
 * pinned an offset in from either edge of the graph. The chips below are its
 * controls, and the corner button on the plot itself (see `CursorPinToggle` in
 * `PmChart.kt`) is a second handle on the pin-to-time state, because that is
 * the pin reached for with a hand already on the glass.
 */
data class CursorControls(
    val enabled: Boolean,
    val onToggleEnabled: () -> Unit,
    val follows: Boolean,
    val onToggleFollows: () -> Unit,
    /** Which pin the cursor is in; [CursorPin.FreeCursor] when none. */
    val pin: CursorPin = CursorPin.FreeCursor,
    /** True while a crosshair is showing, so there is a point to pin. */
    val pinnable: Boolean = false,
    /** True while the plot has a drawn point for Peak to land on. */
    val peakAvailable: Boolean = false,
    val onPinToTime: () -> Unit = {},
    val onPinFromRight: () -> Unit = {},
    val onPinFromLeft: () -> Unit = {},
    val onSnapToPeak: () -> Unit = {},
    val onSnapViewStart: () -> Unit = {},
    val onSnapViewEnd: () -> Unit = {},
) {
    /** Whether touch moves the crosshair - see [cursorMovableByTouch]. */
    val movableByTouch: Boolean get() = cursorMovableByTouch(enabled, pin)
}

// The crosshair toggle and Follow are not chips any more: they live on the
// plot itself as icons - see `CrosshairToggleIcon` and `FollowIcon` in
// PlotChrome.kt - at the captain's own instruction, each at the thing it
// affects. Their spoken descriptions moved with them, word for word.

/**
 * The pin family: one chip per pin type, and the three snap actions.
 *
 * The three pin chips behave as a radio group with an off state: at most one is
 * selected, selecting another switches the pin without moving the crosshair,
 * and unselecting the engaged one frees the cursor where it stands. They are
 * greyed rather than hidden while there is no crosshair to pin - a control
 * that comes and goes teaches the user to hunt for it. Peak (the ⛰ chip) is an
 * action, not a state: it jumps the crosshair to the highest visible point and
 * engages Pin time there, so it never draws as selected itself.
 *
 * The pin chips keep their words where Peak got an icon: "Pin time", "From
 * right" and "From left" are three variants of one idea, and three pin glyphs
 * differing only in a small arrow would need a legend to tell apart - which is
 * the test an icon has to pass here.
 *
 * "Start here" and "End here" are Peak's siblings in the other direction:
 * where Peak moves the crosshair to a feature of the view, these move the view
 * to the crosshair - it becomes the left or right edge of the visible window,
 * with the window's duration kept (a pan, never a zoom). Actions like Peak, so
 * they never draw as selected; unlike Peak they need a crosshair already
 * placed, so they grey with the pin chips.
 *
 * Emitted as siblings like [CursorChips], but placed differently by the two
 * hosts. The roomy [CursorSection] keeps the whole cursor family together. The
 * compact panel puts these **below** the window and averaging groups instead:
 * four more chips above them pushed the window buttons - the most-pressed
 * controls on the panel - under the fold on a phone in landscape, and a control
 * column must spend its scarce height on what the hand reaches for most. The
 * pin-to-time state also has the plot's own corner button, so the everyday pin
 * never needs this row at all.
 */
@Composable
internal fun CursorPinChips(cursor: CursorControls) {
    // Engaged pins stay operable even if the crosshair's point is momentarily
    // not drawable (a window zoomed into a gap): the way out of a pin must
    // never be greyed out by the very view the pin produced.
    val pinChipsLive = cursor.pinnable || cursor.pin !is CursorPin.FreeCursor
    FilterChip(
        selected = cursor.pin is CursorPin.ToTime,
        enabled = pinChipsLive,
        onClick = cursor.onPinToTime,
        label = { Text("Pin time") },
        modifier = Modifier.semantics {
            contentDescription = when {
                cursor.pin is CursorPin.ToTime ->
                    "Crosshair pinned to its moment, travelling with its data point. " +
                        "Tap to unpin it."
                pinChipsLive ->
                    "Tap to pin the crosshair to the data point it is on, so it stays " +
                        "on that moment whatever the view does."
                else -> "Place the crosshair on the plot first, then pin it."
            }
        },
    )
    FilterChip(
        selected = cursor.pin is CursorPin.FromRight,
        enabled = pinChipsLive,
        onClick = cursor.onPinFromRight,
        label = { Text("From right") },
        modifier = Modifier.semantics {
            contentDescription = when {
                cursor.pin is CursorPin.FromRight ->
                    "Crosshair pinned a fixed number of points in from the right edge " +
                        "of the graph. Tap to unpin it."
                pinChipsLive ->
                    "Tap to pin the crosshair by its distance from the right edge, so " +
                        "it rides new data instead of one moment."
                else -> "Place the crosshair on the plot first, then pin it."
            }
        },
    )
    FilterChip(
        selected = cursor.pin is CursorPin.FromLeft,
        enabled = pinChipsLive,
        onClick = cursor.onPinFromLeft,
        label = { Text("From left") },
        modifier = Modifier.semantics {
            contentDescription = when {
                cursor.pin is CursorPin.FromLeft ->
                    "Crosshair pinned a fixed number of points in from the left edge " +
                        "of the graph. Tap to unpin it."
                pinChipsLive ->
                    "Tap to pin the crosshair by its distance from the left edge."
                else -> "Place the crosshair on the plot first, then pin it."
            }
        },
    )
    // A mountain for "Peak": the word's own picture, pointing at the same idea -
    // the highest point in view. Like the crosshair glyph it teaches itself on
    // the first press, when the cursor visibly jumps to the summit of the trace.
    // The word leads the spoken description, so nothing is lost to a reader.
    FilterChip(
        selected = false,
        enabled = cursor.peakAvailable,
        onClick = cursor.onSnapToPeak,
        label = { Text("⛰") },
        modifier = Modifier.semantics {
            contentDescription = if (cursor.peakAvailable) {
                "Peak: jump the crosshair to the highest point in view and pin it " +
                    "to that moment."
            } else {
                "Peak: nothing is drawn yet for the peak to land on."
            }
        },
    )
    FilterChip(
        selected = false,
        enabled = cursor.pinnable,
        onClick = cursor.onSnapViewStart,
        label = { Text("Start here") },
        modifier = Modifier.semantics {
            contentDescription = if (cursor.pinnable) {
                "Start the view at the crosshair: its moment becomes the left edge " +
                    "of the window, keeping the window's duration."
            } else {
                "Place the crosshair on the plot first, then start the view there."
            }
        },
    )
    FilterChip(
        selected = false,
        enabled = cursor.pinnable,
        onClick = cursor.onSnapViewEnd,
        label = { Text("End here") },
        modifier = Modifier.semantics {
            contentDescription = if (cursor.pinnable) {
                "End the view at the crosshair: its moment becomes the right edge " +
                    "of the window, keeping the window's duration."
            } else {
                "Place the crosshair on the plot first, then end the view there."
            }
        },
    )
}

/**
 * The sentence under the chips, which says what the engaged mode is doing.
 *
 * A function of the state rather than a composable, so both placements print the
 * same words through the same [ControlNote] and neither can end up with a stale
 * copy of the other's wording. The pinned sentences all end the same way -
 * touch pans - because that is the surprise worth spending words on: a pinned
 * crosshair does not answer the finger at all.
 */
internal fun cursorNote(follows: Boolean, pin: CursorPin = CursorPin.FreeCursor): String =
    when {
        pin is CursorPin.ToTime ->
            "Pinned to its moment: the crosshair travels with its data point, " +
                "wherever the view goes. Touching the plot pans instead."
        pin is CursorPin.FromRight ->
            "Pinned in from the right edge: the crosshair keeps its distance from " +
                "the newest data as readings arrive. Touching the plot pans instead."
        pin is CursorPin.FromLeft ->
            "Pinned in from the left edge: with the left edge on a mark it stays " +
                "with its data point as new readings squeeze in. Touching the plot " +
                "pans instead."
        follows ->
            "The crosshair rides the newest reading; its readout is live, and " +
                "stamped with the time of day. Touching the plot takes it back."
        else ->
            "Follow keeps the crosshair on the newest reading instead of where " +
                "you last put it."
    }

/**
 * The Cursor group as a headed section of its own.
 *
 * This is the roomy shape, used where the control column beside the chart has
 * height going spare - the tablet in landscape, which is the way it is usually
 * held. Where it does not, [PanelFit] chooses the compact shape instead and the
 * same chips appear in the front panel's chip cluster, with [cursorNote] under
 * it. Both shapes are built from [CursorPinChips], so the group cannot say two
 * different things in two places. The crosshair toggle and Follow live on the
 * plot itself in both shapes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CursorSection(cursor: CursorControls, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
        Text(
            "Cursor",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
        ControlChips(modifier = Modifier.padding(top = 4.dp)) {
            CursorPinChips(cursor)
        }
        ControlNote(
            cursorNote(cursor.follows, cursor.pin),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

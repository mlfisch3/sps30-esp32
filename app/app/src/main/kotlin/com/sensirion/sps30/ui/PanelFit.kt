package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/** [PanelFit]'s two slots: the copy it asks the question of, and the one it shows. */
private enum class PanelSlot { Probe, Shown }

/**
 * Lays out a panel in the shape that fits the height it has been given.
 *
 * ## The fault this exists for
 *
 * The landscape control column is a `Column` in a `Row` that has already taken the
 * screen's height, so its children are measured against what is *left* rather than
 * what they need. A `Column` hands each child the remaining height and the last one
 * gets whatever that has become - which on a phone in landscape was about eight dp
 * for the Cursor group's explanation, so the user saw the top sliver of a sentence
 * and nothing else. On the tablet in landscape the same column has two and a half
 * times the height and everything fits, which is why one device looked correct and
 * the other did not.
 *
 * Squeezing the last child is the failure mode of *bounded* height. So the shape
 * that fits is measured against no bound at all - `Constraints.Infinity` - and the
 * answer is the height the panel actually wants, not the height it was squashed
 * into. If that fits, it is placed as measured and nothing is squeezed. If it does
 * not, the compact shape is composed instead, and it is that shape's job to need
 * less: on this screen it moves the Cursor group up into the chip cluster at the
 * top, which the captain asked for and which costs one wrapped line instead of a
 * headed section.
 *
 * ## Why a threshold is not used
 *
 * Any "phones are compact, tablets are roomy" rule is a guess about a device made
 * at a moment when the real number - how much height this panel has, right now,
 * in this orientation - is already in hand. The guess would also be wrong the first
 * time a chip is added, because it does not know what the panel now contains. This
 * measures both sides of the question instead, so it stays right as the panel
 * grows and on hardware nobody here has seen.
 *
 * ## Why it owns the column and the scrollbar
 *
 * Reflow is the first answer to a panel that has run out of room and scrolling is
 * the second, but there must be a second: a compact shape that still does not fit -
 * one chip too many from now - has to be reachable rather than cut. So the compact
 * branch, and only it, puts the content in a scrolling column. The roomy branch is
 * by definition the case where everything fits, and a scrollbar there would be a
 * control that never moves.
 *
 * That also has to live here rather than in the caller: a scrolling column refuses
 * to be measured against an infinite height, which is exactly the measurement the
 * fit question is asked with.
 *
 * ## The probe, and why its semantics are cleared
 *
 * The question is asked of a second copy of the roomy shape, composed purely to be
 * measured and never placed. That costs a composition of a dozen chips and a few
 * lines of text, which buys a decision that lands in the same frame as the
 * measurement - the alternative, deciding from what the *previous* frame measured,
 * shows one frame of the wrong layout every time the screen rotates.
 *
 * Not being placed is not enough to make the probe harmless. An unplaced node is
 * still in the semantics tree, and a screen reader offering "Crosshair" twice, one
 * of them pointing at nothing, is its own fault. So the probe is wrapped in
 * `clearAndSetSemantics {}`: it keeps its geometry, which is all that is wanted
 * from it, and gives up its identity, which is all that could do harm.
 * `ControlLayoutTest` counts the chips on the screen and requires exactly one of
 * each, which is what holds this in place.
 */
@Composable
internal fun PanelFit(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(compact: Boolean) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val wanted = subcompose(PanelSlot.Probe) {
            Column(modifier = Modifier.clearAndSetSemantics {}) { content(false) }
        }.maxOfOrNull { it.measure(unbounded).height } ?: 0
        val compact = wanted > constraints.maxHeight
        val placeables = subcompose(PanelSlot.Shown) {
            if (compact) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { content(true) }
            } else {
                Column { content(false) }
            }
        }.map { it.measure(if (compact) constraints else unbounded) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

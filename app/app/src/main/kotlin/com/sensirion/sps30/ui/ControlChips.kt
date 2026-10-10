package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A cluster of control chips that wraps onto a second line rather than running
 * off the edge.
 *
 * ## Why this is not a `Row`
 *
 * The front panel's chip cluster is the part of this app that grows. Pin and Auto
 * were joined by the window presets, then by the y-axis pair, and two more are
 * being added while this is being written. A `Row` holds however many chips its
 * width happens to fit and silently squeezes or clips the rest, so every addition
 * to it is a fresh chance to push something off the screen - and the narrowest
 * place these appear is the 300 dp landscape control column, where two chips and a
 * scrolling strip is already most of the width.
 *
 * A flow row has no such arithmetic in it. Chips take the width they need, the
 * line breaks where it runs out, and the cluster grows downwards. That is what
 * makes "add a chip here" a safe edit for the next person: nothing has to be
 * counted, and no width has to be re-checked on four screens.
 *
 * The last item may carry `Modifier.weight(1f)` - the scrolling preset strips do -
 * and it then takes the rest of its own line, exactly as it did inside a `Row`.
 *
 * The spacing matches the rows this replaced: 6 dp between chips, and 4 dp between
 * lines so a wrapped cluster reads as one group rather than two rows of controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlChips(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

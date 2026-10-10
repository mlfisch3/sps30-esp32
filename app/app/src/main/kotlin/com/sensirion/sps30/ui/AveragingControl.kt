package com.sensirion.sps30.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** The word on the button. Its identity, not a prediction of what tapping does. */
internal const val AVERAGE_LABEL = "Average"

/** The tag the layout tests find the chooser's surface by, to measure it. */
internal const val AVERAGING_CHOOSER_TAG = "averaging-chooser"

/**
 * The tag on the host box the chooser may not leave. Its bounds are the
 * chooser's whole permitted world - the geometry tests measure containment in
 * it, because everything outside it is either the plot or off screen.
 */
internal const val AVERAGING_HOST_TAG = "averaging-chooser-host"

/**
 * How much time each plotted point averages over, as a chip label: whole
 * seconds up to two minutes, minutes up to two hours, hours beyond.
 */
internal fun formatAveraging(seconds: Double): String = when {
    seconds < 120 -> "${seconds.toInt()}s"
    seconds < 7200 -> "${(seconds / 60).toInt()}m"
    else -> "${(seconds / 3600).toInt()}h"
}

/**
 * Where the averaging chooser stands: open or not, and where its surface and
 * the dashboard root last laid out.
 *
 * The chooser is deliberately *not a mode*. The Average button owns the
 * averaging state; this class holds nothing but the chooser's own visibility,
 * so closing it - by any route - cannot change what the chart is drawing.
 *
 * The two [LayoutCoordinates] are plain fields rather than state because they
 * are only ever read inside a pointer handler, where recomposition has no part
 * to play. They exist so "a touch outside the chooser" can be answered in one
 * coordinate space regardless of which layout, inset or padding either node
 * sits under - see [closeAveragingChooserOnTouchOutside].
 */
@Stable
internal class AveragingChooserState {
    var open by mutableStateOf(false)

    /** The dashboard node carrying the tap-away handler; set by that modifier. */
    var rootCoords: LayoutCoordinates? = null

    /** The chooser's surface, while one is showing. */
    var chooserCoords: LayoutCoordinates? = null

    /** Whether [position], local to the handler's node, lands on the chooser. */
    fun chooserContains(position: Offset): Boolean {
        val root = rootCoords?.takeIf { it.isAttached } ?: return false
        val chooser = chooserCoords?.takeIf { it.isAttached } ?: return false
        return root.localBoundingBoxOf(chooser, clipBounds = false).contains(position)
    }
}

@Composable
internal fun rememberAveragingChooserState(): AveragingChooserState =
    remember { AveragingChooserState() }

/**
 * The Average button: a toggle, with a press-and-hold for choosing a size.
 *
 * ## The model
 *
 * Not engaged means raw - there is no "raw" state to name, so the word does not
 * appear anywhere on this control. A plain tap engages averaging with the
 * window chosen automatically from the visible span (see
 * `ChartView.effectiveBucketSeconds` - the maths the chart has always used),
 * and a second tap turns it off again. Press-and-hold opens the [chooser
 * drawer][AveragingChooser] for picking a size by hand, engaging averaging
 * first if it was off - **a hold never turns averaging off**. Only the plain
 * tap does, which is the rule that keeps the two gestures unambiguous.
 *
 * A tap deliberately re-engages *automatic* averaging rather than the last
 * hand-picked size: the tap is the "just average it sensibly" gesture, and a
 * manual size is only ever reached through the deliberate hold. If a tap
 * restored a pinned size the two gestures would blur into each other.
 *
 * ## What it shows
 *
 * The state it is in, in the selected/unselected styling every chip here
 * already uses: "Average" unselected when off, "Average" selected when
 * engaged automatically, and the size joining the label - "Average 30s" -
 * when one was picked by hand, so what the chart is actually doing is
 * readable at a glance.
 */
@Composable
internal fun AverageButton(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    chooser: AveragingChooserState,
) {
    val chart = state.chart
    val engaged = chart != null && (chart.autoInterval || chart.bucketSeconds != null)
    val manual = chart?.bucketSeconds?.takeIf { !chart.autoInterval }
    LongPressChip(
        selected = engaged,
        label = if (manual != null) "$AVERAGE_LABEL ${formatAveraging(manual)}" else AVERAGE_LABEL,
        onClick = {
            if (engaged) viewModel.setPlotInterval(null) else viewModel.setAutoAveraging()
        },
        onLongClick = {
            if (!engaged) viewModel.setAutoAveraging()
            chooser.open = true
        },
    )
}

/**
 * Hosts a controls region and, over it, the averaging chooser.
 *
 * ## Why the chooser lives here and not in a popup
 *
 * The one absolute rule the chooser has is that it must never cover the plot:
 * its whole point is watching the graph redraw as sizes are tried. A popup
 * window can be positioned to honour that, but nothing *makes* it hold as the
 * layouts change. This host makes the rule structural: the chooser is an
 * overlay inside the same box as the controls it is allowed to overlap
 * ([matchParentSize], so it cannot influence the box's own measurements), and
 * that box ends where the plot begins - above it in the stacked layout, beside
 * it in the panel column of the wide one. There is nowhere the chooser could
 * reach the plot from, in either orientation, and the geometry tests measure
 * exactly that.
 *
 * Bottom-aligned because the plot is the neighbour the drawer belongs to: in
 * portrait it opens directly above the graph the user is watching, with
 * whatever height it needs taken from over the other controls - which the
 * captain has expressly allowed, since none of them are needed while choosing
 * an average.
 */
@Composable
internal fun AveragingChooserHost(
    chooser: AveragingChooserState,
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth().testTag(AVERAGING_HOST_TAG)) {
        Column(Modifier.fillMaxWidth()) { content() }
        if (chooser.open) {
            Box(
                modifier = Modifier.matchParentSize().zIndex(1f),
                contentAlignment = Alignment.BottomCenter,
            ) {
                AveragingChooser(state, viewModel, chooser)
            }
        }
    }
}

/**
 * The drawer of hand-picked averaging sizes, from the list the options menu
 * edits.
 *
 * It persists while open: each tap applies immediately, the graph redraws, and
 * the drawer stays for the next try - the user comparing three sizes must not
 * have to reopen it three times. It closes only when a touch lands outside it
 * (see [closeAveragingChooserOnTouchOutside]), and closing changes nothing.
 *
 * Only sizes live here. Automatic and off are the button's own two taps, so
 * offering them again as entries would be the same control twice with
 * different words.
 */
@Composable
private fun AveragingChooser(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    chooser: AveragingChooserState,
) {
    val chart = state.chart
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline,
        ),
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AVERAGING_CHOOSER_TAG)
            .onGloballyPositioned { chooser.chooserCoords = it }
            // The chooser overlaps other controls, so its dead space has to be
            // its own: a press between two chips must not fall through to
            // whatever control the drawer happens to be covering.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown() } },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "average over",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
            AveragingSizeChips(chart?.takeIf { !it.autoInterval }?.bucketSeconds, state, viewModel)
        }
    }
}

/** The size chips themselves, current hand-picked size marked selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AveragingSizeChips(
    manualBucket: Double?,
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    ControlChips {
        for (seconds in state.config.plotIntervals) {
            // A 1s entry is not an averaging window, it is the raw drawing the
            // button's own tap already provides; the old row skipped it too.
            if (seconds <= 1.0) continue
            FilterChip(
                selected = manualBucket == seconds,
                onClick = { viewModel.setPlotInterval(seconds) },
                label = { Text(formatAveraging(seconds)) },
            )
        }
    }
}

/**
 * Closes the averaging chooser when a touch lands anywhere but on it.
 *
 * Applied to the dashboard root, and listening on the initial pass so it hears
 * every touch - the plot and the controls consume the presses aimed at them,
 * and a press on any of those is exactly as much "tapping away" as one on dead
 * panel space. Nothing is consumed here: the touch goes on to do whatever it
 * was for, and the chooser simply leaves. That is safe *because* the chooser
 * is not a mode - it holds no state of its own, so closing it this way cannot
 * undo or commit anything.
 *
 * The bounds question is answered through [AveragingChooserState.chooserContains]
 * rather than by comparing against `boundsInRoot`, because this node and the
 * chooser sit under different stacks of padding and insets, and two "root"
 * coordinate spaces that are usually the same is exactly the sort of thing
 * that holds until a `Scaffold` gains an inset.
 *
 * The handler is attached whether or not the chooser is open, gating on the
 * flag at event time: the long-press that *opens* the chooser then cannot race
 * the attach, and its own gesture is already past the down this waits for, so
 * opening never immediately closes.
 */
internal fun Modifier.closeAveragingChooserOnTouchOutside(
    chooser: AveragingChooserState,
): Modifier = this
    .onGloballyPositioned { chooser.rootCoords = it }
    .pointerInput(chooser) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (chooser.open && !chooser.chooserContains(down.position)) {
                chooser.open = false
            }
        }
    }

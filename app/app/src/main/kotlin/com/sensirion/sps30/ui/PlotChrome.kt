package com.sensirion.sps30.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/*
 * The plot's own controls - the chrome that lives on the graph itself.
 *
 * Everything here exists because of one principle the captain stated twice: a
 * control belongs where the thing it affects is. Follow rides the newest
 * reading, so it sits at the top right where the newest reading arrives; the
 * graph lock protects the whole plot, so it takes the corner; the crosshair
 * toggle joins the pin button, which was already the crosshair family's ground;
 * each axis hold sits at the axis it holds; and the tick-density control sits
 * on the time axis whose gradation it cycles.
 *
 * The full inventory, its costs and who yields to whom is docs/PLOT_FURNITURE.md.
 * Update that map in the same commit as any change here.
 */

/**
 * The callbacks the plot's on-graph controls fire, as one value - the same
 * shape [ChartTouchCallbacks] gives the gesture layer, for the same reason:
 * [PmChart] would otherwise grow a dozen more parameters.
 *
 * Null on a [PmChart] rendered without chrome (previews, gesture tests): the
 * plot then draws no controls at all, which is also what keeps every existing
 * bare-chart test meaningful.
 */
internal data class PlotChromeCallbacks(
    val onToggleGraphLock: () -> Unit,
    val cursorEnabled: Boolean,
    val onToggleCursor: () -> Unit,
    val follows: Boolean,
    val onToggleFollows: () -> Unit,
    val onCycleTicks: () -> Unit,
    val onSetXLocked: (Boolean) -> Unit,
    val onReleaseX: () -> Unit,
    val onSetYLocked: (Boolean) -> Unit,
    val onReleaseY: () -> Unit,
    val onTimeFlag: () -> Unit,
)

// --- the top annotation strip's plan -----------------------------------------

/**
 * Where the top strip's three text tenants go this frame, in canvas pixels.
 *
 * [flag] is null when there is nothing to flag. [infoLine] counts strip lines
 * from 1; the flag's own line is readable off [flag]'s vertical centre.
 */
internal class TopStripPlan(
    val unitX: Float,
    /** Which strip line the flag sits on (1 beside the unit, 2 under it), or null. */
    val flagLine: Int?,
    val flagX: Float,
    val flagWidth: Float,
    /** Which strip line the view note sits on: 1, 2 or 3. */
    val infoLine: Int,
)

/**
 * Lay out the top strip: the unit mark, the derived-times flag beside it, and
 * the view note right-aligned clear of the Follow/Lock icons' reserve.
 *
 * Measured, not hoped: the strip was already at capacity on a phone before the
 * flag and the icon pair arrived, so every tenant that does not fit moves down
 * a line instead of overprinting its neighbour. The flag keeps its place
 * beside the unit whenever it fits - it qualifies the axis under it, and that
 * adjacency is the whole reason it lives here - and the view note, which is
 * bookkeeping, is what yields: line 1 when there is room, line 2 otherwise,
 * line 3 in the extreme where the flag has already claimed line 2.
 *
 * Pure, so `TopStripPlanTest` can hold the no-overlap promise at real phone
 * and tablet plot widths without a renderer.
 */
internal fun topStripPlan(
    plotLeft: Float,
    plotRight: Float,
    unitWidth: Float,
    flagWidth: Float?,
    infoWidth: Float,
    rightReserve: Float,
    gap: Float = 24f,
): TopStripPlan {
    val unitX = plotLeft + 10f
    val unitEnd = unitX + unitWidth
    val stripRight = plotRight - 8f - rightReserve
    val flagLine1 = flagWidth != null && unitEnd + gap + flagWidth <= stripRight
    val flagLine = when {
        flagWidth == null -> null
        flagLine1 -> 1
        else -> 2
    }
    val flagX = if (flagLine1) unitEnd + gap else unitX
    val flagEnd = if (flagWidth == null) unitEnd else flagX + flagWidth
    val leftEndLine1 = if (flagLine1) flagEnd else unitEnd
    val infoLeft = stripRight - infoWidth
    val infoLine = when {
        infoLeft >= leftEndLine1 + gap -> 1
        flagLine != 2 || infoLeft >= flagEnd + gap -> 2
        else -> 3
    }
    return TopStripPlan(unitX, flagLine, flagX, flagWidth ?: 0f, infoLine)
}

// --- the x labels' plan -------------------------------------------------------

/** A label may sit on the tick row, the date row, or nowhere. */
internal const val X_LABEL_SKIP = -1
internal const val X_LABEL_TICK_ROW = 0
internal const val X_LABEL_DATE_ROW = 1

/**
 * Which ticks carry a time label, and on which of the bottom margin's two text
 * rows - the whole answer to the captain's "label more gridlines" idea.
 *
 * His suggestion was to alternate labels above and below the plot. The top
 * strip is spoken for - unit, flag, view note, readouts and now the icon pair,
 * see docs/PLOT_FURNITURE.md - and a second label row up there reads as a
 * second axis. The density he was after is delivered at the bottom instead:
 * when the tick control has stepped the gridlines finer ([stagger]) and no
 * date mark needs the second row ([datesInUse]), labels alternate between the
 * tick row and the date row. Two rows hanging from one axis, in one type at
 * one rhythm, roughly double what the axis can label - and same-row
 * neighbours are twice the tick pitch apart, which is what the thinning
 * stride is computed against.
 *
 * Even spacing survives: labels are thinned by a constant stride and rows
 * alternate among the survivors, so the pattern never clumps. Bounds are the
 * label ground left over after the on-axis controls' reserves - a label that
 * would run under a control is dropped, exactly as one that would run off the
 * plot always has been.
 */
internal fun xLabelPlacement(
    tickXs: FloatArray,
    labelWidths: FloatArray,
    stagger: Boolean,
    datesInUse: Boolean,
    left: Float,
    right: Float,
    gapPx: Float = 24f,
): IntArray {
    val plan = IntArray(tickXs.size) { X_LABEL_SKIP }
    if (tickXs.isEmpty()) return plan
    val widest = labelWidths.maxOrNull() ?: 0f
    val spacing = if (tickXs.size >= 2) tickXs[1] - tickXs[0] else Float.MAX_VALUE
    val rows = if (stagger && !datesInUse) 2 else 1
    val stride = if (spacing <= 0f) 1 else {
        ceil((widest + gapPx) / (rows * spacing)).toInt().coerceAtLeast(1)
    }
    var drawn = 0
    for (i in tickXs.indices) {
        if (i % stride != 0) continue
        val row = if (rows == 2) drawn % 2 else X_LABEL_TICK_ROW
        drawn++
        val half = labelWidths.getOrElse(i) { widest } / 2f
        if (tickXs[i] - half < left || tickXs[i] + half > right) continue
        plan[i] = row
    }
    return plan
}

// --- the icons themselves -----------------------------------------------------

/**
 * One on-plot icon button: the corner pin button's species, generalised. A
 * bordered surface so it reads as something to press rather than a stray mark
 * on the plot, selected state carried by the chip fill every control on this
 * screen uses, and sized in canvas pixels so it agrees with the axis geometry
 * at every density - see `CORNER_BUTTON_SIDE` in PmChart.kt for what a dp box
 * beside a px gutter did.
 */
@Composable
internal fun PlotIconButton(
    sidePx: Float,
    selected: Boolean,
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable (glyph: Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val glyph = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val d = LocalDensity.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) scheme.secondaryContainer else scheme.outline),
        modifier = modifier
            .size(with(d) { sidePx.toDp() })
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { content(glyph) }
    }
}

/** The graph lock: the padlock face [GraphLockControl] taught this screen. */
@Composable
internal fun GraphLockIcon(locked: Boolean, onToggle: () -> Unit, sidePx: Float) {
    PlotIconButton(
        sidePx = sidePx,
        selected = locked,
        onClick = onToggle,
        description = if (locked) {
            "${AxisHoldLabels.RELEASE_GRAPH}: it is locked and ignoring touch. Tap to release it."
        } else {
            "${AxisHoldLabels.LOCK_GRAPH}: tap to lock it so touching it does nothing."
        },
    ) { _ ->
        Text(if (locked) "🔒" else "🔓", fontSize = 14.sp)
    }
}

/** The crosshair toggle, wearing the crosshair itself - [CROSSHAIR_GLYPH]. */
@Composable
internal fun CrosshairToggleIcon(
    enabled: Boolean,
    onToggle: () -> Unit,
    sidePx: Float,
    modifier: Modifier = Modifier,
) {
    PlotIconButton(
        sidePx = sidePx,
        selected = enabled,
        onClick = onToggle,
        description = if (enabled) "Crosshair on. Tap to turn it off."
        else "Crosshair off. Tap to turn it on.",
        modifier = modifier,
    ) { glyph ->
        Text(CROSSHAIR_GLYPH, fontSize = 18.sp, color = glyph)
    }
}

/**
 * Follow: an arrow riding into the right-edge bar, drawn rather than worded
 * because its ground is now the plot itself. The word survives in the spoken
 * description, as the padlock's does.
 */
@Composable
internal fun FollowIcon(follows: Boolean, onToggle: () -> Unit, sidePx: Float) {
    val d = LocalDensity.current
    PlotIconButton(
        sidePx = sidePx,
        selected = follows,
        onClick = onToggle,
        description = if (follows) {
            "Following the newest reading. Tap to leave the crosshair where it is."
        } else {
            "Follow: tap to make the crosshair follow the newest reading."
        },
    ) { glyph ->
        Canvas(modifier = Modifier.size(with(d) { (sidePx * 0.5f).toDp() })) {
            val w = size.width
            val h = size.height
            val stroke = h * 0.10f
            val cy = h / 2f
            // The live edge, and the arrow that rides to it.
            drawLine(glyph, Offset(w * 0.86f, h * 0.08f), Offset(w * 0.86f, h * 0.92f), stroke)
            drawLine(glyph, Offset(0f, cy), Offset(w * 0.62f, cy), stroke, cap = StrokeCap.Round)
            val head = Path().apply {
                moveTo(w * 0.72f, cy)
                lineTo(w * 0.42f, cy - h * 0.26f)
                lineTo(w * 0.42f, cy + h * 0.26f)
                close()
            }
            drawPath(head, glyph)
        }
    }
}

/**
 * One axis hold, as its two on-axis icons: the padlock that pins the held
 * range against further gestures, and the release that hands the axis back.
 *
 * The words this control used to spend a row on - the heading, "Lock",
 * "Release Y scale", and the note explaining what is holding the axis - are
 * all still said, in the spoken descriptions, from the same [AxisHoldLabels]
 * family, so `AxisHoldFamilyTest`'s no-collision rule still covers every way
 * out. What the eye gets instead is position: the pair sits at the axis it
 * holds, which is the principle that sent it here.
 */
@Composable
internal fun AxisHoldIcons(
    heading: String,
    releaseLabel: String,
    locked: Boolean,
    note: String,
    onSetLocked: (Boolean) -> Unit,
    onRelease: () -> Unit,
    sidePx: Float,
) {
    PlotIconButton(
        sidePx = sidePx,
        selected = locked,
        onClick = { onSetLocked(!locked) },
        description = if (locked) {
            "$heading ${AxisHoldLabels.LOCKED}: ${AxisHoldLabels.HELD_AGAINST_TOUCH}. " +
                "Tap to unlock."
        } else {
            "$heading ${AxisHoldLabels.LOCK}: $note. Tap to lock the held range " +
                "against stray touches."
        },
    ) { _ ->
        Text(if (locked) "🔒" else "🔓", fontSize = 12.sp)
    }
    ReleaseIcon(releaseLabel = releaseLabel, note = note, onRelease = onRelease, sidePx = sidePx)
}

/** The way out of a hold: a circling-back arrow, release in one press. */
@Composable
internal fun ReleaseIcon(releaseLabel: String, note: String, onRelease: () -> Unit, sidePx: Float) {
    val d = LocalDensity.current
    PlotIconButton(
        sidePx = sidePx,
        selected = false,
        onClick = onRelease,
        description = "$releaseLabel: $note. Tap to release the hold.",
    ) { glyph ->
        Canvas(modifier = Modifier.size(with(d) { (sidePx * 0.52f).toDp() })) {
            val w = size.width
            val h = size.height
            val stroke = h * 0.11f
            drawArc(
                color = glyph,
                startAngle = -60f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(stroke, stroke),
                size = androidx.compose.ui.geometry.Size(w - 2 * stroke, h - 2 * stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // The arrowhead at the arc's open end, pointing the way back.
            val head = Path().apply {
                moveTo(w * 0.86f, h * 0.10f)
                lineTo(w * 0.58f, h * 0.16f)
                lineTo(w * 0.84f, h * 0.40f)
                close()
            }
            drawPath(head, glyph)
        }
    }
}

/**
 * The tick-density control, on the axis whose gradation it cycles.
 *
 * Its face is the current tick interval - "6h", "30m" - so it is the axis'
 * own legend: the label ground it displaces at the margin's right end is
 * partly repaid by the control itself stating the step, which no dropped
 * label said. Tapping steps the gridlines one rung finer and wraps back to
 * the window's own interval; the rungs are derived from the window (see
 * `TimeTicks.finerInterval`), so from days the steps are hours and from an
 * hour they are minutes. Immediate and reversible, no dialog and no mode.
 */
@Composable
internal fun TickDensityButton(
    stepLabel: String,
    engaged: Boolean,
    onCycle: () -> Unit,
    sidePx: Float,
) {
    PlotIconButton(
        sidePx = sidePx,
        selected = engaged,
        onClick = onCycle,
        description = "Gridlines every $stepLabel. Tap for finer gridlines; " +
            "the steps cycle back to the window's own spacing.",
    ) { glyph ->
        Text(stepLabel, fontSize = 12.sp, color = glyph, maxLines = 1)
    }
}

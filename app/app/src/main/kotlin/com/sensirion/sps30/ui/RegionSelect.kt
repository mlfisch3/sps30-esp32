package com.sensirion.sps30.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AlertRule
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.RegionStats
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.data.compactDuration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/*
 * Selecting a stretch of the curve, and reporting on it.
 *
 * The pieces, and where each lives:
 *
 *  - The **gesture** is the plot's, inside `PmChart`'s one gesture block, using
 *    the same arbiter every other touch goes through. While Select is armed a
 *    lone finger draws the selection instead of the crosshair; two fingers
 *    still navigate, exactly as they always have.
 *  - The **armed flag** and the in-flight drag live in the screen; the
 *    **committed selection and its statistics** live in [MonitorSession],
 *    because the statistics must be computed from the full-resolution history
 *    (model-confined) and refreshed as backfill lands inside the selection.
 *  - The **numbers** are [RegionStats] in :core - computed from the raw
 *    samples in the selected range, never from the drawn points, which at wide
 *    windows are bucket means that would understate the very peak the captain
 *    selected the stretch to study.
 *  - The **statistics are plot text**, not a card: a few lines in the
 *    crosshair readout's own idiom ([regionPlotLines], drawn by `drawPmChart`
 *    in the top corner away from the selection). The captain rejected the
 *    card outright - a panel over the plot hid the very stretch of curve the
 *    numbers describe - and asked for "a couple more numbers that appear just
 *    like the reading and the time" of the crosshair.
 *  - The **actions** (Zoom to selection, Back, Clear) and the explanation
 *    behind the small circled "?" live with the controls ([RegionActions]),
 *    never over the plot - see the docking notes there.
 */

/** The committed selection, reported: its bounds, its series, and its numbers. */
data class RegionReport(
    val start: Double,
    val end: Double,
    /** The series the numbers describe; see [statsField]. */
    val field: String,
    /** The alert level "time above" was measured against, or null with none set. */
    val threshold: Double?,
    /** The statistics, or null when the stretch holds no samples. */
    val stats: RegionStats?,
    /** True while a zoom's return view is saved, so "Back" has somewhere to go. */
    val canReturn: Boolean,
)

/**
 * The report for the selection [start]..[end], computed from the raw history.
 *
 * Always the raw samples: `window` is asked with no point budget and no
 * bucket, so what the chart is currently drawing - decimated, averaged, or
 * neither - has no bearing on the numbers. The flag filter does follow the
 * plot (`excludeFlagged`), because a reading the user has excluded from the
 * picture must not come back in the numbers about the picture.
 *
 * The gap rule is the plot's own ([ChartView.gapThreshold]), derived from the
 * selection's samples, so "time above" and the peak width refuse to count
 * time across exactly the holes the plot refuses to draw a line across.
 */
internal fun buildRegionReport(
    history: TimeSeriesHistory,
    start: Double,
    end: Double,
    config: AppConfig,
    canReturn: Boolean,
): RegionReport {
    val field = statsField(config.visiblePm, config.alerts)
    val raw = history.window(
        start, end,
        excludeFlagged = config.excludeFlagged,
        maxPoints = null,
        bucketSeconds = null,
    )
    val threshold = regionThreshold(field, config.alerts)
    val stats = RegionStats.compute(
        raw.epochs,
        raw.series[field] ?: DoubleArray(0),
        threshold,
        ChartView.gapThreshold(raw.epochs, null),
    )
    return RegionReport(start, end, field, threshold, stats, canReturn)
}

/**
 * Which series the selection reports on: one, named on the card, rather than a
 * wall of numbers about every curve.
 *
 * The watched series - the one with the lowest enabled alert level - wins when
 * it is on screen, because an alert-level excursion is what a selection is
 * probed for. Otherwise the health-relevant PM2.5 if visible, then whatever
 * is; a report about a curve that is not drawn would name numbers the user
 * cannot see.
 */
internal fun statsField(visible: List<String>, alerts: List<AlertRule>): String {
    val watched = alerts.filter { it.enabled }.minByOrNull { it.threshold }?.field
    return when {
        watched != null && watched in visible -> watched
        "pm2_5" in visible -> "pm2_5"
        visible.isNotEmpty() -> visible.first()
        else -> watched ?: "pm2_5"
    }
}

/**
 * The series' own alert level: the lowest enabled rule watching [field], or
 * null when none is - "time above" is then absent, not zero.
 *
 * Deliberately not gated on `highlightPlot`: that flag decides whether the
 * plot shades the excursion, and a level the user set is still the level they
 * measure against with the shading off.
 */
internal fun regionThreshold(field: String, alerts: List<AlertRule>): Double? =
    alerts.filter { it.enabled && it.field == field }.minOfOrNull { it.threshold }

// --- the zoom, and the way back ---------------------------------------------

/** The view to give back when the selection zoom is released. */
internal data class RegionReturnView(
    val mode: ViewMode,
    val start: Double,
    val end: Double,
    /**
     * Whether the range was a held one. A pinned view can carry a held frame
     * now - a window-size tap in Pin frames [start, start + size] from the pin
     * (see [ChartView.applyWindow]) - and "back" from the zoom must mean that
     * frame, not the growing [pin, now] a bare Pin mode would re-derive.
     */
    val frozen: Boolean = false,
)

/** What is on screen now, in the terms [restoreReturnView] can give back. */
internal fun captureReturnView(chart: ChartView, now: Double): RegionReturnView {
    val (start, end) = chart.visibleRange(now)
    return RegionReturnView(chart.viewMode(), start, end, chart.xFrozen())
}

/**
 * Frame the selection as the visible window - a held range, the same
 * [ViewMode.LIVE_FROZEN] a hand-panned view lands in, so every existing way
 * out (Live, Pin, Auto, the presets, Release time axis) applies unchanged.
 * Same shape as [ChartView.snapWindowStart] for the same reason.
 */
internal fun zoomViewToRegion(chart: ChartView, start: Double, end: Double, now: Double) {
    if (end <= start) return
    chart.setMode(ChartMode.MOVING, now)
    chart.setXRange(start, end)
}

/**
 * Put the view back where [captureReturnView] found it.
 *
 * By mode, because "where he was" means different things: a live window is a
 * span that follows now, not the frozen copy of one; a pinned view still grows
 * from its origin (which the zoom never touched - [ChartView.setXRange] leaves
 * `pinStart` alone); a held range is exactly its range; and the whole-session
 * view is re-derived the way the All button derives it.
 */
internal fun restoreReturnView(
    chart: ChartView,
    saved: RegionReturnView,
    sessionStart: Double?,
    now: Double,
) {
    when (saved.mode) {
        ViewMode.LIVE -> chart.applyWindow((saved.end - saved.start).coerceAtLeast(1.0), now)
        ViewMode.LIVE_FROZEN -> chart.setXRange(saved.start, saved.end)
        ViewMode.PIN -> {
            chart.setMode(ChartMode.PINNED, now)
            // A framed pin view gets its frame back; a bare one grows from its
            // origin as it always has. setXRange leaves pinStart alone, so the
            // pin - and the mark the size buttons measure from - survives the
            // round trip either way.
            if (saved.frozen) chart.setXRange(saved.start, saved.end)
            else chart.clearXRange()
        }
        ViewMode.ALL -> chart.applyAll(sessionStart ?: now, now)
    }
}

// --- the drag ----------------------------------------------------------------

/**
 * One selection drag, from the finger's landing to its lift.
 *
 * Kept free of Compose types, like [ChartTouchArbiter], so the rules can be
 * tested on numbers: the band always runs from the anchor to the finger in
 * time order whichever way the hand went, a selection only starts existing
 * once the finger has plainly moved sideways ([dragged]), and a touch that
 * moved at all - any direction - is no longer a tap ([travelled]), so a
 * vertical fidget neither draws a selection nor clears the one that exists.
 */
internal class RegionDrag(
    private val slopPx: Float,
    private val anchorX: Float,
    private val anchorY: Float,
    private val anchorEpoch: Double,
) {
    /** True once the finger has moved sideways past slop: a band is being drawn. */
    var dragged = false
        private set

    /** True once the finger has moved past slop in any direction. */
    var travelled = false
        private set

    /** The band, in time order. Zero-width until the finger moves. */
    var lo: Double = anchorEpoch
        private set
    var hi: Double = anchorEpoch
        private set

    fun update(x: Float, y: Float, epoch: Double) {
        if (abs(x - anchorX) >= slopPx) dragged = true
        if (dragged || abs(y - anchorY) >= slopPx) travelled = true
        lo = minOf(anchorEpoch, epoch)
        hi = maxOf(anchorEpoch, epoch)
    }
}

// --- the words on the plot ----------------------------------------------------

/**
 * The selection's statistics as plot text: a few lines in the crosshair
 * readout's own idiom, built pure so the wording is testable without a
 * renderer. Drawn by `drawPmChart` in the top corner away from the selection.
 *
 * Compressed to what earns a line at readout size, in the captain's priority:
 * the span and its duration, the maximum and its moment, time above the alert
 * level, mean and standard deviation, and the peak width. The unit is not
 * repeated - the plot already wears it - and the explanatory prose lives
 * behind the "?" on [RegionActions] ([regionExplanation]), off the plot.
 *
 * Every line is kept deliberately short - readout type is generous, and on a
 * phone in portrait a long line is a block the plot has no clear corner for.
 * That is also why the series and the duration head their own line rather
 * than riding with the stamps.
 */
internal fun regionPlotLines(report: RegionReport): List<String> {
    val withDay = regionSpansDays(report.start, report.end)
    val label = SeriesColors.label[report.field] ?: report.field
    val head = "$label · ${compactDuration(report.end - report.start)}"
    val span = "${regionStamp(report.start, withDay)} to ${regionStamp(report.end, withDay)}"
    val stats = report.stats ?: return listOf(head, span, "no samples in selection")
    return buildList {
        add(head)
        add(span)
        add("max ${SeriesColors.reading(stats.max)} at ${regionStamp(stats.maxAt, withDay)}")
        report.threshold?.let {
            add("above ${SeriesColors.reading(it)} for ${compactDuration(stats.timeAboveSeconds ?: 0.0)}")
        }
        add(
            "mean ${SeriesColors.reading(stats.mean)} · " +
                "sd ${stats.stdDev?.let { SeriesColors.reading(it) } ?: "n/a"}"
        )
        add(fwhmLine(stats.fwhm))
    }
}

/**
 * The peak-width line at readout size. A width that could not be fully
 * measured is a lower bound and must not pose as the width - at this size
 * that whole caveat is the "≥" mark, and the why (cut short, which side, and
 * that the selection edge or a gap did the cutting) is [regionExplanation]'s,
 * behind the "?" beside the words the mark appears in.
 */
internal fun fwhmLine(fwhm: RegionStats.Fwhm?): String = when {
    fwhm == null -> "peak width n/a (flat)"
    fwhm.clipped -> "peak width ≥ ${compactDuration(fwhm.seconds)}"
    else -> "peak width ${compactDuration(fwhm.seconds)}"
}

/**
 * The prose behind the "?": what the peak width means, what a "≥" width is,
 * and the honesty note - the figures come from the raw samples, never from
 * the drawn points, which at wide windows are bucket means. That note is
 * deliberate and survives the card it used to live on.
 */
internal fun regionExplanation(report: RegionReport, bucketSeconds: Double?): String {
    val stats = report.stats ?: return "The selected stretch holds no samples."
    return buildString {
        append(
            "Peak width is the measured FWHM: the peak's full width at half its " +
                "rise above the stretch's lowest value. "
        )
        val fwhm = stats.fwhm
        if (fwhm != null && fwhm.clipped) {
            append(
                "A width marked ≥ is a lower bound: the peak was cut short " +
                    "${clippedSide(fwhm)} by the selection edge or a gap. "
            )
        }
        append("Figures use the ${stats.count} raw samples, not the drawn points")
        if (bucketSeconds != null) {
            append(" (the chart is drawing ${bucketSeconds.toInt()}s averages)")
        }
        append(".")
    }
}

private fun clippedSide(fwhm: RegionStats.Fwhm): String = when {
    fwhm.clippedLeft && fwhm.clippedRight -> "at both ends"
    fwhm.clippedLeft -> "on the left"
    else -> "on the right"
}

/** True when the stretch crosses midnight, so its stamps need their dates. */
internal fun regionSpansDays(start: Double, end: Double): Boolean =
    Instant.ofEpochSecond(start.toLong()).atZone(TimeSeriesHistory.EASTERN).toLocalDate() !=
        Instant.ofEpochSecond(end.toLong()).atZone(TimeSeriesHistory.EASTERN).toLocalDate()

private val REGION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

// No seconds once the day is named: a stretch that crosses midnight is hours
// long, seconds say nothing about it, and the plot lines these stamps land on
// must stay narrow enough for a clear corner on a phone in portrait.
private val REGION_DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")

internal fun regionStamp(epoch: Double, withDay: Boolean): String =
    Instant.ofEpochSecond(epoch.toLong())
        .atZone(TimeSeriesHistory.EASTERN)
        .format(if (withDay) REGION_DAY_TIME else REGION_TIME)

// --- the controls -------------------------------------------------------------

/**
 * The one control that arms the gesture. A mode chip rather than a hidden
 * gesture, because the plot's touch language is full: a lone finger already
 * means the crosshair (or a pan, with the crosshair off or pinned), a hold is
 * already how the crosshair settles, and two fingers navigate. See the commit
 * message and `PmChart` for the whole argument.
 */
@Composable
internal fun RegionSelectControl(selecting: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selecting,
        onClick = onToggle,
        label = { Text("Select") },
        modifier = Modifier.semantics {
            contentDescription = if (selecting) {
                "Selection mode on: drag across the plot to select a stretch of " +
                    "the curve. Tap to turn it off."
            } else {
                "Tap to turn on selection mode, then drag across the plot to " +
                    "select a stretch of the curve and see its statistics."
            }
        },
    )
}

/** The sentence under the chips while Select is armed. */
internal const val REGION_SELECT_NOTE =
    "Drag across the plot to select a stretch of the curve; its statistics " +
        "appear on the plot the way the crosshair's readout does. Two-finger " +
        "pan and zoom still work, and a tap on the plot clears the selection."

/**
 * The selection's actions, docked where the current layout keeps its
 * controls. Nothing renders while there is no selection.
 *
 * A row of small controls, not a card: the statistics themselves are plot
 * text ([regionPlotLines]), because the captain's rule is that the user must
 * never lose sight of the plot while studying the plot. What cannot be plot
 * text lives here, off the plot - zooming to the selection (the explicit
 * confirmation, never a side effect of lifting the finger), Back for as long
 * as the zoom's return view is held, Clear, and the circled "?" that expands
 * the explanation prose ([regionExplanation]) in place.
 *
 * Each layout hands the row a ground whose appearance the plot does not pay
 * for, so the graph-never-resizes rule holds too:
 *
 *  - portrait: the top of the [SteadyControls] viewport, exactly as the
 *    axis-hold rows do - the viewport's height is probed without it, so the
 *    column scrolls and the plot keeps its size;
 *  - landscape: the top of the fixed-width control column, above the
 *    readouts, because on a phone in landscape anything lower starts under
 *    the fold.
 *
 * `RegionActionsPlacementTest` measures both promises - the actions off the
 * plot, and the plot the same size with and without a selection - at all
 * four screen shapes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegionActions(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
) {
    val report = state.region ?: return
    var explaining by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Selection actions" },
    ) {
        FlowRow {
            TextButton(onClick = viewModel::zoomToRegion) { Text("Zoom to selection") }
            if (report.canReturn) {
                TextButton(
                    onClick = viewModel::returnFromRegionZoom,
                    modifier = Modifier.semantics {
                        contentDescription = "Return to the view from before the zoom."
                    },
                ) { Text("Back") }
            }
            TextButton(onClick = viewModel::clearRegion) { Text("Clear") }
            RegionHelpButton(
                explaining = explaining,
                onToggle = { explaining = !explaining },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (explaining) {
            ControlNote(
                regionExplanation(report, state.chart?.bucketSeconds),
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * The small circled "?" that expands the selection's explanation.
 *
 * A drawn glyph rather than an icon: the standard help affordance is a
 * question mark in a circle in the accent colour, and pulling in
 * material-icons-extended for one glyph is the trade [InfoLine]'s plug icon
 * already declined. The prose it reveals expands *below the controls*, never
 * over the plot - the whole reason the card this replaces was rejected.
 */
@Composable
private fun RegionHelpButton(
    explaining: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (explaining) accent.copy(alpha = 0.15f) else Color.Transparent,
        border = BorderStroke(1.5.dp, accent),
        modifier = modifier
            .size(24.dp)
            .semantics {
                contentDescription = if (explaining) {
                    "Hide the explanation of the selection's statistics."
                } else {
                    "Explain the selection's statistics: what peak width means " +
                        "and where the figures come from."
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "?",
                color = accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.PlotStyle
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.compactDuration

/**
 * Everything the plot is drawn from, as one value.
 *
 * The chart is drawn twice: onto the screen by [PmChart]'s Canvas, and into a
 * bitmap by [renderChartToBitmap] when the user saves the image. Those two
 * paths used to be handed their inputs separately, and the export quietly took
 * fewer of them - no crosshair, a linear axis where the screen had a log one, no
 * alert line, no annotations - so "save as image" produced a picture of a chart
 * the user had never seen. Nothing failed; the file simply disagreed with the
 * glass, which is the worst way for an export to be wrong, because the picture
 * is the only thing that outlives the moment.
 *
 * Gathering the inputs into one value is what stops that recurring: a new thing
 * to draw is a field here, both paths receive it by construction, and the
 * compiler - not a reviewer's memory - is what notices when one is added.
 *
 * Built from the UI state by [chartRender], which is a pure function and is what
 * the tests exercise.
 */
data class ChartRender(
    /** The windowed samples: the zoom and pan as they currently are. */
    val data: ChartData?,
    /** Which PM series the user has switched on. */
    val visible: List<String>,
    /** The crosshair's instant, or null when no crosshair is showing. */
    val inspectedEpoch: Double? = null,
    val logY: Boolean = false,
    /** How the traces are drawn: hairline plus hollow markers, or plain line. */
    val plotStyle: PlotStyle = PlotStyle.MARKERS,
    /** Where the dashed alert line is drawn: the lowest threshold that asked. */
    val alertThreshold: Double? = null,
    /**
     * Per-series alert level, for shading the plot where a series is above its
     * own line: each drawn field to the lowest enabled threshold watching it
     * that asked to appear on the plot. Distinct from [alertThreshold], which
     * is the single lowest across all fields and places only the dashed line.
     * See AlertFill.kt for how the shading is drawn.
     */
    val alertFills: Map<String, Double> = emptyMap(),
    /** "window 1d 12h · 1305 pts · frozen" - drawn inside the plot. */
    val viewInfo: String = "",
    /**
     * The zone the time labels are in ("EDT"). Nothing draws it since the
     * x-axis caption that carried it was removed - the ticks say what the axis
     * is without a caption, and the caption's line went back to the plot - but
     * it stays a render input: it is part of what the labels mean, and the
     * export or a readout stamp may need to say so again.
     */
    val timeZoneLabel: String = "",
    /**
     * Where the record is discontinuous, shaded under the traces.
     *
     * The plot already lifts its pen across a hole, which says "these two points
     * are not joined" and stops well short of saying "readings are missing
     * here" - on a quiet trace the two are indistinguishable, and neither of
     * them distinguishes a hole the sensor can still supply from one it has
     * recorded over. Those call for opposite reactions, so they are drawn
     * differently. See [drawRecordGaps].
     *
     * Empty unless the user switched the marking on (`AppConfig.markGaps`):
     * [chartRender] is the one gate, so the screen and the exported image agree
     * about it by construction. Only the *drawing* is gated - detection still
     * runs, and the Data tab still reports every hole, whatever this holds.
     */
    val gaps: List<RecordGap> = emptyList(),
    /**
     * The selected stretch of the record, drawn as a band under the traces, or
     * null with nothing selected. In here rather than handed to the screen's
     * Canvas alone so the exported PNG shows the same selection the screen
     * does - the invariant this class exists to hold.
     */
    val selection: Pair<Double, Double>? = null,
    /**
     * The selection's statistics as lines of plot text, drawn in the
     * crosshair readout's idiom in the top corner away from [selection] - see
     * `drawPmChart`. Empty with no committed selection, and empty while a new
     * drag is in flight, when the numbers would describe a stretch other than
     * the band being drawn. In here for the same reason [selection] is: the
     * exported PNG must carry the same statistics the screen shows.
     */
    val regionLines: List<String> = emptyList(),
    /**
     * The derived-times flag's text - "derived times", or "3 unplaced" when
     * runs are waiting to be placed - or null when every timestamp on the plot
     * came from a real clock. Drawn into the top strip beside the unit mark,
     * because it is a caveat about exactly the axis under it; in here rather
     * than screen-only so the exported PNG carries the same caveat, which for
     * a picture of derived times is the part that must not go missing.
     */
    val timeFlag: String? = null,
)

/**
 * Gather what the plot should draw from the current UI state.
 *
 * [inspectedEpoch] is passed in rather than read from the state because the
 * crosshair lives in the screen's own remembered state, not in the session: it
 * is a thing the finger is doing, not a thing the sensor reported.
 */
fun DashboardUiState.chartRender(
    inspectedEpoch: Double?,
    /** The selection to draw: the drag in flight, or the committed stretch. */
    selection: Pair<Double, Double>? = null,
): ChartRender = ChartRender(
    data = chart,
    visible = config.visiblePm,
    inspectedEpoch = inspectedEpoch,
    selection = selection,
    // Only when the band being drawn is the committed stretch: while a fresh
    // drag is in flight the report still describes the previous selection,
    // and numbers beside a band they do not describe would be a lie.
    regionLines = region
        ?.takeIf { selection != null && selection.first == it.start && selection.second == it.end }
        ?.let { regionPlotLines(it) }
        ?: emptyList(),
    logY = config.logY,
    plotStyle = config.plotStyle,
    alertThreshold = alertThreshold(),
    alertFills = alertFills(),
    viewInfo = viewInfo(),
    timeZoneLabel = chart?.timeZoneLabel ?: "",
    // The one place the marking preference acts. The report itself is untouched:
    // the Data tab reads it straight off the state, so switching the shading off
    // cannot soften a word of what that panel says about the record.
    gaps = if (config.markGaps) gaps.gaps else emptyList(),
    // The same words the chip-row flag used to carry, now stated on the plot
    // itself: a count when runs are waiting to be placed, the quiet label
    // otherwise. Null - no flag at all - whenever the board's clock was real.
    timeFlag = relativeTime?.let { rt ->
        if (rt.unanchoredRuns.isNotEmpty()) "${rt.unanchoredRuns.size} unplaced"
        else "derived times"
    },
)

/** The lowest enabled threshold that asked to be drawn, or null if none did. */
private fun DashboardUiState.alertRule() =
    config.alerts.filter { it.enabled && it.highlightPlot }.minByOrNull { it.threshold }

private fun DashboardUiState.alertThreshold(): Double? = alertRule()?.threshold

/**
 * Each watched field to the lowest enabled threshold on it that asked to be
 * drawn. Lowest for the same reason [alertRule] takes the lowest: with rules at
 * 20 and 35 on one series, the shading starts where the first line is crossed,
 * and the stretch above 35 is already inside it.
 */
private fun DashboardUiState.alertFills(): Map<String, Double> =
    config.alerts.filter { it.enabled && it.highlightPlot }
        .groupBy { it.field }
        .mapValues { (_, rules) -> rules.minOf { it.threshold } }

/**
 * The line drawn inside the plot: how much time is on screen, how many points
 * that is, and whether the axis is being held.
 *
 * The window is written in the compact form - `5d 2h`, not `05:02:00:00` - see
 * [com.sensirion.sps30.data.compactDuration] for its rules. The chips that name
 * a window or an averaging preset carry the label the user gave them and are
 * not readouts at all; this line and the gap list are the places the app shows
 * a *computed* span of time.
 */
private fun DashboardUiState.viewInfo(): String {
    val span = chart?.spanSeconds ?: windowSeconds
    return "window ${compactDuration(span)}" +
        (chart?.let { "  ·  ${it.sampleCount} pts" } ?: "") +
        (chart?.takeIf { it.xFrozen }?.let { "  ·  frozen" } ?: "")
}

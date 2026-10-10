package com.sensirion.sps30.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.PlotStyle
import com.sensirion.sps30.data.PlottedSeries
import com.sensirion.sps30.data.XAxisControl
import com.sensirion.sps30.data.YAxisControl
import com.sensirion.sps30.data.compactDuration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max

/*
 * Annotation type sizes, in canvas pixels.
 *
 * Deliberately generous. This is an air-quality instrument that may well be
 * consulted in a hurry, or by someone without their reading glasses to hand, and
 * an axis nobody can read is a measurement nobody can use. The plot gives up
 * about 2% of its width and half a percent of its height to pay for it, which is
 * a trade worth making every time.
 */
// These are font sizes, and a digit's cap-height is only about 71% of that - the
// first pass at enlarging this text set 38f and got 27px-tall digits, which is
// no better than where it started. Sized from the height wanted on glass.
private const val TEXT_TICK = 52f       // axis tick values (~37px digits)
private const val TEXT_CAPTION = 54f    // dates, crosshair readout, placeholders
private const val TICK_LABEL_GAP = 24f  // clear space demanded between x labels
private const val TEXT_NOTE = 50f       // secondary notes inside the plot
// The view note ("window 5d 10h · 862 pts") alone sits a step below the other
// notes: it is bookkeeping, not measurement, and the captain asked for it a
// little smaller so it stops competing with the readings.
private const val TEXT_VIEW_INFO = 42f

// Left padding carries the y tick values and nothing else since the rotated
// axis caption went (see the note above the unit mark in [drawPmChart]); bottom
// padding carries the time ticks plus a date line under them. Both are sized to
// the type above: the gutter fits "1000" - the sensor's own ceiling, so the
// widest value an axis can honestly need - plus the 16px the ticks stand off
// the axis, and every pixel past that goes to the plot.
private const val LEFT_PAD = 134f
private const val RIGHT_PAD = 28f
private const val TOP_PAD = 20f

/*
 * What the bottom margin carries, measured from the x axis down: the time ticks,
 * and the date line that appears where the day turns over.
 *
 * These were numbers written at the draw calls and one unrelated number for the
 * margin that had to hold them, and the margin lost: the "Time of day (EDT)"
 * caption that used to end this stack was drawn centred 160px below the axis in
 * 54px type, which puts its ink 192px down, and the margin was 176px. So the
 * caption was sliced by the bottom edge of the plot - and of the saved PNG,
 * which draws from this same code and is where it was noticed, the picture
 * being the copy that outlives the moment. (The caption itself has since been
 * removed outright - the tick values already say what the axis is - and the
 * margin it occupied went back to the plot.)
 *
 * The margin is derived from the deepest thing drawn into it rather than chosen
 * to look about right, so a line that moves or grows takes its own room with
 * it. `ChartMarginTest` re-measures the whole stack with the real font metrics
 * and fails if any of it reaches the edge.
 */
private const val X_TICK_MID = 44f     // centre of the time labels, below the axis
private const val X_DATE_MID = 104f    // centre of the date line under those
private const val BOTTOM_MARGIN = 8f   // clear space under the deepest ink

/*
 * The strip of annotation across the top of the plot, in canvas pixels from the
 * plot's top edge.
 *
 * Named because two things have to agree about where it is: the crosshair
 * readout and the view note, only one of which is ever showing.
 */
private const val READOUT_FIRST_MID = 38f // centre of the readout's first line
private const val READOUT_LINE = 52f      // line pitch inside the readout
private const val VIEW_NOTE_MID = 30f     // centre of the one-line view note

/**
 * How far below its centre line a line of [sizePx] type puts ink.
 *
 * Font metrics are the truth here, but they need a `Paint` and this has to be a
 * constant the layout is built from, so it is the standard ratio with room to
 * spare: `(descent - ascent) / 2` is about 0.586 of the type size for the fonts
 * Android ships. The test measures the real thing.
 */
private const val INK_BELOW_CENTRE = 0.62f

private fun inkBelowCentre(sizePx: Float) = sizePx * INK_BELOW_CENTRE

private val BOTTOM_PAD = X_DATE_MID + inkBelowCentre(TEXT_CAPTION) + BOTTOM_MARGIN

// Widest-first draw order + graduated widths so coincident series layer visibly.
//
// PM2.5 and PM1.0 and nothing else - see [PlottedSeries] for why. This is the
// hard gate: even a `visible` list that somehow named PM4.0 could not draw it,
// because nothing here would iterate over it.
private val DRAW_ORDER = PlottedSeries.FIELDS.reversed()
private val STROKE_WIDTH = mapOf("pm2_5" to 3.5f, "pm1_0" to 2.5f)

/*
 * The hairline-and-hollow-markers style, in canvas pixels.
 *
 * These are device pixels on a screen of density 3 (the tablet's and the S24's -
 * see [CORNER_BUTTON_SIDE] for what happens when that is forgotten), so a pixel
 * is a third of a dp and [THIN_LINE] is about two thirds of one. That is the
 * thinnest a stroke can be here and still be a solid, evenly-dark line: below two
 * pixels an antialiased diagonal is drawn at partial coverage, and the trace goes
 * grey and patchy where it slopes rather than merely thin.
 *
 * The ring is sized from the inside out, which is the whole of what was asked for
 * - the line must be visible passing through the marker with a little clear space
 * either side of it, and the marker must be no bigger than that requires:
 *
 *     hole radius   = THIN_LINE / 2 + MARKER_GAP        = 1 + 2 = 3px
 *     stroke radius = hole radius + MARKER_STROKE / 2   = 3 + 1 = 4px
 *     outer         = 2 * stroke radius + MARKER_STROKE = 10px across
 *
 * So the hole is 6px wide with a 2px line down the middle of it: 2px of daylight
 * on each side, which is the "little bit of empty space" and not a pixel more.
 * Ten pixels across is five times the line's width, which is what keeps the ring
 * reading as a ring instead of a bead threaded on the trace.
 */
private const val THIN_LINE = 2f
private const val MARKER_STROKE = 2f
private const val MARKER_GAP = 2f
private const val MARKER_RADIUS = THIN_LINE / 2f + MARKER_GAP + MARKER_STROKE / 2f
private const val MARKER_DIAMETER = 2f * MARKER_RADIUS + MARKER_STROKE

/*
 * The closest two rings may sit, centre to centre.
 *
 * The ring's own outer width, so at exactly this pitch neighbours touch without
 * overlapping - still a row of circles, not a band of colour. It is deliberately
 * not a hair more than that: this pitch is what rations the rings, and every
 * pixel added to it is one more measurement the user stops being told about.
 * See [ringedPoints] for how the ration is spent.
 */
private const val MARKER_PITCH = MARKER_DIAMETER

/*
 * What the crosshair spells out in words, as against what it draws.
 *
 * The SPS30 sizes particles optically below about 1.3um and derives the coarser
 * cuts from that distribution, so indoors pm2_5, pm4_0 and pm10 are identical to
 * two decimals nearly all the time. Four lines of readout to say one number
 * three times is four lines of the plot covered up, and the two that carry
 * information - PM2.5 and PM1.0, the pair with their own tiles on the dashboard -
 * are the ones pushed down the list by it.
 *
 * PM10 and PM4.0 are still acquired, still stored and still exported. They are
 * not drawn either - see [PlottedSeries] - so the readout and the curves now
 * name the same two series, which is what the crosshair should have been saying
 * all along.
 */
private val READOUT_ORDER = listOf("pm2_5", "pm1_0").filter { it in PlottedSeries.FIELDS }

// Smallest PM value a log axis will resolve, ug/m3. Below this the reading is
// past what the sensor meaningfully distinguishes from zero.
private const val LOG_FLOOR = 0.01

// Alert red. Deliberately not from the theme: this must read as a warning in
// both light and dark, and must not collide with any series colour.
internal val ALERT_COLOR = Color(0xFFFF5252)

// The excursion region's wash - see AlertFill.kt for what it marks. The alpha
// is the whole design: pale enough that gridlines, the other series and an
// overlapping fill all stay readable through it, because it is context for the
// curve above it, not a warning of its own. The dashed line and the alert
// itself carry the warning.
internal val ALERT_FILL_COLOR = ALERT_COLOR.copy(alpha = 0.10f)

/**
 * Native line chart of the four PM series over time, with pinch-to-zoom and
 * drag-to-pan. Pure rendering of the supplied [ChartData]; gestures report
 * intent via [onZoom] (span scale factor) and [onPan] (seconds).
 *
 * The y axis has gestures of its own: [onPanY] carries a fraction of the plot
 * height, positive downwards, and [onZoomY] a scale factor for the range
 * together with the point to scale about - a fraction of the range measured from
 * its bottom, so 0 holds the bottom still and 1 holds the top. They are reached
 * two ways - from the gutter the tick values sit in, where a lone finger drags
 * the range and two pinch it, and from the plot itself, where a **vertical**
 * pinch scales the axis, a level one still scales time, and a two-finger drag
 * shifts the range. Which of the two a pinch on the plot is gets settled from
 * how the fingers are arranged and then held for the rest of the gesture; see
 * [PinchArbiter] for why it is not re-decided per frame, and why a pinch that
 * owns the y axis leaves time alone entirely.
 *
 * ## Two fingers to shift the y axis, always
 *
 * Time shifts under one finger when the crosshair cannot be moved by touch -
 * switched off, or held by any pin - and needs two when it can, because with a
 * movable crosshair the single finger is already spoken for.
 * The y axis is stricter: **two fingers, whatever the crosshair is doing.** A
 * finger put on the glass to read a value must never be understood as a request
 * to move the range out from under it, and a rule that depends on a setting is
 * one the hand has to remember the state of. So a one-finger drag on the plot
 * shifts time or moves the crosshair, and never the value axis.
 *
 * Shifting and scaling then share the same two fingers, and [isScaling] is what
 * keeps them apart: a frame whose finger spread barely changed is a drag and
 * shifts the range, one whose spread plainly changed is a pinch and only scales
 * it. Both land in the same temporary override in [ChartView], released the same
 * way.
 *
 * ## Gating every gesture at once
 *
 * All touch handling on the chart - inspect, time pan and zoom, the gutter, and
 * the vertical pinch - lives inside the single `awaitEachGesture` block below,
 * and that block is installed through [chartGestures]. [graphLocked] therefore
 * turns off the whole of it in one place: no handler here consults the flag, and
 * one added later is gated by going where the others are.
 */
@Composable
internal fun PmChart(
    render: ChartRender,
    modifier: Modifier = Modifier,
    colors: Map<String, Color> = SeriesColors.color,
    onZoom: (Double) -> Unit = {},
    onPan: (Double) -> Unit = {},
    onZoomY: (factor: Double, anchor: Double) -> Unit = { _, _ -> },
    onPanY: (Double) -> Unit = {},
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onWidthChanged: (Int) -> Unit = {},
    onInspect: (Double?) -> Unit = {},
    /**
     * Whether a single finger on the plot belongs to the crosshair. True only
     * in the free-cursor state with the crosshair enabled - see
     * [cursorMovableByTouch]. False, a lone finger pans, exactly as it does
     * with the crosshair switched off: a pinned cursor must not be movable, or
     * dismissable, by anything the glass hears.
     */
    cursorMovable: Boolean = true,
    /** Whether the pin-to-time state is engaged; what the corner button shows. */
    cursorPinnedToTime: Boolean = false,
    onPinCursorToTime: () -> Unit = {},
    graphLocked: Boolean = false,
    /**
     * Whether Select is armed: a lone finger on the plot then draws the
     * selection instead of touching the crosshair. Two fingers still navigate,
     * the gutter still owns the y axis, and the lock still gates everything -
     * the gesture lives inside the same block as all the others.
     */
    regionSelecting: Boolean = false,
    /** The band being dragged right now, ordered; drawn via [ChartRender.selection]. */
    onRegionDrag: (Double, Double) -> Unit = { _, _ -> },
    /** The finger lifted off a real drag: this is the selection, ordered. */
    onRegionCommit: (Double, Double) -> Unit = { _, _ -> },
    /** A second finger turned the touch into a navigation: forget the drag. */
    onRegionCancel: () -> Unit = {},
    /** A plain tap while Select is armed: clear the selection. */
    onRegionTap: () -> Unit = {},
    /**
     * The plot's own controls - Follow, the graph lock, the crosshair toggle,
     * the tick-density control, the axis-hold pairs and the derived-times
     * flag's tap target. Null renders a bare plot with no chrome at all, which
     * is what previews and the gesture tests want. See [PlotChromeCallbacks]
     * and docs/PLOT_FURNITURE.md.
     */
    chrome: PlotChromeCallbacks? = null,
) {
    val data = render.data
    val inspectedEpoch = render.inspectedEpoch
    val axisColor = MaterialTheme.colorScheme.outline
    // Stronger than before: with no separate tick marks the gridlines are what
    // tie a label to a position on the plot, and at 0.4 alpha they were barely
    // there. Minor lines stay faint so the labelled ones still read as primary.
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val latest by rememberUpdatedState(data)
    val onStart by rememberUpdatedState(onGestureStart)
    val onEnd by rememberUpdatedState(onGestureEnd)
    val onWidth by rememberUpdatedState(onWidthChanged)
    val inspect by rememberUpdatedState(onInspect)
    val cursorMoves by rememberUpdatedState(cursorMovable)
    val cursorAt by rememberUpdatedState(inspectedEpoch)
    val panY by rememberUpdatedState(onPanY)
    val zoomY by rememberUpdatedState(onZoomY)
    val selectMode by rememberUpdatedState(regionSelecting)
    val regionDragTo by rememberUpdatedState(onRegionDrag)
    val regionCommit by rememberUpdatedState(onRegionCommit)
    val regionCancel by rememberUpdatedState(onRegionCancel)
    val regionTap by rememberUpdatedState(onRegionTap)

    // The canvas' own size, held so the flag's tap target and the gesture
    // exclusions can be computed in the same pixels the flag is drawn in.
    var chartPx by remember { mutableStateOf(IntSize.Zero) }
    // Where the derived-times flag's ink is, or null while it is not drawn -
    // suppressed while inspecting and while the selection statistics own the
    // strip, exactly as the drawn text is in [drawPmChart].
    val flagPx: Rect? = if (
        chrome != null && render.timeFlag != null && data != null &&
        inspectedEpoch == null &&
        (render.regionLines.isEmpty() || render.selection == null) &&
        chartPx != IntSize.Zero
    ) {
        flagInkRect(topStripPlanFor(render, chartPx))
    } else {
        null
    }
    val chromed by rememberUpdatedState(chrome != null)
    val flagZone by rememberUpdatedState(flagPx)

    Box(modifier = modifier.onSizeChanged { chartPx = it; onWidth(it.width) }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // THE GATE. Every gesture the plot understands is installed
                // inside this block and nowhere else - inspect, the time pan and
                // zoom, the y-axis gutter, the vertical pinch - so locking the
                // graph takes all of them away at once, and one added later is
                // covered without being told. See [chartGestures].
                .chartGestures(graphLocked) {
                    pointerInput(Unit) {
                        // One finger inspects, two fingers move - but which of the two
                        // a touch is stays open until the touch says so.
                        //
                        // Low-power mode records a point every 15 minutes, so the only
                        // way to be confident it is working is to read the actual
                        // timestamps off the plot. That has to be a single-finger
                        // action, which means panning must give up the single finger
                        // entirely - otherwise every inspection would drag the data out
                        // from under the reader.
                        //
                        // The cost of that split is that a zoom or a pan also begins
                        // with one finger, and acting on that finger straight away
                        // moved the crosshair on the way into every two-finger gesture.
                        // So nothing happens until the gesture has declared itself:
                        // see [ChartTouchArbiter].
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            // A press on any on-plot control belongs to that
                            // control - a sibling drawn over this canvas - so
                            // the plot neither claims it nor dismisses the
                            // crosshair over it. The corner button has always
                            // had this; the rest of the chrome gets the same.
                            val onControl =
                                inChromeControl(first.position, size, chromed, latest, flagZone)
                            // The y axis owns its own gutter. Landing there is
                            // unambiguous - there is nothing else to do in that
                            // strip - so the gesture needs no arbitration at all.
                            if (inYAxisGutter(first.position, size) && !onControl) {
                                first.consume()
                                // A movable crosshair yields to the gutter touch; a
                                // pinned one is untouchable by anything the glass
                                // hears, gutter included.
                                if (cursorMoves) inspect(null)
                                dragYAxis(first, size, onStart, onEnd, panY, zoomY)
                                return@awaitEachGesture
                            }
                            val inPlot = inPlotArea(first.position, size) && !onControl
                            // Whether this touch, if it stays a lone finger, draws
                            // the selection. Captured at the touch's start, like
                            // everything else about it, so the mode changing
                            // mid-gesture cannot change what the gesture means.
                            val selecting = selectMode && inPlot && latest != null
                            // Where the crosshair was before this touch, so a gesture
                            // that turns out to be a navigation can leave it as it
                            // found it.
                            val cursorBefore = cursorAt
                            // A selection drag claims the lone finger exactly the way
                            // an inspection does, so it rides the same arbitration:
                            // same slop, same hold, same second-finger takeover.
                            val arbiter = ChartTouchArbiter(
                                slopPx = viewConfiguration.touchSlop,
                                inspectAllowed = (cursorMoves || selecting) && inPlot,
                            )
                            arbiter.down(first.uptimeMillis, first.position.x, first.position.y)

                            if (inPlot) {
                                // Claim the touch now, whatever it turns out to be, so
                                // the dashboard's dismiss-on-tap handler does not clear
                                // the crosshair out from under a pinch.
                                first.consume()
                            } else if (cursorMoves && !onControl) {
                                // Tapping the margins (axis labels, below the axis) is
                                // the "off the plot" gesture that dismisses the readout.
                                // Only for a movable crosshair: a pinned one is not
                                // affected by touch anywhere on the graph.
                                //
                                // Except on the corner button, which sits in those
                                // margins and is *about* the crosshair. The Canvas
                                // still sees that press - it is a sibling of the
                                // button, not its parent - and dismissing on it would
                                // clear the crosshair on finger-down, so by the time
                                // the button fired on finger-up there would be nothing
                                // left to pin and the button itself would have gone.
                                inspect(null)
                            }

                            var pos = first.position
                            var down = true
                            var moved = false        // a navigation actually moved the view
                            var cursorMoved = false  // this gesture moved the crosshair
                            var undone = false
                            // One pinch, one axis, for as long as the fingers are down.
                            val pinch = PinchArbiter()
                            // And one drag, one answer to whether it is a vertical
                            // one - see [YDragLatch].
                            val yDrag = YDragLatch(viewConfiguration.touchSlop)

                            // Wait for the gesture to declare itself. It settles on a
                            // second finger, on crossing touch slop, on the finger
                            // lifting, or - last - on the hold expiring.
                            if (arbiter.role == ChartTouchRole.UNDECIDED) {
                                val settled = withTimeoutOrNull(CURSOR_HOLD_MS) {
                                    while (arbiter.role == ChartTouchRole.UNDECIDED) {
                                        val event = awaitPointerEvent()
                                        val self = event.changes.firstOrNull { it.id == first.id }
                                        if (self != null) pos = self.position
                                        if (self == null || !self.pressed) {
                                            down = false
                                            arbiter.lifted()
                                        } else {
                                            arbiter.update(
                                                self.uptimeMillis, self.position.x, self.position.y,
                                                event.changes.count { it.pressed },
                                            )
                                        }
                                    }
                                    arbiter.role
                                }
                                if (settled == null) arbiter.held()
                            }

                            // The lone finger's meaning, settled. In Select it draws
                            // the band and leaves the crosshair alone - a pinned
                            // cursor stays exactly where its pin holds it.
                            var regionDrag: RegionDrag? = null
                            if (arbiter.role == ChartTouchRole.INSPECT) {
                                if (selecting) {
                                    // Anchored where the finger landed, not where
                                    // it was when the touch settled: the band
                                    // starts at the point the user touched.
                                    timeAt(first.position.x, latest, size)?.let {
                                        val drag = RegionDrag(
                                            viewConfiguration.touchSlop,
                                            first.position.x, first.position.y, it,
                                        )
                                        timeAt(pos.x, latest, size)?.let { now ->
                                            drag.update(pos.x, pos.y, now)
                                        }
                                        if (drag.dragged) regionDragTo(drag.lo, drag.hi)
                                        regionDrag = drag
                                    }
                                } else {
                                    inspect(epochAt(pos.x, latest, size))
                                    cursorMoved = true
                                }
                            }

                            while (down) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val self = event.changes.firstOrNull { it.id == first.id }
                                if (self != null) pos = self.position
                                val role = arbiter.update(
                                    self?.uptimeMillis ?: 0L, pos.x, pos.y, pressed,
                                )
                                val d = latest
                                if (role == ChartTouchRole.NAVIGATE) {
                                    // A second finger arriving after the crosshair had
                                    // already committed makes this a pinch after all,
                                    // so put the crosshair back rather than leave it
                                    // dragged to wherever the first finger landed.
                                    if (cursorMoved && !undone) {
                                        inspect(cursorBefore)
                                        undone = true
                                    }
                                    // And a selection drag is undone the same way and
                                    // for the same reason: the touch turned out to be
                                    // a navigation, so it must not have selected
                                    // anything on the way in.
                                    regionDrag?.let {
                                        regionCancel()
                                        regionDrag = null
                                    }
                                    if (d != null) {
                                        val plotW = max(1f, size.width - LEFT_PAD - RIGHT_PAD)
                                        val plotH = max(1f, size.height - TOP_PAD - BOTTOM_PAD)
                                        if (pressed >= 2) {
                                            val s = event.pinchSpreads()
                                            val zoom = pinch.scale(
                                                s.prevX, s.prevY, s.x, s.y, centreY = s.centreY,
                                            )
                                            // Dead-zone: ignore the incidental zoom a
                                            // two-finger drag reports. The same test
                                            // decides that this frame is a scale and
                                            // therefore not a y-axis shift.
                                            val scaling = isScaling(zoom)
                                            if (scaling) {
                                                if (!moved) onStart()
                                                // Spreading the fingers zooms in, so the
                                                // range narrows: the factor is the
                                                // reciprocal on either axis.
                                                val factor = 1.0 / zoom.toDouble()
                                                if (pinch.axis == PinchAxis.VALUE) {
                                                    // About the fingers, not about the
                                                    // middle of the view: that is what
                                                    // lets one end be held still while
                                                    // the other moves. See [ChartView.zoomY].
                                                    zoomY(
                                                        factor,
                                                        anchorFraction(
                                                            pinch.focusY ?: s.centreY,
                                                            TOP_PAD, TOP_PAD + plotH,
                                                        ),
                                                    )
                                                } else {
                                                    onZoom(factor)
                                                }
                                                moved = true
                                            }
                                            // Time pans with the fingers - unless they
                                            // have claimed the y axis. A vertical pinch
                                            // drags the plot sideways by however much
                                            // the hand happened to drift, and scrolling
                                            // the record while setting its height is a
                                            // second thing the user did not ask for.
                                            val pan = event.calculatePan()
                                            if (pan.x != 0f && pinch.axis != PinchAxis.VALUE) {
                                                if (!moved) onStart()
                                                onPan(-(pan.x / plotW).toDouble() * d.spanSeconds)
                                                moved = true
                                            }
                                            // And the y axis shifts with them - two
                                            // fingers, always, whatever the crosshair is
                                            // doing, because a finger placed to read a
                                            // value must never be read as a request to
                                            // move the range out from under it.
                                            //
                                            // Two things have to be true of the frame.
                                            // It must not have scaled: a pinch is never
                                            // made by two fingers moving in perfect
                                            // opposition, so its centre wanders, and
                                            // following that would slide the range while
                                            // the user was only setting its height. And
                                            // the drag has to have declared itself a
                                            // vertical one, so the drift of a sideways
                                            // scroll through time does not take the value
                                            // axis off autoscale on the way. Hence the
                                            // latch is fed only by frames that could
                                            // actually shift - a pinch's wander is not
                                            // travel and must not arm anything.
                                            if (!scaling &&
                                                yDrag.travelled(pan.x, pan.y) &&
                                                pan.y != 0f
                                            ) {
                                                if (!moved) onStart()
                                                panY((pan.y / plotH).toDouble())
                                                moved = true
                                            }
                                        } else if (pressed == 1 && !cursorMoves && self != null) {
                                            // With the crosshair switched off - or
                                            // pinned, which takes the single finger's
                                            // claim on it away just the same - there is
                                            // nothing for that finger to disturb, so it
                                            // pans directly.
                                            //
                                            // Time only. One finger never shifts the y
                                            // axis, here or anywhere: see the two-finger
                                            // branch above for why.
                                            val dx = self.positionChange().x
                                            if (dx != 0f) {
                                                if (!moved) onStart()
                                                onPan(-(dx / plotW).toDouble() * d.spanSeconds)
                                                moved = true
                                            }
                                        }
                                        if (moved) {
                                            event.changes.forEach {
                                                if (it.positionChanged()) it.consume()
                                            }
                                        }
                                    }
                                } else if (pressed == 1) {
                                    val drag = regionDrag
                                    if (drag != null) {
                                        // The lone finger is drawing the selection.
                                        // The x is clamped to the plot inside
                                        // [timeAt], so wandering off the edge holds
                                        // the band at the view's end rather than
                                        // selecting time that is not on screen.
                                        timeAt(pos.x, latest, size)?.let {
                                            drag.update(pos.x, pos.y, it)
                                            if (drag.dragged) regionDragTo(drag.lo, drag.hi)
                                        }
                                        event.changes.forEach {
                                            if (it.positionChanged()) it.consume()
                                        }
                                    } else if (inPlotArea(pos, size)) {
                                        // Still one finger, and it owns the crosshair.
                                        inspect(epochAt(pos.x, latest, size))
                                        cursorMoved = true
                                        event.changes.forEach {
                                            if (it.positionChanged()) it.consume()
                                        }
                                    }
                                }
                                down = event.changes.any { it.pressed }
                            }
                            if (moved) onEnd()
                            // The finger lifted with the drag still in force: a real
                            // drag is the selection, and lifting is only ever how a
                            // drag *ends* - the zoom stays a button, so the captain
                            // can adjust or abandon before committing to it. A plain
                            // tap clears; a touch that moved but never sideways
                            // (a fidget) does neither.
                            regionDrag?.let { drag ->
                                when {
                                    drag.dragged && drag.hi > drag.lo ->
                                        regionCommit(drag.lo, drag.hi)
                                    !drag.travelled -> regionTap()
                                    else -> regionCancel()
                                }
                            }
                        }
                    }
                },
        ) {
            drawPmChart(render, colors, axisColor, gridColor, labelColor)
        }

        // Nothing to pin without a crosshair, so nothing to press: the corner is
        // empty until there is one on the plot.
        if (inspectedEpoch != null) {
            CursorPinToggle(
                pinned = cursorPinnedToTime,
                onToggle = onPinCursorToTime,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        // The rest of the plot's own controls. Siblings of the canvas like the
        // pin button, and after it, so they stay live while the locked canvas
        // swallows the plot's gestures - which is what lets the lock icon
        // release the lock it set.
        if (chrome != null) {
            PlotChromeOverlays(
                render = render,
                chrome = chrome,
                graphLocked = graphLocked,
                flagRect = flagPx,
            )
        }
    }
}

/**
 * The on-plot controls, each at the thing it affects: Follow and the graph
 * lock at the top right where the newest reading arrives, the crosshair
 * toggle beside its family's pin button, the tick-density control and the
 * x hold on the time axis, the y hold on the value axis, and the tap target
 * over the drawn derived-times flag. docs/PLOT_FURNITURE.md is the map.
 */
@Composable
private fun BoxScope.PlotChromeOverlays(
    render: ChartRender,
    chrome: PlotChromeCallbacks,
    graphLocked: Boolean,
    flagRect: Rect?,
) {
    val d = LocalDensity.current
    val data = render.data

    Row(
        horizontalArrangement = Arrangement.spacedBy(with(d) { TOP_ICON_GAP.toDp() }),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(
                top = with(d) { TOP_ICON_TOP.toDp() },
                end = with(d) { (RIGHT_PAD + CORNER_BUTTON_MARGIN).toDp() },
            ),
    ) {
        FollowIcon(chrome.follows, chrome.onToggleFollows, TOP_ICON_SIDE)
        GraphLockIcon(graphLocked, chrome.onToggleGraphLock, TOP_ICON_SIDE)
    }

    CrosshairToggleIcon(
        enabled = chrome.cursorEnabled,
        onToggle = chrome.onToggleCursor,
        sidePx = CORNER_BUTTON_SIDE,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(
                start = with(d) { (2 * CORNER_BUTTON_MARGIN + CORNER_BUTTON_SIDE).toDp() },
                bottom = with(d) { CORNER_BUTTON_MARGIN.toDp() },
            ),
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(with(d) { HOLD_ICON_GAP.toDp() }),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(
                end = with(d) { CORNER_BUTTON_MARGIN.toDp() },
                bottom = with(d) { CORNER_BUTTON_MARGIN.toDp() },
            ),
    ) {
        if (data != null && data.xControl != XAxisControl.FOLLOWING) {
            AxisHoldIcons(
                heading = AxisHoldLabels.X_HEADING,
                releaseLabel = AxisHoldLabels.RELEASE_X,
                locked = data.xControl == XAxisControl.LOCKED,
                note = if (data.xControl == XAxisControl.LOCKED) {
                    AxisHoldLabels.HELD_AGAINST_TOUCH
                } else {
                    AxisHoldLabels.X_HELD
                },
                onSetLocked = chrome.onSetXLocked,
                onRelease = chrome.onReleaseX,
                sidePx = HOLD_ICON_SIDE,
            )
        }
        if (data != null && data.tickStepSeconds > 0) {
            TickDensityButton(
                stepLabel = compactDuration(data.tickStepSeconds.toDouble()),
                engaged = data.tickDensity > 0,
                onCycle = chrome.onCycleTicks,
                sidePx = CORNER_BUTTON_SIDE,
            )
        }
    }

    if (data != null &&
        (data.yControl == YAxisControl.OVERRIDDEN || data.yControl == YAxisControl.LOCKED)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(with(d) { HOLD_ICON_GAP.toDp() }),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = with(d) { ((LEFT_PAD - HOLD_ICON_SIDE) / 2f).toDp() },
                    top = with(d) { Y_HOLD_TOP.toDp() },
                ),
        ) {
            AxisHoldIcons(
                heading = AxisHoldLabels.Y_HEADING,
                releaseLabel = AxisHoldLabels.RELEASE_Y,
                locked = data.yControl == YAxisControl.LOCKED,
                note = if (data.yControl == YAxisControl.LOCKED) {
                    AxisHoldLabels.HELD_AGAINST_TOUCH
                } else {
                    AxisHoldLabels.Y_HELD
                },
                onSetLocked = chrome.onSetYLocked,
                onRelease = chrome.onReleaseY,
                sidePx = HOLD_ICON_SIDE,
            )
        }
    }

    // The derived-times flag's tap target, over the ink [drawPmChart] put down.
    // The flag is a caveat about whether the axis' timestamps can be trusted,
    // and its tap is the only road to placing a run nothing could place - so
    // the drawn text answers the finger exactly as the old chip-row flag did.
    if (flagRect != null && render.timeFlag != null) {
        val unplaced = render.timeFlag.endsWith("unplaced")
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(flagRect.left.toInt(), flagRect.top.toInt()) }
                .size(
                    width = with(d) { flagRect.width.toDp() },
                    height = with(d) { flagRect.height.toDp() },
                )
                .clickable(onClick = chrome.onTimeFlag)
                .semantics {
                    contentDescription = if (unplaced) {
                        "Some runs could not be placed in time. Tap to place them."
                    } else {
                        "Times on this plot are derived from the sensor's own clock. " +
                            "Tap for the explanation."
                    }
                },
        )
    }
}

/**
 * The graph's single gesture gate: [gestures] is what the plot listens for, and
 * locking the graph replaces the lot of it with a touch that goes nowhere.
 *
 * ## Why one gate rather than a check in each handler
 *
 * "Locked" has to mean *nothing on the plot responds* - not "the handlers I
 * remembered to ask do not respond". A per-handler check is a rule that has to be
 * re-obeyed by every gesture anyone adds afterwards, and the one that forgets is
 * the one that loses the crosshair. Installing every handler inside this block
 * instead makes the lock structural: a gesture that is not written here does not
 * exist, and one that is written here is covered by construction, whoever adds it
 * and whenever. The vertical pinch that scales the y axis is the case in point -
 * it was written after the lock, needed no knowledge of it, and is gated anyway
 * because it went where every other plot gesture goes. **Add new plot gestures
 * inside the `chartGestures` block in [PmChart]; nothing else needs to know about
 * the lock.**
 *
 * ## Why locking absorbs the touch instead of just dropping the handlers
 *
 * Simply not installing them would leave the press unconsumed, and an unconsumed
 * press on the dashboard is the gesture that *clears the crosshair* (see
 * [Modifier.dismissCursorOnTapAway]). A lock whose whole purpose is "an accidental
 * touch must not cost me the cursor I set up" would then destroy the cursor on the
 * first stray finger - the exact fault it exists to prevent, delivered by the fix.
 * So the locked graph swallows the whole gesture: every pointer, every move, until
 * the last finger lifts.
 *
 * The lock gates the plot surface only. The controls drawn over it - the corner
 * Pin button - are buttons, not the plot, and stay live, as does everything off
 * the chart.
 */
private fun Modifier.chartGestures(
    locked: Boolean,
    gestures: Modifier.() -> Modifier,
): Modifier = if (locked) absorbGraphTouches() else gestures()

/**
 * Swallows a whole gesture so nothing - here or above - acts on it.
 *
 * ## The key, and why it is not `Unit`
 *
 * `pointerInput` decides whether to restart its handler by comparing its **keys**,
 * never the lambda: two lambdas are different objects on every recomposition, so
 * comparing them would restart every gesture handler every frame. Swapping this
 * modifier for the plot's own is therefore invisible to that comparison if the two
 * agree on their keys - and with `Unit` on both sides they did. Locking worked
 * (nothing had run yet at that point), releasing did not: the chain said gestures,
 * the live handler was still this one, and the plot stayed dead until something
 * else forced it to be rebuilt.
 *
 * A key of its own is what makes the swap visible in both directions. Caught by
 * [GraphLockTest], which flips the lock on a composition already running rather
 * than starting one on each side of it.
 */
private fun Modifier.absorbGraphTouches(): Modifier = pointerInput(GRAPH_LOCK_KEY) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** Distinguishes the locked graph's handler from any the plot installs. */
private const val GRAPH_LOCK_KEY = "graph-locked"

/**
 * Pins the crosshair to the moment it is on, in the empty corner under the y axis.
 *
 * The plot's own handle on the pin-to-time state - the pin reached for with a
 * hand already on the glass, because the crosshair has just slid off the spike
 * being watched and a trip through the control panel to answer that is a trip
 * too many. The full pin family - to time, from either edge, and Snap to peak -
 * lives in the Cursor group's chips; this button engages and releases exactly
 * the same pin-to-time state the "Pin time" chip does (see [CursorPin]).
 *
 * Offered in **every** view. It used to grey itself out wherever the view's
 * range did not run to now, which meant it worked only when new data happened
 * to sit at the far right of the graph - the reported defect. Pinning the
 * cursor to the point it is lined up with is always meaningful now: besides
 * anchoring it to its moment, an engaged pin is what makes touch pan instead
 * of moving the cursor, and that choice exists in a frozen view too.
 *
 * The corner it sits in is the one part of the chart that never carries a
 * label: left of the time ticks and below the value ticks.
 *
 * Drawn rather than taken from a Material icon set, in keeping with the rest of
 * this app's glyphs, and so it can say exactly what it controls: the vertical
 * line the crosshair actually is, with a tack driven through it when it is
 * pinned to its moment.
 */
@Composable
private fun CursorPinToggle(
    pinned: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same species of control as Pin and Auto, and styled to say so: a bare
    // glyph in the corner reads as a stray mark on the plot rather than
    // something to press.
    val scheme = MaterialTheme.colorScheme
    val glyph = if (pinned) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val fill = if (pinned) scheme.secondaryContainer else Color.Transparent
    val border = if (pinned) scheme.secondaryContainer else scheme.outline
    // Placed in the chart's own pixel geometry, converted here at the last
    // moment - see [CORNER_BUTTON_SIDE] for what a dp box beside a px gutter did.
    val d = LocalDensity.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = fill,
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .padding(
                start = with(d) { CORNER_BUTTON_MARGIN.toDp() },
                bottom = with(d) { CORNER_BUTTON_MARGIN.toDp() },
            )
            .size(with(d) { CORNER_BUTTON_SIDE.toDp() })
            .toggleable(value = pinned, role = Role.Switch, onValueChange = { onToggle() })
            .semantics {
                contentDescription = if (pinned) {
                    "Crosshair pinned to its moment and travelling with the data. " +
                        "Tap to unpin it."
                } else {
                    "Crosshair not pinned. Tap to pin it to the data point it is on."
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(with(d) { (CORNER_BUTTON_SIDE * 0.5f).toDp() })) {
                val w = size.width
                val h = size.height
                val stroke = h * 0.10f
                val cx = w / 2f
                val cy = h * 0.42f
                // What it controls, drawn: the vertical line the crosshair is,
                // with the sample it is reading marked on it.
                drawLine(glyph, Offset(cx, 0f), Offset(cx, h), stroke)
                if (pinned) {
                    // A tack through the sample - the crosshair fixed to that
                    // moment in the data rather than to a place on the glass.
                    drawCircle(glyph, h * 0.18f, Offset(cx, cy))
                    drawLine(
                        glyph,
                        Offset(cx, cy),
                        Offset(cx + w * 0.34f, cy - h * 0.34f),
                        stroke * 1.2f,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(glyph, h * 0.10f, Offset(cx + w * 0.38f, cy - h * 0.38f))
                } else {
                    // Open, and free to slide: the data passes under the ring.
                    drawCircle(glyph, h * 0.18f, Offset(cx, cy), style = Stroke(width = stroke))
                }
            }
        }
    }
}

/*
 * Where the corner button sits, in the same canvas pixels the axes are drawn in.
 * Named rather than written twice because the plot's own touch handling has to
 * know the same rectangle: one of them moving without the other is a press that
 * neither the button nor the plot handles the way it looks like it should.
 *
 * ## Why pixels and not dp
 *
 * The button used to be a 44dp box with 8dp/10dp margins, sat in a gutter
 * measured in raw canvas pixels - and those are two different rectangles on any
 * screen whose density is not 1, which is every screen. On the tablet, at
 * density 3, the 54dp the button reserved below it came to 162px against a
 * [BOTTOM_PAD] of 176px, so its top edge landed 4px inside the bottom y-axis
 * label and sliced through "0.5". It was correct at density 2 and wrong at 3
 * with nothing in either place saying so.
 *
 * Sizing it from the same numbers the axis is drawn with is what keeps the two
 * agreeing at every density: it fills the corner the axes leave empty - left of
 * the time ticks, below the deepest ink the value labels put under the axis -
 * and cannot grow into either of them.
 */
private const val CORNER_BUTTON_MARGIN = 12f
private val CORNER_BUTTON_SIDE = minOf(
    // Clear of the y axis on the right.
    LEFT_PAD - 2 * CORNER_BUTTON_MARGIN,
    // Clear of the lowest value label above, which centres on the axis itself.
    BOTTOM_PAD - inkBelowCentre(TEXT_TICK) - 2 * CORNER_BUTTON_MARGIN,
)

/*
 * The rest of the on-plot chrome, in the same canvas pixels and for the same
 * reason the corner button is: a dp box beside a px gutter is a different
 * rectangle at every density. The full inventory of what sits where, what it
 * costs and who yields to whom is docs/PLOT_FURNITURE.md - update it with any
 * change here.
 *
 * Two sizes on purpose. The always-present controls (crosshair, tick density,
 * Follow, the graph lock) take the corner button's proven side; the transient
 * hold pairs are a step smaller, which both tells them apart as the visitors
 * they are and halves what they cost the axis they appear on.
 */
private val TOP_ICON_SIDE = CORNER_BUTTON_SIDE
private const val TOP_ICON_GAP = 16f
private const val TOP_ICON_TOP = 12f
/** What right-anchored top-strip text keeps clear of the Follow/Lock pair. */
private val TOP_RIGHT_RESERVE = 2 * TOP_ICON_SIDE + TOP_ICON_GAP + 2 * CORNER_BUTTON_MARGIN
private const val HOLD_ICON_SIDE = 96f
private const val HOLD_ICON_GAP = 12f
/** Below the top strip's first line of ink, where the y labels rarely are. */
private const val Y_HOLD_TOP = 80f

/** The crosshair toggle's slot: the bottom margin, right of the pin button. */
private fun crosshairSlot(size: IntSize): Rect {
    val left = 2 * CORNER_BUTTON_MARGIN + CORNER_BUTTON_SIDE
    val bottom = size.height - CORNER_BUTTON_MARGIN
    return Rect(left, bottom - CORNER_BUTTON_SIDE, left + CORNER_BUTTON_SIDE, bottom)
}

/** The tick-density control's slot: the bottom margin's right corner. */
private fun tickSlot(size: IntSize): Rect {
    val right = size.width - CORNER_BUTTON_MARGIN
    val bottom = size.height - CORNER_BUTTON_MARGIN
    return Rect(right - CORNER_BUTTON_SIDE, bottom - CORNER_BUTTON_SIDE, right, bottom)
}

/** Both x-hold icons plus their gaps, sliding in left of the tick control. */
private fun xHoldSlot(size: IntSize): Rect {
    val tick = tickSlot(size)
    val right = tick.left - HOLD_ICON_GAP
    val bottom = size.height - CORNER_BUTTON_MARGIN
    return Rect(
        right - 2 * HOLD_ICON_SIDE - HOLD_ICON_GAP, bottom - CORNER_BUTTON_SIDE, right, bottom,
    )
}

/** Both y-hold icons, a vertical pair in the gutter at the axis they hold. */
private fun yHoldSlot(size: IntSize): Rect {
    val left = (LEFT_PAD - HOLD_ICON_SIDE) / 2f
    return Rect(
        left, Y_HOLD_TOP,
        left + HOLD_ICON_SIDE, Y_HOLD_TOP + 2 * HOLD_ICON_SIDE + HOLD_ICON_GAP,
    )
}

/** The Follow/Lock pair's slot: the plot's top-right corner. */
private fun topIconsSlot(size: IntSize): Rect {
    val right = size.width - RIGHT_PAD - CORNER_BUTTON_MARGIN
    return Rect(
        right - 2 * TOP_ICON_SIDE - TOP_ICON_GAP, TOP_ICON_TOP,
        right, TOP_ICON_TOP + TOP_ICON_SIDE,
    )
}

/** How far in from the plot's left edge the time labels may start: clear of
 *  the crosshair toggle's slot. */
private fun xLabelLeftBound(): Float =
    3 * CORNER_BUTTON_MARGIN + 2 * CORNER_BUTTON_SIDE

/** ...and where they must stop on the right: clear of the tick control, and of
 *  the x-hold pair while one is engaged. */
private fun xLabelRightBound(size: IntSize, xHeld: Boolean): Float {
    val edge = if (xHeld) xHoldSlot(size).left else tickSlot(size).left
    return edge - CORNER_BUTTON_MARGIN
}

/**
 * True if [position] is on any on-plot control, the corner pin button included.
 *
 * The canvas is every control's sibling, so it sees their presses too; a press
 * on a control must neither dismiss the crosshair nor start a gesture - the
 * same treatment `inCornerButton` has always had, extended to the rest of the
 * chrome. State-aware, because a rect only excludes while a control actually
 * occupies it: an empty slot must stay ordinary plot or margin.
 */
private fun inChromeControl(
    position: Offset,
    size: IntSize,
    chromed: Boolean,
    data: ChartData?,
    flagRect: Rect?,
): Boolean {
    if (inCornerButton(position, size)) return true
    if (!chromed) return false
    if (crosshairSlot(size).contains(position)) return true
    if (tickSlot(size).contains(position)) return true
    if (topIconsSlot(size).contains(position)) return true
    if (data != null && data.xControl != XAxisControl.FOLLOWING &&
        xHoldSlot(size).contains(position)
    ) {
        return true
    }
    if (data != null &&
        (data.yControl == YAxisControl.OVERRIDDEN || data.yControl == YAxisControl.LOCKED) &&
        yHoldSlot(size).contains(position)
    ) {
        return true
    }
    return flagRect?.contains(position) == true
}

/**
 * The plot's margins and what is drawn into them, in canvas pixels.
 *
 * Exposed for `ChartMarginTest`, which is the only thing that can tell whether
 * these numbers still add up: they are a set of offsets and type sizes that have
 * to leave room for each other, and every one of them looks reasonable alone.
 * The two faults here were of exactly that kind - a caption drawn 192px into a
 * 176px margin, and a button in dp overlapping a label in px - and neither is
 * visible from any single line.
 */
internal object ChartGeometry {
    val leftPad = LEFT_PAD
    val topPad = TOP_PAD
    val bottomPad = BOTTOM_PAD
    val tickTextSize = TEXT_TICK
    val captionTextSize = TEXT_CAPTION
    /** Centres of the two things drawn below the axis, measured from it. */
    val xTickMid = X_TICK_MID
    val xDateMid = X_DATE_MID
    val cornerMargin = CORNER_BUTTON_MARGIN
    val cornerSide = CORNER_BUTTON_SIDE
}

/** Centre line of top-strip line [line] (1-based), measured from the plot's top. */
private fun stripLineMid(line: Int): Float = VIEW_NOTE_MID + (line - 1) * READOUT_LINE

/** This frame's top-strip plan, measured with the real text widths. */
private fun topStripPlanFor(render: ChartRender, size: IntSize): TopStripPlan =
    topStripPlan(
        plotLeft = LEFT_PAD,
        plotRight = size.width - RIGHT_PAD,
        unitWidth = measureText(SeriesColors.unit, TEXT_NOTE),
        flagWidth = render.timeFlag?.let { measureText("◷ $it", TEXT_NOTE) },
        infoWidth = if (render.viewInfo.isEmpty()) 0f
        else measureText(render.viewInfo, TEXT_VIEW_INFO),
        rightReserve = TOP_RIGHT_RESERVE,
    )

/** The flag's ink band in canvas px, padded to a usable touch height. */
private fun flagInkRect(plan: TopStripPlan): Rect? {
    val line = plan.flagLine ?: return null
    val mid = TOP_PAD + stripLineMid(line)
    return Rect(plan.flagX, mid - 50f, plan.flagX + plan.flagWidth, mid + 50f)
}

/** True if [position] is on the corner button, in the margin below the y axis. */
private fun inCornerButton(position: Offset, size: IntSize): Boolean {
    val bottom = size.height - CORNER_BUTTON_MARGIN
    return position.x >= CORNER_BUTTON_MARGIN &&
        position.x <= CORNER_BUTTON_MARGIN + CORNER_BUTTON_SIDE &&
        position.y >= bottom - CORNER_BUTTON_SIDE && position.y <= bottom
}

/**
 * The y axis' own gutter: a drag shifts the range, two fingers pinch it wider or
 * narrower about the point between them.
 *
 * Reported as fractions of the plot height rather than in ug/m3, because the
 * renderer knows how tall the plot is and the model knows what the axis spans,
 * and neither should have to learn the other's units.
 *
 * ## Why one finger still shifts the axis *here*
 *
 * On the plot a shift takes two fingers and always will: one finger there
 * belongs to the crosshair, and a touch made to read a value must never be read
 * as a request to move the range out from under it. This strip has no crosshair
 * in it and nothing else to mean - landing here is already unambiguous, which is
 * why the gesture needs no arbitration at all - so the rule that a lone finger
 * is spoken for does not reach it, and taking the gutter's existing one-finger
 * drag away would remove a working control to answer a problem it does not have.
 * The plot is where the promise is kept; see the two-finger branch in [PmChart].
 */
private suspend fun AwaitPointerEventScope.dragYAxis(
    first: PointerInputChange,
    size: IntSize,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onPanY: (Double) -> Unit,
    onZoomY: (Double, Double) -> Unit,
) {
    val plotTop = TOP_PAD
    val plotH = max(1f, size.height - TOP_PAD - BOTTOM_PAD)
    var moved = false
    var down = true
    // Settled on the first two-finger frame and held, exactly as the plot's own
    // pinch holds its focal point - see [PinchArbiter.focusY] for why it may not
    // be taken afresh each frame.
    var focusY: Float? = null
    while (down) {
        val event = awaitPointerEvent()
        val held = event.changes.filter { it.pressed && it.previousPressed }
        if (held.size >= 2) {
            val zoom = event.calculateZoom()
            val centreY = held.sumOf { it.position.y.toDouble() }.toFloat() / held.size
            if (focusY == null) focusY = centreY
            val scaling = isScaling(zoom)
            if (scaling) {
                if (!moved) onStart()
                onZoomY(
                    1.0 / zoom.toDouble(),
                    anchorFraction(focusY ?: centreY, plotTop, plotTop + plotH),
                )
                moved = true
            }
            // The same split the plot makes, and for the same reason: a frame
            // that scaled must not also shift, or a wandering pinch centre would
            // slide the range while the hand was only setting its height.
            val dy = event.calculatePan().y
            if (!scaling && dy != 0f) {
                if (!moved) onStart()
                onPanY((dy / plotH).toDouble())
                moved = true
            }
        } else {
            val dy = event.changes.firstOrNull { it.id == first.id }?.positionChange()?.y ?: 0f
            if (dy != 0f) {
                if (!moved) onStart()
                onPanY((dy / plotH).toDouble())
                moved = true
            }
        }
        event.changes.forEach { if (it.positionChanged()) it.consume() }
        down = event.changes.any { it.pressed }
    }
    if (moved) onEnd()
}

/**
 * How far the pressed fingers sit from their centroid along each axis, this
 * frame ([x], [y]) and last ([prevX], [prevY]), in canvas pixels - and
 * [centreY], the centroid itself, which is the point a vertical pinch scales
 * about.
 */
private class PinchSpreads(
    val prevX: Float,
    val prevY: Float,
    val x: Float,
    val y: Float,
    val centreY: Float,
)

/**
 * The spread of this event's fingers along each axis, before and after it, and
 * where they are centred vertically now.
 *
 * The measuring is [spreadOf]'s; this only picks out the coordinates to measure.
 * Only pointers that were down for both frames count: one arriving or leaving
 * moves the centre on its own, and reading that as the fingers having spread
 * would rescale the axis every time a second finger touched down.
 */
private fun PointerEvent.pinchSpreads(): PinchSpreads {
    val held = changes.filter { it.pressed && it.previousPressed }
    if (held.isEmpty()) return PinchSpreads(0f, 0f, 0f, 0f, 0f)
    var centreY = 0f
    for (c in held) centreY += c.position.y
    return PinchSpreads(
        prevX = spreadOf(FloatArray(held.size) { held[it].previousPosition.x }),
        prevY = spreadOf(FloatArray(held.size) { held[it].previousPosition.y }),
        x = spreadOf(FloatArray(held.size) { held[it].position.x }),
        y = spreadOf(FloatArray(held.size) { held[it].position.y }),
        centreY = centreY / held.size,
    )
}

/**
 * True if [position] is in the y-axis gutter: left of the plot, beside the tick
 * values, and above the time axis.
 *
 * The corner button sits in the same column, and how much of it falls inside
 * this strip depends on the display's density - its 44dp is well below the
 * bottom padding on the tablet and would overlap it on a denser screen. So the
 * button is excluded by the same rectangle the layout places it with, rather
 * than by a margin that happens to be big enough here.
 */
private fun Density.inYAxisGutter(position: Offset, size: IntSize): Boolean =
    position.x < LEFT_PAD &&
        position.y >= 0f &&
        position.y <= size.height - BOTTOM_PAD &&
        !inCornerButton(position, size)

/** True if [position] is inside the plotting rectangle, excluding the axis margins. */
private fun inPlotArea(position: Offset, size: IntSize): Boolean =
    position.x >= LEFT_PAD &&
        position.x <= size.width - RIGHT_PAD &&
        position.y >= TOP_PAD &&
        position.y <= size.height - BOTTOM_PAD

/**
 * The instant under [x], continuously - not snapped to a drawn sample the way
 * [epochAt] snaps. A selection's edges are bounds on time, chosen by where the
 * finger is; snapping them to drawn points would quantise the band to the
 * decimation, which is exactly the dependency on the drawn subset the
 * statistics refuse. Clamped to the plot, so a finger in the margin selects to
 * the view's edge and no further.
 */
private fun timeAt(x: Float, data: ChartData?, size: IntSize): Double? {
    if (data == null) return null
    val plotW = max(1f, size.width - LEFT_PAD - RIGHT_PAD)
    val cx = x.coerceIn(LEFT_PAD, size.width - RIGHT_PAD)
    val span = (data.end - data.start).coerceAtLeast(1e-6)
    return data.start + ((cx - LEFT_PAD) / plotW).toDouble() * span
}

/** Epoch of the drawn sample nearest [x], or null when there is nothing to inspect. */
private fun epochAt(x: Float, data: ChartData?, size: IntSize): Double? {
    if (data == null || data.x.isEmpty()) return null
    val plotW = max(1f, size.width - LEFT_PAD - RIGHT_PAD)
    val span = (data.end - data.start).coerceAtLeast(1e-6)
    val target = data.start + ((x - LEFT_PAD) / plotW).toDouble() * span
    return data.x.minByOrNull { abs(it - target) }
}

/**
 * Whether a label centred on [x] fits between [plotLeft] and [plotRight].
 *
 * Ticks land on round times now rather than at the centres of equal bands, which
 * is what the labels are for - but a round time can fall anywhere, including on
 * the very edge of the view, and a label centred there is half in the gutter or
 * half off the canvas. Shifting it inward is what used to make the outermost
 * labels collide with their neighbours, so it is left out instead: its gridline
 * is still drawn, and the tick either side of it says what it would have.
 */
internal fun labelFitsInPlot(x: Float, width: Float, plotLeft: Float, plotRight: Float): Boolean =
    x - width / 2f >= plotLeft && x + width / 2f <= plotRight

/**
 * Which of the drawn points at [xPx] carry a ring: as many as fit at [minPitchPx]
 * without overlapping, taken from the newest backwards.
 *
 * Why there is a choice to make at all. The rings exist to answer one question -
 * *where are the actual measurements* - because three readings on a straight
 * diagonal draw the same picture as two, and without rings nothing on screen
 * tells them apart. Over a minute's window the samples are tens of pixels apart
 * and a ring on each of them answers it exactly. Over a multi-hour window there
 * are thousands across nine hundred pixels, and a ring on every one answers
 * nothing: it is a solid band of colour with the trace lost inside it.
 *
 * This used to be all of them or none of them, and past the density where they
 * fit the plot drew a bare line. That was defensible - a thinned-out set of rings
 * does not mean "one sample here" any more - but it withheld the answer at
 * precisely the windows where the eye most needs it, and a bare line does not
 * claim the record is sparse so much as decline to say anything at all. Neither
 * does it help that the points reaching the renderer are already a decimation:
 * past the point budget a "sample" here is one of a stride, or a bucket's mean,
 * so ringing every drawn point had stopped being a one-to-one claim well before
 * this did.
 *
 * So: rings at the closest legible pitch, always. Sparse enough and every point
 * still gets one, exactly as before - the greedy walk keeps them all when they
 * are already far enough apart, so nothing that reads well today changes. Denser
 * than that and the eye gets discrete circles at [MARKER_PITCH] rather than a
 * smear, which says *measurements lie along here, at this pitch or closer*. That
 * is the weaker claim, and it is the true one.
 *
 * Chosen by pixel distance rather than by a fixed every-k-th stride because the
 * drawn points are not evenly spaced: a recording gap, or a bucket that caught
 * fewer samples, would let an index stride drop two rings on top of each other
 * in one place and leave a bare stretch in another. Walking from the newest
 * backwards anchors the pattern at the right-hand edge, where the live sample is
 * and where the eye already is, so the rings do not crawl as the window slides.
 */
internal fun ringedPoints(xPx: FloatArray, minPitchPx: Float = MARKER_PITCH): BooleanArray {
    val ringed = BooleanArray(xPx.size)
    if (xPx.isEmpty()) return ringed
    var last = xPx.size - 1
    ringed[last] = true
    for (j in last - 1 downTo 0) {
        if (xPx[last] - xPx[j] >= minPitchPx) {
            ringed[j] = true
            last = j
        }
    }
    return ringed
}

/** The two styles' geometry, for the tests that check it renders as described. */
internal object MarkerGeometry {
    val lineWidth = THIN_LINE
    val stroke = MARKER_STROKE
    val radius = MARKER_RADIUS
    val diameter = MARKER_DIAMETER
    /** Clear space between the line and the inside of the ring. */
    val gap = MARKER_GAP
    /** Closest two rings may sit, centre to centre. */
    val pitch = MARKER_PITCH
}

/**
 * Draws the chart into any [DrawScope] - used both by the live [PmChart] Canvas
 * and by [renderChartToBitmap] for PNG export, so there is one source of truth.
 */
fun DrawScope.drawPmChart(
    render: ChartRender,
    colors: Map<String, Color>,
    axisColor: Color,
    gridColor: Color,
    labelColor: Color,
) {
    val data = render.data
    val visible = render.visible
    val inspectedEpoch = render.inspectedEpoch
    val logY = render.logY
    val alertThreshold = render.alertThreshold
    val viewInfo = render.viewInfo

    val plotW = max(1f, size.width - LEFT_PAD - RIGHT_PAD)
    val plotH = max(1f, size.height - TOP_PAD - BOTTOM_PAD)
    val plotLeft = LEFT_PAD
    val plotTop = TOP_PAD
    val plotRight = LEFT_PAD + plotW
    val plotBottom = TOP_PAD + plotH

    drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 2f)
    drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 2f)

    if (data == null) {
        drawAxisText("connecting…", plotLeft + plotW / 2f, plotTop + plotH / 2f,
            labelColor, TEXT_CAPTION + 4f, center = true)
        return
    }

    // The axis as the model resolved it: fitted, overridden or set by hand, and
    // already snapped to its gridline step. The renderer used to redo all of
    // that from yAuto/yMin/yMax, which made two places decide the axis and left
    // only one of them knowing about the temporary override.
    val (rawMin, rawMax) = resolveYRange(data)
    val linearStep = data.yStep
    // On a log axis zero and anything below it have no position, so the axis is
    // floored just under the smallest PM value worth distinguishing rather than
    // at zero. PM readings are non-negative and a true zero is indistinguishable
    // from "cleaner than the sensor can resolve".
    val yMin = if (logY) maxOf(rawMin, LOG_FLOOR).coerceAtMost(rawMax / 2.0) else rawMin
    val yMax = if (logY) maxOf(rawMax, yMin * 10.0) else rawMax
    val ySpan = (yMax - yMin).coerceAtLeast(1e-6)
    val logLo = log10(yMin.coerceAtLeast(LOG_FLOOR))
    val logSpan = (log10(yMax.coerceAtLeast(LOG_FLOOR * 10)) - logLo).coerceAtLeast(1e-6)

    fun xOf(epoch: Double): Float {
        val span = (data.end - data.start).coerceAtLeast(1e-6)
        return (plotLeft + (epoch - data.start) / span * plotW).toFloat()
    }
    fun yOf(value: Double): Float = if (logY) {
        val t = (log10(value.coerceAtLeast(LOG_FLOOR)) - logLo) / logSpan
        (plotBottom - t * plotH).toFloat().coerceIn(plotTop, plotBottom)
    } else {
        (plotBottom - (value - yMin) / ySpan * plotH).toFloat().coerceIn(plotTop, plotBottom)
    }

    val minorColor = gridColor.copy(alpha = gridColor.alpha * 0.4f)
    // While the y hold's icons occupy the gutter, the value labels their ink
    // would sit behind are left off rather than half-hidden behind a button.
    // The hold is the user's own act on exactly this axis, the labels above
    // and below the pair stay, and the gridlines are untouched.
    val yHeld = data.yControl == YAxisControl.OVERRIDDEN || data.yControl == YAxisControl.LOCKED
    val yHoldBottom = Y_HOLD_TOP + 2 * HOLD_ICON_SIDE + HOLD_ICON_GAP
    fun yLabelClearOfHold(y: Float): Boolean =
        !yHeld || y < Y_HOLD_TOP - 40f || y > yHoldBottom + 40f
    if (logY) {
        // A log axis gets decade-ish lines rather than linear ones squashed
        // against the top.
        val rows = 4
        for (i in 0..rows) {
            val value = Math.pow(10.0, logLo + logSpan * i / rows)
            val y = yOf(value)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1.5f)
            if (yLabelClearOfHold(y)) {
                drawAxisText(
                    formatY(value), plotLeft - 16f, y, labelColor, TEXT_TICK, alignRight = true,
                )
            }
        }
    } else {
        // Labels land on round numbers at a sensible interval instead of five
        // evenly-divided values like 1.08 and 1.76. Those spent width on
        // precision the sensor does not have - it does not resolve tenths
        // reliably - and gave the eye nothing to anchor on. Reading a value to
        // two decimals is what the crosshair is for.
        val step = linearStep
        val firstIndex = ceil(yMin / step - 1e-9)
        // Indexed rather than accumulated: adding 0.1 repeatedly drifts, and the
        // drift shows up as a label reading 1.30000000000000004.
        var n = 0
        var value = (firstIndex + n) * step
        while (value <= yMax + step * 1e-6) {
            val y = yOf(value)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1.5f)
            if (yLabelClearOfHold(y)) {
                drawAxisText(formatStep(value, step), plotLeft - 16f, y, labelColor,
                    TEXT_TICK, alignRight = true)
            }
            // Halfway line, unlabelled: it shows where the midpoint falls without
            // spending any width on saying so.
            val half = value + step / 2.0
            if (half <= yMax) {
                val hy = yOf(half)
                drawLine(minorColor, Offset(plotLeft, hy), Offset(plotRight, hy), 1f)
            }
            n++
            value = (firstIndex + n) * step
        }
        // And the half below the first labelled line, when it is in range.
        val firstHalf = firstIndex * step - step / 2.0
        if (firstHalf >= yMin) {
            val hy = yOf(firstHalf)
            drawLine(minorColor, Offset(plotLeft, hy), Offset(plotRight, hy), 1f)
        }
    }

    // Where nothing was recorded, shaded before anything is drawn over it: above
    // the gridlines so it reads as a region rather than as a background, and
    // under every trace, because it is the absence of the data and must never
    // sit on top of the data either side of it. See [drawRecordGaps]. Only drawn
    // when the user asked for the marking - `chartRender` hands over no gaps
    // otherwise - and the key to the patterns lives on the Help tab.
    val gapBands = gapBands(render.gaps, data.start, data.end, plotLeft, plotRight)
    drawRecordGaps(gapBands, plotTop, plotBottom)

    // The selected stretch: a quiet band under the traces, with a firm line at
    // each edge so the bounds read exactly. Under the traces because it is
    // about the data and must never sit on top of it; in the label colour at
    // low alpha so it reads in both themes and collides with no series colour.
    render.selection?.let { (selStart, selEnd) ->
        if (selEnd > data.start && selStart < data.end && selEnd > selStart) {
            val left = xOf(maxOf(selStart, data.start)).coerceIn(plotLeft, plotRight)
            val right = xOf(minOf(selEnd, data.end)).coerceIn(plotLeft, plotRight)
            if (right > left) {
                drawRect(
                    labelColor.copy(alpha = 0.08f),
                    topLeft = Offset(left, plotTop),
                    size = Size(right - left, plotH),
                )
                // An edge inside the view is drawn; one the view has cut off is
                // not, so a band running off-plot reads as running off-plot.
                if (selStart >= data.start) {
                    drawLine(
                        labelColor.copy(alpha = 0.55f),
                        Offset(left, plotTop), Offset(left, plotBottom), 2f,
                    )
                }
                if (selEnd <= data.end) {
                    drawLine(
                        labelColor.copy(alpha = 0.55f),
                        Offset(right, plotTop), Offset(right, plotBottom), 2f,
                    )
                }
            }
        }
    }

    // Which ticks carry a label and on which of the bottom margin's two rows.
    // Label width varies with the format, so the plan is measured rather than
    // assumed; gridlines are drawn at every tick regardless, only the text is
    // thinned. The bounds are what the on-axis controls leave - the crosshair
    // toggle on the left, the tick control and any engaged x hold on the
    // right - and with the tick control stepping the gridlines finer the
    // surviving labels alternate rows to carry twice the density; see
    // [xLabelPlacement] for both rules.
    val tickXs = FloatArray(data.xTicks.size) { xOf(data.xTicks[it]) }
    val labelWidths = FloatArray(data.xTicks.size) {
        measureText(data.xLabels.getOrNull(it) ?: "", TEXT_TICK)
    }
    val labelPlan = xLabelPlacement(
        tickXs = tickXs,
        labelWidths = labelWidths,
        stagger = data.tickDensity > 0,
        datesInUse = data.xDates.any { it.isNotEmpty() },
        left = maxOf(plotLeft, xLabelLeftBound()),
        right = minOf(plotRight, xLabelRightBound(IntSize(size.width.toInt(), size.height.toInt()), data.xControl != XAxisControl.FOLLOWING)),
        gapPx = TICK_LABEL_GAP,
    )
    for (i in data.xTicks.indices) {
        val x = tickXs[i]
        drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), 1f)
        val row = labelPlan[i]
        if (row == X_LABEL_SKIP) continue
        val label = data.xLabels.getOrNull(i) ?: continue
        val labelY = plotBottom + if (row == X_LABEL_DATE_ROW) X_DATE_MID else X_TICK_MID
        drawAxisText(label, x, labelY, labelColor, TEXT_TICK, center = true)
        // The date appears only where the day turns over, so a multi-day window
        // shows at a glance where one day ends and the next begins. Never
        // fighting a staggered label for the row: the plan staggers only when
        // no date is in view.
        val date = data.xDates.getOrNull(i)
        if (!date.isNullOrEmpty()) {
            drawAxisText(date, x, plotBottom + X_DATE_MID, labelColor, TEXT_CAPTION, center = true)
        }
    }
    // No axis caption under the time labels: "14:05" under a plot of readings
    // does not need to be told it is a time of day, and the line it occupied
    // was a line taken from the plot on every screen for the sake of naming
    // the obvious. The "(EDT)" it carried went with it - see
    // [ChartRender.timeZoneLabel] for where that stands.

    // The global pin time mark, whenever its instant is in view (see
    // [pinMarkVisible]): a dashed vertical with a head, over the grid and under
    // the traces - it is context the data sits on, like the selection band, and
    // must never cover a reading. Dashed where the crosshair is solid, so the
    // two verticals cannot be mistaken for one another.
    if (pinMarkVisible(data.referencePinEpoch, data.start, data.end)) {
        val px = xOf(data.referencePinEpoch!!)
        drawLine(
            labelColor.copy(alpha = 0.65f),
            Offset(px, plotTop), Offset(px, plotBottom), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
        // The pin's head, at the top: what says "a mark someone placed" rather
        // than "another gridline".
        drawCircle(labelColor.copy(alpha = 0.65f), 5f, Offset(px, plotTop + 5f))
    }

    // Where a series sits above its own alert threshold, the region between
    // the curve and the threshold line fills with a pale red - drawn before
    // the traces so the data sits on top of its context, never under it. The
    // trace and its markers keep their series colour throughout: the breaching
    // stretch used to be redrawn in red, and against a real excursion that
    // painted a warning over exactly the part of the record the user was
    // trying to read. Per series, so if both breach both fill; the alpha is
    // faint enough that the overlap deepens rather than occludes. Gated to a
    // threshold genuinely inside the plotted y range, for the same reason as
    // the dashed line below. See AlertFill.kt for the geometry, and for what
    // "above" means when a drawn point is a bucket's mean.
    for (field in DRAW_ORDER) {
        if (field !in visible) continue
        val fillThreshold = render.alertFills[field]
            ?.takeIf { it > 0.0 && it >= yMin && it <= yMax } ?: continue
        val vals = data.series[field] ?: continue
        drawAlertFill(
            alertExcursions(data.x, vals, fillThreshold, data.gapSeconds),
            fillThreshold, ::xOf, ::yOf,
        )
    }

    // Draw PM2.5 first, PM1.0 over it. In air with nothing fine in it the two
    // coincide, and in the plain-line style the graduated widths keep the
    // narrower one visible on top instead of being overdrawn.
    val thin = render.plotStyle == PlotStyle.MARKERS
    // Chosen once, from the x axis rather than per series, so the two traces are
    // never ringed under different rules on the same plot - rings at one pitch on
    // one curve and another pitch on the other would read as a difference in the
    // data. Both share an x, so one pass over it serves both.
    val ringed = if (thin) ringedPoints(FloatArray(data.x.size) { xOf(data.x[it]) }) else null
    for (field in DRAW_ORDER) {
        if (field !in visible) continue
        val vals = data.series[field] ?: continue
        if (vals.isEmpty() || data.x.isEmpty()) continue
        val color = colors[field] ?: Color.Gray
        // One hairline for both series in the marker style. The graduated widths
        // exist to tell coincident traces apart, and where there are markers the
        // rings do that job - a "thin" line that came in two thicknesses would be
        // the spreadsheet look in name only.
        val width = if (thin) THIN_LINE else STROKE_WIDTH[field] ?: 3f
        val path = Path()
        var started = false
        val n = minOf(vals.size, data.x.size)
        for (j in 0 until n) {
            val px = xOf(data.x[j])
            val py = yOf(vals[j])
            // Lift the pen across a gap in the record. Recording stopped and
            // started again there, so a segment drawn over it would be pure
            // invention - and the longer the gap, the more confident the
            // invention looks. A lone point either side of a gap still gets its
            // dot below, so nothing disappears for being isolated.
            val broken = started && data.gapSeconds?.let { data.x[j] - data.x[j - 1] > it } == true
            if (!started || broken) {
                path.moveTo(px, py)
                started = true
                // A segment of one sample has no line to draw, so mark it -
                // unless the marker pass below is about to ring it anyway, which
                // would put a filled blob inside its own hole.
                val alone = broken || j == 0
                val next = j + 1
                val isolated = alone &&
                    (next >= n || data.gapSeconds?.let { data.x[next] - data.x[j] > it } == true)
                if (isolated && ringed?.get(j) != true) drawCircle(color, width * 0.8f, Offset(px, py))
            } else {
                path.lineTo(px, py)
            }
        }
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
        // Rings over the line, not under it: the hole is meant to show the trace
        // running through it, so the line has to be there first.
        if (ringed != null) {
            val marker = Stroke(width = MARKER_STROKE)
            for (j in 0 until n) {
                if (!ringed[j]) continue
                drawCircle(color, MARKER_RADIUS, Offset(xOf(data.x[j]), yOf(vals[j])), style = marker)
            }
        }
        // The newest sample, filled. In the marker style it is the one ring with
        // its middle inked in - the newest point always carries a ring, so "you
        // are here" is never the odd mark out.
        if (n > 0) drawCircle(color, width + 1f, Offset(xOf(data.x[n - 1]), yOf(vals[n - 1])))
    }

    // Alert threshold: an unlabelled dashed line. A horizontal rule across the
    // plot in the alert colour already reads as the alert level, so it carries
    // no text of its own; the number itself is stated where it is set, in
    // Options → Alerts. The excursion itself is marked by the fill above, not
    // by recolouring the trace.
    // Only when the threshold genuinely falls inside the plotted y range. yOf
    // clamps out-of-range values to the axis ends, which drew the line pinned to
    // the top of a chart whose axis stopped well below it - making a reading of
    // 13 look as though it were nearly touching an alert set at 20.
    val threshold = alertThreshold?.takeIf { it > 0.0 && it >= yMin && it <= yMax }
    if (threshold != null && data.x.isNotEmpty()) {
        val ty = yOf(threshold)
        if (ty in plotTop..plotBottom) {
            drawLine(
                ALERT_COLOR.copy(alpha = 0.75f),
                Offset(plotLeft, ty),
                Offset(plotRight, ty),
                2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
            )
        }
    }

    // The selection's statistics: a few lines of plain text in the crosshair
    // readout's own idiom, never a panel - the captain's rule is that the user
    // must never lose sight of the plot while studying the plot. The block
    // claims the top corner away from the selection, flipping only if the
    // curve rises into it and the other corner is clear ([regionReadoutRect]),
    // so the numbers sit over empty plot, not over the data they describe.
    // The crosshair owns the top strip while it is showing, exactly as it
    // does against the view note.
    val regionLines = render.regionLines
    val regionRect = if (
        regionLines.isNotEmpty() && inspectedEpoch == null &&
        render.selection != null && data.x.isNotEmpty()
    ) {
        val blockW = regionLines.maxOf { measureText(it, TEXT_CAPTION) }
        val blockH = READOUT_FIRST_MID + (regionLines.size - 1) * READOUT_LINE +
            TEXT_CAPTION * INK_BELOW_CENTRE
        val drawn = visible.filter { it in data.series }
        val curveXs = FloatArray(data.x.size) { xOf(data.x[it]) }
        val curveTopYs = FloatArray(data.x.size) { i ->
            var top = plotBottom
            for (field in drawn) {
                val v = data.series[field]?.getOrNull(i) ?: continue
                if (v.isFinite()) top = minOf(top, yOf(v))
            }
            top
        }
        val (selStart, selEnd) = render.selection
        regionReadoutRect(
            plotLeft = plotLeft, plotRight = plotRight, plotTop = plotTop,
            blockW = blockW, blockH = blockH,
            selectionLeft = xOf(maxOf(selStart, data.start)).coerceIn(plotLeft, plotRight),
            selectionRight = xOf(minOf(selEnd, data.end)).coerceIn(plotLeft, plotRight),
            curveXs = curveXs, curveTopYs = curveTopYs,
            rightReserve = TOP_RIGHT_RESERVE,
        )
    } else {
        null
    }
    val regionOnLeft = regionRect != null && regionRect.left < plotLeft + plotW / 2f

    // The top strip's tenants - the unit mark, the derived-times flag beside
    // it, and the view note right-aligned clear of the Follow/Lock icons -
    // laid out by measurement rather than hope, because the strip was already
    // at capacity on a phone: see [topStripPlan] for who yields and where to.
    val strip = topStripPlanFor(render, IntSize(size.width.toInt(), size.height.toInt()))

    // The unit, small, at the top of the y axis - all that remains of the
    // rotated "Particle concentration (µg/m³)" caption that used to run up the
    // gutter. The tick values are the part of an axis that cannot be inferred
    // and they are still there; the unit is the one word of the caption that
    // was information, so it stays, at the size of the other in-plot notes.
    // Suppressed while inspecting, exactly as the view note is: the crosshair
    // readout owns the plot's top strip then, and flips to this side when the
    // crosshair is on the other. The selection readout suppresses only the
    // corner it actually claims.
    if (inspectedEpoch == null && !regionOnLeft) {
        drawAxisText(
            SeriesColors.unit, strip.unitX, plotTop + VIEW_NOTE_MID, labelColor, TEXT_NOTE,
        )
    }
    // The derived-times flag, beside the unit mark it qualifies: the axis'
    // timestamps are the thing it is a caveat about. Drawn text - so the
    // exported PNG carries the same caveat the screen does - with its tap
    // target overlaid by [PlotChromeOverlays]. Suppressed while inspecting
    // exactly as the view note is, and while the selection statistics hold
    // the strip.
    if (render.timeFlag != null && inspectedEpoch == null && regionRect == null &&
        strip.flagLine != null
    ) {
        drawAxisText(
            "◷ ${render.timeFlag}", strip.flagX, plotTop + stripLineMid(strip.flagLine),
            labelColor, TEXT_NOTE,
        )
    }
    // Suppressed while inspecting - the crosshair readout owns this corner then -
    // and while the selection readout has taken this side.
    if (viewInfo.isNotEmpty() && inspectedEpoch == null && (regionRect == null || regionOnLeft)) {
        drawAxisText(
            viewInfo, plotRight - 8f - TOP_RIGHT_RESERVE,
            plotTop + stripLineMid(strip.infoLine), labelColor, TEXT_VIEW_INFO,
            alignRight = true,
        )
    }
    if (regionRect != null) {
        var regionY = plotTop + READOUT_FIRST_MID
        for (line in regionLines) {
            drawAxisText(line, regionRect.left, regionY, labelColor, TEXT_CAPTION)
            regionY += READOUT_LINE
        }
    }

    if (data.x.isEmpty()) {
        drawAxisText("waiting for readings…", plotLeft + plotW / 2f, plotTop + plotH / 2f,
            labelColor, TEXT_CAPTION, center = true)
    }

    // Inspection crosshair, drawn last so it sits over the series.
    if (inspectedEpoch != null && data.x.isNotEmpty()) {
        // Tested against the instant the cursor holds, not against the sample it
        // snaps to. Only samples inside the window are ever handed to the
        // renderer, so a snapped sample is in range by construction - which made
        // this check dead code, and left a cursor whose instant had been carried
        // out of view clamped to the edge sample, quietly reading out a value
        // from a moment the user never chose.
        if (inspectedEpoch < data.start || inspectedEpoch > data.end) {
            // Holding an instant means a zoom or a pan can carry that instant off
            // the plot, and a crosshair that simply disappears reads as one that
            // has been lost. Say which way it went and when it was, so navigating
            // back to it is an obvious move rather than a guess.
            drawOffPlotCursor(
                before = inspectedEpoch < data.start,
                plotLeft = plotLeft, plotRight = plotRight,
                plotTop = plotTop, plotBottom = plotBottom,
                color = labelColor, stamp = formatStamp(inspectedEpoch),
            )
            return
        }
        // Which sample the crosshair snapped to.
        val index = data.x.indices.minByOrNull { abs(data.x[it] - inspectedEpoch) } ?: return
        val epoch = data.x[index]
        val x = xOf(epoch)
        drawLine(labelColor, Offset(x, plotTop), Offset(x, plotBottom), 2f)

        // Ring the series the readout names, so each number is unambiguous about
        // which trace it came from. Only those: a ring on a trace with no line of
        // text beside it points at nothing.
        val read = READOUT_ORDER.filter { it in visible }
        for (field in read) {
            val value = data.series[field]?.getOrNull(index) ?: continue
            drawCircle(colors[field] ?: Color.Gray, 7f, Offset(x, yOf(value)))
        }

        // Readout, top-right of the plot. Flips to the left when the crosshair
        // would otherwise sit underneath it.
        val lines = buildList {
            add(formatStamp(epoch) to labelColor)
            for (field in read) {
                val value = data.series[field]?.getOrNull(index) ?: continue
                add(
                    "${SeriesColors.label[field] ?: field}  ${formatY(value)}" to
                        (colors[field] ?: labelColor)
                )
            }
        }
        val onRight = x < plotLeft + plotW * 0.55f
        // The right-side anchor keeps clear of the Follow/Lock icons, which
        // hold the top-right corner whatever the readout is doing.
        val boxRight =
            if (onRight) plotRight - 8f - TOP_RIGHT_RESERVE else plotLeft + plotW * 0.42f
        var textY = plotTop + READOUT_FIRST_MID
        for ((line, color) in lines) {
            drawAxisText(line, boxRight, textY, color, TEXT_CAPTION, alignRight = true)
            textY += READOUT_LINE
        }
    }
}

/**
 * A marker for a pinned crosshair whose instant now lies outside the view.
 *
 * A dashed line hard against the edge it left by, an arrow pointing that way,
 * and the instant it is holding.
 */
private fun DrawScope.drawOffPlotCursor(
    before: Boolean,
    plotLeft: Float,
    plotRight: Float,
    plotTop: Float,
    plotBottom: Float,
    color: Color,
    stamp: String,
) {
    val faded = color.copy(alpha = 0.65f)
    val x = if (before) plotLeft + 2f else plotRight - 2f
    val midY = (plotTop + plotBottom) / 2f
    drawLine(
        faded, Offset(x, plotTop), Offset(x, plotBottom), 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
    )
    val tip = if (before) x - 26f else x + 26f
    val arrow = Path().apply {
        moveTo(tip, midY)
        lineTo(x, midY - 20f)
        lineTo(x, midY + 20f)
        close()
    }
    drawPath(arrow, faded)
    // Along the top, where the crosshair readout normally is - beside the arrow
    // it lands in the middle of the plot and sits over the traces. On the right
    // it starts left of the Follow/Lock icons' corner, like every other
    // right-anchored line of the strip.
    drawAxisText(
        stamp,
        if (before) x + 16f else x - 16f - TOP_RIGHT_RESERVE, plotTop + READOUT_FIRST_MID,
        faded, TEXT_NOTE, alignRight = !before,
    )
}

/**
 * Where the selection's statistics text sits: the top corner away from the
 * selection, unless the drawn curve rises into that corner and the other one
 * is clear.
 *
 * Away from the selection first, because the numbers describe that stretch and
 * must not cover it - the failed card covered it completely, which is the
 * fault this whole idiom replaces. Then curve-aware: a corner is "clear" when
 * no drawn point within the block's span (plus a small margin) reaches up into
 * the block's rows. When neither corner is clear the preferred one is kept -
 * a few lines of unboxed text over the curve's tip reads fine, exactly as the
 * crosshair readout always has - but the choice is made from the data rather
 * than hoped about.
 *
 * Pure, and in plot pixels, so `RegionReadoutPlacementTest` can hold it to
 * the promise at both orientations' plot shapes without a renderer.
 */
internal fun regionReadoutRect(
    plotLeft: Float,
    plotRight: Float,
    plotTop: Float,
    blockW: Float,
    blockH: Float,
    selectionLeft: Float,
    selectionRight: Float,
    curveXs: FloatArray,
    curveTopYs: FloatArray,
    /** Width the right corner keeps clear of the Follow/Lock icons. */
    rightReserve: Float = 0f,
): Rect {
    val margin = 12f
    val leftX = plotLeft + 10f
    val rightX = plotRight - 8f - rightReserve - blockW
    fun clear(x: Float): Boolean {
        for (i in curveXs.indices) {
            if (curveXs[i] >= x - margin && curveXs[i] <= x + blockW + margin &&
                curveTopYs[i] <= plotTop + blockH + margin
            ) {
                return false
            }
        }
        return true
    }
    val selectionMid = (selectionLeft + selectionRight) / 2f
    val preferLeft = selectionMid >= (plotLeft + plotRight) / 2f
    val first = if (preferLeft) leftX else rightX
    val second = if (preferLeft) rightX else leftX
    val x = when {
        clear(first) -> first
        clear(second) -> second
        else -> first
    }
    return Rect(x, plotTop, x + blockW, plotTop + blockH)
}

/** "14:03:27" for sub-day spans, else "Jul 27 14:03" - enough to confirm cadence. */
private fun formatStamp(epoch: Double): String =
    java.time.Instant.ofEpochSecond(epoch.toLong())
        .atZone(com.sensirion.sps30.data.TimeSeriesHistory.EASTERN)
        .format(STAMP_FORMAT)

private val STAMP_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM d  HH:mm:ss")

/**
 * Render the chart to an ARGB [Bitmap] for PNG export.
 *
 * Saving the image is meant to produce a picture of the plot as it stands, so
 * every input the on-screen Canvas draws from is taken here too: the same
 * [ChartData] (hence the same zoom and pan), the same crosshair, the same axis
 * type, the same annotations. Leaving any of them out silently exported a
 * *different* chart than the one being looked at, which is the one thing an
 * image export must not do - a saved picture that disagrees with the screen is
 * worse than no picture, because nothing on it says which one to believe.
 *
 * Callers should pass the on-screen canvas size, so the export is the same plot
 * at the same proportions rather than a re-layout into a different rectangle
 * (the tick spacing and label density both follow the size).
 */
fun renderChartToBitmap(
    render: ChartRender,
    widthPx: Int,
    heightPx: Int,
    background: Color,
    axisColor: Color,
    gridColor: Color,
    labelColor: Color,
    colors: Map<String, Color> = SeriesColors.color,
): android.graphics.Bitmap {
    val image = ImageBitmap(widthPx, heightPx)
    val canvas = Canvas(image)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        canvas,
        Size(widthPx.toFloat(), heightPx.toFloat()),
    ) {
        // The screen composites the plot over the app's surface; the PNG has no
        // window behind it, so it paints that surface itself.
        drawRect(background)
        drawPmChart(render, colors, axisColor, gridColor, labelColor)
    }
    return image.asAndroidBitmap()
}

/**
 * The range the axis spans, as [ChartView] resolved it.
 *
 * The renderer used to work this out for itself from `yAuto`/`yMin`/`yMax`,
 * which meant two places decided the axis and only one of them knew about the
 * temporary override. The fallback is for a [ChartData] built by hand rather
 * than by `compute`, and calls the same fit so it cannot disagree either.
 */
private fun resolveYRange(data: ChartData): Pair<Double, Double> {
    val lo = data.yFloor
    val hi = data.yCeil
    return if (lo != null && hi != null && lo < hi) lo to hi
    else ChartView.autoYRange(data.dataYMin, data.dataYMax)
}

/** Width of [text] at [sizePx], for deciding whether it fits before drawing it. */
private fun measureText(text: String, sizePx: Float): Float =
    android.graphics.Paint().apply { textSize = sizePx }.measureText(text)

private fun DrawScope.drawAxisText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizePx: Float,
    center: Boolean = false,
    alignRight: Boolean = false,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = sizePx
        isAntiAlias = true
        textAlign = when {
            center -> android.graphics.Paint.Align.CENTER
            alignRight -> android.graphics.Paint.Align.RIGHT
            else -> android.graphics.Paint.Align.LEFT
        }
    }
    val fm = paint.fontMetrics
    val baseline = y - (fm.ascent + fm.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, x, baseline, paint)
}

/** Exactly the decimals the interval needs - no trailing noise, none missing. */
private fun formatStep(value: Double, step: Double): String = when {
    step >= 1.0 -> "%.0f".format(value)
    step >= 0.1 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}

private fun formatY(value: Double): String {
    val a = abs(value)
    return when {
        a >= 100 -> "%.0f".format(value)
        a >= 10 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
}

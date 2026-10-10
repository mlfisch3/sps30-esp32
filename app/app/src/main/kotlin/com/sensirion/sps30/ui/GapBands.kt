package com.sensirion.sps30.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap

/**
 * Shading the plot where nothing was recorded, and saying which kind of nothing.
 *
 * ## What a gap looked like before
 *
 * Absence. The trace lifts its pen across one - that much was already right, and
 * an interpolated line over three missing hours would be an invention - but a
 * broken line on a quiet trace is indistinguishable from a quiet trace, and the
 * whole failure of 2026-08-04 was that nobody could see there was anything to
 * look at. "Readings are missing here" has to be visible without being inferred.
 *
 * ## Why the pattern is coded, and not just a pattern
 *
 * One undifferentiated hatch is decoration: the break in the line already says a
 * gap is there, so a band that says the same thing again in amber adds nothing a
 * reader can act on. What a reader can act on is *why* the hole is there, because
 * the four reasons call for four different responses - wait, give up, look in a
 * host backup, or find out more. So the fill is the answer to "why", each one is
 * told apart at a glance rather than by study, and the Help tab's key
 * ([GapLegendSection]) names them in the Data tab's own words.
 *
 * ## The marking is a preference; the record's honesty is not
 *
 * None of this is drawn unless `AppConfig.markGaps` is on, and it is off by
 * default: once storage decimates instead of dropping, a hole will usually hold
 * *something*, and shading every hole suits the behaviour before that. The
 * switch gates only what `chartRender` hands the plot. Detection, the sweep and
 * the Data tab's account of what is missing, recoverable or gone are the same
 * with it off - a display preference must never soften what that panel says.
 *
 * Each pattern is one arm of [GapPattern], which is derived from the verdict
 * detection already reaches ([GapStanding]) and adds nothing to it. The four are
 * built from three separate signals - hatch angle, colour, and solid against
 * hatched against dotted - so no two are told apart by a single dimension, and
 * losing one of those dimensions to a colourblind reader or a dim screen still
 * leaves two.
 *
 * ## What the app cannot say, and therefore does not draw
 *
 * There is no pattern for "the sensor was not recording then" - it was powered
 * off, or stopped - and there deliberately is not one, because nothing here can
 * tell it from "recorded, and this device has not fetched it". A verdict is
 * reached against [com.sensirion.sps30.data.SensorRetention], which is a single
 * instant: where the sensor's retained record *begins*. Any hole after that
 * instant is [GapStanding.Fillable], whether the board was recording through it
 * or switched off at the wall. `file_list` states a session's start and its row
 * count and never its end, so the stretches between sessions cannot be laid out
 * on the axis without assuming an interval each session was recorded at, which
 * the board never states per session.
 *
 * A pattern invented for it would therefore be a guess dressed as a finding, and
 * this is the screen where that is least affordable. What is done instead is in
 * the wording: [GapPattern.StillOnSensor] reads "still inside the sensor's
 * record", which is exactly the fact that was established, and not "the sensor
 * has these readings", which is the fact that was not.
 */

/** Why one hole in the record is there, as far as anything here can tell. */
enum class GapPattern {
    /**
     * The sensor's ring has wrapped past it. Nothing on the device to ask for.
     *
     * Declared first because the legend is drawn in this order, worst first, the
     * same order the Data tab lists gaps in.
     */
    Gone,

    /** The reason cannot be determined, and saying so beats guessing at one. */
    NotKnown,

    /**
     * The sensor's record reaches into this hole but does not cover the front.
     *
     * Deliberately *not* worded as "the earlier part is gone". A gap gets this
     * verdict whenever its end falls after the sensor's retained floor, and that
     * floor is only a proven floor when the listing was walked to its end - see
     * `RecordGaps.standingOf`. On a one-page listing the earlier part may be
     * sitting in a session nothing has named yet. "Out of reach" is true either
     * way; "gone for good" would be true only sometimes.
     */
    PartlyOutOfReach,

    /**
     * The hole lies inside the range the sensor still holds, so it can be asked
     * for - and the sweep will, unaided.
     *
     * The one case the user can act on right now, and the one that keeps the
     * amber diagonal hatch it has always had.
     */
    StillOnSensor,
}

/**
 * What can be done about a hole, as a fill. Derived, never detected.
 *
 * The whole of the mapping from detection's verdict to the plot's vocabulary. It
 * adds no case detection cannot substantiate: [GapPattern.PartlyOutOfReach] is
 * exactly [RecordGap.partlyBeyondReach], which the detector already computes and
 * names, and there is no arm for anything the detector does not answer.
 */
internal fun gapPatternOf(gap: RecordGap): GapPattern = when (gap.standing) {
    GapStanding.Lost -> GapPattern.Gone
    is GapStanding.Unknown -> GapPattern.NotKnown
    is GapStanding.Fillable ->
        if (gap.partlyBeyondReach) GapPattern.PartlyOutOfReach else GapPattern.StillOnSensor
}

/** One gap resolved to pixels on the plot. */
data class GapBand(val left: Float, val right: Float, val pattern: GapPattern)

/**
 * The narrowest a band may be drawn.
 *
 * Zoomed out to a fortnight a three-hour hole is under a pixel wide, and a band
 * that rounds away to nothing is the same as not drawing it. Widened about its
 * own centre, so it still marks where the hole is; the plot's own pen-lift is
 * what continues to say exactly where the record breaks.
 */
private const val MIN_BAND_PX = 3f

/** Pitch of the diagonals in a hatched band. */
private const val HATCH_PITCH_PX = 11f

/** Pitch of the uprights in a "not yet judged" band, and they are sparser. */
private const val UPRIGHT_PITCH_PX = 16f

private val LOST_COLOR = Color(0xFFB0453C)
private val FILLABLE_COLOR = Color(0xFFFFB300)
private val UNKNOWN_COLOR = Color(0xFF9E9E9E)

/**
 * How one pattern is laid down, as data rather than as a branch.
 *
 * Stated this way for two reasons. The renderer becomes one loop, so a band and
 * the legend's swatch beside it are painted by literally the same code and cannot
 * drift into showing the reader two different things for one meaning; and the
 * four fills become comparable, so a test can assert they are pairwise distinct
 * rather than eyeballing four `when` arms.
 */
internal data class GapFill(
    /** The flat wash over the whole band. */
    val wash: Color,
    /** Diagonals rising left to right, `/`. */
    val hatch: Color? = null,
    /** Diagonals falling left to right, `\`, crossing [hatch] into a mesh. */
    val counterHatch: Color? = null,
    /** Uprights through the band. */
    val uprights: Color? = null,
    /** Vertical rules down each side of the band. */
    val edge: Color? = null,
    /** Whether that edge is dotted - a question mark rather than a cut. */
    val edgeDotted: Boolean = false,
)

/**
 * The four fills, and why each looks the way it does.
 *
 * - **Still on the sensor** - the amber diagonal hatch, unchanged. The diagonals
 *   read as provisional; the sweep is going to fill this in.
 * - **Partly out of reach** - the same amber diagonals crossed by grey ones. The
 *   mesh says at a glance that this hole is two things at once, and the second
 *   thread is grey rather than red on purpose: the earlier part is out of reach,
 *   which is not the same claim as gone. See [GapPattern.PartlyOutOfReach].
 * - **Gone** - a solid block with hard edges. Nothing is going to happen here,
 *   and a solid block is what that looks like.
 * - **Not known yet** - the faintest of the four, dotted through and dotted at
 *   the sides. It is a question, not a claim, and it must not carry the weight of
 *   any of the answers.
 */
internal val GAP_FILLS: Map<GapPattern, GapFill> = mapOf(
    GapPattern.Gone to GapFill(
        wash = LOST_COLOR.copy(alpha = 0.18f),
        edge = LOST_COLOR.copy(alpha = 0.6f),
    ),
    GapPattern.NotKnown to GapFill(
        wash = UNKNOWN_COLOR.copy(alpha = 0.06f),
        uprights = UNKNOWN_COLOR.copy(alpha = 0.38f),
        edge = UNKNOWN_COLOR.copy(alpha = 0.5f),
        edgeDotted = true,
    ),
    GapPattern.PartlyOutOfReach to GapFill(
        wash = FILLABLE_COLOR.copy(alpha = 0.08f),
        hatch = FILLABLE_COLOR.copy(alpha = 0.45f),
        counterHatch = UNKNOWN_COLOR.copy(alpha = 0.5f),
    ),
    GapPattern.StillOnSensor to GapFill(
        wash = FILLABLE_COLOR.copy(alpha = 0.08f),
        hatch = FILLABLE_COLOR.copy(alpha = 0.45f),
    ),
)

/**
 * What the key says about one pattern, in the plainest words there are.
 *
 * Two forms: [label] is the name, [detail] the sentence behind it. The pair is
 * chosen so that the short form alone is never *wrong* - only terser - because
 * wherever the label is read without its detail it is all the reader gets.
 *
 * The vocabulary is the Data tab's, and it is taken from the record panel rather
 * than invented here: "gone for good" is `everRecordedSummary`'s phrase and the
 * label on its fourth row, "not yet judged" is `goneForGoodValue`'s, and "not
 * here yet" is the row that counts what the sensor holds and this device does
 * not. The same condition may not be called two things in two places, so when
 * that panel's wording moves this follows it.
 */
internal data class GapLegendEntry(val pattern: GapPattern, val label: String, val detail: String) {
    val full: String get() = "$label - $detail"
}

/**
 * The wording, which is the part that does the work.
 *
 * Every one of these is a statement about what the *sensor has said*, never about
 * what it holds, because those differ and only the first is established. The
 * difference is not pedantry: a board that was switched off for three hours has a
 * retained record spanning those hours and no readings in them, and it gets
 * "still inside the sensor's record" - which is true - rather than "the sensor
 * has this", which would send a reader hunting for data that never existed. See
 * this file's header for why that case has no pattern of its own.
 */
internal val GAP_LEGEND: Map<GapPattern, GapLegendEntry> = mapOf(
    GapPattern.Gone to GapLegendEntry(
        GapPattern.Gone, "gone for good", "the sensor has recorded over it",
    ),
    GapPattern.NotKnown to GapLegendEntry(
        GapPattern.NotKnown, "not yet judged", "the sensor has not said enough to judge it",
    ),
    GapPattern.PartlyOutOfReach to GapLegendEntry(
        GapPattern.PartlyOutOfReach,
        "partly out of reach",
        "only the later part is still in the sensor's record",
    ),
    GapPattern.StillOnSensor to GapLegendEntry(
        GapPattern.StillOnSensor, "not here yet", "still inside the sensor's record",
    ),
)

/**
 * Where each gap falls on the x axis, clipped to what is on screen.
 *
 * Pure, and separated from the drawing for the usual reason: the clipping and the
 * minimum width are the parts that can be got wrong, and they can be checked
 * without a Canvas. A gap wholly outside the window yields nothing rather than a
 * band pinned to the edge, which is what would otherwise put a "gone" block over
 * data that is perfectly present.
 */
internal fun gapBands(
    gaps: List<RecordGap>,
    start: Double,
    end: Double,
    plotLeft: Float,
    plotRight: Float,
): List<GapBand> {
    val span = end - start
    if (gaps.isEmpty() || !(span > 0.0) || plotRight <= plotLeft) return emptyList()
    val width = plotRight - plotLeft
    val out = ArrayList<GapBand>(gaps.size)
    for (gap in gaps) {
        // Strictly outside, not merely touching: a gap that ends exactly at the
        // left edge has no width on screen and nothing to say about it.
        if (gap.endEpoch <= start || gap.startEpoch >= end) continue
        val from = ((gap.startEpoch - start) / span * width + plotLeft).toFloat()
        val to = ((gap.endEpoch - start) / span * width + plotLeft).toFloat()
        var left = from
        var right = to
        if (right - left < MIN_BAND_PX) {
            val middle = (left + right) / 2f
            left = middle - MIN_BAND_PX / 2f
            right = middle + MIN_BAND_PX / 2f
        }
        out.add(
            GapBand(
                left = left.coerceIn(plotLeft, plotRight),
                right = right.coerceIn(plotLeft, plotRight),
                pattern = gapPatternOf(gap),
            )
        )
    }
    return out
}

/**
 * Shade the bands, under the traces.
 *
 * Under, deliberately: what is drawn here is the absence of data, and it must
 * never sit on top of the data either side of it. The alpha values are low for
 * the same reason - this has to be noticeable at a glance and must not compete
 * with the curves for attention.
 */
internal fun DrawScope.drawRecordGaps(
    bands: List<GapBand>,
    plotTop: Float,
    plotBottom: Float,
) {
    if (bands.isEmpty() || plotBottom <= plotTop) return
    for (band in bands) {
        if (band.right - band.left <= 0f) continue
        drawGapFill(band.pattern, band.left, band.right, plotTop, plotBottom)
    }
}

/**
 * One pattern laid into one rectangle, wherever that rectangle is.
 *
 * The single painter for both a band on the plot and a swatch in the Help tab's
 * key ([GapLegendSection]), so the key cannot come to show something the plot
 * does not draw. The hatch pitch is in absolute pixels rather than a fraction of
 * the height, which is what makes a small swatch show the same weave as a 700px
 * band rather than four fat stripes.
 */
internal fun DrawScope.drawGapFill(
    pattern: GapPattern,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
) {
    val fill = GAP_FILLS[pattern] ?: return
    val width = right - left
    val height = bottom - top
    if (width <= 0f || height <= 0f) return

    drawRect(fill.wash, topLeft = Offset(left, top), size = Size(width, height))

    if (fill.hatch != null || fill.counterHatch != null || fill.uprights != null) {
        clipRect(left = left, top = top, right = right, bottom = bottom) {
            // Started a full height to the left, so every diagonal crosses the
            // band rather than clipping its corner.
            fill.hatch?.let { color ->
                var x = left - height
                while (x < right) {
                    drawLine(color, Offset(x, bottom), Offset(x + height, top), 1.5f)
                    x += HATCH_PITCH_PX
                }
            }
            fill.counterHatch?.let { color ->
                var x = left - height
                while (x < right) {
                    drawLine(color, Offset(x, top), Offset(x + height, bottom), 1.5f)
                    x += HATCH_PITCH_PX
                }
            }
            fill.uprights?.let { color ->
                val dotted = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                var x = left + UPRIGHT_PITCH_PX / 2f
                while (x < right) {
                    drawLine(color, Offset(x, top), Offset(x, bottom), 1.5f, pathEffect = dotted)
                    x += UPRIGHT_PITCH_PX
                }
            }
        }
    }

    // Hard edges on a block that fades into the plot would read as a shadow; with
    // sides it reads as a piece cut out of the record. Dotted, it reads as a
    // question about the same piece.
    fill.edge?.let { color ->
        val dotted = if (fill.edgeDotted) PathEffect.dashPathEffect(floatArrayOf(4f, 6f)) else null
        val stroke = if (fill.edgeDotted) 1.5f else 2f
        drawLine(color, Offset(left, top), Offset(left, bottom), stroke, pathEffect = dotted)
        drawLine(color, Offset(right, top), Offset(right, bottom), stroke, pathEffect = dotted)
    }
}

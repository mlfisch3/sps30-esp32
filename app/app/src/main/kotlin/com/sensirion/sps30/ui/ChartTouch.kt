package com.sensirion.sps30.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * How long a lone, motionless finger stays undecided before the chart accepts it
 * as a request to move the crosshair, in milliseconds.
 *
 * Every two-finger gesture necessarily starts as one finger, so anything the
 * chart does the instant a first finger lands is done to gestures that were
 * never single-finger at all. Waiting is what lets the second finger arrive and
 * be recognised, and the wait has to cover the spread between the two landings.
 *
 * Measured on the Tab S7+ (Android 13) that this is used on:
 *
 *  - The input path reproduces the ACTION_DOWN -> ACTION_POINTER_DOWN interval
 *    faithfully: over 72 two-finger gestures replayed at commanded gaps from
 *    0 to 250 ms, observed minus commanded had a median of -1 ms, a standard
 *    deviation of 1.05 ms and a worst case of 3 ms, and the second pointer was
 *    never missed. So a threshold in the tens of milliseconds is enforced
 *    exactly; there is no batching quantum to leave room for.
 *  - The platform's own [android.view.ViewConfiguration] on this device reports
 *    a double-tap *minimum* of 40 ms - Android's own statement of how close two
 *    touch events have to be before it refuses to read them as separate
 *    deliberate actions - a double-tap timeout of 300 ms and a long press of
 *    500 ms.
 *  - Touch slop is 15 px here, and a single finger sliding deliberately crosses
 *    it in 75-81 ms at 200 px/s, 39-46 ms at 400 px/s and 18-22 ms at 1000 px/s.
 *
 * 120 ms sits three times above the platform's simultaneity floor and clear of
 * the slop crossing of even a slow deliberate slide, while staying well under
 * the 300 ms double-tap and 500 ms long-press timeouts it must not collide
 * with.
 *
 * The number is deliberately not load-bearing. A gesture also settles the
 * moment the finger crosses touch slop or lifts, so this timeout is only ever
 * felt by a finger that lands and then holds perfectly still - and a pinch
 * whose second finger is slower than this is still put right, because
 * [ChartTouchRole.NAVIGATE] arriving late makes the caller undo the crosshair
 * move rather than live with it.
 */
const val CURSOR_HOLD_MS = 120L

/** What a touch on the plot turned out to be. */
enum class ChartTouchRole {
    /** Not yet known - too soon, and the finger has not moved. */
    UNDECIDED,

    /** One finger asking to read a value: it owns the crosshair. */
    INSPECT,

    /** Zoom or pan: the crosshair must not notice it happened. */
    NAVIGATE,
}

/**
 * Decides what a touch on the plot is, and refuses to decide too early.
 *
 * Kept free of Compose and Android types so the rule can be tested directly
 * rather than through a gesture recogniser.
 *
 * @param slopPx how far the finger may drift and still count as motionless;
 *   pass the platform's own touch slop rather than a number of your own.
 * @param inspectAllowed false when the crosshair is switched off, which settles
 *   every touch as [ChartTouchRole.NAVIGATE] before it starts.
 */
class ChartTouchArbiter(
    private val slopPx: Float,
    private val inspectAllowed: Boolean,
    private val holdMillis: Long = CURSOR_HOLD_MS,
) {
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f

    var role: ChartTouchRole =
        if (inspectAllowed) ChartTouchRole.UNDECIDED else ChartTouchRole.NAVIGATE
        private set

    /** The first finger has landed. */
    fun down(atMillis: Long, x: Float, y: Float) {
        downAt = atMillis
        downX = x
        downY = y
        role = if (inspectAllowed) ChartTouchRole.UNDECIDED else ChartTouchRole.NAVIGATE
    }

    /**
     * Where the gesture stands now.
     *
     * A second finger settles it as a navigation **even after it has already
     * settled as an inspection**, because a slow pinch is still a pinch and the
     * caller can put the crosshair back where it found it. The reverse never
     * happens: lifting back to one finger leaves the gesture a navigation until
     * every finger is up.
     */
    fun update(atMillis: Long, x: Float, y: Float, pointerCount: Int): ChartTouchRole {
        if (pointerCount >= 2) {
            role = ChartTouchRole.NAVIGATE
            return role
        }
        if (role != ChartTouchRole.UNDECIDED) return role
        // A finger that has not moved is not yet a cursor request; one that has
        // travelled past slop plainly is, and need not wait out the timeout.
        if (hypot(x - downX, y - downY) >= slopPx || atMillis - downAt >= holdMillis) {
            role = ChartTouchRole.INSPECT
        }
        return role
    }

    /** The hold expired with one finger down and still. */
    fun held(): ChartTouchRole {
        if (role == ChartTouchRole.UNDECIDED) role = ChartTouchRole.INSPECT
        return role
    }

    /** The finger lifted: a completed single tap, whatever else it was going to be. */
    fun lifted(): ChartTouchRole {
        if (role == ChartTouchRole.UNDECIDED) role = ChartTouchRole.INSPECT
        return role
    }
}

/**
 * How far [coords] sit from their own centre, on average - "how far apart the
 * fingers are" reduced to one axis.
 *
 * The same measure Compose's own `calculateZoom` takes, except that it takes it
 * as a distance in the plane and this takes it one axis at a time. A chart that
 * scales in one direction has no use for the difference; one that must tell a
 * vertical pinch from a level one has nothing else to go on.
 *
 * Mean deviation rather than the gap between two fingers, because it needs no
 * special case for a third: a hand resting an extra finger on the glass changes
 * the number a little rather than making it meaningless.
 */
fun spreadOf(coords: FloatArray): Float {
    if (coords.isEmpty()) return 0f
    var centre = 0f
    for (c in coords) centre += c
    centre /= coords.size
    var spread = 0f
    for (c in coords) spread += kotlin.math.abs(c - centre)
    return spread / coords.size
}

/**
 * Ignore incidental pinch below this magnitude, so a pure drag does not zoom.
 *
 * This one number is what keeps a two-finger drag and a two-finger pinch out of
 * each other's way. Fingers held a fixed distance apart and moved together
 * report a spread ratio of very nearly 1, and fingers spreading report one that
 * is plainly not - so the same threshold that stopped a drag from zooming is
 * what now decides, frame by frame, whether the hand is scaling the axis or
 * shifting it. See [isScaling].
 */
const val ZOOM_DEADZONE = 0.015f

/**
 * Whether [zoom] - one frame's ratio of finger spreads - is a deliberate scale
 * rather than the incidental spread every two-finger drag reports.
 *
 * A frame that is scaling must not also pan the y axis, and a frame that is not
 * is free to: that is the whole of how the two gestures share two fingers, and
 * it is one predicate rather than a mechanism of its own.
 */
fun isScaling(zoom: Float): Boolean = zoom > 0f && abs(zoom - 1f) > ZOOM_DEADZONE

/**
 * Whether a two-finger drag has committed to shifting the y axis.
 *
 * ## What it is for
 *
 * Two fingers dragged sideways scroll the record, and they never do it in a
 * perfectly level line - the hand drifts down the glass as it travels. Acting on
 * that drift would take the value axis off autoscale and leave a hold to release,
 * on a gesture that was only ever about time. It is the complaint that already
 * stopped a vertical pinch from scrolling time ("scrolling the record while
 * setting its height is a second thing the user did not ask for"), arriving from
 * the other direction, and it gets the same answer.
 *
 * ## The rule
 *
 * The shift arms once the gesture has travelled further up or down than it has
 * sideways, by more than a finger's slop - and then stays armed, because a drag
 * near the diagonal would otherwise flicker in and out several times a second and
 * the axis would judder. Settled once and held, exactly as [PinchArbiter] settles
 * which axis a pinch owns and for the same reason.
 *
 * A tie does not arm it. Sideways is what two fingers have always done here, and
 * an existing habit is not reinterpreted by a rounding accident - the same
 * tie-break [PinchArbiter] makes.
 *
 * Only the y axis is gated this way. Time keeps panning on whatever sideways
 * component the fingers have, exactly as it did before there was anything else
 * for a two-finger drag to mean.
 *
 * @param slopPx the platform's own touch slop; how far a finger may travel
 *   before the travel is taken to be deliberate.
 */
class YDragLatch(private val slopPx: Float) {
    private var sideways = 0f
    private var vertical = 0f

    /** True once the drag has declared itself a vertical one. */
    var armed = false
        private set

    /** One frame of drag, as the fingers' translation in canvas pixels. */
    fun travelled(panX: Float, panY: Float): Boolean {
        if (armed) return true
        sideways += abs(panX)
        vertical += abs(panY)
        if (vertical > slopPx && vertical > sideways) armed = true
        return armed
    }
}

/**
 * Where a gesture centred on canvas y [focusY] sits in the range it is scaling:
 * 0 at the bottom of the plot, 1 at the top.
 *
 * The y axis runs the other way from canvas pixels - values grow upwards, pixels
 * grow downwards - so this is the one place that flip is written down. Clamped,
 * because fingers that stray into the margins are still asking to scale about
 * the end they are nearest to, not about nothing.
 */
fun anchorFraction(focusY: Float, plotTop: Float, plotBottom: Float): Double {
    val height = max(1f, plotBottom - plotTop)
    return ((plotBottom - focusY) / height).toDouble().coerceIn(0.0, 1.0)
}

/** Which axis a two-finger pinch on the plot is scaling. */
enum class PinchAxis {
    /** Fingers side by side: the pinch is about time, as it always has been. */
    TIME,

    /** Fingers one above the other: the pinch is setting the y axis' bounds. */
    VALUE,
}

/**
 * Turns a two-finger pinch into a scale factor for one axis.
 *
 * A pinch on the plot used to mean time and nothing else, and the y axis could
 * only be scaled from the narrow gutter the tick values sit in - a strip most
 * people never find, on a control they have no reason to guess exists. Reaching
 * onto the plot itself and squeezing vertically is what a person actually does
 * when the trace is the wrong height, so that is what this recognises.
 *
 * ## Why the axis is decided once and then held
 *
 * The obvious rule - "scale whichever axis the fingers are moving along, frame
 * by frame" - falls apart in the hand. Fingers are never exactly level or
 * exactly stacked, so a gesture near the diagonal flips between axes several
 * times a second and the chart shudders in both directions at once. The
 * arrangement of the fingers when the pinch is first recognised is a deliberate
 * choice by the person making it, so it is taken as the answer for the whole
 * gesture, and lifting back to one finger does not reopen the question.
 *
 * The split at 45 degrees goes to [PinchAxis.TIME] on a tie, which is the axis
 * a pinch has always scaled: an existing habit is never reinterpreted by a
 * rounding accident.
 *
 * Kept free of Compose and Android types, like [ChartTouchArbiter], so the rule
 * can be exercised on numbers rather than through a gesture recogniser.
 */
class PinchArbiter {

    /** The axis this pinch owns, or null before it has been recognised as one. */
    var axis: PinchAxis? = null
        private set

    /**
     * The canvas y this pinch scales about - where the fingers were centred when
     * the axis was settled - or null before that. Null while [axis] is null.
     *
     * ## Why it is held rather than taken each frame
     *
     * The point of a focal scale is that the value under the fingers does not
     * move, and a focal point taken afresh every frame cannot promise that: two
     * scales about two different points compose into a scale *and a shift*, so a
     * hand drifting a few pixels during a pinch would slide the range while
     * claiming only to be resizing it - which is the exact fault the deadzone
     * exists to prevent, arriving by another door. So the focal point is settled
     * with the axis, on the same reasoning and at the same moment, and held for
     * as long as the fingers are down.
     *
     * A screen position, not a value: after a pan the same place on the glass
     * has a different reading under it, and the anchor should follow the glass.
     */
    var focusY: Float? = null
        private set

    /**
     * One frame of a pinch, given how far the fingers sit from their centroid
     * along each axis before and after it.
     *
     * Spreads rather than raw positions because that is the quantity a pinch is
     * *about*, it generalises past two fingers without a special case, and it is
     * what makes the returned number a plain ratio: fingers twice as far apart
     * vertically as they were is a factor of 2, whatever else the hand did.
     *
     * @param centreY where the fingers are centred this frame, in canvas pixels;
     *   kept as [focusY] if this is the frame that settles the axis.
     * @return the scale factor for [axis] - above 1 when the fingers spread
     *   apart, below when they close - or exactly 1 when this frame says nothing
     *   about scale.
     */
    fun scale(
        previousX: Float,
        previousY: Float,
        currentX: Float,
        currentY: Float,
        centreY: Float = 0f,
    ): Float {
        if (axis == null) {
            // Too small to have a direction: two fingers landing on the same
            // spot say nothing about which axis was meant, and settling on one
            // now would settle it on noise for the rest of the gesture.
            if (currentX < MIN_SPREAD_PX && currentY < MIN_SPREAD_PX) return 1f
            axis = if (currentY > currentX) PinchAxis.VALUE else PinchAxis.TIME
            focusY = centreY
        }
        val vertical = axis == PinchAxis.VALUE
        val before = if (vertical) previousY else previousX
        val after = if (vertical) currentY else currentX
        // A spread of nearly zero along the scaled axis makes the ratio a
        // division by noise - a stacked pair of fingers can report a horizontal
        // spread of a pixel, and 2/1 is a doubling the hand never asked for.
        if (before < MIN_SPREAD_PX || after < MIN_SPREAD_PX) return 1f
        return after / before
    }

    private companion object {
        /**
         * The smallest spread, in canvas pixels, that counts as a direction.
         *
         * Below the platform's touch slop on the tablet this runs on (15 px), so
         * it never rejects a spread a finger could deliberately produce, and far
         * enough above zero that the ratio it guards cannot be manufactured out
         * of a pixel of jitter.
         */
        const val MIN_SPREAD_PX = 4f
    }
}

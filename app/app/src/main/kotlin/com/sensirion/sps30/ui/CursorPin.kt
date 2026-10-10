package com.sensirion.sps30.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.sensirion.sps30.data.ChartData
import kotlin.math.abs

/**
 * The crosshair's pin state machine: the free cursor, or one of three pins.
 *
 * ## Why one value rather than flags
 *
 * Pinning used to be a lone boolean beside the anchor it implied, and the two
 * could disagree - which is exactly how the pin button ended up tappable only
 * when the view happened to run to now. This is one value, and a pinned state
 * carries its own anchor as its payload, so "pinned, but to nothing" cannot be
 * expressed at all.
 *
 * ## The states
 *
 *  - [FreeCursor]: no pin. The crosshair belongs to the finger - touching the
 *    plot moves it (when the crosshair is enabled at all), and the view mode
 *    decides what it holds between touches (see [anchorFor]). This is the state
 *    the crosshair is used in most, and the only one in which touch moves it.
 *  - [ToTime]: pinned to the data point it was on, which is the same thing as a
 *    fixed time mark. Panning, zooming and new readings all leave it on its own
 *    point; the point moves across the plot and the cursor moves with it.
 *  - [FromRight]: pinned a fixed number of plotted points in from the **right
 *    edge** of the graph. As new data arrives in a view that runs to now, the
 *    cursor stays that many points in from the edge, riding the newest region.
 *  - [FromLeft]: pinned a fixed number of plotted points in from the **left
 *    edge** of the graph.
 *
 * ## How the two edge pins relate (the captain's own note, kept on purpose)
 *
 * If the x axis is *not* pinned to a mark on the left - a live window, where
 * both edges slide together - pin-from-left and pin-from-right are equivalent:
 * the count of points on screen holds roughly steady, so an offset from either
 * edge is the same place on the glass. But when the time axis *is* pinned on
 * the left (the view's Pin and Auto modes), points accumulate from the right:
 * pin-from-left then travels leftward with its data point, getting increasingly
 * scrunched as new data arrives, while pin-from-right holds a fixed distance
 * from the edge. "Pinned to distance" (from right) and "pinned to count" (from
 * left) were offered as alternative names for that distinction; the directional
 * names are used throughout because the edge the offset is measured from *is*
 * the distinction, and either alternative would need this paragraph every time.
 *
 * ## Counts are of plotted points
 *
 * The counts are in the points actually drawn - after averaging and
 * decimation - because those are the data points the eye sees on the graph.
 * A count of 0 is the newest (or oldest) point on the plot. A count that
 * reaches past what the window holds clamps to the far edge's point for as
 * long as the view is that narrow; the stored count is not rewritten, so
 * widening the view puts the cursor back where it was pinned.
 *
 * Kept free of Compose state so the machine can be exercised as plain values;
 * the screen holds the current state and calls the transitions below.
 */
sealed interface CursorPin {
    /** No pin engaged: the crosshair is the finger's to move. */
    data object FreeCursor : CursorPin

    /** Pinned to the instant [epoch]: travels with its own data point. */
    data class ToTime(val epoch: Double) : CursorPin

    /** Pinned [count] plotted points in from the right edge; 0 is the newest. */
    data class FromRight(val count: Int) : CursorPin

    /** Pinned [count] plotted points in from the left edge; 0 is the oldest. */
    data class FromLeft(val count: Int) : CursorPin
}

/**
 * The instant a pinned cursor sits on, given what is on screen - or null for
 * the free cursor, whose position is the [ChartCursor] anchor's business, and
 * null when there is nothing drawn for an edge pin to count.
 */
fun CursorPin.epochIn(data: ChartData?): Double? = when (this) {
    CursorPin.FreeCursor -> null
    is CursorPin.ToTime -> epoch
    is CursorPin.FromRight -> data?.x?.takeIf { it.isNotEmpty() }
        ?.let { it[(it.size - 1 - count).coerceIn(0, it.size - 1)] }
    is CursorPin.FromLeft -> data?.x?.takeIf { it.isNotEmpty() }
        ?.let { it[count.coerceIn(0, it.size - 1)] }
}

/**
 * Whether touching the plot moves the crosshair.
 *
 * True only in the free-cursor state with the crosshair enabled. In any pinned
 * state a touch must not affect the cursor at all: a single finger is then read
 * exactly as it is with the crosshair switched off, which on this plot means it
 * pans. This is the one place that rule is written; the chart's gesture layer
 * consumes the answer rather than restating the condition.
 */
fun cursorMovableByTouch(enabled: Boolean, pin: CursorPin): Boolean =
    enabled && pin is CursorPin.FreeCursor

/** Index of the drawn sample nearest [epoch], or null with nothing drawn. */
internal fun nearestIndex(data: ChartData?, epoch: Double): Int? {
    val x = data?.x ?: return null
    if (x.isEmpty()) return null
    return x.indices.minByOrNull { abs(x[it] - epoch) }
}

/**
 * Pin the point nearest [epoch] by its offset from the right edge, or null with
 * nothing drawn to count. Engaging never moves the crosshair: the count is
 * however many points sit between it and the edge right now.
 */
fun pinFromRight(data: ChartData?, epoch: Double): CursorPin? =
    nearestIndex(data, epoch)?.let { CursorPin.FromRight(data!!.x.size - 1 - it) }

/** Pin the point nearest [epoch] by its offset from the left edge. */
fun pinFromLeft(data: ChartData?, epoch: Double): CursorPin? =
    nearestIndex(data, epoch)?.let { CursorPin.FromLeft(it) }

/**
 * The instant of the highest-value point on the plot, or null with nothing
 * drawn - what Snap to peak jumps the cursor to.
 *
 * Taken over the **visible window** and over the **series the user has switched
 * on**. When both series are plotted the overall maximum wins, which in
 * practice is PM2.5's peak: the cuts are cumulative, so PM2.5 is never below
 * PM1.0. Hide PM2.5 and the peak is PM1.0's, because a snap onto a curve that
 * is not on screen would land the readout on nothing. Equal peaks go to the
 * earliest, so the answer is stable while the data is.
 *
 * The snap pins the cursor to that instant as an ordinary [CursorPin.ToTime],
 * so if the view then changes and some other point becomes the visible maximum,
 * the cursor stays on the point it snapped to - it holds a moment, not the
 * title of "current peak".
 */
fun peakEpoch(data: ChartData?, visible: Collection<String>): Double? {
    if (data == null || data.x.isEmpty()) return null
    var bestEpoch: Double? = null
    var best = Double.NEGATIVE_INFINITY
    for ((field, values) in data.series) {
        if (field !in visible) continue
        val n = minOf(values.size, data.x.size)
        for (i in 0 until n) {
            if (values[i] > best) {
                best = values[i]
                bestEpoch = data.x[i]
            }
        }
    }
    return bestEpoch
}

/**
 * The pin machine's saved form - one statement of it, used by [CursorState]'s
 * saver, which is what actually carries the whole cursor family (this pin
 * included) across a rotation.
 */
val CursorPinSaver: Saver<CursorPin, Any> = listSaver(
    save = { pin ->
        when (pin) {
            CursorPin.FreeCursor -> listOf("free", 0.0)
            is CursorPin.ToTime -> listOf("time", pin.epoch)
            is CursorPin.FromRight -> listOf("right", pin.count.toDouble())
            is CursorPin.FromLeft -> listOf("left", pin.count.toDouble())
        }
    },
    restore = { saved ->
        val value = saved.getOrNull(1) as? Double ?: 0.0
        when (saved.getOrNull(0) as? String) {
            "time" -> CursorPin.ToTime(value)
            "right" -> CursorPin.FromRight(value.toInt())
            "left" -> CursorPin.FromLeft(value.toInt())
            else -> CursorPin.FreeCursor
        }
    },
)

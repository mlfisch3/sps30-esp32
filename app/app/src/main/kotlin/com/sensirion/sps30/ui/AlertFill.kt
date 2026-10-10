package com.sensirion.sps30.ui

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Shading the area between a series and its alert threshold, where the series
 * is above it.
 *
 * ## What this replaced, and why
 *
 * The breaching stretch of the watched series used to be redrawn in red, over
 * the trace and heavier than it. Seen against a real excursion that turned out
 * to be exactly backwards: the recoloured stretch sat *on* the data, so the one
 * part of the record the user most wants to read closely was the part with a
 * warning painted over it. What an excursion needs is context - how far over,
 * for how long - and context belongs under the data, not on top of it.
 *
 * So: the trace and its markers keep their series colour everywhere, and the
 * region between the curve and the threshold line fills with a pale, translucent
 * red. A sharp excursion reads as a tall thin triangle; a long one as a band
 * whose height is the margin over the line. The alpha is deliberately faint -
 * this is quiet context, and gridlines, the other series and any overlapping
 * fill must stay readable through it.
 *
 * ## Per series
 *
 * Each drawn series is filled against *its own* threshold (the lowest enabled
 * rule for that field that asked to be drawn - see `ChartRender.alertFills`).
 * If both series breach, both regions fill, and at this alpha the overlap
 * merely deepens rather than occludes.
 *
 * ## What "above threshold" means for an aggregated point
 *
 * At wide windows a drawn point is a bucket's mean, and the raw seconds inside
 * one bucket may straddle the line. The fill is bounded by the *drawn curve*,
 * so it takes the drawn value's word for it: a point is above threshold exactly
 * when its plotted (possibly aggregated) value is at or above the threshold,
 * `>=` to match [com.sensirion.sps30.data.AlertEngine]'s "at or above". The
 * chart shows bucket means everywhere else - the trace, the crosshair readout -
 * and a fill that claimed to know more than the curve it hangs from would
 * disagree with the very line that bounds it.
 */

/**
 * One contiguous run of a series above its threshold, as points along the
 * curve in data space (epoch, value), in x order.
 *
 * Where the run enters or leaves through the threshold inside the view, the
 * first or last point is the *interpolated crossing* - the exact spot the
 * segment between the neighbouring samples meets the line - so the region
 * comes to a point instead of starting blockily at the first sample above.
 * A run cut short by the edge of the view, or by a gap in the record, ends at
 * its last real sample instead: there is no crossing there to interpolate.
 */
internal class AlertExcursion(val xs: DoubleArray, val ys: DoubleArray)

/**
 * The runs of `vals >= threshold`, split wherever the record has a gap.
 *
 * Pure, so the boundary handling - the part that can be got wrong - is
 * checkable without a Canvas. The samples arrive already windowed, so an
 * excursion in progress at either edge of the view simply begins or ends at
 * the boundary sample and fills to the edge.
 *
 * Split at gaps for the same reason the trace lifts its pen there: nothing was
 * recorded across the hole, so a fill spanning it would assert the air was bad
 * through a stretch nobody measured.
 */
internal fun alertExcursions(
    x: DoubleArray,
    vals: DoubleArray,
    threshold: Double,
    gapSeconds: Double? = null,
): List<AlertExcursion> {
    val n = minOf(x.size, vals.size)
    val out = ArrayList<AlertExcursion>()
    var runX = ArrayList<Double>()
    var runY = ArrayList<Double>()
    fun close() {
        if (runX.isEmpty()) return
        out.add(AlertExcursion(runX.toDoubleArray(), runY.toDoubleArray()))
        runX = ArrayList()
        runY = ArrayList()
    }
    for (j in 0 until n) {
        val afterGap = j > 0 && gapSeconds != null && x[j] - x[j - 1] > gapSeconds
        // A run may not continue across a gap, and neither may a crossing be
        // interpolated over one - both would invent data inside the hole.
        if (afterGap) close()
        if (vals[j] >= threshold) {
            if (runX.isEmpty() && j > 0 && !afterGap && vals[j - 1] < threshold) {
                val t = (threshold - vals[j - 1]) / (vals[j] - vals[j - 1])
                runX.add(x[j - 1] + t * (x[j] - x[j - 1]))
                runY.add(threshold)
            }
            runX.add(x[j])
            runY.add(vals[j])
        } else if (runX.isNotEmpty()) {
            val t = (threshold - vals[j - 1]) / (vals[j] - vals[j - 1])
            runX.add(x[j - 1] + t * (x[j] - x[j - 1]))
            runY.add(threshold)
            close()
        }
    }
    close()
    return out
}

/**
 * Fill each excursion between the curve and the threshold line.
 *
 * The path runs along the curve and closes along the threshold, so an
 * interpolated crossing at either end brings the region to a point there. A
 * run of a single sample has a curve of zero width and encloses nothing;
 * skipped rather than widened, because the sample's own marker already says
 * everything one sample can.
 *
 * [yOf] clamps to the plot, so a curve that leaves the top of the axis fills
 * up to the plot's edge rather than past it.
 */
internal fun DrawScope.drawAlertFill(
    excursions: List<AlertExcursion>,
    threshold: Double,
    xOf: (Double) -> Float,
    yOf: (Double) -> Float,
) {
    val ty = yOf(threshold)
    for (e in excursions) {
        if (e.xs.size < 2) continue
        val path = Path()
        path.moveTo(xOf(e.xs[0]), ty)
        for (i in e.xs.indices) path.lineTo(xOf(e.xs[i]), yOf(e.ys[i]))
        path.lineTo(xOf(e.xs[e.xs.size - 1]), ty)
        path.close()
        drawPath(path, ALERT_FILL_COLOR)
    }
}

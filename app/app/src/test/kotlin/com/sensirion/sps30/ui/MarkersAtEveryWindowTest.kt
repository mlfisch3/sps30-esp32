package com.sensirion.sps30.ui

import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.TimeSeriesHistory
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markers survive every window the user can actually choose.
 *
 * [PlotStyleGeometryTest] checks the thinning rule against points laid out by
 * hand; this drives the whole pipeline the app drives - the point budget, the
 * stride decimator, auto's averaging buckets - and asks the question the markers
 * exist to answer at each preset in turn: *can you see where the measurements
 * are?*
 *
 * It is here because the answer was no. The rings were drawn only when every
 * drawn point could carry one, which on an S24 meant the 1 minute window and
 * nothing wider: at 5 minutes and beyond the plot was a bare line with no way to
 * tell three readings on a diagonal from two. A rule checked only against
 * hand-made spacings could not have caught that, because the thing that was wrong
 * was which spacings the app really produces.
 */
class MarkersAtEveryWindowTest {

    /**
     * The plot is the canvas less the chart's margins: ~900px on an S24 in
     * portrait, ~2380px on the tablet in landscape. Both at density 3, so these
     * are the canvas pixels [DrawScope] measures in.
     */
    private val widths = mapOf("phone" to 902f, "tablet" to 2382f)

    /** Two days of 1 Hz readings, with structure at day, room and second scale. */
    private fun history(now: Double, seconds: Int): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = 300_000.0, nowSeconds = { now })
        for (i in 0 until seconds) {
            val v = 6.0 +
                3.0 * sin(i * 2 * Math.PI / 43_200.0) +
                1.2 * sin(i * 2 * Math.PI / 1_700.0) +
                0.35 * sin(i * 2 * Math.PI / 47.0)
            h.add(
                csv = "windows.csv",
                index = i.toString(),
                timestamp = java.time.Instant.ofEpochSecond((now - (seconds - 1 - i)).toLong()).toString(),
                values = mapOf("pm2_5" to v, "pm1_0" to v * 0.7),
            )
        }
        return h
    }

    /** Where the renderer puts each drawn point, in canvas pixels. */
    private fun xPixels(data: ChartData, plotW: Float): FloatArray {
        val span = max(1e-6, data.end - data.start)
        return FloatArray(data.x.size) { ((data.x[it] - data.start) / span * plotW).toFloat() }
    }

    @Test
    fun everyWindowPresetShowsWhereTheMeasurementsAre() {
        // 2026-08-06, past the axis's plausibility floor.
        val now = 1_786_320_000.0
        val hist = history(now, 2 * 24 * 3600)
        // Every preset the app ships with, plus Auto over the full retention span,
        // which is the widest view there is.
        val windows = AppConfig.DEFAULT_PRESETS.map { it.label to (it.seconds to false) } +
            listOf("auto" to (AppConfig.ALL_SECONDS to true))

        for ((name, plotW) in widths) {
            for ((label, spec) in windows) {
                val (seconds, auto) = spec
                val view = ChartView(windowSeconds = seconds)
                if (auto) view.setAutoInterval()
                // The app's point budget is the plot's own pixel width, clamped.
                val budget = plotW.toInt().coerceIn(120, 2000)
                val data = view.compute(
                    hist, now, maxPoints = budget, visible = listOf("pm2_5", "pm1_0"),
                )
                val where = "$name / $label (${data.x.size} points across ${plotW}px)"
                assertTrue("$where drew nothing at all", data.x.isNotEmpty())

                val xs = xPixels(data, plotW)
                val ringed = ringedPoints(xs)
                val drawn = ringed.count { it }

                // Rings land on points, so the pitch achieved is the point spacing
                // rounded up to [MarkerGeometry.pitch] and can reach twice it. Even
                // then the plot carries rings across half its width.
                val fewest = minOf(
                    data.x.size,
                    (plotW / (2 * MarkerGeometry.pitch)).toInt(),
                )
                assertTrue(
                    "$where drew $drawn rings, fewer than the $fewest that fit - this " +
                        "window is back to a bare line and the user cannot tell how " +
                        "many measurements lie on a segment",
                    drawn >= fewest,
                )

                // And they are still rings, not a band: none closer than one is wide.
                var last: Float? = null
                for (j in xs.indices) {
                    if (!ringed[j]) continue
                    last?.let {
                        assertTrue(
                            "$where put two rings ${xs[j] - it}px apart, closer than " +
                                "the ${MarkerGeometry.pitch}px a ring is wide",
                            xs[j] - it >= MarkerGeometry.pitch - 0.001f,
                        )
                    }
                    last = xs[j]
                }

                // The live sample is the anchor, so it always carries one.
                assertTrue("$where left the newest sample unringed", ringed[ringed.size - 1])
            }
        }
    }

    /**
     * Zoomed in far enough that every reading fits, nothing is thinned: the rings
     * are one-to-one with the measurements, which is the strongest thing they can
     * say and the thing the 1 minute window already said before any of this.
     */
    @Test
    fun aMinutesWindowStillRingsEverySingleReading() {
        val now = 1_786_320_000.0
        val hist = history(now, 3600)
        for ((name, plotW) in widths) {
            val view = ChartView(windowSeconds = 60.0)
            val data = view.compute(
                hist, now, maxPoints = plotW.toInt().coerceIn(120, 2000),
                visible = listOf("pm2_5", "pm1_0"),
            )
            val ringed = ringedPoints(xPixels(data, plotW))
            assertTrue(
                "$name: a minute's window drew ${ringed.count { it }} rings on " +
                    "${data.x.size} readings - the one window that was right before " +
                    "has lost markers",
                ringed.all { it },
            )
        }
    }
}

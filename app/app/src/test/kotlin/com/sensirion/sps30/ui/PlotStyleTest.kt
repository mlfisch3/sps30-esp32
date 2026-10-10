package com.sensirion.sps30.ui

import androidx.compose.ui.graphics.Color
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.PlotStyle
import com.sensirion.sps30.data.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A flat trace of [n] samples, so a horizontal line makes the marker geometry
 * legible in a single column of pixels.
 */
internal fun flatChart(n: Int, value: Double = 6.0): ChartData {
    val start = 1000.0
    val end = start + 60.0 * n
    return ChartData(
        x = DoubleArray(n) { start + it * 60.0 },
        series = mapOf("pm2_5" to DoubleArray(n) { value }),
        start = start,
        end = end,
        xTicks = doubleArrayOf(start + (end - start) / 2),
        xLabels = listOf("01:25"),
        timeZoneLabel = "EDT",
        dataYMin = value,
        dataYMax = value,
        spanSeconds = end - start,
        sampleCount = n,
        mode = ChartMode.MOVING,
        viewMode = ViewMode.LIVE,
        windowSeconds = end - start,
        yAuto = true,
        yMin = null,
        yMax = null,
        yFloor = 0.0,
        yCeil = 10.0,
        yStep = 2.0,
        xFrozen = false,
        hasReferencePin = false,
    )
}

/**
 * The arithmetic of the hollow-marker style, which is the half of it that can be
 * checked without drawing anything.
 *
 * What was asked for was a specific look and it was described precisely: the
 * thinnest line that renders cleanly, a hollow ring on every sample, the ring's
 * outline as thin as the line, and the ring no wider than it takes to show the
 * line passing through it with a little clear space either side. Those are
 * relations between four numbers, and any one of them edged later - a fatter
 * outline, a smaller radius - quietly turns the ring into a bead on a string.
 */
class PlotStyleGeometryTest {

    private val g = MarkerGeometry

    @Test
    fun theRingIsHollowWithRoomForTheLineAndSomeDaylight() {
        val hole = g.diameter - 2 * g.stroke
        assertTrue(
            "the ring's hole is ${g.diameter - 2 * g.stroke}px across and the line " +
                "through it is ${g.lineWidth}px - there is no clear space left inside " +
                "the marker, so it reads as a bead on the trace",
            hole > g.lineWidth,
        )
        assertEquals(
            "the clear space either side of the line inside the ring",
            g.gap,
            (hole - g.lineWidth) / 2f,
            0.001f,
        )
        assertTrue("the gap must actually be visible", g.gap >= 2f)
    }

    @Test
    fun theRingReadsAsACircleAndNotAsAThickeningOfTheLine() {
        assertTrue(
            "the ring is ${g.diameter}px across against a ${g.lineWidth}px line, which " +
                "is not clearly bigger than the line it sits on",
            g.diameter >= 4 * g.lineWidth,
        )
        assertTrue(
            "the ring's outline (${g.stroke}px) is heavier than the connecting line " +
                "(${g.lineWidth}px), which is not the thin-edged marker asked for",
            g.stroke <= g.lineWidth,
        )
    }

    /** [n] points spread evenly across [plotW] pixels, as the renderer places them. */
    private fun spread(n: Int, plotW: Float = 902f) =
        FloatArray(n) { if (n < 2) 0f else it * plotW / (n - 1) }

    /**
     * The one judgement call in this style, stated as a number.
     *
     * Below the pitch every point is ringed and the chart says exactly where each
     * measurement is. Above it the rings thin to the closest pitch that still
     * reads as circles - never to none, because a bare line is the one answer that
     * tells the user nothing about where the measurements are, which is the entire
     * reason the rings exist.
     */
    @Test
    fun everyPointIsRingedUntilTheyWouldOverlap() {
        // The plot is the canvas less its margins; ~900px on the S24 in portrait.
        val plotW = 902f
        for (n in listOf(2, 30, 60, 91)) {
            val ringed = ringedPoints(spread(n, plotW))
            assertEquals(
                "$n points are ${plotW / (n - 1)}px apart, which is room for a ring " +
                    "on each, and some went unringed",
                n, ringed.count { it },
            )
        }
        // A single sample has no neighbour to crowd it, and is the one point that
        // most needs saying is there.
        assertEquals("a lone reading", 1, ringedPoints(spread(1, plotW)).count { it })
        assertEquals("nothing at all", 0, ringedPoints(FloatArray(0)).size)
    }

    @Test
    fun aCrowdedWindowStillShowsWhereTheMeasurementsAre() {
        val plotW = 902f
        val most = (plotW / MarkerGeometry.pitch).toInt() + 1
        // Rings land on points, so the achievable pitch is the point spacing
        // rounded up to the pitch and can be up to twice it - which still leaves
        // the plot half-full of rings at its very worst.
        val fewest = (plotW / (2 * MarkerGeometry.pitch)).toInt()
        for (n in listOf(92, 300, 901, 3000)) {
            val xs = spread(n, plotW)
            val ringed = ringedPoints(xs)
            val drawn = ringed.count { it }
            assertTrue(
                "$n points across ${plotW}px drew $drawn rings, under $fewest - a dense " +
                    "window is back to a bare line, which is what this was meant to stop",
                drawn >= fewest,
            )
            assertTrue("and it drew more rings than the pitch has room for", drawn <= most)
            // No two rings closer than their own width, or they smear.
            var last: Float? = null
            for (j in xs.indices) {
                if (!ringed[j]) continue
                last?.let {
                    assertTrue(
                        "two rings ${xs[j] - it}px apart at $n points, closer than the " +
                            "${MarkerGeometry.pitch}px a ring is wide",
                        xs[j] - it >= MarkerGeometry.pitch - 0.001f,
                    )
                }
                last = xs[j]
            }
        }
    }

    /**
     * The live sample is the anchor, so the pattern does not crawl leftwards as
     * the window slides and the filled "you are here" dot always has its ring.
     */
    @Test
    fun theNewestPointAlwaysCarriesARing() {
        for (n in listOf(1, 2, 95, 3000)) {
            val ringed = ringedPoints(spread(n))
            assertTrue("the newest of $n points went unringed", ringed[n - 1])
        }
    }

    /**
     * Rings are placed by pixel distance, not by counting indices, so a run of
     * points bunched after a gap cannot stack two rings on one another.
     */
    @Test
    fun unevenlySpacedPointsAreStillRingedEvenly() {
        // A sparse first half and a bunched second half, as a recording gap or an
        // under-filled bucket produces.
        val xs = FloatArray(200) { if (it < 20) it * 30f else 600f + (it - 20) * 1.5f }
        val ringed = ringedPoints(xs)
        var last: Float? = null
        for (j in xs.indices) {
            if (!ringed[j]) continue
            last?.let {
                assertTrue(
                    "two rings ${xs[j] - it}px apart where the points bunch up",
                    xs[j] - it >= MarkerGeometry.pitch - 0.001f,
                )
            }
            last = xs[j]
        }
        assertEquals("every point in the sparse half should keep its ring",
            20, (0 until 20).count { ringed[it] })
    }

    @Test
    fun theMarkerStyleIsWhatTheAppStartsWith() {
        assertEquals(PlotStyle.MARKERS, AppConfig.default().plotStyle)
    }
}

/**
 * And the half that can only be checked by drawing it.
 *
 * The numbers above can all be right while the marker still comes out a filled
 * dot - a `Stroke` swapped for a `Fill`, the rings drawn under the line instead
 * of over it - so this renders the plot and reads the pixels back. A column
 * straight down through a ring must show three separate bands of ink with real
 * background between them: the top of the ring, the line, the bottom of the ring.
 * A filled dot is one band, and so is a smudge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class PlotStyleRenderTest {

    private val w = 1080
    private val h = 900

    /** Vertical runs of ink in column [x], as (first row, last row) pairs. */
    private fun bands(px: IntArray, x: Int, rows: IntRange): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = -1
        for (y in rows) {
            val inked = android.graphics.Color.green(px[y * w + x]) > 60
            if (inked && from < 0) from = y
            if (!inked && from >= 0) { out += from..(y - 1); from = -1 }
        }
        if (from >= 0) out += from..rows.last
        return out
    }

    /** The plot drawn on black in one flat green trace, as a pixel array. */
    private fun pixels(n: Int, style: PlotStyle): IntArray {
        val bitmap = renderChartToBitmap(
            render = ChartRender(
                data = flatChart(n),
                visible = listOf("pm2_5"),
                plotStyle = style,
            ),
            widthPx = w,
            heightPx = h,
            background = Color.Black,
            // Everything but the trace painted out, so "ink" means "the trace".
            axisColor = Color.Black,
            gridColor = Color.Black,
            labelColor = Color.Black,
            colors = mapOf("pm2_5" to Color.Green),
        )
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /** The row the flat trace was drawn on: the one carrying the most ink. */
    private fun traceRow(px: IntArray): Int = (0 until h).maxByOrNull { y ->
        (0 until w).count { android.graphics.Color.green(px[y * w + it]) > 60 }
    }!!

    @Test
    fun everySampleIsRingedAndTheLineShowsThroughTheRing() {
        val px = pixels(40, PlotStyle.MARKERS)
        val row = traceRow(px)
        val rows = (row - 14)..(row + 14)
        // The last sample is a filled dot on purpose - "you are here" - so the
        // left half of the plot is where the plain rings are.
        val profiles = (200 until w / 2).map { it to bands(px, it, rows) }
        val rings = profiles.filter { it.second.size == 3 }
        val plain = profiles.filter { it.second.size == 1 }
        assertTrue(
            "no column anywhere down the trace showed ring/line/ring - the markers " +
                "are drawn filled, or under the line, or not at all",
            rings.size >= 20,
        )
        assertTrue(
            "every column through the trace had three bands, so the rings are not " +
                "markers on samples - they are a tube around the whole line",
            plain.size > rings.size,
        )

        val (x, bandsHere) = rings[rings.size / 2]
        val (top, line, bottom) = Triple(bandsHere[0], bandsHere[1], bandsHere[2])
        assertTrue(
            "at x=$x the middle band ($line) is not the trace, which is on row $row",
            row in line,
        )
        // Clear background between the ring and the line, on both sides. This is
        // the "a little bit of empty space around it" that sets the diameter.
        assertTrue(
            "at x=$x the top of the ring ($top) touches the line ($line) - there is " +
                "no daylight inside the marker",
            line.first - top.last >= 2,
        )
        assertTrue(
            "at x=$x the bottom of the ring ($bottom) touches the line ($line)",
            bottom.first - line.last >= 2,
        )
        // And the ring is the marker's full height, not a stray pixel.
        assertTrue(
            "at x=$x the marker spans ${bottom.last - top.first + 1}px, not the " +
                "${MarkerGeometry.diameter}px the ring is meant to be",
            bottom.last - top.first + 1 >= MarkerGeometry.diameter - 1,
        )
    }

    @Test
    fun theHairlineIsThinnerThanThePlainLineItReplaces() {
        val thin = pixels(40, PlotStyle.MARKERS)
        val plain = pixels(40, PlotStyle.LINE)
        // Measured between markers, where the thin style is only its line.
        fun lineThickness(px: IntArray): Int {
            val row = traceRow(px)
            val rows = (row - 14)..(row + 14)
            return (200 until w / 2)
                .map { bands(px, it, rows) }
                .filter { it.size == 1 }
                .minOf { it[0].last - it[0].first + 1 }
        }
        val thinPx = lineThickness(thin)
        val plainPx = lineThickness(plain)
        assertTrue(
            "the marker style's connecting line measured ${thinPx}px against the " +
                "plain style's ${plainPx}px - it is not the thinner line asked for",
            thinPx < plainPx,
        )
    }

    @Test
    fun thePlainStyleDrawsNoRingsAtAll() {
        val px = pixels(40, PlotStyle.LINE)
        val row = traceRow(px)
        val rows = (row - 14)..(row + 14)
        val ringed = (200 until w / 2).count { bands(px, it, rows).size > 1 }
        assertEquals("the line-only style put markers on the trace", 0, ringed)
    }

    /**
     * A multi-hour window, where thousands of samples share nine hundred pixels.
     *
     * The rings thin out rather than disappearing, and the proof that they thinned
     * *enough* is that the trace is still visible between them: columns of bare
     * line, with three-band ring columns punctuating them. A ring on every sample
     * would leave no bare column at all, which is the band of colour this guards
     * against; no rings at all would leave no three-band column, which is the
     * silence it also guards against.
     */
    @Test
    fun aDenseWindowStillShowsWhereTheMeasurementsAre() {
        val px = pixels(3000, PlotStyle.MARKERS)
        val row = traceRow(px)
        val rows = (row - 14)..(row + 14)
        val profiles = (200 until w / 2).map { bands(px, it, rows) }
        val rings = profiles.count { it.size == 3 }
        val bare = profiles.count { it.size == 1 }
        assertTrue(
            "a dense window drew $rings ringed columns - the markers vanish at " +
                "exactly the window the user needs them at",
            rings >= 20,
        )
        assertTrue(
            "$bare of ${profiles.size} columns were bare line - the rings ran " +
                "together into a band of colour instead of thinning out",
            bare > rings,
        )
    }
}

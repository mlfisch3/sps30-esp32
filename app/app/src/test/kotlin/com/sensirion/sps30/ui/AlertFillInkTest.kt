package com.sensirion.sps30.ui

import androidx.compose.ui.graphics.Color
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.ViewMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An excursion is context under the data, not a warning painted over it.
 *
 * The breaching stretch of the watched series used to be redrawn in heavy
 * alert red, on top of the trace. What the plot draws now is the opposite
 * arrangement: the trace and its markers keep their series colour through the
 * excursion, and the region between the curve and the threshold line carries a
 * pale translucent wash. Like [AlertLineTest], this renders the chart and
 * reads the pixels back, because "the data points do not turn red" and "the
 * wash is pale" are claims about ink.
 *
 * The geometry of where the region starts and ends is [AlertFillTest]'s
 * subject; here the chart breaches and the ink is examined for three things -
 * saturated red confined to the dashed line's rows, faint red spread over many
 * rows above it, and the series' own green surviving inside the excursion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class AlertFillInkTest {

    private val w = 1080
    private val h = 900

    /**
     * A trace that sits at 3, climbs to a plateau at 9 for the middle of the
     * view, and comes back down, on a 0..10 axis with the alert at 6: one
     * clean excursion well inside the view, with real area between the curve
     * and the line.
     */
    private fun breachingChart(): ChartData {
        val n = 40
        val start = 1000.0
        val end = start + 60.0 * n
        val values = DoubleArray(n) { if (it in 14..25) 9.0 else 3.0 }
        return ChartData(
            x = DoubleArray(n) { start + it * 60.0 },
            series = mapOf("pm2_5" to values),
            start = start,
            end = end,
            xTicks = doubleArrayOf(start + (end - start) / 2),
            xLabels = listOf("01:25"),
            timeZoneLabel = "EDT",
            dataYMin = 3.0,
            dataYMax = 9.0,
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

    private fun pixels(): IntArray {
        val bitmap = renderChartToBitmap(
            render = ChartRender(
                data = breachingChart(),
                visible = listOf("pm2_5"),
                alertThreshold = 6.0,
                alertFills = mapOf("pm2_5" to 6.0),
            ),
            widthPx = w,
            heightPx = h,
            background = Color.Black,
            axisColor = Color.Black,
            gridColor = Color.Black,
            labelColor = Color.Black,
            colors = mapOf("pm2_5" to Color.Green),
        )
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /** Full-strength alert red: the dashed line, or the old overdrawn trace. */
    private fun isLoudRed(p: Int): Boolean {
        val r = android.graphics.Color.red(p)
        return r > 150 && r > 2 * android.graphics.Color.green(p)
    }

    /** The wash: unmistakably red, nowhere near the line's strength. */
    private fun isPaleRed(p: Int): Boolean {
        val r = android.graphics.Color.red(p)
        val g = android.graphics.Color.green(p)
        return r in 12..90 && r > g + 8 && !isLoudRed(p)
    }

    private fun isSeriesGreen(p: Int): Boolean {
        val g = android.graphics.Color.green(p)
        return g > 150 && g > 2 * android.graphics.Color.red(p)
    }

    private fun rowsWhere(px: IntArray, test: (Int) -> Boolean): Set<Int> =
        (0 until h).filter { y -> (0 until w).any { test(px[y * w + it]) } }.toSet()

    @Test
    fun theBreachingTraceIsNotRedrawnInRed() {
        val loud = rowsWhere(pixels(), ::isLoudRed)
        assertTrue("no alert-coloured ink anywhere - even the line has gone", loud.isNotEmpty())
        val span = loud.max() - loud.min() + 1
        // The dashed threshold line is 2.5px plus antialiasing. The old
        // overdraw followed the trace up to its plateau, a couple of hundred
        // rows away: any recolouring of the curve blows this wide open.
        assertTrue(
            "saturated alert red spans $span rows (${loud.min()}..${loud.max()}) - " +
                "something beyond the dashed line is drawn at full strength",
            span <= 6,
        )
    }

    @Test
    fun theExcursionCarriesAPaleWashAboveTheLine() {
        val px = pixels()
        val loud = rowsWhere(px, ::isLoudRed)
        val pale = rowsWhere(px, ::isPaleRed)
        assertTrue("no pale red ink anywhere - the excursion region is not filled", pale.isNotEmpty())
        // The wash fills from the threshold line up to the plateau: 6 to 9 on
        // a 0..10 axis is over a quarter of the plot's height.
        assertTrue(
            "pale red ink covers only ${pale.size} rows, which is a smear, not the " +
                "region between the curve and the line",
            pale.size > 100,
        )
        // And it is a region *above* the line, not a glow around it.
        val lineTop = loud.min()
        assertTrue(
            "the wash does not reach well above the threshold line",
            pale.count { it < lineTop } > 100,
        )
    }

    @Test
    fun theTraceKeepsItsOwnColourThroughTheExcursion() {
        val px = pixels()
        val loud = rowsWhere(px, ::isLoudRed)
        val green = rowsWhere(px, ::isSeriesGreen)
        // The plateau at 9 sits far above the alert line at 6: the series'
        // own colour must still be laid down up there, inside the excursion.
        val lineTop = loud.min()
        assertTrue(
            "no series-coloured ink above the threshold line - the excursion's " +
                "trace has lost its colour",
            green.count { it < lineTop - 6 } > 10,
        )
    }
}

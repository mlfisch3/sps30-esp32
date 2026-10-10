package com.sensirion.sps30.ui

import androidx.compose.ui.graphics.Color
import com.sensirion.sps30.Slow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The alert level is a line and nothing else.
 *
 * It used to write "alert 20.0" onto the plot at the end of its own line. A rule
 * drawn across the chart in the alert colour already says what it is, so the text
 * was restating the obvious in the one place there is no room to spare - and,
 * once the y axis could be pinched open, it arrived from the top of the plot and
 * overprinted the crosshair readout, which is the annotation the user's finger is
 * actually asking for. Removing it settles both at once, so the machinery that
 * used to shuffle the label out of the readout's way is gone too.
 *
 * This renders the chart and reads the pixels back, because "no text" is a claim
 * about ink and nothing short of ink can check it: the line is drawn from one
 * side of the plot to the other on a single row, so any alert-coloured ink more
 * than a line's thickness away from that row is a label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class AlertLineTest {

    private val w = 1080
    private val h = 900

    /**
     * The plot on black with everything but the alert mark painted out.
     *
     * The trace sits flat at 6 on a 0..10 axis and the threshold is 8, so it never
     * breaches: the only red on the canvas is the threshold line itself, and the
     * green trace is nowhere near the row it lands on.
     */
    private fun pixels(): IntArray {
        val bitmap = renderChartToBitmap(
            render = ChartRender(
                data = flatChart(n = 40, value = 6.0),
                visible = listOf("pm2_5"),
                alertThreshold = 8.0,
                // The note that shares the top strip, so the label - if one came
                // back - would have something to collide with.
                viewInfo = "window 40m · 40 pts",
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

    /** Alert red, however faintly the antialiasing laid it down. */
    private fun isAlertInk(p: Int): Boolean {
        val r = android.graphics.Color.red(p)
        return r > 40 && r > 2 * android.graphics.Color.green(p)
    }

    private fun alertRows(px: IntArray): Map<Int, Int> =
        (0 until h).associateWith { y -> (0 until w).count { isAlertInk(px[y * w + it]) } }
            .filterValues { it > 0 }

    @Test
    fun theAlertLineIsStillDrawnRightAcrossThePlot() {
        val rows = alertRows(pixels())
        assertTrue("no alert-coloured ink anywhere - the line itself has gone", rows.isNotEmpty())
        val busiest = rows.maxByOrNull { it.value }!!
        // Dashed 14 on, 10 off, over a plot ~900px wide: comfortably past 300px of
        // ink on its row, and nothing like that from any label.
        assertTrue(
            "the widest run of alert ink is only ${busiest.value}px across, which is " +
                "not a rule spanning the plot",
            busiest.value > 300,
        )
    }

    @Test
    fun theAlertLineCarriesNoLabel() {
        val rows = alertRows(pixels())
        val span = rows.keys.max() - rows.keys.min() + 1
        // 2.5px of line, plus antialiasing. The label was 50px type lifted 12px
        // clear of the line, so it put ink some 30 rows above it: any label at all
        // blows this open by an order of magnitude.
        assertTrue(
            "alert-coloured ink spans $span rows (${rows.keys.min()}..${rows.keys.max()}) " +
                "when the line alone is 2.5px thick - there is text on the alert line",
            span <= 6,
        )
    }
}

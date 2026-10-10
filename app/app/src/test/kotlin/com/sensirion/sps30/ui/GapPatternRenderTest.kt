package com.sensirion.sps30.ui

import androidx.compose.ui.graphics.Color
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The four fills as ink, and the absence of the key the plot used to carry.
 *
 * [GapPatternTest] checks that each case reaches the renderer as its own
 * described fill, which is a claim about data. This is the other half and it can
 * only be settled in pixels: "told apart at a glance" is a claim about what is on
 * the screen, and four `when` arms that all quietly painted the same amber would
 * satisfy every assertion in the other file.
 *
 * Gaps are handed straight to [ChartRender] here, which is the renderer's own
 * contract: it draws whatever it is given. Whether it is given anything is
 * `chartRender`'s decision, gated by `AppConfig.markGaps` and settled in
 * `ChartRenderTest` - off by default, so what these tests exercise is the plot
 * as it looks with the marking switched on.
 *
 * The chart is drawn on black with the axes, gridlines and the trace itself
 * painted black too, so the only ink inside the band is the fill under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class GapPatternRenderTest {

    private val w = 1080
    private val h = 900

    // flatChart's window, and a hole across the middle of it. The band lands
    // around x 600..830, well clear of the key in the bottom left corner.
    private val windowStart = 1000.0
    private val holeOpens = 2200.0
    private val holeCloses = 2800.0

    private fun gap(standing: GapStanding) =
        RecordGap(holeOpens, holeCloses, standing, expectedReadings = 600)

    private fun pixels(
        gaps: List<RecordGap>,
        labelColor: Color = Color.Black,
    ): IntArray {
        val bitmap = renderChartToBitmap(
            render = ChartRender(
                data = flatChart(n = 40, value = 6.0),
                visible = listOf("pm2_5"),
                gaps = gaps,
            ),
            widthPx = w,
            heightPx = h,
            background = Color.Black,
            axisColor = Color.Black,
            gridColor = Color.Black,
            labelColor = labelColor,
            colors = mapOf("pm2_5" to Color.Black),
        )
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /**
     * Which of the three inks a pixel is, or null for the black background.
     *
     * By the ratio of green to red rather than by absolute values, because every
     * one of these is laid down at a low alpha over black and so arrives dim: the
     * amber is 0xFFB300 (g/r 0.70), the red 0xB0453C (0.39) and the grey is grey
     * (1.00). That ordering is what a reader's eye is using too.
     */
    private fun ink(p: Int): String? {
        val r = android.graphics.Color.red(p)
        val g = android.graphics.Color.green(p)
        val b = android.graphics.Color.blue(p)
        if (maxOf(r, g, b) <= 5) return null
        if (r == 0) return "grey"
        return when {
            g > 0.85 * r -> "grey"
            g > 0.55 * r -> "amber"
            else -> "red"
        }
    }

    /**
     * The inks inside one band, sampled away from its own sides.
     *
     * The four pixels either side are skipped: the edges of a "gone" block and a
     * "not known" band are drawn in their own colour at full weight, and reading
     * them would let two fills look alike merely because both have sides.
     */
    private fun bandInks(px: IntArray): Set<String> {
        val data = flatChart(n = 40, value = 6.0)
        val plotLeft = 150f
        val plotWidth = w - 150f - 28f
        val span = data.end - data.start
        val from = (plotLeft + (holeOpens - windowStart) / span * plotWidth).toInt() + 5
        val to = (plotLeft + (holeCloses - windowStart) / span * plotWidth).toInt() - 5
        val out = HashSet<String>()
        for (y in 100..400) for (x in from..to) ink(px[y * w + x])?.let { out.add(it) }
        return out
    }

    @Test
    fun eachKindLaysDownADifferentPattern() {
        val drawn = mapOf(
            GapPattern.StillOnSensor to bandInks(pixels(listOf(gap(GapStanding.Fillable(holeOpens))))),
            GapPattern.PartlyOutOfReach to
                bandInks(pixels(listOf(gap(GapStanding.Fillable(holeOpens + 300))))),
            GapPattern.Gone to bandInks(pixels(listOf(gap(GapStanding.Lost)))),
            GapPattern.NotKnown to
                bandInks(pixels(listOf(gap(GapStanding.Unknown("nothing has looked"))))),
        )
        for ((pattern, inks) in drawn) {
            assertTrue("$pattern put no ink on the plot at all", inks.isNotEmpty())
        }
        assertEquals(
            "two of the four are drawn in the same ink: $drawn",
            drawn.size,
            drawn.values.toSet().size,
        )
        // And specifically, because "different" is not the same as "right": the
        // case the captain likes keeps its amber and nothing else, and the mesh
        // is the one that carries two.
        assertEquals(setOf("amber"), drawn[GapPattern.StillOnSensor])
        assertEquals(setOf("amber", "grey"), drawn[GapPattern.PartlyOutOfReach])
        assertEquals(setOf("red"), drawn[GapPattern.Gone])
        assertEquals(setOf("grey"), drawn[GapPattern.NotKnown])
    }

    // --- no key on the plot ----------------------------------------------------

    /**
     * Rows of the bottom-left corner of the plot carrying legend text.
     *
     * The key's words would be drawn in `labelColor`, so a white one would make
     * them the only bright neutral ink on the canvas. Every fill is laid down at
     * a low alpha and the brightest of them reaches 106, so a band crossing this
     * corner cannot be mistaken for a key.
     */
    private fun keyRows(px: IntArray): List<Int> =
        (400..720).filter { y ->
            (160..760).any { x ->
                val p = px[y * w + x]
                android.graphics.Color.red(p) > 120 &&
                    android.graphics.Color.green(p) > 120 &&
                    android.graphics.Color.blue(p) > 120
            }
        }

    @Test
    fun theKeyLivesOnTheHelpTabNowAndThePlotDrawsNone() {
        // The key used to be drawn in this corner whenever a marked gap was on
        // screen. It moved to the Help tab when the marking became a preference,
        // so a plot with every kind of hole on it must carry shading and nothing
        // else - a key here again would be the two-places-to-keep-in-step problem
        // the move was made to end.
        val px = pixels(
            listOf(
                RecordGap(1_500.0, 1_700.0, GapStanding.Lost, 0),
                RecordGap(1_900.0, 2_100.0, GapStanding.Unknown("nothing has looked"), 0),
                RecordGap(2_300.0, 2_500.0, GapStanding.Fillable(2_400.0), 0),
                RecordGap(2_700.0, 2_900.0, GapStanding.Fillable(2_700.0), 0),
            ),
            labelColor = Color.White,
        )
        assertTrue("legend text is still being drawn on the plot", keyRows(px).isEmpty())
    }
}

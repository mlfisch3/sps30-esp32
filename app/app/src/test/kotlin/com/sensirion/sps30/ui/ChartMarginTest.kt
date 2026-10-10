package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The plot's bottom margin has to hold everything drawn into it, and the corner
 * button has to fit in what is left.
 *
 * ## The two faults this guards
 *
 * **The saved PNG sliced its own x-axis title.** "Time of day (EDT)" was drawn
 * centred 160px below the axis in 54px type, which reaches about 192px down; the
 * margin that had to contain it was 176px, so the bottom edge of the image cut
 * through the caption. Nothing failed and the rest of the export was faithful -
 * the picture simply disagreed with itself, and a picture is the copy that
 * outlives the moment. (That caption has since been removed altogether; the
 * date line is now the deepest thing the margin holds, and the same arithmetic
 * guards it.)
 *
 * **The corner button cut through the bottom y-axis label.** The button was a
 * 44dp box with a 10dp margin sat in a gutter measured in raw canvas pixels, and
 * those are the same rectangle only at density 1. On the tablet, at density 3,
 * its top edge landed 4px inside "0.5" and sliced it lengthwise. It was correct
 * on a density-2 screen and wrong on a density-3 one with nothing in either place
 * saying which it had been written for - and portrait is the phone layout, which
 * is the one the captain's S24 will show.
 *
 * Both are arithmetic between numbers that each look reasonable alone, so this
 * does the arithmetic - with the real font metrics, not the estimate the layout
 * is built from - and one Compose pass to confirm the button is actually laid out
 * where the chart's touch handling believes it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ChartMarginTest {

    @get:Rule
    val compose = createComposeRule()

    private val g = ChartGeometry

    /** How far the real font puts ink below and above a line centred on `y`. */
    private fun inkBelowCentre(sizePx: Float): Float {
        val fm = android.graphics.Paint().apply { textSize = sizePx }.fontMetrics
        // drawAxisText centres the line box: baseline = y - (ascent + descent) / 2.
        return -(fm.ascent + fm.descent) / 2f + fm.descent
    }

    @Test
    fun everythingDrawnBelowTheAxisFitsInsideTheBottomMargin() {
        val stack = listOf(
            "the time ticks" to (g.xTickMid to g.tickTextSize),
            "the date line" to (g.xDateMid to g.captionTextSize),
        )
        for ((what, place) in stack) {
            val (mid, size) = place
            val deepest = mid + inkBelowCentre(size)
            assertTrue(
                "$what reaches ${"%.1f".format(deepest)}px below the axis, and the " +
                    "bottom margin is only ${"%.1f".format(g.bottomPad)}px - it is sliced " +
                    "by the bottom edge of the plot, and of the saved PNG",
                deepest <= g.bottomPad,
            )
        }
    }

    @Test
    fun theCornerButtonClearsTheLowestValueLabel() {
        // A value label centres on its own gridline, and the lowest gridline can
        // be the axis itself - which is where "0.5" was when the button cut it.
        val labelBottom = g.bottomPad - inkBelowCentre(g.tickTextSize)
        val buttonTop = g.cornerSide + g.cornerMargin
        assertTrue(
            "the corner button rises ${"%.1f".format(buttonTop)}px above the bottom edge " +
                "and the lowest y-axis label reaches down to ${"%.1f".format(labelBottom)}px " +
                "above it, so the button is drawn over the label",
            buttonTop <= labelBottom,
        )
    }

    /** And it must not grow rightwards into the y axis either. */
    @Test
    fun theCornerButtonStaysOutOfTheYAxisGutter() {
        assertTrue(
            "the corner button reaches ${g.cornerMargin + g.cornerSide}px across, past " +
                "the axis at ${g.leftPad}px",
            g.cornerMargin + g.cornerSide <= g.leftPad,
        )
    }

    /**
     * The exported image itself, which is where the sliced x-axis caption was
     * seen back when the plot carried one.
     *
     * The arithmetic above says it fits; this says the picture agrees. Scanned
     * rather than eyeballed: the ink of the deepest thing drawn - the time
     * ticks, now that the caption is gone - must stop clear of the bottom edge,
     * and there must *be* ink down there, so a render that quietly drew nothing
     * could not pass.
     */
    @Test
    fun theSavedImageDoesNotTouchItsBottomEdge() {
        val w = 1600
        val h = 900
        val bitmap = renderChartToBitmap(
            render = chartRenderFor(ViewMode.ALL, inspectedEpoch = 1500.0),
            widthPx = w,
            heightPx = h,
            background = Color.Black,
            axisColor = Color.White,
            gridColor = Color.White,
            labelColor = Color.White,
        )
        val row = IntArray(w)
        var lowestInk = -1
        for (y in h - 1 downTo 0) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            if (row.any { it != android.graphics.Color.BLACK }) {
                lowestInk = y
                break
            }
        }
        assertTrue("the export drew nothing at all, so this proves nothing", lowestInk >= 0)
        val axis = h - g.bottomPad
        assertTrue(
            "nothing was drawn below the axis at all (lowest ink at y=$lowestInk, " +
                "axis at ${"%.0f".format(axis)}), so this proves nothing",
            lowestInk > axis + g.xTickMid,
        )
        assertTrue(
            "the export's deepest ink is at y=$lowestInk of $h - it runs into the " +
                "bottom edge, which is what once sliced \"Time of day (EDT)\"",
            lowestInk < h - 1,
        )
    }

    /**
     * The button's own layout must land on the rectangle above, at a density
     * where dp and px part company.
     *
     * This is the half the arithmetic cannot see: the numbers can agree
     * perfectly while the composable is placed by some other rule, which is
     * exactly what was wrong. Density 3 is the tablet's, and the density the
     * fault appeared at.
     */
    @Test
    fun theButtonIsLaidOutOnThatRectangle() {
        val heightDp = 300.dp
        // The tablet's density, stated rather than inherited: at density 1 a dp
        // box and a px box are the same rectangle and the fault is invisible.
        val density = 3f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Sps30Theme(useDarkTheme = true) {
                    Box(modifier = Modifier.size(width = 500.dp, height = heightDp)) {
                        PmChart(
                            render = chartRenderFor(ViewMode.ALL, inspectedEpoch = 1500.0),
                            modifier = Modifier.fillMaxSize().testTag("plot"),
                            colors = mapOf("pm2_5" to Color.Green, "pm1_0" to Color.Yellow),
                        )
                    }
                }
            }
        }
        // Read in raw pixels, which is the only unit the two halves share: the
        // chart's touch handling knows nothing about dp.
        val bounds = compose
            .onNodeWithContentDescription("Crosshair", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        // Against the plot's own rectangle, which is the one the canvas measures
        // its touch handling in - not against the window, which may be smaller.
        val plot = compose.onNodeWithTag("plot").fetchSemanticsNode().boundsInRoot
        val sidePx = bounds.width
        val bottomGapPx = plot.bottom - bounds.bottom
        val leftGapPx = bounds.left - plot.left
        assertTrue(
            "the corner button measured ${"%.1f".format(sidePx)}px across, not " +
                "${"%.1f".format(g.cornerSide)}px - it is no longer the rectangle the " +
                "chart's touch handling excludes, nor the one that fits the corner",
            kotlin.math.abs(sidePx - g.cornerSide) < 2f,
        )
        assertTrue(
            "the corner button sits ${"%.1f".format(bottomGapPx)}px above the plot's " +
                "bottom edge and ${"%.1f".format(leftGapPx)}px in from its left, not " +
                "${"%.1f".format(g.cornerMargin)}px",
            kotlin.math.abs(bottomGapPx - g.cornerMargin) < 2f &&
                kotlin.math.abs(leftGapPx - g.cornerMargin) < 2f,
        )
    }
}

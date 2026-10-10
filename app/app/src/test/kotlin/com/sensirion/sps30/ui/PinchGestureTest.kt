package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.move
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.data.YAxisControl
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Two fingers on the plot, scaling the y axis.
 *
 * [PinchYAxisTest] settles the rule; this settles the wiring, which is the half
 * that was actually missing. The model has been able to hold a user's range
 * since the override was written, and a pinch on the plot still went to the time
 * axis and nowhere else - so the whole feature came down to which callback the
 * gesture loop in [PmChart] reaches for. Nothing about that is visible from the
 * arbiter's own tests: they would pass just as happily with the routing removed.
 *
 * So this drives the real composable with real pointers and watches which of
 * `onZoomY` and `onZoom` fires, then puts what came out through a real
 * [ChartView] to check the axis ends up where the fingers put it.
 *
 * A big plot on purpose: the chart's margins are in raw canvas pixels
 * ([ChartGeometry]) and the touch has to land in the plotting rectangle inside
 * them, which a small test surface at a low density does not have much of.
 */
@RunWith(RobolectricTestRunner::class)
// A tablet-sized screen at density 1, so the plot below is the pixel rectangle it
// asks for. On the default (phone) screen the box is measured down to whatever
// fits, and finger positions worked out from the plot's own pixel geometry land
// in the axis gutter or off the node entirely - the gesture under test then never
// runs and the test passes or fails on the wrong thing.
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class PinchGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val yZooms = mutableListOf<Double>()
    private val timeZooms = mutableListOf<Double>()
    private val pans = mutableListOf<Double>()

    private fun plot() {
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(modifier = Modifier.size(width = PLOT_W, height = PLOT_H)) {
                    PmChart(
                        render = chartRenderFor(ViewMode.LIVE),
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                        onZoom = { timeZooms += it },
                        onZoomY = { factor, _ -> yZooms += factor },
                        onPan = { pans += it },
                    )
                }
            }
        }
    }

    /**
     * A pinch between two pairs of finger positions, in eight steps.
     *
     * Interpolated rather than jumped, because that is what a hand does and
     * because the chart reads each frame's change rather than the whole gesture:
     * one enormous step would exercise a path no finger produces.
     */
    private fun pinch(from: Pair<Offset, Offset>, to: Pair<Offset, Offset>) {
        compose.onNodeWithTag("plot").performTouchInput {
            down(0, from.first)
            down(1, from.second)
            val steps = 8
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                updatePointerTo(0, lerp(from.first, to.first, t))
                updatePointerTo(1, lerp(from.second, to.second, t))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
    }

    private fun lerp(a: Offset, b: Offset, t: Float) =
        Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /**
     * The middle of the plotting rectangle, in the canvas pixels the chart draws
     * in - inside the axis gutter on the left and the time axis below, both of
     * which have gestures of their own and would answer instead.
     */
    private fun plotCentre(): Offset {
        val node = compose.onNodeWithTag("plot").fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        check(w >= 600f && h >= 500f) { "the plot measured ${w}x$h, too small to pinch in" }
        return Offset(
            (ChartGeometry.leftPad + w) / 2f,
            (0f + (h - ChartGeometry.bottomPad)) / 2f,
        )
    }

    /** Fingers one above the other, [gap] pixels apart, in the middle of the plot. */
    private fun stacked(gap: Float): Pair<Offset, Offset> {
        val c = plotCentre()
        return Offset(c.x, c.y - gap / 2f) to Offset(c.x, c.y + gap / 2f)
    }

    /** Fingers side by side, [gap] pixels apart. */
    private fun level(gap: Float): Pair<Offset, Offset> {
        val c = plotCentre()
        return Offset(c.x - gap / 2f, c.y) to Offset(c.x + gap / 2f, c.y)
    }

    /** A vertical pinch made by a real hand: stacked, but not perfectly. */
    private fun slanted(gap: Float, skewX: Float): Pair<Offset, Offset> {
        val (a, b) = stacked(gap)
        return Offset(a.x - skewX / 2f, a.y) to Offset(b.x + skewX / 2f, b.y)
    }

    /** Everything the gesture asked of the y axis, applied in order. */
    private fun applyTo(view: ChartView, dataMin: Double, dataMax: Double) {
        for (factor in yZooms) view.zoomY(factor, dataMin, dataMax)
    }

    /**
     * The specification's item 2, at the surface the user touches: squeezing two
     * fingers together vertically on the graph zooms the y axis out.
     */
    @Test
    fun squeezingVerticallyOnThePlotScalesTheYAxis() {
        plot()
        pinch(stacked(360f), stacked(120f))

        assertTrue("a vertical pinch did nothing to the y axis", yZooms.isNotEmpty())
        assertTrue(
            "squeezing must widen the range, so every factor is above 1: $yZooms",
            yZooms.all { it > 1.0 },
        )
    }

    /** And spreading them zooms it in, on the same gesture path. */
    @Test
    fun spreadingVerticallyOnThePlotZoomsTheYAxisIn() {
        plot()
        pinch(stacked(120f), stacked(360f))

        assertTrue("a vertical pinch did nothing to the y axis", yZooms.isNotEmpty())
        assertTrue(
            "spreading must narrow the range, so every factor is below 1: $yZooms",
            yZooms.all { it < 1.0 },
        )
    }

    /**
     * Items 3 and 4 end to end: the gesture puts the chart into the override
     * state without switching autoscale off, and the range it left stays put
     * while readings keep arriving inside it.
     */
    @Test
    fun aVerticalPinchEntersTheOverrideAndTheRangeStays() {
        plot()
        pinch(stacked(400f), stacked(80f))

        val view = ChartView()
        applyTo(view, 0.4, 1.5)

        assertEquals(YAxisControl.OVERRIDDEN, view.yControl())
        assertTrue("autoscale must still be the setting", view.yAuto)
        val chosen = view.resolveYRange(0.4, 1.5)
        repeat(30) { view.resolveYRange(0.5, 1.4) }
        assertEquals(chosen, view.resolveYRange(0.4, 1.5))
    }

    /**
     * Item 5, from the finger: a reading above the range the pinch left grows the
     * axis to fit it, and only upwards.
     */
    @Test
    fun aReadingAboveThePinchedRangeGrowsTheAxis() {
        plot()
        pinch(stacked(400f), stacked(80f))

        val view = ChartView()
        applyTo(view, 0.4, 1.5)
        val before = view.resolveYRange(0.4, 1.5)

        val after = view.resolveYRange(0.4, before.second + 40.0)

        assertTrue("the reading is off the top of the chart", after.second >= before.second + 40.0)
        assertEquals("the bottom must not move", before.first, after.first, 1e-9)
    }

    /** Item 6: Release hands the axis back, and the fit returns. */
    @Test
    fun releaseAfterAPinchRestoresAutoscale() {
        plot()
        pinch(stacked(400f), stacked(80f))

        val view = ChartView()
        applyTo(view, 0.4, 1.5)
        view.releaseYOverride()

        assertEquals(YAxisControl.FITTED, view.yControl())
        assertEquals(ChartView().resolveYRange(0.4, 1.5), view.resolveYRange(0.4, 1.5))
    }

    /**
     * A vertical pinch belongs to the y axis alone. Fingers moving up and down
     * drift sideways by a few pixels whatever the hand intends, and scrolling the
     * record while setting its height is a second thing nobody asked for.
     */
    @Test
    fun aVerticalPinchLeavesTimeAlone() {
        plot()
        // Squeezed vertically while the hand also drifts sideways and splays a
        // little - the incidental horizontal movement a real pinch always has.
        pinch(slanted(gap = 400f, skewX = 12f), slanted(gap = 120f, skewX = 40f))

        assertTrue("a vertical pinch did nothing to the y axis", yZooms.isNotEmpty())
        assertEquals("a vertical pinch zoomed time as well", emptyList<Double>(), timeZooms)
        assertEquals("a vertical pinch panned time as well", emptyList<Double>(), pans)
    }

    /**
     * The other half of being right: the gesture that has always zoomed time
     * still does, and does not touch the y axis on its way.
     */
    @Test
    fun aLevelPinchStillZoomsTimeAndNotTheYAxis() {
        plot()
        pinch(level(360f), level(120f))

        assertTrue("a level pinch stopped zooming time", timeZooms.isNotEmpty())
        assertTrue("squeezing must widen the window: $timeZooms", timeZooms.all { it > 1.0 })
        assertEquals("a level pinch moved the y axis", emptyList<Double>(), yZooms)
    }
}

/** The test plot, big enough that its pixel margins leave a rectangle to pinch in. */
private val PLOT_W = 1200.dp
private val PLOT_H = 760.dp

package com.sensirion.sps30.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.move
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.data.XAxisControl
import com.sensirion.sps30.data.YAxisControl
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The captain's rule, measured: **the graph never changes size** while the
 * screen keeps its orientation. Zoom and pan change what is drawn inside the
 * plot, never the size of the drawing surface itself.
 *
 * ## The fault this pins down
 *
 * Zooming the live view engaged the x-axis hold - a real hand's pinch always
 * drifts sideways a little, and that drift is a pan, which freezes the range
 * (see [aLevelPinchOnTheLivePlotEngagesTheXHold] for the proof from the
 * fingers). Engaging the hold made [XHoldControls] appear above the chart, and
 * in the stacked portrait layout the chart takes whatever height the controls
 * leave - so the plot visibly crunched inward the moment the pinch landed, and
 * stayed crunched, because zooming back never releases the hold. The y-axis
 * override did the same through [YOverrideControls].
 *
 * The layout tests here drive that exact state change through the real
 * dashboard layouts and assert the chart's measured surface does not move by a
 * pixel. They fail against the old shape of [StackedDashboard], where the hold
 * rows and the chart shared one column height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ChartSizeStableTest {

    @get:Rule
    val compose = createComposeRule()

    /** Every size the chart surface reported, in layout order. */
    private val sizes = mutableListOf<IntSize>()

    /** Flipped mid-composition: false = untouched live view, true = after a zoom. */
    private var zoomed by mutableStateOf(false)

    /**
     * The real dashboard, branching the way [DashboardScreen] branches, with the
     * chart state switchable between the two sides of a zoom gesture.
     *
     * Before: the live view nothing has touched - x following, y fitted. After:
     * what a pinch leaves behind - the x range held, the y range overridden.
     * Everything else is identical, so any size change the chart reports is the
     * layout reacting to the zoom, which is exactly what must never happen.
     */
    private fun dashboard() {
        sizes.clear()
        zoomed = false
        compose.setContent {
            val app: Application = RuntimeEnvironment.getApplication()
            val viewModel = remember { DashboardViewModel(app) }
            val base = chartRenderFor(if (zoomed) ViewMode.LIVE_FROZEN else ViewMode.LIVE)
            // The "after" side piles on everything that appears at runtime: both
            // axis holds, a stepped tick density, and the derived-times flag.
            // Every one of them lives on the plot now, so none of them may move
            // the plot's own surface by a pixel.
            val chart = if (zoomed) {
                base.data!!.copy(
                    xControl = XAxisControl.HELD,
                    xFrozen = true,
                    yControl = YAxisControl.OVERRIDDEN,
                    tickDensity = 1,
                )
            } else {
                base.data!!
            }
            val render = if (zoomed) {
                base.copy(data = chart, timeFlag = "derived times")
            } else {
                base
            }
            val state = DashboardUiState(chart = chart)
            val touch = ChartTouchCallbacks(
                inspected = null,
                onInspect = {},
                cursor = CursorControls(
                    enabled = true, onToggleEnabled = {},
                    follows = false, onToggleFollows = {},
                ),
                onGestureStart = {},
                onGestureEnd = {},
                graphLocked = false,
                onToggleGraphLock = {},
            )
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    if (sideBySide(LocalConfiguration.current)) {
                        WideDashboard(
                            Modifier, state, viewModel, render, touch,
                            onChartSize = { sizes += it },
                            onTimeFlag = {}, onOpenOptions = {},
                        )
                    } else {
                        StackedDashboard(
                            Modifier, state, viewModel, render, touch,
                            onChartSize = { sizes += it },
                            onTimeFlag = {}, onOpenOptions = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertChartHoldsItsSizeAcrossAZoom() {
        dashboard()
        assertTrue("the chart never reported a size", sizes.isNotEmpty())
        val before = sizes.last()
        assertNotEquals("the chart measured to nothing", IntSize.Zero, before)

        zoomed = true
        compose.waitForIdle()
        val after = sizes.last()

        assertEquals(
            "the plot surface was ${before.width}x${before.height} before the zoom and " +
                "${after.width}x${after.height} after it. The graph must never change size " +
                "in a given orientation: a zoom changes what is drawn, not the surface " +
                "it is drawn on.",
            before,
            after,
        )

        // Holding the chart's size must not have cost the way out of the holds:
        // both release icons are on the plot, at the axes they release.
        compose.onNodeWithContentDescription(AxisHoldLabels.RELEASE_X, substring = true)
            .assertExists()
        compose.onNodeWithContentDescription(AxisHoldLabels.RELEASE_Y, substring = true)
            .assertExists()
        // And the flag that arrived with the zoomed state is on the plot too.
        compose.onNodeWithContentDescription("Times on this plot", substring = true)
            .assertExists()
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theChartKeepsItsSizeAcrossAZoomOnAPhoneInPortrait() =
        assertChartHoldsItsSizeAcrossAZoom()

    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun theChartKeepsItsSizeAcrossAZoomOnATabletInPortrait() =
        assertChartHoldsItsSizeAcrossAZoom()

    @Test @Config(qualifiers = PHONE_LANDSCAPE)
    fun theChartKeepsItsSizeAcrossAZoomOnAPhoneInLandscape() =
        assertChartHoldsItsSizeAcrossAZoom()

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun theChartKeepsItsSizeAcrossAZoomOnATabletInLandscape() =
        assertChartHoldsItsSizeAcrossAZoom()

    // --- the finger end of the chain -----------------------------------------

    /**
     * Why "zooming" resizes anything at all: a level pinch made by a real hand
     * drifts sideways while it scales, the drift is a pan, and a pan on the live
     * view freezes the x range - which is the state the hold controls appear in.
     *
     * Driven with real pointers on the real composable, exactly as
     * [PinchGestureTest] drives its gestures, then applied to a real [ChartView]
     * the way [MonitorSession] applies them.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
    fun aLevelPinchOnTheLivePlotEngagesTheXHold() {
        val zooms = mutableListOf<Double>()
        val pans = mutableListOf<Double>()
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    PmChart(
                        render = chartRenderFor(ViewMode.LIVE),
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                        onZoom = { zooms += it },
                        onPan = { pans += it },
                    )
                }
            }
        }
        compose.waitForIdle()

        // A pinch as a hand makes one: squeezing along the time axis while the
        // pair drifts a little to the left.
        val node = compose.onNodeWithTag("plot").fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        val centre = Offset(
            (ChartGeometry.leftPad + w) / 2f,
            (0f + (h - ChartGeometry.bottomPad)) / 2f,
        )
        val from = Offset(centre.x - 180f, centre.y) to Offset(centre.x + 180f, centre.y)
        val to = Offset(centre.x - 75f, centre.y) to Offset(centre.x + 45f, centre.y)
        compose.onNodeWithTag("plot").performTouchInput {
            down(0, from.first)
            down(1, from.second)
            val steps = 8
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                updatePointerTo(
                    0,
                    Offset(
                        from.first.x + (to.first.x - from.first.x) * t,
                        from.first.y + (to.first.y - from.first.y) * t,
                    ),
                )
                updatePointerTo(
                    1,
                    Offset(
                        from.second.x + (to.second.x - from.second.x) * t,
                        from.second.y + (to.second.y - from.second.y) * t,
                    ),
                )
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertTrue("the pinch never zoomed", zooms.isNotEmpty())
        assertTrue("a drifting pinch reports its drift as a pan", pans.isNotEmpty())

        val view = ChartView()
        val now = 2000.0
        for (factor in zooms) view.zoom(factor, now)
        for (delta in pans) view.pan(delta, now)

        assertEquals(
            "a level pinch with a hand's sideways drift leaves the x axis held - " +
                "which is the state whose controls must not steal the chart's space",
            XAxisControl.HELD,
            view.xControl(),
        )
    }
}

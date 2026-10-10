package com.sensirion.sps30.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.RegionStats
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Nothing about the selection may sit over the plot, and nothing about it may
 * resize the plot.
 *
 * The statistics themselves are plot text and their placement is pinned by
 * [RegionReadoutPlacementTest]; what remains composed - the actions row with
 * Zoom to selection, Back, Clear and the "?" whose prose expands beneath it -
 * docks with the controls ([RegionActions]). These tests drive the real
 * dashboard layouts through a selection appearing, the explanation expanding,
 * and the selection disappearing, at all four screen shapes, and assert the
 * actions stay reachable, stay off the plot's surface, and never move it by a
 * pixel - the graph-never-resizes rule, which is why the portrait row lives
 * inside the [SteadyControls] viewport rather than as a row beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class RegionActionsPlacementTest {

    @get:Rule
    val compose = createComposeRule()

    /** Every size the chart surface reported, in layout order. */
    private val sizes = mutableListOf<IntSize>()

    /** Flipped mid-composition: whether a committed selection is on screen. */
    private var selected by mutableStateOf(false)

    /**
     * A report like the one a real drag commits: a threshold so the "time
     * above" line exists, and a saved return view so Back is on the row too -
     * the row's fullest shape, which is the one that must fit off the plot.
     */
    private fun regionReport(): RegionReport {
        val epochs = DoubleArray(21) { 1000.0 + it * 50.0 }
        val values = DoubleArray(epochs.size) { 0.5 + (it % 5) * 0.2 }
        return RegionReport(
            start = 1200.0,
            end = 1800.0,
            field = "pm2_5",
            threshold = 1.0,
            stats = RegionStats.compute(epochs, values, threshold = 1.0),
            canReturn = true,
        )
    }

    /** The real dashboard, branching the way [DashboardScreen] branches. */
    private fun dashboard() {
        sizes.clear()
        selected = false
        compose.setContent {
            val app: Application = RuntimeEnvironment.getApplication()
            val viewModel = remember { DashboardViewModel(app) }
            val render = chartRenderFor(ViewMode.LIVE)
            val state = DashboardUiState(
                chart = render.data,
                region = if (selected) regionReport() else null,
            )
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

    private fun assertActionsOffThePlot(moment: String) {
        val actions = compose.onNodeWithContentDescription("Selection actions")
            .fetchSemanticsNode().boundsInRoot
        val plot = compose.onNodeWithTag("plot").fetchSemanticsNode().boundsInRoot
        assertFalse(
            "the selection actions $actions overlap the plot $plot $moment. Nothing " +
                "about the selection may sit over the plot - covering the data was " +
                "the whole fault the rejected card had.",
            actions.overlaps(plot),
        )
    }

    private fun assertActionsStayOffThePlotAndThePlotStaysPut() {
        dashboard()
        assertTrue("the chart never reported a size", sizes.isNotEmpty())
        val before = sizes.last()

        selected = true
        compose.waitForIdle()

        // The actions are really on screen - visible, not merely composed.
        compose.onNodeWithText("Zoom to selection").assertIsDisplayed()
        compose.onNodeWithText("Clear").assertIsDisplayed()
        assertActionsOffThePlot("with a selection showing")
        assertEquals(
            "the plot surface was ${before.width}x${before.height} without a selection " +
                "and ${sizes.last().width}x${sizes.last().height} with one. The graph " +
                "must never change size in a given orientation.",
            before,
            sizes.last(),
        )

        // The "?" expands the explanation - and the prose, too, stays off the
        // plot and costs it nothing.
        compose.onNodeWithContentDescription("Explain the selection", substring = true)
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText("raw samples", substring = true).assertIsDisplayed()
        assertActionsOffThePlot("with the explanation expanded")
        assertEquals(before, sizes.last())

        selected = false
        compose.waitForIdle()
        assertEquals(before, sizes.last())
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theActionsStayOffThePlotOnAPhoneInPortrait() =
        assertActionsStayOffThePlotAndThePlotStaysPut()

    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun theActionsStayOffThePlotOnATabletInPortrait() =
        assertActionsStayOffThePlotAndThePlotStaysPut()

    @Test @Config(qualifiers = PHONE_LANDSCAPE)
    fun theActionsStayOffThePlotOnAPhoneInLandscape() =
        assertActionsStayOffThePlotAndThePlotStaysPut()

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun theActionsStayOffThePlotOnATabletInLandscape() =
        assertActionsStayOffThePlotAndThePlotStaysPut()
}

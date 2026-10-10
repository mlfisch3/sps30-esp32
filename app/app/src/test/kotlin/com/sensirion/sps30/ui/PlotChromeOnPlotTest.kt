package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.data.XAxisControl
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
 * The plot's own controls, on the plot, each at the thing it affects.
 *
 * This file replaced `XHoldControlsTest` and `YOverrideHintTest` when the
 * axis-hold rows moved onto the graph: the rows cost 104 dp that no phone
 * landscape had, reserving the space permanently would have taken it straight
 * back from the plot, and scrolling is ruled out - so each hold now sits at
 * the axis it holds, which is the captain's own principle. What is asserted
 * is the same contract the rows had - present only while there is a hold to
 * act on, Lock toggles, Release works locked or not - plus the part that is
 * new: *where* they are, measured against the plot's own geometry, because
 * "at the axis it owns" is now a testable claim rather than a heading.
 *
 * docs/PLOT_FURNITURE.md is the map of everything this file touches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class PlotChromeOnPlotTest {

    @get:Rule
    val compose = createComposeRule()

    private var xLocks = mutableListOf<Boolean>()
    private var xReleases = 0
    private var yLocks = mutableListOf<Boolean>()
    private var yReleases = 0
    private var tickCycles = 0
    private var cursorToggles = 0
    private var followToggles = 0
    private var lockToggles = 0
    private var flagTaps = 0

    private fun chrome(
        cursorEnabled: Boolean = true,
        follows: Boolean = false,
    ) = PlotChromeCallbacks(
        onToggleGraphLock = { lockToggles++ },
        cursorEnabled = cursorEnabled,
        onToggleCursor = { cursorToggles++ },
        follows = follows,
        onToggleFollows = { followToggles++ },
        onCycleTicks = { tickCycles++ },
        onSetXLocked = { xLocks += it },
        onReleaseX = { xReleases++ },
        onSetYLocked = { yLocks += it },
        onReleaseY = { yReleases++ },
        onTimeFlag = { flagTaps++ },
    )

    private fun plot(
        render: ChartRender,
        graphLocked: Boolean = false,
    ) {
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    PmChart(
                        render = render,
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                        graphLocked = graphLocked,
                        chrome = chrome(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun renderWith(
        xControl: XAxisControl = XAxisControl.FOLLOWING,
        yControl: YAxisControl = YAxisControl.FITTED,
        timeFlag: String? = null,
        inspectedEpoch: Double? = null,
    ): ChartRender {
        val base = chartRenderFor(ViewMode.LIVE, inspectedEpoch = inspectedEpoch)
        return base.copy(
            data = base.data!!.copy(xControl = xControl, yControl = yControl),
            timeFlag = timeFlag,
        )
    }

    // --- presence follows the hold, exactly as the rows' did ------------------

    @Test
    fun `following axes show no hold icons`() {
        plot(renderWith())
        compose.onAllNodes(hasContentDescription(AxisHoldLabels.RELEASE_X, substring = true))
            .assertCountEquals(0)
        compose.onAllNodes(hasContentDescription(AxisHoldLabels.RELEASE_Y, substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun `a held x range offers lock and a named release at the time axis`() {
        plot(renderWith(xControl = XAxisControl.HELD))
        val lock = compose.onNodeWithContentDescription(
            "${AxisHoldLabels.X_HEADING} ${AxisHoldLabels.LOCK}", substring = true,
        )
        val release = compose.onNodeWithContentDescription(
            AxisHoldLabels.RELEASE_X, substring = true,
        )
        lock.assertExists()
        release.assertExists()
        // The note the row used to print survives in the spoken words - on
        // both icons of the pair, so either alone tells the whole story.
        compose.onAllNodes(hasContentDescription(AxisHoldLabels.X_HELD, substring = true))
            .assertCountEquals(2)

        // At its own axis: in the bottom margin, at the right end, beside the
        // tick control - not somewhere on a panel.
        val window = compose.onNodeWithTag("plot").getUnclippedBoundsInRoot()
        for (node in listOf(lock, release)) {
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "an x-hold icon at ${bounds.pretty()} is not in the bottom margin " +
                    "of ${window.pretty()}",
                bounds.bottom > window.bottom - (window.bottom - window.top) / 5,
            )
            assertTrue(
                "an x-hold icon at ${bounds.pretty()} is not at the axis' right end",
                bounds.left > window.left + (window.right - window.left) / 2,
            )
        }
    }

    @Test
    fun `an overridden y range puts its pair at the value axis`() {
        plot(renderWith(yControl = YAxisControl.OVERRIDDEN))
        val lock = compose.onNodeWithContentDescription(
            "${AxisHoldLabels.Y_HEADING} ${AxisHoldLabels.LOCK}", substring = true,
        )
        val release = compose.onNodeWithContentDescription(
            AxisHoldLabels.RELEASE_Y, substring = true,
        )
        lock.assertExists()
        release.assertExists()
        compose.onAllNodes(hasContentDescription(AxisHoldLabels.Y_HELD, substring = true))
            .assertCountEquals(2)

        val window = compose.onNodeWithTag("plot").getUnclippedBoundsInRoot()
        for (node in listOf(lock, release)) {
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "a y-hold icon at ${bounds.pretty()} is not at the y axis (left edge) " +
                    "of ${window.pretty()}",
                bounds.right < window.left + (window.right - window.left) / 5,
            )
            assertTrue(
                "a y-hold icon at ${bounds.pretty()} is not in the axis' upper half",
                bounds.bottom < window.top + (window.bottom - window.top) / 2,
            )
        }
    }

    // --- the actions are the rows' actions, unchanged --------------------------

    @Test
    fun `the x lock icon asks for the lock`() {
        plot(renderWith(xControl = XAxisControl.HELD))
        compose.onNodeWithContentDescription(
            "${AxisHoldLabels.X_HEADING} ${AxisHoldLabels.LOCK}", substring = true,
        ).performClick()
        assertEquals(listOf(true), xLocks)
    }

    /**
     * Locked, the icon says so - and release still answers in one press: an
     * unlock-then-release pair would be a second tap that answers no question
     * the user asked.
     */
    @Test
    fun `release works even while locked`() {
        plot(renderWith(xControl = XAxisControl.LOCKED))
        compose.onNodeWithContentDescription(AxisHoldLabels.LOCKED, substring = true)
            .assertExists()
        compose.onNodeWithContentDescription(AxisHoldLabels.RELEASE_X, substring = true)
            .performClick()
        assertEquals(1, xReleases)
    }

    @Test
    fun `the y pair drives its own callbacks`() {
        plot(renderWith(yControl = YAxisControl.OVERRIDDEN))
        compose.onNodeWithContentDescription(
            "${AxisHoldLabels.Y_HEADING} ${AxisHoldLabels.LOCK}", substring = true,
        ).performClick()
        compose.onNodeWithContentDescription(AxisHoldLabels.RELEASE_Y, substring = true)
            .performClick()
        assertEquals(listOf(true), yLocks)
        assertEquals(1, yReleases)
    }

    // --- the tick-density control ----------------------------------------------

    @Test
    fun `the tick control states the step and cycles on a press`() {
        plot(renderWith())
        // chartRenderFor pins tickStepSeconds to 900 s, so the face reads "15m" -
        // the control is the axis' own legend.
        val tick = compose.onNodeWithContentDescription("Gridlines every 15m", substring = true)
        tick.assertExists()
        tick.performClick()
        tick.performClick()
        assertEquals("two presses are two steps of the cycle", 2, tickCycles)

        val window = compose.onNodeWithTag("plot").getUnclippedBoundsInRoot()
        val bounds = compose.onNodeWithContentDescription("Gridlines every", substring = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the tick control at ${bounds.pretty()} is not at the time axis' right " +
                "corner of ${window.pretty()}",
            bounds.bottom > window.bottom - (window.bottom - window.top) / 5 &&
                bounds.right > window.right - (window.right - window.left) / 5,
        )
    }

    // --- Follow, the graph lock and the crosshair toggle ------------------------

    @Test
    fun `follow and the graph lock hold the top right corner and answer presses`() {
        plot(renderWith())
        val window = compose.onNodeWithTag("plot").getUnclippedBoundsInRoot()
        val follow = compose.onNodeWithContentDescription("Follow", substring = true)
        val lock = compose.onNodeWithContentDescription(
            AxisHoldLabels.LOCK_GRAPH, substring = true,
        )
        for ((name, node) in listOf("Follow" to follow, "the graph lock" to lock)) {
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "$name at ${bounds.pretty()} is not in the top-right corner of " +
                    window.pretty(),
                bounds.top < window.top + (window.bottom - window.top) / 4 &&
                    bounds.right > window.left + (window.right - window.left) * 0.7f,
            )
        }
        follow.performClick()
        lock.performClick()
        assertEquals(1, followToggles)
        assertEquals(1, lockToggles)
    }

    @Test
    fun `the crosshair toggle sits with its family beside the pin corner`() {
        plot(renderWith())
        val node = compose.onNodeWithContentDescription("Crosshair on", substring = true)
        val window = compose.onNodeWithTag("plot").getUnclippedBoundsInRoot()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue(
            "the crosshair toggle at ${bounds.pretty()} is not at the bottom-left " +
                "of ${window.pretty()}",
            bounds.bottom > window.bottom - (window.bottom - window.top) / 5 &&
                bounds.left < window.left + (window.right - window.left) / 3,
        )
        node.performClick()
        assertEquals(1, cursorToggles)
    }

    /** The controls stay live while the locked canvas swallows the plot's own
     *  gestures - the lock icon must be able to release the lock it set. */
    @Test
    fun `the chrome answers presses while the graph is locked`() {
        plot(renderWith(xControl = XAxisControl.HELD), graphLocked = true)
        compose.onNodeWithContentDescription(AxisHoldLabels.RELEASE_GRAPH, substring = true)
            .performClick()
        compose.onNodeWithContentDescription(AxisHoldLabels.RELEASE_X, substring = true)
            .performClick()
        assertEquals(1, lockToggles)
        assertEquals(1, xReleases)
    }

    // --- the derived-times flag ---------------------------------------------------

    @Test
    fun `the flag is on the plot when times are derived and taps through`() {
        plot(renderWith(timeFlag = "derived times"))
        val flag = compose.onNodeWithContentDescription(
            "Times on this plot are derived", substring = true,
        )
        flag.assertExists()
        // In the top strip, at the left, beside the unit mark's ground.
        val window = compose.onNodeWithTag("plot").getUnclippedBoundsInRoot()
        val bounds = flag.getUnclippedBoundsInRoot()
        assertTrue(
            "the flag at ${bounds.pretty()} is not in the plot's top strip of " +
                window.pretty(),
            bounds.top < window.top + (window.bottom - window.top) / 4,
        )
        flag.performClick()
        assertEquals(1, flagTaps)
    }

    @Test
    fun `a run count changes the flag's words and its tap opens the placing dialog path`() {
        plot(renderWith(timeFlag = "3 unplaced"))
        compose.onNodeWithContentDescription("could not be placed", substring = true)
            .performClick()
        assertEquals(1, flagTaps)
    }

    /** The crosshair readout owns the strip while inspecting, so the flag yields
     *  exactly as the view note does. */
    @Test
    fun `the flag is suppressed while inspecting`() {
        plot(renderWith(timeFlag = "derived times", inspectedEpoch = 1500.0))
        compose.onAllNodes(hasContentDescription("Times on this plot", substring = true))
            .assertCountEquals(0)
    }

    /** No chrome, no controls: the bare plot previews and gesture tests rely on it. */
    @Test
    fun `a chartless or chromeless plot draws no controls`() {
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    PmChart(
                        render = chartRenderFor(ViewMode.LIVE),
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("Gridlines every", substring = true))
            .assertCountEquals(0)
        compose.onAllNodes(hasContentDescription(AxisHoldLabels.LOCK_GRAPH, substring = true))
            .assertCountEquals(0)
    }
}

package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The graph lock: while it is on, touching the plot does nothing at all.
 *
 * The complaint it answers is small and repeated - a crosshair placed on the
 * reading that mattered, then lost to a finger brushing the glass, and the whole
 * framing to do again. So "nothing" has to mean nothing: not the cursor moving,
 * not the view panning, and not the dashboard reading the stray touch as a tap
 * away from the crosshair and clearing it, which is the same loss by another
 * route and the one a naive fix would leave behind.
 *
 * Pressed through the real composables rather than by calling the gate directly:
 * every fault this area has had - see [CursorPinPressTest] - lived in the join
 * between two halves that were each correct alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class GraphLockTest {

    @get:Rule
    val compose = createComposeRule()

    private var inspects = mutableListOf<Double?>()
    private var pans = 0
    private var dismissed = 0

    /**
     * The chart inside the dashboard's dismiss-on-tap-away handler, as it really
     * sits. [cursorMovable] off is how a one-finger drag becomes a pan.
     */
    private fun dashboard(locked: Boolean, cursorMovable: Boolean = true): (Boolean) -> Unit {
        var isLocked by mutableStateOf(locked)
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(
                    modifier = Modifier
                        .size(width = 500.dp, height = 300.dp)
                        .testTag("dashboard")
                        .dismissCursorOnTapAway(active = true) { dismissed++ },
                ) {
                    PmChart(
                        render = chartRenderFor(ViewMode.LIVE),
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                        onPan = { pans++ },
                        onInspect = { inspects += it },
                        cursorMovable = cursorMovable,
                        graphLocked = isLocked,
                    )
                }
            }
        }
        return { isLocked = it }
    }

    /**
     * A point comfortably inside the plotting rectangle, in the canvas pixels the
     * chart's own geometry is written in - so it stays inside whatever the screen
     * density does to the dp box around it.
     */
    private fun onThePlot(): Offset = Offset(ChartGeometry.leftPad + 40f, 40f)

    private fun tapThePlot() {
        compose.onNodeWithTag("plot").performTouchInput { click(onThePlot()) }
        compose.waitForIdle()
    }

    @Test fun aLockedGraphIgnoresATapOnThePlot() {
        dashboard(locked = true)
        tapThePlot()
        assertEquals("a locked plot moved the crosshair", emptyList<Double?>(), inspects)
    }

    /** And the stray touch must not reach the dismiss handler either. */
    @Test fun aLockedGraphDoesNotLetATouchClearTheCrosshair() {
        dashboard(locked = true)
        tapThePlot()
        assertEquals("a touch on the locked plot cleared the crosshair", 0, dismissed)
    }

    @Test fun anUnlockedGraphAnswersTheSameTap() {
        dashboard(locked = false)
        tapThePlot()
        assertEquals("the unlocked plot ignored a tap", 1, inspects.size)
        assertNotNull("the tap did not land on a sample", inspects.single())
    }

    @Test fun aLockedGraphIgnoresADragThatWouldPan() {
        dashboard(locked = true, cursorMovable = false)
        compose.onNodeWithTag("plot").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("a locked plot panned", 0, pans)
    }

    @Test fun anUnlockedGraphPansOnTheSameDrag() {
        dashboard(locked = false, cursorMovable = false)
        compose.onNodeWithTag("plot").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue("the unlocked plot ignored a pan", pans > 0)
    }

    /**
     * Flipped on a composition already running, which is the only way the app
     * ever flips it - and the only way to catch a gate that is installed once and
     * then never swapped. Both directions, because they fail independently.
     */
    @Test fun theLockTakesEffectAndLetsGoWhileTheChartIsOnScreen() {
        val setLocked = dashboard(locked = false)
        tapThePlot()
        assertEquals("the plot was not answering to begin with", 1, inspects.size)

        compose.runOnUiThread { setLocked(true) }
        compose.waitForIdle()
        tapThePlot()
        assertEquals("locking a live chart did not stop it", 1, inspects.size)

        compose.runOnUiThread { setLocked(false) }
        compose.waitForIdle()
        tapThePlot()
        assertEquals("releasing the lock did not give the plot back", 2, inspects.size)
    }

    // --- the toggle itself --------------------------------------------------

    /**
     * The icon's face and its spoken words both state the graph's standing, so
     * they have to flip with the state: an open padlock while touch works, a
     * closed one while it does not, and descriptions that lead with "Lock
     * graph"/"Release graph". "graph" is carried in both spoken forms because
     * the y axis has a Release of its own within earshot, and Pin has an
     * Unpin - three bare verbs would be a puzzle. The control is an icon on
     * the plot now (see `GraphLockIcon`), with the same face the chip had.
     */
    @Test fun theToggleFaceAndItsSpokenWordsFlipWithTheState() {
        var locked by mutableStateOf(false)
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                GraphLockIcon(locked = locked, onToggle = { locked = !locked }, sidePx = 110f)
            }
        }
        compose.onNodeWithText("🔓").assertIsDisplayed()
        compose.onNodeWithContentDescription(AxisHoldLabels.LOCK_GRAPH, substring = true)
            .performClick()
        compose.waitForIdle()
        assertTrue("pressing Lock did not lock", locked)
        compose.onNodeWithText("🔒").assertIsDisplayed()
        compose.onNodeWithContentDescription(AxisHoldLabels.RELEASE_GRAPH, substring = true)
            .performClick()
        compose.waitForIdle()
        assertTrue("pressing Release did not release", !locked)
        compose.onNodeWithText("🔓").assertIsDisplayed()
    }
}

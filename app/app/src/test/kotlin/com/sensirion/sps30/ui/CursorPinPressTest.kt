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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ViewMode
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
 * Pressing the crosshair's own button must never be what destroys the crosshair.
 *
 * ## The fault
 *
 * The dashboard clears the crosshair when the user touches away from it, on the
 * first *unconsumed* press. The button once greyed itself out in views it
 * thought had nothing to change, and greyed it carried no `toggleable` modifier
 * - and a control with nothing to toggle consumes nothing. So the press went
 * straight through to the dismiss handler and cleared the crosshair: the
 * pin-the-crosshair button un-pinned it, permanently, in the view the app opens
 * on. The button now toggles in every view - see [CursorPinButtonTest] - which
 * is also what consumes its press; these tests are what stop the fall-through
 * from coming back by any route.
 *
 * The chart's own dismissal already excluded the button by name (`inCornerButton`)
 * and the two halves each looked right in isolation, which is why nothing caught
 * it. It only exists in the composition of a real control under a real dismiss
 * handler, so that is what this puts together, rather than either half's idea of
 * the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class CursorPinPressTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0
    private var toggled = 0

    /** The dashboard as the plot sees it: the chart inside the dismiss handler. */
    private fun dashboardShowing(viewMode: ViewMode): (ViewMode) -> Unit {
        var mode by mutableStateOf(viewMode)
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(
                    modifier = Modifier
                        .size(width = 500.dp, height = 300.dp)
                        .testTag("dashboard")
                        .dismissCursorOnTapAway(active = true) { dismissed++ },
                ) {
                    PmChart(
                        render = chartRenderFor(mode, inspectedEpoch = 1500.0),
                        modifier = Modifier.fillMaxSize(),
                        onPinCursorToTime = { toggled++ },
                    )
                }
            }
        }
        return { mode = it }
    }

    private fun pressTheCornerButton() {
        compose
            .onNodeWithContentDescription("Crosshair", substring = true, useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()
    }

    /** `Auto` - the window the app opens on, where the fault used to live. */
    @Test
    fun theDefaultViewsCornerButtonDoesNotClearTheCrosshair() {
        dashboardShowing(ViewMode.ALL)
        pressTheCornerButton()
        assertEquals(
            "pressing the crosshair's own button dismissed the crosshair",
            0,
            dismissed,
        )
    }

    /**
     * Every view that shows the plot without a live window has the same button in
     * the same state, so none of them may lose the crosshair to it either.
     */
    @Test
    fun noViewOfThePastLosesItsCrosshairToTheCornerButton() {
        val show = dashboardShowing(ViewMode.ALL)
        for (mode in listOf(ViewMode.ALL, ViewMode.PIN, ViewMode.LIVE_FROZEN)) {
            compose.runOnUiThread { show(mode) }
            compose.waitForIdle()
            dismissed = 0
            pressTheCornerButton()
            assertEquals("$mode lost its crosshair to the corner button", 0, dismissed)
        }
    }

    /** A live window, where the button does toggle: it still must not dismiss. */
    @Test
    fun theLiveViewsCornerButtonTogglesAndKeepsTheCrosshair() {
        dashboardShowing(ViewMode.LIVE)
        pressTheCornerButton()
        assertEquals("the pin did not toggle", 1, toggled)
        assertEquals("toggling the pin dismissed the crosshair", 0, dismissed)
    }

    /**
     * And the dismissal still works, which is the other half of being right.
     *
     * A tap on the far side of the margin below the axis - away from the corner
     * button, on nothing at all - is the gesture that clears the readout, and a
     * fix that made the whole plot swallow presses would take it away.
     */
    @Test
    fun aTapOnEmptyMarginStillClearsTheCrosshair() {
        dashboardShowing(ViewMode.ALL)
        compose.onNodeWithTag("dashboard").performTouchInput {
            click(Offset(width * 0.8f, height - 4f))
        }
        compose.waitForIdle()
        assertTrue("a tap away from the plot no longer dismisses the crosshair", dismissed > 0)
    }

    /** Belt and braces: the button is not silently absent in the default view. */
    @Test
    fun theButtonIsThereToBePressedInTheDefaultView() {
        dashboardShowing(ViewMode.ALL)
        compose
            .onNodeWithContentDescription("Crosshair", substring = true, useUnmergedTree = true)
            .assertExists()
        assertEquals("nothing should have been dismissed by merely showing it", 0, dismissed)
    }
}

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
import androidx.compose.ui.test.onNodeWithTag
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
 * What a finger on the plot does to a pinned cursor: nothing at all.
 *
 * While any pin is engaged, a touch is read exactly as it is with the crosshair
 * switched off - a single finger pans - and neither the cursor's position nor
 * its existence answers to the glass. Moving the cursor by touch is possible
 * only in the free-cursor state with the crosshair on. The rule itself is
 * [cursorMovableByTouch], unit-tested in [CursorPinStateTest]; what this puts
 * together is the composition - the real chart, fed through the same function
 * the dashboard feeds it through, under the dashboard's real dismiss handler -
 * because that join is where every fault in this area has actually lived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class CursorPinTouchTest {

    @get:Rule
    val compose = createComposeRule()

    private var inspects = mutableListOf<Double?>()
    private var pans = 0
    private var dismissed = 0

    /**
     * The chart wired the way [DashboardScreen] wires it: movability comes from
     * [cursorMovableByTouch] over the crosshair switch and the pin state, and
     * the dashboard's dismiss-on-tap-away handler is active only for a free
     * cursor - a pinned one is not dismissable, so a pinned chart sits under an
     * inactive handler exactly as it does in the app.
     *
     * One composition per test, with the pin switched on the running screen -
     * which is how the app switches it, and the only way a loop over the pin
     * types can share the compose rule.
     */
    private fun chartWith(pin: CursorPin): (CursorPin) -> Unit {
        var current by mutableStateOf(pin)
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(
                    modifier = Modifier
                        .size(width = 500.dp, height = 300.dp)
                        .testTag("dashboard")
                        .dismissCursorOnTapAway(
                            active = current is CursorPin.FreeCursor,
                        ) { dismissed++ },
                ) {
                    PmChart(
                        render = chartRenderFor(ViewMode.LIVE, inspectedEpoch = 1500.0),
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                        onPan = { pans++ },
                        onInspect = { inspects += it },
                        cursorMovable = cursorMovableByTouch(enabled = true, pin = current),
                        cursorPinnedToTime = current is CursorPin.ToTime,
                    )
                }
            }
        }
        return { next ->
            compose.runOnUiThread { current = next }
            compose.waitForIdle()
        }
    }

    /** A point comfortably inside the plotting rectangle, in canvas pixels. */
    private fun onThePlot(): Offset = Offset(ChartGeometry.leftPad + 40f, 40f)

    private val everyPin = listOf(
        CursorPin.ToTime(1500.0),
        CursorPin.FromRight(3),
        CursorPin.FromLeft(2),
    )

    @Test fun aTapOnThePlotDoesNotMoveAPinnedCursor() {
        val show = chartWith(everyPin.first())
        for (pin in everyPin) {
            show(pin)
            inspects.clear()
            compose.onNodeWithTag("plot").performTouchInput { click(onThePlot()) }
            compose.waitForIdle()
            assertEquals("$pin: a touch reached the pinned cursor", emptyList<Double?>(), inspects)
        }
    }

    @Test fun aSingleFingerDragPansWhilePinned() {
        val show = chartWith(everyPin.first())
        for (pin in everyPin) {
            show(pin)
            pans = 0
            compose.onNodeWithTag("plot").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertTrue("$pin: a one-finger drag did not pan", pans > 0)
        }
    }

    @Test fun touchCannotDismissAPinnedCursorEither() {
        // Not moving the cursor is only half of "does not affect it": the tap
        // must not clear it through the dashboard's dismiss handler, and a drag
        // in the y-axis gutter - which yields a movable crosshair - must not
        // clear it, and a tap on the margin below the axis must not either.
        val show = chartWith(everyPin.first())
        for (pin in everyPin) {
            show(pin)
            dismissed = 0
            inspects.clear()
            compose.onNodeWithTag("plot").performTouchInput {
                click(onThePlot())
                click(Offset(ChartGeometry.leftPad / 2f, height / 2f))
                click(Offset(width * 0.8f, height - 4f))
            }
            compose.waitForIdle()
            assertEquals("$pin: touch dismissed the pinned cursor", 0, dismissed)
            assertEquals("$pin: touch cleared the pinned cursor", emptyList<Double?>(), inspects)
        }
    }

    @Test fun theFreeCursorStillMovesOnTouch() {
        // The behaviour the captain uses most, stated as its own test rather
        // than assumed: crosshair on, nothing pinned, a tap moves the cursor.
        chartWith(CursorPin.FreeCursor)
        compose.onNodeWithTag("plot").performTouchInput { click(onThePlot()) }
        compose.waitForIdle()
        assertEquals("the free cursor ignored a tap", 1, inspects.size)
        assertNotNull("the tap did not land on a sample", inspects.single())
    }

    @Test fun pinningAndUnpinningOnTheRunningScreenHandsTouchBackAndForth() {
        // The transition itself, on a composition already running - the only
        // way the app ever flips it. Free: touch moves the cursor. Pinned:
        // the same tap pans. Freed again: the tap is the cursor's once more.
        val show = chartWith(CursorPin.FreeCursor)
        compose.onNodeWithTag("plot").performTouchInput { click(onThePlot()) }
        compose.waitForIdle()
        assertEquals("the free cursor was not answering to begin with", 1, inspects.size)

        show(CursorPin.ToTime(1500.0))
        compose.onNodeWithTag("plot").performTouchInput { click(onThePlot()) }
        compose.waitForIdle()
        assertEquals("pinning did not take the touch away", 1, inspects.size)

        show(CursorPin.FreeCursor)
        compose.onNodeWithTag("plot").performTouchInput { click(onThePlot()) }
        compose.waitForIdle()
        assertEquals("unpinning did not hand the touch back", 2, inspects.size)
    }
}

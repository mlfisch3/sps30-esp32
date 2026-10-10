package com.sensirion.sps30.ui

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.sensirion.sps30.Slow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the captain's report: rotating the phone lost the crosshair.
 *
 * The cursor state used to be four separate declarations in the dashboard, and
 * the free crosshair's anchor was the one in a plain `remember` - so rotating
 * kept an engaged pin but silently discarded a crosshair that had merely been
 * placed, which is the state the crosshair is used in most. The whole family
 * now lives in [CursorState] behind one saver.
 *
 * These tests put the real [rememberCursorState] through exactly what a
 * rotation does to composable state - save, dispose, restore - and hold every
 * part of the family to surviving it: same data position, still drawn, still
 * pinned if it was pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(Slow::class)
class CursorRotationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: CursorState

    /** Composes the production holder and rotates the screen around [mutate]. */
    private fun rotateAround(mutate: (CursorState) -> Unit) {
        val tester = StateRestorationTester(compose)
        tester.setContent { state = rememberCursorState() }
        compose.runOnIdle { mutate(state) }
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
    }

    @Test
    fun placedCrosshairSurvivesRotation() {
        rotateAround { it.cursor = ChartCursor.AtTime(1_754_000_123.0) }
        compose.runOnIdle {
            assertEquals(
                "the crosshair the user placed must survive rotation",
                ChartCursor.AtTime(1_754_000_123.0),
                state.cursor,
            )
            assertEquals(CursorPin.FreeCursor, state.pin)
        }
    }

    @Test
    fun crosshairHoldingItsPlaceSurvivesRotation() {
        rotateAround { it.cursor = ChartCursor.AtPlace(0.25) }
        compose.runOnIdle { assertEquals(ChartCursor.AtPlace(0.25), state.cursor) }
    }

    @Test
    fun pinnedToTimeSurvivesRotationStillPinned() {
        rotateAround {
            it.cursor = null
            it.pin = CursorPin.ToTime(1_754_000_123.0)
        }
        compose.runOnIdle {
            assertEquals(
                "a pinned crosshair must come back still pinned, on its moment",
                CursorPin.ToTime(1_754_000_123.0),
                state.pin,
            )
        }
    }

    @Test
    fun aFromRightPinSurvivesRotationWithItsCount() {
        rotateAround { it.pin = CursorPin.FromRight(7) }
        compose.runOnIdle { assertEquals(CursorPin.FromRight(7), state.pin) }
    }

    @Test
    fun aFromLeftPinSurvivesRotationWithItsCount() {
        rotateAround { it.pin = CursorPin.FromLeft(3) }
        compose.runOnIdle { assertEquals(CursorPin.FromLeft(3), state.pin) }
    }

    @Test
    fun followSurvivesRotation() {
        rotateAround {
            it.follows = true
            it.cursor = ChartCursor.AtLatest
        }
        compose.runOnIdle {
            assertEquals(ChartCursor.AtLatest, state.cursor)
            assertEquals(true, state.follows)
        }
    }

    @Test
    fun aSwitchedOffCrosshairStaysOff() {
        rotateAround {
            it.enabled = false
            it.cursor = null
        }
        compose.runOnIdle {
            assertFalse("rotation must not silently switch the crosshair back on", state.enabled)
            assertEquals(null, state.cursor)
        }
    }
}

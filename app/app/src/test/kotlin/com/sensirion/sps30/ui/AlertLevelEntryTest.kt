package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.AlertEngine
import com.sensirion.sps30.data.AlertLevel
import com.sensirion.sps30.data.AlertRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Typing a decimal alert level into the real field.
 *
 * ## Why this is not covered by `AlertLevelTest`
 *
 * That test pins the arithmetic - sanitise, parse, format - and a field can use
 * correct arithmetic and still be unable to accept a decimal. The field is
 * re-rendered from the stored rule *while it is being edited*, so what is on
 * screen after each keystroke is a product of the whole loop and not of any one
 * function in it. Only driving the real field settles it.
 *
 * **Type a key at a time.** One `performTextInput("12.34")` is a single
 * `onValueChange` carrying the finished string, which never produces the
 * intermediate `"12."` and so cannot see anything that goes wrong there. Fed a
 * character at a time this test goes red against the field as it was - digits
 * only, redisplayed as `threshold.toInt().toString()` - which was checked by
 * putting that code back.
 *
 * `@Category(Slow::class)` for the same reason the layout tests are: it starts
 * Compose under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
@Category(Slow::class)
class AlertLevelEntryTest {

    @get:Rule val compose = createComposeRule()

    /** The section with one rule in it, reporting whatever it stores. */
    private fun oneRule(start: Double, onStore: (AlertRule) -> Unit) {
        compose.setContentAtWidth(OPTIONS_SHEET_WIDTH) {
            // `remember`, because without it every recomposition builds a fresh
            // rule back at [start] and the section is never re-rendered from
            // what was stored - which is the loop this class exists to drive.
            var rule by remember {
                mutableStateOf(AlertEngine.defaults().first().copy(threshold = start))
            }
            Column {
                AlertsSection(
                    rules = listOf(rule),
                    activeIds = emptySet(),
                    onUpdate = { rule = it; onStore(it) },
                    onTest = {},
                )
            }
        }
    }

    @Test fun aTwoDecimalLevelCanBeTypedAndIsWhatGetsStored() {
        var stored = 0.0
        oneRule(start = 20.0) { stored = it.threshold }

        compose.onNodeWithText("20").performTextClearance()
        // One character at a time, and that is the whole point - see the class
        // comment. Typing the string in one go tests a case the user never
        // produces and the old field would have passed it.
        for (key in "12.34") {
            compose.onNode(hasSetTextAction()).performTextInput(key.toString())
            compose.waitForIdle()
        }

        assertEquals("the decimal point must survive being typed", 12.34, stored, 0.0)
        // And the field shows back exactly what was entered, rather than the
        // integer part of it - which is what `threshold.toInt().toString()` did.
        compose.onNodeWithText("12.34").assertExists()
    }

    @Test fun theLevelIsDisplayedBackAtTwoDecimals() {
        oneRule(start = 12.34) {}
        compose.onNodeWithText("12.34").assertExists()
        assertEquals("12.34", AlertLevel.format(12.34))
    }

    @Test fun aClearedFieldSaysSoAndLeavesTheStoredLevelAlone() {
        var stored = 55.5
        oneRule(start = 55.5) { stored = it.threshold }

        compose.onNodeWithText("55.5").performTextClearance()
        compose.waitForIdle()

        assertEquals("nothing usable was entered, so nothing was saved", 55.5, stored, 0.0)
        // Silently declining to save is its own small lie, so the field says it.
        compose.onNodeWithText(AlertLevel.rejection("")!!).assertExists()
    }
}

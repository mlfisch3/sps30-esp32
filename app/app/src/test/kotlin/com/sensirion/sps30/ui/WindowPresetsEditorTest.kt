package com.sensirion.sps30.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.WindowPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The window buttons field, driven as the user drives it.
 *
 * [com.sensirion.sps30.data.WindowPresetsTest] pins the parsing; this drives
 * the real field, because the fault being guarded against lived between the
 * two - every token parsed, and the chip row then silently discarded what the
 * field had accepted. What must hold at the surface is the pair: a window of
 * any size Applies, and a refusal is a sentence on screen rather than nothing
 * happening.
 *
 * `@Category(Slow::class)` for the reason the layout tests are: it starts
 * Compose under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
@Category(Slow::class)
class WindowPresetsEditorTest {

    @get:Rule val compose = createComposeRule()

    /** The editor over the default config, reporting what Apply delivers. */
    private fun editor(onApply: (List<WindowPreset>) -> Unit) {
        compose.setContentAtWidth(OPTIONS_SHEET_WIDTH) {
            WindowPresetsEditor(AppConfig(), onApply)
        }
    }

    private fun retype(text: String) {
        val field = compose.onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput(text)
    }

    @Test fun windowsOfAnySizeApply() {
        var applied: List<WindowPreset>? = null
        editor { applied = it }
        retype("1h, 2d, 3w, 1M, 1y")
        compose.onNodeWithText("Apply").performClick()
        assertEquals(
            listOf(3_600.0, 172_800.0, 1_814_400.0, 2_592_000.0, 31_536_000.0),
            applied?.map { it.seconds },
        )
    }

    @Test fun aRefusalIsASentenceOnScreenAndNothingIsApplied() {
        var applied: List<WindowPreset>? = null
        editor { applied = it }
        retype("1h, 5q")
        compose.onNodeWithText("Apply").performClick()
        assertNull("a field with an unreadable token still applied", applied)
        compose.onNodeWithText("unknown unit 'q' in '5q'", substring = true).assertExists()

        // Editing the text withdraws the verdict: it judged text that is gone.
        retype("1h, 5s")
        compose.onAllNodes(
            androidx.compose.ui.test.hasText("unknown unit", substring = true),
        ).fetchSemanticsNodes(atLeastOneRootRequired = false).let {
            assertTrue("the error outlived the text it judged", it.isEmpty())
        }
        compose.onNodeWithText("Apply").performClick()
        assertEquals(listOf(3_600.0, 5.0), applied?.map { it.seconds })
    }
}

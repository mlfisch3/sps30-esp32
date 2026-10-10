package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The crosshair's corner button, pressed the way a finger presses it.
 *
 * The reported defect was that the button only became tappable when new data
 * happened to sit at the far right of the graph - it greyed itself out in
 * every view whose range did not run to now, which is every view a pan or a
 * zoom produces. Pinning the cursor to the point it is lined up with must be
 * possible from **any** view: the pin fixes what the cursor holds, and it is
 * also what hands the single finger back to panning, and both of those exist
 * in a frozen view too. So the button toggles everywhere, no exceptions - the
 * loop below deliberately walks every [ViewMode] there is, so a mode added
 * later is covered without being named.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class CursorPinButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private var toggled = 0

    private fun chartShowing(viewMode: ViewMode): (ViewMode) -> Unit {
        var mode by mutableStateOf(viewMode)
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(modifier = Modifier.size(width = 500.dp, height = 300.dp)) {
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

    @Test fun theCornerButtonWorksInEveryView() {
        val show = chartShowing(ViewMode.LIVE)
        for (mode in ViewMode.entries) {
            compose.runOnUiThread { show(mode) }
            compose.waitForIdle()
            toggled = 0
            pressTheCornerButton()
            assertEquals("$mode ignored the pin button", 1, toggled)
        }
    }
}

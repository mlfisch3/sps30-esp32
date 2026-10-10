package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.data.WindowPreset
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
 * Where there is room to show every value button, they wrap into view; only
 * where there is not does the strip hide some behind its overflow mark.
 *
 * ## The fault this guards
 *
 * The captain, in landscape on his tablet: most of the control column standing
 * empty while the window sizes he wanted sat hidden behind an overflow mark. A
 * strip that scrolls is the right answer for a panel with no room, and the wrong
 * one for a panel with plenty - hiding a button there is spending the user's
 * taps to save space nobody needed.
 *
 * So [GroupValues] has two shapes, and the choice between them is [PanelFit]'s
 * measurement rather than a guess about the device: wrapped into full view where
 * everything genuinely fits the column's height, the marked strip where it does
 * not - and always the strip in portrait, where the lines a wrap would take
 * belong to the chart. These tests pin the choice at the four device shapes, with
 * more presets than the defaults because the fault only shows once the strip has
 * something to hide.
 *
 * Slow for the reason in `LayoutTest.kt`: real Compose in native graphics mode,
 * which is what makes a `dp` here mean a `dp` on the tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ControlWrapTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The case the captain raised: the tablet in landscape has height to spare,
     * so every window size is simply visible - wrapped onto further lines inside
     * the values box, with no overflow mark left saying there is more.
     */
    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletLandscapeWrapsEverySizeIntoView() {
        compose.setContent { Sps30Theme(useDarkTheme = true) { Dashboard() } }
        compose.waitForIdle()

        for (forward in listOf(true, false)) {
            assertEquals(
                "the \"window sizes\" strip still draws an overflow mark on a screen " +
                    "with room to show every chip",
                0,
                marks("window sizes", forward),
            )
        }

        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val values = compose.onNodeWithTag(controlGroupValuesTag(WINDOW_GROUP_LABEL))
            .getUnclippedBoundsInRoot()
        var lines = 1
        var previousTop = compose.onNodeWithText(PRESETS.first().label)
            .getUnclippedBoundsInRoot().top
        for (preset in PRESETS) {
            val chip = compose.onNodeWithText(preset.label).getUnclippedBoundsInRoot()
            assertTrue(
                "\"${preset.label}\" is at ${chip.pretty()}, outside the window values box " +
                    "at ${values.pretty()} - hidden, clipped or squeezed rather than wrapped",
                chip.left >= values.left - TOLERANCE && chip.right <= values.right + TOLERANCE &&
                    chip.top >= values.top - TOLERANCE && chip.bottom <= values.bottom + TOLERANCE,
            )
            assertTrue(
                "\"${preset.label}\" is at ${chip.pretty()}, off the ${screen.pretty()} " +
                    "screen - every size must be visible without scrolling here",
                chip.top >= screen.top && chip.bottom <= screen.bottom &&
                    chip.left >= screen.left && chip.right <= screen.right,
            )
            if (chip.top > previousTop + TOLERANCE) {
                lines++
                previousTop = chip.top
            }
        }
        assertTrue(
            "${PRESETS.size} sizes claim to fit on $lines line(s) of a 300dp column, " +
                "so something is hidden rather than wrapped",
            lines >= 2,
        )
    }

    /**
     * The tablet in portrait stacks, and its strip is wide enough for everything:
     * every size on the one line, so the mark has nothing to report.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletPortraitFitsEverySizeOnItsLine() {
        compose.setContent { Sps30Theme(useDarkTheme = true) { Dashboard() } }
        compose.waitForIdle()
        assertEquals(
            "a 934dp strip holds every size, so a mark there is a lie",
            0,
            marks("window sizes", forward = true) + marks("window sizes", forward = false),
        )
    }

    /**
     * The case that breaks most easily: a phone in portrait genuinely has no room
     * - the height belongs to the chart - so the strip and its overflow mark stay
     * exactly as they were.
     */
    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phonePortraitKeepsTheMarkedStrip() =
        assertTheStripStillSaysMore()

    /** A phone in landscape: 411dp of height for the whole column, so the same. */
    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneLandscapeKeepsTheMarkedStrip() =
        assertTheStripStillSaysMore()

    private fun assertTheStripStillSaysMore() {
        compose.setContent { Sps30Theme(useDarkTheme = true) { Dashboard() } }
        compose.waitForIdle()
        assertEquals(
            "this screen has no room to wrap every size into view, so the strip must " +
                "still say when it is hiding some",
            1,
            marks("window sizes", forward = true),
        )
        assertEquals(
            "nothing has been scrolled, so no mark belongs on the left",
            0,
            marks("window sizes", forward = false),
        )
    }

    // --- the harness ---------------------------------------------------------

    private val TOLERANCE = 0.5.dp

    /**
     * More presets than the defaults, because the strip only hides something once
     * there is something to hide - and labels that collide with nothing else on
     * the panel, so each chip is found exactly once.
     */
    private val PRESETS = listOf(
        WindowPreset("2m", 120.0),
        WindowPreset("7m", 420.0),
        WindowPreset("20m", 1200.0),
        WindowPreset("45m", 2700.0),
        WindowPreset("90m", 5400.0),
        WindowPreset("3h", 10_800.0),
        WindowPreset("8h", 28_800.0),
        WindowPreset("16h", 57_600.0),
    )

    private fun marks(what: String, forward: Boolean): Int =
        compose.onAllNodes(hasContentDescription(moreChipsThisWay(what, forward)))
            .fetchSemanticsNodes().size

    /**
     * The real dashboard, branching the way [DashboardScreen] branches - the
     * construction `ControlLayoutTest` uses, with the larger preset list in the
     * config.
     */
    @Composable
    private fun Dashboard() {
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val viewModel = DashboardViewModel(app)
        val render = chartRenderFor(ViewMode.PIN)
        val state = DashboardUiState(
            chart = render.data!!.copy(yControl = YAxisControl.OVERRIDDEN),
            config = AppConfig(windowPresets = PRESETS),
        )
        val touch = ChartTouchCallbacks(
            inspected = null,
            onInspect = {},
            cursor = CursorControls(
                enabled = true,
                onToggleEnabled = {},
                follows = false,
                onToggleFollows = {},
            ),
            onGestureStart = {},
            onGestureEnd = {},

            graphLocked = true,
            onToggleGraphLock = {},
        )
        Box(Modifier.fillMaxSize()) {
            if (sideBySide(LocalConfiguration.current)) {
                WideDashboard(
                    Modifier, state, viewModel, render, touch,
                    onChartSize = {}, onTimeFlag = {}, onOpenOptions = {},
                )
            } else {
                StackedDashboard(
                    Modifier, state, viewModel, render, touch,
                    onChartSize = {}, onTimeFlag = {}, onOpenOptions = {},
                )
            }
        }
    }
}

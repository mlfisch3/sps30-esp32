package com.sensirion.sps30.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.ui.theme.Sps30Theme
import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The icon row is gone, and what it held went where the captain sent it.
 *
 * The row above the controls used to hold three things: the battery pill, the
 * storage-map icon and the three-dot menu button. The first two moved into the
 * menu (their reachability is pinned by `PowerPanelTest` and
 * `StorageMapDialogTest`); the dots moved onto the control cluster's own row,
 * across a gap wide enough that they cannot be read as a fifth chip; and the
 * row's height went to the plot. This file pins the main screen's half of
 * that: the old row's tenants are off the screen, the dots sit on the chips'
 * row beyond a real gap, and they are bare - the grey disc they floated on is
 * not drawn.
 *
 * The disc is checked in pixels, the way [AutoChosenShapeTest] checks its
 * corners and for the same reason: no amount of semantics can see a
 * background. The screenshot comes off the test's own root view - see that
 * file for why `captureToImage` cannot be used under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ReclaimedIconRowTest {

    @get:Rule
    val compose = createComposeRule()

    /** A background nothing in the theme draws, so "nothing drawn" is unambiguous. */
    private val backdrop = Color(0xFF000000)

    /**
     * The stacked dashboard with everything the old row showed: a host
     * reporting power, a store with a flash ring. If the row were still there,
     * both of its icons would render.
     */
    private fun showDashboard() {
        val app = RuntimeEnvironment.getApplication()
        val viewModel = DashboardViewModel(app)
        val state = DashboardUiState(
            power = ServerMessage.PowerStatus(source = "usb"),
            storage = ServerMessage.Storage(
                usedPages = 1200, totalPages = 4480, readings = 53000, sessions = 3,
                fraction = 0.27, warnAt = 0.85, warn = false, ready = true,
            ),
            chart = chartRenderFor(ViewMode.LIVE).data,
        )
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(backdrop)) {
                    StackedDashboard(
                        Modifier, state, viewModel, state.chartRender(null),
                        ChartTouchCallbacks(
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
                        ),
                        onChartSize = {}, onTimeFlag = {}, onOpenOptions = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theOldRowsTenantsAreOffTheMainScreen() {
        showDashboard()
        // Relocated, not deleted: these two live in the menu now, and their
        // reachability there has tests of its own.
        compose.onNodeWithContentDescription(POWER_PILL_DESC).assertDoesNotExist()
        compose.onNodeWithContentDescription("Storage map").assertDoesNotExist()
        // The way into the menu did not go with them.
        compose.onNodeWithContentDescription(OPTIONS_BUTTON_DESC).assertExists()
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theDotsShareTheChipsRowAcrossADeliberateGap() {
        showDashboard()
        val dots = compose.onNodeWithContentDescription(OPTIONS_BUTTON_DESC)
            .fetchSemanticsNode().boundsInRoot
        val average = compose.onNodeWithText(AVERAGE_LABEL)
            .fetchSemanticsNode().boundsInRoot

        // On the cluster's one line: the dots' centre falls within the Average
        // chip's vertical extent. Average is the flow's final chip now that the
        // lock, crosshair and Follow chips live on the plot itself
        // (sensirion-axis-hold-inplot) - the cluster is Select, Average and the
        // dots, and it fits one line at phone width, which was the whole point
        // of the move: the wrapped second line went back to the plot.
        val centreY = (dots.top + dots.bottom) / 2f
        assertTrue(
            "the three dots (centre y $centreY) do not share a line with the " +
                "Average chip (${average.top}..${average.bottom}); they were meant to " +
                "ride the control cluster's line",
            centreY in average.top..average.bottom,
        )

        // And separated from it by far more than the 6dp that separates the
        // chips from each other - the gap is the only thing keeping the dots
        // from reading as a third member of the group, so it has to be
        // unmistakable.
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val gapDp = (dots.left - average.right) / density
        assertTrue(
            "only ${gapDp.roundToInt()}dp separates the three dots from the Average " +
                "chip - at that distance they read as a third button of the group",
            gapDp >= 24f,
        )
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theDotsAreBareOfTheirOldDisc() {
        showDashboard()
        val bounds = compose.onNodeWithContentDescription(OPTIONS_BUTTON_DESC)
            .fetchSemanticsNode().boundsInRoot
        val screen = screenshot()

        // The button's box is mostly padding round a thin glyph. A disc filled
        // the whole box, so the box's corners are where its absence is
        // unambiguous: just inside each one there must be nothing but backdrop.
        val inset = 2
        val xs = listOf(bounds.left.toInt() + inset, bounds.right.toInt() - 1 - inset)
        val ys = listOf(bounds.top.toInt() + inset, bounds.bottom.toInt() - 1 - inset)
        for (x in xs) for (y in ys) {
            assertTrue(
                "pixel ($x, $y) in the corner of the three-dot button is painted: " +
                    "the dots are floating on a container again",
                screen.getPixel(x, y) == backdrop.toArgb(),
            )
        }

        // And the glyph itself is really there - a fully bare region would
        // pass the corner check by being absent.
        var inked = false
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                if (screen.getPixel(x, y) != backdrop.toArgb()) inked = true
            }
        }
        assertTrue("no glyph is drawn where the three-dot button should be", inked)
    }

    private fun Color.toArgb(): Int = android.graphics.Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )

    /** The test's own root view, drawn into a bitmap - see [AutoChosenShapeTest]. */
    private fun screenshot(): Bitmap {
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }
}

package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ViewMode
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
 * Which buttons belong to "window" must be visible, and the group's geometry is
 * what says so.
 *
 * ## The shape being asserted
 *
 * The captain described it precisely, from his tablet: an outer box around the
 * whole group; the group's name on the top row with the group's mode buttons -
 * Pin and Unpin - beside it; and the value buttons in a thin box of their own
 * under that row. The shape it replaced had the label inside the box over the
 * buttons and Pin wrapping onto a line that was otherwise empty, which read as
 * the mistake it looked like. See [ControlGroup] for the history. The averaging
 * group these assertions used to cover alongside it is gone - averaging is the
 * Average button in the cluster now, asserted here to stay out of the box, with
 * its own behaviour and geometry in `AveragingControlTest`.
 *
 * A caption pointing at a row is a claim nothing can check. A box is geometry,
 * so these assertions are geometric: the label and the mode buttons share the
 * top row, that row and the values box are both inside the outer box, every
 * value button is inside the values box, and the controls that belong to
 * neither group are outside both.
 *
 * ## Why the four shapes
 *
 * The boxes went in immediately after the control column was made to reflow
 * rather than sit at fixed positions, and the whole point of that work was that
 * a fixed arrangement was clipped on the captain's phone in portrait while
 * looking perfect on his tablet. A box is exactly the sort of thing that puts a
 * fixed arrangement back, so the grouping is asserted at all four device shapes
 * rather than at a representative one - the same four as `ControlLayoutTest`,
 * for the same reason.
 *
 * Slow for the reason in `LayoutTest.kt`: real Compose in native graphics mode,
 * which is what makes a `dp` here mean a `dp` on the tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ControlGroupingTest {

    @get:Rule
    val compose = createComposeRule()

    // --- the real dashboard, at the four shapes ------------------------------

    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phonePortraitGroupsTheControls() =
        assertTheGroupsRead()

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneLandscapeGroupsTheControls() =
        assertTheGroupsRead()

    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletPortraitGroupsTheControls() =
        assertTheGroupsRead()

    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletLandscapeGroupsTheControls() =
        assertTheGroupsRead()

    /**
     * The group is the box the captain described, and the cluster stays clear
     * of it.
     *
     * The order down the panel has to be: the ungrouped chips, then the window
     * group whole. Within the group: the label and the mode buttons on the top
     * row, the values in their box below it, and everything inside the outer
     * line. Anything else and a label is naming buttons that are not under it,
     * which is the fault the old captions had.
     */
    private fun assertTheGroupsRead() {
        compose.setContent { Sps30Theme(useDarkTheme = true) { Dashboard() } }
        compose.waitForIdle()

        val nodes = compose.onAllNodesWithTextExactly(WINDOW_GROUP_LABEL)
        assertEquals(
            "\"$WINDOW_GROUP_LABEL\" appears ${nodes.size} times; a group heading must " +
                "be drawn once and only once",
            1,
            nodes.size,
        )

        val windowLabel = compose.onNodeWithText(WINDOW_GROUP_LABEL).bounds()
        val windowBox = compose.onNodeWithTag(controlGroupTag(WINDOW_GROUP_LABEL)).bounds()
        val windowValues =
            compose.onNodeWithTag(controlGroupValuesTag(WINDOW_GROUP_LABEL)).bounds()

        // The nesting: the label immediately above its values box, both inside
        // the group's outer box.
        assertTrue(
            "the \"window\" label at ${windowLabel.pretty()} is not above its values " +
                "box at ${windowValues.pretty()}, so it reads as one of the buttons " +
                "rather than their name",
            windowLabel.bottom <= windowValues.top + TOLERANCE,
        )
        assertTrue(
            "the \"window\" label at ${windowLabel.pretty()} is outside its group's " +
                "outer box at ${windowBox.pretty()}",
            windowLabel.isWithin(windowBox),
        )
        assertTrue(
            "the \"window\" values box at ${windowValues.pretty()} is outside its " +
                "group's outer box at ${windowBox.pretty()}",
            windowValues.isWithin(windowBox),
        )

        // Pin and Unpin are the window group's mode buttons: on the label's own
        // row - beside the word, which is what the captain asked for by name -
        // inside the outer box, and not among the values.
        for (chip in WINDOW_MODE_CHIPS) {
            val bounds = compose.onNodeWithText(chip).bounds()
            assertTrue(
                "\"$chip\" at ${bounds.pretty()} does not share the top row with the " +
                    "\"$WINDOW_GROUP_LABEL\" label at ${windowLabel.pretty()}",
                bounds.top < windowLabel.bottom && windowLabel.top < bounds.bottom,
            )
            assertTrue(
                "\"$chip\" at ${bounds.pretty()} is outside the window group's outer box " +
                    "at ${windowBox.pretty()}",
                bounds.isWithin(windowBox),
            )
            assertTrue(
                "\"$chip\" at ${bounds.pretty()} reaches into the values box at " +
                    "${windowValues.pretty()}, so a mode button reads as one of the sizes",
                bounds.bottom <= windowValues.top + TOLERANCE,
            )
        }

        // The values: Auto is one of the window sizes, inside the values box.
        assertTrue(
            "Auto is not inside the window values box",
            compose.onNodeWithText("Auto").bounds().isWithin(windowValues),
        )

        // The graph lock protects the window, the y range and the crosshair
        // alike, so it is not in the box - and a box is a strong enough claim
        // that having it inside would be a lie rather than an approximation.
        // It lives on the plot itself now (see `GraphLockIcon`), so the claim
        // to hold is that the padlock shares no ground with the window group.
        // Found by its face: the harness composes the graph locked, so the
        // padlock is closed.
        val lock = compose.onNodeWithText("🔒").bounds()
        assertTrue(
            "the graph lock at ${lock.pretty()} overlaps the " +
                "\"$WINDOW_GROUP_LABEL\" group at ${windowBox.pretty()}, " +
                "so it reads as one of the window controls",
            !lock.overlaps(windowBox),
        )

        // Average lives in the cluster too, not in the box: it is about what
        // the plot shows, not which span is on screen, and its own sizes are in
        // the chooser behind its press-and-hold (see `AveragingControlTest`).
        val average = compose.onNodeWithText(AVERAGE_LABEL).bounds()
        assertTrue(
            "the Average button is at y=${average.top.value}dp, inside or below the " +
                "\"$WINDOW_GROUP_LABEL\" group that starts at y=${windowBox.top.value}dp, " +
                "so it reads as one of the window controls",
            average.bottom <= windowBox.top,
        )

        // Nothing left that reads as somewhere to press for more.
        assertEquals(
            "a disclosure caret is still on the front panel, which is what made the " +
                "old captions read as collapsed sections",
            0,
            listOf("▲", "▼", "▸", "▾").sumOf { glyph ->
                compose.onAllNodes(hasText(glyph, substring = true)).fetchSemanticsNodes().size
            },
        )

        // Reflow, not a fixed arrangement: everything is on the screen or can be
        // scrolled to, at every one of the four shapes.
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        for (chip in WINDOW_MODE_CHIPS + listOf("Auto", AVERAGE_LABEL)) {
            val node = compose.onNodeWithText(chip)
            if (!isInside(screen, node)) node.performScrollTo()
            assertTrue(
                "\"$chip\" is at ${node.bounds().pretty()}, outside the " +
                    "${screen.pretty()} screen and unreachable by scrolling",
                isInside(screen, node),
            )
        }
    }

    // --- the box itself ------------------------------------------------------

    /**
     * The outline encloses what it names, and goes on doing so as chips are added.
     *
     * Six value chips and both mode buttons in the narrowest column the app has -
     * more than the window group holds today, because chips get added to this
     * screen steadily and a box that only fits the current set is a box that clips
     * the next one. The values box must grow downwards with the flow inside it,
     * keep every value chip within its own outline, and keep the mode buttons
     * above it, inside the outer line.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Test fun theBoxGrowsWithTheChipsRatherThanClippingThem() {
        val modeLabels = listOf("Pin", "Unpin")
        val valueLabels = listOf("Auto", "1m", "10m", "1h", "6h", "24h")
        compose.setContentAtWidth(CONTROL_COLUMN_NARROW) {
            ControlGroup(
                WINDOW_GROUP_LABEL,
                labelChips = {
                    for (label in modeLabels) FilterChip(false, {}, { Text(label) })
                },
            ) {
                ControlChips { for (label in valueLabels) FilterChip(false, {}, { Text(label) }) }
            }
        }
        val box = compose.onNodeWithTag(controlGroupTag(WINDOW_GROUP_LABEL))
            .getUnclippedBoundsInRoot()
        val values = compose.onNodeWithTag(controlGroupValuesTag(WINDOW_GROUP_LABEL))
            .getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText(WINDOW_GROUP_LABEL, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the values box at ${values.pretty()} is outside the outer box at ${box.pretty()}",
            values.isWithin(box),
        )
        assertTrue(
            "the heading at ${heading.pretty()} is not above the values box at " +
                "${values.pretty()}",
            heading.bottom <= values.top + TOLERANCE,
        )
        for (label in modeLabels) {
            val chip = compose.onNodeWithText(label, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "mode button \"$label\" at ${chip.pretty()} is outside the outer box at " +
                    "${box.pretty()}",
                chip.isWithin(box),
            )
            assertTrue(
                "mode button \"$label\" at ${chip.pretty()} reaches into the values box " +
                    "at ${values.pretty()}",
                chip.bottom <= values.top + TOLERANCE,
            )
        }
        var lines = 1
        var previousTop = compose.onNodeWithText(valueLabels.first(), useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top
        for (label in valueLabels) {
            val chip = compose.onNodeWithText(label, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "\"$label\" is at ${chip.pretty()}, outside the ${values.pretty()} values box",
                chip.isWithin(values),
            )
            if (chip.top > previousTop + TOLERANCE) {
                lines++
                previousTop = chip.top
            }
        }
        assertTrue(
            "${valueLabels.size} chips claim to fit on $lines line(s) of a " +
                "${(values.right - values.left).value}dp box, so the box squeezed them",
            lines >= 2,
        )
    }

    // --- the harness ---------------------------------------------------------

    private val TOLERANCE = 0.5.dp

    /** The window group's mode buttons, in the state [Dashboard] renders (pinned). */
    private val WINDOW_MODE_CHIPS = listOf("Pin", "Unpin")

    private fun SemanticsNodeInteraction.bounds(): DpRect = getUnclippedBoundsInRoot()

    private fun DpRect.isWithin(outer: DpRect): Boolean =
        left >= outer.left - TOLERANCE && right <= outer.right + TOLERANCE &&
            top >= outer.top - TOLERANCE && bottom <= outer.bottom + TOLERANCE

    private fun isInside(window: DpRect, node: SemanticsNodeInteraction): Boolean =
        node.getUnclippedBoundsInRoot().isWithin(window)

    /**
     * The real dashboard in its fullest state, branching the way [DashboardScreen]
     * branches - the same construction `ControlLayoutTest` uses and for the same
     * reason: pinned so Unpin is in the window group, and an overridden y axis so
     * the panel carries everything it can carry.
     */
    @Composable
    private fun Dashboard() {
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val viewModel = DashboardViewModel(app)
        val render = chartRenderFor(ViewMode.PIN)
        val state = DashboardUiState(
            chart = render.data!!.copy(yControl = YAxisControl.OVERRIDDEN),
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

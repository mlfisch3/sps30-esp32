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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The cursor controls must be on the screen, whole, on both devices, either way up.
 *
 * ## The fault this guards
 *
 * The captain found the Cursor group in one of the four states his two devices can
 * be in:
 *
 * | | portrait | landscape |
 * |---|---|---|
 * | phone | not there at all | chips visible, the sentence under them cut to a sliver |
 * | tablet | not there at all | correct |
 *
 * Two causes, one shape. The group lived only in the landscape control column, so
 * portrait - which stacks - never drew it and offered it in the options menu
 * instead, where nobody looking at the plot would find it. And that column is a
 * `Column` inside a `Row` that has already taken the screen's height, so it hands
 * each child what is *left*: on a phone in landscape the last child got a few dp
 * and rendered the top edge of a paragraph. The tablet's column has two and a half
 * times the height, so the same code was correct there, which is why it survived a
 * device pass.
 *
 * So the assertions here are about *height that ran out*, and they are made at all
 * four shapes rather than at a representative one - the fault was invisible at
 * three of them, and would have been invisible at the fourth had the tablet been
 * the phone.
 *
 * ## What is asserted, and why each fails on the old shape
 *
 * - **The chips are on the screen, exactly once.** Portrait drew them nowhere;
 *   this fails there. "Exactly once" is the other half: [PanelFit] composes the
 *   roomy shape to measure it even when it places the compact one, and this is
 *   what says the unplaced copy stays out of the semantics tree - out of the
 *   test's reach, and out of TalkBack's.
 * - **The sentence is whole**, by [androidx.compose.ui.text.TextLayoutResult]'s own
 *   account: no line ellipsised and, the one that catches this fault,
 *   `didOverflowHeight` false. A `Text` squeezed vertically reports its full string
 *   to `onNodeWithText` and ellipsises nothing - it simply draws the top of itself,
 *   which is precisely what the captain saw.
 * - **Everything sits inside the window.** Geometry, read unclipped, because a
 *   clipped rectangle is squashed against the edge and so agrees with the bug.
 *
 * Slow for the reason in `LayoutTest.kt`: real Compose under Robolectric in native
 * graphics mode, which is what makes a `dp` here mean a `dp` on the tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ControlLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phoneInPortraitShowsTheWholeCursorGroup() =
        assertCursorGroupIsWhole(expectSection = false)

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneInLandscapeShowsTheWholeCursorGroup() =
        assertCursorGroupIsWhole(expectSection = false)

    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletInPortraitShowsTheWholeCursorGroup() =
        assertCursorGroupIsWhole(expectSection = false)

    /**
     * The one shape that was already right, asserted to be unchanged: the tablet's
     * landscape column has room for the headed section, so it keeps it.
     */
    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletInLandscapeKeepsTheCursorSection() =
        assertCursorGroupIsWhole(expectSection = true)

    /**
     * The four shapes must be the two layouts the app really picks between.
     *
     * Without this the test above could be measuring the stacked layout four times
     * over and reporting that landscape is fine.
     */
    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phonePortraitStacks() = assertSideBySide(false)

    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletPortraitStacks() = assertSideBySide(false)

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneLandscapeGoesSideBySide() =
        assertSideBySide(true)

    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletLandscapeGoesSideBySide() =
        assertSideBySide(true)

    /**
     * The chip cluster survives buttons nobody has thought of yet.
     *
     * Two more chips were being added to this screen while the layout was being
     * fixed, and there will be more after them. A `Row` answers "one more chip" by
     * squeezing or clipping, silently, and only at the narrowest width - which is
     * how a control ends up off the edge of one device and nowhere else. So the
     * cluster is a flow, and this is the property that makes adding to it safe:
     * eight chips in the narrowest column the app has are all still whole and all
     * still inside it.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Test fun theChipClusterWrapsRatherThanRunningOffTheEdge() {
        val labels = listOf(
            "Pin", "Auto", "Crosshair", "Follow", "Lock", "Release", "Log Y", "Markers",
        )
        compose.setContentAtWidth(CONTROL_COLUMN_NARROW) {
            ControlChips { for (label in labels) FilterChip(false, {}, { Text(label) }) }
        }
        val cluster = compose.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot()
        var lines = 1
        var previousTop = compose.onNodeWithText(labels.first()).getUnclippedBoundsInRoot().top
        for (label in labels) {
            val chip = compose.onNodeWithText(label, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "\"$label\" is at ${chip.pretty()}, outside the ${cluster.pretty()} cluster",
                chip.left >= cluster.left - TOLERANCE && chip.right <= cluster.right + TOLERANCE,
            )
            if (chip.top > previousTop + TOLERANCE) {
                lines++
                previousTop = chip.top
            }
        }
        assertTrue(
            "${labels.size} chips claim to fit on $lines line(s) of a " +
                "${(cluster.right - cluster.left).value}dp column, so they were squeezed rather than wrapped",
            lines >= 3,
        )
    }

    // --- the harness ---------------------------------------------------------

    /** How much a rounded dp may differ before it counts as a real difference. */
    private val TOLERANCE = 0.5.dp

    private fun assertSideBySide(expected: Boolean) {
        var actual: Boolean? = null
        compose.setContent { actual = sideBySide(LocalConfiguration.current) }
        assertEquals(expected, actual)
    }

    /**
     * Render the real dashboard at this screen shape and require the whole Cursor
     * group to be on it.
     *
     * [expectSection] says which shape this screen should have got: the headed
     * section in the control column, or the chips up in the cluster with Pin and
     * Auto. Asserting it pins down the half of the fix the captain asked for by
     * name - the controls move to the top where they do not fit in the column -
     * and, on the tablet in landscape, that they did *not* move.
     */
    private fun assertCursorGroupIsWhole(expectSection: Boolean) {
        val follows = false
        compose.setContent { Sps30Theme(useDarkTheme = true) { Dashboard(follows) } }
        compose.waitForIdle()

        val window = compose.onRoot().getUnclippedBoundsInRoot()
        // The crosshair toggle and Follow are icons on the plot now (see
        // PlotChrome.kt), so what this asserts of them is presence, uniqueness
        // and being inside the window - found by their spoken words, since an
        // icon has no text face.
        for ((name, description) in listOf(
            "the crosshair toggle" to "Crosshair on",
            "Follow" to "Follow",
        )) {
            val nodes = compose.onAllNodes(
                hasContentDescription(description, substring = true),
            ).fetchSemanticsNodes()
            assertEquals(
                "$name appears ${nodes.size} times on the screen; it must be drawn " +
                    "once and only once",
                1,
                nodes.size,
            )
            assertInside(
                window,
                compose.onNodeWithContentDescription(description, substring = true),
                name,
            )
        }
        // The pin family stays on the panel, once.
        val pinChips = compose.onAllNodesWithTextExactly("Pin time")
        assertEquals(
            "\"Pin time\" appears ${pinChips.size} times; it must be drawn once",
            1,
            pinChips.size,
        )

        val note = compose.onNodeWithText(cursorNote(follows))
        val layout = note.textLayout()
        assertTrue(
            "the Cursor note was cut: line(s) ${layout.ellipsizedLines()} ellipsised",
            layout.ellipsizedLines().isEmpty(),
        )
        assertTrue(
            "the Cursor note ran out of height: ${layout.lineCount} lines laid out into " +
                "${layout.size}, so the user sees the top of a sentence and no more",
            !layout.didOverflowHeight,
        )
        // The note may end up below the fold - on a phone in landscape the panel
        // holds more than 411 dp of screen can show, and no arrangement changes
        // that. What it may not do is be unreachable: `performScrollTo` brings it
        // in through the panel's own scroll, and fails outright if there is no
        // scroll to do it with, which is the state this whole fix is about.
        if (!isInside(window, note)) note.performScrollTo()
        assertInside(window, note, "the Cursor note")

        val headings = compose.onAllNodesWithTextExactly("Cursor").size
        assertEquals(
            if (expectSection) {
                "this screen has a control column with room to spare, so the Cursor " +
                    "group keeps its own headed section there"
            } else {
                "this screen has no room for a headed Cursor section, so the pin " +
                    "chips belong in the compact panel"
            },
            if (expectSection) 1 else 0,
            headings,
        )
    }

    private fun isInside(window: DpRect, node: SemanticsNodeInteraction): Boolean {
        val bounds = node.getUnclippedBoundsInRoot()
        return bounds.top >= window.top - TOLERANCE && bounds.bottom <= window.bottom + TOLERANCE &&
            bounds.left >= window.left - TOLERANCE && bounds.right <= window.right + TOLERANCE
    }

    private fun assertInside(window: DpRect, node: SemanticsNodeInteraction, what: String) {
        assertTrue(
            "$what is at ${node.getUnclippedBoundsInRoot().pretty()}, outside the " +
                "${window.pretty()} window",
            isInside(window, node),
        )
    }

    /**
     * The real dashboard, branching the way [DashboardScreen] branches.
     *
     * The screen itself is not used because it reaches for an activity's permission
     * launchers and starts the background service; what is under test is the layout
     * it picks and what that layout does with the space, so this is the same choice
     * over the same two layouts with the same arguments.
     */
    @Composable
    private fun Dashboard(follows: Boolean) {
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val viewModel = DashboardViewModel(app)
        // Every optional control at once, because the panel is only as roomy as
        // its fullest state: pinned puts Unpin in the cluster, an overridden y
        // axis adds its own pair and note. A test against the emptiest panel
        // would pass while the real one, three chips later, did not.
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
                follows = follows,
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

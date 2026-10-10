package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
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
 * The window row reads as one row, and the strip says when it has more to show.
 *
 * ## The four faults this guards
 *
 * All four came off the captain's own screens, and all four are geometric - the
 * sort nothing in the semantics tree can see:
 *
 * 1. **Auto sat on a different line from the window sizes.** It was a third fixed
 *    chip beside Pin, so on a phone in portrait the cluster put Pin, Unpin and
 *    Auto on one line and wrapped the preset strip onto the next: the button that
 *    picks a window on one row, the windows on another, and a row spent on saying
 *    so.
 * 2. **Pin rode 8 dp above the chips beside it**, because a hand-built `Surface`
 *    is 32 dp where a `FilterChip` claims the 48 dp interactive minimum, and a
 *    flow lines its children up by their tops. It read as a mistake, which is what
 *    it was.
 * 3. **The strip's only overflow cue was a fade**, which says nothing to a user
 *    meeting it for the first time.
 *
 * The first two are asserted against the real dashboard at all four device shapes,
 * for the reason `ControlLayoutTest` gives: the arrangement differs between them
 * and a fault that shows on one can be invisible on the other three. The third is
 * asserted against the component itself, where the width can be swept and the
 * strip scrolled.
 *
 * A fourth fault used to be guarded here - Auto's chosen averaging window drawn
 * as an ordinary chip in the averaging row. That row is gone: averaging is the
 * Average button and its press-and-hold chooser now (see `AveragingControl.kt`
 * and `AveragingControlTest`), so the mark and its tests went with it.
 *
 * Slow for the reason in `LayoutTest.kt`: real Compose under Robolectric in native
 * graphics mode, which is what makes a `dp` here mean a `dp` on the tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ControlRowTest {

    @get:Rule
    val compose = createComposeRule()

    // --- Auto rides with the window sizes, at all four shapes ----------------

    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phonePortraitKeepsAutoWithTheWindows() =
        assertTheWindowRowReadsAsOne()

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneLandscapeKeepsAutoWithTheWindows() =
        assertTheWindowRowReadsAsOne()

    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletPortraitKeepsAutoWithTheWindows() =
        assertTheWindowRowReadsAsOne()

    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletLandscapeKeepsAutoWithTheWindows() =
        assertTheWindowRowReadsAsOne()

    /**
     * Auto lives with the window sizes it picks between, and every chip on a line
     * shares its top with the rest of that line.
     *
     * Auto used to be a fixed chip beside Pin, which could put the one button that
     * picks a window on a different row from the windows. Now it is the first of
     * the group's values: it shares the values box with the sizes, and it shares
     * an actual line with the first of them. Later sizes may wrap onto further
     * lines where the panel has room to show them all - that is [GroupValues]
     * wrapping, not Auto drifting - so sharing a line is only required of chips
     * the flow put on the same line.
     *
     * Sharing a line is asserted as "the vertical spans overlap" rather than as an
     * equality of tops, because that is the property a reader actually has - two
     * controls either look like one row or they do not - and it stays true if a
     * chip on the line is ever a different height. The tops are then asserted
     * equal, which is the Pin fault of old: two chips on one line with one of
     * them visibly riding high.
     */
    private fun assertTheWindowRowReadsAsOne() {
        compose.setContent { Sps30Theme(useDarkTheme = true) { Dashboard() } }
        compose.waitForIdle()

        val values = compose.onNodeWithTag(controlGroupValuesTag(WINDOW_GROUP_LABEL))
            .getUnclippedBoundsInRoot()
        val auto = compose.onNodeWithText("Auto").bounds()
        assertTrue(
            "Auto is at ${auto.pretty()}, outside the window values box at " +
                "${values.pretty()}, so it is not with the window sizes it picks between",
            auto.top >= values.top - TOLERANCE && auto.bottom <= values.bottom + TOLERANCE,
        )
        val first = compose.onNodeWithText("1m").bounds()
        assertTrue(
            "Auto is at ${auto.pretty()} and the first window size at ${first.pretty()}, " +
                "so Auto is on a row of its own rather than leading the sizes",
            auto.sharesALineWith(first),
        )
        for (preset in listOf("1m", "5m", "30m", "1h")) {
            val chip = compose.onNodeWithText(preset).bounds()
            assertTrue(
                "the \"$preset\" window at ${chip.pretty()} is outside the values box at " +
                    "${values.pretty()}",
                chip.top >= values.top - TOLERANCE && chip.bottom <= values.bottom + TOLERANCE,
            )
            if (!auto.sharesALineWith(chip)) continue
            assertEquals(
                "Auto and \"$preset\" share a line but not a top, so one of them sits high",
                auto.top.value.toDouble(),
                chip.top.value.toDouble(),
                TOLERANCE.value.toDouble(),
            )
        }

        // Pin: the whole of the second fault. It shares the label row with Unpin,
        // and two chips on one line must sit on it together, at the same height.
        val pin = compose.onNodeWithText("Pin").bounds()
        for (neighbour in listOf("Unpin", "Auto")) {
            val chip = compose.onNodeWithText(neighbour).bounds()
            if (!pin.sharesALineWith(chip)) continue
            assertEquals(
                "Pin is at ${pin.pretty()} and \"$neighbour\" at ${chip.pretty()}: they are on " +
                    "the same line and Pin does not sit on it",
                chip.top.value.toDouble(),
                pin.top.value.toDouble(),
                TOLERANCE.value.toDouble(),
            )
            assertEquals(
                "Pin is ${(pin.bottom - pin.top).value}dp tall and \"$neighbour\" " +
                    "${(chip.bottom - chip.top).value}dp, so they cannot line up for long",
                (chip.bottom - chip.top).value.toDouble(),
                (pin.bottom - pin.top).value.toDouble(),
                TOLERANCE.value.toDouble(),
            )
        }
    }

    // --- the overflow mark ---------------------------------------------------

    /**
     * The mark is there when there is more, and gone when there is not.
     *
     * A width sweep rather than one width: the fault the fade had was not that it
     * was drawn at the wrong moment but that it said nothing, and the replacement
     * must not trade that for a mark that lies. So this walks from a column too
     * narrow for two chips up to one with room for all six, and requires the mark
     * to be present for exactly as long as the strip really has something hidden.
     */
    @Test fun theMarkIsThereInAColumnTooNarrowForTwoChips() = assertTheMarkTellsTheTruthAt(80.dp)

    @Test fun theMarkIsThereInAVeryNarrowColumn() = assertTheMarkTellsTheTruthAt(120.dp)

    @Test fun theMarkIsThereInTheNarrowestColumnTheAppHas() =
        assertTheMarkTellsTheTruthAt(CONTROL_COLUMN_NARROW)

    @Test fun theMarkIsThereInAMiddlingColumn() = assertTheMarkTellsTheTruthAt(300.dp)

    @Test fun theMarkIsGoneWhenEveryChipFits() = assertTheMarkTellsTheTruthAt(600.dp)

    /**
     * One width, and the mark agreeing with the geometry.
     *
     * A separate test per width rather than a loop, because a compose rule takes
     * one `setContent` per test - and because a sweep that fails should say which
     * width it failed at without being read for it.
     */
    private fun assertTheMarkTellsTheTruthAt(width: Dp) {
        compose.setContentAtWidth(width) { Strip(Modifier.testTag(STRIP)) }
        compose.waitForIdle()
        // The truth the mark is supposed to be reporting, measured rather than
        // assumed: the last chip's own right edge against the strip's. Read
        // unclipped, so a chip pushed off the end still says where it went.
        val strip = compose.onNodeWithTag(STRIP).getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText(STRIP_LABELS.last()).bounds()
        val fits = last.right <= strip.right + TOLERANCE
        assertEquals(
            "at ${width.value}dp the last chip ends at ${last.right.value}dp in a strip " +
                "ending at ${strip.right.value}dp, and the strip draws " +
                "${marks(forward = true)} forward mark(s)",
            if (fits) 0 else 1,
            marks(forward = true),
        )
        assertEquals(
            "at ${width.value}dp nothing has been scrolled, so there is nothing off the " +
                "left and no mark belongs there",
            0,
            marks(forward = false),
        )
    }

    /**
     * Scrolled to the end, the mark is on the other side.
     *
     * The chips that went off the left are exactly as hidden as the ones that were
     * off the right, so a strip that only ever marks one side is a strip that stops
     * telling the truth the moment it is used.
     */
    @Test fun theMarkFollowsTheChipsThatAreHidden() {
        compose.setContentAtWidth(120.dp) { Strip() }
        compose.waitForIdle()
        assertEquals("nothing is off the left yet", 0, marks(forward = false))
        assertEquals("the far chips are off the right", 1, marks(forward = true))

        repeat(6) {
            compose.onNode(hasScrollAction()).performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        assertEquals(
            "the strip is scrolled to its end and still claims there is more to the right",
            0,
            marks(forward = true),
        )
        assertEquals(
            "the strip is scrolled to its end and says nothing about the chips now off its left",
            1,
            marks(forward = false),
        )
    }

    /** The mark is a sign and not a control: there is nothing on it to press. */
    @Test fun theMarkIsNotSomethingToPress() {
        compose.setContentAtWidth(120.dp) { Strip() }
        compose.waitForIdle()
        val mark = compose.onNode(hasContentDescription(moreChipsThisWay(STRIP_WHAT, true)))
        val actions = mark.fetchSemanticsNode().config.toString()
        assertTrue(
            "the overflow mark offers $actions - a cue the user can act on invites the " +
                "dragging a scroll bar would have invited",
            !actions.contains("OnClick") && !actions.contains("ScrollBy"),
        )
    }

    // --- Pin, on its own, against a chip -------------------------------------

    /**
     * The Pin chip lines up with a `FilterChip` wherever the two are put together.
     *
     * Asserted against the pair directly and not only through the dashboard,
     * because the fix belongs to the chip rather than to the one place it is used:
     * the next control added beside it has to line up too, and nobody adding one
     * will think to check.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Test fun thePinChipStandsAtTheSameHeightAsAChip() {
        compose.setContentAtWidth(CONTROL_COLUMN_NARROW) {
            ControlChips {
                LongPressChip(selected = false, label = "Pin", onClick = {}, onLongClick = {})
                FilterChip(false, {}, { Text("Auto") })
            }
        }
        val pin = compose.onNodeWithText("Pin").bounds()
        val chip = compose.onNodeWithText("Auto").bounds()
        assertEquals(
            "Pin is at ${pin.pretty()} and the chip beside it at ${chip.pretty()}",
            chip.top.value.toDouble(),
            pin.top.value.toDouble(),
            TOLERANCE.value.toDouble(),
        )
    }

    // --- the harness ---------------------------------------------------------

    private val TOLERANCE = 0.5.dp

    /** Six chips: more than any width under test but the widest can show. */
    private val STRIP_LABELS = listOf("10s", "30s", "1m", "5m", "30m", "12h")

    private val STRIP_WHAT = "window sizes"

    private val STRIP = "scrolling-chips"

    @Composable
    private fun Strip(modifier: Modifier = Modifier) =
        ScrollingChips(what = STRIP_WHAT, modifier = modifier) {
            for (label in STRIP_LABELS) FilterChip(false, {}, { Text(label) })
        }

    private fun marks(forward: Boolean): Int =
        compose.onAllNodes(hasContentDescription(moreChipsThisWay(STRIP_WHAT, forward)))
            .fetchSemanticsNodes().size

    private fun SemanticsNodeInteraction.bounds(): DpRect = getUnclippedBoundsInRoot()

    /**
     * True when two controls read as being on the same row: their vertical spans
     * overlap. Deliberately not [overlaps], which wants both axes and so is false
     * for any two things side by side - which is every pair on a row.
     */
    private fun DpRect.sharesALineWith(other: DpRect): Boolean =
        top < other.bottom && other.top < bottom

    /**
     * The real dashboard in its fullest state, branching the way [DashboardScreen]
     * branches - the construction `ControlLayoutTest` uses.
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

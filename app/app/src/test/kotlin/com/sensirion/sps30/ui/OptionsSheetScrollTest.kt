package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The options menu has to be reachable all the way down, on every tab, on every
 * screen the captain uses.
 *
 * ## The fault this guards
 *
 * The tabbed menu shipped with a `verticalScroll` on its body and did not scroll.
 * On the tablet in landscape `uiautomator` found no scrollable container in the
 * sheet at all, four swipes from four places moved nothing, and the Sensor tab's
 * guard plate ran off the bottom of the screen - its last line readable, its
 * bottom border and padding below the edge and unreachable. A control that cannot
 * be reached is the same as a control that does not exist, so that is a menu with
 * a missing lower half, on the one tab that can pause and clean the instrument.
 *
 * ## What is asserted, and why it is these things
 *
 * The mechanism is that a bottom sheet asks its content how tall it would like to
 * be and then places it at that height: content that measures taller than the
 * window is placed with the excess *below the screen*, and the scrolling column
 * never learns it is short because as far as it was told, everything fitted. So
 * the two halves of the property are tested separately.
 *
 *  * [theSheetIsNeverTallerThanTheWindow] offers the sheet far more height than
 *    the window has, the way a full-screen dialog window does, and requires it to
 *    refuse. That is the half that fails if the fix is taken out.
 *  * The per-geometry tests then require that after scrolling to the end, nothing
 *    is left below the visible area and the last control has real clearance from
 *    the bottom edge.
 *  * [assertNothingScrollsWhileHeightIsGoingSpare] then requires that any scrolling
 *    left is the content's fault and not the frame's: a tab may overflow, but only
 *    once the sheet has taken all the window there was to take.
 *  * [aTallEnoughWindowNeedsNoScrollingAtAll] is the same property at a synthetic
 *    shape with room for every tab whole, where the answer is no scrolling at all.
 *
 * Both run against the real [OptionsSheetBody] with the real tab content, at four
 * screen shapes: the Tab S7+ in both orientations at the density the captain runs,
 * and a phone in both, because the app is about to go on an S24 where less fits.
 *
 * Slow for the reason in `ui/LayoutTest.kt`: this renders Compose for real under
 * Robolectric in native graphics mode, which is what makes a `dp` mean what it
 * means on the tablet, and what makes it cost seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class OptionsSheetScrollTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The widest a `ModalBottomSheet` lets itself get - `BottomSheetDefaults
     * .SheetMaxWidth`. On the tablet the sheet is this wide and not the screen,
     * and the width is what decides how many lines the paragraphs take, so the
     * heights below are only the app's heights if this is honoured.
     */
    private val sheetWidth = 640.dp

    /** Space that must be left under the last control of a tab. */
    private val requiredClearance = 20.dp

    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun theSheetIsNeverTallerThanTheWindow() {
        // The tablet in landscape: the shape the fault was found on, and the
        // tightest of the four - the Sensor tab very nearly fills it.
        withBody(offerUnlimitedHeight = true) { tab ->
            val window = windowHeightPx()
            val height = measuredBodyHeight
            assertTrue(
                "the $tab tab laid the sheet out ${height}px tall in a ${window}px " +
                    "window: a bottom sheet is placed at the height it measures, so " +
                    "${height - window}px of it is below the screen and no amount of " +
                    "scrolling brings it back",
                height <= window,
            )
        }
    }

    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletInLandscapeHoldsStill() =
        assertTheSheetDoesNotMoveBetweenTabs()

    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletInPortraitHoldsStill() =
        assertTheSheetDoesNotMoveBetweenTabs()

    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phoneInPortraitHoldsStill() =
        assertTheSheetDoesNotMoveBetweenTabs()

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneInLandscapeHoldsStill() =
        assertTheSheetDoesNotMoveBetweenTabs()

    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletInLandscapeUsesTheHeightItHas() =
        assertNothingScrollsWhileHeightIsGoingSpare()

    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletInPortraitUsesTheHeightItHas() =
        assertNothingScrollsWhileHeightIsGoingSpare()

    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phoneInPortraitUsesTheHeightItHas() =
        assertNothingScrollsWhileHeightIsGoingSpare()

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneInLandscapeUsesTheHeightItHas() =
        assertNothingScrollsWhileHeightIsGoingSpare()

    /**
     * A window with room for every tab whole, where the answer is no scrolling.
     *
     * This used to run at [TABLET_PORTRAIT], the case the captain reported - a
     * tablet held upright, where the old fixed 640dp frame left 853dp of screen
     * unused above a menu he still had to scroll. Display then outgrew that
     * screen: the gap-marking switch (2026-08-07) put its body past the 1445dp a
     * Tab S7+ upright can give, so on every real screen the window now wins and
     * the tab scrolls by exactly the shortfall - which `tabletInPortraitUsesThe
     * HeightItHas` is what polices.
     *
     * The height here is therefore synthetic, comfortably taller than any screen
     * the app runs on, and this test keeps the one duty the real screens no
     * longer can: it is the guard on `OPTIONS_SHEET_MAX_HEIGHT`. A tab that
     * grows past the ceiling fails here rather than quietly reintroducing
     * scrolling into windows that had the height to spare.
     */
    @Test @Config(qualifiers = "w934dp-h1700dp-port") fun aTallEnoughWindowNeedsNoScrollingAtAll() =
        withBody { tab ->
            val overflow = scrollOverflowPx()
            assertTrue(
                "the $tab tab still scrolls ${overflow}px in a window with room for " +
                    "the whole of it, so OPTIONS_SHEET_MAX_HEIGHT is below what the " +
                    "tab now measures",
                overflow <= 0f,
            )
        }

    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletInLandscapeReachesEveryBottom() =
        assertEveryTabReachesItsBottom()

    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletInPortraitReachesEveryBottom() =
        assertEveryTabReachesItsBottom()

    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phoneInPortraitReachesEveryBottom() =
        assertEveryTabReachesItsBottom()

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneInLandscapeReachesEveryBottom() =
        assertEveryTabReachesItsBottom()

    /**
     * Every tab lays the sheet out at exactly the same height, so nothing moves.
     *
     * A bottom sheet is bottom-anchored and placed at the height it measures, so
     * its height *is* the position of everything above the fold: the top edge,
     * the "Options" header, and the tab strip the finger is already aiming at.
     * The menu used to measure whatever the tab happened to need - 587dp on Watch
     * against 1060dp on Display, on a tablet in portrait - so switching tabs threw
     * the strip up or down by most of a screen, and the second tap of two landed
     * somewhere the first tap had not aimed.
     *
     * Height is asserted rather than screen position because the position is
     * derived from it, and because a height read under a test parent is the real
     * measurement while a position is an artefact of that parent.
     *
     * Exact equality, not a tolerance: any drift here is content leaking back
     * into the frame, and a few dp of it is the same bug caught early.
     */
    private fun assertTheSheetDoesNotMoveBetweenTabs() {
        // Measured the way the user meets it: bottom-anchored and free to size
        // itself, which is the arrangement in which a taller tab would push
        // everything above it up the screen.
        val heights = mutableMapOf<OptionsTab, Int>()
        val strips = mutableMapOf<OptionsTab, Float>()
        withBody { tab ->
            heights[tab] = measuredBodyHeight
            strips[tab] = tabStripTop()
        }

        val distinctHeights = heights.values.toSet()
        assertTrue(
            "the tabs lay the sheet out at ${distinctHeights.size} different heights, so " +
                "switching tabs moves the whole menu: " +
                heights.entries.joinToString { "${it.key}=${it.value}px" },
            distinctHeights.size == 1,
        )

        // The same property said as the user meets it. The strip is what the
        // finger is on when it switches tabs, so it is the one thing that must
        // not have moved by the time the next tap lands.
        val distinctStrips = strips.values.toSet()
        assertTrue(
            "the tab strip sits at ${distinctStrips.size} different heights across the " +
                "tabs, so the second of two taps lands somewhere the first did not aim: " +
                strips.entries.joinToString { "${it.key}=${it.value}px" },
            distinctStrips.size == 1,
        )
    }

    /**
     * Nobody is asked to scroll while there is screen going unused above the menu.
     *
     * The complaint this answers was not "it scrolls" - a phone cannot hold the
     * Display tab whole and never will - it was scrolling *and* a band of empty
     * window over the top of it, which buys the user nothing. So the property is
     * conditional: a tab may have scroll range, but only once the sheet has taken
     * everything the window had to give bar the [SCRIM_STRIP] that keeps the tap
     * outside it possible.
     *
     * Stated per tab, because the frame is one height for all six and it is the
     * tallest of them that decides whether anyone has to scroll.
     */
    private fun assertNothingScrollsWhileHeightIsGoingSpare() = withBody { tab ->
        val overflow = scrollOverflowPx()
        if (overflow <= 0f) return@withBody
        val spare = windowHeightPx() - measuredBodyHeight
        assertTrue(
            "the $tab tab scrolls ${overflow}px while ${spare}px of window sits unused " +
                "above the sheet, which is more than the ${px(SCRIM_STRIP)}px the scrim " +
                "needs - so the scrolling is the frame's fault and not the content's",
            spare <= px(SCRIM_STRIP) + 1f,
        )
    }

    /** How much of the showing tab is below the fold, in pixels. */
    private fun scrollOverflowPx(): Float {
        val scroll = scrollNode() ?: error("the sheet has no scrollable container at all")
        return scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    }

    /** Where the tab strip's top edge sits, taken from the first tab's label. */
    private fun tabStripTop(): Float = compose
        .onNodeWithText(OptionsTab.entries.first().label, useUnmergedTree = true)
        .fetchSemanticsNode()
        .positionInRoot.y

    /**
     * Scroll each tab to its end and require that nothing is left underneath.
     *
     * The bounds read here are the unclipped ones. A node past the bottom of the
     * scrolling viewport reports a clipped rectangle squashed against the edge,
     * which is exactly the shape of "it fits" - so the clipped bounds would agree
     * with the bug.
     */
    private fun assertEveryTabReachesItsBottom() = withBody { tab ->
        val scroll = scrollNode()
            ?: error("the $tab tab has no scrollable container: nothing below the fold can be reached")
        val viewportBottom = scroll.positionInRoot.y + scroll.size.height
        scrollToEnd(scroll)

        // The lowest thing *inside* the scroll, not the scroll container itself:
        // its own bottom is the viewport edge by definition and would always
        // report a perfect fit.
        val lowest = scrollNode()!!.children.maxOf(::lowestUnclippedBottom)
        assertTrue(
            "the $tab tab still has content ${lowest - viewportBottom}px below the " +
                "bottom of the menu after scrolling as far as it goes",
            lowest <= viewportBottom,
        )
        val clearancePx = px(requiredClearance)
        assertTrue(
            "the $tab tab ends ${viewportBottom - lowest}px above the bottom edge, " +
                "which is less than the ${clearancePx}px a guard plate's border needs " +
                "to read as an ending rather than a cut",
            viewportBottom - lowest >= clearancePx,
        )
    }

    // --- the harness ---------------------------------------------------------

    /**
     * Render the real sheet body, once per tab, and run [check] on each.
     *
     * [offerUnlimitedHeight] wraps it in a parent that hands down far more height
     * than the window has. That is not a contrivance: a modal bottom sheet lives
     * in a window of its own that covers the whole display, system bars included,
     * so the height offered to the sheet is routinely more than the app's own.
     */
    private fun withBody(
        offerUnlimitedHeight: Boolean = false,
        check: (OptionsTab) -> Unit,
    ) {
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val viewModel = DashboardViewModel(app)
        val noParentScroll = object : NestedScrollConnection {}
        var tab by mutableStateOf(OptionsTab.entries.first())
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                val body = @Composable {
                    OptionsSheetBody(
                        state = DashboardUiState(),
                        viewModel = viewModel,
                        tab = tab,
                        onSelectTab = {},
                        onSetY = {},
                        onToggleBackground = {},
                        onExportCsv = {},
                        onSavePng = {},
                        onSelectMode = {},
                        onDismiss = {},
                        swallowLeftoverScroll = noParentScroll,
                    )
                }
                // A sheet is bottom-aligned and no wider than its own maximum.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(sheetWidth)) {
                        Measured(relax = offerUnlimitedHeight) { body() }
                    }
                }
            }
        }
        for (entry in OptionsTab.entries) {
            tab = entry
            compose.waitForIdle()
            check(entry)
        }
    }

    /**
     * Records what the sheet body measured itself at, and optionally lies to it
     * about how much height there is.
     *
     * With [relax] on it offers three windows' worth and keeps its own full size,
     * which is the full-screen dialog window a `ModalBottomSheet` really lives in
     * and the condition [theSheetIsNeverTallerThanTheWindow] needs. With it off it
     * passes the real constraints through and wraps the body, so the enclosing
     * bottom-aligned box places the sheet exactly where the sheet would sit.
     */
    @Composable
    private fun Measured(relax: Boolean, content: @Composable () -> Unit) {
        val windowPx = with(LocalDensity.current) {
            LocalConfiguration.current.screenHeightDp.dp.roundToPx()
        }
        Layout(content = { content() }) { measurables, constraints ->
            val offered =
                if (relax) constraints.copy(minHeight = 0, maxHeight = windowPx * 3)
                else constraints
            val placeables = measurables.map { it.measure(offered) }
            val height = placeables.maxOf { it.height }
            measuredBodyHeight = height
            val outWidth = if (relax) constraints.maxWidth else placeables.maxOf { it.width }
            val outHeight = if (relax) constraints.maxHeight else height
            layout(outWidth, outHeight) { placeables.forEach { it.place(0, 0) } }
        }
    }

    /** What [MoreHeightThanTheWindow] last saw the sheet body measure itself at. */
    private var measuredBodyHeight = 0

    private fun px(dp: androidx.compose.ui.unit.Dp): Float =
        dp.value * org.robolectric.RuntimeEnvironment.getApplication()
            .resources.displayMetrics.density

    private fun windowHeightPx(): Float = px(
        org.robolectric.RuntimeEnvironment.getApplication()
            .resources.configuration.screenHeightDp.dp,
    )

    private fun scrollNode(): SemanticsNode? {
        var found: SemanticsNode? = null
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                found == null
            ) {
                found = node
            }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return found
    }

    /** Drive the scroll container to its far end through its own semantics action. */
    private fun scrollToEnd(node: SemanticsNode) {
        val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
        val remaining = range.maxValue() - range.value()
        if (remaining <= 0f) return
        node.config[SemanticsActions.ScrollBy].action?.invoke(0f, remaining)
        compose.waitForIdle()
    }

    private fun lowestUnclippedBottom(node: SemanticsNode): Float {
        var lowest = node.positionInRoot.y + node.size.height
        for (child in node.children) lowest = maxOf(lowest, lowestUnclippedBottom(child))
        return lowest
    }

}

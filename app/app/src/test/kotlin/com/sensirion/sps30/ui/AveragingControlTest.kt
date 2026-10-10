package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensirion.sps30.Slow
import com.sensirion.sps30.config.ConfigStore
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.data.YAxisControl
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
 * The Average button's whole contract, driven through the real dashboard.
 *
 * ## What the control promises
 *
 * The captain's refinement, exactly: the button is a toggle whose plain tap
 * engages averaging with an automatic window and whose second tap turns it
 * off; press-and-hold opens the size chooser, engaging averaging first if it
 * was off, and **never** turns averaging off. The chooser is not a mode - it
 * persists across selections so sizes can be compared against the live graph,
 * closes only when a touch lands away from it, and closing changes nothing.
 *
 * The behaviour half runs against the real [MonitorSession] the way
 * [DashboardControlsPressTest] does, because most of these promises are about
 * what reaches the model: a chooser that stayed open but stopped applying
 * sizes would pass any purely visual assertion.
 *
 * The geometry half pins the one absolute rule: the chooser may overlap any
 * control but must never cover the plot, in either orientation - watching the
 * graph respond is the entire point of trying sizes. It is asserted at the
 * captain's four device shapes against the measured plot surface, not against
 * the layout's good intentions.
 *
 * Slow for the reason in `LayoutTest.kt`: real Compose under Robolectric in
 * native graphics mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class AveragingControlTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    // --- the toggle ----------------------------------------------------------

    /** A plain tap from off engages averaging with the automatic window. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aTapEngagesAutomaticAveraging() {
        liveDashboard()
        button().performClick()
        awaitModel("tapping Average did not engage automatic averaging") {
            liveChart()?.autoInterval == true
        }
    }

    /** The second tap turns averaging off - back to every reading drawn. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aSecondTapTurnsAveragingOff() {
        liveDashboard()
        button().performClick()
        awaitModel("the first tap did not engage averaging") {
            liveChart()?.autoInterval == true
        }
        button().performClick()
        awaitModel("the second tap did not turn averaging off") {
            liveChart()?.let { !it.autoInterval && it.bucketSeconds == null } == true
        }
    }

    /**
     * A tap after a hand-picked size returns to *automatic*, not to the size.
     *
     * The tap is the "just average it sensibly" gesture and the hold is the
     * only route to a manual size; a tap that restored the pinned size would
     * blur the two into each other.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aTapAfterAManualSizeReturnsToAutomatic() {
        liveDashboard()
        button().performTouchInput { longClick() }
        compose.waitForIdle()
        chooserChip("30s").performClick()
        awaitModel("the chooser's 30s size never reached the model") {
            liveChart()?.bucketSeconds == 30.0
        }
        button().performClick()
        awaitModel("the tap on an engaged button did not turn averaging off") {
            liveChart()?.let { !it.autoInterval && it.bucketSeconds == null } == true
        }
        button().performClick()
        awaitModel("the tap re-engaged the manual size rather than automatic") {
            liveChart()?.let { it.autoInterval && it.bucketSeconds != 30.0 } == true
        }
    }

    // --- the hold ------------------------------------------------------------

    /** From off, a hold opens the chooser and engages averaging on the way. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aHoldOpensTheChooserAndEngages() {
        liveDashboard()
        button().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertExists()
        awaitModel("the hold opened the chooser without engaging averaging") {
            liveChart()?.autoInterval == true
        }
    }

    /**
     * A hold while engaged opens the chooser again and does not turn
     * averaging off - only the plain tap does. This is the rule that keeps
     * the two gestures unambiguous, and it is the point of the refinement.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aHoldWhileEngagedDoesNotTurnAveragingOff() {
        liveDashboard()
        button().performTouchInput { longClick() }
        compose.waitForIdle()
        chooserChip("30s").performClick()
        awaitModel("the chooser's 30s size never reached the model") {
            liveChart()?.bucketSeconds == 30.0
        }
        tapAway()
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertDoesNotExist()
        button().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertExists()
        awaitModel("the second hold changed the averaging state") {
            liveChart()?.let { !it.autoInterval && it.bucketSeconds == 30.0 } == true
        }
    }

    // --- the chooser is not a mode -------------------------------------------

    /** Each selection applies at once and the chooser stays for the next try. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun theChooserSurvivesRepeatedSelections() {
        liveDashboard()
        button().performTouchInput { longClick() }
        compose.waitForIdle()
        chooserChip("30s").performClick()
        awaitModel("the 30s selection never reached the model") {
            liveChart()?.bucketSeconds == 30.0
        }
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertExists()
        chooserChip("10s").performClick()
        awaitModel("the 10s selection never reached the model") {
            liveChart()?.bucketSeconds == 10.0
        }
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertExists()
    }

    /** Tapping away closes the chooser and whatever was last picked stays. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun tappingAwayClosesTheChooserAndKeepsTheChoice() {
        liveDashboard()
        button().performTouchInput { longClick() }
        compose.waitForIdle()
        chooserChip("10s").performClick()
        awaitModel("the 10s selection never reached the model") {
            liveChart()?.bucketSeconds == 10.0
        }
        tapAway()
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertDoesNotExist()
        awaitModel("closing the chooser disturbed the picked size") {
            liveChart()?.let { !it.autoInterval && it.bucketSeconds == 10.0 } == true
        }
    }

    /** Opening and tapping away without choosing is a complete no-op. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun openingAndTappingAwayChangesNothing() {
        liveDashboard()
        button().performClick()
        awaitModel("the tap did not engage automatic averaging") {
            liveChart()?.autoInterval == true
        }
        button().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertExists()
        tapAway()
        compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).assertDoesNotExist()
        awaitModel("an opened-and-abandoned chooser changed the averaging state") {
            liveChart()?.let { it.autoInterval && it.bucketSeconds == null } == true
        }
    }

    // --- the chooser never covers the plot, at the four shapes ---------------

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theChooserStaysOffThePlotOnAPhoneInPortrait() =
        assertTheChooserStaysOffThePlot(wide = false)

    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun theChooserStaysOffThePlotOnATabletInPortrait() =
        assertTheChooserStaysOffThePlot(wide = false)

    @Test @Config(qualifiers = PHONE_LANDSCAPE)
    fun theChooserStaysOffThePlotOnAPhoneInLandscape() =
        assertTheChooserStaysOffThePlot(wide = true)

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun theChooserStaysOffThePlotOnATabletInLandscape() =
        assertTheChooserStaysOffThePlot(wide = true)

    /**
     * The chooser inside its host, and the host's world ending at the plot.
     *
     * Two assertions on purpose. The containment one pins the structural
     * guarantee [AveragingChooserHost] exists for - the drawer cannot leave
     * the box the controls live in. The overlap one is the captain's rule
     * measured directly against the plot surface the dashboard reported,
     * so it stays true even if the host is ever moved somewhere careless.
     */
    private fun assertTheChooserStaysOffThePlot(wide: Boolean) {
        fixedDashboard()
        button().inView().performTouchInput { longClick() }
        compose.waitForIdle()

        val chooser = compose.onNodeWithTag(AVERAGING_CHOOSER_TAG).getUnclippedBoundsInRoot()
        val host = compose.onNodeWithTag(AVERAGING_HOST_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "the chooser at ${chooser.pretty()} leaves its host at ${host.pretty()}, " +
                "so it is loose on a screen whose only other territory is the plot",
            chooser.left >= host.left - TOLERANCE && chooser.right <= host.right + TOLERANCE &&
                chooser.top >= host.top - TOLERANCE && chooser.bottom <= host.bottom + TOLERANCE,
        )

        assertTrue("the chart never reported a size", chartSize != IntSize.Zero)
        val plot = plotRect(wide)
        assertTrue(
            "the chooser at ${chooser.pretty()} covers the plot at ${plot.pretty()} - " +
                "the whole point of the drawer is watching the graph change under it",
            !chooser.overlaps(plot),
        )
    }

    /**
     * Where the plot surface is, in dp in root coordinates, reconstructed from
     * the size the dashboard reported through `onChartSize` and the layout's
     * fixed edges: the chart sits against the 12dp root padding - bottom of
     * the column when stacked, right of the row when side by side.
     */
    private fun plotRect(wide: Boolean): DpRect {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val w = (chartSize.width / density).dp
        val h = (chartSize.height / density).dp
        val pad = 12.dp
        return if (wide) {
            DpRect(root.right - pad - w, root.bottom - pad - h, root.right - pad, root.bottom - pad)
        } else {
            DpRect(root.left + pad, root.bottom - pad - h, root.left + pad + w, root.bottom - pad)
        }
    }

    // --- the harness ---------------------------------------------------------

    private val TOLERANCE = 0.5.dp

    private var chartSize = IntSize.Zero
    private var density = 1f

    /**
     * The button, by the word on it. Substring, because a hand-picked size
     * joins the label - "Average 30s" - and the button must stay findable
     * across its own honesty. Nothing else on the dashboard starts with a
     * capital "Average".
     */
    private fun button(): SemanticsNodeInteraction =
        compose.onNodeWithText(AVERAGE_LABEL, substring = true)

    /** A size inside the chooser - scoped, because "1m" is also a window preset. */
    private fun chooserChip(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(AVERAGING_CHOOSER_TAG)))

    /**
     * A touch on the top-left corner of the screen: the status banner, which
     * is as "away" as the dashboard has - not the chooser, not the button,
     * not the plot.
     */
    private fun tapAway() {
        compose.onRoot().performTouchInput { click(Offset(2f, 2f)) }
        compose.waitForIdle()
    }

    private fun liveChart() = MonitorSession.ui.value.chart

    /** Polled rather than slept on, for the reason [DashboardControlsPressTest] gives. */
    private fun awaitModel(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError(what)
    }

    private fun SemanticsNodeInteraction.inView(): SemanticsNodeInteraction = try {
        performScrollTo()
    } catch (notScrollable: AssertionError) {
        this
    }

    /**
     * The real dashboard over the real session, with the chart state flowing
     * back out of the model - the button decides its tap from the state it
     * shows, so a harness with a frozen chart would re-engage forever instead
     * of toggling. [MonitorSession] is process-wide, so the averaging state is
     * explicitly reset first rather than assumed fresh.
     */
    private fun liveDashboard() {
        ConfigStore.saveCurrent(app, AppConfig.default())
        val viewModel = DashboardViewModel(app)
        viewModel.updateConfig(AppConfig.default())
        viewModel.setPlotInterval(null)
        awaitModel("the session never reset to raw") {
            liveChart()?.let { !it.autoInterval && it.bucketSeconds == null } ?: true
        }
        compose.setContent {
            val live by viewModel.ui.collectAsStateWithLifecycle()
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    Dashboard(live, viewModel, live.chartRender(null))
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The dashboard with the fixed full chart the layout tests share, for the
     * geometry half - where nothing about the model matters and the plot has
     * to be a real drawn surface with a measured size.
     */
    private fun fixedDashboard() {
        val viewModel = DashboardViewModel(app)
        val render = chartRenderFor(ViewMode.PIN)
        val state = DashboardUiState(
            chart = render.data!!.copy(yControl = YAxisControl.OVERRIDDEN),
        )
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) { Dashboard(state, viewModel, render) }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Dashboard(
        state: DashboardUiState,
        viewModel: DashboardViewModel,
        render: ChartRender,
    ) {
        density = LocalDensity.current.density
        val touch = ChartTouchCallbacks(
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
        )
        if (sideBySide(LocalConfiguration.current)) {
            WideDashboard(
                Modifier, state, viewModel, render, touch,
                onChartSize = { chartSize = it },
                onTimeFlag = {}, onOpenOptions = {},
            )
        } else {
            StackedDashboard(
                Modifier, state, viewModel, render, touch,
                onChartSize = { chartSize = it },
                onTimeFlag = {}, onOpenOptions = {},
            )
        }
    }
}

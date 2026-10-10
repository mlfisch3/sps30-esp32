package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensirion.sps30.Slow
import com.sensirion.sps30.config.ConfigStore
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.PlottedSeries
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
 * The controls beside the plot must answer the finger.
 *
 * ## The fault this exists for
 *
 * The captain's tablet arrived with an empty plot, both readout tiles grey, and -
 * he reported - most of the panel dead: no way to change the window, and no way to
 * turn the series back on. The plot was empty because both series were hidden, and
 * the hidden state had been written to the stored config, so restarting the app
 * brought it straight back.
 *
 * Every layout test the app had asserts *where* a control is and *whether it fits*.
 * None of them pressed one. So this presses them, through the real dashboard with
 * the real session behind it - a closed loop, in which a press that fails to reach
 * the model is a press that changed nothing, which is what the captain saw.
 *
 * The presses are made at all four screen shapes because the panel reflows between
 * them ([PanelFit] folds the Cursor section into the chip cluster and puts the
 * compact shape in a scrolling column), and a container that had stopped answering
 * for its children would be dead at one shape and live at the other three.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class DashboardControlsPressTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    /** The config the session is actually holding, after a press has landed. */
    private fun liveConfig(): AppConfig = MonitorSession.ui.value.config

    /**
     * The model runs on a confined background dispatcher, so a press reaches it a
     * moment after the click returns. Polled rather than slept on a fixed delay: a
     * wrong guess either flakes or slows every test in the file.
     */
    private fun awaitModel(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError(what)
    }

    /**
     * Bring a control into view the way a finger would.
     *
     * A chip may sit in the one-line strip or the compact column, both of which
     * scroll - or in the wrapped shape, where every chip is already in view and
     * nothing around it scrolls at all. `performScrollTo` treats the second case
     * as an error, and here it is the layout doing exactly what it should; the
     * wrap layout tests are what hold "already in view" true.
     */
    private fun SemanticsNodeInteraction.inView(): SemanticsNodeInteraction = try {
        performScrollTo()
    } catch (notScrollable: AssertionError) {
        this
    }

    /**
     * The real dashboard, wired to the real session, at this screen shape.
     *
     * [DashboardScreen] itself is not used because it reaches for an activity's
     * permission launchers and starts the background service. This is the same
     * branch over the same two layouts, with the config coming back out of the
     * session so a press has somewhere real to show up.
     */
    private fun dashboardWith(config: AppConfig, graphLocked: Boolean = false) {
        ConfigStore.saveCurrent(app, config)
        val viewModel = DashboardViewModel(app)
        viewModel.updateConfig(config)
        awaitModel("the session never took the starting config") {
            liveConfig().visiblePm == config.visiblePm
        }
        compose.setContent {
            val live by viewModel.ui.collectAsStateWithLifecycle()
            // The config is the session's, so a press lands somewhere observable.
            // The chart is a fixed full one, because the panel is only as crowded
            // as its fullest state - a pinned view with an overridden y axis puts
            // Unpin and the y-axis pair in the cluster - and a press test against
            // the emptiest panel would pass while the real one, three chips later,
            // did not.
            val state = live.copy(
                chart = chartRenderFor(ViewMode.PIN).data!!
                    .copy(yControl = YAxisControl.OVERRIDDEN),
            )
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) { Dashboard(state, viewModel, graphLocked) }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Dashboard(
        state: DashboardUiState,
        viewModel: DashboardViewModel,
        graphLocked: Boolean,
    ) {
        val touch = ChartTouchCallbacks(
            inspected = null,
            onInspect = {},
            cursor = CursorControls(
                enabled = true, onToggleEnabled = {},
                follows = false, onToggleFollows = {},
            ),
            onGestureStart = {},
            onGestureEnd = {},

            graphLocked = graphLocked,
            onToggleGraphLock = {},
        )
        val render = state.chartRender(null)
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

    // --- the series toggles --------------------------------------------------

    /** The fault at the state his tablet was in: nothing plotted, tile is the way back. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aHiddenSeriesComesBackWhenItsTileIsPressed() = assertTilePressShowsTheSeries()

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun theTileAnswersOnATabletInLandscape() = assertTilePressShowsTheSeries()

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theTileAnswersOnAPhoneInPortrait() = assertTilePressShowsTheSeries()

    @Test @Config(qualifiers = PHONE_LANDSCAPE)
    fun theTileAnswersOnAPhoneInLandscape() = assertTilePressShowsTheSeries()

    private fun assertTilePressShowsTheSeries() {
        dashboardWith(AppConfig.default().copy(visiblePm = emptyList()))
        compose.onNodeWithText("PM1.0").performClick()
        awaitModel("pressing the PM1.0 tile did not turn the series back on") {
            "pm1_0" in liveConfig().visiblePm
        }
    }

    /** Off and on again, which is the round trip the button promises. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aSeriesTogglesOffAndBackOn() {
        dashboardWith(AppConfig.default())
        compose.onNodeWithText("PM2.5").performClick()
        awaitModel("pressing a shown tile did not hide the series") {
            "pm2_5" !in liveConfig().visiblePm
        }
        compose.onNodeWithText("PM2.5").performClick()
        awaitModel("pressing a hidden tile did not show the series again") {
            "pm2_5" in liveConfig().visiblePm
        }
    }

    // --- the window and average buttons --------------------------------------

    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun theWindowButtonsAnswerOnATabletInPortrait() = assertEveryWindowButtonAnswers()

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun theWindowButtonsAnswerOnATabletInLandscape() = assertEveryWindowButtonAnswers()

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theWindowButtonsAnswerOnAPhoneInPortrait() = assertEveryWindowButtonAnswers()

    @Test @Config(qualifiers = PHONE_LANDSCAPE)
    fun theWindowButtonsAnswerOnAPhoneInLandscape() = assertEveryWindowButtonAnswers()

    /**
     * Every preset, not just the first. Where the column keeps the one-line strip
     * they sit in something that scrolls, and a chip scrolled out of its viewport
     * is one shape of "visible but dead" - so each is brought into view before it
     * is pressed, exactly as a finger would have to. Where the panel has wrapped
     * them all into view instead, there is nothing to scroll and nothing to bring.
     */
    private fun assertEveryWindowButtonAnswers() {
        dashboardWith(AppConfig.default())
        for (preset in AppConfig.DEFAULT_PRESETS) {
            compose.onNodeWithText(preset.label).inView().performClick()
            awaitModel("the ${preset.label} window button did not change the window") {
                MonitorSession.ui.value.windowSeconds == preset.seconds
            }
        }
    }

    /**
     * The averaging control is a single button in the cluster now, and its
     * sizes live in the chooser behind a press-and-hold. This is the closed
     * loop for that route: hold, press a size, and the size must reach the
     * model. The button's own toggling and the chooser's lifecycle are
     * `AveragingControlTest`'s subject; this file's question is only whether
     * the presses land.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun theAveragingChooserButtonsAnswer() {
        dashboardWith(AppConfig.default())
        compose.onNodeWithText(AVERAGE_LABEL).inView().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(
            hasText("30s") and hasAnyAncestor(hasTestTag(AVERAGING_CHOOSER_TAG)),
        ).performClick()
        awaitModel("pressing the 30s size in the averaging chooser did nothing") {
            MonitorSession.ui.value.chart?.bucketSeconds == 30.0
        }
    }

    // --- the lock is about the plot and nothing else -------------------------

    /**
     * The graph lock gates the plot surface. A control in the panel is not the
     * plot, so a locked graph must leave every one of them live - its own release
     * included, or there is no way out of it.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aLockedGraphLeavesTheControlsLive() {
        dashboardWith(AppConfig.default().copy(visiblePm = emptyList()), graphLocked = true)
        compose.onNodeWithText("PM1.0").performClick()
        awaitModel("the graph lock swallowed the press on a readout tile") {
            "pm1_0" in liveConfig().visiblePm
        }
        compose.onNodeWithText("1m").inView().performClick()
        awaitModel("the graph lock swallowed the press on a window button") {
            MonitorSession.ui.value.windowSeconds == 60.0
        }
    }

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun aLockedGraphLeavesTheControlsLiveInLandscape() {
        dashboardWith(AppConfig.default(), graphLocked = true)
        compose.onNodeWithText("1m").inView().performClick()
        awaitModel("the graph lock swallowed a window press in the landscape column") {
            MonitorSession.ui.value.windowSeconds == 60.0
        }
    }

    // --- no state the user cannot get out of ---------------------------------

    /**
     * A stored config that leaves the plot empty must not be handed back as it was
     * written. This is the trap: the plot draws nothing, and the state that caused
     * it survives the restart the user reaches for.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aStoredConfigWithNoVisibleSeriesDoesNotOpenBlank() {
        ConfigStore.saveCurrent(app, AppConfig.default().copy(visiblePm = emptyList()))
        assertEquals(
            "a stored empty visible-series set was loaded as-is, so the app opens " +
                "on a plot that draws nothing",
            PlottedSeries.FIELDS,
            ConfigStore.loadCurrent(app).visiblePm,
        )
    }

    /**
     * The launch path, end to end: the app started on a config narrowed to nothing
     * by the upgrade must come up plotting.
     *
     * This is the route the captain's tablet actually took - not a tap, an install -
     * so it is asserted through the same call the view model makes on startup rather
     * than against [ConfigStore] alone.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun theAppLaunchedOnAnUpgradedConfigOpensPlotting() {
        // What a build that plotted all four wrote, for a user who had the two fine
        // cuts switched off. Nothing here is reachable in this build's own UI.
        ConfigStore.saveCurrent(app, AppConfig.default().copy(visiblePm = listOf("pm4_0", "pm10")))
        MonitorSession.applyConfig(ConfigStore.loadCurrent(app))
        awaitModel("the app opened on a plot that draws nothing") {
            liveConfig().visiblePm == PlottedSeries.FIELDS
        }
    }

    /**
     * And whatever route the app took to get there, the screen the user is looking
     * at has a way back to a plot with something on it.
     */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun aConfigWithNothingVisibleIsRecoverable() {
        dashboardWith(AppConfig.default().copy(visiblePm = emptyList()))
        if (liveConfig().visiblePm.isEmpty()) {
            for (field in PlottedSeries.FIELDS) {
                compose.onNodeWithText(SeriesColors.label.getValue(field)).performClick()
                compose.waitForIdle()
            }
        }
        awaitModel("a config with no visible series left the user with no way back") {
            liveConfig().visiblePm.isNotEmpty()
        }
        assertTrue(
            "the plot is still empty and there is nothing left to press",
            liveConfig().visiblePm.isNotEmpty(),
        )
    }
}

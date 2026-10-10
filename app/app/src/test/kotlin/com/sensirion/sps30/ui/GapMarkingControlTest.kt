package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.config.ConfigStore
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gap-marking switch, and the two promises around it.
 *
 * The first promise is the ordinary one for a Display control: it is on the
 * Display tab with the other plot settings, it is off until asked for, and
 * flipping it stores the choice on the spot - a switch that lights up and is
 * forgotten on the next launch is the failure `ConfigStore` is asserted against.
 *
 * The second is the one that must survive every future tidy-up: the switch is a
 * preference about ink, and the Data tab's account of the record is not. The
 * captain's standard for that panel is that an intelligent person must never be
 * misled about the state of their data, so its gap reporting is rendered here
 * under both positions of the switch and required to say exactly the same
 * things.
 *
 * The key to the patterns moved from the plot to the Help tab when the marking
 * became optional; the last test is what keeps it there.
 *
 * Slow for the reason in `ui/LayoutTest.kt`: real Compose under Robolectric in
 * native graphics mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class GapMarkingControlTest {

    @get:Rule
    val compose = createComposeRule()

    private val sheetMaxWidth = 640.dp

    /**
     * The switch on the "Mark gaps in the record" row.
     *
     * Every row's label and switch are siblings in the one scrolling column, so
     * a sibling matcher finds every switch on the tab. What pins a switch to its
     * row is geometry: the two share the row's vertical centre.
     */
    private fun markGapsSwitch(): androidx.compose.ui.test.SemanticsNodeInteraction {
        val label = compose.onNodeWithText("Mark gaps in the record").performScrollTo()
            .fetchSemanticsNode().boundsInRoot
        val mid = (label.top + label.bottom) / 2f
        val switches = compose.onAllNodes(isToggleable())
        val nodes = switches.fetchSemanticsNodes()
        val onTheSameRow = nodes.indices.minByOrNull {
            val b = nodes[it].boundsInRoot
            kotlin.math.abs((b.top + b.bottom) / 2f - mid)
        } ?: error("no toggleable anywhere on the tab")
        return switches[onTheSameRow]
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun `the switch sits on the Display tab, off until asked for, and stores the choice`() {
        val app = showSheet(OptionsTab.CUSTOMIZE)
        assertFalse(
            "the marking must default off",
            ConfigStore.loadCurrent(app).markGaps,
        )
        markGapsSwitch().performScrollTo().assertIsDisplayed().assertIsOff()

        // Asserted against what `ConfigStore` has stored rather than against the
        // screen: the sheet here is drawn from a static state, and storing the
        // choice is the whole of what the switch has to achieve.
        markGapsSwitch().performClick()
        compose.waitForIdle()
        assertTrue("flipping the switch was not stored", ConfigStore.loadCurrent(app).markGaps)
    }

    /**
     * The Data tab's account of the record, word for word, under both positions.
     *
     * Rendered rather than reasoned about: `RecordGapsRow` could grow a
     * `config.markGaps` branch tomorrow and every pure-function argument for its
     * independence would still hold. So the row is drawn with the marking off -
     * the default - and the full account of a hole the ring has eaten is
     * required on screen; then drawn again with it on, and required identical.
     */
    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun `the Data tab reports gaps identically whichever way the switch points`() {
        val hole = RecordGap(
            startEpoch = 1_754_300_000.0,
            endEpoch = 1_754_310_000.0,
            standing = GapStanding.Lost,
            expectedReadings = 10_000,
        )
        val report = RecordGapReport(gaps = listOf(hole), sweptAtMs = 1L)

        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val viewModel = DashboardViewModel(app)
        var marking by mutableStateOf(false)
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column {
                        RecordGapsRow(
                            DashboardUiState(config = AppConfig(markGaps = marking), gaps = report),
                            viewModel,
                        )
                    }
                }
            }
        }
        // The summary, the hole's clock times, and the verdict - the three
        // things an intelligent person must not be misled about - under both
        // positions of the switch.
        for (position in listOf(false, true)) {
            marking = position
            compose.waitForIdle()
            compose.onNodeWithText(gapSummary(report)).assertExists()
            compose.onNodeWithText(gapWhen(hole)).assertExists()
            compose.onNodeWithText(gapStandingLabel(hole), substring = true).assertExists()
        }
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun `the Help tab carries the key to all four patterns`() {
        showSheet(OptionsTab.HELP)
        compose.onNodeWithText("The plot's gap markings").performScrollTo().assertIsDisplayed()
        for (pattern in GapPattern.entries) {
            val entry = GAP_LEGEND.getValue(pattern)
            compose.onNodeWithText(entry.label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(entry.detail).performScrollTo().assertIsDisplayed()
        }
    }

    /** The real sheet body, opened on [tab]. Returns the application it ran in. */
    private fun showSheet(tab: OptionsTab): android.app.Application {
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val viewModel = DashboardViewModel(app)
        val noParentScroll = object : NestedScrollConnection {}
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().widthIn(max = sheetMaxWidth)) {
                        OptionsSheetBody(
                            state = DashboardUiState(config = AppConfig.default()),
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
                }
            }
        }
        return app
    }
}

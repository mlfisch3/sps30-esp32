package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sensirion.sps30.Slow
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.ui.theme.Sps30Theme
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
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
 * Tapping the battery icon must open the power panel, and the panel must tell
 * the truth: the current state's estimated draw with the rest of the board
 * included, what every other mode would cost, no runtime while nothing can
 * know one, and the word "estimate" where it belongs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class PowerPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    /** The reply the firmware sends for the board as it runs today: continuous,
     *  both radios up, no battery. Values from the generated vectors. */
    private fun boardToday() = ServerMessage.PowerEstimate(
        basis = Wire.POWER_BASIS_MODELLED,
        sourcesDatasheet = 2,
        sourcesMeasured = 0,
        sourcesEstimate = 12,
        profile = ServerMessage.AcquisitionProfile(
            mode = Wire.MODE_CONTINUOUS, intervalSeconds = 1.0, cycleSeconds = 900.0,
            warmupSeconds = 30.0, averageSeconds = 30.0,
        ),
        sleeps = false,
        totalMw = 994.4425647977101,
        bySubsystemMw = linkedMapOf(
            "sensor" to 275.0132275132275,
            "controller" to 273.9,
            "radio" to 445.5,
            "storage" to 0.029337284482758623,
        ),
        alternatives = listOf(
            ServerMessage.PowerAlternative(
                mode = Wire.MODE_CONTINUOUS, totalMw = 994.4425647977101,
            ),
            ServerMessage.PowerAlternative(
                mode = Wire.MODE_DUTY_CYCLE, cycleSeconds = 900.0, sleeps = false,
                totalMw = 758.7132601102103,
            ),
            ServerMessage.PowerAlternative(
                mode = Wire.MODE_DUTY_CYCLE, cycleSeconds = 86400.0, sleeps = false,
                totalMw = 737.6351028527794,
            ),
        ),
        runtimeStatus = Wire.POWER_RUNTIME_NO_CAPACITY,
    )

    private fun showingPanel(state: DashboardUiState) {
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                PowerPanelDialog(state = state, onDismiss = {})
            }
        }
    }

    @Test
    fun theCurrentStateIsPricedWholeBoardIncluded() {
        showingPanel(DashboardUiState(powerEstimate = boardToday()))
        // The headline: the mode it is in (exact - the alternatives row carries
        // "Continuous (now)") and the estimate for it ("994 mW" is also the
        // continuous row's figure, so count rather than expect one).
        compose.onNodeWithText("Continuous").assertExists()
        assertTrue(
            compose.onAllNodesWithText("994 mW", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // The rest of the board is in the bill, not just the sensor.
        compose.onNodeWithText("Controller 274 mW", substring = true).assertExists()
        compose.onNodeWithText("Radios 446 mW", substring = true).assertExists()
    }

    @Test
    fun everyAlternativeModeIsShownWithItsCost() {
        showingPanel(DashboardUiState(powerEstimate = boardToday()))
        compose.onNodeWithText("Low power, every 15 min", substring = true).assertExists()
        compose.onNodeWithText("759 mW", substring = true).assertExists()
        compose.onNodeWithText("Low power, every 24 h", substring = true).assertExists()
        compose.onNodeWithText("738 mW", substring = true).assertExists()
        // The row for the mode the board is in says so.
        compose.onNodeWithText("Continuous (now)", substring = true).assertExists()
    }

    @Test
    fun noBatteryMeansProseNotANumber() {
        showingPanel(DashboardUiState(powerEstimate = boardToday()))
        compose.onNodeWithText("No runtime is shown", substring = true).assertExists()
        compose.onNodeWithText("When a capacity is known", substring = true).assertExists()
    }

    @Test
    fun theEstimateSaysItIsOne() {
        showingPanel(DashboardUiState(powerEstimate = boardToday()))
        compose.onNodeWithText("not measurements", substring = true).assertExists()
        compose.onNodeWithText("none has been measured", substring = true).assertExists()
    }

    @Test
    fun aHostWithNoModelIsNamedRatherThanWaitedOn() {
        showingPanel(
            DashboardUiState(powerEstimateRefusal = PowerRefusal.HostDoesNotModelPower),
        )
        compose.onNodeWithText("does not report power estimates", substring = true)
            .assertExists()
    }

    @Test
    fun oldControllerFirmwareIsNamedAndTheFixWithIt() {
        // The captain's actual case: his controller, on firmware from before
        // the power model. The panel must not call his board a Raspberry Pi,
        // and must name the way out rather than read as a dead end.
        showingPanel(
            DashboardUiState(
                powerEstimateRefusal = PowerRefusal.FirmwarePredatesEstimates,
            ),
        )
        compose.onNodeWithText("firmware", substring = true).assertExists()
        compose.onNodeWithText("Updating the controller", substring = true).assertExists()
        compose.onAllNodesWithText("Raspberry Pi", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun aRefusalWithNoRuleIsShownInTheHostsOwnWords() {
        showingPanel(
            DashboardUiState(
                powerEstimateRefusal =
                    PowerRefusal.Unclassified("power model still warming up"),
            ),
        )
        compose.onNodeWithText("does not recognise", substring = true).assertExists()
        compose.onNodeWithText("power model still warming up", substring = true)
            .assertExists()
    }

    @Test
    fun disconnectedSaysThereIsNothingToAskYet() {
        // Nothing has been asked or answered, and nothing can be: the honest
        // words are "not connected", not a guess about any host.
        showingPanel(DashboardUiState())
        compose.onNodeWithText("Not connected", substring = true).assertExists()
    }

    @Test
    fun waitingForTheAnswerSaysSo() {
        showingPanel(
            DashboardUiState(
                connection = ConnectionState.Connected("192.168.1.226:8730"),
            ),
        )
        compose.onNodeWithText("Asking the controller", substring = true).assertExists()
    }

    // --- the tap itself -----------------------------------------------------

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun tappingTheBatteryIconInTheMenuAsksForThePanel() {
        // The readout moved off the main screen into the menu's Sensor tab, so
        // that is where the tap that opens the panel now lives.
        var opened = 0
        val viewModel = DashboardViewModel(app)
        val state = DashboardUiState(
            power = ServerMessage.PowerStatus(source = "usb"),
        )
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    OptionsSheetBody(
                        state = state,
                        viewModel = viewModel,
                        tab = OptionsTab.SENSOR,
                        onSelectTab = {},
                        onSetY = {},
                        onToggleBackground = {},
                        onExportCsv = {},
                        onSavePng = {},
                        onSelectMode = {},
                        onDismiss = {},
                        swallowLeftoverScroll = object : NestedScrollConnection {},
                        onOpenPowerPanel = { opened++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(POWER_PILL_DESC)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun thePillSaysWhatATapDoes() {
        // The description doubles as the accessibility label for an icon that
        // otherwise has no text at all on mains power; it has to name the tap.
        assertTrue(POWER_PILL_DESC.contains("Tap"))
        assertTrue(POWER_PILL_DESC.contains("estimated"))
    }
}

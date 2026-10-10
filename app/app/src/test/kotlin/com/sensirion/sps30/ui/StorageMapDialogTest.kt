package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * The storage-map popup: what it says in each of its states, and that opening
 * it is what asks the sensor for a map.
 *
 * The renderer's ink is [StorageMapRenderTest]'s half; this file holds the
 * words around it - a store still coming up must not render as an empty grid
 * (the most convincing wrong picture this dialog could draw), and a map shown
 * off a dead link must say it is the past.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(Slow::class)
class StorageMapDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val storageReady = ServerMessage.Storage(
        usedPages = 1200, totalPages = 4480, readings = 53000, sessions = 3,
        fraction = 0.27, warnAt = 0.85, warn = false, ready = true,
    )

    private fun mapOfKnownBoard() = ServerMessage.StorageMap(
        pageBytes = 512,
        pages = 20,
        sections = listOf(
            ServerMessage.MapSection(
                id = Wire.MAP_SECTION_RING, start = 0, pages = 12, head = 5,
                wraps = 7, states = List(12) { Wire.PAGE_STATE_DATA },
            ),
            ServerMessage.MapSection(
                id = Wire.MAP_SECTION_ALERT_BODIES, start = 16, pages = 4, head = 0,
                wraps = 0, states = List(4) { Wire.PAGE_STATE_EMPTY },
            ),
        ),
    )

    private var refreshes = 0

    private fun showing(state: DashboardUiState) {
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                StorageMapDialog(state, onRefresh = { refreshes++ }, onDismiss = {})
            }
        }
    }

    @Test
    fun openingTheDialogAsksTheSensorForAMap() {
        showing(DashboardUiState(storage = storageReady))
        compose.waitForIdle()
        assertTrue("opening the dialog must request a map", refreshes >= 1)
        // And until one arrives, the dialog says it is asking rather than
        // drawing anything.
        compose.onNodeWithText("Asking the sensor", substring = true).assertExists()
    }

    @Test
    fun aStoreStillComingUpIsSaidNotDrawn() {
        val notReady = storageReady.copy(ready = false)
        showing(DashboardUiState(storage = notReady, storageMap = mapOfKnownBoard()))
        compose.onNodeWithText("still bringing its storage up", substring = true).assertExists()
    }

    @Test
    fun theMapNamesItsSectionsAndTheWrap() {
        showing(
            DashboardUiState(
                storage = storageReady,
                storageMap = mapOfKnownBoard(),
                connection = ConnectionState.Connected("192.168.1.226:8730"),
            )
        )
        compose.onNodeWithText("Readings ring").assertExists()
        compose.onNodeWithText("Alert samples (reserved)").assertExists()
        // The number the map exists to show: which pass, and how far round.
        compose.onNodeWithText("pass 8 - overwriting, 41% of the way round").assertExists()
        // The legend, painted by the cells' own painter, named in its words.
        compose.onNodeWithText("holds readings").assertExists()
        compose.onNodeWithText("write position").assertExists()
        // Connected, so no staleness disclaimer.
        compose.onNodeWithText("As last seen", substring = true).assertDoesNotExist()
    }

    @Test
    fun aMapOffADeadLinkSaysItIsThePast() {
        showing(DashboardUiState(storage = storageReady, storageMap = mapOfKnownBoard()))
        compose.onNodeWithText("As last seen", substring = true).assertExists()
    }

    @Test
    fun theIconOpensTheMapWhenTapped() {
        var opened = 0
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                StorageMapIcon(fraction = 0.27, onClick = { opened++ })
            }
        }
        compose.onNodeWithContentDescription("Storage map").performClick()
        assertEquals(1, opened)
    }

    @Test @Config(qualifiers = PHONE_PORTRAIT)
    fun theMapIsReachableFromTheMenusDataTab() {
        // The icon moved off the main screen into the menu: the map is not
        // something the user needs to be checking often, and the Data tab is
        // where the record's questions live.
        var opened = 0
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    OptionsSheetBody(
                        state = DashboardUiState(storage = storageReady),
                        viewModel = DashboardViewModel(app),
                        tab = OptionsTab.DATA,
                        onSelectTab = {},
                        onSetY = {},
                        onToggleBackground = {},
                        onExportCsv = {},
                        onSavePng = {},
                        onSelectMode = {},
                        onDismiss = {},
                        swallowLeftoverScroll = object : NestedScrollConnection {},
                        onOpenStorageMap = { opened++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Storage map")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }
}

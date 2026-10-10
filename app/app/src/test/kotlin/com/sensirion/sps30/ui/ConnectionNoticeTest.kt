package com.sensirion.sps30.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A minute of waiting must not end in silence.
 *
 * The failure this guards against is not a wrong message, it is no message: the
 * user taps Bluetooth, the app looks for a minute, finds nothing, quietly keeps the
 * Wi-Fi link and says not a word. The banner cannot carry it - after a failed
 * switch the banner is describing the Wi-Fi connection, which is healthy - so the
 * explanation is its own dialog, in the app's own words rather than an error code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(Slow::class)
class ConnectionNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    private fun showing(message: String) {
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                ConnectionNoticeDialog(message, onDismiss = { dismissed++ })
            }
        }
    }

    @Test fun theFailedBluetoothAttemptIsExplainedOnScreen() {
        showing(searchAbandonedFor(ConnectionMode.BLUETOOTH, ConnectionMode.WIFI).notice)
        compose.onNodeWithText("No sensor found").assertExists()
        compose.onNodeWithText("No sensor answered over Bluetooth", substring = true).assertExists()
        // The reassurance is the other half of it: nothing was lost by the attempt.
        compose.onNodeWithText("still connected over Wi-Fi", substring = true).assertExists()
    }

    @Test fun itCanBePutAway() {
        showing(searchAbandonedFor(ConnectionMode.BLUETOOTH, null).notice)
        compose.onNodeWithText("OK").performClick()
        assertEquals("the notice is dismissible", 1, dismissed)
    }
}

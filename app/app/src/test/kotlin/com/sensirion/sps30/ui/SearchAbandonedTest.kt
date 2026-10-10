package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.net.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a connection the user asked for and could not have leaves behind.
 *
 * The behaviour being protected here is the one the captain singled out: choosing
 * Bluetooth does **not** drop the Wi-Fi link, and the working link is kept until a
 * Bluetooth connection has actually been made. That used to hold for thirty seconds
 * and then stop holding - the handover committed the switch anyway when it timed
 * out, so a Bluetooth choice with nothing to find ended with a good Wi-Fi
 * connection torn down and a radio scanning at nothing. Now a search that fails
 * changes nothing about the link that works.
 */
class SearchAbandonedTest {

    private fun bluetoothFailedWhileOnWifi() =
        searchAbandonedFor(ConnectionMode.BLUETOOTH, ConnectionMode.WIFI)

    @Test fun theWifiConnectionSurvivesAFailedBluetoothAttempt() {
        val verdict = bluetoothFailedWhileOnWifi()
        assertTrue("the link that works is not touched", verdict.keepsServingLink)
        assertEquals("and the app is left on it", ConnectionMode.WIFI, verdict.fallback)
    }

    @Test fun theModeThatFailedIsReleasedSoItCanBeTriedAgain() {
        // The selector greys out the current mode, so leaving the app nominally on
        // Bluetooth would leave its button dead - no way to ask again short of
        // switching twice. Releasing it is what makes a second try one tap, and it
        // is why no separate retry control was added.
        assertTrue(bluetoothFailedWhileOnWifi().fallback != ConnectionMode.BLUETOOTH)
    }

    @Test fun withNothingWorkingTheAppIsPlainlyDisconnected() {
        val verdict = searchAbandonedFor(ConnectionMode.BLUETOOTH, serving = null)
        assertFalse(verdict.keepsServingLink)
        assertEquals(ConnectionMode.NONE, verdict.fallback)
    }

    @Test fun theUserIsToldWhatHappenedInWords() {
        val notice = bluetoothFailedWhileOnWifi().notice
        assertTrue("says nothing answered: '$notice'", notice.contains("No sensor answered"))
        assertTrue("names what was tried", notice.contains("Bluetooth"))
        assertTrue("and that nothing was lost by it", notice.contains("still connected over Wi-Fi"))
        // The controls it names have to be the ones on screen, spelled the same way.
        assertTrue(notice.contains("Tap ${connectionModeLabel(ConnectionMode.BLUETOOTH)} again"))
    }

    @Test fun theMessageWithNothingWorkingSaysWhatToDo() {
        val notice = searchAbandonedFor(ConnectionMode.BLUETOOTH, null).notice
        assertTrue("says nothing answered: '$notice'", notice.contains("No sensor answered"))
        assertTrue("points at the sensor", notice.contains("powered on and in range"))
        assertTrue("and offers the way back", notice.contains("try again"))
    }

    @Test fun theMessageIsPlainRatherThanTechnical() {
        // Not a status code, not an exception, not a UUID. This is the sentence a
        // user reads after a minute of waiting, and it has to be about their sensor.
        for (serving in listOf(ConnectionMode.WIFI, null)) {
            val notice = searchAbandonedFor(ConnectionMode.BLUETOOTH, serving).notice
            assertFalse(notice.contains("GATT"))
            assertFalse(notice.contains("timeout"))
            assertFalse(notice.contains("BLE"))
            assertFalse(notice.contains("Exception"))
            assertTrue("a whole sentence", notice.trim().endsWith("."))
        }
    }

    @Test fun aSwitchAwayFromBluetoothIsAbandonedTheSameWay() {
        // The rule is not about which radio it was: a Wi-Fi choice that finds no
        // host must not cost a working Bluetooth link either.
        val verdict = searchAbandonedFor(ConnectionMode.WIFI, ConnectionMode.BLUETOOTH)
        assertEquals(ConnectionMode.BLUETOOTH, verdict.fallback)
        assertTrue(verdict.notice.contains("still connected over Bluetooth"))
    }
}

/**
 * A transport that has stopped looking must not simply be started again.
 *
 * The link loop's whole job is to keep trying, and it is right about every
 * transport but one: the Bluetooth search now ends deliberately, and a loop that
 * called it again would restore the endless scan the bound was added to end.
 */
class KeepsTryingTest {

    @Test fun anOrdinaryEmptySweepIsRetried() {
        // The TCP sweep comes back empty all the time and the next one is what finds
        // a host that has just been switched on.
        assertTrue(keepsTrying(ConnectionState.NotFound("no sensor found on the network")))
    }

    @Test fun aSearchThatStoppedIsNotStartedAgain() {
        assertFalse(keepsTrying(ConnectionState.NotFound("no sensor answered", stopped = true)))
    }

    @Test fun everyOtherStateIsRetried() {
        assertTrue(keepsTrying(ConnectionState.Idle))
        assertTrue(keepsTrying(ConnectionState.Reconnecting("dropped")))
        assertTrue(keepsTrying(ConnectionState.Searching("scanning for the sensor")))
        assertTrue(keepsTrying(ConnectionState.Connected("SPS30")))
    }

    @Test fun aStoppedSearchStillReadsAsAFailedSearchOnScreen() {
        // Stopping changes what the loop does, not what the user is told: the
        // banner's verdict still has to name what was looked for and not found.
        val flags = discoveryFlagsFor(
            ConnectionState.NotFound("no sensor answered", stopped = true), wasFailed = false,
        )
        assertTrue(flags.failed)
        assertEquals("no sensor answered", flags.detail)
    }
}

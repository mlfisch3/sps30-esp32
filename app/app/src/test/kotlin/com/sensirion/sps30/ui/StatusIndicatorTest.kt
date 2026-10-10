package com.sensirion.sps30.ui

import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.data.DotTransport
import com.sensirion.sps30.net.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the connection header is a dot, and the one case where it is still words.
 *
 * The rule under test is the captain's, stated on 2026-08-12 after finding the
 * searching state drawn as a full-width pale pink bar across the top of his
 * tablet: **the three-colour dot covers disconnected too - red not connected,
 * green Wi-Fi, blue Bluetooth, no ring.** Not connected is an ordinary state on
 * a tablet that has just woken, and a banner for it costs a row of the plot
 * every time.
 *
 * This file used to assert the opposite - that every kind of trouble keeps the
 * expanded banner, because a loss of connection announced only by a small red
 * dot would be the silent failure this app keeps refusing to have. That concern
 * is answered rather than dismissed: the whole banner, wording and all, is one
 * tap on the dot away ([ConnectionDetailPopup]), and the two cases where a
 * colour genuinely cannot carry the meaning are kept apart below.
 */
class StatusIndicatorTest {

    private fun state(
        connection: ConnectionState = ConnectionState.Connected("192.168.1.226:8730"),
        mode: ConnectionMode = ConnectionMode.WIFI,
        active: ConnectionMode = mode,
        switchingTo: ConnectionMode? = null,
        discovering: Boolean = false,
        discoveryFailed: Boolean = false,
        streamStalled: Boolean = false,
        sensorShutDown: Boolean = false,
    ) = DashboardUiState(
        connection = connection,
        activeMode = active,
        switchingTo = switchingTo,
        discovering = discovering,
        discoveryFailed = discoveryFailed,
        streamStalled = streamStalled,
        sensorShutDown = sensorShutDown,
        config = AppConfig(connectionMode = mode),
    )

    // --- the three colours ---------------------------------------------------

    @Test fun runningOverWifiIsTheGreenDot() {
        assertEquals(
            StatusIndicator.Dot(DotTransport.WIFI),
            statusIndicator(state(mode = ConnectionMode.WIFI)),
        )
    }

    @Test fun runningOverBluetoothIsTheBlueDot() {
        assertEquals(
            StatusIndicator.Dot(DotTransport.BLUETOOTH),
            statusIndicator(state(mode = ConnectionMode.BLUETOOTH)),
        )
    }

    @Test fun disconnectedByChoiceIsTheRedDot() {
        // NONE is a state the user put the app in; restating their decision in
        // a banner is noise. The red fill and no ring carry it.
        assertEquals(
            StatusIndicator.Dot(null),
            statusIndicator(state(connection = ConnectionState.Idle, mode = ConnectionMode.NONE)),
        )
    }

    // --- and every other way of not being connected is the same red dot -------

    @Test fun everyConnectingPhaseIsTheRedDot() {
        val connecting = listOf(
            ConnectionState.Idle,
            ConnectionState.Searching("sweeping the local network"),
            ConnectionState.Connecting("192.168.1.226:8730"),
            ConnectionState.Reconnecting("connection lost"),
        )
        for (c in connecting) {
            assertEquals(
                "$c is not connected, so it is the red dot",
                StatusIndicator.Dot(null),
                statusIndicator(state(connection = c)),
            )
        }
    }

    @Test fun theSearchingBannerThatCostAWholeRowIsGone() {
        // The exact state the captain photographed: searching, discovering, no
        // sensor yet. It used to be a full-width pink bar above the plot.
        assertEquals(
            StatusIndicator.Dot(null),
            statusIndicator(
                state(connection = ConnectionState.Searching("scanning"), discovering = true),
            ),
        )
    }

    @Test fun aFailedSearchIsTheRedDotAndItsVerdictIsOneTapAway() {
        assertEquals(
            StatusIndicator.Dot(null),
            statusIndicator(
                state(connection = ConnectionState.NotFound("no sensor found"), discoveryFailed = true),
            ),
        )
    }

    @Test fun aStalledStreamIsRedEvenThoughTheSocketIsOpen() {
        // The socket says connected; no readings are arriving. Green beside a
        // frozen number would be the confident-banner-over-a-dead-plot failure,
        // so freshness and not the socket decides the colour.
        assertEquals(
            StatusIndicator.Dot(null),
            statusIndicator(state(streamStalled = true)),
        )
    }

    // --- what the dot must not get wrong -------------------------------------

    @Test fun aSwitchInFlightShowsTheLinkThatIsSERVING() {
        // Make-before-break: Wi-Fi is still delivering the readings on screen
        // while Bluetooth is brought up beside it. The dot vouches for those
        // readings, so it stays green until the handover lands - naming the
        // mode being switched *to* would be a claim about a link that has not
        // answered yet.
        assertEquals(
            StatusIndicator.Dot(DotTransport.WIFI),
            statusIndicator(
                state(
                    mode = ConnectionMode.BLUETOOTH,
                    active = ConnectionMode.WIFI,
                    switchingTo = ConnectionMode.BLUETOOTH,
                ),
            ),
        )
    }

    @Test fun aSensorThatAnnouncedShutdownIsStillSaidInWords() {
        // "Safe to unplug" cannot be carried by a dot, whatever its colour: it
        // is a fact about the instrument, not about the link, and it tells the
        // user they may now do something. This is the one banner left.
        assertEquals(
            StatusIndicator.Expanded,
            statusIndicator(state(sensorShutDown = true)),
        )
    }
}

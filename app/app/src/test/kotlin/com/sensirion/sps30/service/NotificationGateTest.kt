package com.sensirion.sps30.service

import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.ui.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ongoing notification is posted when it changes, and not otherwise.
 *
 * Measured from logcat on the tablet on 2026-08-04: 2-3.5 `notify(42)` calls a
 * second at idle and up to ~53 a second during a transfer - 23,157 posts in 39
 * minutes - because the service posted on every emission of the session's state.
 * Most of those emissions are things the notification does not draw: a row
 * landing in the plot, a backfill's progress, a transfer's byte count.
 */
class NotificationGateTest {

    private val connected = DashboardUiState(
        connection = ConnectionState.Connected("192.168.1.226:8730"),
        daemonState = "measuring",
        latest = mapOf("pm1_0" to 3.8, "pm2_5" to 4.2, "pm10" to 4.9),
    )

    @Test fun theFirstStateIsPosted() {
        assertNotNull(NotificationGate().postFor(connected))
    }

    @Test fun aStateThatDrawsTheSameTwoLinesIsNotPostedAgain() {
        val gate = NotificationGate()
        gate.postFor(connected)
        assertNull("the same notification was posted twice", gate.postFor(connected))
    }

    /**
     * The shape of the flood: a transfer moves the state on every frame and
     * moves nothing the notification shows.
     */
    @Test fun aTransferInFlightDoesNotRepostTheNotification() {
        val gate = NotificationGate()
        assertNotNull(gate.postFor(connected))
        var posts = 0
        for (row in 1..2_000) {
            val progressing = connected.copy(
                transfer = com.sensirion.sps30.data.SessionTransfer.Sending(
                    file = "sps30-export.zip",
                    session = "SPS30-20260804_011822.csv",
                    sessionsDone = 0,
                    sessionsTotal = 32,
                    rowsSent = row,
                    rowsTotal = 72_814,
                ),
            )
            if (gate.postFor(progressing) != null) posts++
        }
        assertEquals("2,000 transfer frames re-posted the same two lines", 0, posts)
    }

    @Test fun aNewReadoutIsPosted() {
        val gate = NotificationGate()
        gate.postFor(connected)
        val moved = connected.copy(latest = mapOf("pm1_0" to 8.4, "pm2_5" to 9.7, "pm10" to 11.0))
        val content = gate.postFor(moved)
        assertNotNull("the reading changed and the notification did not", content)
        assertEquals("PM1.0 8.40 · PM2.5 9.70 µg/m³", content?.text)
    }

    @Test fun aChangeOfConnectionIsPosted() {
        val gate = NotificationGate()
        gate.postFor(connected)
        val lost = connected.copy(connection = ConnectionState.Reconnecting("the link dropped"))
        assertNotNull(gate.postFor(lost))
    }

    /**
     * Once the notification has been taken away there is nothing on screen for
     * the next state to be the same as, so the gate has to forget.
     */
    @Test fun aResetPostsTheNextStateWhateverItSays() {
        val gate = NotificationGate()
        gate.postFor(connected)
        gate.reset()
        assertNotNull(gate.postFor(connected))
    }
}

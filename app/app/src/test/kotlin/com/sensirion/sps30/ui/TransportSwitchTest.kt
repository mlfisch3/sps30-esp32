package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule for what a mode selection does to the links that are running.
 *
 * Switching used to break the working link and then go looking for the new one,
 * so the plot stopped for as long as a BLE scan takes and whatever the board
 * recorded in the meantime was only recovered by luck. The rule below is the
 * whole of the fix, which is why it lives in a function that can be stated
 * without a radio.
 */
class TransportChangeTest {

    @Test fun choosingTheModeAlreadyServingDoesNothing() {
        assertEquals(
            TransportChange.Nothing,
            transportChangeFor(ConnectionMode.WIFI, ConnectionMode.WIFI, null, true),
        )
    }

    @Test fun aWorkingLinkIsHandedOverRatherThanDropped() {
        val change = transportChangeFor(
            target = ConnectionMode.BLUETOOTH,
            serving = ConnectionMode.WIFI,
            pending = null,
            servingConnected = true,
        )
        assertEquals(TransportChange.Handover(ConnectionMode.BLUETOOTH), change)
    }

    @Test fun bothDirectionsHandOver() {
        // The switch has to be gapless either way round; Bluetooth -> Wi-Fi is the
        // one that used to be worst, because a subnet sweep is seconds of nothing.
        assertEquals(
            TransportChange.Handover(ConnectionMode.WIFI),
            transportChangeFor(ConnectionMode.WIFI, ConnectionMode.BLUETOOTH, null, true),
        )
    }

    @Test fun aLinkThatIsNotConnectedHasNothingToProtect() {
        // Still searching: waiting for a handover that can never complete would
        // only delay the transport the user asked for.
        assertEquals(
            TransportChange.Restart(ConnectionMode.BLUETOOTH),
            transportChangeFor(ConnectionMode.BLUETOOTH, ConnectionMode.WIFI, null, false),
        )
    }

    @Test fun coldStartRestarts() {
        assertEquals(
            TransportChange.Restart(ConnectionMode.WIFI),
            transportChangeFor(ConnectionMode.WIFI, null, null, false),
        )
    }

    @Test fun disconnectIsNeverAHandover() {
        // There is nothing on the other side to hand over to, so Disconnect must
        // tear down immediately or the app would keep streaming after being told
        // to stop.
        assertEquals(
            TransportChange.Restart(ConnectionMode.NONE),
            transportChangeFor(ConnectionMode.NONE, ConnectionMode.WIFI, null, true),
        )
    }

    @Test fun changingBackMidSwitchCancelsIt() {
        val change = transportChangeFor(
            target = ConnectionMode.WIFI,
            serving = ConnectionMode.WIFI,
            pending = ConnectionMode.BLUETOOTH,
            servingConnected = true,
        )
        assertEquals(TransportChange.CancelSwitch, change)
    }

    @Test fun askingAgainForTheSwitchAlreadyRunningDoesNotRestartIt() {
        // Restarting would throw away a scan that is most of the way through and
        // start the fifteen seconds again.
        assertEquals(
            TransportChange.Nothing,
            transportChangeFor(
                ConnectionMode.BLUETOOTH, ConnectionMode.WIFI, ConnectionMode.BLUETOOTH, true,
            ),
        )
    }

    @Test fun theWorkingLinkIsTheOneCaptionedDuringASwitch() {
        assertEquals(
            ConnectionMode.WIFI,
            servingDuringSwitch(ConnectionMode.WIFI, ConnectionMode.BLUETOOTH),
        )
        assertNull("no switch, no second caption", servingDuringSwitch(ConnectionMode.WIFI, null))
    }
}

/**
 * What the switch is actually for: the plot comes out of it whole.
 *
 * These exercise the store the way a handover drives it - readings over the old
 * transport, a seam where neither is delivering, readings over the new one, and
 * then the `get_file since_index` backfill that fills the seam from the board's
 * own record. Nothing here is interpolated or invented; the backfill rows are
 * exactly what the controller would send back.
 */
class HandoverContinuityTest {

    private val columns = Wire.CSV_COLUMNS

    /** One reading as the live stream delivers it. */
    private fun live(csv: String, index: Int) = ServerMessage.Reading(
        csv, "%07d".format(index), stamp(index),
        Wire.PM_FIELDS.associateWith { index.toDouble() },
    )

    /** The same reading as a `file_data` row, cells in [Wire.CSV_COLUMNS] order. */
    private fun row(index: Int): List<String> = columns.map { c ->
        when (c) {
            "index" -> "%07d".format(index)
            "timestamp" -> stamp(index)
            Wire.COL_EVENT -> ""
            else -> index.toString()
        }
    }

    /** Second `index` of a fixed minute, so ordering is index ordering. */
    private fun stamp(index: Int) = "2026-08-03T02:%02d:%02d-04:00".format(index / 60, index % 60)

    @Test fun theSeamIsFilledFromTheBoardAndNothingIsDoubled() {
        val h = TimeSeriesHistory()
        val csv = "SPS30-20260803_020000.csv"

        // Over Wi-Fi, up to index 10.
        for (i in 1..10) assertTrue(h.add(live(csv, i)))
        // The switch: neither link delivers 11..20.
        // The new Bluetooth link comes up and starts at 21.
        for (i in 21..25) assertTrue(h.add(live(csv, i)))
        assertEquals(15, h.size)

        // The backfill asks from where this copy stops being complete, which is 10 -
        // NOT from the highest index held, which the readings that arrived after the
        // seam have already pushed to 25. Asking from 25 is what used to make the
        // controller answer with nothing and leave the hole permanent.
        val since = h.coverage()[csv]!!.contiguousTo
        assertEquals(10, since)
        // And the copy has to read as short, or `file_list` would skip the session
        // entirely: 15 held against the 25 the controller says it has.
        assertEquals(15, h.coverage()[csv]!!.rows)

        // The board answers `get_file since_index=10` with everything past 10,
        // which overlaps the live readings already held. That overlap is the price
        // of never leaving a hole, and de-dup by (csv, index) is what makes it free.
        val added = h.addRows(csv, columns, ((since + 1)..25).map { row(it) }, explicit = true)
        assertEquals("only the seam is new", 10, added)
        assertEquals(25, h.size)

        // Contiguous, in order, each index exactly once.
        val samples = h.snapshot()
        assertEquals((1..25).toList(), samples.map { it.index })
        assertEquals(
            "time order, so the chart draws one unbroken line",
            samples.map { it.epoch!! }.sorted(), samples.map { it.epoch!! },
        )
    }

    @Test fun theCursorDoesNotDependOnHelloWinningTheRace() {
        // The old code was only correct because `hello` is the first thing a client
        // is sent, so the cursor was read before any reading off the new link could
        // raise it. That is a timing argument, and this is the same case with the
        // race lost: live readings folded in FIRST, cursor taken afterwards.
        val h = TimeSeriesHistory()
        val csv = "SPS30-20260803_020000.csv"
        for (i in 1..10) h.add(live(csv, i))
        for (i in 21..25) h.add(live(csv, i))

        val since = h.coverage()[csv]!!.contiguousTo
        assertEquals("still asks from the edge of the hole", 10, since)
        val filled = h.addRows(csv, columns, ((since + 1)..25).map { row(it) }, explicit = true)
        assertEquals(10, filled)
        assertEquals((1..25).toList(), h.snapshot().map { it.index })
    }

    @Test fun anOlderSessionWithASeamIsNotMistakenForComplete() {
        // A switch during session A, then the board reboots into session B. The
        // check that skips a session already held has to compare counts: 15 held
        // against the 25 the controller reports is short, and short means fetch.
        val h = TimeSeriesHistory()
        val a = "SPS30-20260803_020000.csv"
        for (i in 1..10) h.add(live(a, i))
        for (i in 21..25) h.add(live(a, i))
        val held = h.coverage()[a]!!
        assertTrue("the copy is short of the controller's 25", held.rows < 25)
        assertEquals(10, held.contiguousTo)
    }

    @Test fun aSecondSwitchBackDoesNotDuplicateAnything() {
        // Wi-Fi -> Bluetooth -> Wi-Fi. Each hello triggers a backfill of the same
        // session, and the second one re-sends rows the first already delivered.
        val h = TimeSeriesHistory()
        val csv = "SPS30-20260803_020000.csv"
        for (i in 1..10) h.add(live(csv, i))
        h.addRows(csv, columns, (11..20).map { row(it) }, explicit = true)
        for (i in 21..30) h.add(live(csv, i))
        val again = h.addRows(csv, columns, (11..30).map { row(it) }, explicit = true)
        assertEquals("a repeated backfill adds nothing", 0, again)
        assertEquals((1..30).toList(), h.snapshot().map { it.index })
    }

    @Test fun aSwitchAcrossARebootKeepsTheSessionsApart() {
        // The board starts a new session on reboot, so the two carry the same
        // indices. They must not annihilate each other - the key is (csv, index).
        val h = TimeSeriesHistory()
        for (i in 1..5) h.add(live("SPS30-20260803_020000.csv", i))
        for (i in 1..5) h.add(live("SPS30-20260803_030000.csv", i))
        assertEquals(10, h.size)
        assertEquals(5, h.coverage()["SPS30-20260803_020000.csv"]!!.contiguousTo)
        assertEquals(5, h.coverage()["SPS30-20260803_030000.csv"]!!.contiguousTo)
    }
}

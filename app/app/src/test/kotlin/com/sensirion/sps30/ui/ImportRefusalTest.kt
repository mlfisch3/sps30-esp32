package com.sensirion.sps30.ui

import com.sensirion.sps30.data.SessionTransfer
import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app half of the two-devices-importing-at-once fix.
 *
 * The controller now answers a transfer it cannot serve with one
 * `import_result` carrying `state: "busy"` and nothing added, instead of an
 * `error` per chunk that nobody was reading (see
 * `firmware/components/net/net_import.h`). That answer arrives here, and the
 * hazard is that it reads exactly like a success: **both** a refusal and a file
 * the sensor already held report `added = 0`, and the wording for the second -
 * "The sensor already had all of it" - would be a flat lie about the first.
 *
 * So the two must be told apart at the protocol edge and again in the wording.
 */
class ImportRefusalTest {

    private fun result(state: String, message: String = "") =
        Messages.parse(
            """{"type":"import_result","name":"SPS30-20260803_200110.csv","added":0,""" +
                """"duplicate":0,"rejected":52226,"sessions":0,"state":"$state",""" +
                """"message":"$message"}""",
        ) as ServerMessage.ImportResult

    @Test fun aBusyResultIsNotASuccess() {
        val msg = result(
            "busy",
            "another device was already importing, so 52226 readings were not taken - " +
                "send the file again in a moment",
        )
        assertFalse("busy must not read as ok", msg.ok)
        assertFalse("busy is not the ring being full", msg.full)
        assertTrue("busy means the controller stored none of it", msg.tookNothing)
        assertEquals(0, msg.added)
        assertEquals(52226, msg.rejected)
    }

    @Test fun anErrorResultAlsoMeansNothingWasTaken() {
        val msg = result("error", "storage is not available on this controller")
        assertTrue(msg.tookNothing)
    }

    @Test fun anOrdinaryResultIsNotMistakenForARefusal() {
        val msg = result("ok", "0 added, 52226 already here")
        assertTrue(msg.ok)
        assertFalse("a re-import of readings the sensor holds is a success", msg.tookNothing)
    }

    @Test fun aFullRingIsStillItsOwnThing() {
        val msg = result("full", "the controller ran out of room after 12 readings")
        assertTrue(msg.full)
        assertFalse("`full` has its own wording and its own advice", msg.tookNothing)
    }

    // --- and the wording -----------------------------------------------------

    private fun done(
        added: Int = 0,
        duplicate: Int = 0,
        rejected: Int = 0,
        sessions: Int = 0,
        ranOutOfRoom: Boolean = false,
        refused: Boolean = false,
        message: String = "",
    ) = SessionTransfer.Done(
        file = "sessions.zip",
        added = added,
        duplicate = duplicate,
        rejected = rejected,
        sessions = sessions,
        ranOutOfRoom = ranOutOfRoom,
        refused = refused,
        message = message,
    )

    @Test fun aRefusalIsNeverWordedAsTheSensorAlreadyHavingIt() {
        // The bug this guards: `added == 0` was asked first, so a transfer the
        // controller never looked at was reported as one it already held.
        val headline = transferHeadline(
            done(refused = true, rejected = 52226, message = "another device was importing"),
        )
        assertEquals("The sensor did not take it", headline)
        assertFalse(
            "a refusal must not be worded as a success: '$headline'",
            headline.contains("already had"),
        )
    }

    @Test fun aRefusalShowsTheControllersOwnReason() {
        val why = "another device was already importing, so 52226 readings were not taken - " +
            "send the file again in a moment"
        assertEquals(why, transferDetail(done(refused = true, rejected = 52226, message = why)))
    }

    @Test fun aTransferTheSensorAlreadyHeldStillSaysSo() {
        val transfer = done(added = 0, duplicate = 52226, sessions = 1)
        assertEquals("The sensor already had all of it", transferHeadline(transfer))
        assertTrue(transferDetail(transfer).contains("52,226 were already there"))
    }

    @Test fun aTransferThatAddedSomethingSaysHowMuch() {
        assertEquals(
            "Added 1,204 readings to the sensor",
            transferHeadline(done(added = 1204, duplicate = 12, sessions = 2)),
        )
    }

    @Test fun aFullRingKeepsItsOwnHeadline() {
        assertEquals(
            "The sensor ran out of room",
            transferHeadline(done(added = 40, ranOutOfRoom = true)),
        )
    }

    // --- and the opposite fault ----------------------------------------------
    //
    // Asking `refused` first made the heading about the controller's *state*
    // rather than about whether the data arrived, and those are not the same
    // question. A controller that placed every row it could still answers
    // `error` when some rows had a reason to be turned away.

    /**
     * The real one, from the freeze-verification pass of 2026-08-04.
     *
     * 72,814 readings were sent and every one of them arrived: 71,548 the sensor
     * already held, 1,266 refused for having been recorded without a clock. The
     * controller's state was `error` because it had a reason to give, and the
     * heading read "The sensor did not take it" over a body that said precisely
     * what it had taken. In front of an audience that is a failure report of the
     * one operation that used to fail.
     */
    @Test fun aTransferThatFullyArrivedIsNotHeadedAsAFailure() {
        val transfer = done(
            added = 0,
            duplicate = 71_548,
            rejected = 1_266,
            sessions = 32,
            refused = true,
            message = "this controller cannot yet take back a session recorded without a " +
                "clock; 0 added, 71548 already here, 1266 not placed",
        )
        val headline = transferHeadline(transfer)
        assertFalse(
            "the sensor took 71,548 readings and the heading says it took none: '$headline'",
            headline.contains("did not take it"),
        )
        assertEquals("The sensor has all but 1,266 of the readings", headline)
        assertFalse("nothing landed, on a transfer where 71,548 did", transfer.nothingLanded)
        assertEquals(71_548, transfer.landed)
    }

    /** The counts still get the controller's own explanation under them. */
    @Test fun theReasonSomeRowsWereNotPlacedIsStillShown() {
        val why = "this controller cannot yet take back a session recorded without a clock; " +
            "0 added, 71548 already here, 1266 not placed"
        assertEquals(
            why,
            transferDetail(done(duplicate = 71_548, rejected = 1_266, refused = true, message = why)),
        )
    }

    /** A send that added rows and had some turned away says both. */
    @Test fun aPartlyPlacedTransferSaysHowMuchDidNotGoIn() {
        assertEquals(
            "The sensor has all but 1,266 of the readings",
            transferHeadline(done(added = 5_000, duplicate = 100, rejected = 1_266)),
        )
    }

    /** And a refusal that really placed nothing keeps the failure wording. */
    @Test fun aRefusalThatPlacedNothingIsStillAFailure() {
        assertEquals(
            "The sensor did not take it",
            transferHeadline(done(rejected = 52_226, refused = true, message = "another device")),
        )
    }
}

package com.sensirion.sps30.ui

import com.sensirion.sps30.data.SessionTransfer
import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a send back to the sensor is finished, and who gets to say so.
 *
 * The failure these exist for was seen on the board on 2026-08-04: an archive of
 * 111,056 readings went across in full, the progress line reached "111,056 of
 * 111,056", and it stayed there. For ten minutes, on two attempts, with the app
 * connected and streaming the whole time. There was no error, no timeout, no
 * retirement and nothing in any log on either side to say it had happened.
 *
 * Two ends, each reasonable alone, disagreeing about when a send is over:
 *
 *  - the app treated **the last frame going out** as the end. Its watchdog
 *    ([streamIsDead]) covers a send that is *sending*, and is deliberately stood
 *    down the moment the last frame is queued - at which point the controller
 *    resumes the live stream it had paused, so every watchdog in the app saw a
 *    perfectly healthy link;
 *  - the controller treats **the frame marked `done`** as the end, answers it
 *    exactly once, and never repeats that answer. It also ends a send that has
 *    gone quiet for fifteen seconds *on its own* and answers that, knowing
 *    nothing of whether the sender has stopped.
 *
 * So between the last frame and the answer there was no owner: nothing running,
 * no deadline, and a progress bar sitting at its end as the only thing on
 * screen. Any one of the ordinary ways for one line to go missing left it there
 * for good, and the only way out was force-stopping the app.
 *
 * The rules below are what fills that gap. They are stated against the pure
 * functions rather than a device because the defect is a disagreement about
 * meaning, and that can be stated without a radio.
 */
class TransferCompletionTest {

    private val t0 = 1_754_300_000_000L

    private fun answer(
        added: Int = 0,
        duplicate: Int = 0,
        rejected: Int = 0,
        sessions: Int = 1,
        state: String = "ok",
        message: String = "$added added, $duplicate already here",
    ) = ServerMessage.ImportResult(
        name = "SPS30-20260804_043401.csv",
        added = added,
        duplicate = duplicate,
        rejected = rejected,
        sessions = sessions,
        state = state,
        message = message,
    )

    private fun sending(rowsSent: Int = 5_000, landedSoFar: Int = 0) = SessionTransfer.Sending(
        file = "sps30-export-20260804-043401.zip",
        session = "SPS30-20260804_043401.csv",
        sessionsDone = 3,
        sessionsTotal = 64,
        rowsSent = rowsSent,
        rowsTotal = 111_056,
        landedSoFar = landedSoFar,
    )

    private fun awaiting(landedSoFar: Int = 0, sentAtMs: Long = t0) = SessionTransfer.Awaiting(
        file = "sps30-export-20260804-043401.zip",
        rowsSent = 111_056,
        sentAtMs = sentAtMs,
        landedSoFar = landedSoFar,
    )

    // --- the deadline that was missing --------------------------------------

    @Test fun aSendThatIsNeverAnsweredIsGivenUpOnRatherThanWaitedOnForEver() {
        val given = transferAfterAnswerDeadline(awaiting(), t0 + TRANSFER_ANSWER_MS)
        assertTrue(
            "an unanswered send is still showing as in progress: $given",
            given is SessionTransfer.Failed,
        )
        val reason = (given as SessionTransfer.Failed).reason
        // What it says matters as much as that it says something: the readings
        // did go, and nothing on the sensor was removed to receive them. A user
        // told only "it failed" has no way to know either.
        assertTrue("the reason does not say the readings were sent: $reason", "sent" in reason)
        assertTrue(
            "the reason does not say the sensor kept what it had: $reason",
            "Nothing was removed" in reason,
        )
        assertTrue("the reason does not say what to do: $reason", "send the file again" in reason)
    }

    @Test fun theControllerIsGivenLongerToAnswerThanItsOwnWorstCase() {
        // `NET_IMPORT_IDLE_MS` (15 s): the controller closes a send that has gone
        // quiet and *answers* it, which is a far better thing for the user to see
        // than a timeout here. `CLIENT_STALL_MS` (30 s): it then gives a client
        // that long to accept the answer before dropping it. Forty-five seconds
        // is therefore the controller's own worst case, and firing inside it
        // would replace a true account with a guess.
        val controllerWorstCase = 15_000L + 30_000L
        assertTrue(
            "the app gives up at ${TRANSFER_ANSWER_MS} ms, inside the controller's own " +
                "$controllerWorstCase ms, so a board that was about to answer is pre-empted",
            TRANSFER_ANSWER_MS > controllerWorstCase,
        )
        val stillWaiting = awaiting()
        assertSame(
            "given up on at the controller's worst case, before its answer could arrive",
            stillWaiting,
            transferAfterAnswerDeadline(stillWaiting, t0 + controllerWorstCase),
        )
    }

    @Test fun aSendStillWaitingWellInsideTheBoundIsLeftAlone() {
        for (elapsed in longArrayOf(0, 1_000, 15_500, TRANSFER_ANSWER_MS - 1)) {
            val waiting = awaiting()
            assertSame(
                "given up on after $elapsed ms",
                waiting,
                transferAfterAnswerDeadline(waiting, t0 + elapsed),
            )
        }
    }

    @Test fun onlyASendWaitingOnAnAnswerHasThisDeadline() {
        // Everything else either has its own ([streamIsDead] covers a send that
        // is still sending) or has already ended.
        val long = t0 + 10 * TRANSFER_ANSWER_MS
        for (other in listOf<SessionTransfer>(
            SessionTransfer.Idle,
            SessionTransfer.Checking("f.zip"),
            sending(),
            SessionTransfer.Rejected("f.zip", "no readings in it"),
            SessionTransfer.Failed("f.zip", "the connection dropped"),
        )) {
            assertSame("$other was given the answer deadline", other, transferAfterAnswerDeadline(other, long))
        }
    }

    // --- the answer that used to be thrown away -----------------------------

    @Test fun anAnswerThatArrivesMidSendDoesNotEndTheSend() {
        // The controller ends a send that has gone quiet for fifteen seconds and
        // answers it. The sender knows nothing of that and is still sending, so
        // this is one part of the file, not the end of it. Ending here would put
        // a result on screen that the very next progress update overwrites - and
        // since the controller answers each part exactly once, the readings that
        // part covered would then be accounted for nowhere at all.
        val mid = sending(rowsSent = 40_000)
        val next = transferAfterAnswer(mid, mid.file, answer(added = 0, duplicate = 9_000))
        assertTrue("a mid-send answer ended the send: $next", next is SessionTransfer.Sending)
        assertEquals(
            "the readings the controller accounted for were not kept",
            9_000,
            (next as SessionTransfer.Sending).landedSoFar,
        )
        assertEquals("progress was disturbed by the answer", mid.rowsSent, next.rowsSent)
    }

    @Test fun everyPartTheControllerAnsweredIsAddedBackIntoTheOneResult() {
        var state: SessionTransfer = sending(rowsSent = 40_000)
        state = transferAfterAnswer(state, "f.zip", answer(added = 0, duplicate = 9_000))
        state = transferAfterAnswer(state, "f.zip", answer(added = 100, duplicate = 2_000))
        assertEquals(11_100, (state as SessionTransfer.Sending).landedSoFar)

        // The last frame goes out; the send is now waiting on the final answer,
        // and carries what the earlier parts covered with it.
        val waiting = awaiting(landedSoFar = state.landedSoFar)
        val done = transferAfterAnswer(waiting, waiting.file, answer(added = 0, duplicate = 500))
        assertTrue(done is SessionTransfer.Done)
        done as SessionTransfer.Done
        assertEquals(
            "the file's own account is short by the parts answered separately",
            11_600,
            done.landed,
        )
        assertFalse(
            "a file the sensor holds 11,600 readings of is being reported as arriving nowhere",
            done.nothingLanded,
        )
        assertTrue(
            "nothing says the file was answered in more than one part: ${done.message}",
            "earlier" in done.message,
        )
    }

    @Test fun theAnswerAfterTheLastFrameIsTheOneThatEndsTheSend() {
        val done = transferAfterAnswer(
            awaiting(),
            "sps30-export-20260804-043401.zip",
            answer(added = 12, duplicate = 108_937, rejected = 2_119, sessions = 61, state = "error"),
        )
        assertTrue(done is SessionTransfer.Done)
        done as SessionTransfer.Done
        assertEquals(12, done.added)
        assertEquals(108_937, done.duplicate)
        assertEquals(2_119, done.rejected)
        assertEquals(61, done.sessions)
        assertEquals("sps30-export-20260804-043401.zip", done.file)
    }

    @Test fun anAnswerThatArrivesAfterGivingUpStillReplacesTheGuess() {
        // The controller's own account beats a timeout, however late. Leaving the
        // failure up would tell the user their data went nowhere while the sensor
        // was in the middle of saying it had all of it.
        val gaveUp = SessionTransfer.Failed(
            "sps30-export-20260804-043401.zip",
            transferUnansweredReason("the sensor never said what it did with them"),
        )
        val done = transferAfterAnswer(gaveUp, gaveUp.file, answer(added = 0, duplicate = 111_056))
        assertTrue("a late answer was discarded: $done", done is SessionTransfer.Done)
        assertEquals(111_056, (done as SessionTransfer.Done).landed)
    }

    @Test fun anAnswerWithNothingOfOursInFlightChangesNothing() {
        for (idle in listOf<SessionTransfer>(
            SessionTransfer.Idle,
            SessionTransfer.Checking("f.zip"),
            SessionTransfer.Rejected("f.zip", "no readings in it"),
        )) {
            assertSame(
                "$idle was turned into a result by an answer for a send that was never made",
                idle,
                transferAfterAnswer(idle, "f.zip", answer(added = 5)),
            )
        }
    }

    // --- the word ------------------------------------------------------------

    @Test fun whatTheUserIsToldSaysSendingRatherThanMoving() {
        // Nothing on this path moves: a file sent back is copied to the sensor
        // and stays on this device, and the sensor keeps everything it had. A
        // word that implies moving is what makes a user hesitate over an
        // operation that cannot lose anything.
        val reason = transferUnansweredReason("the connection dropped")
        assertFalse("'transfer' implies moving: $reason", "transfer" in reason.lowercase())
        assertTrue(reason.startsWith("every reading was sent"))
    }
}

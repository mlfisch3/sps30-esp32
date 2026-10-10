package com.sensirion.sps30.ui

import com.sensirion.sps30.data.SessionTransfer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides when the app closes its own socket.
 *
 * This is the whole of the fix for "sending a full archive back to the sensor
 * fails every time" - three attempts of three, at 6,991, 11,334 and 13,742 rows
 * of the same 72,814 file, each ending "the connection dropped while sending".
 * The board was never at fault: it did not reboot, it kept streaming at 1 Hz to
 * a separate probe throughout, and it accepted the same import from a host
 * client twice. The app was killing its own connection.
 *
 * The mechanism, which is a contradiction between two components that were each
 * reasonable alone:
 *
 *  - the controller deliberately sends **nothing** to a client that is
 *    uploading (`client_task` in `net_server.c`: "Nothing is volunteered to a
 *    client that is uploading"), because its reply buffer is one line deep and
 *    a reading queued mid-import would stop it reading the transfer;
 *  - the app treated 15 s without a reading as a dead link and closed the
 *    socket to force a reconnect, with no idea a transfer was in flight.
 *
 * So the transfer guaranteed the silence that killed it. Three different row
 * counts and one duration is the signature: the trigger was the clock, not the
 * data.
 *
 * These cases are stated against [streamIsDead] rather than a device because
 * the bug is a rule, and a rule can be stated without a radio.
 */
class StreamWatchdogTest {

    private val t0 = 1_754_000_000_000L

    /** No transfer is putting frames on the link. */
    private val idle = 0L

    // --- the bug ------------------------------------------------------------

    @Test fun aTransferIsNotKilledByTheSilenceItCauses() {
        // The reproduction, in the terms the rule sees it: the last reading
        // arrived just before the transfer began and no other can arrive until
        // it ends, but frames are being accepted the whole way.
        val transferStarted = t0
        for (elapsed in longArrayOf(15_000, 20_000, 60_000, 79_000, 10 * 60_000)) {
            val now = transferStarted + elapsed
            assertFalse(
                "a transfer $elapsed ms in, still being accepted, is alive",
                streamIsDead(
                    nowMs = now,
                    lastReadingAtMs = transferStarted - 500,
                    // A frame went out this second; the queue only takes one
                    // when the socket drains and the controller reads.
                    lastTransferProgressAtMs = now - 1_000,
                ),
            )
        }
    }

    @Test fun theOldRuleWouldHaveFiredOnEveryOneOfThose() {
        // Guards the test above from passing vacuously: without the transfer
        // arm, each of those instants is past STREAM_SILENT_MS and the app
        // closed its socket. This is the reverted behaviour, stated once.
        val transferStarted = t0
        for (elapsed in longArrayOf(15_000, 20_000, 60_000, 79_000)) {
            assertTrue(
                "reading-only silence of $elapsed ms reads as dead",
                streamIsDead(
                    nowMs = transferStarted + elapsed,
                    lastReadingAtMs = transferStarted - 500,
                    lastTransferProgressAtMs = idle,
                ),
            )
        }
    }

    @Test fun theTransferOutlivesTheFifteenSecondWindowByAWholeArchive() {
        // The measured shape of the operation this has to survive: 72,814 rows
        // at the ~400 rows/s the board takes is around three minutes of a
        // stream the controller is deliberately withholding.
        assertFalse(
            streamIsDead(
                nowMs = t0 + 182_000,
                lastReadingAtMs = t0,
                lastTransferProgressAtMs = t0 + 181_500,
            ),
        )
    }

    // --- and the watchdog still does its real job ---------------------------

    @Test fun aTransferThatStopsBeingAcceptedIsStillTornDown() {
        // A board that has genuinely gone stops taking bytes, the bounded queue
        // fills, `sendAwait` stops returning, and progress stops. That must not
        // hang at "Sending" for ever - the exemption is for a *moving*
        // transfer, not for the existence of one.
        assertTrue(
            streamIsDead(
                nowMs = t0 + TRANSFER_STALLED_MS,
                lastReadingAtMs = t0,
                lastTransferProgressAtMs = t0,
            ),
        )
    }

    @Test fun aStalledTransferIsGivenLongerThanTheBoardsOwnIdleCloseOut() {
        // The controller closes out an import quiet for NET_IMPORT_IDLE_MS
        // (15 s) and answers it, which is a better thing to show the user than
        // a dropped connection. Firing before then would pre-empt it.
        assertTrue("the app must not give up first", TRANSFER_STALLED_MS > STREAM_SILENT_MS)
        assertFalse(
            "still the board's turn at 15 s of a stalled transfer",
            streamIsDead(
                nowMs = t0 + 15_000,
                lastReadingAtMs = t0,
                lastTransferProgressAtMs = t0,
            ),
        )
    }

    @Test fun anOrdinaryDeadStreamIsStillCaughtAtFifteenSeconds() {
        // The behaviour this watchdog exists for, unchanged: a peer that
        // vanished without a FIN leaves the reader blocked for ever, and the
        // app showed "connected" over a frozen plot until it was restarted.
        assertTrue(streamIsDead(t0 + STREAM_SILENT_MS, t0, idle))
        assertFalse(streamIsDead(t0 + STREAM_SILENT_MS - 1, t0, idle))
    }

    @Test fun aLinkThatHasNeverDeliveredAReadingIsNotJudgedYet() {
        // 0 means "nothing to be silent against": the link connected but no
        // reading has arrived, so there is no gap to measure.
        assertFalse(streamIsDead(t0 + 60_000, 0L, idle))
    }

    // --- handing the link back ----------------------------------------------

    @Test fun aFinishedTransferDoesNotLeaveTheLinkInstantlyOverdue() {
        // The other half of the fix. Readings were withheld for the length of
        // the transfer, so measuring the silence from the last one before it
        // would fire on the very next tick and drop a connection the completed
        // transfer had just proved healthy. The transfer's `finally` re-arms
        // the reading clock as it clears the transfer one; this is that pair of
        // values, one tick later.
        val ended = t0 + 182_000
        assertFalse(
            "a transfer that just succeeded must not be followed by a teardown",
            streamIsDead(nowMs = ended + 1_000, lastReadingAtMs = ended, lastTransferProgressAtMs = idle),
        )
    }

    @Test fun andTheOrdinaryRuleResumesFromThere() {
        // Re-armed, not exempted: if the stream really does not come back after
        // the transfer, the watchdog fires fifteen seconds later as it always
        // did.
        val ended = t0 + 182_000
        assertTrue(streamIsDead(ended + STREAM_SILENT_MS, ended, idle))
    }

    // --- the same silence, one stage later ----------------------------------
    //
    // The fix above covers a send that is *sending*, and stands down when the
    // last frame goes out. It is not out of silence at that point. The
    // controller stays quiet until it has read the whole transfer and answered
    // it, and the last frame reaching this device's queue is not the last frame
    // reaching the board: on 2026-08-04 the app finished handing over a
    // 121,249-reading archive at 08:08:23 and the board, whose console shows it
    // storing rows without a pause the whole time, answered at 08:08:38.
    //
    // So the reading clock started again into a silence that had fifteen more
    // seconds to run, and the app closed its own socket about thirty
    // milliseconds after the answer was written to it. Twice, at 15.07 s and
    // 15.41 s, byte-identical, and a fourth device pass failed on it.

    /** The measured failure, in the terms the rule sees it. */
    @Test fun theWaitForTheAnswerIsNotJudgedAsADeadStream() {
        val lastFrame = t0
        for (elapsed in longArrayOf(15_000, 15_070, 15_410, 30_000, 45_000, TRANSFER_ANSWER_MS)) {
            assertFalse(
                "the link was torn down $elapsed ms into a wait the controller is entitled to",
                streamIsDead(
                    nowMs = lastFrame + elapsed,
                    // Re-armed by the transfer's `finally` as the last frame went.
                    lastReadingAtMs = lastFrame,
                    lastTransferProgressAtMs = idle,
                    awaitingAnswerSinceMs = lastFrame,
                ),
            )
        }
    }

    @Test fun theOldRuleWouldHaveFiredOnBothOfTheMeasuredAnswers() {
        // Guards the test above from passing vacuously: with nothing waiting on
        // an answer, both of the instants the board answered at are past
        // STREAM_SILENT_MS, and both times the app closed its socket first.
        for (elapsed in longArrayOf(15_070, 15_410)) {
            assertTrue(
                "an answer at $elapsed ms would have been read, so this proves nothing",
                streamIsDead(t0 + elapsed, t0, idle, awaitingAnswerSinceMs = 0L),
            )
        }
    }

    @Test fun aLinkTheSensorHasJustAnsweredIsNotFifteenSecondsSilent() {
        // The clock, not the rule. Holding the teardown while nothing is
        // arriving is only half of it: a reading clock left running through the
        // wait is already overdue at the moment the answer lands, so the tick
        // after a send back finally succeeded would drop the link. Played out a
        // second at a time, over the measured 15.07 s.
        val lastFrame = t0
        var clock = lastFrame
        var now = lastFrame
        while (now < lastFrame + 15_070) {
            assertFalse(
                "torn down mid-wait at ${now - lastFrame} ms",
                streamIsDead(now, clock, idle, awaitingAnswerSinceMs = lastFrame),
            )
            clock = readingClockAfterTick(now, clock, awaitingAnswerSinceMs = lastFrame)
            now += 1_000
        }
        // The answer arrives, and nothing is waiting on one any more.
        assertFalse(
            "the link was judged dead on the tick after the sensor answered it",
            streamIsDead(now + 1_000, clock, idle, awaitingAnswerSinceMs = 0L),
        )
    }

    @Test fun aWaitThatOutlivesItsOwnDeadlineIsStillTornDown() {
        // Not an exemption either. A controller that will never answer leaves
        // this link to be rebuilt, once its own deadline has had its say.
        assertTrue(
            streamIsDead(
                nowMs = t0 + TRANSFER_ANSWER_MS + 1,
                lastReadingAtMs = t0,
                lastTransferProgressAtMs = idle,
                awaitingAnswerSinceMs = t0,
            ),
        )
    }

    @Test fun onTheTickTheyBothComeDueTheAnswerGetsToSayWhatHappened() {
        // `checkStream` runs before `checkTransferAnswer` on the same tick. A
        // teardown at exactly TRANSFER_ANSWER_MS would replace a true account of
        // what was sent - "every reading was sent, and nothing was removed on
        // the sensor" - with "the connection dropped".
        val due = t0 + TRANSFER_ANSWER_MS
        assertFalse(
            "the watchdog pre-empted the transfer's own deadline",
            streamIsDead(due, t0, idle, awaitingAnswerSinceMs = t0),
        )
        assertTrue(
            "the transfer's deadline did not fire on the tick it came due",
            transferAfterAnswerDeadline(
                SessionTransfer.Awaiting(
                    file = "sps30-export-20260804-080223.zip",
                    rowsSent = 121_249,
                    sentAtMs = t0,
                ),
                due,
            ) is SessionTransfer.Failed,
        )
    }

    @Test fun aSendStillSendingIsJudgedByItsFramesAndNotByAnyOfThis() {
        // Precedence: while frames are going out there is no answer outstanding
        // and the transfer rule owns the link. Both arms present is a state that
        // cannot occur, and if it ever does, the moving one wins.
        assertFalse(
            streamIsDead(
                nowMs = t0 + 5 * TRANSFER_ANSWER_MS,
                lastReadingAtMs = t0,
                lastTransferProgressAtMs = t0 + 5 * TRANSFER_ANSWER_MS - 1_000,
                awaitingAnswerSinceMs = t0,
            ),
        )
    }

    @Test fun theWholeOperationSurvivesEndToEnd() {
        // Sending and waiting are one operation to the user, and it has to
        // survive as one. The measured shape: 121,249 rows at the ~500 rows/s
        // the board stores them at is about four minutes of frames, and then a
        // wait of fifteen seconds for the answer.
        val sendingFor = 245_000L
        var now = t0
        while (now < t0 + sendingFor) {
            assertFalse(
                "torn down ${now - t0} ms into the sending",
                streamIsDead(now, t0 - 500, lastTransferProgressAtMs = now - 1_000),
            )
            now += 1_000
        }
        val lastFrame = now
        var clock = lastFrame
        while (now < lastFrame + 15_100) {
            assertFalse(
                "torn down ${now - lastFrame} ms into the wait",
                streamIsDead(now, clock, idle, awaitingAnswerSinceMs = lastFrame),
            )
            clock = readingClockAfterTick(now, clock, awaitingAnswerSinceMs = lastFrame)
            now += 1_000
        }
    }
}

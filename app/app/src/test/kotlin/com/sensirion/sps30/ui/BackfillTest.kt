package com.sensirion.sps30.ui

import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an `error` from the controller does to a backfill that is running.
 *
 * The protocol's `error` says nothing about which command it answers, and the app
 * used to guess "a transfer is running, so it must be the transfer's". A backfill
 * runs on every connect and after every transport switch, so the guess was wrong
 * often, and it was wrong twice over: the refusal was hidden from the user and
 * the healthy transfer was abandoned mid-stream.
 */
class BackfillErrorTest {

    @Test fun aRefusalIsShownEvenWhileABackfillIsRunning() {
        // A `set_profile` refusal is sent with needs_control = false. Tapping a
        // mode chip shortly after connecting is an ordinary thing to do, and the
        // controller has said exactly why it will not move.
        val handling = errorHandlingFor(
            needsControl = false, copyRequested = false, backfillInFlight = true,
        )
        assertTrue("the refusal must reach the user", handling.showDialog)
    }

    @Test fun aRefusalDoesNotCancelTheTransferInFlight() {
        val handling = errorHandlingFor(
            needsControl = false, copyRequested = false, backfillInFlight = true,
        )
        assertFalse(handling.cancelsBackfill)

        // And through the queue: the session being served is still being served.
        val q = BackfillQueue()
        q.enqueue("SPS30-20260803_020000.csv", since = 10)
        q.enqueue("SPS30-20260803_010000.csv", since = 0)
        val running = q.start(nowMs = 1_000)
        assertEquals("SPS30-20260803_020000.csv", running?.name)
        q.progressed(running!!.name, nowMs = 2_000)
        if (handling.cancelsBackfill) q.finish(running.name)
        assertEquals(
            "the get_file that was actually running keeps its turn",
            running, q.current,
        )
        assertNull("and nothing else is sent alongside it", q.start(nowMs = 2_100))
    }

    @Test fun aControlRefusalIsStillShownDuringACopy() {
        // needs_control wins over every suppression, as it always did.
        assertTrue(
            errorHandlingFor(needsControl = true, copyRequested = true, backfillInFlight = true)
                .showDialog,
        )
    }

    @Test fun acopyWaitingToHearBackStillConsumesTheError() {
        // Unchanged on purpose: that state lasts from `list_files` going out until
        // the first frame comes back, so an error inside it is the copy's answer.
        val handling = errorHandlingFor(
            needsControl = false, copyRequested = true, backfillInFlight = false,
        )
        assertFalse("the copy reports it, so a dialog would say it twice", handling.showDialog)
        assertTrue(handling.failsCopy)
    }

    // --- `ref`: the errors that no longer have to be guessed about -----------

    @Test fun anErrorAboutSomethingElseLeavesTheCopyAlone() {
        // The failure this exists to stop. A refused `clean` used to look exactly
        // like a failed copy, because the frame said nothing about what it
        // answered. Now it says `clean`, and the copy is untouched - and the
        // refusal is shown, because nothing else is going to report it.
        val handling = errorHandlingFor(
            needsControl = false, copyRequested = true, backfillInFlight = true,
            ref = Wire.CMD_CLEAN,
        )
        assertFalse("a refused clean is not a failed copy", handling.failsCopy)
        assertTrue("and the user still has to hear about it", handling.showDialog)
        assertFalse(handling.cancelsBackfill)
    }

    @Test fun anErrorAboutTheCopyStillFailsIt() {
        for (ref in listOf(Wire.CMD_LIST_FILES, Wire.CMD_GET_FILE, Wire.CMD_BACKUP)) {
            val handling = errorHandlingFor(
                needsControl = false, copyRequested = true, backfillInFlight = false,
                ref = ref,
            )
            assertTrue("$ref answers the copy", handling.failsCopy)
            assertFalse("the copy reports it itself", handling.showDialog)
        }
    }

    @Test fun anUntaggedErrorBehavesExactlyAsItDidBefore() {
        // Additive: an older controller sends no `ref`, and every outcome has to
        // be the one it had when the field did not exist.
        for (needsControl in listOf(false, true)) {
            for (copyRequested in listOf(false, true)) {
                val blank = errorHandlingFor(
                    needsControl, copyRequested, backfillInFlight = true,
                    ref = Wire.ERROR_REF_NONE,
                )
                val legacy = errorHandlingFor(needsControl, copyRequested, backfillInFlight = true)
                assertEquals("needsControl=$needsControl copy=$copyRequested", legacy, blank)
                assertEquals(copyRequested, blank.failsCopy)
                assertEquals(needsControl || !copyRequested, blank.showDialog)
            }
        }
    }

    @Test fun aGetFileThatIsNeverAnsweredDoesNotStrandTheQueue() {
        // What the error-driven cancel used to be for. The deadline does the same
        // job without having to guess who an error belonged to.
        val q = BackfillQueue(stallMs = 15_000)
        q.enqueue("refused.csv", since = 0)
        q.enqueue("next.csv", since = 0)
        assertEquals("refused.csv", q.start(nowMs = 0)?.name)

        assertNull("not given up on while it might still answer", q.giveUpIfStalled(14_999))
        assertEquals("refused.csv", q.giveUpIfStalled(15_000)?.name)
        assertEquals("the rest of the queue still runs", "next.csv", q.start(15_000)?.name)
    }

    @Test fun aTransferStillDeliveringIsNeverGivenUpOn() {
        // The deadline runs from the last frame, not from the request: a full ring
        // takes half an hour to send and must not be cut off at fifteen seconds.
        val q = BackfillQueue(stallMs = 15_000)
        q.enqueue("long.csv", since = 0)
        q.start(nowMs = 0)
        var now = 0L
        repeat(100) {
            now += 10_000
            q.progressed("long.csv", now)
            assertNull(q.giveUpIfStalled(now))
        }
        assertNotNull("and it is given up on once it really does stop", q.giveUpIfStalled(now + 15_000))
    }
}

/**
 * What happens when "copy everything" meets a backfill that is already under way.
 *
 * The two callers ask for materially different things: a reconnect asks for the
 * live session from where this device's copy stops being complete, and a copy
 * asks for the whole of it. De-duplicating on the name alone threw the second
 * away and the archive got only the tail, while the user was told the copy had
 * succeeded.
 */
class BackfillQueueTest {

    private val live = "SPS30-20260803_020000.csv"
    private val older = "SPS30-20260803_010000.csv"

    @Test fun aCopyEverythingRequestIsNotDroppedForOneAlreadyQueued() {
        val q = BackfillQueue()
        q.enqueue(live, since = 5_000)
        q.enqueue(live, since = 0, forArchive = true)

        assertEquals("still one request for the session", 1, q.size)
        val queued = q.waiting.single()
        assertEquals("and it is the inclusive one", 0, queued.since)
        assertTrue(queued.forArchive)
    }

    @Test fun aCopyEverythingRequestQueuesBehindTheOneInFlight() {
        // The controller's cursor cannot be rewound, so the running transfer keeps
        // its turn and the full copy of the same session follows it.
        val q = BackfillQueue()
        q.enqueue(live, since = 5_000)
        assertEquals(live, q.start(nowMs = 0)?.name)

        q.enqueue(live, since = 0, forArchive = true)
        assertEquals(1, q.size)
        q.finish(live)
        val next = q.start(nowMs = 1_000)
        assertEquals(live, next?.name)
        assertEquals(0, next?.since)
        assertTrue("and its rows are the ones that go in the file", next!!.forArchive)
    }

    @Test fun anarrowerRequestDoesNotUndoAWiderOne() {
        val q = BackfillQueue()
        q.enqueue(live, since = 0, forArchive = true)
        q.enqueue(live, since = 5_000)

        assertEquals(1, q.size)
        assertEquals(0, q.waiting.single().since)
        assertTrue(q.waiting.single().forArchive)
    }

    @Test fun aRepeatOfWhatIsAlreadyRunningIsDropped() {
        val q = BackfillQueue()
        q.enqueue(live, since = 0, forArchive = true)
        q.start(nowMs = 0)
        q.enqueue(live, since = 0, forArchive = true)
        assertEquals("nothing to do twice", 0, q.size)
        q.enqueue(live, since = 5_000)
        assertEquals("nor anything the running one already covers", 0, q.size)
    }

    @Test fun mergingKeepsTheOrderTheSessionsWereListedIn() {
        // Newest first is the order `file_list` uses and the order that matters if
        // the session cap bites; a merge must not move a session down the queue.
        val q = BackfillQueue()
        q.enqueue(live, since = 5_000)
        q.enqueue(older, since = 0)
        q.enqueue(live, since = 0, forArchive = true)

        assertEquals(listOf(live, older), q.waiting.map { it.name })
    }

    @Test fun clearingWhatIsWaitingLeavesTheTransferInFlightAlone() {
        val q = BackfillQueue()
        q.enqueue(live, since = 0)
        q.enqueue(older, since = 0)
        q.start(nowMs = 0)
        q.clearWaiting()
        assertEquals(0, q.size)
        assertEquals("still being served", live, q.current?.name)
        assertNull("so no second get_file goes out beside it", q.start(nowMs = 1))
    }

    @Test fun anEmptyNameIsNeverQueued() {
        val q = BackfillQueue()
        q.enqueue("", since = 0)
        assertEquals(0, q.size)
    }
}

/**
 * When a session stops being worth asking for.
 *
 * The old test compared the controller's row count against what this device still
 * *holds*, and this device trims to 48 hours while the board's ring holds about
 * 56. For a session older than the retention window that comparison can never
 * come out level, so the session was asked for again on every connect, forever.
 */
class BackfillCompletenessTest {

    private val name = "SPS30-20260803_020000.csv"

    @Test fun aSessionCollectedInFullSettlesEvenAfterRetentionTrimsIt() {
        // 200k readings arrived; retention has since trimmed the copy to 170k.
        val held = TimeSeriesHistory.Coverage(
            rows = 170_000, contiguousTo = 200_000, highestIndex = 200_000,
        )
        assertTrue("the old rule could never pass", held.rows < 200_000)
        assertNull(
            "nothing to ask for",
            backfillSinceFor(controllerRows = 200_000, receivedEver = 200_000, held = held),
        )
    }

    @Test fun aSessionThisDeviceIsShortOfIsAskedForFromWhereItStops() {
        val held = TimeSeriesHistory.Coverage(rows = 900, contiguousTo = 900, highestIndex = 900)
        assertEquals(
            900,
            backfillSinceFor(controllerRows = 1_000, receivedEver = 900, held = held),
        )
    }

    @Test fun aHoleIsFetchedHoweverManyRowsHaveEverArrived() {
        // The count says complete and the copy plainly is not: 1..10 then 21..25.
        val held = TimeSeriesHistory.Coverage(rows = 15, contiguousTo = 10, highestIndex = 25)
        assertEquals(
            "asks from the edge of the hole, not the highest index seen",
            10,
            backfillSinceFor(controllerRows = 15, receivedEver = 15, held = held),
        )
    }

    @Test fun aSessionThisDeviceHasNeverSeenIsFetchedWhole() {
        assertEquals(
            0,
            backfillSinceFor(controllerRows = 1_000, receivedEver = 0, held = null),
        )
    }

    @Test fun anEmptySessionIsNotAskedFor() {
        assertNull(backfillSinceFor(controllerRows = 0, receivedEver = 0, held = null))
    }

    @Test fun aCopyAsksForEverythingWhateverIsHeld() {
        val held = TimeSeriesHistory.Coverage(rows = 1_000, contiguousTo = 1_000, highestIndex = 1_000)
        assertEquals(
            0,
            backfillSinceFor(
                controllerRows = 1_000, receivedEver = 1_000, held = held, forArchive = true,
            ),
        )
    }

    @Test fun theLiveSessionSettlesAgainstARealTrimmedHistory() {
        // The retention case end to end, on the store itself rather than a made-up
        // Coverage: ten readings arrive, retention keeps four, and the controller
        // still reports ten. What arrived is what makes it a fair comparison.
        val h = TimeSeriesHistory(retentionSeconds = 3.0)
        for (s in 0..9) {
            h.add(
                ServerMessage.Reading(
                    name, "%07d".format(s + 1), "2026-08-03T02:00:${"%02d".format(s)}-04:00",
                    mapOf("pm1_0" to 1.0, "pm2_5" to 1.0, "pm4_0" to 1.0, "pm10" to 1.0),
                ),
            )
        }
        val held = h.coverage()[name]!!
        assertEquals("retention has taken six of them", 4, held.rows)
        assertEquals(10, h.receivedRows(name))
        assertNull(
            "and the session is not asked for again",
            backfillSinceFor(
                controllerRows = 10, receivedEver = h.receivedRows(name), held = held,
            ),
        )
        // One more reading on the board than this device ever had, and it asks.
        assertEquals(
            10,
            backfillSinceFor(
                controllerRows = 11, receivedEver = h.receivedRows(name), held = held,
            ),
        )
    }

    @Test fun theLiveSessionOnlyGetsAHeadStartWhenSomethingIsVisiblyMissing() {
        assertTrue("nothing held at all", liveSessionNeedsHeadStart(null))
        assertTrue(
            "a hole this device can already see",
            liveSessionNeedsHeadStart(
                TimeSeriesHistory.Coverage(rows = 15, contiguousTo = 10, highestIndex = 25),
            ),
        )
        assertFalse(
            "a whole copy waits for file_list, one message later",
            liveSessionNeedsHeadStart(
                TimeSeriesHistory.Coverage(rows = 25, contiguousTo = 25, highestIndex = 25),
            ),
        )
    }
}

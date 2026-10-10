package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value the Data tab's whole account is drawn from.
 *
 * Its job is to make one particular sentence impossible to write: a settled
 * claim about someone's data printed while something is still running. On
 * 2026-08-06 the panel managed exactly that - "Everything the sensor is holding
 * has reached this device" over a counter climbing past 75,000 - because the
 * summary was computed from [DataInventory] alone and the copy was a state it
 * could not see. Everything asserted here is a property of the *value*, so a
 * future sentence written against it inherits the guarantee rather than having
 * to remember it.
 */
class RecordStandingTest {

    private val here = DataInventory(
        sensor = RecordCount.Counted(1, 193_121),
        here = RecordCount.Counted(1, 193_121),
        gap = RecordCount.Counted(0, 0),
        live = true,
        clocklessSessions = 0,
        unnamedSessions = 0,
    )

    private val short = here.copy(
        here = RecordCount.Counted(0, 100_000),
        gap = RecordCount.Counted(1, 93_121),
    )

    private val lostGap = RecordGap(
        startEpoch = 1_754_300_538.0,
        endEpoch = 1_754_313_532.0,
        standing = GapStanding.Lost,
        expectedReadings = 12_994,
    )

    private fun standing(
        inventory: DataInventory = here,
        gaps: RecordGapReport = RecordGapReport(sweptAtMs = 1L),
        work: RecordWork = RecordWork.None,
    ) = RecordStanding(inventory, gaps, work)

    // --- what is running -----------------------------------------------------

    @Test fun aRunningCopyIsTheWorkWhateverElseIsTrue() {
        val work = RecordWork.of(
            backup = SessionBackup.Receiving(
                received = 0,
                total = 1,
                sessions = 0,
                rows = 54_222,
                newHere = 0,
            ),
            storedImport = StoredImport.Idle,
            transfer = SessionTransfer.Idle,
            gaps = RecordGapReport(sweptAtMs = 1L),
        )
        assertEquals(
            RecordWork.Copying(session = 1, ofSessions = 1, readings = 54_222, newHere = 0),
            work,
        )
    }

    @Test fun theSessionNumberNeverRunsPastTheTotal() {
        // The row this replaces printed `received + 1` with no upper guard, so a
        // copy that had finished its last session read "session 3 of 2" for as
        // long as the archive took to close - a figure that is arithmetically
        // impossible and therefore reads as a bug in the copy itself.
        val work = RecordWork.of(
            backup = SessionBackup.Receiving(received = 2, total = 2, sessions = 2, rows = 900),
            storedImport = StoredImport.Idle,
            transfer = SessionTransfer.Idle,
            gaps = RecordGapReport(),
        ) as RecordWork.Copying
        assertEquals(2, work.session)
        assertEquals(2, work.ofSessions)
    }

    @Test fun aByteCountedArchiveIsNotReportedAsASessionCount() {
        // The legacy path sends one sized archive and puts bytes in the same two
        // fields. Reading them as sessions is how "session 1 of 4,182,993" got
        // onto a screen that is meant to be the one place nothing is ambiguous.
        val work = RecordWork.of(
            backup = SessionBackup.Receiving(
                received = 12_000,
                total = 4_182_993,
                sessions = 4,
                rows = 152_010,
                receivedAreSessions = false,
            ),
            storedImport = StoredImport.Idle,
            transfer = SessionTransfer.Idle,
            gaps = RecordGapReport(),
        ) as RecordWork.Copying
        assertEquals("a byte total is not a number of sessions", 1, work.ofSessions)
    }

    @Test fun aSweepAndAFillBothCountAsWorkAndAFillNamesItsSessions() {
        assertEquals(
            RecordWork.ClosingGaps(0),
            RecordWork.of(
                SessionBackup.Idle,
                StoredImport.Idle,
                SessionTransfer.Idle,
                RecordGapReport(sweeping = true),
            ),
        )
        assertEquals(
            RecordWork.ClosingGaps(3),
            RecordWork.of(
                SessionBackup.Idle,
                StoredImport.Idle,
                SessionTransfer.Idle,
                RecordGapReport(sweptAtMs = 1L, fetching = 3),
            ),
        )
    }

    @Test fun aTransferThatHasSentEverythingIsStillWorkUntilTheSensorAnswers() {
        // [SessionTransfer.Awaiting] is the state that exists because "the last
        // frame has gone" and "the transfer is over" are different things. A
        // summary that called it over would be settling on the controller's
        // behalf.
        assertEquals(
            RecordWork.Sending,
            RecordWork.of(
                SessionBackup.Idle,
                StoredImport.Idle,
                SessionTransfer.Awaiting(file = "a.zip", rowsSent = 10, sentAtMs = 0L),
                RecordGapReport(sweptAtMs = 1L),
            ),
        )
    }

    @Test fun anIdleRecordHasNoWork() {
        assertEquals(
            RecordWork.None,
            RecordWork.of(
                SessionBackup.Idle,
                StoredImport.Idle,
                SessionTransfer.Idle,
                RecordGapReport(sweptAtMs = 1L),
            ),
        )
    }

    @Test fun aFinishedCopyIsNotWork() {
        // Saved and Failed both stay on screen until dismissed, deliberately. A
        // result that went on counting as work would leave the summary saying
        // the figures were moving for as long as the user left it there.
        for (finished in listOf(
            SessionBackup.Saved(location = "Downloads/SPS30/x.zip", bytes = 1, sessions = 1, rows = 1),
            SessionBackup.Failed("nothing came back"),
        )) {
            assertEquals(
                "$finished is over",
                RecordWork.None,
                RecordWork.of(
                    finished,
                    StoredImport.Idle,
                    SessionTransfer.Idle,
                    RecordGapReport(sweptAtMs = 1L),
                ),
            )
        }
    }

    // --- parity may only be called settled when nothing is moving ------------

    @Test fun parityWithNothingRunningIsSettled() {
        assertTrue(standing().atParityAndSettled)
        assertFalse(standing().moving)
    }

    @Test fun parityWithAnythingRunningIsNotSettled() {
        // The captain's screen, as a property. The arithmetic answer is still
        // "everything is here" and stays true; what is denied is the right to
        // call it finished.
        val running = standing(
            work = RecordWork.Copying(session = 1, ofSessions = 1, readings = 54_222, newHere = 0),
        )
        assertTrue("the arithmetic is unchanged", running.inventory.everythingIsHere)
        assertTrue(running.moving)
        assertFalse("but no sentence may call it settled", running.atParityAndSettled)
    }

    @Test fun aGapIsNeverSettledEvenWithNothingRunning() {
        assertFalse(standing(inventory = short).atParityAndSettled)
    }

    // --- what is gone is a separate question with its own honest unknown -----

    @Test fun lossIsUnansweredUntilSomethingHasSwept() {
        val fresh = standing(gaps = RecordGapReport())
        assertEquals(null, fresh.readingsGoneForGood)
        assertFalse("an unswept record has not established anything", fresh.nothingLost)
    }

    @Test fun aSweptCleanRecordAnswersTheLossQuestionWithNo() {
        val clean = standing(gaps = RecordGapReport(sweptAtMs = 1L))
        assertEquals(0, clean.readingsGoneForGood)
        assertTrue(clean.nothingLost)
    }

    @Test fun lossSurvivesParityWithTheSensor() {
        // The two questions are independent and this is the case that proves it:
        // every reading the sensor still holds is here, and 12,994 that it does
        // not are gone. A panel with one answer has to get one of them wrong.
        val bereaved = standing(gaps = RecordGapReport(gaps = listOf(lostGap), sweptAtMs = 1L))
        assertTrue("parity with the sensor holds", bereaved.inventory.everythingIsHere)
        assertEquals(12_994, bereaved.readingsGoneForGood)
        assertFalse("and the record is not whole", bereaved.nothingLost)
    }

    @Test fun anUnjudgedHoleForbidsBothAnswers() {
        val open = standing(
            gaps = RecordGapReport(
                gaps = listOf(lostGap.copy(standing = GapStanding.Unknown("nothing has looked"))),
                sweptAtMs = 1L,
            ),
        )
        assertEquals("nothing may be called lost on a guess", 0, open.readingsGoneForGood)
        assertFalse("and nothing may be called safe either", open.nothingLost)
        assertEquals(1, open.holesNotJudged)
    }
}

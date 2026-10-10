package com.sensirion.sps30.ui

import com.sensirion.sps30.data.DataInventory
import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordCount
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.RecordStanding
import com.sensirion.sps30.data.RecordWork
import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences the Data tab's account of the record is read as.
 *
 * The rendering is separated from the model for the same reason
 * [transferHeadline] is: what these lines say is the part that can be got wrong
 * in a way no arithmetic test would notice, and it is what the user actually
 * has. Two rules run through the lot, and both come from a real screen:
 *
 *  - **An honest unknown beats a wrong certainty.** So an unknown never acquires
 *    a number and never turns into a claim about the sensor.
 *  - **Nothing may be called finished while something is running.** On
 *    2026-08-06 this panel said "Everything the sensor is holding has reached
 *    this device" directly above a counter climbing past 75,000. Both were true;
 *    together they were unreadable. See [RecordWork].
 */
class DataInventoryTextTest {

    private fun unknownInventory(because: String): DataInventory {
        val unknown = RecordCount.Unknown(because)
        return DataInventory(
            sensor = unknown,
            here = unknown,
            gap = unknown,
            live = false,
            clocklessSessions = 0,
            unnamedSessions = 0,
        )
    }

    private fun counted(sensor: Long, here: Long, live: Boolean = true): DataInventory =
        DataInventory(
            sensor = RecordCount.Counted(2, sensor),
            here = RecordCount.Counted(if (sensor == here) 2 else 1, here),
            gap = RecordCount.Counted(if (sensor == here) 0 else 1, sensor - here),
            live = live,
            clocklessSessions = 0,
            unnamedSessions = 0,
        )

    /** Nothing running, nothing lost, and the loss question actually answered. */
    private fun settled(inventory: DataInventory) = RecordStanding(
        inventory = inventory,
        gaps = RecordGapReport(sweptAtMs = 1L),
        work = RecordWork.None,
    )

    private fun summaryOf(inventory: DataInventory) = sensorSideSummary(settled(inventory))

    private val lostGap = RecordGap(
        startEpoch = 1_754_300_538.0,
        endEpoch = 1_754_313_532.0,
        standing = GapStanding.Lost,
        expectedReadings = 12_994,
    )

    private val fillableGap = lostGap.copy(standing = GapStanding.Fillable(1_754_300_538.0))
    private val unjudgedGap = lostGap.copy(standing = GapStanding.Unknown("nothing has looked"))

    /** Every state that is not "nothing is happening", for the invariant sweeps. */
    private val everyKindOfWork = listOf(
        RecordWork.Asking,
        RecordWork.Copying(session = 1, ofSessions = 1, readings = 54_222, newHere = 0),
        RecordWork.Loading,
        RecordWork.ClosingGaps(sessions = 0),
        RecordWork.ClosingGaps(sessions = 3),
        RecordWork.Sending,
    )

    // --- the figures themselves ---------------------------------------------

    @Test fun anUnknownRendersAsItsReasonAndNeverAsANumber() {
        val text = inventoryValue(RecordCount.Unknown("no sensor has been reached yet"))
        assertEquals("no sensor has been reached yet", text)
        assertFalse("a reason must not contain a count: $text", text.any(Char::isDigit))
    }

    @Test fun aCountCarriesBothFiguresWithThousandsSeparators() {
        val text = inventoryValue(RecordCount.Counted(sessions = 12, readings = 148_893))
        assertTrue("sessions are named: $text", text.contains("12 session"))
        assertTrue("readings are grouped: $text", text.contains("148,893"))
    }

    @Test fun nothingIsSaidInWordsRatherThanAsTwoZeroes() {
        // "0 session(s) · 0 readings" is a row of noughts to scan past. The one
        // word says the same thing and cannot be misread as an unfinished count.
        assertEquals("nothing", inventoryValue(RecordCount.Counted(0, 0)))
    }

    // --- claim one: has this device got what the sensor holds? ---------------

    @Test fun anUnknownGapNeverReadsAsACompleteCopy() {
        // The failure this whole block was built against: an audit of the app's
        // dynamic messages found lines claiming "it is not there" where the truth
        // was "I have not checked". The summary is the one sentence a hurried
        // reader takes away, so it is the line that must not do it.
        val summary = summaryOf(unknownInventory("connected - waiting for the sensor to say"))
        assertTrue("it must say the answer is not known: $summary", summary.contains("not known"))
        assertFalse(
            "and it must not congratulate anybody: $summary",
            summary.contains("Everything"),
        )
    }

    @Test fun aClosedGapIsStatedPlainly() {
        val summary = summaryOf(counted(sensor = 100, here = 100))
        assertTrue(summary, summary.contains("Everything the sensor is holding is here"))
    }

    @Test fun anOpenGapPointsAtTheOneActionAndSaysWhatItCosts() {
        val summary = summaryOf(counted(sensor = 100, here = 40))
        assertTrue("it must name the readings: $summary", summary.contains("60 readings"))
        assertTrue("it must name the action: $summary", summary.contains("Copy sessions"))
        // The single most reassuring fact about the copy, and the reason nobody
        // should hesitate over it: it is a read, and the sensor keeps everything.
        assertTrue("it must say the sensor keeps its own: $summary", summary.contains("keeps"))
    }

    @Test fun anEmptySensorDoesNotReadAsAMissingCopy() {
        val empty = DataInventory(
            sensor = RecordCount.Counted(0, 0),
            here = RecordCount.Counted(0, 0),
            gap = RecordCount.Counted(0, 0),
            live = true,
            clocklessSessions = 0,
            unnamedSessions = 0,
        )
        val summary = summaryOf(empty)
        assertTrue(
            "with nothing on the sensor, \"everything has reached this device\" is " +
                "technically true and reads as an achievement: $summary",
            summary.contains("not holding any"),
        )
    }

    @Test fun parityOnALiveLinkSaysTheFiguresAreStillMoving() {
        // The heart of it. A sensor that is recording has no settled total, so a
        // sentence that sounds settled is a promise nothing can keep. The old one
        // - "Everything the sensor is holding has reached this device." - was
        // exactly that shape, and it is the sentence the captain read while a
        // counter climbed underneath it.
        val summary = summaryOf(counted(sensor = 100, here = 100))
        assertTrue("it must say the figures are live: $summary", summary.contains("live"))
        assertFalse(
            "and it must not use the finished-sounding wording it replaced: $summary",
            summary.contains("has reached this device"),
        )
    }

    @Test fun parityOffADeadLinkIsSaidInThePastTense() {
        val summary = summaryOf(counted(sensor = 100, here = 100, live = false))
        assertTrue(
            "a figure from a link that has gone must not be stated in the present: $summary",
            summary.contains("last heard from it"),
        )
        assertFalse(
            "and nothing about it is live: $summary",
            summary.contains("are live"),
        )
    }

    // --- claim one against claim two: they are different claims --------------

    @Test fun theTwoClaimsAreAnsweredSeparatelyAndCanDisagree() {
        // The captain's 2026-08-04, in the state it left the tablet in: this
        // device holds every reading the sensor still has, and three and a half
        // hours the sensor destroyed are missing for ever. One sentence cannot
        // say that, which is precisely why there are two.
        val standing = RecordStanding(
            inventory = counted(sensor = 100, here = 100),
            gaps = RecordGapReport(gaps = listOf(lostGap), sweptAtMs = 1L),
            work = RecordWork.None,
        )
        val sensorSide = sensorSideSummary(standing)
        val everRecorded = everRecordedSummary(standing.gaps)

        assertTrue("parity with the sensor still holds: $sensorSide", sensorSide.contains("here"))
        assertTrue(
            "and the loss must be stated, not swallowed by it: $everRecorded",
            everRecorded.contains("gone for good"),
        )
        assertTrue(
            "with the figure, so it can be weighed: $everRecorded",
            everRecorded.contains("12,994"),
        )
        assertNotEquals(
            "two claims that render identically are one claim",
            sensorSide,
            everRecorded,
        )
    }

    // --- the summary and anything running may never contradict ---------------

    @Test fun everyKindOfWorkIsSaidOutLoudWhateverTheFiguresAre() {
        // The invariant, swept over every combination rather than sampled: no
        // arrangement of the figures may suppress the fact that something is
        // running. A future arm added to either `when` is caught here.
        val inventories = listOf(
            counted(sensor = 100, here = 100),
            counted(sensor = 100, here = 40),
            counted(sensor = 100, here = 100, live = false),
            counted(sensor = 0, here = 0),
            unknownInventory("connected - waiting for the sensor to say"),
        )
        for (inventory in inventories) {
            for (work in everyKindOfWork) {
                val summary = sensorSideSummary(
                    RecordStanding(inventory, RecordGapReport(sweptAtMs = 1L), work),
                )
                assertTrue(
                    "$work left no trace in the summary, so a climbing figure under it " +
                        "would have nothing explaining it: $summary",
                    summary.contains("still moving"),
                )
            }
        }
    }

    @Test fun aRunningCopyNeverLetsTheSummaryLeadWithParity() {
        // Exactly the captain's screen: the inventory is genuinely at parity and
        // an archive copy is genuinely running. The panel may say both. What it
        // may not do is put the settled half first, which is what made the two
        // read as a contradiction rather than as two facts.
        val standing = RecordStanding(
            inventory = counted(sensor = 193_121, here = 193_121),
            gaps = RecordGapReport(sweptAtMs = 1L),
            work = RecordWork.Copying(session = 1, ofSessions = 1, readings = 54_222, newHere = 0),
        )
        val summary = sensorSideSummary(standing)
        assertTrue("the copy must be named first: $summary", summary.startsWith("A copy is running"))
        assertTrue(
            "and the parity must still be stated rather than hidden: $summary",
            summary.contains("Everything the sensor is holding is here"),
        )
    }

    @Test fun theLivenessNoteIsNotRepeatedOnTopOfAWorkClause() {
        // Both say the figures are not settled. Saying it twice in one paragraph
        // is how a sentence stops being read.
        val standing = RecordStanding(
            inventory = counted(sensor = 100, here = 100),
            gaps = RecordGapReport(sweptAtMs = 1L),
            work = RecordWork.Copying(session = 1, ofSessions = 1, readings = 10, newHere = 0),
        )
        val summary = sensorSideSummary(standing)
        assertFalse(summary, summary.contains("Both figures are live"))
    }

    // --- claim two: is any of it gone? ---------------------------------------

    @Test fun anUnsweptRecordSaysSoRatherThanReportingNoLoss() {
        // An empty list of holes before anything has looked is not the absence of
        // holes. Rendering the two the same way is how three hours went missing
        // with nothing on screen to say so. Same rule as [RecordCount.Unknown].
        val fresh = RecordGapReport()
        assertEquals("not compared against the sensor yet", goneForGoodValue(fresh))
        assertFalse("nothing may be known yet", goneForGoodIsKnown(fresh))
        assertTrue(everRecordedSummary(fresh), everRecordedSummary(fresh).contains("not been checked"))
    }

    @Test fun aSweptAndCleanRecordIsAllowedToSayNothingIsGone() {
        val swept = RecordGapReport(sweptAtMs = 1L)
        assertEquals("nothing", goneForGoodValue(swept))
        assertTrue("this one is established", goneForGoodIsKnown(swept))
        val summary = everRecordedSummary(swept)
        // Bounded by what the detection can see: holes are found *between* held
        // readings, so the claim is about the record's own span and never about
        // everything that preceded it.
        assertTrue("it must not overclaim: $summary", summary.contains("between its first"))
    }

    @Test fun lostReadingsAreStatedWithNoRemedyOffered() {
        val report = RecordGapReport(gaps = listOf(lostGap, lostGap), sweptAtMs = 1L)
        assertEquals("about 25,988 readings, in 2 gap(s)", goneForGoodValue(report))
        val summary = everRecordedSummary(report)
        assertTrue("it must say it plainly: $summary", summary.contains("gone for good"))
        assertTrue("and why: $summary", summary.contains("recorded over"))
        assertTrue(
            "and that there is nothing to be done, because there is not: $summary",
            summary.contains("nothing can bring them back"),
        )
        assertFalse(
            "offering a fix for data that does not exist is the one unforgivable line: $summary",
            summary.contains("Copy sessions"),
        )
    }

    @Test fun holesTheSensorStillHasNameTheButtonThatClosesThem() {
        val report = RecordGapReport(gaps = listOf(fillableGap), sweptAtMs = 1L)
        val summary = everRecordedSummary(report)
        assertTrue("the sensor still has it: $summary", summary.contains("still has all of it"))
        assertTrue("so the remedy is named: $summary", summary.contains("Find and fill gaps now"))
    }

    @Test fun anUnjudgedHoleIsNeverCountedAsLostAndNeverAsSafe() {
        val report = RecordGapReport(gaps = listOf(unjudgedGap), sweptAtMs = 1L)
        val value = goneForGoodValue(report)
        assertTrue("it must not read as a settled nought: $value", value.contains("not yet judged"))
        assertFalse("nor as established: $value", goneForGoodIsKnown(report))
        val summary = everRecordedSummary(report)
        assertFalse("the word must not appear on a guess: $summary", summary.contains("gone for good"))
        assertTrue("and the way to settle it is named: $summary", summary.contains("settles it"))
    }

    @Test fun lostAndUnjudgedHolesAreBothReportedRatherThanTheWorseOneWinning() {
        val report = RecordGapReport(gaps = listOf(lostGap, unjudgedGap), sweptAtMs = 1L)
        val value = goneForGoodValue(report)
        assertTrue("the loss is counted: $value", value.contains("12,994"))
        assertTrue("and the open question is kept: $value", value.contains("1 gap(s) not yet judged"))
    }

    @Test fun theTwoGapVerdictsAgree() {
        // The record's panel gives the verdict and the list below gives the
        // detail, and they are two functions writing about the same holes. Two
        // answers to "is any of my data gone" on one screen would be worse than
        // either, so every state is checked for the one disagreement that
        // matters: whether the word "gone" is used at all.
        val states = listOf(
            RecordGapReport(),
            RecordGapReport(sweeping = true),
            RecordGapReport(sweptAtMs = 1L),
            RecordGapReport(gaps = listOf(fillableGap), sweptAtMs = 1L),
            RecordGapReport(gaps = listOf(unjudgedGap), sweptAtMs = 1L),
            RecordGapReport(gaps = listOf(lostGap), sweptAtMs = 1L),
            RecordGapReport(gaps = listOf(lostGap, fillableGap, unjudgedGap), sweptAtMs = 1L),
        )
        for (report in states) {
            val panel = everRecordedSummary(report)
            val list = gapSummary(report)
            assertEquals(
                "one of these says data is gone and the other does not, for $report:\n" +
                    "  panel: $panel\n  list:  $list",
                report.lost > 0,
                panel.contains("gone for good"),
            )
            assertEquals(
                "the list's verdict must match it, for $report:\n" +
                    "  panel: $panel\n  list:  $list",
                report.lost > 0,
                list.contains("beyond recovery"),
            )
        }
    }

    // --- a sensor that keeps recording cannot make the panel lie -------------

    @Test fun aGrowingSensorRecordOpensTheGapRatherThanClaimingCompleteness() {
        // The listing is what the sensor said a moment ago; `storage` is what it
        // is holding now. A board recording at 1 Hz moves the second and not the
        // first, and the panel has to follow the live figure - a comparison that
        // quietly pinned itself to the stale one would report a permanent, false
        // parity on a sensor that was pulling ahead every second.
        val listing = listOf(entry("s1", rows = 193_121))
        val claims = mutableListOf<Boolean>()
        for (extra in 0..5) {
            val inventory = DataInventory.of(
                connected = true,
                storage = storage(sessions = 1, readings = 193_121 + extra),
                listing = listing,
            ) { 193_121 }
            val summary = summaryOf(inventory)
            claims.add(summary.contains("Everything the sensor is holding is here"))
            if (extra > 0) {
                assertTrue(
                    "$extra readings behind, and the panel said: $summary",
                    summary.contains("$extra readings are on the sensor and not here yet"),
                )
            }
        }
        assertEquals(
            "completeness may be claimed at the one instant it is true and at no other",
            listOf(true, false, false, false, false, false),
            claims,
        )
    }

    @Test fun readingsThisDeviceHoldsThatTheSensorHasDroppedDoNotBuyBackAGap() {
        // The device is holding more of a session than the sensor still has, and
        // is short of a second session entirely. Letting the surplus offset the
        // shortfall would net out to a false parity - the arithmetic answer to
        // "everything that ever existed" masquerading as the answer to
        // "everything the sensor holds".
        val inventory = DataInventory.of(
            connected = true,
            storage = storage(sessions = 2, readings = 150_000),
            listing = listOf(entry("s1", rows = 50_000), entry("s2", rows = 100_000)),
        ) { name -> if (name == "s1") 80_000 else 0 }
        val summary = summaryOf(inventory)
        assertFalse("it must not read as complete: $summary", summary.contains("Everything"))
        assertTrue(
            "and the shortfall is the sensor's own, not the net: $summary",
            summary.contains("100,000 readings are on the sensor and not here yet"),
        )
    }

    private fun entry(name: String, rows: Int) = ServerMessage.FileEntry(
        name = name,
        rows = rows,
        bytes = rows.toLong() * 130,
        start = "2026-08-06T00:00:00",
    )

    private fun storage(sessions: Int, readings: Int) = ServerMessage.Storage(
        usedPages = readings / 100,
        totalPages = 40_000,
        readings = readings,
        sessions = sessions,
        fraction = readings / 4_000_000.0,
        warnAt = 0.9,
        warn = false,
        ready = true,
    )
}

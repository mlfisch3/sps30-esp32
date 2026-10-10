package com.sensirion.sps30.ui

import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.RecordGaps
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.SensorRetention
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a hole in time into the `get_file` that closes it.
 *
 * The half of the 2026-08-04 failure that lives in this file is the one that made
 * the reconnect backfill useless: the app reattached at 11:18, received every
 * reading from then on, and had a copy of the session with no hole *in* it - only
 * a copy that started three and a half hours late. `contiguousTo` counts up from
 * the lowest index held and so reported that copy complete, and
 * `get_file since_index=contiguousTo` then asked for readings the device already
 * had. See [gapFillSince].
 */
class GapFillTest {

    private fun at(iso: String): Double = TimeSeriesHistory.epochOf(iso)!!

    private val gapOpens = at("2026-08-04T07:42:18-04:00")
    private val gapCloses = at("2026-08-04T11:18:52-04:00")

    private val liveSession = "SPS30-20260804_050000.csv"
    private val liveStart = "2026-08-04T05:00:00-04:00"

    private fun entry(name: String, start: String?, rows: Int = 188_251, relative: Boolean = false) =
        ServerMessage.FileEntry(name, rows, rows * 10L, start, relative)

    private fun theHole(standing: GapStanding) =
        RecordGap(gapOpens, gapCloses, standing, expectedReadings = 12_993)

    // --- where a fill has to start -------------------------------------------

    @Test fun aCopyThatStartsLateIsAskedForFromTheBeginning() {
        // The captain's session as the tablet held it: indices 12,994 upwards,
        // unbroken, and the whole of the front missing. There is no hole for
        // `contiguousTo` to find, and the protocol's one cursor runs forwards, so
        // reaching the front means asking for the session.
        val held = TimeSeriesHistory.Coverage(
            rows = 175_257, contiguousTo = 188_250, highestIndex = 188_250, lowestIndex = 12_994,
        )
        assertNotEquals(
            "asking from where the copy stops being whole fetches nothing at all",
            0,
            held.contiguousTo,
        )
        assertEquals(0, gapFillSince(held))
    }

    @Test fun aHoleInsideACopyIsAskedForFromWhereItStops() {
        // The ordinary shape, and the one the reconnect backfill already handles.
        // No more expensive than a reconnect, so it stays that way.
        val held = TimeSeriesHistory.Coverage(
            rows = 1_300, contiguousTo = 400, highestIndex = 900, lowestIndex = 0,
        )
        assertEquals(400, gapFillSince(held))
    }

    @Test fun aSessionThisDeviceHasNothingOfIsAskedForWhole() {
        assertEquals(0, gapFillSince(null))
        assertEquals(
            0,
            gapFillSince(TimeSeriesHistory.Coverage(rows = 0, contiguousTo = 0, lowestIndex = 0)),
        )
    }

    // --- which sessions a hole falls in --------------------------------------

    @Test fun aFillableHoleNamesTheSessionThatCoversIt() {
        val plan = gapFillPlan(
            gaps = listOf(theHole(GapStanding.Fillable(gapOpens))),
            files = listOf(entry(liveSession, liveStart)),
            held = mapOf(
                liveSession to TimeSeriesHistory.Coverage(
                    rows = 175_257, contiguousTo = 188_250,
                    highestIndex = 188_250, lowestIndex = 12_994,
                ),
            ),
        )
        assertEquals(1, plan.size)
        assertEquals(liveSession, plan.single().name)
        assertEquals("from the beginning, or it fetches nothing", 0, plan.single().since)
        assertTrue(
            "a fill puts readings in the plot and must never write the backup zip",
            !plan.single().forArchive,
        )
    }

    @Test fun aHoleTheRingHasWrappedPastAsksForNothing() {
        // The requirement in one assertion: where the data no longer exists, no
        // transfer is started, and the user is told rather than left watching a
        // fetch that cannot succeed.
        assertTrue(
            gapFillPlan(
                gaps = listOf(theHole(GapStanding.Lost)),
                files = listOf(entry("SPS30-20260804_142900.csv", "2026-08-04T14:29:00-04:00")),
                held = emptyMap(),
            ).isEmpty(),
        )
    }

    @Test fun aHoleNobodyHasJudgedAsksForNothingEither() {
        assertTrue(
            gapFillPlan(
                gaps = listOf(theHole(GapStanding.Unknown("the sensor has not said"))),
                files = listOf(entry(liveSession, liveStart)),
                held = emptyMap(),
            ).isEmpty(),
        )
    }

    @Test fun aSweepWithNoGapsDoesNothingAtAll() {
        // The cheap case, which is nearly every sweep: nothing is asked for, so
        // the reconciliation costs one listing and no transfer.
        val unbroken = DoubleArray(7_200) { gapOpens + it }
        val retention = SensorRetention.of(listOf(entry(liveSession, liveStart)), fullyNamed = true)
        val gaps = RecordGaps.of(unbroken, cadenceSeconds = 1.0, retention = retention)
        assertTrue(gaps.isEmpty())
        assertTrue(
            gapFillPlan(gaps, listOf(entry(liveSession, liveStart)), emptyMap()).isEmpty(),
        )
    }

    @Test fun aHoleSpanningTwoSessionsNamesBoth() {
        // The board reboots and starts a new session; the app was away across the
        // seam. Both sides of it have to be asked for, and the mapping needs
        // nothing but the two start times - the board's record is contiguous, so
        // one session runs until the next begins.
        val older = entry("SPS30-20260804_050000.csv", "2026-08-04T05:00:00-04:00")
        val newer = entry("SPS30-20260804_090000.csv", "2026-08-04T09:00:00-04:00")
        val plan = gapFillPlan(
            gaps = listOf(theHole(GapStanding.Fillable(gapOpens))),
            files = listOf(newer, older), // newest first, as the controller sends it
            held = emptyMap(),
        )
        assertEquals(setOf(older.name, newer.name), plan.map { it.name }.toSet())
    }

    @Test fun aSessionThatEndedBeforeTheHoleIsLeftAlone() {
        val old = entry("SPS30-20260801_000000.csv", "2026-08-01T00:00:00-04:00")
        val live = entry(liveSession, liveStart)
        val plan = gapFillPlan(
            gaps = listOf(theHole(GapStanding.Fillable(gapOpens))),
            files = listOf(live, old),
            held = emptyMap(),
        )
        assertEquals(listOf(liveSession), plan.map { it.name })
    }

    @Test fun aClocklessSessionIsNeverNamedByAFill() {
        // It has no place on an axis of dates, so nothing can say it covers a
        // stretch of one. Its readings still reach the plot by the ordinary
        // backfill; what must not happen is a fill claiming to close a dated hole
        // by fetching a session that cannot be dated.
        val plan = gapFillPlan(
            gaps = listOf(theHole(GapStanding.Fillable(gapOpens))),
            files = listOf(entry("SPS30-R000004AC-00000817.csv", "", relative = true)),
            held = emptyMap(),
        )
        assertTrue(plan.isEmpty())
    }

    @Test fun twoHolesInOneSessionAreOneTransfer() {
        val plan = gapFillPlan(
            gaps = listOf(
                theHole(GapStanding.Fillable(gapOpens)),
                RecordGap(gapCloses + 600, gapCloses + 9_000, GapStanding.Fillable(gapCloses + 600), 8_399),
            ),
            files = listOf(entry(liveSession, liveStart)),
            held = mapOf(
                liveSession to TimeSeriesHistory.Coverage(
                    rows = 100, contiguousTo = 400, highestIndex = 900, lowestIndex = 0,
                ),
            ),
        )
        assertEquals(1, plan.size)
        assertEquals(400, plan.single().since)
    }

    // --- one ask per hole -----------------------------------------------------

    @Test fun aGapKeepsItsNameAcrossSweeps() {
        // What stops an automatic sweep re-fetching a session every half hour for
        // a hole the sensor will not actually give up.
        val once = theHole(GapStanding.Fillable(gapOpens))
        val again = theHole(GapStanding.Fillable(gapOpens))
        assertEquals(gapKey(once), gapKey(again))
        assertNotEquals(
            gapKey(once),
            gapKey(RecordGap(gapOpens, gapCloses + 1, GapStanding.Fillable(gapOpens), 12_994)),
        )
    }

    // --- the promise that a fill can only add --------------------------------

    @Test fun fillingAddsWhatIsMissingAndTouchesNothingElse() {
        // The captain's data must never be damaged by a fill. The transfer goes in
        // through the same door a backfill uses, so this is the merge's own
        // guarantee being asserted on the shape a fill actually has: the sensor
        // re-sends everything after the cursor, including the readings this device
        // already holds.
        val history = TimeSeriesHistory(nowSeconds = { gapCloses + 86_400 })
        val session = liveSession

        // What the app held: the tail of the session, nothing before it.
        assertEquals(1_000, history.addRows(session, COLUMNS, rows(1_000 until 2_000), explicit = true))
        val before = history.snapshot()
        assertEquals(1_000, before.size)

        // What the fill fetches: the whole session from index 0, which is what
        // `gapFillSince` asks for when the front is missing.
        val added = history.addRows(session, COLUMNS, rows(0 until 2_000), explicit = true)

        assertEquals("only the readings that were actually missing", 1_000, added)
        assertEquals(2_000, history.size)

        val after = history.snapshot()
        // Nothing lost: every reading held before is still held, with its value.
        val values = after.associateBy({ it.index }, { it.values["pm2_5"] })
        for (sample in before) {
            assertEquals(
                "reading ${sample.index} must survive a fill unchanged",
                sample.values["pm2_5"],
                values[sample.index],
            )
        }
        // Nothing duplicated, and nothing out of order.
        assertEquals(2_000, after.map { it.index }.toSet().size)
        assertEquals((0 until 2_000).toList(), after.map { it.index })
        assertEquals(
            "which is what makes running the same fill twice free",
            0,
            history.addRows(session, COLUMNS, rows(0 until 2_000), explicit = true),
        )
        assertEquals(2_000, history.size)

        // And the hole is gone.
        val retention = SensorRetention.of(
            listOf(entry(session, liveStart)),
            fullyNamed = true,
        )
        assertTrue(
            RecordGaps.of(history.placedEpochs(), 1.0, retention).isEmpty(),
        )
    }

    private val COLUMNS = Wire.CSV_COLUMNS

    /** One reading a second from the moment the hole opened. */
    private fun rows(indices: IntRange): List<List<String>> = indices.map { i ->
        val stamp = java.time.Instant.ofEpochSecond((gapOpens + i).toLong())
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        listOf("%07d".format(i), stamp) + List(Wire.FIELDS.size) { "1.5" } + ""
    }
}

/**
 * The words the Data tab puts on a hole.
 *
 * They are the whole of the captain's third requirement: a hole the sensor can
 * still supply and a hole it has recorded over must be unmistakable for one
 * another when skimmed, and "there is nothing you can do about this" must never
 * be a softened way of saying "not fetched yet".
 */
class GapWordingTest {

    private fun at(iso: String) = TimeSeriesHistory.epochOf(iso)!!

    private val opens = at("2026-08-04T07:42:18-04:00")
    private val closes = at("2026-08-04T11:18:52-04:00")

    private fun gap(standing: GapStanding, from: Double = opens, to: Double = closes) =
        RecordGap(from, to, standing, expectedReadings = 12_993)

    @Test fun aLostHoleSaysThereIsNothingToBeDone() {
        val said = gapStandingLabel(gap(GapStanding.Lost))
        assertTrue(said, said.contains("gone"))
        assertTrue("and why, so it does not read as a failure to try", said.contains("recorded over"))
    }

    @Test fun anUnfetchedHoleNeverReadsAsALostOne() {
        val fillable = gapStandingLabel(gap(GapStanding.Fillable(opens)))
        val unknown = gapStandingLabel(gap(GapStanding.Unknown("the sensor has not said")))
        for (said in listOf(fillable, unknown)) {
            assertTrue(said, !said.contains("gone"))
        }
        assertTrue(fillable.contains("still has this"))
        assertTrue(unknown.startsWith("not known yet"))
    }

    @Test fun aHalfEatenHoleClaimsBackOnlyWhatIsThere() {
        val said = gapStandingLabel(gap(GapStanding.Fillable(opens + 1_800)))
        assertTrue(said, said.contains("before that it is gone"))
    }

    @Test fun anEmptyListBeforeASweepDoesNotReadAsAllClear() {
        assertTrue(gapSummary(RecordGapReport()).contains("Not compared"))
        assertTrue(gapSummary(RecordGapReport(sweptAtMs = 1L)).contains("No gaps"))
    }

    @Test fun theSummaryLeadsWithWhatCannotBeRecovered() {
        val said = gapSummary(
            RecordGapReport(
                gaps = listOf(gap(GapStanding.Lost), gap(GapStanding.Fillable(opens))),
                sweptAtMs = 1L,
            ),
        )
        assertTrue(said, said.contains("beyond recovery"))
        assertTrue("and how much of it", said.contains("12,993"))
    }

    @Test fun aHoleInsideOneDayIsNotDatedTwice() {
        assertEquals("Aug 4 07:42:18 → 11:18:52", gapWhen(gap(GapStanding.Lost)))
    }

    @Test fun aHoleAcrossMidnightIsDatedAtBothEnds() {
        val said = gapWhen(
            gap(
                GapStanding.Lost,
                from = at("2026-08-04T23:50:00-04:00"),
                to = at("2026-08-05T00:20:00-04:00"),
            ),
        )
        assertEquals("Aug 4 23:50:00 → Aug 5 00:20:00", said)
    }
}

/**
 * When a reconciliation sweep is allowed to run.
 *
 * The cadence is half an hour, chosen against the thing it is racing rather than
 * against nothing: the board's ring holds about 2.36 days at 1 Hz, so a hole gets
 * on the order of a hundred chances to be noticed and closed before the readings
 * that would close it are overwritten. What is asserted here is the part that can
 * be got wrong silently - the order of the arms, and that a sweep never competes
 * for the controller's one cursor.
 */
class SweepCadenceTest {

    private val start = 1_000_000L

    private fun step(
        nowMs: Long,
        dueAtMs: Long = start,
        running: Boolean = false,
        startedAtMs: Long = 0L,
        connected: Boolean = true,
        linkBusy: Boolean = false,
    ) = sweepStep(nowMs, dueAtMs, running, startedAtMs, connected, linkBusy)

    @Test fun nothingHappensBeforeItIsDue() {
        assertEquals(SweepAction.WAIT, step(nowMs = start - 1))
        assertEquals(SweepAction.START, step(nowMs = start))
    }

    @Test fun nothingIsScheduledUntilSomethingSchedulesIt() {
        // Zero is "no sweep pending", not "due at the epoch". A fresh process that
        // has never connected must not sweep on its first tick.
        assertEquals(SweepAction.WAIT, step(nowMs = start, dueAtMs = 0L))
    }

    @Test fun aSweepAlreadyRunningIsNotStartedAgain() {
        // Asked before "is it due", so a walk still in progress when the next
        // sweep falls due is left alone rather than started alongside itself. Two
        // listings interleaving would reconcile against a listing half of each.
        assertEquals(
            SweepAction.WAIT,
            step(
                nowMs = start + RECONCILE_GIVE_UP_MS - 1,
                dueAtMs = start, // due, and would otherwise start
                running = true,
                startedAtMs = start,
            ),
        )
    }

    @Test fun aSweepThatIsNeverAnsweredIsGivenUpOn() {
        // Without this, one swallowed `list_files` stops reconciliation for the
        // life of the process: nothing else starts a sweep while one is running.
        assertEquals(
            SweepAction.GIVE_UP,
            step(
                nowMs = start + RECONCILE_GIVE_UP_MS,
                running = true,
                startedAtMs = start,
            ),
        )
    }

    @Test fun aBusyLinkIsStoodDownFromRatherThanQueuedBehind() {
        // The controller serves `get_file` from one cursor. A sweep behind a copy
        // would hold it away from the transfer the user is waiting on, and would
        // be asking about a listing that is stale before it arrives.
        assertEquals(SweepAction.STAND_DOWN, step(nowMs = start, linkBusy = true))
        assertEquals(SweepAction.STAND_DOWN, step(nowMs = start, connected = false))
    }

    @Test fun theCadenceIsTheOneThatWasChosen() {
        // Stated as an assertion so that changing it is a deliberate act. Half an
        // hour against a ring that wraps in 2.36 days at 1 Hz.
        assertEquals(30 * 60_000L, RECONCILE_EVERY_MS)
        assertTrue(
            "many chances to catch a hole before the sensor records over it",
            RECONCILE_EVERY_MS * 100 < 2.36 * 86_400_000L,
        )
    }
}

/**
 * Where a hole lands on the plot.
 *
 * The geometry is separated from the shading for the usual reason: clipping and
 * the minimum visible width are the parts that can be got wrong, and a band that
 * rounds away to nothing when the user zooms out is a gap the plot has gone back
 * to hiding.
 */
class GapBandTest {

    private fun gap(from: Double, to: Double, standing: GapStanding = GapStanding.Lost) =
        RecordGap(from, to, standing, expectedReadings = 0)

    @Test fun aHoleIsPlacedWhereItHappened() {
        val bands = gapBands(
            listOf(gap(250.0, 500.0)),
            start = 0.0, end = 1_000.0, plotLeft = 0f, plotRight = 100f,
        )
        assertEquals(1, bands.size)
        assertEquals(25f, bands.single().left, 0.01f)
        assertEquals(50f, bands.single().right, 0.01f)
    }

    @Test fun aHoleOffScreenIsNotDrawn() {
        // The band must never be pinned to the edge of the window: a "gone" block
        // over data that is perfectly present would be the opposite of the truth.
        assertTrue(
            gapBands(listOf(gap(-500.0, -100.0)), 0.0, 1_000.0, 0f, 100f).isEmpty(),
        )
        assertTrue(
            gapBands(listOf(gap(2_000.0, 3_000.0)), 0.0, 1_000.0, 0f, 100f).isEmpty(),
        )
    }

    @Test fun aHoleTooNarrowToSeeIsStillDrawn() {
        // Zoomed out to a fortnight, three missing hours are under a pixel. A band
        // that rounds to nothing is the plot hiding the gap again.
        val bands = gapBands(listOf(gap(500.0, 500.1)), 0.0, 1_000.0, 0f, 100f)
        assertTrue(bands.single().right - bands.single().left >= 3f)
    }

    @Test fun aBandNeverLeavesThePlot() {
        val band = gapBands(listOf(gap(-100.0, 5_000.0)), 0.0, 1_000.0, 20f, 120f).single()
        assertTrue(band.left >= 20f)
        assertTrue(band.right <= 120f)
    }

    @Test fun eachKindKeepsItsOwnVerdict() {
        // What the shading is for: the kinds are drawn differently, so they must
        // arrive at the renderer still told apart. Which pattern belongs to which
        // verdict is [GapPatternTest]'s.
        val bands = gapBands(
            listOf(
                gap(100.0, 200.0, GapStanding.Lost),
                gap(300.0, 400.0, GapStanding.Fillable(300.0)),
            ),
            0.0, 1_000.0, 0f, 100f,
        )
        assertEquals(GapPattern.Gone, bands[0].pattern)
        assertEquals(GapPattern.StillOnSensor, bands[1].pattern)
    }
}

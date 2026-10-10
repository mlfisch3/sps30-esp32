package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the holes in this device's record, and saying which of them are gone.
 *
 * Written around the one that actually happened. On 2026-08-04 the captain's
 * tablet held nothing between 07:42:18 and 11:18:52; the board had every one of
 * those readings at the time and had recorded over them by the time anyone
 * looked, its retained record then beginning at 14:29. Both halves of that are
 * asserted here - the hole while it could still be had, and the same hole once it
 * could not - because the feature is worth nothing if it cannot tell them apart.
 */
class RecordGapsTest {

    private fun at(iso: String): Double = TimeSeriesHistory.epochOf(iso)!!

    private val gapOpens = at("2026-08-04T07:42:18-04:00")
    private val gapCloses = at("2026-08-04T11:18:52-04:00")
    private val ringNowBegins = at("2026-08-04T14:29:00-04:00")

    /** One second apart, the rate the board actually records at. */
    private fun secondly(from: Double, count: Int): DoubleArray =
        DoubleArray(count) { from + it }

    /** The captain's record: an hour up to the hole, then an hour after it. */
    private fun recordWithTheHole(): DoubleArray =
        secondly(gapOpens - 3_600, 3_601) + secondly(gapCloses, 3_600)

    private fun listing(
        name: String,
        start: String?,
        rows: Int = 100_000,
        relative: Boolean = false,
    ) = ServerMessage.FileEntry(
        name = name, rows = rows, bytes = rows * 10L, start = start, relative = relative,
    )

    // --- detecting a hole at all ---------------------------------------------

    @Test fun aHoleInTheRecordIsFound() {
        val gaps = RecordGaps.of(
            epochs = recordWithTheHole(),
            cadenceSeconds = 1.0,
            retention = SensorRetention.UNKNOWN,
        )
        assertEquals("one hole, not one per missing reading", 1, gaps.size)
        val gap = gaps.single()
        assertEquals(gapOpens, gap.startEpoch, 0.0)
        assertEquals(gapCloses, gap.endEpoch, 0.0)
        // 3h 36m 34s at one a second, less the two readings that bound it.
        assertEquals(12_993, gap.expectedReadings)
    }

    @Test fun anUnbrokenRecordHasNoHoles() {
        val gaps = RecordGaps.of(
            epochs = secondly(gapOpens, 7_200),
            cadenceSeconds = 1.0,
            retention = SensorRetention.UNKNOWN,
        )
        assertTrue("nothing is missing, so nothing is reported", gaps.isEmpty())
    }

    @Test fun anOrdinaryHiccupIsNotAGap() {
        // Four seconds of silence in a 1 Hz record. The link stuttered; the
        // record did not break, and a list that named this would name hundreds.
        val epochs = secondly(gapOpens, 600) + secondly(gapOpens + 604, 600)
        assertTrue(
            RecordGaps.of(epochs, cadenceSeconds = 1.0, retention = SensorRetention.UNKNOWN)
                .isEmpty(),
        )
    }

    @Test fun aDutyCycledSleepIsNotAGap() {
        // The whole point of duty cycling is to be silent between bursts, so the
        // cycle - not the within-burst interval - is what a gap is measured
        // against. Reading the interval here would report the feature working as
        // data loss, every cycle, for ever.
        val profile = ServerMessage.AcquisitionProfile(
            mode = "duty_cycle",
            intervalSeconds = 1.0,
            cycleSeconds = 900.0,
            warmupSeconds = 30.0,
            averageSeconds = 10.0,
        )
        val cadence = RecordGaps.cadenceSeconds(profile)
        assertEquals(900.0, cadence!!, 0.0)
        // Bursts of ten readings, fifteen minutes apart.
        val epochs = (0 until 6).flatMap { burst ->
            (0 until 10).map { gapOpens + burst * 900.0 + it }
        }.toDoubleArray()
        assertTrue(
            RecordGaps.of(epochs, cadence, SensorRetention.UNKNOWN).isEmpty(),
        )
    }

    @Test fun theIntervalIsTakenFromTheRecordWhenNothingHasSaid() {
        // A median rather than a mean, so the hole cannot widen the threshold
        // that is meant to catch it.
        assertEquals(1.0, RecordGaps.medianDelta(recordWithTheHole())!!, 0.0)
        assertEquals(
            1,
            RecordGaps.of(recordWithTheHole(), cadenceSeconds = null, SensorRetention.UNKNOWN).size,
        )
    }

    // --- what can still be had -----------------------------------------------

    @Test fun aHoleInsideWhatTheSensorRetainsIsFillable() {
        // The board on the morning it happened: still recording the session that
        // began at 05:00, so every missing reading is still on it.
        val retention = SensorRetention.of(
            listing = listOf(listing("SPS30-20260804_050000.csv", "2026-08-04T05:00:00-04:00")),
            fullyNamed = true,
        )
        val gap = RecordGaps.of(recordWithTheHole(), 1.0, retention).single()
        val standing = gap.standing
        assertTrue("the sensor still has it", standing is GapStanding.Fillable)
        assertEquals(
            "and all of it, so the fill starts where the hole does",
            gapOpens,
            (standing as GapStanding.Fillable).fromEpoch,
            0.0,
        )
        assertTrue(!gap.partlyBeyondReach)
    }

    @Test fun aHoleTheRingHasWrappedPastIsLostAndSaysSo() {
        // The board days later. Its retained record begins at 14:29, later than
        // both ends of the hole, so there is nothing left to ask for. This is the
        // one verdict that tells the user to stop trying.
        val retention = SensorRetention.of(
            listing = listOf(listing("SPS30-20260804_142900.csv", "2026-08-04T14:29:00-04:00")),
            fullyNamed = true,
        )
        val gap = RecordGaps.of(recordWithTheHole(), 1.0, retention).single()
        assertEquals(GapStanding.Lost, gap.standing)
        assertNull("nothing to ask for", gap.fillableFrom)
        assertTrue(ringNowBegins > gap.endEpoch)
    }

    @Test fun aHoleTheRingHasHalfEatenIsFilledAsFarAsItGoes() {
        // The interesting middle: the sensor's record starts inside the hole. Some
        // of it can be had and the rest cannot, and neither single word is honest.
        val startsMidGap = gapOpens + 1_800
        val retention = SensorRetention.of(
            listing = listOf(
                listing("SPS30-mid.csv", isoOf(startsMidGap)),
            ),
            fullyNamed = true,
        )
        val gap = RecordGaps.of(recordWithTheHole(), 1.0, retention).single()
        val standing = gap.standing as GapStanding.Fillable
        assertEquals(startsMidGap, standing.fromEpoch, 1.0)
        assertTrue("and the app must not claim the earlier half back", gap.partlyBeyondReach)
    }

    // --- never claiming data is gone when nobody has looked -------------------

    @Test fun anUnwalkedListingCannotSayLost() {
        // The failure mode this feature could most easily introduce. `file_list`
        // is bounded by one frame and the automatic pass reads a single page, so
        // most of the time the oldest session named is simply the oldest one that
        // fitted. Calling a hole below it "gone" would be telling the user to give
        // up on readings that are sitting on the board.
        val retention = SensorRetention.of(
            listing = listOf(listing("SPS30-20260804_142900.csv", "2026-08-04T14:29:00-04:00")),
            fullyNamed = false,
        )
        val standing = RecordGaps.of(recordWithTheHole(), 1.0, retention).single().standing
        assertTrue("not lost, merely not asked about", standing is GapStanding.Unknown)
        assertTrue(
            (standing as GapStanding.Unknown).because.contains("not named everything"),
        )
    }

    @Test fun aSensorThatHasSaidNothingLeavesEveryHoleUndecided() {
        val standing = RecordGaps.of(recordWithTheHole(), 1.0, SensorRetention.UNKNOWN)
            .single().standing
        assertTrue(standing is GapStanding.Unknown)
    }

    // --- clockless sessions ---------------------------------------------------

    @Test fun aClocklessSessionSetsNoFloorAndForcesNoVerdict() {
        // An R-named session has no start to compare against anything. Left to
        // count as a dated entry it would either move the retained floor onto a
        // date it does not have, or - worse - be quietly skipped, which would put
        // the floor on the oldest session that *is* dated while an older one sits
        // below it unaccounted for.
        val retention = SensorRetention.of(
            listing = listOf(
                listing("SPS30-20260804_142900.csv", "2026-08-04T14:29:00-04:00"),
                listing("SPS30-R000004AC-00000817.csv", start = "", relative = true),
            ),
            fullyNamed = true,
        )
        assertEquals(1, retention.undatedSessions)
        assertEquals(ringNowBegins, retention.startsAt!!, 0.0)
        assertNotNull(
            "an undated session older than the floor means the floor is not one",
            retention.unsettledBecause,
        )
        val standing = RecordGaps.of(recordWithTheHole(), 1.0, retention).single().standing
        assertTrue("so this may not be called lost", standing is GapStanding.Unknown)
    }

    @Test fun aClocklessSessionNewerThanTheFloorDoesNotBlockAVerdict() {
        // Newest first, so an undated entry *above* the oldest dated one cannot be
        // older than it. Nothing is uncertain and "lost" stays available.
        val retention = SensorRetention.of(
            listing = listOf(
                listing("SPS30-R000004AC-00000817.csv", start = "", relative = true),
                listing("SPS30-20260804_142900.csv", "2026-08-04T14:29:00-04:00"),
            ),
            fullyNamed = true,
        )
        assertNull(retention.unsettledBecause)
        assertEquals(
            GapStanding.Lost,
            RecordGaps.of(recordWithTheHole(), 1.0, retention).single().standing,
        )
    }

    @Test fun readingsNothingCanDateDoNotOpenAGap() {
        // The phantom-gap case, through the store rather than around it. A
        // clockless run with no equivalence to place it is kept, counted and
        // exported, and has no position - so it can be neither side of a hole.
        val history = TimeSeriesHistory(nowSeconds = { ringNowBegins + 86_400 })
        history.basis = TimeBasis()
        val columns = com.sensirion.sps30.wire.Wire.CSV_COLUMNS_RELATIVE
        val rows = (0 until 600).map { i ->
            listOf(
                "%07d".format(i), "", "1.0", "1.0", "1.0", "1.0",
                "1.0", "1.0", "1.0", "1.0", "1.0", "0.5", "",
                "1196", "${i * 1000L}",
            )
        }
        assertEquals(600, history.addRows("SPS30-R000004AC-00000817.csv", columns, rows))
        assertEquals("kept, every one of them", 600, history.size)
        assertEquals("and none of them placed", 600, history.unplacedCount())
        assertEquals(
            "so the gap detector never sees them at all",
            0,
            history.placedEpochs().size,
        )
        assertTrue(
            RecordGaps.of(history.placedEpochs(), 1.0, SensorRetention.UNKNOWN).isEmpty(),
        )
    }

    // --- a loaded archive is data on the plot, not a run of gaps --------------

    @Test fun aLoadedArchiveIsNotReadAsASpacing() {
        // Stored sessions come back averaged - a point a minute, and on a large
        // archive as coarse as one an hour. Folded into the record as if that were
        // the recording interval, a loaded archive would hatch itself from end to
        // end: every bucket boundary a "gap", every one of them reported as data
        // that might be lost. The store keeps them out of the epoch series for
        // exactly that reason.
        val history = TimeSeriesHistory(nowSeconds = { ringNowBegins + 86_400 })
        val values = DoubleArray(com.sensirion.sps30.wire.Wire.FIELDS.size) { 1.5 }
        // Six hours of hourly buckets, which is what a big archive imports as.
        for (i in 0 until 6) {
            history.addImported("SPS30-old.csv", i, gapOpens - 86_400 + i * 3_600.0, values)
        }
        history.finishImport()
        assertEquals("the readings are held and drawn", 6, history.size)
        assertEquals(
            "and none of them is offered to the gap detector as a spacing",
            0,
            history.placedEpochs().size,
        )
        assertTrue(RecordGaps.of(history.placedEpochs(), 1.0, SensorRetention.UNKNOWN).isEmpty())
    }

    @Test fun aHoleAnArchiveCoversIsNotAHole() {
        // The other half of it. The user loaded the host backup that has these
        // hours in it, so there is a trace across the hole - and telling them the
        // stretch is missing, or lost, would be arguing with their own screen.
        val covered = listOf((gapOpens - 60.0)..(gapCloses + 60.0))
        assertTrue(
            RecordGaps.of(recordWithTheHole(), 1.0, SensorRetention.UNKNOWN, covered).isEmpty(),
        )
    }

    @Test fun aHoleAnArchiveOnlyHalfCoversIsStillAHole() {
        // Over-reporting a gap costs a de-duplicated re-fetch. Under-reporting one
        // costs the data, which is the whole of what this feature is about.
        val covered = listOf((gapOpens - 60.0)..(gapOpens + 600.0))
        assertEquals(
            1,
            RecordGaps.of(recordWithTheHole(), 1.0, SensorRetention.UNKNOWN, covered).size,
        )
    }

    private fun isoOf(epoch: Double): String =
        java.time.Instant.ofEpochSecond(epoch.toLong())
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/**
 * The report the Data tab is built from, and the zero it must never print.
 *
 * "No gaps" and "nobody has looked" are the same empty list, and only one of them
 * is reassuring. Same rule, and the same reason, as [RecordCount.Unknown].
 */
class RecordGapReportTest {

    @Test fun anEmptyListBeforeASweepIsNotAllClear() {
        assertTrue(!RecordGapReport().nothingMissing)
        assertTrue(RecordGapReport(sweptAtMs = 1L).nothingMissing)
    }

    @Test fun theLostReadingsAreCountedApartFromTheRest() {
        val report = RecordGapReport(
            gaps = listOf(
                RecordGap(100.0, 200.0, GapStanding.Lost, expectedReadings = 99),
                RecordGap(300.0, 400.0, GapStanding.Fillable(300.0), expectedReadings = 99),
                RecordGap(500.0, 600.0, GapStanding.Unknown("not asked"), expectedReadings = 99),
            ),
            sweptAtMs = 1L,
        )
        assertEquals(1, report.lost)
        assertEquals(1, report.fillable)
        assertEquals(1, report.undecided)
        assertEquals("only the unrecoverable ones", 99, report.readingsLost)
    }
}

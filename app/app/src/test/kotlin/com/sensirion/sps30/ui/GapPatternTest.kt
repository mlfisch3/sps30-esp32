package com.sensirion.sps30.ui

import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.RecordGaps
import com.sensirion.sps30.data.SensorRetention
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fill a hole gets, and why the answer is never a guess.
 *
 * A single hatch over every gap was decoration: the break in the trace already
 * says a gap is there. What the reader cannot see, and has to act on, is *why* -
 * and the four reasons want four different responses. So the pattern is the
 * answer to "why", and this file holds the two things that makes true: that each
 * case a gap can be in reaches the plot as its own fill, and that no case the
 * detector cannot substantiate has been invented to fill out the set.
 *
 * Detection itself is untouched. Every verdict here is one
 * [RecordGaps.of] already reached before any of this existed - the tests below
 * build them the same way [com.sensirion.sps30.data.RecordGapsTest] does, on the
 * timestamps of the incident that started it.
 */
class GapPatternTest {

    // The captain's tablet lost 2026-08-04 07:42:18 to 11:18:52, and the board's
    // ring had wrapped past it by the time anyone looked - its record then began
    // at 14:29, later than either end of the hole.
    private fun at(iso: String): Double = TimeSeriesHistory.epochOf(iso)!!
    private val gapOpens = at("2026-08-04T07:42:18-04:00")
    private val gapCloses = at("2026-08-04T11:18:52-04:00")
    private val ringNowBegins = at("2026-08-04T14:29:00-04:00")

    private fun isoOf(epoch: Double): String =
        java.time.Instant.ofEpochSecond(epoch.toLong())
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun listing(name: String, start: String, relative: Boolean = false) =
        ServerMessage.FileEntry(name, rows = 1, bytes = 1, start = start, relative = relative)

    /** The record either side of the hole, at 1 Hz. */
    private fun recordWithTheHole(): DoubleArray {
        val before = DoubleArray(120) { gapOpens - 120 + it }
        val after = DoubleArray(120) { gapCloses + it }
        return before + after
    }

    private fun theHole(retention: SensorRetention): RecordGap =
        RecordGaps.of(recordWithTheHole(), cadenceSeconds = 1.0, retention = retention).single()

    // --- the four cases, each arriving as its own fill -------------------------

    @Test fun aHoleTheSensorStillCoversKeepsTheAmberHatch() {
        // The one the captain likes, and the one case he can act on right now.
        // The board's record starts before the hole, so all of it is in reach.
        val retention = SensorRetention.of(
            listing = listOf(listing("SPS30-early.csv", isoOf(gapOpens - 86_400))),
            fullyNamed = true,
        )
        val gap = theHole(retention)
        assertTrue(gap.standing is GapStanding.Fillable)
        assertEquals(GapPattern.StillOnSensor, gapPatternOf(gap))
        // Amber, hatched one way, and no block. Unchanged from what shipped.
        val fill = GAP_FILLS.getValue(GapPattern.StillOnSensor)
        assertEquals(
            androidx.compose.ui.graphics.Color(0xFFFFB300).copy(alpha = 0.45f), fill.hatch,
        )
        assertEquals("no second thread", null, fill.counterHatch)
        assertEquals("and no hard sides", null, fill.edge)
    }

    @Test fun aHoleTheRingHasWrappedPastIsASolidBlock() {
        // The sensor's record begins after the hole ends, on a listing walked to
        // its end. The only case in which anything may be called gone.
        val retention = SensorRetention.of(
            listing = listOf(listing("SPS30-20260804_142900.csv", isoOf(ringNowBegins))),
            fullyNamed = true,
        )
        val gap = theHole(retention)
        assertEquals(GapStanding.Lost, gap.standing)
        assertEquals(GapPattern.Gone, gapPatternOf(gap))
        val fill = GAP_FILLS.getValue(GapPattern.Gone)
        assertEquals("a block, not a weave", null, fill.hatch)
        assertTrue("with sides, so it reads as a piece cut out", fill.edge != null)
        assertTrue("and they are hard", !fill.edgeDotted)
    }

    @Test fun aHoleTheRingHasHalfEatenGetsItsOwnFill() {
        // The interesting middle, and the one the two-way split could not show:
        // the sensor's record starts inside the hole. Neither single answer is
        // honest, so neither single pattern is drawn.
        val retention = SensorRetention.of(
            listing = listOf(listing("SPS30-mid.csv", isoOf(gapOpens + 1_800))),
            fullyNamed = true,
        )
        val gap = theHole(retention)
        assertTrue(gap.partlyBeyondReach)
        assertEquals(GapPattern.PartlyOutOfReach, gapPatternOf(gap))
        // A mesh: the amber of work outstanding crossed by the grey of the
        // unanswered, which is what the earlier part actually is.
        val fill = GAP_FILLS.getValue(GapPattern.PartlyOutOfReach)
        assertEquals(GAP_FILLS.getValue(GapPattern.StillOnSensor).hatch, fill.hatch)
        assertTrue(fill.counterHatch != null)
    }

    @Test fun aHoleNobodyHasAskedAboutIsDrawnAsAQuestion() {
        val gap = theHole(SensorRetention.UNKNOWN)
        assertTrue(gap.standing is GapStanding.Unknown)
        assertEquals(GapPattern.NotKnown, gapPatternOf(gap))
        val fill = GAP_FILLS.getValue(GapPattern.NotKnown)
        assertTrue("dotted through", fill.uprights != null)
        assertTrue("and dotted at the sides", fill.edgeDotted)
    }

    @Test fun anUnwalkedListingIsNotKnownRatherThanGone() {
        // The failure the coding could most easily introduce, one step on from
        // where detection guards it: a hole below a listing that was only one
        // page long must not get the block that means "give up on this".
        val retention = SensorRetention.of(
            listing = listOf(listing("SPS30-20260804_142900.csv", isoOf(ringNowBegins))),
            fullyNamed = false,
        )
        assertEquals(GapPattern.NotKnown, gapPatternOf(theHole(retention)))
    }

    // --- and no fifth case, because there is no fifth answer -------------------

    @Test fun aBoardThatWasSwitchedOffIsNotClaimedToBeAnything() {
        // The case the app cannot see, asserted as a limit rather than left to be
        // discovered. A verdict is reached against one instant - where the
        // sensor's retained record begins - so a hole *after* that instant is
        // Fillable whether the board recorded through it or sat unplugged. There
        // is no fifth pattern for "it was not recording", and the wording of the
        // fourth is what stops it being read as one.
        //
        // If a later change gives the app a way to tell the two apart, this test
        // is the one that should fail, and a fifth pattern is then honest.
        val recording = SensorRetention.of(
            listing = listOf(listing("SPS30-early.csv", isoOf(gapOpens - 86_400))),
            fullyNamed = true,
        )
        assertEquals(GapPattern.StillOnSensor, gapPatternOf(theHole(recording)))
        assertEquals(
            "so the label may not promise readings exist, only that the range does",
            "still inside the sensor's record",
            GAP_LEGEND.getValue(GapPattern.StillOnSensor).detail,
        )
        assertTrue(
            "and it must not say the sensor has them",
            GAP_LEGEND.values.none { it.full.contains("the sensor has this") },
        )
    }

    @Test fun nothingClaimsTheEarlierPartOfAHalfEatenHoleIsGone() {
        // Detection reaches Fillable for a straddling hole whether or not the
        // listing was walked to its end, so on a one-page listing the earlier
        // part may simply be in a session nothing has named. "Out of reach" is
        // true in both worlds; "gone for good" would be true in only one.
        val entry = GAP_LEGEND.getValue(GapPattern.PartlyOutOfReach)
        assertTrue(
            "the partly-out-of-reach wording claims loss: ${entry.full}",
            !entry.full.contains("gone"),
        )
    }

    // --- told apart at a glance ------------------------------------------------

    @Test fun everyPatternHasAFillAndNoTwoAreAlike() {
        val patterns = GapPattern.values().toList()
        assertEquals(patterns.toSet(), GAP_FILLS.keys)
        assertEquals(patterns.toSet(), GAP_LEGEND.keys)
        for (a in patterns) for (b in patterns) {
            if (a == b) continue
            assertNotEquals("$a and $b are drawn identically", GAP_FILLS[a], GAP_FILLS[b])
        }
    }

    @Test fun noTwoAreToldApartByColourAlone() {
        // A pattern that differs only in hue is one a colourblind reader, a dim
        // screen or a printed export cannot tell apart, and this is data
        // provenance rather than decoration. So each fill is described by its
        // structure - hatched which ways, dotted, blocked, edged - and no two
        // share one.
        fun shape(f: GapFill) = listOf(
            f.hatch != null, f.counterHatch != null, f.uprights != null,
            f.edge != null, f.edgeDotted,
        )
        val shapes = GapPattern.values().map { shape(GAP_FILLS.getValue(it)) }
        assertEquals("two fills differ only in colour", shapes.size, shapes.toSet().size)
    }

    // --- one condition, one name, wherever it is written ----------------------

    @Test fun theLegendUsesTheRecordPanelsWordsForTheSameConditions() {
        // The record panel on the Data tab owns the account of the record, so it
        // owns the vocabulary too: a hole the ring ate is "gone for good" there
        // and must not be "gone", "lost" or "destroyed" on the plot. A reader who
        // has to work out that two phrases mean one condition is a reader
        // reconciling the screen instead of reading it.
        //
        // Asserted against the panel's own pure functions rather than against a
        // copy of its strings, so this fails when that wording moves - which is
        // the point of having it.
        val lostReport = RecordGapReport(
            gaps = listOf(theHole(
                SensorRetention.of(
                    listing = listOf(listing("SPS30-20260804_142900.csv", isoOf(ringNowBegins))),
                    fullyNamed = true,
                )
            )),
            sweptAtMs = 1L,
        )
        val gone = GAP_LEGEND.getValue(GapPattern.Gone).label
        assertTrue(
            "the panel says \"${everRecordedSummary(lostReport)}\"; the plot says \"$gone\"",
            gone in everRecordedSummary(lostReport),
        )

        val undecidedReport = RecordGapReport(
            gaps = listOf(theHole(SensorRetention.UNKNOWN)),
            sweptAtMs = 1L,
        )
        val judged = GAP_LEGEND.getValue(GapPattern.NotKnown).label
        assertTrue(
            "the panel says \"${goneForGoodValue(undecidedReport)}\"; the plot says \"$judged\"",
            judged in goneForGoodValue(undecidedReport),
        )
    }

    @Test fun theTwoLabelsTakenFromRowHeadingsArePinnedToThem() {
        // Two of the four are named by a row label in `DataInventoryRow` rather
        // than by any sentence: "Not here yet" counts what the sensor holds and
        // this device does not, and "Gone for good" heads the row this plot's red
        // block is the picture of. A composable literal is not a function a test
        // can call, so they are pinned here instead - changing one without the
        // other then has to be a deliberate act rather than a drift nobody sees.
        assertEquals("not here yet", GAP_LEGEND.getValue(GapPattern.StillOnSensor).label)
        assertEquals("gone for good", GAP_LEGEND.getValue(GapPattern.Gone).label)
    }
}

/**
 * The wording of the key, which now lives on the Help tab.
 *
 * The key used to be drawn on the plot itself, only while a marked gap was on
 * screen, and this class held the appears-and-disappears rules for it. The
 * marking became a preference (off by default) and the key moved to Help, where
 * it is reference material and shows all four patterns unconditionally - see
 * `GapLegendSection`, and `GapMarkingControlTest` for the tab actually carrying
 * it. What remains here is the contract the wording itself still has to keep.
 */
class GapLegendTest {

    @Test fun everyPatternHasItsLineAndTheLabelStandsAlone() {
        // The label is read at a glance and the detail behind it; the short form
        // alone must never be wrong, only terser.
        assertEquals(GapPattern.values().toSet(), GAP_LEGEND.keys)
        for (entry in GAP_LEGEND.values) {
            assertTrue("empty label for ${entry.pattern}", entry.label.isNotBlank())
            assertTrue("empty detail for ${entry.pattern}", entry.detail.isNotBlank())
            assertTrue(entry.full.startsWith(entry.label))
        }
    }
}

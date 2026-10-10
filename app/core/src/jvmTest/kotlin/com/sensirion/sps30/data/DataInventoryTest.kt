package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Data tab's comparison: what the sensor holds, what is here, what is not.
 *
 * Two properties are worth more than the arithmetic and are what most of this
 * file is about.
 *
 * **A count nobody has taken is never a zero.** The app has already shipped one
 * of these - the Watch tab told the captain the watch app was not installed on a
 * watch it was installed on, because a failed lookup came back as an empty set
 * and "could not ask" was indistinguishable from "the answer is no". Every
 * figure here describes a device this phone cannot see for itself, and the same
 * mistake would read as "nothing is missing" on a sensor nobody has asked.
 *
 * **The three figures reconcile.** [DataInventory.here] and [DataInventory.gap]
 * add to [DataInventory.sensor] on both columns, always. A comparison whose
 * parts do not add up is one the reader has to check by hand, and a figure that
 * has to be checked is one that gets ignored.
 */
class DataInventoryTest {

    private fun storage(
        sessions: Int,
        readings: Int,
        ready: Boolean = true,
    ) = ServerMessage.Storage(
        usedPages = 1,
        totalPages = 10,
        readings = readings,
        sessions = sessions,
        fraction = 0.1,
        warnAt = 0.9,
        warn = false,
        ready = ready,
    )

    private fun entry(name: String, rows: Int, relative: Boolean = false) =
        ServerMessage.FileEntry(name = name, rows = rows, bytes = rows * 220L, start = null, relative = relative)

    private fun inventory(
        connected: Boolean = true,
        storage: ServerMessage.Storage? = null,
        listing: List<ServerMessage.FileEntry> = emptyList(),
        received: Map<String, Int> = emptyMap(),
    ) = DataInventory.of(connected, storage, listing) { received[it] ?: 0 }

    private fun counted(count: RecordCount): RecordCount.Counted =
        count as? RecordCount.Counted ?: throw AssertionError("expected a count, got $count")

    // --- an unknown is an unknown --------------------------------------------

    @Test fun nothingHeardMeansNothingClaimed() {
        val it = inventory(connected = false, storage = null)
        assertTrue("the sensor figure must be unknown", it.sensor is RecordCount.Unknown)
        assertTrue("what is here must be unknown too", it.here is RecordCount.Unknown)
        assertTrue(
            "with no sensor figure there is nothing to subtract from, so the gap " +
                "cannot be known either",
            it.gap is RecordCount.Unknown,
        )
        assertFalse(
            "\"everything is here\" off a sensor nobody has asked is the exact " +
                "false claim this type exists to stop",
            it.everythingIsHere,
        )
        assertFalse(it.worthCopying)
    }

    @Test fun theTwoWaysOfNotKnowingReadDifferently() {
        // "I have not been able to ask" and "I have asked and it has not answered
        // yet" call for different patience from the reader, and the second is the
        // one that resolves on its own.
        val offline = (inventory(connected = false, storage = null).sensor as RecordCount.Unknown).because
        val waiting = (inventory(connected = true, storage = null).sensor as RecordCount.Unknown).because
        assertTrue("offline must mention that nothing has been reached: $offline", offline.contains("reached"))
        assertTrue("connected must say it is waiting: $waiting", waiting.contains("waiting"))
        assertTrue(offline != waiting)
    }

    @Test fun aStoreThatIsNotUpYetIsNotAnEmptyStore() {
        // The most convincing wrong number on the screen: a controller still
        // bringing its store up answers `storage` with zeroes, and rendered
        // plainly those zeroes say the sensor has recorded nothing at all.
        val it = inventory(storage = storage(sessions = 0, readings = 0, ready = false))
        assertTrue(it.sensor is RecordCount.Unknown)
        assertTrue(it.gap is RecordCount.Unknown)
        assertFalse(it.everythingIsHere)
    }

    @Test fun anEmptySensorIsAnAnswerAndNotAnUnknown() {
        // The other half of the same distinction: a ready store holding nothing
        // is a fact, and reporting it as unknown would be its own dishonesty.
        val it = inventory(storage = storage(sessions = 0, readings = 0))
        assertEquals(RecordCount.Counted(0, 0L), it.sensor)
        assertTrue(it.everythingIsHere)
        assertFalse("there is nothing to copy", it.worthCopying)
    }

    // --- the arithmetic -------------------------------------------------------

    @Test fun theThreeFiguresAddUp() {
        val it = inventory(
            storage = storage(sessions = 3, readings = 300),
            listing = listOf(entry("a", 100), entry("b", 100), entry("c", 100)),
            received = mapOf("a" to 100, "b" to 40),
        )
        val sensor = counted(it.sensor)
        val here = counted(it.here)
        val gap = counted(it.gap)
        assertEquals("readings must reconcile", sensor.readings, here.readings + gap.readings)
        assertEquals("sessions must reconcile", sensor.sessions, here.sessions + gap.sessions)
        assertEquals(140L, here.readings)
        assertEquals(160L, gap.readings)
        // One session complete, two not - a session half here is not here.
        assertEquals(1, here.sessions)
        assertEquals(2, gap.sessions)
        assertTrue(it.worthCopying)
        assertFalse(it.everythingIsHere)
    }

    @Test fun everythingHereIsSaidOnlyWhenEverythingIsHere() {
        val it = inventory(
            storage = storage(sessions = 2, readings = 200),
            listing = listOf(entry("a", 100), entry("b", 100)),
            received = mapOf("a" to 100, "b" to 100),
        )
        assertEquals(RecordCount.Counted(2, 200L), it.here)
        assertEquals(RecordCount.Counted(0, 0L), it.gap)
        assertTrue(it.everythingIsHere)
        assertFalse(it.worthCopying)
    }

    @Test fun readingsTheRingHasDroppedDoNotShrinkTheGap() {
        // This device keeps what it has received; the controller is a ring and
        // wraps. So a session can be longer here than it is there, and counting
        // the surplus against another session's shortfall would report a copy as
        // complete while one of them is still only on the sensor.
        val it = inventory(
            storage = storage(sessions = 2, readings = 150),
            listing = listOf(entry("old", 50), entry("new", 100)),
            received = mapOf("old" to 5_000, "new" to 10),
        )
        assertEquals(60L, counted(it.here).readings)
        assertEquals(90L, counted(it.gap).readings)
    }

    @Test fun neitherSideEverRunsPastTheOther() {
        // `storage` and `file_list` are two messages about a board that is still
        // recording, so the listing can name more rows than the totals do. The
        // subtraction must not produce a negative gap out of that.
        val it = inventory(
            storage = storage(sessions = 1, readings = 100),
            listing = listOf(entry("a", 400)),
            received = mapOf("a" to 400),
        )
        assertEquals(RecordCount.Counted(1, 100L), it.here)
        assertEquals(RecordCount.Counted(0, 0L), it.gap)
    }

    // --- what the comparison cannot see --------------------------------------

    @Test fun sessionsTheListingHasNotNamedAreCountedMissingAndSaidOutLoud() {
        // `file_list` is bounded by one frame and the automatic pass reads a
        // single page, so on a board holding more sessions than a page can name
        // the per-session half of this is short. Counting the remainder as
        // missing is the safe direction; leaving the reader to discover it is
        // not.
        val it = inventory(
            storage = storage(sessions = 84, readings = 100_000),
            listing = List(64) { entry("s$it", 1_000) },
            received = List(64) { "s$it" to 1_000 }.toMap(),
        )
        assertEquals(20, it.unnamedSessions)
        assertEquals(64, counted(it.here).sessions)
        assertEquals(20, counted(it.gap).sessions)
        assertEquals(
            "the readings behind the unnamed sessions are missing as far as this " +
                "device can tell",
            36_000L,
            counted(it.gap).readings,
        )
    }

    @Test fun aCompleteListingReportsNothingUnnamed() {
        val it = inventory(
            storage = storage(sessions = 2, readings = 200),
            listing = listOf(entry("a", 100), entry("b", 100)),
        )
        assertEquals(0, it.unnamedSessions)
    }

    @Test fun clocklessSessionsAreCountedAndFlagged() {
        // They are in the totals - they copy and plot like any other session -
        // and they are named separately because the stored-sessions overview on
        // the same tab reads them as holding nothing. See
        // [ClocklessOverviewGapTest] for why that is true.
        val it = inventory(
            storage = storage(sessions = 3, readings = 300),
            listing = listOf(
                entry("SPS30-20260803_002028.csv", 100),
                entry("SPS30-R000004AC-00000817.csv", 100, relative = true),
                entry("SPS30-R000004B2-00000821.csv", 100, relative = true),
            ),
            received = mapOf("SPS30-20260803_002028.csv" to 100),
        )
        assertEquals(2, it.clocklessSessions)
        assertEquals("they are part of the record, not an aside", 300L, counted(it.sensor).readings)
        assertEquals(200L, counted(it.gap).readings)
    }

    // --- the connection tense -------------------------------------------------

    @Test fun figuresFromADroppedLinkAreMarkedAsNoLongerCurrent() {
        // Nothing clears `storage` on a disconnect - the last known figures are
        // worth keeping - so the tense has to move instead of the field.
        val live = inventory(connected = true, storage = storage(1, 100))
        val stale = inventory(connected = false, storage = storage(1, 100))
        assertTrue(live.live)
        assertFalse(stale.live)
        assertEquals("the figures themselves are the same", live.sensor, stale.sensor)
    }

    @Test fun theStartingValueClaimsNothing() {
        assertTrue(DataInventory.UNKNOWN.sensor is RecordCount.Unknown)
        assertTrue(DataInventory.UNKNOWN.here is RecordCount.Unknown)
        assertTrue(DataInventory.UNKNOWN.gap is RecordCount.Unknown)
        assertFalse(DataInventory.UNKNOWN.everythingIsHere)
        assertFalse(DataInventory.UNKNOWN.worthCopying)
    }
}

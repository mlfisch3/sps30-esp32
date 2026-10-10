package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A copy per crossing, not a copy per sample.
 *
 * `autoExportIfNearlyFull` was meant to fire once each time the controller's
 * store crossed its warn line, and guarded with
 * `if (autoExportedAt == storage.usedPages) return`. On a board that is recording
 * `usedPages` climbs continuously, so the two were never equal and the guard
 * never held once. Measured on 2026-08-04 with the board at 92%:
 *
 * ```
 * 05:18:47 store is 85% full; copying everything here
 * 05:23:04 store is 85% full; copying everything here
 * 05:38:10 store is 87% full; copying everything here
 * ```
 *
 * Each one a seven-minute, 1.7 MB copy that writes a fresh archive, with *Copy
 * sessions* and *Send sessions back* disabled while it runs. Above the line the
 * app ran them back to back for as long as the board stayed there, which on the
 * captain's board is permanently.
 *
 * These are the two halves of the fix: the crossing is what fires it, and a store
 * that stays above the line - now the ordinary case - does not fire it again
 * until enough new data has arrived to be worth another copy.
 */
class AutoExportTest {

    /** One `storage` message folded in. */
    private fun step(
        state: AutoExportState,
        warn: Boolean,
        readings: Int,
        nowMs: Long,
        copyInFlight: Boolean = false,
    ) = autoExportStep(state, warn, readings, copyInFlight, nowMs)

    /**
     * The bug, replayed. A board above the line reporting once a second, with
     * readings climbing exactly as they do on hardware.
     *
     * This fails against the old guard: `usedPages` moved on nearly every sample
     * and every move started a full copy.
     */
    @Test fun aBoardSittingAboveTheLineIsCopiedOnceNotEverySample() {
        var state = AutoExportState()
        var copies = 0
        var readings = 100_000
        var now = 0L
        // Twenty minutes of a live board at 1 Hz, which is the window the three
        // logged copies fell inside.
        repeat(20 * 60) {
            val out = step(state, warn = true, readings = readings, nowMs = now)
            state = out.state
            if (out.startCopy) copies++
            readings++
            now += 1_000L
        }
        assertEquals("above the line the app copied the whole board $copies times", 1, copies)
    }

    /** The crossing is what fires it, and it fires on the first sample past the line. */
    @Test fun theCrossingFiresACopy() {
        var state = AutoExportState()
        var now = 0L
        repeat(10) {
            val out = step(state, warn = false, readings = 50_000, nowMs = now)
            assertFalse("a store below its warn line was copied", out.startCopy)
            state = out.state
            now += 1_000L
        }
        val crossed = step(state, warn = true, readings = 50_000, nowMs = now)
        assertTrue("the crossing did not start a copy", crossed.startCopy)
    }

    /**
     * Dropping below the line re-arms it, which is what makes it a crossing at
     * all. An erase, or an import that failed and freed pages, gets the next one.
     */
    @Test fun droppingBackBelowTheLineRearmsTheCrossing() {
        var state = step(AutoExportState(), warn = true, readings = 50_000, nowMs = 0L).state
        state = step(state, warn = false, readings = 50_100, nowMs = 60_000L).state
        val again = step(state, warn = true, readings = 50_200, nowMs = 120_000L)
        assertTrue("the second crossing did not start a copy", again.startCopy)
    }

    /**
     * A store that never drops below the line is now the ordinary case, so a
     * crossing alone would copy once and then never again while the board went on
     * recording into a ring that is about to wrap. Enough new readings is the
     * second trigger, and it is a long way from a copy per sample.
     */
    @Test fun aStoreThatStaysFullIsCopiedAgainWhenEnoughNewDataHasArrived() {
        val first = step(AutoExportState(), warn = true, readings = 100_000, nowMs = 0L)
        assertTrue(first.startCopy)
        val state = first.state

        val hourLater = 60L * 60L * 1000L
        assertFalse(
            "a copy was taken again for ${AUTO_EXPORT_MIN_NEW_READINGS - 1} new readings",
            step(
                state, warn = true,
                readings = 100_000 + AUTO_EXPORT_MIN_NEW_READINGS - 1,
                nowMs = hourLater + 1,
            ).startCopy,
        )
        assertTrue(
            "the board recorded ${AUTO_EXPORT_MIN_NEW_READINGS} more readings and was " +
                "never copied again",
            step(
                state, warn = true,
                readings = 100_000 + AUTO_EXPORT_MIN_NEW_READINGS,
                nowMs = hourLater + 1,
            ).startCopy,
        )
    }

    /**
     * And the floor holds whatever the readings say. An import can add tens of
     * thousands in one go, and two seven-minute copies inside an hour is not a
     * reasonable answer to that on any controller.
     */
    @Test fun theIntervalIsAFloorWhateverArrives() {
        val state = step(AutoExportState(), warn = true, readings = 100_000, nowMs = 0L).state
        assertFalse(
            "a burst of readings bypassed the interval floor",
            step(
                state, warn = true,
                readings = 100_000 + 10 * AUTO_EXPORT_MIN_NEW_READINGS,
                nowMs = AUTO_EXPORT_MIN_INTERVAL_MS - 1,
            ).startCopy,
        )
        assertTrue(
            step(
                state, warn = true,
                readings = 100_000 + 10 * AUTO_EXPORT_MIN_NEW_READINGS,
                nowMs = AUTO_EXPORT_MIN_INTERVAL_MS,
            ).startCopy,
        )
    }

    /** Nothing recorded is nothing to copy, and it is not a crossing either. */
    @Test fun anEmptyStoreIsNotCopied() {
        val out = step(AutoExportState(), warn = true, readings = 0, nowMs = 0L)
        assertFalse(out.startCopy)
        // And the store filling up afterwards still counts as the crossing.
        assertTrue(step(out.state, warn = true, readings = 1, nowMs = 1_000L).startCopy)
    }

    /**
     * A copy already running is the copy this crossing wanted.
     *
     * It is recorded as taken rather than deferred: what is running collects
     * everything the controller holds, which is exactly what this one would ask
     * for, and queueing a second identical copy behind it is the back-to-back
     * behaviour the whole rule exists to stop.
     */
    @Test fun aCopyAlreadyRunningSatisfiesTheCrossing() {
        val out = step(
            AutoExportState(), warn = true, readings = 100_000, nowMs = 0L,
            copyInFlight = true,
        )
        assertFalse("a second copy was started on top of one already running", out.startCopy)
        assertEquals(100_000, out.copiedAtReadings())
        // And the next sample does not start one either: the crossing is spent.
        assertFalse(
            step(out.state, warn = true, readings = 100_001, nowMs = 1_000L).startCopy,
        )
    }

    private fun AutoExportStep.copiedAtReadings() = state.copiedAtReadings
}

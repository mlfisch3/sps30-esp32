package com.sensirion.sps30.ui

import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discovery flags decide whether the user is told "searching", "no sensor
 * found" or nothing at all. Both flags were previously never set by anything, so
 * the "no sensor found" banner was unreachable and a missing host rendered as
 * "reconnecting…" forever - a connection that had never once been made.
 */
class DiscoveryFlagsTest {

    @Test fun aSweepInProgressReportsSearching() {
        val f = discoveryFlagsFor(ConnectionState.Searching("sweeping"), wasFailed = false)
        assertTrue(f.discovering)
        assertFalse(f.failed)
    }

    @Test fun anEmptySweepReportsNotFound() {
        val f = discoveryFlagsFor(ConnectionState.NotFound("no sensor found"), wasFailed = false)
        assertTrue(f.failed)
        assertFalse(f.discovering)
    }

    @Test fun theNotFoundVerdictSurvivesTheNextRetrySweep() {
        // The bug this guards: retries would blink the banner between "searching…"
        // and "no sensor found" every few seconds instead of saying one useful thing.
        val f = discoveryFlagsFor(ConnectionState.Searching("sweeping"), wasFailed = true)
        assertTrue("the verdict must latch", f.failed)
        assertFalse("and must not flip back to searching", f.discovering)
    }

    @Test fun theReasonLatchesWithTheVerdict() {
        // Measured on the tablet before this was carried: the banner alternated
        // between "no sensor found on the network." and the generic "no sensor
        // found." as retries swapped the instantaneous state under it.
        val found = discoveryFlagsFor(ConnectionState.NotFound("no sensor found on the network"), false)
        assertEquals("no sensor found on the network", found.detail)
        val retrying = discoveryFlagsFor(ConnectionState.Searching("sweeping"), true, found.detail)
        assertEquals("the reason must survive the retry", found.detail, retrying.detail)
    }

    @Test fun theReasonIsDroppedOnceAHostIsReached() {
        val f = discoveryFlagsFor(ConnectionState.Connecting("192.168.1.226:8730"), true, "stale reason")
        assertFalse(f.failed)
        assertEquals(null, f.detail)
    }

    @Test fun reachingAHostClearsTheVerdict() {
        // Connecting is the first evidence a host exists, so it clears - waiting for
        // Connected would leave "no sensor found" on screen during the handshake.
        for (state in listOf(
            ConnectionState.Connecting("192.168.1.226:8730"),
            ConnectionState.Connected("192.168.1.226:8730"),
        )) {
            assertFalse("$state must clear the verdict", discoveryFlagsFor(state, true).failed)
        }
    }

    @Test fun losingAnEstablishedLinkIsNotADiscoveryFailure() {
        // A drop after a real connection is "reconnecting", which is true and is a
        // different banner; it must not be relabelled "no sensor found".
        val f = discoveryFlagsFor(ConnectionState.Reconnecting("connection lost"), wasFailed = false)
        assertFalse(f.failed)
        assertFalse(f.discovering)
    }

    /**
     * The whole mid-session sequence, folded state by state as the UI folds it.
     *
     * This is what a sensor going away mid-session now looks like, and it is the
     * same tail a cold start has: the drop is "reconnecting", the re-sweep says so,
     * and the empty sweep names the problem and then holds still. It could not
     * happen before, because the re-sweep after a drop was silent - the state never
     * left Reconnecting, so the first two flags below stayed false for ever and the
     * banner said "reconnecting…" about a sensor that was switched off.
     */
    @Test fun aMidSessionLossEndsOnTheSameVerdictAColdStartGives() {
        var f = discoveryFlagsFor(ConnectionState.Connected("192.168.1.226:8730"), wasFailed = false)
        assertFalse("connected is neither searching nor failed", f.discovering || f.failed)

        f = discoveryFlagsFor(ConnectionState.Reconnecting("connection closed"), f.failed, f.detail)
        assertFalse("the drop itself is not a discovery verdict", f.failed)

        f = discoveryFlagsFor(ConnectionState.Searching("sweeping the local network"), f.failed, f.detail)
        assertTrue("the re-sweep is visible", f.discovering)

        f = discoveryFlagsFor(
            ConnectionState.NotFound("no sensor found on the network"), f.failed, f.detail,
        )
        assertTrue("and its emptiness is reported", f.failed)
        assertEquals("no sensor found on the network", f.detail)

        // Further sweeps hold the sentence still rather than blinking it.
        f = discoveryFlagsFor(ConnectionState.Searching("sweeping the local network"), f.failed, f.detail)
        assertTrue(f.failed)
        assertEquals("no sensor found on the network", f.detail)

        // And the sensor returning clears it, with no restart and nothing latched.
        f = discoveryFlagsFor(ConnectionState.Connecting("192.168.1.226:8730"), f.failed, f.detail)
        assertFalse(f.failed)
        assertEquals(null, f.detail)
    }

    @Test fun aTransportsOwnAdviceLatchesAlongsideItsReason() {
        val busy = ConnectionState.NotFound("the sensor at 192.168.1.226 is full", "Close the other app.")
        val f = discoveryFlagsFor(busy, wasFailed = false)
        assertEquals("Close the other app.", f.advice)
        val retrying = discoveryFlagsFor(ConnectionState.Searching("sweeping"), true, f.detail, f.advice)
        assertEquals("Close the other app.", retrying.advice)
    }
}

/**
 * The retry gap between discovery sweeps. This is a load question, not a latency
 * one: each sweep spends one of the host's client slots per candidate address.
 */
class RetryBackoffTest {

    @Test fun aFruitlessSweepBacksOff() {
        assertTrue(nextRetryMs(MIN_RETRY_MS) > MIN_RETRY_MS)
    }

    @Test fun theBackoffIsBounded() {
        var ms = MIN_RETRY_MS
        repeat(50) { ms = nextRetryMs(ms) }
        assertEquals(MAX_RETRY_MS, ms)
    }

    @Test fun theCeilingStillFindsAHostPromptlyOnceItAppears() {
        // The ESP32 must be picked up within seconds of its network layer coming up,
        // so the ceiling is a real constraint and not just a politeness setting.
        assertTrue("a 20s ceiling keeps discovery responsive", MAX_RETRY_MS <= 20_000L)
    }
}

/**
 * The disconnected banner makes a claim about hardware nobody can see, so its
 * wording is a correctness question rather than a copy question.
 */
class BannerTest {

    @Test fun recordingStatesSayTheSensorIsStillRecording() {
        for (state in listOf(Wire.STATE_MEASURING, Wire.STATE_WARMUP, Wire.STATE_SLEEPING)) {
            assertEquals("the sensor is still recording", lastKnownSensorActivity(state))
        }
    }

    @Test fun aShutDownOrStoppedSensorIsNeverDescribedAsRecording() {
        // The bug: every one of these used to render as "still recording".
        for (state in listOf(Wire.STATE_PAUSED, Wire.STATE_CLEANING, Wire.STATE_SHUTDOWN)) {
            assertFalse(
                "$state must not claim the sensor is recording",
                lastKnownSensorActivity(state).contains("recording"),
            )
        }
    }

    @Test fun anUnknownStateClaimsNothingAboutTheSensor() {
        // Never connected, so there is no last known state to report.
        assertFalse(lastKnownSensorActivity(null).contains("recording"))
        assertTrue(lastKnownSensorActivity(null).isNotEmpty())
        assertFalse(lastKnownSensorActivity("something_new").contains("recording"))
    }
}

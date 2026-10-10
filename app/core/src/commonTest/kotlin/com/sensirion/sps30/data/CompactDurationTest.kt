package com.sensirion.sps30.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The compact window readout, held to its three rules: zero units are dropped,
 * at most the two most significant non-zero units are spoken, and the letters
 * are the codebase's lowercase ones.
 *
 * In `commonTest` so the JVM the app uses and the Kotlin/Native pack prove the
 * same arithmetic - this is pure common code and must stay that way.
 */
class CompactDurationTest {

    private val minute = 60.0
    private val hour = 3_600.0
    private val day = 86_400.0

    @Test
    fun subMinuteWindowsAreSecondsAlone() {
        assertEquals("45s", compactDuration(45.0))
        assertEquals("1s", compactDuration(1.0))
        assertEquals("59s", compactDuration(59.0))
    }

    @Test
    fun zeroUnitsAreDropped() {
        // An exact hour says nothing about minutes or seconds.
        assertEquals("1h", compactDuration(hour))
        // A zero unit in the middle is dropped, not written as 0h.
        assertEquals("1d 30m", compactDuration(day + 30 * minute))
        assertEquals("2h 1s", compactDuration(2 * hour + 1))
    }

    @Test
    fun atMostTheTwoMostSignificantUnitsAreSpoken() {
        // 5 days 2 hours 13 minutes: the minutes are true and still unsaid.
        assertEquals("5d 2h", compactDuration(5 * day + 2 * hour + 13 * minute))
        assertEquals("2h 30m", compactDuration(2 * hour + 30 * minute))
        assertEquals("2m 30s", compactDuration(2 * minute + 30.0))
    }

    @Test
    fun exactBoundariesReadAsOneUnit() {
        assertEquals("1m", compactDuration(minute))
        assertEquals("1h", compactDuration(hour))
        assertEquals("1d", compactDuration(day))
        // And just across them, the new field appears.
        assertEquals("1m 1s", compactDuration(minute + 1))
        assertEquals("1h 1s", compactDuration(hour + 1))
        assertEquals("1d 1s", compactDuration(day + 1))
    }

    @Test
    fun roundsToTheNearestSecondRatherThanTruncating() {
        // A one-hour window that floating point has nudged is a one-hour window.
        assertEquals("1h", compactDuration(3599.9999))
        assertEquals("1m", compactDuration(59.6))
    }

    @Test
    fun nothingAndNonsenseReadAsZeroSeconds() {
        assertEquals("0s", compactDuration(0.0))
        assertEquals("0s", compactDuration(-5.0))
        assertEquals("0s", compactDuration(Double.NaN))
        assertEquals("0s", compactDuration(Double.POSITIVE_INFINITY))
    }

    @Test
    fun daysGrowPastTwoDigitsOnTheirOwn() {
        assertEquals("365d", compactDuration(365 * day))
        assertEquals("14d 12h", compactDuration(14 * day + 12 * hour))
    }
}

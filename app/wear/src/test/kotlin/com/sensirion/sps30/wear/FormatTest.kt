package com.sensirion.sps30.wear

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The watch shows the reading and its unit, and [Format] is the one place that
 * says how - every surface (app, Tile, complication) renders through it, the
 * same presentation the phone gives the value. These tests hold that rendering:
 * the number's precision rules and the unit that must accompany it.
 *
 * There is deliberately nothing else to hold. The value used to arrive with an
 * EPA-style category label ("Unhealthy", "Hazardous"); those labels and their
 * breakpoint table were removed - the scale is defined for 24-hour averages,
 * not a live sample - and `AirQualityCategoryTest` in :app fails if one of the
 * words returns to a user-facing string.
 *
 * [Format.pm] formats with the default locale; pinned to US here so the
 * assertions read literally.
 */
class FormatTest {

    private lateinit var saved: Locale

    @Before
    fun pinLocale() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(saved)
    }

    @Test
    fun `one decimal below 100`() {
        assertEquals("0.0", Format.pm(0.0))
        assertEquals("3.1", Format.pm(3.14))
        assertEquals("99.9", Format.pm(99.94))
    }

    @Test
    fun `whole numbers from 100 up`() {
        assertEquals("100", Format.pm(100.0))
        assertEquals("153", Format.pm(153.7))
    }

    @Test
    fun `the unit is micrograms per cubic metre`() {
        assertEquals("μg/m³", Format.PM_UNIT)
        assertEquals("PM2.5", Format.PM_LABEL)
    }

    @Test
    fun `value and unit render together`() {
        assertEquals("3.1 μg/m³", Format.pmWithUnit(3.14))
        assertEquals("153 μg/m³", Format.pmWithUnit(153.7))
    }
}

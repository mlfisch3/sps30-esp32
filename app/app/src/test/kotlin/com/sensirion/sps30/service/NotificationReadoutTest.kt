package com.sensirion.sps30.service

import com.sensirion.sps30.data.PlottedSeries
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.ui.DashboardUiState
import com.sensirion.sps30.ui.SeriesColors
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ongoing notification's second line says.
 *
 * It used to say PM2.5 and PM10, which was the answer from before the coarse
 * cuts came off the plot: the chart draws PM1.0 and PM2.5 only (see
 * [PlottedSeries] for why), so the notification was showing a size the app no
 * longer plots and hiding one it does. The two now come from the same list, in
 * the same order - smallest on the left - and are rendered by the same
 * [SeriesColors.reading] the dashboard tiles use, so the glanceable copy and the
 * on-screen copy of a reading cannot drift apart.
 *
 * The width this has to live in is checked separately and with real font
 * metrics, in `NotificationWidthTest`.
 */
class NotificationReadoutTest {

    private fun textOf(latest: Map<String, Double>): String =
        Notifications.contentOf(
            DashboardUiState(
                connection = ConnectionState.Connected("192.168.1.226:8730"),
                daemonState = "measuring",
                latest = latest,
            ),
        ).text

    private val allFour = mapOf(
        "pm1_0" to 8.4, "pm2_5" to 9.7, "pm4_0" to 10.2, "pm10" to 11.0,
    )

    @Test fun itShowsPm1AndPm2point5SmallestFirstWithTheUnit() {
        assertEquals("PM1.0 8.40 · PM2.5 9.70 µg/m³", textOf(allFour))
    }

    @Test fun theCoarseCutsAreNotDrawnEvenThoughTheyArrive() {
        val text = textOf(allFour)
        assertFalse("PM10 is still on the notification", text.contains("PM10"))
        assertFalse("PM4.0 is on the notification", text.contains("PM4.0"))
    }

    /** The pair and the order are the chart's, not a second list kept in step by hand. */
    @Test fun itDrawsExactlyWhatThePlotDraws() {
        val text = textOf(allFour)
        assertEquals(
            "the notification's series are not the plotted ones, in order",
            PlottedSeries.FIELDS.map { SeriesColors.label.getValue(it) },
            Regex("PM[0-9.]+").findAll(text).map { it.value }.toList(),
        )
    }

    /** Two decimals, the same as the dashboard tile renders the same number. */
    @Test fun aReadingIsRenderedTheWayTheTileRendersIt() {
        assertTrue(textOf(allFour).contains("PM2.5 ${SeriesColors.reading(9.7)}"))
    }

    /**
     * A default-locale `%.2f` writes `9,70` in half of Europe, and the number in
     * a notification should read the same in Berlin as on the plot beside it.
     */
    @Test fun theNumbersDoNotFollowTheDeviceLocale() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("PM1.0 8.40 · PM2.5 9.70 µg/m³", textOf(allFour))
        } finally {
            Locale.setDefault(was)
        }
    }

    @Test fun beforeAnyReadingItSaysSoRatherThanDrawingZeroes() {
        assertEquals("Waiting for readings…", textOf(emptyMap()))
    }

    /** A coarse cut moving is not something the notification shows, so it must not re-post. */
    @Test fun aPm10OnlyChangeDoesNotRepostTheNotification() {
        val state = DashboardUiState(
            connection = ConnectionState.Connected("192.168.1.226:8730"),
            daemonState = "measuring",
            latest = allFour,
        )
        val gate = NotificationGate()
        gate.postFor(state)
        val coarserOnly = state.copy(latest = allFour + mapOf("pm10" to 40.0, "pm4_0" to 30.0))
        assertEquals(null, gate.postFor(coarserOnly))
    }
}

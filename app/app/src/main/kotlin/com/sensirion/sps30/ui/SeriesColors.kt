package com.sensirion.sps30.ui

import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * Per-series colours and labels for the four PM mass concentrations, mirroring
 * the Textual client's palette (pm1.0 pale yellow, pm2.5 green, pm4.0 red,
 * pm10 cyan) so the two front-ends read the same.
 *
 * All four are named because all four are recorded. Only PM1.0 and PM2.5 are
 * ever drawn - see [com.sensirion.sps30.data.PlottedSeries].
 */
object SeriesColors {
    val color: Map<String, Color> = mapOf(
        "pm1_0" to Color(0xFFEEE8AA), // pale yellow (palegoldenrod)
        "pm2_5" to Color(0xFF2ECC40), // green
        "pm4_0" to Color(0xFFFF4136), // red
        "pm10" to Color(0xFF39CCCC),  // cyan
    )

    val label: Map<String, String> = mapOf(
        "pm1_0" to "PM1.0",
        "pm2_5" to "PM2.5",
        "pm4_0" to "PM4.0",
        "pm10" to "PM10",
    )

    val unit: String = "µg/m³"

    /**
     * A concentration rendered the one way this app renders concentrations.
     *
     * Two decimals, deliberately finer than the sensor's absolute accuracy of
     * +-(5 ug/m3 + 5%): consecutive samples move by about 0.02 ug/m3, so a
     * single decimal holds the same number for long stretches and reads as a
     * frozen display. What the sensor resolves well is *change*.
     *
     * [Locale.US] and not the default, for the reason spelled out on
     * [com.sensirion.sps30.data.AlertLevel]: a default-locale `%.2f` writes
     * `12,34` in half of Europe, and the readout tile and the notification are
     * meant to show the same number as each other and as the plot's axis.
     */
    fun reading(value: Double): String = String.format(Locale.US, "%.2f", value)
}

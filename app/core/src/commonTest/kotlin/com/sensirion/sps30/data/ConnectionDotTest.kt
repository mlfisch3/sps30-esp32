package com.sensirion.sps30.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dot's contract, as the captain restated it on 2026-08-12: three solid
 * colours and nothing else - red not connected, green Wi-Fi, blue Bluetooth -
 * each "as pronounced as the app". The requirements are held as numbers here
 * rather than as prose in the colour file: vividness as saturation and a
 * luminance floor, distinctness as hue separation, and the colour-blind
 * fallback as the luminance step between disconnected and either connected
 * colour.
 */
class ConnectionDotTest {

    // --- every state maps to its one solid colour -----------------------------

    @Test
    fun notConnectedIsSolidRed() {
        assertEquals(ConnectionDot.NOT_CONNECTED, ConnectionDot.spec(null).fillArgb)
    }

    @Test
    fun wifiIsSolidGreen() {
        assertEquals(ConnectionDot.WIFI, ConnectionDot.spec(DotTransport.WIFI).fillArgb)
    }

    @Test
    fun bluetoothIsSolidBlue() {
        assertEquals(ConnectionDot.BLUETOOTH, ConnectionDot.spec(DotTransport.BLUETOOTH).fillArgb)
    }

    // --- "as pronounced as the app" -------------------------------------------

    @Test
    fun everyColourIsVividNotMuted() {
        for ((name, argb) in colours()) {
            assertTrue(
                saturation(argb) >= 0.80,
                "$name has saturation ${saturation(argb)}; a muted dot fails the brief",
            )
            assertTrue(
                ConnectionDot.relativeLuminance(argb) >= 0.15,
                "$name is too dark to be pronounced",
            )
        }
    }

    @Test
    fun theThreeColoursCannotBeMistakenForEachOther() {
        val entries = colours().entries.toList()
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val apart = hueDegreesApart(entries[i].value, entries[j].value)
                assertTrue(
                    apart >= 60.0,
                    "${entries[i].key} and ${entries[j].key} are only $apart degrees " +
                        "of hue apart",
                )
            }
        }
    }

    @Test
    fun aColourBlindViewerStillSeesTheStateChange() {
        // Red-green confusion is the common case, so the state change must also
        // be a luminance step: either connected colour is markedly brighter
        // than the disconnected red.
        val red = ConnectionDot.relativeLuminance(ConnectionDot.NOT_CONNECTED)
        val green = ConnectionDot.relativeLuminance(ConnectionDot.WIFI)
        assertTrue(
            green >= red * 2.0,
            "the Wi-Fi green ($green) must be at least twice as luminous as the " +
                "disconnected red ($red), so the state change survives colour blindness",
        )
    }

    @Test
    fun theDotIsBigEnoughToNotice() {
        // The captain named the old dot compact but hard to notice; the solid
        // dot takes the whole footprint the fill-plus-ring pair used to.
        assertTrue(ConnectionDot.DIAMETER_DP >= 14f)
        assertTrue(ConnectionDot.DIAMETER_DP <= 24f)
    }

    // --- helpers -------------------------------------------------------------

    private fun colours() = mapOf(
        "not-connected red" to ConnectionDot.NOT_CONNECTED,
        "Wi-Fi green" to ConnectionDot.WIFI,
        "Bluetooth blue" to ConnectionDot.BLUETOOTH,
    )

    /** HSV saturation: 1 is fully vivid, 0 is grey. */
    private fun saturation(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toDouble()
        val g = ((argb shr 8) and 0xFF).toDouble()
        val b = (argb and 0xFF).toDouble()
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        return if (maxC == 0.0) 0.0 else (maxC - minC) / maxC
    }

    /** Angular distance between two colours' HSV hues, in degrees. */
    private fun hueDegreesApart(a: Long, b: Long): Double {
        val d = abs(hue(a) - hue(b))
        return min(d, 360.0 - d)
    }

    private fun hue(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toDouble()
        val g = ((argb shr 8) and 0xFF).toDouble()
        val b = (argb and 0xFF).toDouble()
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val range = maxC - minC
        if (range == 0.0) return 0.0
        val h = when (maxC) {
            r -> (g - b) / range % 6.0
            g -> (b - r) / range + 2.0
            else -> (r - g) / range + 4.0
        }
        return (h * 60.0 + 360.0) % 360.0
    }
}

package com.sensirion.sps30.data

import kotlin.math.pow

/**
 * The transport a collapsed connection dot is describing. Deliberately not
 * [ConnectionMode]: that type lives with the JVM config store, and this one has
 * to be visible to every front-end (the Android app today, an iOS port
 * tomorrow), which is why it and the colours below sit in commonMain.
 */
enum class DotTransport { WIFI, BLUETOOTH }

/**
 * What a connection dot looks like: one solid colour, nothing else.
 *
 * It used to be a fill plus a ring - state on the fill, transport on the ring -
 * and the captain withdrew that design on 2026-08-12: three solid colours,
 * red for not connected, green for Wi-Fi, blue for Bluetooth. One glance, one
 * fact. The class keeps its shape (a spec, built only by [ConnectionDot.spec])
 * so the two platforms still agree by construction.
 */
data class DotSpec(val fillArgb: Long)

/**
 * Colours and geometry of the connection status dot, shared by every front-end
 * so the dot reads identically on Android and iOS.
 *
 * ## Why these colours
 *
 * Three solid colours, decided by the captain: **red** not connected, **green**
 * connected over Wi-Fi, **blue** connected over Bluetooth. Green means Wi-Fi
 * specifically - not "connected" generically - and there is no ring, no second
 * axis of meaning, nothing to decode.
 *
 * Each is the highest-luminosity version of its hue that still reads as that
 * hue, per his instruction that the dot be "as pronounced as the app": a muted
 * dot fails. They are Material's A400 accents - the vivid end of each hue -
 * and they are deliberately not the chart's series colours (the readout
 * palette labels *data*, and the dot must not look like another series) nor
 * the alert red (0xFFFF5252), which the dot's red sits visibly deeper than.
 *
 * A colour-blind viewer who cannot tell red from green still gets the state
 * change as a large luminance step: the connected pair are roughly twice the
 * disconnected red's luminance, which a test holds rather than this prose.
 */
object ConnectionDot {
    /** Not connected, whatever the reason: bright red. */
    const val NOT_CONNECTED = 0xFFFF1744L

    /** Connected over Wi-Fi: bright green. */
    const val WIFI = 0xFF00E676L

    /** Connected over Bluetooth: bright blue. */
    const val BLUETOOTH = 0xFF2979FFL

    /**
     * Dot diameter in density-independent points, shared so the two platforms
     * agree on proportion. Grown from the old 13 when the ring went: the ring
     * was half the old dot's footprint and all of its noticeability, so a
     * bare dot at the old fill size vanished. The captain named exactly that -
     * compact but hard to notice - so the solid dot takes the whole footprint
     * the fill-plus-ring pair used to.
     */
    const val DIAMETER_DP = 16f

    /** The dot for a connection over [transport]; null transport = not connected. */
    fun spec(transport: DotTransport?): DotSpec = when (transport) {
        null -> DotSpec(NOT_CONNECTED)
        DotTransport.WIFI -> DotSpec(WIFI)
        DotTransport.BLUETOOTH -> DotSpec(BLUETOOTH)
    }

    /**
     * WCAG relative luminance of an ARGB colour, here so a test can hold the
     * three colours to the "as pronounced as the app" requirement instead of
     * trusting the numbers in this file's prose.
     */
    fun relativeLuminance(argb: Long): Double {
        fun lin(channel: Long): Double {
            val u = channel / 255.0
            return if (u <= 0.04045) u / 12.92 else ((u + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

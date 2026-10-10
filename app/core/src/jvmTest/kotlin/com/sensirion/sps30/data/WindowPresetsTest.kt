package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window buttons field, unbounded.
 *
 * Windows used to be capped just under 48 hours - not by any parser, but by
 * the chip row treating "≥ [AppConfig.ALL_SECONDS]" as the old "all" button
 * and skipping it, so 47h appeared and 48h silently did not. The record long
 * outgrew the 48 h horizon that number came from; these tests hold the door
 * open and hold every refusal to naming its reason.
 */
class WindowPresetsTest {

    /** The spans the cap used to swallow, and the units added with its removal. */
    @Test fun longWindowsAreAccepted() {
        val parsed = AppConfig.checkPresets("2d, 48h, 2880m, 4d, 3w, 1M, 1y")
        assertEquals(emptyList<String>(), parsed.errors)
        assertEquals(
            listOf(
                "2d" to 172_800.0,
                "48h" to 172_800.0,
                "2880m" to 172_800.0,
                "4d" to 345_600.0,
                "3w" to 1_814_400.0,
                "1M" to 2_592_000.0,
                "1y" to 31_536_000.0,
            ),
            parsed.values.map { it.label to it.seconds },
        )
        // And none of them is mistaken for the legacy "all" token: that is
        // exactly the aliasing that made them vanish.
        assertTrue(parsed.values.none { it.isAll })
    }

    @Test fun onlyTheAllLabelIsAll() {
        assertTrue(WindowPreset("all", AppConfig.ALL_SECONDS).isAll)
        assertTrue(WindowPreset("ALL", AppConfig.ALL_SECONDS).isAll)
        // Sharing "all"'s seconds does not make a fixed window "all".
        assertFalse(WindowPreset("48h", AppConfig.ALL_SECONDS).isAll)
        assertFalse(WindowPreset("1y", 31_536_000.0).isAll)
    }

    /** A refused token is named, with the reason; the good ones still parse. */
    @Test fun aBadTokenIsReportedNotSwallowed() {
        val parsed = AppConfig.checkPresets("1h, 5q, 2d")
        assertEquals(listOf("1h", "2d"), parsed.values.map { it.label })
        assertEquals(1, parsed.errors.size)
        assertTrue(parsed.errors[0].contains("'q'"))
        assertTrue(parsed.errors[0].contains(Durations.UNIT_HINT))
    }

    @Test fun anEmptyFieldIsAnErrorNotASilentReset() {
        val parsed = AppConfig.checkPresets("  ")
        assertTrue(parsed.values.isEmpty())
        assertEquals(1, parsed.errors.size)
        assertTrue(parsed.errors[0].contains("no window sizes"))
    }

    /** The legacy entry point keeps its drop-and-default contract. */
    @Test fun parsePresetsStillDropsAndDefaults() {
        assertEquals(listOf("1h"), AppConfig.parsePresets("1h, 5q").map { it.label })
        assertEquals(AppConfig.DEFAULT_PRESETS, AppConfig.parsePresets(""))
    }
}

package com.sensirion.sps30.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gap-marking preference survives the app being closed.
 *
 * A display setting that has to be set again on every launch is worse than no
 * setting - and this one is easier to lose than most, because its default is
 * off: a build that quietly dropped the field would come back looking exactly
 * like a user who never touched it.
 *
 * `ConfigStore` writes the config as one JSON blob into SharedPreferences and
 * reads it back, so what is exercised here is the serialization it delegates
 * to, configured exactly as `ConfigStore` configures it.
 */
class MarkGapsPersistenceTest {

    /** Byte-for-byte the `Json` in `com.sensirion.sps30.config.ConfigStore`. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `both positions survive a round trip`() {
        for (marking in listOf(false, true)) {
            val saved = AppConfig(markGaps = marking)
            val loaded = json.decodeFromString<AppConfig>(json.encodeToString(saved)).normalized()
            assertEquals("markGaps=$marking did not come back from storage", marking, loaded.markGaps)
            assertEquals("the rest of the config changed on the way", saved, loaded)
        }
    }

    /**
     * The stored form names the setting, so a hand-read config file and a future
     * migration both see what it is rather than a bare `true` somewhere.
     */
    @Test
    fun `the stored form names the setting`() {
        val text = json.encodeToString(AppConfig(markGaps = true))
        assertTrue(text, text.contains("\"markGaps\":true"))
    }

    /**
     * A config written before this setting existed still loads, opens with the
     * marking off - the default - and keeps everything else it said.
     * `ConfigStore` falls back to a *default* `AppConfig` when decoding fails,
     * so a config this could not read would take the user's alert rules with it.
     */
    @Test
    fun `a config saved before this setting existed still loads, marking off`() {
        val older = """{"logY":true,"pinCycleCount":7,"backgroundMonitoring":false}"""
        val loaded = json.decodeFromString<AppConfig>(older).normalized()
        assertFalse(loaded.markGaps)
        assertEquals(true, loaded.logY)
        assertEquals(7, loaded.pinCycleCount)
        assertEquals(false, loaded.backgroundMonitoring)
    }
}

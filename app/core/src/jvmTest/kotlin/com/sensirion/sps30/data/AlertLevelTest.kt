package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Commands
import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An alert level with two decimal places, along the whole path it travels.
 *
 * ## What was wrong
 *
 * The field took `text.filter { it.isDigit() }` and stored
 * `rule.threshold.toInt().toString()`, so a fractional level could neither be
 * typed nor shown. Nothing downstream of the field was ever the problem -
 * [AlertRule.threshold] is a `Double`, `set_alerts` carries a JSON number and the
 * firmware keeps a `float` - so this is a test of the entry and of the claim that
 * the rest of the path really does carry it, rather than of a protocol change.
 *
 * The last case is the one that would have shipped a lie: a field that shows
 * `12.34` over a wire that sends `12`.
 */
class AlertLevelTest {

    // --- entry ---------------------------------------------------------------

    @Test fun twoDecimalsAreTypeable() {
        assertEquals("12.34", AlertLevel.sanitize("12.34"))
        assertEquals(12.34, AlertLevel.parse("12.34")!!, 0.0)
    }

    @Test fun aThirdDecimalIsNotAccepted() {
        // Silently dropping the extra keystroke is the honest behaviour: the
        // field never shows a precision it will not keep.
        assertEquals("12.34", AlertLevel.sanitize("12.345"))
        assertEquals(12.34, AlertLevel.parse("12.3456")!!, 0.0)
    }

    @Test fun theDecimalPointSurvivesBeingTypedOnItsOwn() {
        // The fault that would have made decimals unreachable in practice: "12."
        // parses as the value already stored, so a field re-rendered from the
        // store would snap back to "12" and eat the point. Sanitising has to
        // leave it alone.
        assertEquals("12.", AlertLevel.sanitize("12."))
        assertEquals(12.0, AlertLevel.parse("12.")!!, 0.0)
    }

    @Test fun aCommaIsADecimalPointAndALeadingPointIsAZero() {
        assertEquals("12.34", AlertLevel.sanitize("12,34"))
        assertEquals("0.5", AlertLevel.sanitize(".5"))
        assertEquals(0.5, AlertLevel.parse(".5")!!, 0.0)
    }

    @Test fun malformedInputIsRejectedAndSaysWhy() {
        for (bad in listOf("", "   ", "abc", ".", "-", "+", "NaN", "Infinity", "ug/m")) {
            assertNull("\"$bad\" is not a level", AlertLevel.parse(bad))
            assertNotNull("the user is told, not left guessing", AlertLevel.rejection(bad))
        }
        // A sign and an exponent are dropped rather than honoured, so neither can
        // smuggle a negative or an absurd level past the bounds. The user sees
        // that happen - the field holds the sanitised text as it is typed, so
        // "-5" is on screen as "5" and "1e3" as "13" - rather than having some
        // other number saved behind a display that still reads as what they typed.
        assertEquals("5", AlertLevel.sanitize("-5"))
        assertEquals(5.0, AlertLevel.parse("-5")!!, 0.0)
        assertEquals("13", AlertLevel.sanitize("1e3"))
    }

    @Test fun theOldGuardsAreStillThere() {
        // Zero used to be accepted by the digit filter, and `value >= threshold`
        // made it fire on every reading while the wire dropped it for being
        // non-positive. Both ends of the range are refused, and each says which.
        assertNull(AlertLevel.parse("0"))
        assertNull(AlertLevel.parse("0.00"))
        assertNull("above what the SPS30 can report", AlertLevel.parse("5000"))
        assertEquals(AlertLevel.MIN, AlertLevel.parse("0.01")!!, 0.0)
        assertEquals(AlertLevel.MAX, AlertLevel.parse("1000")!!, 0.0)
        assertTrue(AlertLevel.rejection("0")!!.contains("between"))
        assertTrue(AlertLevel.rejection("abc")!!.contains("for example"))
        assertNull("a good level has nothing to complain about", AlertLevel.rejection("12.34"))
    }

    // --- display back --------------------------------------------------------

    @Test fun formatAndParseAreInverses() {
        // The field re-renders itself from the stored value while it is being
        // typed in, so a format the parser could not read back would eat
        // keystrokes. Locale-independent for the same reason: a default-locale
        // "%.2f" writes 12,34 in half of Europe and that will not parse.
        for (value in listOf(0.01, 0.5, 1.0, 12.34, 20.0, 55.55, 150.0, 999.99)) {
            assertEquals(value, AlertLevel.parse(AlertLevel.format(value))!!, 0.0)
        }
    }

    @Test fun trailingZerosAreNotShown() {
        assertEquals("20", AlertLevel.format(20.0))
        assertEquals("12.5", AlertLevel.format(12.5))
        assertEquals("12.34", AlertLevel.format(12.34))
        assertEquals("100", AlertLevel.format(100.0))
    }

    // --- storage -------------------------------------------------------------

    @Test fun aTwoDecimalLevelSurvivesBeingSavedAndLoaded() {
        val rule = AlertEngine.defaults().first().copy(threshold = 12.34)
        val json = Json { ignoreUnknownKeys = true }
        val restored = json.decodeFromString(AlertRule.serializer(), json.encodeToString(AlertRule.serializer(), rule))
        assertEquals(12.34, restored.threshold, 0.0)
        assertEquals("12.34", AlertLevel.format(restored.threshold))
    }

    // --- what the controller is told -----------------------------------------

    @Test fun theWireCarriesTheDecimalsRatherThanRoundingThem() {
        // The claim this whole job turns on: if `set_alerts` truncated, the app
        // would be showing a precision the controller does not have. It does not
        // - the field is a JSON number and the firmware keeps a float - and this
        // is what would go red if that changed.
        val config = AppConfig(alerts = listOf(AlertEngine.defaults().first().copy(threshold = 12.34)))
        assertEquals(12.34, config.preservationThresholds["pm2_5"]!!, 0.0)

        val line = Commands.setAlerts(auto = true, thresholds = config.preservationThresholds)
        assertTrue(line, line.contains("12.34"))
        assertFalse("a truncated level would read as \"pm2_5\":12", line.contains("\"pm2_5\":12,"))
        assertFalse(line.contains("\"pm2_5\":12}"))
    }

    @Test fun aPreservedSegmentReportsTheLevelBackUnrounded() {
        // The controller's own report of the line in force, which is the only
        // place a level comes back the other way.
        val msg = Messages.parse(
            """{"type":"alert_segment","seq":1,"session":"SPS30-20260101_000000.csv",""" +
                """"field":"pm2_5","threshold":12.34,"points":3}""",
        )
        assertTrue(msg is ServerMessage.AlertSegment)
        assertEquals(12.34, (msg as ServerMessage.AlertSegment).threshold, 1e-6)
        assertEquals("12.34", AlertLevel.format(msg.threshold))
    }

    // --- and it still fires where it should ----------------------------------

    @Test fun aDecimalLevelFiresOnTheValueItNames() {
        val rule = AlertEngine.defaults().first().copy(threshold = 12.34)
        val engine = AlertEngine { 0L }
        assertTrue("12.33 is below the line", engine.evaluate(listOf(rule), mapOf("pm2_5" to 12.33)).isEmpty())
        assertEquals(
            "12.34 is the line, and >= fires on it",
            1,
            engine.evaluate(listOf(rule), mapOf("pm2_5" to 12.34)).size,
        )
    }
}

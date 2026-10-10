package com.sensirion.sps30.ui

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the power panel is allowed to use.
 *
 * Two of the captain's requirements are properties of strings, so they are held
 * here as such: an estimate must never read as a measurement, and the absence
 * of a battery must never read as a battery at zero - or as a battery at all.
 */
class PowerPanelTextTest {

    private fun estimate(
        status: String = Wire.POWER_RUNTIME_NO_CAPACITY,
        hours: Double? = null,
        alternatives: List<ServerMessage.PowerAlternative> = emptyList(),
        profile: ServerMessage.AcquisitionProfile? = null,
    ) = ServerMessage.PowerEstimate(
        basis = Wire.POWER_BASIS_MODELLED,
        sourcesDatasheet = 2,
        sourcesMeasured = 0,
        sourcesEstimate = 12,
        profile = profile,
        totalMw = 994.4,
        alternatives = alternatives,
        runtimeStatus = status,
        runtimeHours = hours,
    )

    private fun duty(cycle: Double, sleeps: Boolean = false) =
        ServerMessage.PowerAlternative(
            mode = Wire.MODE_DUTY_CYCLE, cycleSeconds = cycle, sleeps = sleeps,
            totalMw = 750.0,
        )

    // --- refusal classification ----------------------------------------------

    @Test
    fun `a controller that does not know the command is old firmware, not a Pi`() {
        // The captain's case: connected to his own controller, whose firmware
        // predates the power model. Only a bare controller says it cannot
        // power itself off, and a current controller would have answered.
        val refusal = classifyPowerRefusal(
            message = "unknown cmd 'get_power_estimate'",
            helloSeen = true,
            canPowerOff = false,
        )
        assertEquals(PowerRefusal.FirmwarePredatesEstimates, refusal)
    }

    @Test
    fun `a Pi host that does not know the command genuinely has no model`() {
        val refusal = classifyPowerRefusal(
            message = "unknown cmd 'get_power_estimate'",
            helloSeen = true,
            canPowerOff = true,
        )
        assertEquals(PowerRefusal.HostDoesNotModelPower, refusal)
    }

    @Test
    fun `an error the app has no rule for is passed through, not guessed at`() {
        // Words that are not "unknown cmd" fit neither known case.
        assertEquals(
            PowerRefusal.Unclassified("model not ready"),
            classifyPowerRefusal("model not ready", helloSeen = true, canPowerOff = false),
        )
        // And before a hello has said what the host is, there is no honest way
        // to pick a case - even for an "unknown cmd".
        assertEquals(
            PowerRefusal.Unclassified("unknown cmd 'get_power_estimate'"),
            classifyPowerRefusal(
                "unknown cmd 'get_power_estimate'",
                helloSeen = false,
                canPowerOff = true,
            ),
        )
    }

    // --- the refusal messages -------------------------------------------------

    @Test
    fun `old firmware reads as actionable and never as a Raspberry Pi`() {
        val text = powerUnavailableText(PowerRefusal.FirmwarePredatesEstimates)
        assertTrue(text.contains("firmware"))
        assertTrue(text.contains("Updating the controller"))
        assertFalse(text.contains("Raspberry Pi"))
        assertFalse(text.contains("does not report power estimates"))
    }

    @Test
    fun `a host with no model is explained without blaming firmware`() {
        val text = powerUnavailableText(PowerRefusal.HostDoesNotModelPower)
        assertTrue(text.contains("does not report power estimates"))
        assertTrue(text.contains("Raspberry Pi"))
        // Nothing that suggests updating anything would change the answer.
        assertFalse(text.contains("firmware", ignoreCase = true))
    }

    @Test
    fun `an unclassified refusal quotes the host rather than inventing a story`() {
        val text = powerUnavailableText(PowerRefusal.Unclassified("flux capacitor busy"))
        assertTrue(text.contains("flux capacitor busy"))
        assertTrue(text.contains("does not recognise"))
        assertFalse(text.contains("Raspberry Pi"))
        assertFalse(text.contains("firmware"))
    }

    @Test
    fun `no refusal message leaks the placeholder product tokens`() {
        val texts = listOf(
            powerUnavailableText(PowerRefusal.FirmwarePredatesEstimates),
            powerUnavailableText(PowerRefusal.HostDoesNotModelPower),
            powerUnavailableText(PowerRefusal.Unclassified("busy")),
        )
        for (text in texts) {
            assertFalse(text.contains("SENSORQ"))
            assertFalse(text.contains("APPQ"))
        }
    }

    // --- mode labels --------------------------------------------------------

    @Test
    fun `mode labels read the way the picker does`() {
        assertEquals("Continuous", powerModeLabel(Wire.MODE_CONTINUOUS, null))
        assertEquals("Continuous", powerModeLabel(Wire.MODE_CONTINUOUS, 900.0))
        assertEquals("Low power, every 5 min", powerModeLabel(Wire.MODE_DUTY_CYCLE, 300.0))
        assertEquals("Low power, every 15 min", powerModeLabel(Wire.MODE_DUTY_CYCLE, 900.0))
        assertEquals("Low power, every 12 h", powerModeLabel(Wire.MODE_DUTY_CYCLE, 43200.0))
        assertEquals("Low power, every 24 h", powerModeLabel(Wire.MODE_DUTY_CYCLE, 86400.0))
    }

    // --- number formatting --------------------------------------------------

    @Test
    fun `milliwatts round to whole figures because the inputs are estimates`() {
        assertEquals("994 mW", formatMilliwatts(994.4425647977101))
        assertEquals("995 mW", formatMilliwatts(994.5425647977101))
    }

    @Test
    fun `a small real draw does not round to switched off`() {
        assertEquals("under 1 mW", formatMilliwatts(0.029337284482758623))
        assertEquals("0 mW", formatMilliwatts(0.0))
    }

    @Test
    fun `hours keep a decimal while it means something`() {
        assertEquals("5.2 h", formatHours(5.208948393167098))
        assertEquals("300 h", formatHours(300.4))
    }

    // --- the runtime slot ---------------------------------------------------

    @Test
    fun `no capacity reads as nothing to measure, not as a battery at zero`() {
        val text = powerRuntimeText(estimate())
        assertTrue(text.contains("No runtime is shown"))
        assertTrue(text.contains("nothing on this board measures", ignoreCase = true))
        // No number of hours may appear: "unknown" and "about to die" must
        // never render the same, and a digit here would be one or the other.
        assertFalse(Regex("""\d+(\.\d+)?\s*h\b""").containsMatchIn(text))
        // And nothing that implies a battery exists and is merely empty.
        assertFalse(text.contains("0%"))
        assertFalse(text.contains("empty", ignoreCase = true))
        assertFalse(text.contains("flat", ignoreCase = true))
    }

    @Test
    fun `the slot names where the runtime will appear, so the future has a place`() {
        val text = powerRuntimeText(estimate())
        assertTrue(text.contains("When a capacity is known"))
    }

    @Test
    fun `a known runtime is a number and says it is deliberately short`() {
        val text = powerRuntimeText(
            estimate(status = Wire.POWER_RUNTIME_OK, hours = 5.208948393167098),
        )
        assertTrue(text.contains("5.2 h"))
        assertTrue(text.contains("underestimated"))
    }

    @Test
    fun `an unusable gauge answer is named, not shown as zero`() {
        val text = powerRuntimeText(estimate(status = Wire.POWER_RUNTIME_UNUSABLE_CAPACITY))
        assertTrue(text.contains("not a usable number"))
        assertFalse(Regex("""\d+(\.\d+)?\s*h\b""").containsMatchIn(text))
    }

    // --- the honesty line ---------------------------------------------------

    @Test
    fun `the honesty line says estimates and nothing measured, from the counts`() {
        val text = powerHonestyLine(datasheet = 2, measured = 0, estimate = 12)
        assertTrue(text.contains("not measurements"))
        assertTrue(text.contains("12 are estimates"))
        assertTrue(text.contains("2 come from datasheets"))
        assertTrue(text.contains("none has been measured"))
    }

    @Test
    fun `the day a meter arrives the line updates itself`() {
        val text = powerHonestyLine(datasheet = 2, measured = 3, estimate = 9)
        assertTrue(text.contains("3 measured on this board"))
        assertFalse(text.contains("none has been measured"))
    }

    // --- the sleep note and the current marker ------------------------------

    @Test
    fun `the sleep note tells the truth for this board both ways`() {
        val cannot = estimate(alternatives = listOf(duty(900.0, sleeps = false)))
        assertTrue(powerSleepNote(cannot)!!.contains("cannot put it to sleep"))
        val can = estimate(alternatives = listOf(duty(900.0, sleeps = true)))
        assertTrue(powerSleepNote(can)!!.contains("sleeps between cycles"))
        assertNull(powerSleepNote(estimate()))
    }

    @Test
    fun `the current mode is recognised among the alternatives`() {
        val profile = ServerMessage.AcquisitionProfile(
            mode = Wire.MODE_DUTY_CYCLE, intervalSeconds = 1.0, cycleSeconds = 900.0,
            warmupSeconds = 30.0, averageSeconds = 30.0,
        )
        val est = estimate(profile = profile)
        assertTrue(isCurrentMode(est, duty(900.0)))
        assertFalse(isCurrentMode(est, duty(300.0)))
        assertFalse(
            isCurrentMode(
                est,
                ServerMessage.PowerAlternative(mode = Wire.MODE_CONTINUOUS, totalMw = 994.0),
            ),
        )
    }

    @Test
    fun `the subsystem breakdown names all four in reading order`() {
        val line = powerBreakdownLine(
            linkedMapOf(
                "sensor" to 275.0132275132275,
                "controller" to 273.9,
                "radio" to 445.5,
                "storage" to 0.029337284482758623,
            ),
        )!!
        assertEquals(
            "Sensor 275 mW · Controller 274 mW · Radios 446 mW · Storage under 1 mW",
            line,
        )
        assertNull(powerBreakdownLine(emptyMap()))
    }
}

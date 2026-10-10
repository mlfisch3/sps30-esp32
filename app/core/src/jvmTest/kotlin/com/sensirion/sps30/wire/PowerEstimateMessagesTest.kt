package com.sensirion.sps30.wire

import com.sensirion.sps30.data.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `power_estimate` reply - the power panel's whole diet - held to what
 * `wire.py` and `power.py` say it is.
 *
 * The long lines here are ones `firmware/test/gen_power_vectors.py` generated
 * by running the real `power.estimate_message`, and `firmware/test/
 * test_power_model.c` asserts the firmware emits byte for byte. Parsing the
 * same bytes on this side is what makes it one power model with one wire shape
 * rather than three that happen to agree - the app deliberately owns no model
 * of its own.
 */
class PowerEstimateMessagesTest {

    /** `power.estimate_message(DeviceState())`: the board as it runs today. */
    private val noBatteryLine =
        """{"type":"power_estimate","basis":"modelled","sources":{"datasheet":2,"measured":0,"estimate":12},"current":{"profile":{"mode":"continuous","interval":1.0,"cycle_s":900.0,"warmup_s":30.0,"average_s":30.0},"sleeps":false,"total_mw":994.4425647977101,"by_subsystem":{"sensor":275.0132275132275,"controller":273.9,"radio":445.5,"storage":0.029337284482758623}},"alternatives":[{"mode":"continuous","total_mw":994.4425647977101},{"mode":"duty_cycle","cycle_s":300.0,"sleeps":false,"total_mw":801.3133253041758},{"mode":"duty_cycle","cycle_s":900.0,"sleeps":false,"total_mw":758.7132601102103},{"mode":"duty_cycle","cycle_s":1800.0,"sleeps":false,"total_mw":748.0632438117188},{"mode":"duty_cycle","cycle_s":3600.0,"sleeps":false,"total_mw":742.7382356624731},{"mode":"duty_cycle","cycle_s":43200.0,"sleeps":false,"total_mw":737.8569781923313},{"mode":"duty_cycle","cycle_s":86400.0,"sleeps":false,"total_mw":737.6351028527794}],"runtime":{"status":"no_capacity"}}"""

    /** The same state with 2000 mAh known - the future the panel is shaped for. */
    private val batteryKnownLine =
        """{"type":"power_estimate","basis":"modelled","sources":{"datasheet":2,"measured":0,"estimate":12},"current":{"profile":{"mode":"continuous","interval":1.0,"cycle_s":900.0,"warmup_s":30.0,"average_s":30.0},"sleeps":false,"total_mw":994.4425647977101,"by_subsystem":{"sensor":275.0132275132275,"controller":273.9,"radio":445.5,"storage":0.029337284482758623}},"alternatives":[{"mode":"continuous","total_mw":994.4425647977101,"hours":5.208948393167098},{"mode":"duty_cycle","cycle_s":300.0,"sleeps":false,"total_mw":801.3133253041758,"hours":6.4643876950800605},{"mode":"duty_cycle","cycle_s":900.0,"sleeps":false,"total_mw":758.7132601102103,"hours":6.827348713066589},{"mode":"duty_cycle","cycle_s":1800.0,"sleeps":false,"total_mw":748.0632438117188,"hours":6.9245482154764995},{"mode":"duty_cycle","cycle_s":3600.0,"sleeps":false,"total_mw":742.7382356624731,"hours":6.9741932639024355},{"mode":"duty_cycle","cycle_s":43200.0,"sleeps":false,"total_mw":737.8569781923313,"hours":7.020330705132628},{"mode":"duty_cycle","cycle_s":86400.0,"sleeps":false,"total_mw":737.6351028527794,"hours":7.022442370172625}],"runtime":{"status":"ok","hours":5.208948393167098,"modelled_hours":7.441354847381569,"derating":0.7}}"""

    private fun parse(line: String): ServerMessage.PowerEstimate {
        val msg = Messages.parse(line)
        return msg as? ServerMessage.PowerEstimate
            ?: throw AssertionError("did not parse to a PowerEstimate: $msg")
    }

    @Test
    fun `the command is the name wire py gives it`() {
        val line = Commands.getPowerEstimate()
        assertTrue(line.contains(""""cmd":"get_power_estimate""""))
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun `the current state arrives whole`() {
        val estimate = parse(noBatteryLine)
        assertEquals("modelled", estimate.basis)
        assertEquals(994.4425647977101, estimate.totalMw, 0.0)
        assertEquals("continuous", estimate.profile?.mode)
        assertFalse(estimate.sleeps)
        // The whole bill, in reading order: measuring is only part of it.
        assertEquals(
            listOf("sensor", "controller", "radio", "storage"),
            estimate.bySubsystemMw.keys.toList(),
        )
        assertEquals(445.5, estimate.bySubsystemMw["radio"]!!, 0.0)
        // The honesty counts: nothing measured, and the panel says so from data.
        assertEquals(2, estimate.sourcesDatasheet)
        assertEquals(0, estimate.sourcesMeasured)
        assertEquals(12, estimate.sourcesEstimate)
    }

    @Test
    fun `every mode the picker offers is priced`() {
        val estimate = parse(noBatteryLine)
        // Continuous first, then the duty cycles, smallest first.
        assertEquals("continuous", estimate.alternatives.first().mode)
        val cycles = estimate.alternatives.filter { it.dutyCycling }.map { it.cycleSeconds }
        // The same six periods the mode picker offers, so the panel prices
        // exactly the switches the user can make. If this fails, either
        // `wire.POWER_COMPARE_CYCLES` or [AppConfig.DUTY_CYCLE_PERIODS] moved
        // without the other.
        assertEquals(AppConfig.DUTY_CYCLE_PERIODS, cycles)
        // Duty cycling anything is cheaper than continuous, and longer cycles
        // are cheaper than shorter ones - the comparison the panel exists for.
        val totals = estimate.alternatives.map { it.totalMw }
        assertEquals(totals.sortedDescending(), totals)
    }

    @Test
    fun `no battery means no runtime and no hours anywhere`() {
        val estimate = parse(noBatteryLine)
        assertEquals(Wire.POWER_RUNTIME_NO_CAPACITY, estimate.runtimeStatus)
        assertFalse(estimate.runtimeKnown)
        assertNull(estimate.runtimeHours)
        assertNull(estimate.runtimeModelledHours)
        assertTrue(estimate.alternatives.all { it.hours == null })
    }

    @Test
    fun `a known capacity fills the runtime slot and every alternative`() {
        val estimate = parse(batteryKnownLine)
        assertTrue(estimate.runtimeKnown)
        assertEquals(5.208948393167098, estimate.runtimeHours!!, 0.0)
        assertEquals(7.441354847381569, estimate.runtimeModelledHours!!, 0.0)
        assertEquals(0.7, estimate.runtimeDerating!!, 0.0)
        // The reported hours are shorter than the physics, never longer.
        assertTrue(estimate.runtimeHours!! < estimate.runtimeModelledHours!!)
        assertTrue(estimate.alternatives.all { it.hours != null })
    }

    @Test
    fun `a status that is not ok never smuggles hours through`() {
        // A frame no honest sender produces: "unknown" with numbers riding
        // along. The numbers are the half that has to lose, because a client
        // rendering them would show a runtime nobody stands behind.
        val line =
            """{"type":"power_estimate","basis":"modelled","current":{"total_mw":900.0},""" +
                """"alternatives":[{"mode":"continuous","total_mw":900.0,"hours":5.0}],""" +
                """"runtime":{"status":"no_capacity","hours":5.0}}"""
        val estimate = parse(line)
        assertFalse(estimate.runtimeKnown)
        assertNull(estimate.runtimeHours)
        assertTrue(estimate.alternatives.all { it.hours == null })
    }

    @Test
    fun `ok without a number is not known`() {
        val line =
            """{"type":"power_estimate","current":{"total_mw":900.0},""" +
                """"runtime":{"status":"ok"}}"""
        val estimate = parse(line)
        assertFalse(estimate.runtimeKnown)
        assertNull(estimate.runtimeHours)
    }

    @Test
    fun `a frame with no usable total is dropped rather than rendered empty`() {
        assertNull(Messages.parse("""{"type":"power_estimate","basis":"modelled"}"""))
        assertNull(Messages.parse("""{"type":"power_estimate","current":{}}"""))
    }

    @Test
    fun `an older host that never heard of the message stays harmless`() {
        // The Pi daemon's answer to the command is its usual tagged error, which
        // parses like any other error and names the command it refuses.
        val error = Messages.parse(
            """{"type":"error","message":"unknown cmd 'get_power_estimate'","ref":"get_power_estimate"}"""
        )
        val msg = error as? ServerMessage.ErrorMsg
            ?: throw AssertionError("did not parse to an ErrorMsg: $error")
        assertEquals(Wire.CMD_GET_POWER_ESTIMATE, msg.ref)
    }
}

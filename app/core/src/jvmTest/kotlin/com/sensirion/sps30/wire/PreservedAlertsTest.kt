package com.sensirion.sps30.wire

import com.sensirion.sps30.data.AlertEngine
import com.sensirion.sps30.data.AlertRule
import com.sensirion.sps30.data.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's half of what the controller preserves when its storage wraps.
 *
 * Two things are worth pinning here and the rest follows from them.
 *
 * The first is [AppConfig.preservationThresholds]: it is the **only** thing
 * about the user's alert rules that ever leaves this phone, and getting it wrong
 * in the lenient direction sends the controller a rule's identity it has no
 * business holding, while getting it wrong in the strict direction means an
 * alert fires on air whose readings the controller did not think worth keeping -
 * so the evidence for the alert is gone by the time anyone looks.
 *
 * The second is that an `alert_segment` whose samples have been reduced away is
 * still a complete summary. That is not a degraded message; it is the case the
 * whole design exists for.
 */
class PreservedAlertsTest {

    // --- what the controller is told ------------------------------------------

    @Test fun theDefaultRulesPreserveAgainstTwenty() {
        val thresholds = AppConfig().preservationThresholds
        assertEquals(mapOf("pm2_5" to 20.0), thresholds)
    }

    @Test fun onlyTheLowestEnabledThresholdPerFieldIsSent() {
        val config = AppConfig(
            alerts = listOf(
                AlertRule(id = "a", label = "A", threshold = 55.0, field = "pm2_5"),
                AlertRule(id = "b", label = "B", threshold = 20.0, field = "pm2_5"),
                AlertRule(id = "c", label = "C", threshold = 90.0, field = "pm10"),
            )
        )
        // The minimum, because keeping a superset of what any rule would fire on
        // is right and keeping less than some rule would fire on is not.
        assertEquals(mapOf("pm2_5" to 20.0, "pm10" to 90.0), config.preservationThresholds)
    }

    @Test fun aDisabledRuleContributesNothing() {
        val config = AppConfig(
            alerts = listOf(
                AlertRule(id = "off", label = "Off", threshold = 5.0, enabled = false),
                AlertRule(id = "on", label = "On", threshold = 20.0),
            )
        )
        assertEquals(mapOf("pm2_5" to 20.0), config.preservationThresholds)
    }

    @Test fun everyRuleOffMeansWatchNothing() {
        val config = AppConfig(
            alerts = AlertEngine.defaults().map { it.copy(enabled = false) }
        )
        // A thing the user is allowed to choose, and it has to be expressible:
        // "leave it as it was" would give no way to say "stop keeping this".
        assertTrue(config.preservationThresholds.isEmpty())
    }

    @Test fun aThresholdThatIsNotANumberIsNotSent() {
        val config = AppConfig(
            alerts = listOf(
                AlertRule(id = "nan", label = "NaN", threshold = Double.NaN),
                AlertRule(id = "inf", label = "Inf", threshold = Double.POSITIVE_INFINITY),
                AlertRule(id = "zero", label = "Zero", threshold = 0.0),
            )
        )
        assertTrue(config.preservationThresholds.isEmpty())
    }

    @Test fun theCommandCarriesTheThresholdsAndNothingElseAboutARule() {
        val line = Commands.setAlerts(auto = true, thresholds = mapOf("pm2_5" to 20.0))
        assertTrue(line.endsWith("\n"))
        assertTrue(line.contains(""""cmd":"set_alerts""""))
        assertTrue(line.contains(""""auto":true"""))
        assertTrue(line.contains(""""pm2_5":20.0"""))
        // Nothing that identifies a rule or a person may travel with it.
        for (leaked in listOf("id", "label", "notify", "vibrate", "sound", "watch")) {
            assertFalse("set_alerts leaked '$leaked'", line.contains("\"$leaked\""))
        }
    }

    @Test fun decliningIsExpressibleOnItsOwn() {
        val line = Commands.setAlerts(auto = false)
        assertTrue(line.contains(""""auto":false"""))
        assertFalse("declining must not also rewrite the line", line.contains("thresholds"))
    }

    @Test fun anEmptyThresholdMapIsSentAsAnEmptyObject() {
        // Present-but-empty is the message; absent would mean "leave it alone",
        // and the user turning every rule off has to reach the controller.
        val line = Commands.setAlerts(thresholds = emptyMap())
        assertTrue(line.contains(""""thresholds":{}"""))
    }

    // --- what the controller says ---------------------------------------------

    @Test fun parsesTheAlertsStatus() {
        val msg = Messages.parse(
            """{"type":"alerts","ready":true,"segments":7,"pressure":0.625,
               "rate_floor_s":60.0,"wraps":11,"thresholds":{"pm2_5":20.0}}""".trimIndent()
        )
        val alerts = msg as ServerMessage.Alerts
        assertTrue(alerts.ready)
        assertEquals(7, alerts.segments)
        assertEquals(0.625, alerts.pressure, 0.0)
        assertEquals(11, alerts.wraps)
        assertEquals(mapOf("pm2_5" to 20.0), alerts.thresholds)
        assertTrue("a floor above zero is a reduced rate", alerts.rateReduced)
    }

    @Test fun aControllerAtFullRateSaysSo() {
        val msg = Messages.parse(
            """{"type":"alerts","ready":true,"segments":0,"pressure":0.0,
               "rate_floor_s":0.0,"wraps":0,"thresholds":{}}""".trimIndent()
        )
        val alerts = msg as ServerMessage.Alerts
        assertFalse(alerts.rateReduced)
        assertTrue(alerts.thresholds.isEmpty())
    }

    @Test fun parsesASegmentSummary() {
        val msg = Messages.parse(
            """{"type":"alert_segment","seq":42,"session":"SPS30-20260803_100000.csv",
               "field":"pm2_5","threshold":20.0,"start":"2026-08-03T11:04:05-04:00",
               "stop":"2026-08-03T11:05:44-04:00","first":21.5,"last":19.25,
               "midpoint":44.125,"mean":33.75,"variance":12.5,"minimum":18.0,
               "maximum":91.5,"points":100,"first_index":3745,"last_index":3844,
               "wrap":11,"has_body":true}""".trimIndent()
        )
        val segment = msg as ServerMessage.AlertSegment
        // Every figure the captain asked a preserved segment to carry.
        assertEquals(42, segment.seq)
        assertEquals("SPS30-20260803_100000.csv", segment.session)
        assertEquals("pm2_5", segment.field)
        assertEquals("2026-08-03T11:04:05-04:00", segment.start)
        assertEquals("2026-08-03T11:05:44-04:00", segment.stop)
        assertEquals(21.5, segment.first, 0.0)
        assertEquals(19.25, segment.last, 0.0)
        assertEquals(44.125, segment.midpoint, 0.0)
        assertEquals(33.75, segment.mean, 0.0)
        assertEquals(12.5, segment.variance, 0.0)
        assertEquals(18.0, segment.minimum, 0.0)
        assertEquals(91.5, segment.maximum, 0.0)
        assertEquals(100, segment.points)
        assertEquals(3745, segment.firstIndex)
        assertEquals(3844, segment.lastIndex)
        assertEquals(11, segment.wrap)
        assertTrue(segment.hasBody)
    }

    @Test fun aSegmentWhoseSamplesAreGoneIsStillWholeAsASummary() {
        val msg = Messages.parse(
            """{"type":"alert_segment","seq":43,"session":"SPS30-20260803_100000.csv",
               "field":"pm2_5","threshold":20.0,"start":"2026-08-03T11:04:05-04:00",
               "stop":"2026-08-03T11:05:44-04:00","first":21.5,"last":19.25,
               "midpoint":44.125,"mean":33.75,"variance":12.5,"minimum":18.0,
               "maximum":91.5,"points":100,"first_index":3745,"last_index":3844,
               "wrap":11,"has_body":false}""".trimIndent()
        )
        val segment = msg as ServerMessage.AlertSegment
        assertFalse(segment.hasBody)
        // The point of the header: the samples went and the account of the event
        // did not.
        assertEquals(100, segment.points)
        assertEquals(91.5, segment.maximum, 0.0)
        assertEquals(33.75, segment.mean, 0.0)
    }

    @Test fun theSamplesArriveInTheShapeABackfillArrivesIn() {
        val msg = Messages.parse(
            """{"type":"alert_data","seq":42,"name":"SPS30-20260803_100000.csv",
               "columns":["index","timestamp","pm1_0","pm2_5","pm4_0","pm10","nc0_5",
               "nc1_0","nc2_5","nc4_0","nc10","typical_size","event"],
               "rows":[["0003745","2026-08-03T11:04:05-04:00","21.50","44.25","47.00",
               "48.50","100.00","110.50","120.25","130.00","140.75","0.750",""]],
               "done":true}""".trimIndent()
        )
        val data = msg as ServerMessage.AlertData
        assertEquals(42, data.seq)
        assertEquals("SPS30-20260803_100000.csv", data.name)
        // Byte-identical columns to `file_data`, which is what lets these rows go
        // into the history through the code a backfill already uses - and so
        // through the same timestamp handling.
        assertEquals(Wire.CSV_COLUMNS, data.columns)
        assertEquals(1, data.rows.size)
        assertEquals("0003745", data.rows[0][0])
        assertTrue(data.done)
    }

    @Test fun anOlderAppIgnoresWhatItDoesNotModel() {
        // The mirror of the promise the controller makes: it starts sending these
        // unasked, so a client that models none of them must not be harmed.
        val msg = Messages.parse("""{"type":"something_new","seq":1}""")
        assertTrue(msg is ServerMessage.Unknown)
    }
}

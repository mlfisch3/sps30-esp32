package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import com.sensirion.sps30.wire.mustNotBeDropped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Readings from a controller that never knew the time.
 *
 * The acceptance criteria this file exists for, in order:
 *
 * 1. A board that has never had a clock loses no readings.
 * 2. Once an equivalence arrives, the same data has correct absolute times, and
 *    nothing was rewritten to achieve it.
 * 3. Two runs separated by a power cut stay two runs.
 * 4. A run nothing can place is recognised as such and can be shifted by hand.
 * 5. Absolute sessions behave exactly as they did.
 */
class RelativeTimeTest {

    private fun values(pm: Double) = mapOf(
        "pm1_0" to pm, "pm2_5" to pm + 0.2, "pm4_0" to pm + 0.2, "pm10" to pm + 0.2,
    )

    private fun relativeReading(boot: Int, relMs: Long, index: Int, pm: Double = 2.0) =
        ServerMessage.Reading(
            csv = "SPS30-R%08X-00001925.csv".format(boot),
            index = Wire.formatIndex(index),
            timestamp = "",
            values = values(pm),
            relBoot = boot,
            relMs = relMs,
        )

    private fun history(): Pair<TimeSeriesHistory, TimeBasis> {
        val basis = TimeBasis()
        val h = TimeSeriesHistory(retentionSeconds = 1_000_000.0)
        h.basis = basis
        return h to basis
    }

    // --- 1. nothing is lost --------------------------------------------------

    @Test
    fun `a clockless session loses no readings`() {
        val (h, _) = history()
        for (i in 1..600) {
            assertTrue(
                "reading $i was dropped",
                h.add(relativeReading(854, 6_437L + i * 1009L, i)),
            )
        }
        assertEquals(600, h.size)
        assertTrue(h.hasRelativePoints())
    }

    @Test
    fun `relative readings keep the spacing the board recorded`() {
        val (h, basis) = history()
        for (i in 1..5) h.add(relativeReading(854, 1_000L + (i - 1) * 1009L, i))
        // Nothing places this run, so nothing on the plot's axis describes it -
        // the store holds no epoch for any of the five. What survives is its
        // internal timing, which was never in doubt, and it is read from the run
        // rather than from a column that would have to call it an instant.
        assertTrue(h.snapshot().all { it.epoch == null })
        val elapsed = h.snapshot().map {
            (basis.resolve(it.relBoot!!, it.relMs!!) as TimeBasis.Resolution.Unanchored)
                .elapsedSeconds
        }
        for (i in 1 until elapsed.size) {
            assertEquals(
                "the interval between $i and ${i - 1} is not what was recorded",
                1.009, elapsed[i] - elapsed[i - 1], 1e-6,
            )
        }
    }

    // --- 2. an equivalence places them, without rewriting anything -----------

    @Test
    fun `an equivalence gives the same readings real times`() {
        val (h, basis) = history()
        for (i in 1..10) h.add(relativeReading(854, 10_000L + (i - 1) * 1000L, i))
        val before = h.snapshot()

        // "my clock read 10,000 when the world clock read this instant"
        val anchorEpochMs = 1_785_733_000_000L
        assertTrue(
            basis.accept(
                ServerMessage.TimeMap(
                    854,
                    listOf(ServerMessage.TimeAnchor(854, 10_000L, anchorEpochMs, -240)),
                ),
            ),
        )
        assertEquals(10, h.retime())

        val after = h.snapshot()
        assertEquals(
            "re-placing changed how many readings there are",
            before.size, after.size,
        )
        for (i in after.indices) {
            assertEquals(anchorEpochMs / 1000.0 + i, after[i].epoch!!, 1e-6)
            // The raw relative clock is still there, which is what makes the
            // conversion redoable when a better time source turns up.
            assertEquals(before[i].relMs, after[i].relMs)
            assertEquals(before[i].values, after[i].values)
        }
    }

    @Test
    fun `the nearest equivalence wins, which is what bounds the drift`() {
        val basis = TimeBasis()
        // Two measurements an hour apart; the board's clock lost 200 ms over it.
        basis.accept(
            ServerMessage.TimeMap(
                1,
                listOf(
                    ServerMessage.TimeAnchor(1, 0L, 1_000_000_000_000L, -240),
                    ServerMessage.TimeAnchor(1, 3_600_000L, 1_000_003_600_200L, -240),
                ),
            ),
        )
        val early = basis.resolve(1, 60_000L)
        val late = basis.resolve(1, 3_540_000L)
        assertTrue(early is TimeBasis.Resolution.Derived)
        assertTrue(late is TimeBasis.Resolution.Derived)
        // 60 s from the nearest, not 59 minutes from the first: recording more
        // than one equivalence is what buys that.
        assertEquals(60_000L, (early as TimeBasis.Resolution.Derived).distanceMs)
        assertEquals(60_000L, (late as TimeBasis.Resolution.Derived).distanceMs)
    }

    // --- 3. a power cut is two runs, not one ---------------------------------

    @Test
    fun `an equivalence from another run does not place this one`() {
        val basis = TimeBasis()
        basis.noteBoot(900)
        basis.noteFirstRelMs(900, 5_000L)
        basis.accept(
            ServerMessage.TimeMap(
                900,
                listOf(ServerMessage.TimeAnchor(854, 10_000L, 1_785_733_000_000L, -240)),
            ),
        )

        assertTrue(basis.isAnchored(854))
        assertFalse(
            "an equivalence from before the power cut placed the run after it",
            basis.isAnchored(900),
        )
        assertEquals(listOf(900), basis.unanchoredBoots())
        assertTrue(basis.resolve(900, 6_000L) is TimeBasis.Resolution.Unanchored)
    }

    @Test
    fun `two runs stay two sessions on the plot`() {
        val (h, basis) = history()
        for (i in 1..5) h.add(relativeReading(854, 1_000L + i * 1000L, i))
        for (i in 1..5) h.add(relativeReading(900, 1_000L + i * 1000L, i))
        basis.accept(
            ServerMessage.TimeMap(
                900,
                listOf(ServerMessage.TimeAnchor(854, 2_000L, 1_785_733_000_000L, -240)),
            ),
        )
        h.retime()

        val names = h.snapshot().map { it.csv }.toSet()
        assertEquals("the two runs were merged into one session", 2, names.size)
        // And the unplaced run is not on the axis at all, so nothing can read as
        // a single continuous stretch of recording. It used to sit fifty-six
        // years to the left of the placed one, which is "nowhere near" only until
        // something frames the two of them together.
        val placed = h.snapshot().filter { it.relBoot == 854 }
        val unplaced = h.snapshot().filter { it.relBoot == 900 }
        assertTrue("the placed run lost its times", placed.all { it.epoch != null })
        assertTrue(
            "an unplaceable run was drawn as if it continued a placed one",
            unplaced.all { it.epoch == null },
        )
        assertEquals(unplaced.size, h.unplacedCount())
    }

    // --- 4. an unplaceable run is said to be, and can be moved ---------------

    @Test
    fun `an unplaceable run is kept, flagged, and shiftable`() {
        val (h, basis) = history()
        for (i in 1..20) h.add(relativeReading(77, 4_000L + (i - 1) * 1000L, i))
        assertEquals("readings with no known time were dropped", 20, h.size)
        assertEquals(listOf(77), basis.unanchoredBoots())

        assertTrue(basis.resolve(77, 4_000L) is TimeBasis.Resolution.Unanchored)
        assertTrue(
            "a reading nothing can place was given a position",
            h.snapshot().all { it.epoch == null },
        )
        assertEquals(
            "elapsed time within the run should start at zero",
            0.0,
            (basis.resolve(77, 4_000L) as TimeBasis.Resolution.Unanchored).elapsedSeconds,
            1e-9,
        )

        // "I remember leaving the house at this moment."
        val origin = 1_785_700_000.0
        basis.placeManually(77, origin)
        h.retime()
        val placed = h.snapshot()
        assertEquals(origin, placed.first().epoch!!, 1e-6)
        assertEquals(origin + 19.0, placed.last().epoch!!, 1e-6)
        assertTrue(basis.resolve(77, 4_000L) is TimeBasis.Resolution.Manual)
        assertTrue(basis.unanchoredBoots().isEmpty())

        // Taking it back leaves the readings exactly where they were recorded
        // relative to each other, which was never in doubt.
        basis.placeManually(77, null)
        h.retime()
        assertEquals(20, h.size)
        assertTrue("withdrawing a placement left a time behind", h.snapshot().all { it.epoch == null })
        assertEquals(
            19.0,
            (basis.resolve(77, 23_000L) as TimeBasis.Resolution.Unanchored).elapsedSeconds,
            1e-6,
        )
    }

    @Test
    fun `a measurement beats a recollection`() {
        val basis = TimeBasis()
        basis.noteBoot(5)
        basis.noteFirstRelMs(5, 0L)
        basis.placeManually(5, 1_000_000_000.0)
        assertTrue(basis.resolve(5, 0L) is TimeBasis.Resolution.Manual)
        basis.accept(
            ServerMessage.TimeMap(
                5,
                listOf(ServerMessage.TimeAnchor(5, 0L, 1_785_733_000_000L, -240)),
            ),
        )
        assertTrue(
            "a hand-placed origin overrode a real equivalence",
            basis.resolve(5, 0L) is TimeBasis.Resolution.Derived,
        )
    }

    // --- 5. absolute sessions are untouched ----------------------------------

    @Test
    fun `an absolute reading is unaffected by any of this`() {
        val (h, _) = history()
        val reading = ServerMessage.Reading(
            csv = "SPS30-20260803_004512.csv",
            index = Wire.formatIndex(1),
            timestamp = "2026-08-03T00:45:12-04:00",
            values = values(3.0),
        )
        assertTrue(h.add(reading))
        val sample = h.snapshot().single()
        assertNull("an absolute reading picked up a relative clock", sample.relBoot)
        assertEquals(1_785_732_312.0, sample.epoch!!, 1e-6)
        assertFalse(h.hasRelativePoints())
        assertEquals("re-placing moved an absolute reading", 0, h.retime())
    }

    @Test
    fun `a reading with neither a timestamp nor a relative clock is still refused`() {
        val (h, _) = history()
        assertFalse(
            h.add(
                ServerMessage.Reading(
                    csv = "x.csv", index = "0000001", timestamp = "", values = values(1.0),
                ),
            ),
        )
    }

    // --- the wire ------------------------------------------------------------

    @Test
    fun `a relative reading parses off the wire`() {
        val line = "{\"type\":\"reading\",\"csv\":\"SPS30-R00000356-00001925.csv\"," +
            "\"index\":\"0000017\",\"timestamp\":\"\",\"pm1_0\":1.62,\"pm2_5\":1.91," +
            "\"pm4_0\":2.03,\"pm10\":2.11,\"event\":\"\",\"rel_boot\":854," +
            "\"rel_ms\":21437}"
        val msg = Messages.parse(line)
        assertTrue(msg is ServerMessage.Reading)
        val reading = msg as ServerMessage.Reading
        assertTrue(reading.relative)
        assertEquals(854, reading.relBoot)
        assertEquals(21437L, reading.relMs)
        assertEquals("", reading.timestamp)
    }

    @Test
    fun `a time map parses off the wire`() {
        val line = "{\"type\":\"time_map\",\"boot\":855,\"anchors\":[" +
            "{\"boot\":854,\"rel_ms\":12000,\"epoch_ms\":1754170975000," +
            "\"utc_offset_min\":-240}," +
            "{\"boot\":855,\"rel_ms\":8000,\"epoch_ms\":1754180000000," +
            "\"utc_offset_min\":-300}]}"
        val msg = Messages.parse(line)
        assertTrue(msg is ServerMessage.TimeMap)
        val map = msg as ServerMessage.TimeMap
        assertEquals(855, map.boot)
        assertEquals(2, map.anchors.size)
        assertEquals(1_754_170_975_000L, map.anchors[0].epochMs)
        assertEquals(-300, map.anchors[1].utcOffsetMinutes)
    }

    @Test
    fun `a time map is never dropped under pressure`() {
        val map: ServerMessage = ServerMessage.TimeMap(1, emptyList())
        assertTrue(
            "the only thing that places relative readings was droppable",
            map.mustNotBeDropped(),
        )
    }

    @Test
    fun `a relative session is recognisable by name`() {
        assertTrue(Wire.isRelativeSessionName("SPS30-R00000356-00001925.csv"))
        assertFalse(Wire.isRelativeSessionName("SPS30-20260803_004512.csv"))
        assertFalse(Wire.isRelativeSessionName("SPS30-Rzzzzzzzz-00001925.csv"))
    }

    // --- backfilled rows -----------------------------------------------------

    @Test
    fun `stored rows carrying a relative clock are folded in, not skipped`() {
        val (h, basis) = history()
        val columns = listOf("index", "timestamp") + Wire.FIELDS +
            listOf(Wire.COL_EVENT, Wire.COL_REL_BOOT, Wire.COL_REL_MS)
        val rows = (1..4).map { i ->
            listOf(Wire.formatIndex(i), "") + Wire.FIELDS.map { "1.50" } +
                listOf("", "854", "${5_000 + (i - 1) * 1000}")
        }
        assertEquals(
            4,
            h.addRows("SPS30-R00000356-00001388.csv", columns, rows, explicit = true),
        )
        assertNotNull(h.snapshot().first().relMs)
        assertEquals(listOf(854), basis.unanchoredBoots())

        basis.accept(
            ServerMessage.TimeMap(
                854,
                listOf(ServerMessage.TimeAnchor(854, 5_000L, 1_785_733_000_000L, -240)),
            ),
        )
        h.retime()
        assertEquals(1_785_733_000.0, h.snapshot().first().epoch!!, 1e-6)
    }

    // --- 6. an offset that cannot be a zone is not believed -------------------

    /**
     * The `time_map` read off the captain's board on 2026-08-04, verbatim.
     *
     * Three equivalences a connected phone laid down, and a fourth that SNTP
     * wrote with seconds in the minutes field: -14400 is EDT multiplied by
     * sixty. The board is on boot 9006 and has no equivalence for it at all.
     */
    private fun captainsTimeMap() = ServerMessage.TimeMap(
        9006,
        listOf(
            ServerMessage.TimeAnchor(1202, 328_315L, 1_785_746_736_267L, -240),
            ServerMessage.TimeAnchor(1202, 462_767L, 1_785_746_870_721L, -240),
            ServerMessage.TimeAnchor(1202, 564_675L, 1_785_746_972_626L, -240),
            ServerMessage.TimeAnchor(3395, 159_395L, 1_785_831_331_731L, -14400),
        ),
    )

    @Test
    fun `an equivalence whose offset cannot be a zone is refused`() {
        val basis = TimeBasis()
        basis.accept(captainsTimeMap())
        basis.noteBoot(1202)
        basis.noteBoot(3395)
        basis.noteBoot(9006)

        assertEquals("a corrupt equivalence was taken in", 3, basis.anchorCount)
        assertTrue("the three good equivalences were lost", basis.isAnchored(1202))
        assertFalse(
            "an offset of ten days was believed",
            basis.isAnchored(3395),
        )
        assertTrue(basis.resolve(3395, 159_395L) is TimeBasis.Resolution.Unanchored)
        assertNull(
            "a run with only a corrupt equivalence was still put on the axis",
            basis.epochOrNull(3395, 159_395L),
        )
        // Unplaced, and said to be: the run is offered for manual placement
        // alongside the one that never had an equivalence at all.
        assertEquals(listOf(3395, 9006), basis.unanchoredBoots())

        // The good run is untouched. Refusing one equivalence is not distrusting
        // the board.
        val good = basis.resolve(1202, 462_767L)
        assertTrue(good is TimeBasis.Resolution.Derived)
        assertEquals(1_785_746_870.721, (good as TimeBasis.Resolution.Derived).epochSeconds, 1e-6)
        assertEquals(-240, good.utcOffsetMinutes)
    }

    @Test
    fun `a corrupt equivalence loses to a good one even when it is nearer`() {
        val basis = TimeBasis()
        basis.accept(
            ServerMessage.TimeMap(
                3395,
                listOf(
                    ServerMessage.TimeAnchor(3395, 100_000L, 1_785_831_000_000L, -240),
                    ServerMessage.TimeAnchor(3395, 159_395L, 1_785_831_331_731L, -14400),
                ),
            ),
        )

        // 395 ms from the corrupt one and 59 s from the good one, so "nearest"
        // picks the corrupt one unless it was never let in.
        val at = basis.resolve(3395, 159_000L)
        assertTrue(at is TimeBasis.Resolution.Derived)
        assertEquals(
            "the nearer equivalence won despite being the impossible one",
            1_785_831_059.0,
            (at as TimeBasis.Resolution.Derived).epochSeconds,
            1e-6,
        )
        assertEquals(-240, at.utcOffsetMinutes)
    }

    @Test
    fun `a corrupt equivalence is refused coming back out of the cache too`() {
        val basis = TimeBasis()
        basis.restore(
            TimeBasis.State(
                currentBoot = 9006,
                anchors = captainsTimeMap().anchors,
                manualOrigins = emptyMap(),
                firstRelMs = emptyMap(),
            ),
        )

        assertEquals("a cached corrupt equivalence was taken in", 3, basis.anchorCount)
        assertFalse(basis.isAnchored(3395))
        assertTrue(basis.isAnchored(1202))
    }

    // --- 7. a reading dated after tomorrow is not one -------------------------

    /**
     * The captain's board on 2026-08-04, read off his tablet's plot cache.
     *
     * The live session `SPS30-20260804_074435` is correctly dated: the board's
     * clock is right. Beside it sits `SPS30-21321011_113448`, thirty-one
     * readings whose session start was `5136780888`. That is not an instant -
     * it is 5,136,780,888 **microseconds** of uptime, one hour twenty-five
     * minutes, in a field read as epoch seconds. The board named the file from
     * the same number, which is why it says the year 2132.
     *
     * Boot 9006 is current and has no equivalence, and every surviving
     * equivalence belongs to boot 1202, so the relative block stays unplaced
     * throughout: this is the clocked stream and the boot mismatch together,
     * which is the situation as it actually stood.
     */
    private val boardNow = 1_785_877_381.0 // 2026-08-04 21:03:01Z, the newest live reading
    private val futureSessionStart = 5_136_780_888.0 // 2132-10-11, and 1h25m of uptime

    private fun clockedHistory(): Pair<TimeSeriesHistory, TimeBasis> {
        val basis = TimeBasis()
        // Retention long enough that what these tests assert about is placement,
        // never the 48h horizon quietly taking a reading back.
        val h = TimeSeriesHistory(retentionSeconds = 1_000_000_000.0, nowSeconds = { boardNow })
        h.basis = basis
        basis.accept(captainsTimeMap())
        return h to basis
    }

    private fun absoluteReading(csv: String, index: Int, epochSeconds: Double) =
        ServerMessage.Reading(
            csv = csv,
            index = Wire.formatIndex(index),
            timestamp = java.time.Instant.ofEpochSecond(epochSeconds.toLong())
                .atOffset(java.time.ZoneOffset.UTC).toString(),
            values = values(1.5),
        )

    @Test
    fun `a reading the controller dated in the year 2132 is not drawn there`() {
        val (h, basis) = clockedHistory()

        // The live session, correctly dated, and the poisoned one beside it.
        for (i in 1..5) {
            h.add(absoluteReading("SPS30-20260804_074435.csv", i, boardNow - (5 - i)))
        }
        for (i in 1..31) {
            h.add(absoluteReading("SPS30-21321011_113448.csv", i, futureSessionStart + i))
        }
        // And the relative block, which no surviving equivalence can place.
        for (i in 1..4) h.add(relativeReading(9006, 1_000L + i * 1000L, i))

        assertEquals("readings the board really took were thrown away", 40, h.size)

        // The whole of the fix, in one assertion: nothing is on the axis after
        // this device's own clock, so "now" cannot be dragged into the future.
        val latest = h.latestEpoch()!!
        assertTrue(
            "a reading dated 2132 was placed, and it takes the axis with it",
            latest <= boardNow + TimeSeriesHistory.FUTURE_GRACE_SECONDS,
        )
        assertEquals(boardNow, latest, 1e-6)

        // The impossible session is kept and counted, not discarded and not drawn.
        assertEquals(35, h.unplacedCount()) // 31 from 2132, 4 with no equivalence
        val future = h.snapshot().filter { it.csv == "SPS30-21321011_113448.csv" }
        assertEquals(31, future.size)
        assertTrue("a reading from 2132 kept a position", future.all { it.epoch == null })

        // The correctly dated session is untouched. One bad session does not
        // cost the captain the recording beside it.
        val live = h.snapshot().filter { it.csv == "SPS30-20260804_074435.csv" }
        assertEquals(5, live.size)
        assertTrue(live.all { it.epoch != null })

        // Boot 9006 is current, has no equivalence of its own, and says so.
        assertEquals(9006, basis.currentBoot)
        assertFalse(basis.isAnchored(9006))
        assertTrue(basis.unanchoredBoots().contains(9006))
    }

    @Test
    fun `the axis a clocked board with one poisoned session produces is sane`() {
        val (h, _) = clockedHistory()
        for (i in 1..120) {
            h.add(absoluteReading("SPS30-20260804_074435.csv", i, boardNow - (120 - i)))
        }
        for (i in 1..31) {
            h.add(absoluteReading("SPS30-21321011_113448.csv", i, futureSessionStart + i))
        }

        // "now" as the screen computes it: the later of the wall clock and the
        // newest reading. This is the step that turned one bad point into a
        // hundred-and-six-year axis.
        val now = maxOf(boardNow, h.latestEpoch() ?: boardNow)
        // Exactly what the Auto button does - MonitorSession.autoSet().
        val chart = ChartView(windowSeconds = 300.0)
        chart.setAutoInterval()
        chart.applyAll(h.firstStableEpoch() ?: now, now)
        val data = chart.compute(h, now, maxPoints = 600)

        assertTrue(
            "the axis still spans ${data.spanSeconds / 31_556_952.0} years",
            data.spanSeconds < 2 * 86_400.0,
        )
        assertEquals("the live session was averaged away", 120, data.sampleCount)
        // Auto picks its averaging from the visible span, so an absurd span was
        // what produced a 465-hour bucket that changed with screen width. At two
        // minutes of data it should not be averaging at all.
        assertNull(
            "Auto is still averaging over ${data.autoChosenSeconds}s",
            data.autoChosenSeconds,
        )
    }

    @Test
    fun `an older session is still placed - the check is forwards only`() {
        val (h, _) = clockedHistory()
        // A year old. Ordinary to copy in from the board or import from a file,
        // and refusing it would break a real use to catch a fault that has only
        // ever come from the other direction.
        h.add(absoluteReading("SPS30-20250804_074435.csv", 1, boardNow - 31_556_952.0))
        h.add(absoluteReading("SPS30-20260804_074435.csv", 2, boardNow))

        assertEquals(2, h.size)
        assertEquals(0, h.unplacedCount())
        assertEquals(boardNow - 31_556_952.0, h.earliestEpoch()!!, 1e-6)
    }

    @Test
    fun `the ordinary clock skew of a board just told the time is still believed`() {
        val (h, _) = clockedHistory()
        // A minute ahead: real, common, and nothing like a unit slip.
        h.add(absoluteReading("SPS30-20260804_074435.csv", 1, boardNow + 60.0))
        assertEquals(0, h.unplacedCount())
        assertEquals(boardNow + 60.0, h.latestEpoch()!!, 1e-6)
    }

    @Test
    fun `the real range of zones is kept and only what is past it is refused`() {
        // Baker Island and the Line Islands, the two ends of the inhabited
        // world, must both survive the check that catches -14400.
        assertTrue(TimeBasis.plausibleUtcOffset(-720))
        assertTrue(TimeBasis.plausibleUtcOffset(840))
        assertTrue(TimeBasis.plausibleUtcOffset(-240))
        assertTrue(TimeBasis.plausibleUtcOffset(0))
        assertTrue("a 45-minute zone like Kathmandu", TimeBasis.plausibleUtcOffset(345))
        assertFalse(TimeBasis.plausibleUtcOffset(-721))
        assertFalse(TimeBasis.plausibleUtcOffset(841))
        assertFalse("EDT in seconds", TimeBasis.plausibleUtcOffset(-14400))
        assertFalse("EST in seconds", TimeBasis.plausibleUtcOffset(-18000))
        assertFalse("IST in seconds", TimeBasis.plausibleUtcOffset(19800))
    }
}

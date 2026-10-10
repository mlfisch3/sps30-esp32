package com.sensirion.sps30.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the time axis puts its labels.
 *
 * The complaint was that they landed on arbitrary instants - 14:59, 20:20, 01:41,
 * 07:02 - evenly spaced and unreadable as clock times. So the property under test
 * throughout is **roundness in local time**, and it is checked by converting each
 * tick back to a wall clock rather than by comparing against numbers worked out
 * by hand: an expected-value test written in epoch seconds would encode the same
 * offset arithmetic it is supposed to be checking.
 *
 * The daylight-saving cases are here because that arithmetic is where this
 * project has repeatedly gone wrong, and because a shift is precisely the event
 * that turns "every N seconds from an anchor" into "an hour off the round value,
 * for the next six months".
 */
class TimeTicksTest {

    private val zone: ZoneId = TimeSeriesHistory.EASTERN

    private fun at(local: String): Double =
        LocalDateTime.parse(local).atZone(zone).toEpochSecond().toDouble()

    private fun localOf(epoch: Double): LocalDateTime =
        Instant.ofEpochSecond(epoch.toLong()).atZone(zone).toLocalDateTime()

    /** The clock reading of each tick, which is the thing the user actually sees. */
    private fun clocks(ticks: DoubleArray): List<String> =
        ticks.map { localOf(it).toLocalTime().toString() }

    /** Seconds since local midnight - what "round" is measured from. */
    private fun intoDay(epoch: Double): Long {
        val local = localOf(epoch)
        return Duration.between(local.toLocalDate().atStartOfDay(), local).seconds
    }

    private fun assertAllRound(ticks: DoubleArray, step: Long) {
        for (t in ticks) {
            if (step >= TimeTicks.DAY) {
                assertEquals(
                    "tick at ${localOf(t)} is not local midnight",
                    0L,
                    intoDay(t),
                )
            } else {
                assertEquals(
                    "tick at ${localOf(t)} is ${intoDay(t) % step}s off a $step s boundary",
                    0L,
                    intoDay(t) % step,
                )
            }
        }
    }

    // --- the ladder ----------------------------------------------------------

    /**
     * Every sub-day rung divides a day exactly.
     *
     * This is what makes "a whole number of steps since local midnight" mean
     * anything: an interval of, say, seven minutes has boundaries that fall at a
     * different time every day, and labels on them would be no rounder than the
     * arbitrary instants this replaced.
     */
    @Test
    fun `every interval under a day divides a day`() {
        for (step in TimeTicks.LADDER.filter { it < TimeTicks.DAY }) {
            assertEquals("$step s does not divide a day", 0L, TimeTicks.DAY % step)
        }
    }

    /** And the ladder is the one that was asked for, in order. */
    @Test
    fun `the ladder is seconds, minutes, hours, days, weeks - ascending`() {
        assertEquals(TimeTicks.LADDER.sorted(), TimeTicks.LADDER)
        assertEquals(TimeTicks.LADDER.distinct(), TimeTicks.LADDER)
        assertTrue(listOf(5L, 10L, 15L, 30L).all { it in TimeTicks.LADDER })
        assertTrue(listOf(5, 10, 15, 30).map { it * TimeTicks.MINUTE }
            .all { it in TimeTicks.LADDER })
        assertTrue(listOf(3, 6, 12).map { it * TimeTicks.HOUR }.all { it in TimeTicks.LADDER })
        assertTrue(TimeTicks.DAY in TimeTicks.LADDER && TimeTicks.WEEK in TimeTicks.LADDER)
    }

    /**
     * The interval scales the whole way, and never yields more labels than asked
     * for. Swept rather than sampled: the fault this guards is a rung that is
     * fine at the spans anyone thought to try and absurd at one between them.
     */
    @Test
    fun `every span from a second to a year gets a sensible interval`() {
        var span = 1.0
        while (span < 400 * TimeTicks.DAY) {
            val step = TimeTicks.interval(span)
            val count = span / step
            assertTrue(
                "span ${span}s chose a ${step}s interval, which is $count ticks",
                count <= TimeTicks.TARGET_TICKS + 1e-9,
            )
            // And not so coarse that the axis is left with one label or none. The
            // rungs are at most a factor of two apart below a day, so the count
            // cannot fall below half the target - except at spans shorter than
            // the finest rung, where there is nothing finer to choose.
            if (span > TimeTicks.TARGET_TICKS) {
                assertTrue(
                    "span ${span}s chose a ${step}s interval, which is only $count ticks",
                    count >= 2.0,
                )
            }
            span *= 1.07
        }
    }

    /**
     * The complaint and its answer, spelled out.
     *
     * These are the two windows he named, opened at an arbitrary moment - the
     * kind of moment that produced "14:59, 20:20, 01:41, 07:02". The exact label
     * lists are written down rather than described, because the whole of the
     * complaint was about which strings appear, and a test that only checked the
     * arithmetic would pass on an axis nobody could read.
     */
    @Test
    fun `the labels a minute and an hour of data actually get`() {
        val end = at("2026-08-06T14:47:13")

        val minute = TimeTicks.ticks(end - 60, end, zone, TimeTicks.interval(60.0))
        // "14:47" rather than "14:47:00" only because LocalTime prints it that
        // way; the axis formats every one of these as HH:mm:ss at this interval.
        assertEquals(
            listOf("14:46:20", "14:46:30", "14:46:40", "14:46:50", "14:47", "14:47:10"),
            clocks(minute),
        )

        val hour = TimeTicks.ticks(end - 3600, end, zone, TimeTicks.interval(3600.0))
        assertEquals(
            listOf("13:50", "14:00", "14:10", "14:20", "14:30", "14:40"),
            clocks(hour).map { it.take(5) },
        )
    }

    /** A one-hour window lands on ten-minute boundaries, half past among them. */
    @Test
    fun `an hour of data is labelled on round minutes`() {
        val start = at("2026-08-06T14:47:13")
        val ticks = TimeTicks.ticks(start, start + TimeTicks.HOUR, zone, TimeTicks.interval(3600.0))
        assertAllRound(ticks, TimeTicks.interval(3600.0))
        assertTrue("no half hour among $ticks", clocks(ticks).any { it.endsWith(":30") })
        // The complaint, in its own terms: nothing like 14:47 survives.
        assertTrue(clocks(ticks).none { it.startsWith("14:47") })
    }

    /** A day on screen is labelled on the hours, not on some multiple of 14:47. */
    @Test
    fun `a day of data is labelled on round hours`() {
        val start = at("2026-08-06T14:47:13")
        val step = TimeTicks.interval(TimeTicks.DAY.toDouble())
        val ticks = TimeTicks.ticks(start, start + TimeTicks.DAY, zone, step)
        assertAllRound(ticks, step)
        assertTrue("a whole day should carry a midnight", clocks(ticks).contains("00:00"))
    }

    /** Several days, which is what Auto shows: whole local midnights. */
    @Test
    fun `several days are labelled on local midnights`() {
        val start = at("2026-08-03T09:17:41")
        val span = 6.0 * TimeTicks.DAY
        val step = TimeTicks.interval(span)
        assertEquals("six days should be labelled daily", TimeTicks.DAY, step)
        val ticks = TimeTicks.ticks(start, start + span, zone, step)
        assertAllRound(ticks, step)
        assertTrue("expected a label a day", ticks.size >= 5)
        assertTrue("labels should be dates by now", TimeTicks.labelsAreDates(step))
    }

    /** A month or more falls back to weeks, anchored on real Mondays. */
    @Test
    fun `weekly ticks land on Mondays at local midnight`() {
        val start = at("2026-07-04T00:00:00")
        val span = 40.0 * TimeTicks.DAY
        val step = TimeTicks.interval(span)
        assertTrue("a 40-day span should be weekly or coarser", step >= TimeTicks.WEEK)
        val ticks = TimeTicks.ticks(start, start + span, zone, step)
        assertAllRound(ticks, step)
        for (t in ticks) {
            assertEquals(
                "tick at ${localOf(t)} is not a Monday",
                java.time.DayOfWeek.MONDAY,
                localOf(t).dayOfWeek,
            )
        }
    }

    // --- daylight saving -----------------------------------------------------

    /**
     * Spring forward, half-hourly.
     *
     * 2026-03-08 02:00 EST becomes 03:00 EDT. Stepping in epoch seconds from an
     * anchor before it puts every later label on :47 and :17 - the exact fault
     * being fixed, arriving through the back door. Every label must still read as
     * a round half hour, and the ticks must stay half an hour apart in *real*
     * time, because real time is what the axis measures.
     */
    @Test
    fun `half-hourly labels stay round across the spring shift`() {
        val start = at("2026-03-08T00:00:00")
        val end = start + 6 * TimeTicks.HOUR
        val ticks = TimeTicks.ticks(start, end, zone, 30 * TimeTicks.MINUTE)

        assertAllRound(ticks, 30 * TimeTicks.MINUTE)
        // The hour that did not happen carries no labels.
        assertTrue("02:00 did not exist that day", clocks(ticks).none { it.startsWith("02:") })
        assertTrue(clocks(ticks).containsAll(listOf("01:30", "03:00", "03:30")))
        // Evenly spaced in real time, which is what the plot is linear in.
        val gaps = ticks.toList().zipWithNext { a, b -> b - a }
        assertTrue(
            "ticks are not evenly spaced across the shift: $gaps",
            gaps.all { it == 1800.0 },
        )
    }

    /**
     * Autumn, half-hourly: the hour that happens twice is labelled twice.
     *
     * 2026-11-01 02:00 EDT falls back to 01:00 EST, so 01:00 and 01:30 each occur
     * at two different instants. Both are real, both are round, and the axis is
     * linear in real time - so both get a tick, and the spacing never breaks.
     */
    @Test
    fun `the repeated autumn hour is labelled twice`() {
        val start = at("2026-11-01T00:00:00")
        val end = start + 6 * TimeTicks.HOUR
        val ticks = TimeTicks.ticks(start, end, zone, 30 * TimeTicks.MINUTE)

        assertAllRound(ticks, 30 * TimeTicks.MINUTE)
        assertEquals("01:00 happened twice", 2, clocks(ticks).count { it == "01:00" })
        assertEquals("01:30 happened twice", 2, clocks(ticks).count { it == "01:30" })
        val gaps = ticks.toList().zipWithNext { a, b -> b - a }
        assertTrue("ticks are not evenly spaced: $gaps", gaps.all { it == 1800.0 })
    }

    /**
     * Six-hourly across the spring shift.
     *
     * Six hours is not a divisor of the one-hour offset change, so this is the
     * case that cannot have both: either the labels stay on 00/06/12/18 and one
     * gap is an hour short, or the gaps stay equal and every label after the
     * shift is an hour off. Round wins - the axis is read by its labels, and a
     * day on which one gap really was five hours long is what happened.
     */
    @Test
    fun `six-hourly labels stay on the same hours across the spring shift`() {
        val start = at("2026-03-07T18:00:00")
        val ticks = TimeTicks.ticks(start, start + 2 * TimeTicks.DAY, zone, 6 * TimeTicks.HOUR)

        assertAllRound(ticks, 6 * TimeTicks.HOUR)
        assertTrue(clocks(ticks).all { it in setOf("00:00", "06:00", "12:00", "18:00") })
        // The short day is the point: one gap is five hours, and it is the one
        // containing the shift.
        val gaps = ticks.toList().zipWithNext { a, b -> b - a }
        assertEquals("exactly one gap should be short", 1, gaps.count { it == 5.0 * 3600 })
        assertTrue("every other gap should be six hours", gaps.all { it == 5.0 * 3600 || it == 6.0 * 3600 })
    }

    /** And a daily axis crossing the shift still sits on midnight, not 01:00. */
    @Test
    fun `daily labels stay on midnight across a shift`() {
        val start = at("2026-03-06T00:00:00")
        val ticks = TimeTicks.ticks(start, start + 5 * TimeTicks.DAY, zone, TimeTicks.DAY)
        assertAllRound(ticks, TimeTicks.DAY)
        assertEquals(6, ticks.size)
        // The 23-hour day is real and must show up as one.
        assertTrue(ticks.toList().zipWithNext { a, b -> b - a }.contains(23.0 * 3600))
    }

    // --- general shape -------------------------------------------------------

    /** Ticks are ascending, inside the range, and never duplicated. */
    @Test
    fun `ticks are ordered and inside the range`() {
        var span = 3.0
        while (span < 90 * TimeTicks.DAY) {
            val start = at("2026-03-07T13:41:07")
            val ticks = TimeTicks.ticks(start, start + span, zone, TimeTicks.interval(span))
            assertTrue("a span of ${span}s produced no ticks", ticks.isNotEmpty())
            for (i in ticks.indices) {
                assertTrue("tick outside the range at span $span", ticks[i] in start..(start + span))
                if (i > 0) {
                    assertTrue("ticks out of order at span $span", ticks[i] > ticks[i - 1])
                }
            }
            span *= 1.31
        }
    }

    /** The interval between labels is uniform, which is the part he already liked. */
    @Test
    fun `ticks are evenly spaced away from a shift`() {
        val start = at("2026-08-06T14:47:13")
        for (span in listOf(60.0, 600.0, 3600.0, 6.0 * 3600, 86_400.0)) {
            val ticks = TimeTicks.ticks(start, start + span, zone, TimeTicks.interval(span))
            val gaps = ticks.toList().zipWithNext { a, b -> b - a }
            assertTrue(
                "span ${span}s gave uneven gaps $gaps",
                gaps.all { kotlin.math.abs(it - gaps.first()) < 1e-6 },
            )
        }
    }

    /** Formats follow the interval, not the span: no column of `:00` seconds. */
    @Test
    fun `the label format follows the interval`() {
        val moment = at("2026-08-06T14:30:00")
        assertEquals("14:30:00", TimeTicks.labelFormat(15L).format(Instant.ofEpochSecond(moment.toLong()).atZone(zone)))
        assertEquals("14:30", TimeTicks.labelFormat(TimeTicks.MINUTE).format(Instant.ofEpochSecond(moment.toLong()).atZone(zone)))
        assertEquals("Aug 6", TimeTicks.labelFormat(TimeTicks.DAY).format(Instant.ofEpochSecond(moment.toLong()).atZone(zone)))
    }

    /**
     * A zone that is not the one the phone happens to be in.
     *
     * The axis is plotted in the *sensor's* zone, so the boundaries have to be
     * that zone's - and a zone whose offset is not a whole number of hours is
     * where an implementation that quietly assumed otherwise comes apart.
     * Kathmandu is UTC+05:45, so its ten-minute boundaries are five minutes off
     * every ten-minute boundary UTC has: anchoring to the epoch instead of to the
     * local day would pass in New York and fail here.
     */
    @Test
    fun `boundaries are the plotted zone's, not UTC's`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // UTC+05:45
        val start = LocalDateTime.parse("2026-08-06T14:47:13").atZone(kathmandu).toEpochSecond()
            .toDouble()
        val ticks = TimeTicks.ticks(start, start + TimeTicks.HOUR, kathmandu, 10 * TimeTicks.MINUTE)
        assertTrue(ticks.isNotEmpty())
        for (t in ticks) {
            val local = Instant.ofEpochSecond(t.toLong()).atZone(kathmandu).toLocalDateTime()
            val into = Duration.between(local.toLocalDate().atStartOfDay(), local).seconds
            assertEquals("tick at $local is not on a ten-minute boundary", 0L, into % 600L)
        }
        assertNotEquals(0L, ticks.first().toLong() % 600L)
    }
}

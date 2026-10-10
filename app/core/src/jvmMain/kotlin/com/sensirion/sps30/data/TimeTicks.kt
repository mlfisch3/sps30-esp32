package com.sensirion.sps30.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the labelled instants on a time axis go.
 *
 * ## The fault this replaces
 *
 * The axis used to divide the visible range into five equal bands and label
 * their centres. That is evenly spaced, which is right, and it lands on
 * arbitrary instants, which is not: a window opened at 14:47:13 was labelled
 * 14:59, 20:20, 01:41, 07:02 - four numbers with no relation to any clock a
 * person reads. Even spacing is not the property that makes an axis legible;
 * even spacing **on round times** is, because that is what lets the eye put a
 * feature at "just after half past" without doing arithmetic.
 *
 * So the phase changes and the uniform interval stays. Two decisions make that
 * up, and they are separate on purpose:
 *
 *  - [interval] picks how far apart the ticks are, from a ladder of intervals
 *    people actually read clocks in - seconds, 5/10/15/30 s, minutes, 5/10/15/30
 *    min, hours, 3/6/12 h, days, weeks - choosing the one that yields about
 *    [TARGET_TICKS] of them across the span.
 *  - [ticks] places them on the **local** boundaries of that interval, so a
 *    half-hour tick is on a real local half hour and a daily one is on real
 *    local midnight.
 *
 * ## Why local time is the hard part
 *
 * "Every [step] seconds from some anchor" and "on the local half hour" are the
 * same statement only while the offset from UTC holds still, and twice a year it
 * does not. Stepping in epoch seconds through a daylight-saving shift puts every
 * label after it an hour off the round value - which is the fault this class
 * exists to fix, arriving by the back door. Stepping in local wall-clock time
 * instead trades that for two new problems: the spring-forward hour does not
 * exist (02:00 is not a time that happened, and asking for it gets you 03:00
 * under another name), and the autumn hour happens twice.
 *
 * What is done here is neither: the walk advances by [step] in **epoch** seconds,
 * which is what keeps the ticks evenly spaced on the plot, and every step is then
 * **snapped to the nearest local boundary**, which is what keeps every label
 * round. Away from a transition the snap moves nothing. Across one it moves the
 * tick by the offset change, and both awkward cases come out right:
 *
 *  - Spring forward, half-hourly: … 01:00, 01:30, 03:00, 03:30 … - the hour that
 *    did not happen carries no labels, and the ticks stay half an hour apart in
 *    real time, which is what the axis is measuring.
 *  - Autumn, half-hourly: … 01:00 EDT, 01:30 EDT, 01:00 EST, 01:30 EST … - the
 *    repeated hour is labelled twice, because it really did happen twice.
 *  - Six-hourly across either: 00:00, 06:00, 12:00, 18:00 stay on those hours;
 *    one of the gaps is five or seven hours wide, because one of the days was.
 *
 * Guarded by `TimeTicksTest`, which walks both transitions rather than reasoning
 * about them.
 */
internal object TimeTicks {

    const val MINUTE = 60L
    const val HOUR = 60L * MINUTE
    const val DAY = 24L * HOUR
    const val WEEK = 7L * DAY

    /**
     * The intervals a clock is read in, shortest first.
     *
     * Every entry below [DAY] divides a day exactly, which is what makes "a
     * multiple of [step] since local midnight" a meaningful test of roundness -
     * an interval of 7 minutes would put its boundaries at a different time each
     * day and be no rounder than the arbitrary instants this replaces.
     * `TimeTicksTest` asserts that property of the list rather than trusting the
     * reader to check it.
     */
    val LADDER: List<Long> = listOf(
        1L, 2L, 5L, 10L, 15L, 30L,
        MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
        HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
        // Three and four days are on the ladder because two and seven are three
        // and a half apart, and a span in that gap - a fortnight, which is an
        // ordinary thing to have recorded - was rounded up to weekly and left
        // with two labels on it. Every rung here is still a local midnight, which
        // is what round means at this scale.
        DAY, 2 * DAY, 3 * DAY, 4 * DAY, WEEK, 2 * WEEK, 4 * WEEK,
    )

    /**
     * How many ticks the interval aims for across the visible span.
     *
     * Six rather than the five bands the old axis drew, because the renderer
     * declines to draw a label that would run off the end of the plot (see
     * `PmChart`) and the outermost tick often is one. Aiming for six leaves about
     * five labels standing, which is the density that was already there and
     * liked.
     */
    const val TARGET_TICKS = 6

    /** How many ticks may be generated for one axis, whatever the arithmetic says. */
    private const val MAX_TICKS = 512

    private val FMT_HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val FMT_HM = DateTimeFormatter.ofPattern("HH:mm")
    private val FMT_DATE = DateTimeFormatter.ofPattern("MMM d")

    /**
     * The interval to space ticks at over [spanSeconds]: the shortest rung of
     * [LADDER] that yields no more than [target] of them.
     *
     * Rounding *up* to a rung rather than to the nearest keeps the count at or
     * below the target - a chart is easier to read with one label too few than
     * with two too many - and past the top of the ladder it keeps going in whole
     * four-week blocks, so even a year-long axis is labelled on Mondays rather
     * than on some multiple of the span.
     */
    fun interval(spanSeconds: Double, target: Int = TARGET_TICKS): Long {
        if (!spanSeconds.isFinite() || spanSeconds <= 0.0) return 1L
        val ideal = spanSeconds / target.coerceAtLeast(1)
        LADDER.firstOrNull { it >= ideal }?.let { return it }
        val blocks = ceil(ideal / (4.0 * WEEK)).toLong().coerceAtLeast(1L)
        return blocks * 4 * WEEK
    }

    /**
     * How many rungs finer than the window's own interval the tick-density
     * control may step. Three is what the request that created the control
     * needs - six-hourly ticks reach hourly - and each stop roughly doubles
     * the gridlines, so a fourth would put over a thousand of them on a wide
     * span for no legible gain.
     */
    const val DENSITY_MAX = 3

    /**
     * The interval [notches] rungs of [LADDER] finer than the one [interval]
     * picks for [spanSeconds].
     *
     * This is the tick-density control's whole ladder: the steps it cycles
     * through are *relative to the window* by construction - from a five-day
     * window they are half-days and hours, from an hour they are minutes -
     * because the walk starts wherever the span's own interval sits. Clamped
     * at the finest rung, and at zero notches it is exactly [interval].
     */
    fun finerInterval(spanSeconds: Double, notches: Int, target: Int = TARGET_TICKS): Long {
        val base = interval(spanSeconds, target)
        if (notches <= 0) return base
        val at = LADDER.indexOfFirst { it >= base }.let { if (it == -1) LADDER.size else it }
        return LADDER[(at - notches).coerceAtLeast(0)]
    }

    /**
     * How many useful stops the density cycle has over [spanSeconds]: the
     * default plus every rung [finerInterval] can actually reach, so the
     * control never cycles through steps that draw the same axis twice.
     */
    fun densityStops(spanSeconds: Double, target: Int = TARGET_TICKS): Int {
        val base = interval(spanSeconds, target)
        val at = LADDER.indexOfFirst { it >= base }.let { if (it == -1) LADDER.size else it }
        return at.coerceAtMost(DENSITY_MAX) + 1
    }

    /** True when [step] is coarse enough that each label is a date, not a time. */
    fun labelsAreDates(step: Long): Boolean = step >= DAY

    /**
     * The format a label carries at [step]: seconds only while ticks are less
     * than a minute apart, a date once they are a day or more.
     *
     * At a step of a minute or more the seconds are always `00`, so printing them
     * spends width on a column of zeroes; at a step of a day or more the time is
     * always `00:00` for the same reason and the date is the part that varies.
     */
    fun labelFormat(step: Long): DateTimeFormatter = when {
        step < MINUTE -> FMT_HMS
        step < DAY -> FMT_HM
        else -> FMT_DATE
    }

    /**
     * The instants to draw ticks at over `[start, end]`, evenly spaced at [step]
     * and each on a round boundary of [step] in [zone].
     *
     * Ticks may land on the very ends of the range; placing them is this
     * function's job and deciding whether a label fits there is the renderer's,
     * because only the renderer knows how wide the text is.
     */
    fun ticks(start: Double, end: Double, zone: ZoneId, step: Long): DoubleArray {
        if (!start.isFinite() || !end.isFinite() || end <= start || step <= 0L) {
            return doubleArrayOf(start)
        }
        val out = ArrayList<Double>()
        if (step >= DAY) {
            appendDayTicks(start, end, zone, step / DAY, out)
        } else {
            appendTimeTicks(start, end, zone, step, out)
        }
        // A span shorter than one rung of the ladder can contain no boundary at
        // all. An axis with no label on it says nothing, so it falls back to its
        // own midpoint - which is what the old band-centre axis would have drawn.
        if (out.isEmpty()) return doubleArrayOf((start + end) / 2.0)
        return out.toDoubleArray()
    }

    /**
     * Sub-day ticks: step in epoch seconds, snap each to the nearest local
     * boundary. See the class comment for why that combination and not either
     * half of it.
     */
    private fun appendTimeTicks(
        start: Double,
        end: Double,
        zone: ZoneId,
        step: Long,
        out: MutableList<Double>,
    ) {
        var at = boundary(floor(start).toLong(), zone, step, down = true)
        var guard = 0
        while (guard++ < MAX_TICKS && at.toDouble() <= end) {
            if (at.toDouble() >= start) out.add(at.toDouble())
            val next = boundary(at + step, zone, step, down = false)
            // The snap moves a tick by less than half a step, so the next one is
            // always ahead of this one; the fallback is a backstop for a zone
            // whose rules are stranger than any in use.
            at = if (next > at) next else at + step
        }
    }

    /**
     * Whole-day ticks: every [days]-th local midnight, counted from a Monday so
     * a weekly axis is labelled on Mondays rather than on whatever day the epoch
     * happened to start.
     *
     * Dates rather than instants, because "midnight" is a property of the local
     * calendar and adding 86400 seconds to one does not reliably reach the next.
     */
    private fun appendDayTicks(
        start: Double,
        end: Double,
        zone: ZoneId,
        days: Long,
        out: MutableList<Double>,
    ) {
        val startDay = Instant.ofEpochSecond(floor(start).toLong()).atZone(zone).toLocalDate()
        var day = Math.floorDiv(startDay.toEpochDay() - MONDAY_EPOCH_DAY, days) * days +
            MONDAY_EPOCH_DAY
        var guard = 0
        while (guard++ < MAX_TICKS) {
            // atStartOfDay, not atTime(0, 0): a handful of zones skip midnight on
            // a transition day, and this asks for "the day's first instant"
            // rather than for a wall-clock time that may not have happened.
            val at = LocalDate.ofEpochDay(day).atStartOfDay(zone).toEpochSecond().toDouble()
            if (at > end) break
            if (at >= start) out.add(at)
            day += days
        }
    }

    /**
     * The boundary of [step] nearest [epoch] in [zone] - or the one at or before
     * it when [down].
     *
     * Measured from local midnight, which is what makes a half-hour boundary the
     * local half hour rather than a half hour past some UTC anchor.
     */
    private fun boundary(epoch: Long, zone: ZoneId, step: Long, down: Boolean): Long {
        val local = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDateTime()
        val midnight = local.toLocalDate().atStartOfDay()
        val into = Duration.between(midnight, local).seconds
        val steps = if (down) Math.floorDiv(into, step) else Math.floorDiv(into + step / 2, step)
        return midnight.plusSeconds(steps * step).atZone(zone).toEpochSecond()
    }

    /** 1970-01-05, the first Monday of the epoch; the anchor every weekly tick counts from. */
    private const val MONDAY_EPOCH_DAY = 4L
}

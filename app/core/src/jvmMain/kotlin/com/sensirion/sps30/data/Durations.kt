package com.sensirion.sps30.data

/**
 * Kotlin port of `chartview.parse_duration`, extended past it: the port added
 * week, month and year units when the window buttons stopped being capped at
 * the old 48 h retention horizon. The frozen Pi app keeps the original.
 */
object Durations {

    /** The units a token can carry, resolved lowercase - except month; see [parse]. */
    private val UNIT_SECONDS: Map<String, Double> = mapOf(
        "s" to 1.0, "sec" to 1.0, "secs" to 1.0, "second" to 1.0, "seconds" to 1.0,
        "m" to 60.0, "min" to 60.0, "mins" to 60.0, "minute" to 60.0, "minutes" to 60.0,
        "h" to 3600.0, "hr" to 3600.0, "hrs" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
        "d" to DAY, "day" to DAY, "days" to DAY,
        "w" to WEEK, "wk" to WEEK, "wks" to WEEK, "week" to WEEK, "weeks" to WEEK,
        "mo" to MONTH, "mon" to MONTH, "month" to MONTH, "months" to MONTH,
        "y" to YEAR, "yr" to YEAR, "yrs" to YEAR, "year" to YEAR, "years" to YEAR,
    )

    /** What a rejection offers instead: every short unit, shortest span first. */
    const val UNIT_HINT = "s, m, h, d, w, mo (or M), y"

    private const val DAY = 86_400.0
    /** Seven days exactly. */
    private const val WEEK = 7 * DAY
    /** Thirty days exactly - a chart window, not calendar arithmetic. */
    private const val MONTH = 30 * DAY
    /** 365 days exactly, for the same reason. */
    private const val YEAR = 365 * DAY

    private val TOKEN = Regex("""(\d+(?:\.\d+)?)([a-zA-Z]+)""")
    private val WHITESPACE = Regex("""\s+""")
    // Strict decimal/scientific number. Guards the bare-number shortcut against
    // Kotlin/Java accepting a trailing d/D/f/F suffix (so "1d" is days, not 1.0).
    private val PURE_NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    /**
     * Human duration -> seconds (> 0). Accepts a bare number as seconds
     * ("1800"), unit suffixes (s/m/h/d/w/mo/y and long spellings), combinations
     * ("1h30m"), and spaced word units ("12 hours").
     *
     * Case-insensitive, with one deliberate exception: a bare `M` is a month
     * and a bare `m` is minutes, because "1M" is how a month is usually written
     * and folding it to minutes would misread it by a factor of 43 200. Month
     * and year are fixed spans (30 and 365 days), not calendar arithmetic -
     * these size a chart window, where "roughly a month" is the whole ask.
     *
     * Throws [IllegalArgumentException] on empty/unparseable/non-positive
     * input, and the message says which token failed and what would work.
     */
    fun parse(text: String?): Double {
        if (text == null) throw IllegalArgumentException("duration is required")
        val stripped = text.trim()
        if (stripped.isEmpty()) throw IllegalArgumentException("empty duration")

        // A bare number (no unit) is seconds - but only if it is purely numeric,
        // so a unit token like "1d" is not swallowed by the d/f suffix.
        if (PURE_NUMBER.matches(stripped)) {
            val seconds = stripped.toDouble()
            if (seconds <= 0) throw IllegalArgumentException("'$text' is not a positive span")
            return seconds
        }

        // Case survives to here so "M" can be told from "m"; everything else
        // resolves lowercase.
        val compact = stripped.replace(WHITESPACE, "")
        var total = 0.0
        var pos = 0
        var matched = false
        for (m in TOKEN.findAll(compact)) {
            if (m.range.first != pos) {
                throw IllegalArgumentException("cannot read '$text' as a span of time")
            }
            val unit = m.groupValues[2]
            val unitSeconds = if (unit == "M") MONTH else UNIT_SECONDS[unit.lowercase()]
                ?: throw IllegalArgumentException(
                    "unknown unit '$unit' in '$text' - use $UNIT_HINT"
                )
            total += m.groupValues[1].toDouble() * unitSeconds
            pos = m.range.last + 1
            matched = true
        }
        if (!matched || pos != compact.length) {
            throw IllegalArgumentException("cannot read '$text' as a span of time")
        }
        if (total <= 0) throw IllegalArgumentException("'$text' is not a positive span")
        return total
    }
}

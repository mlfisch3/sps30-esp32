package com.sensirion.sps30.data

import kotlin.math.roundToLong

/**
 * A span of time in the compact form the chart's readouts use: `5d 2h`,
 * `2h 30m`, `45s`.
 *
 * ## The rules
 *
 * Zero units are dropped, and at most the two most significant non-zero units
 * are spoken: 5 days 2 hours 13 minutes reads `5d 2h`, exactly one hour reads
 * `1h`, and 1 day 0 hours 30 minutes reads `1d 30m`. Two units place any span
 * to within about 2% - the precision a glance at a chart window wants - and a
 * third would be clutter, which is what this replaced. This form supersedes
 * the clock notations (`01:12:00:00` and its variants) the window readout used
 * to offer a choice of: the captain read the colon-and-zeros style as clutter
 * however the fields were arranged, so the choice went with it.
 *
 * ## The unit letters
 *
 * Lowercase `d h m s`, because that is this codebase's one convention for
 * short units: the window preset chips (`1m`, `5m`, `30m`, `1h`), the editors'
 * placeholders (`10s, 1m, 1h, 2d`) and [Durations.parse]'s hint all write them
 * this way - and in [Durations.parse] an uppercase `M` already means month, so
 * capitals here would collide with the one place the case of a unit letter is
 * load-bearing. A happy consequence: everything this returns parses back
 * through [Durations.parse].
 *
 * Rounded to the nearest second rather than truncated: a window computed as
 * 3599.9999 s is a one-hour window that floating point has nudged, and
 * truncation would call it `59m 59s`. A negative or non-finite span reads as
 * `0s` - nothing produces one today, and a readout is the wrong place to
 * discover that something has.
 */
fun compactDuration(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.roundToLong().coerceAtLeast(0L) else 0L
    val fields = listOf(
        total / 86_400L to "d",
        (total / 3_600L) % 24 to "h",
        (total / 60L) % 60 to "m",
        total % 60 to "s",
    )
    val spoken = fields.filter { it.first != 0L }.take(2)
    if (spoken.isEmpty()) return "0s"
    return spoken.joinToString(" ") { (count, unit) -> "$count$unit" }
}

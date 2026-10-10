package com.sensirion.sps30.net

/**
 * Look for a host in bounded bursts: scan, pause, scan, until something answers
 * or the budget runs out.
 *
 * Written with no radio in it so the rule can be stated - and tested - on its own,
 * the same way [transportChangeFor] states the switching rule. [BleTransport] is
 * its only caller.
 *
 * It exists to sit between two failures. Scanning for ever is what the app used to
 * do when Bluetooth was chosen with nothing to find: the scan/backoff loop had no
 * end in it at all, so the radio kept waking for as long as the app was open.
 * Giving up after a scan or two is the opposite fault and the worse one, because a
 * controller that is slow to start advertising, or drifting in and out of range, is
 * a real controller - so the budget is a minute rather than a scan or two.
 *
 * The pause between scans is what makes a minute affordable. A BLE scan at
 * `SCAN_MODE_LOW_LATENCY` runs the radio at a duty cycle near 100%, and an
 * advertiser that is there at all is caught just as well by several windows spread
 * across the minute as by one unbroken one.
 *
 * @param budgetMs the whole search's bound, or null to keep looking until the
 *   caller cancels. Null is for a link that has already answered once: it is known
 *   to exist, so the case this bound exists for does not apply to it.
 * @param scanWindowMs the longest single scan.
 * @param firstPauseMs the gap after the first fruitless scan; it doubles up to
 *   [maxPauseMs].
 * @param now a clock, injected so a test need not spend a real minute.
 * @param pause how to wait between scans - `delay` in the app.
 * @param scan one scan window, returning what it found or null. It is handed the
 *   window it may use, clipped so a last scan cannot overrun the budget.
 */
internal suspend fun <T : Any> searchWithin(
    budgetMs: Long?,
    scanWindowMs: Long,
    firstPauseMs: Long,
    maxPauseMs: Long,
    now: () -> Long,
    pause: suspend (Long) -> Unit,
    scan: suspend (windowMs: Long) -> T?,
): T? {
    val deadline = budgetMs?.let { now() + it } ?: Long.MAX_VALUE
    var pauseMs = firstPauseMs
    while (true) {
        val left = deadline - now()
        // Too little left to be worth starting the radio for: a scan window of a
        // few milliseconds costs a start and a stop and can find nothing.
        if (left < MIN_USEFUL_SCAN_MS) return null
        scan(minOf(scanWindowMs, left))?.let { return it }
        val rest = deadline - now()
        if (rest <= 0) return null
        pause(minOf(pauseMs, rest))
        pauseMs = (pauseMs * 2).coerceAtMost(maxPauseMs)
    }
}

/** The shortest scan window worth starting the radio for. */
internal const val MIN_USEFUL_SCAN_MS = 500L

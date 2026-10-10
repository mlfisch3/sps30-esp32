package com.sensirion.sps30.data

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Reading, checking and writing an alert level, to two decimal places.
 *
 * ## Why two, and why one definition of them
 *
 * The field took digits only ([AlertRule.threshold] is a `Double`, and the input
 * threw the fractional part away before it ever got there), so "12.34" could not
 * be expressed at all. Indoor PM2.5 sits in the low tens and a level worth
 * setting is often a fraction of one, which is exactly where whole numbers stop
 * being enough.
 *
 * Everything that turns a level into text or text into a level goes through
 * here, because the two have to be inverses: the input field re-renders itself
 * from the stored value while it is being typed in, and a [format] that [parse]
 * could not read back would eat the user's keystrokes.
 *
 * [Locale.US] and not the default, for the same reason: a default-locale
 * `%.2f` writes `12,34` in half of Europe, and `"12,34".toDouble()` throws. The
 * text this produces is the same text that is parsed back.
 *
 * ## What survives the whole path, and what checks it
 *
 * Nothing downstream of the field truncates: `AlertRule.threshold` is a `Double`
 * serialized as one, `wire.CMD_SET_ALERTS` carries a JSON number, and the
 * firmware keeps `float threshold[STORE_FIELDS]` (`store_alert.h`). So two
 * decimals reach the controller and come back on `alert_segment.threshold`
 * unrounded, and no protocol change was needed. `AlertLevelTest` walks that path.
 */
object AlertLevel {

    /** Two, and it is a hard limit rather than a display preference. */
    const val DECIMALS = 2

    /**
     * The smallest level that means anything.
     *
     * Not zero. `AlertEngine` fires on `value >= threshold`, so a threshold of
     * zero fires on every reading forever, and the wire and the preservation
     * policy both drop a non-positive threshold on the floor - so a rule set to
     * 0 was a rule that alerted constantly and told the controller to preserve
     * nothing. Refusing it at the field is the only place that reads as an
     * answer rather than as a fault.
     */
    const val MIN = 0.01

    /**
     * The largest, from the instrument rather than from taste.
     *
     * The SPS30's specified mass-concentration range tops out at 1000 ug/m3, so
     * a level above it is one the sensor can never report crossing.
     */
    const val MAX = 1000.0

    /**
     * The keystrokes of [raw] that can still become a level, in order.
     *
     * Applied to the field's text as it is typed, so the field itself never
     * holds anything unparseable: no sign, no exponent, one decimal point, at
     * most [DECIMALS] digits after it. A comma is taken as a decimal point,
     * because that is the key a European keyboard offers.
     *
     * A leading point becomes `0.` rather than being dropped: somebody typing
     * `.5` means a half, and silently deleting the point would turn it into 5.
     */
    fun sanitize(raw: String): String {
        val out = StringBuilder()
        var seenPoint = false
        var decimals = 0
        for (c in raw) {
            when {
                c.isDigit() -> {
                    if (seenPoint) {
                        if (decimals >= DECIMALS) continue
                        decimals++
                    }
                    out.append(c)
                }

                (c == '.' || c == ',') && !seenPoint -> {
                    seenPoint = true
                    if (out.isEmpty()) out.append('0')
                    out.append('.')
                }
            }
        }
        return out.toString()
    }

    /**
     * [text] as a level, or null if it is not one.
     *
     * Null is the whole of the rejection: an empty field, a bare `.`, and
     * anything outside [MIN]..[MAX] all come back null and leave the stored rule
     * exactly as it was. Rounded to [DECIMALS], so what is stored is what was
     * shown and not a hundredth away from it.
     */
    fun parse(text: String): Double? {
        val value = sanitize(text).toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < MIN || value > MAX) return null
        return round(value)
    }

    /** [value] to [DECIMALS], with trailing zeros dropped: `20`, `12.5`, `12.34`. */
    fun format(value: Double): String {
        if (!value.isFinite()) return ""
        val text = String.format(Locale.US, "%.${DECIMALS}f", round(value))
        return text.trimEnd('0').trimEnd('.')
    }

    /** [value] snapped to [DECIMALS]. The one rounding, so nothing disagrees. */
    fun round(value: Double): Double = (value * 100.0).roundToLong() / 100.0

    /** Why the field is refusing what is in it, for the line under the field. */
    fun rejection(text: String): String? = when {
        parse(text) != null -> null
        sanitize(text).toDoubleOrNull() == null ->
            "Enter a level, for example 12.34. Nothing is saved until you do, so " +
                "this rule is still using the level it had."
        else ->
            "A level has to be between ${format(MIN)} and ${format(MAX)} ug/m3. " +
                "This rule is still using the level it had."
    }
}

/**
 * One threshold and what should happen when the air crosses it.
 *
 * Levels are independent: any combination may be enabled or none at all, so a
 * user who wants no interruptions can have exactly that. Each rule owns its own
 * reactions rather than there being a fixed escalation order, because "vibrate
 * at 35 but stay silent at 20" and "notify at 20 and also sound at 20" are both
 * reasonable and neither is more correct.
 *
 * Thresholds deliberately live only on the phone. They exist to notify *this*
 * user, so putting them on the shared controller would either impose one
 * person's idea of "high" on everyone or require per-client state the sensor has
 * no reason to carry.
 */
@Serializable
data class AlertRule(
    val id: String,
    val label: String,
    /** ug/m3 at or above which this rule fires. */
    val threshold: Double,
    /** Which PM series is watched. PM2.5 is the health-relevant default. */
    val field: String = "pm2_5",
    val enabled: Boolean = true,
    val notify: Boolean = true,
    val vibrate: Boolean = false,
    val sound: Boolean = false,
    /**
     * Let this alert reach a paired watch as well as the phone.
     *
     * A modifier on [notify] rather than a reaction of its own, because on a
     * paired Wear device they are the same object: Android bridges a phone
     * notification to the watch unless it is marked local-only, and that
     * bridging is what "alert on my watch" means. So this turns the marking off
     * rather than sending anything extra, and a rule with [notify] off has
     * nothing to bridge - which is why [hasReaction] does not count it.
     *
     * Default true because bridging is the platform default and was therefore
     * already the behaviour; the flag exists so someone who does not want their
     * wrist buzzed can say so, not to opt back in to what they already had.
     */
    val watch: Boolean = true,
    /** Shade the plot above this threshold. */
    val highlightPlot: Boolean = true,
) {
    /** True if any reaction at all would happen; a rule with none is inert. */
    val hasReaction: Boolean get() = notify || vibrate || sound || highlightPlot
}

/**
 * Tracks which rules are currently breached and reports only the *transitions*.
 *
 * Readings arrive up to once a second, so firing on every sample above the line
 * would be unusable. A rule fires once on the way up and cannot fire again until
 * the reading has fallen clear of the threshold by [HYSTERESIS_FRACTION] - air
 * hovering exactly on a threshold is the normal case, not an edge case, and
 * without hysteresis it would alert continuously.
 *
 * Pure and clock-injected so the whole escalation chain is testable without
 * waiting for bad air.
 */
class AlertEngine(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val breached = HashSet<String>()
    private val lastFiredMs = HashMap<String, Long>()

    /** Rules currently in the breached state, for painting the UI. */
    val active: Set<String> get() = breached.toSet()

    /**
     * Fold in one reading and return the rules that have *just* become breached.
     * Rules that are still breached from before return nothing.
     */
    fun evaluate(rules: List<AlertRule>, values: Map<String, Double>): List<AlertRule> {
        val fired = ArrayList<AlertRule>()
        val known = rules.mapTo(HashSet()) { it.id }
        // Drop state for rules the user has deleted, so an id reused later starts clean.
        breached.retainAll(known)
        lastFiredMs.keys.retainAll(known)

        for (rule in rules) {
            if (!rule.enabled || !rule.hasReaction) {
                breached.remove(rule.id)
                continue
            }
            val value = values[rule.field] ?: continue
            val wasBreached = rule.id in breached
            if (!wasBreached && value >= rule.threshold) {
                breached.add(rule.id)
                val last = lastFiredMs[rule.id]
                val now = nowMs()
                if (last == null || now - last >= COOLDOWN_MS) {
                    lastFiredMs[rule.id] = now
                    fired.add(rule)
                }
            } else if (wasBreached && value < rule.threshold * (1 - HYSTERESIS_FRACTION)) {
                breached.remove(rule.id)
            }
        }
        return fired
    }

    /** Force [rule] to fire next evaluation - used by the test-alert button. */
    fun rearm(rule: AlertRule) {
        breached.remove(rule.id)
        lastFiredMs.remove(rule.id)
    }

    fun reset() {
        breached.clear()
        lastFiredMs.clear()
    }

    companion object {
        /** A rule re-arms once the reading falls this far below its threshold. */
        const val HYSTERESIS_FRACTION = 0.10

        /** Never fire the same rule more often than this, however the air behaves. */
        const val COOLDOWN_MS = 5 * 60 * 1000L

        /**
         * The out-of-the-box rule. A fresh install should not be silent about air
         * genuinely worth knowing about, and 20 ug/m3 PM2.5 sits above typical
         * indoor background while staying below the 24-hour guideline levels that
         * health bodies treat as concerning - high enough not to cry wolf, low
         * enough to catch cooking, smoke and similar.
         */
        fun defaults(): List<AlertRule> = listOf(
            AlertRule(
                id = "elevated",
                label = "Elevated",
                threshold = 20.0,
                notify = true,
                highlightPlot = true,
            ),
            AlertRule(
                id = "high",
                label = "High",
                threshold = 55.0,
                notify = true,
                vibrate = true,
                enabled = false,
            ),
            AlertRule(
                id = "very-high",
                label = "Very high",
                threshold = 150.0,
                notify = true,
                vibrate = true,
                sound = true,
                enabled = false,
            ),
        )
    }
}

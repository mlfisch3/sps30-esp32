package com.sensirion.sps30.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlin.math.roundToInt

/**
 * What tapping the battery icon opens: the controller's own estimate of what
 * the board is drawing right now, and what each other acquisition mode would
 * cost instead - so the consequence of switching can be seen before switching.
 *
 * Two commitments shape every string in here:
 *
 * **The numbers are estimates and the panel says so once, plainly.** The
 * model's constants are datasheet figures and educated guesses - the wire
 * carries the counts and [powerHonestyLine] turns them into the one sentence.
 * Nothing in this panel may read as a measurement, because a user who believes
 * a meter produced these figures will trust them further than they go.
 *
 * **There is no battery, and the panel must not imply one.** The runtime slot
 * exists - the captain named it as the future shape - but today it says why
 * there is no number, in words that describe a board with nothing to measure.
 * "Unknown" and "about to die" must never render the same, so an absent
 * runtime is prose, never a zero or a dash beside an hours label.
 *
 * The app deliberately owns no power model: the controller computes every
 * figure here and this panel renders what it was told. See `power.py`.
 */
@Composable
fun PowerPanelDialog(state: DashboardUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Power") },
        text = {
            // Scrollable for the same reason the shutdown confirm is: on a short
            // landscape phone AlertDialog clips its text slot rather than grow.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val estimate = state.powerEstimate
                val refusal = state.powerEstimateRefusal
                when {
                    estimate != null -> PowerPanelBody(estimate)
                    refusal != null -> Text(
                        powerUnavailableText(refusal),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.connection !is ConnectionState.Connected -> Text(
                        "Not connected. The estimate comes from the controller, " +
                            "so there is nothing to show until one is.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        "Asking the controller what it is drawing…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PowerPanelBody(estimate: ServerMessage.PowerEstimate) {
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant

    // The state the board is in, and what the model says it costs.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            powerModeLabel(
                estimate.profile?.mode ?: Wire.MODE_CONTINUOUS,
                estimate.profile?.cycleSeconds,
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "≈ ${formatMilliwatts(estimate.totalMw)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    // The whole bill, not just the sensor: the point of the breakdown is that
    // measuring is only one of the things the controller spends power on.
    powerBreakdownLine(estimate.bySubsystemMw)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = subtle)
    }

    HorizontalDivider()

    Text(
        "Switching modes would change the estimate to:",
        style = MaterialTheme.typography.bodyMedium,
    )
    for (alternative in estimate.alternatives) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val current = isCurrentMode(estimate, alternative)
            Text(
                powerModeLabel(alternative.mode, alternative.cycleSeconds) +
                    if (current) " (now)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) subtle else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "≈ ${formatMilliwatts(alternative.totalMw)}" +
                    (alternative.hours?.let { ", about ${formatHours(it)} left" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) subtle else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    powerSleepNote(estimate)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = subtle)
    }

    HorizontalDivider()

    // The runtime slot: a number the day a capacity is known, prose until then.
    Text(powerRuntimeText(estimate), style = MaterialTheme.typography.bodyMedium)

    Text(
        powerHonestyLine(
            estimate.sourcesDatasheet, estimate.sourcesMeasured, estimate.sourcesEstimate,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = subtle,
    )
}

/**
 * Why `get_power_estimate` came back as an error instead of an estimate.
 *
 * The panel used to hold one boolean for every refusal and one sentence for the
 * boolean, and the sentence guessed: it told a captain connected to his own
 * controller that he was on a Raspberry Pi. The cases are genuinely different -
 * one is fixed by reflashing a board, one is permanent and correct, one the app
 * has no rule for - so they are told apart where the error arrives and each
 * gets its own words. See [classifyPowerRefusal] for how, and
 * [powerUnavailableText] for the words.
 */
sealed interface PowerRefusal {
    /**
     * A bare controller did not know the command. Today's firmware answers it,
     * so not knowing it means the board is running firmware from before the
     * power model existed - which reflashing fixes, and the message says so.
     */
    data object FirmwarePredatesEstimates : PowerRefusal

    /**
     * A host with an operating system - the Pi daemon - which never models
     * power on purpose: the model's constants describe the controller board's
     * rails and radios, and a Pi answering with them would be lying about
     * different hardware. See `wire.py`'s `CONTROLLER_ONLY_COMMANDS`.
     */
    data object HostDoesNotModelPower : PowerRefusal

    /**
     * Refused in words the app has no rule for. Carried verbatim and shown as
     * the honest unknown, rather than mapped onto whichever known case sounds
     * most plausible - which is exactly the mistake the old boolean made.
     */
    data class Unclassified(val message: String) : PowerRefusal
}

/**
 * Which [PowerRefusal] an error answering `get_power_estimate` is.
 *
 * "unknown cmd" means the command is not in the host's vocabulary. Both the Pi
 * daemon and a controller on old firmware answer exactly that, so the two are
 * told apart by what the hello said the host *is*: only a bare controller says
 * it cannot power itself off, and a bare controller running today's firmware
 * would have answered with an estimate - so a controller that does not know
 * the command is running firmware from before the power model.
 *
 * Any other error text, or an error arriving before any hello has said what
 * the host is, fits no rule here and is passed through as
 * [PowerRefusal.Unclassified] instead of being guessed at.
 */
internal fun classifyPowerRefusal(
    message: String,
    helloSeen: Boolean,
    canPowerOff: Boolean,
): PowerRefusal = when {
    !message.startsWith("unknown cmd") -> PowerRefusal.Unclassified(message)
    !helloSeen -> PowerRefusal.Unclassified(message)
    !canPowerOff -> PowerRefusal.FirmwarePredatesEstimates
    else -> PowerRefusal.HostDoesNotModelPower
}

/**
 * The sentence the panel shows instead of an estimate, one per refusal.
 *
 * The firmware case must be actionable - it names the fix rather than reading
 * as a dead end. The no-model case must not name a product: it describes the
 * two kinds of host in plain words, the way the rest of the app talks about
 * "the controller" and "the sensor".
 */
internal fun powerUnavailableText(refusal: PowerRefusal): String = when (refusal) {
    PowerRefusal.FirmwarePredatesEstimates ->
        "This controller is running firmware from before it learned to price " +
            "its own draw, so there is no estimate to show yet. Updating the " +
            "controller's firmware will add it - nothing here is broken."
    PowerRefusal.HostDoesNotModelPower ->
        "This host does not report power estimates. The model's constants " +
            "describe the controller board's own hardware, and a Raspberry Pi " +
            "host stays silent rather than borrow figures for hardware it " +
            "does not have."
    is PowerRefusal.Unclassified ->
        "The host declined to price its draw, for a reason this app does not " +
            "recognise. In the host's own words: ${refusal.message}"
}

/** "5 min", "12 h" - the shortest honest rendering of a duty-cycle period. */
internal fun powerCycleLabel(seconds: Double): String = when {
    seconds < 60 -> "${seconds.roundToInt()} s"
    seconds < 3600 -> "${(seconds / 60).roundToInt()} min"
    else -> "${(seconds / 3600).roundToInt()} h"
}

/** The row label for a mode: "Continuous", or "Low power, every 15 min". */
internal fun powerModeLabel(mode: String, cycleSeconds: Double?): String = when {
    mode != Wire.MODE_DUTY_CYCLE -> "Continuous"
    cycleSeconds == null -> "Low power"
    else -> "Low power, every ${powerCycleLabel(cycleSeconds)}"
}

/**
 * A power in milliwatts as a person reads one. Whole milliwatts - the inputs
 * are estimates and more digits would claim a precision nobody has - except
 * that a small-but-real figure must not round to the "0 mW" of something
 * switched off.
 */
internal fun formatMilliwatts(mw: Double): String = when {
    !mw.isFinite() -> "?"
    mw <= 0.0 -> "0 mW"
    mw < 1.0 -> "under 1 mW"
    else -> "${mw.roundToInt()} mW"
}

/** Hours as a person plans with them: "5.2 h" while it is short, "300 h" once
 *  a decimal place would be noise. */
internal fun formatHours(hours: Double): String = when {
    !hours.isFinite() -> "?"
    hours >= 100.0 -> "${hours.roundToInt()} h"
    else -> "${"%.1f".format(hours)} h"
}

/** "Sensor 275 mW · Controller 274 mW · Radios 446 mW · Storage under 1 mW". */
internal fun powerBreakdownLine(bySubsystemMw: Map<String, Double>): String? {
    if (bySubsystemMw.isEmpty()) return null
    val names = mapOf(
        "sensor" to "Sensor",
        "controller" to "Controller",
        "radio" to "Radios",
        "storage" to "Storage",
    )
    return bySubsystemMw.entries.joinToString(" · ") { (key, mw) ->
        "${names[key] ?: key} ${formatMilliwatts(mw)}"
    }
}

/** Whether an alternative row is the mode the board is already in. */
internal fun isCurrentMode(
    estimate: ServerMessage.PowerEstimate,
    alternative: ServerMessage.PowerAlternative,
): Boolean {
    val profile = estimate.profile ?: return false
    if (profile.mode != alternative.mode) return false
    return !alternative.dutyCycling || profile.cycleSeconds == alternative.cycleSeconds
}

/**
 * The one line that says whether the low-power figures assume the sensor
 * sleeps. Null when no alternative is a duty cycle, which no reply today
 * produces but which must not render a sentence about nothing.
 */
internal fun powerSleepNote(estimate: ServerMessage.PowerEstimate): String? {
    val duty = estimate.alternatives.filter { it.dutyCycling }
    if (duty.isEmpty()) return null
    return if (duty.all { it.sleeps }) {
        "Low-power figures assume the sensor sleeps between cycles."
    } else {
        "Low-power figures assume the sensor only idles between cycles - " +
            "this sensor's firmware cannot put it to sleep."
    }
}

/**
 * The runtime slot's text. The statuses come from the wire and the rule they
 * carry is kept here: only "ok" ever yields a number, and the no-capacity text
 * describes a board with nothing to measure rather than implying a battery
 * that is merely shy.
 */
internal fun powerRuntimeText(estimate: ServerMessage.PowerEstimate): String = when {
    estimate.runtimeKnown ->
        "About ${formatHours(estimate.runtimeHours ?: 0.0)} of runtime left - " +
            "deliberately underestimated, so it lasts longer than it promises."
    estimate.runtimeStatus == Wire.POWER_RUNTIME_UNUSABLE_CAPACITY ->
        "The reported battery capacity was not a usable number, so no runtime " +
            "is claimed."
    else ->
        "No runtime is shown: nothing on this board measures remaining battery " +
            "capacity. When a capacity is known, the estimated runtime - and the " +
            "remaining life under each mode above - will appear here."
}

/**
 * The honesty sentence, driven by the wire's own counts so it updates itself
 * the day somebody finally puts a meter on the board.
 */
internal fun powerHonestyLine(datasheet: Int, measured: Int, estimate: Int): String {
    val total = datasheet + measured + estimate
    if (total == 0) {
        return "These are modelled figures, not measurements."
    }
    val measuredClause = if (measured == 0) {
        "none has been measured on this board"
    } else {
        "$measured measured on this board"
    }
    return "These are modelled figures, not measurements: of the model's " +
        "$total constants, $estimate are estimates, $datasheet come from " +
        "datasheets, and $measuredClause."
}

/** What a screen reader (and a UI test) calls the tappable battery pill - the
 *  drawn icon has no text of its own when the host is on mains. */
internal const val POWER_PILL_DESC = "Power. Tap for the estimated draw and what other modes would cost."

/**
 * Sensor-host power at a glance: battery level when there is a battery, a plug
 * when on mains, and a warning when the host reports under-voltage.
 *
 * The under-voltage case earns its place because a weak supply is this project's
 * most expensive failure mode - it presents as the sensor never answering at all,
 * which looks like a wiring fault rather than a power one.
 *
 * Tapping it opens [PowerPanelDialog]: what the board is estimated to be
 * drawing, and what the other modes would cost. The pill itself stays a status
 * glyph - the panel is where the numbers live, because a figure that fits in
 * its row would have to drop the one word ("estimated") it must not.
 *
 * It used to live on the main screen. It sits in the menu's Sensor tab now -
 * today there is not even a battery, so a permanent slot on the display was
 * width spent on a glyph that never changes. See `SensorTab`.
 */
@Composable
internal fun PowerPill(state: DashboardUiState, onTap: () -> Unit) {
    val power = state.power ?: return
    val lowPower = state.profile?.dutyCycling == true
    val warn = MaterialTheme.colorScheme.error
    val normal = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        // Clickable before padding, the way [RelativeTimeFlag] is, so the
        // padding is part of the touch target rather than a dead ring round it.
        modifier = Modifier
            .clickable(onClick = onTap)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .semantics { contentDescription = POWER_PILL_DESC },
    ) {
        if (lowPower) {
            // Only meaningful once the sensor actually sleeps between readings.
            Text("🌙", style = MaterialTheme.typography.labelSmall, color = normal)
        }
        when {
            power.undervoltage -> {
                Text("⚠", style = MaterialTheme.typography.labelMedium, color = warn)
                Text("low volts", style = MaterialTheme.typography.labelSmall, color = warn)
            }
            power.onBattery -> {
                val pct = power.percent
                val tint = if ((pct ?: 100.0) < 15) warn else normal
                BatteryIcon(percent = pct, charging = power.charging, tint = tint)
                pct?.let {
                    Text(
                        "${it.roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                    )
                }
            }
            // Mains: a charging battery, which is what "plugged in" means here.
            else -> BatteryIcon(percent = null, charging = true, tint = normal)
        }
    }
}

/**
 * A battery outline with an optional fill level and charging bolt.
 *
 * Drawn rather than using a Material icon so it can be sized freely and so the
 * app does not have to pull in material-icons-extended for one glyph. The plug
 * emoji it replaces was both too small to read and ambiguous.
 */
@Composable
private fun BatteryIcon(
    percent: Double?,
    charging: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 30.dp, height = 16.dp)) {
        val capW = size.width * 0.08f
        val bodyW = size.width - capW
        val stroke = size.height * 0.11f
        val r = size.height * 0.16f

        // Body outline.
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyW - stroke, size.height - stroke),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = stroke),
        )
        // Positive terminal cap.
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyW, size.height * 0.3f),
            size = Size(capW, size.height * 0.4f),
            cornerRadius = CornerRadius(r / 2f, r / 2f),
        )
        // Charge level, when known.
        if (percent != null) {
            val inset = stroke * 1.6f
            val trackW = bodyW - inset * 2f
            val fill = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
            if (fill > 0f) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(inset, inset),
                    size = Size(trackW * fill, size.height - inset * 2f),
                    cornerRadius = CornerRadius(r / 2f, r / 2f),
                )
            }
        }
        if (charging) {
            // Lightning bolt, centred on the body. Drawn over any fill, in the
            // background colour where a fill exists so it stays legible.
            val cx = bodyW / 2f
            val cy = size.height / 2f
            val h = size.height * 0.62f
            val w = size.width * 0.16f
            val bolt = Path().apply {
                moveTo(cx + w * 0.35f, cy - h / 2f)
                lineTo(cx - w * 0.75f, cy + h * 0.12f)
                lineTo(cx - w * 0.05f, cy + h * 0.12f)
                lineTo(cx - w * 0.35f, cy + h / 2f)
                lineTo(cx + w * 0.85f, cy - h * 0.10f)
                lineTo(cx + w * 0.10f, cy - h * 0.10f)
                close()
            }
            drawPath(bolt, tint)
        }
    }
}

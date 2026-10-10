package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AlertLevel
import com.sensirion.sps30.data.AlertRule
import com.sensirion.sps30.wire.ServerMessage

/**
 * Threshold alerts, one editable row per level.
 *
 * Levels are independent rather than a fixed escalation ladder: any combination
 * of reactions may be enabled on any level, and none need be enabled at all, so
 * "notify me at 20 but only buzz me at 55" and "stay completely silent" are both
 * expressible. Each row carries its own test button, because waiting for
 * genuinely bad air to find out whether alerts work is not a usable test loop.
 */
@Composable
fun AlertsSection(
    rules: List<AlertRule>,
    activeIds: Set<String>,
    onUpdate: (AlertRule) -> Unit,
    onTest: (AlertRule) -> Unit,
    preserved: ServerMessage.Alerts? = null,
    receivePreserved: Boolean = true,
    onReceivePreserved: (Boolean) -> Unit = {},
) {
    Text(
        "Alerts fire when PM2.5 crosses a level, then re-arm once it drops clear. " +
            "They live on this phone only - other people connected to the same " +
            "sensor keep their own.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Two groups, and they were not distinguishable: the rules you set are one
    // thing and what the controller kept for you while nobody was watching is
    // another, and this was the only tab in the menu with no headings at all,
    // so the preserved-alerts switch read as a seventh level.
    Section("Alert levels")
    for (rule in rules) {
        AlertRow(
            rule = rule,
            active = rule.id in activeIds,
            onUpdate = onUpdate,
            onTest = onTest,
        )
    }
    Section("Preserved excursions")
    PreservedAlertsRow(preserved, receivePreserved, onReceivePreserved)
}

/**
 * What the controller kept for you, and the one switch that turns it off.
 *
 * The controller's storage is a ring: when it fills it wraps, and ordinary
 * history goes. Readings that crossed the lowest level above are copied out of
 * the ring's way first, so they survive it - and are sent here on connection
 * without being asked. This is the opt-out, and it only stops the *sending*:
 * the controller keeps preserving either way, so turning it off costs nothing
 * but the copy on this phone.
 */
@Composable
private fun PreservedAlertsRow(
    preserved: ServerMessage.Alerts?,
    receive: Boolean,
    onReceive: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Collect preserved alerts", fontWeight = FontWeight.Medium)
            Text(
                when {
                    // Not every controller preserves anything - the Pi daemon does
                    // not answer `get_alerts` at all - so before one has said so
                    // this must describe the switch rather than the sensor. It
                    // used to state what "the sensor" does off a null message,
                    // which on a Pi was simply untrue.
                    preserved == null ->
                        "Where the sensor supports it, readings above your lowest " +
                            "level are copied somewhere its storage cannot overwrite " +
                            "them and sent here. This sensor has not said whether it " +
                            "does yet."
                    !preserved.ready ->
                        "The sensor copies readings above your lowest level somewhere " +
                            "its storage cannot overwrite them, and sends them here."
                    preserved.segments == 0 ->
                        "Nothing preserved yet. The sensor has wrapped its storage " +
                            "${preserved.wraps} time(s)."
                    else ->
                        "${preserved.segments} preserved excursion(s); the sensor has " +
                            "wrapped its storage ${preserved.wraps} time(s)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The one thing the user must not have to discover by counting
            // points: the controller has slowed its recording down.
            if (preserved?.rateReduced == true) {
                Text(
                    "Preserved alerts are filling their space, so the sensor is " +
                        "recording every ${preserved.rateFloorSeconds.toInt()} s until " +
                        "there is room again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(checked = receive, onCheckedChange = onReceive)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertRow(
    rule: AlertRule,
    active: Boolean,
    onUpdate: (AlertRule) -> Unit,
    onTest: (AlertRule) -> Unit,
) {
    // Keyed on the rule, with an explicit re-sync below, rather than keyed on
    // the threshold. The field is re-rendered from the stored value while it is
    // being edited, and with a decimal in play the text and the value stop being
    // the same thing: "12." and "12.30" both parse to a value whose canonical
    // rendering is shorter than what was typed. Keying on the value works today
    // only because it happens never to fire on those keystrokes; saying what is
    // actually wanted - re-sync when the stored level stops being what the text
    // says, which is a saved configuration loading and nothing else - does not
    // depend on that coincidence holding.
    var threshold by remember(rule.id) { mutableStateOf(AlertLevel.format(rule.threshold)) }
    LaunchedEffect(rule.threshold) {
        if (AlertLevel.parse(threshold) != rule.threshold) {
            threshold = AlertLevel.format(rule.threshold)
        }
    }
    val rejection = AlertLevel.rejection(threshold)

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                rule.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (active) {
                // Say so plainly rather than relying on the colour alone.
                Text(
                    "breached now",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onUpdate(rule.copy(enabled = it)) },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = threshold,
                onValueChange = { text ->
                    // Sanitise first, so the field can never hold anything that
                    // is not on its way to being a level, then save only what
                    // parses. A rule keeps the level it had until a usable one
                    // replaces it - which is what the line below says out loud,
                    // because a field that quietly declines to save is its own
                    // small lie.
                    threshold = AlertLevel.sanitize(text)
                    AlertLevel.parse(threshold)?.let { onUpdate(rule.copy(threshold = it)) }
                },
                singleLine = true,
                enabled = rule.enabled,
                isError = rule.enabled && rejection != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(120.dp),
                label = { Text("ug/m3") },
            )
            TextButton(
                onClick = { onTest(rule) },
                enabled = rule.hasReaction,
            ) { Text("Test") }
        }

        // Only while the rule is on: a level is not being asked for on a rule
        // that is switched off, so complaining about it there would be noise.
        if (rule.enabled && rejection != null) {
            Text(
                rejection,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Five chips no longer fit one line on a phone, and a row that clips
        // its last chip hides a reaction rather than showing there is one more.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReactionChip("Notify", rule.notify, rule.enabled) {
                onUpdate(rule.copy(notify = it))
            }
            ReactionChip("Vibrate", rule.vibrate, rule.enabled) {
                onUpdate(rule.copy(vibrate = it))
            }
            ReactionChip("Sound", rule.sound, rule.enabled) {
                onUpdate(rule.copy(sound = it))
            }
            // Greyed out rather than hidden when Notify is off: the reaction is
            // still real, it just has nothing to travel on, and a chip that
            // vanishes reads as a feature that is not there at all.
            ReactionChip("On watch", rule.watch, rule.enabled && rule.notify) {
                onUpdate(rule.copy(watch = it))
            }
            ReactionChip("On plot", rule.highlightPlot, rule.enabled) {
                onUpdate(rule.copy(highlightPlot = it))
            }
        }
    }
}

@Composable
private fun ReactionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = { onToggle(!selected) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}

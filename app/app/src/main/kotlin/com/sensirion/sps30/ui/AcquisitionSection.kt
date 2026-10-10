package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlin.math.roundToInt

/** What the controller is recording, as one three-way choice. */
enum class MeasurementMode { CONTINUOUS, LOW_POWER, PAUSED }

/**
 * What the controller records: continuously, on Sensirion's low-power duty
 * cycle, or not at all.
 *
 * ## Why the third chip says "Paused" and not "Off"
 *
 * It said Off, and Off was the wrong word twice over. This section governs the
 * *controller* - whether it is asking the SPS30 for readings and storing them -
 * while the Sensor section below governs the *sensor*, its fan, its laser and
 * its power. "Off" reads as the second of those, so it invited the question of
 * whether it powered the sensor down, and the help text underneath made that
 * worse by talking about the fan and laser in the same breath. "Paused" says the
 * true thing: nothing is being recorded, nothing has been powered off, and the
 * next tap picks up where this left off. It is also the word the controller
 * itself uses for the state, and the word the status line already shows, so the
 * chip and the readout no longer disagree about what happened.
 *
 * ## The claim every note here has to get right
 *
 * A longer *continuous* interval does not save power. The fan and laser run
 * without stopping in that mode whatever interval is picked; all a longer
 * interval does is record less often. Only the duty cycle actually rests the
 * sensor. Each note below is written to make its own scope explicit rather than
 * leaving the reader to work out which of the two things a control touches.
 */
@Composable
fun AcquisitionSection(
    profile: ServerMessage.AcquisitionProfile?,
    power: ServerMessage.PowerStatus?,
    supportsSleep: Boolean,
    daemonState: String?,
    safety: SafetyState,
    onSetMode: (MeasurementMode) -> Unit,
    onSetInterval: (Double) -> Unit,
    onSetCycle: (Double) -> Unit,
) {
    // A host that is powering off is not measuring either. Without this the chips
    // fall through to their default and show "Continuous" for a sensor whose fan
    // the user just watched stop.
    val paused = daemonState == Wire.STATE_PAUSED || daemonState == Wire.STATE_SHUTDOWN
    val dutyCycling = profile?.dutyCycling == true
    // Null until the controller has said something about itself. `else ->
    // CONTINUOUS` used to answer here, which put a filled "Continuous ●" chip and
    // a paragraph about what the controller is recording on the screen of an app
    // that had never spoken to a controller. Every claim below is guarded on this.
    val mode: MeasurementMode? = when {
        paused -> MeasurementMode.PAUSED
        dutyCycling -> MeasurementMode.LOW_POWER
        profile == null && daemonState == null -> null
        else -> MeasurementMode.CONTINUOUS
    }
    // Left null rather than defaulted, for the same reason [mode] is: a selected
    // "1s" chip is a statement about how often the controller is reading the
    // sensor, and `?: 1.0` made it whenever no profile had arrived.
    val cycle = profile?.cycleSeconds
    val interval = profile?.intervalSeconds

    // Everything in this section changes what the physical sensor is doing - the
    // mode obviously, but the interval too, since it sets how often the host
    // polls the sensor. So the whole thing sits under one guard.
    GuardedSection(safety) {
        // The scope line. Without it the reader has to deduce from the chip
        // labels which of the two things this section touches, and the whole
        // reason the labels were confusing is that they do not say.
        Note(
            "These set what the controller records and how often. They do not " +
                "power the sensor down - only Low power ever sleeps it."
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ModeChip("Continuous", mode == MeasurementMode.CONTINUOUS, true) {
                onSetMode(MeasurementMode.CONTINUOUS)
            }
            ModeChip("Low power", mode == MeasurementMode.LOW_POWER, supportsSleep) {
                onSetMode(MeasurementMode.LOW_POWER)
            }
            ModeChip("Paused", mode == MeasurementMode.PAUSED, true) {
                onSetMode(MeasurementMode.PAUSED)
            }
        }

        if (!supportsSleep) {
            Note("Low power needs sensor firmware 2.0 or newer; this one is older.")
        }

        when (mode) {
            // Nothing has been heard, so nothing is described. Picking a mode is
            // still allowed - the command is what makes the answer arrive.
            null -> Note(
                "The controller has not said what it is recording, so none of the " +
                    "three above is shown as the current one. Picking one sets it."
            )

            MeasurementMode.PAUSED -> Note(
                "The controller has stopped reading the sensor, so nothing is " +
                    "being recorded. The sensor is idle with its fan and laser " +
                    "off, but it is still powered and the controller is still " +
                    "connected, so Continuous or Low power starts it again " +
                    "straight away."
            )

            MeasurementMode.LOW_POWER -> {
                Text("Measure once every", style = MaterialTheme.typography.bodyMedium)
                ChipRow(AppConfig.DUTY_CYCLE_PERIODS, cycle, onSetCycle)
                val warmup = profile?.warmupSeconds ?: 30.0
                val average = profile?.averageSeconds ?: 30.0
                Note(
                    "This is the one mode that acts on the sensor as well as the " +
                        "controller. Each cycle wakes the sensor, discards " +
                        "${warmup.roundToInt()}s while the fan spins up, averages " +
                        "${average.roundToInt()}s of readings into one point, then " +
                        "puts it back to sleep. This is Sensirion's recommended " +
                        "low-power sequence."
                )
                if (cycle != null && cycle >= 43_200.0) {
                    Note(
                        "At this interval the sensor is asleep over 99.9% of the time, " +
                            "so the battery drains mostly by itself rather than by " +
                            "measuring. Intended for storage or long absences."
                    )
                }
            }

            MeasurementMode.CONTINUOUS -> {
                Text("Read the sensor every", style = MaterialTheme.typography.bodyMedium)
                ChipRow(AppConfig.CONTINUOUS_INTERVALS, interval, onSetInterval)
                Note(
                    if (interval != null && interval > AppConfig.SUGGEST_DUTY_CYCLE_ABOVE) {
                        "At this interval, low power mode records just as often and uses " +
                            "far less energy - the sensor sleeps between readings instead " +
                            "of running continuously."
                    } else {
                        "How often the controller reads the sensor and stores the " +
                            "result, so it sets how fast the stored data grows. It " +
                            "changes nothing about the sensor: in this mode its fan " +
                            "and laser run without stopping whichever interval you " +
                            "pick. Low power is the mode that rests them."
                    }
                )
            }
        }

        power?.averageMa?.let { ma ->
            val draw = if (mode == MeasurementMode.PAUSED) "0 mA (nothing being recorded)"
                else "${"%.1f".format(ma)} mA average"
            Note("Estimated sensor draw: $draw.")
        }
    }
}

/**
 * A mode button whose selected state is unmistakable.
 *
 * A plain FilterChip only shows selection as a subtle tint plus a check, which
 * was not readable at a glance - the mode had to be inferred from the
 * explanatory text below. Selected chips get the full primary colour and a bold
 * label so the active mode is obvious without reading anything.
 */
@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        },
        leadingIcon = if (selected) {
            { Text("●", style = MaterialTheme.typography.labelMedium) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ChipRow(options: List<Double>, current: Double?, onPick: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seconds in options) {
            FilterChip(
                selected = current == seconds,
                onClick = { onPick(seconds) },
                label = { Text(labelFor(seconds)) },
            )
        }
    }
}

@Composable
private fun Note(text: String) = ControlNote(text)

/** "30s", "15m", "1h", "24h" - the shortest honest rendering of a period. */
private fun labelFor(seconds: Double): String = when {
    seconds < 60 -> "${seconds.roundToInt()}s"
    seconds < 3600 -> "${(seconds / 60).roundToInt()}m"
    else -> "${(seconds / 3600).roundToInt()}h"
}

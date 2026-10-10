package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.DeviceDrain

/**
 * The one line the row leads with, by what actually happened.
 *
 * A function rather than a `when` buried in the composable, for the same reason
 * [transferHeadline] is one: the order of its arms is the part that can be got
 * wrong and the part worth a test. The rule it keeps is that a heading must never
 * claim more than the body - and here that matters more than anywhere else in the
 * app, because "the sensor is clear" over a body saying eleven blocks were kept
 * back would be telling someone their data is safe when it is not.
 */
internal fun clearHeadline(status: DeviceDrain.Status): String = when (status.phase) {
    DeviceDrain.Phase.IDLE -> ""
    DeviceDrain.Phase.STARTING -> "Asking the sensor what it holds…"
    DeviceDrain.Phase.COPYING, DeviceDrain.Phase.CONFIRMING ->
        "Copying the sensor to this device and clearing it"
    DeviceDrain.Phase.DONE ->
        "Copied ${"%,d".format(status.readingsCopied)} readings off; the sensor is clear"
    DeviceDrain.Phase.CANCELLED -> "Stopped after ${status.blocksFreed} block(s)"
    DeviceDrain.Phase.FAILED -> "Stopped after ${status.blocksFreed} block(s)"
}

/**
 * The line under it: what is on this device, and what that means for the sensor.
 *
 * Every arm says where the readings are, because that is the only question this
 * feature raises. A run that stopped part way is not a failure to explain away -
 * the blocks it did copy are here and are gone from the sensor, and the ones it
 * did not are still on the sensor. Both halves are said.
 */
internal fun clearDetail(status: DeviceDrain.Status): String = when (status.phase) {
    DeviceDrain.Phase.IDLE -> ""
    DeviceDrain.Phase.STARTING -> "Nothing has been removed."
    DeviceDrain.Phase.COPYING, DeviceDrain.Phase.CONFIRMING -> buildString {
        append("${"%,d".format(status.rowsWritten)} rows written here")
        if (status.segmentsCopied > 0) {
            append(" · ${status.segmentsCopied} preserved alert segment(s)")
        }
        append(" · ${status.blocksLeft} block(s) left on the sensor")
    }
    DeviceDrain.Phase.DONE -> buildString {
        append("${status.blocksFreed} block(s) freed")
        if (status.segmentsCopied > 0) {
            append(" · ${status.segmentsCopied} preserved alert segment(s) included")
        }
        append(". The readings that arrived during the copy are still on the sensor.")
    }
    DeviceDrain.Phase.CANCELLED, DeviceDrain.Phase.FAILED ->
        "${"%,d".format(status.rowsWritten)} rows are on this device and are no longer on " +
            "the sensor. Everything the sensor still holds is untouched, and running this " +
            "again picks up where it left off."
}

/**
 * Back up everything the sensor holds and clear it.
 *
 * A **separate** control from "Copy sessions to this device" and from "Erase what
 * is backed up", and separate on purpose rather than by accident of layout: those
 * two do exactly what they have always done, and this does not reach into either.
 * What it adds is the interleaving - copy a block, prove it, free that block - and
 * the reason that is worth its own control is that it is the only one here that
 * empties the sensor, and the only one whose progress is measured in what has
 * already been removed.
 *
 * ## Why it is behind the same guard as erasing
 *
 * It is the most consequential control in the app, so it gets the guard the other
 * consequential ones have rather than a second kind of safety with its own rules,
 * and it asks once more before it starts. The real protection is not either of
 * those: it is that the sensor refuses to free a block this device cannot prove it
 * holds, and that protection is on the sensor, where a mistake in this file
 * cannot reach it.
 */
@Composable
internal fun BackupAndClearRow(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    enabled: Boolean,
    safety: SafetyState,
) {
    var armed by remember { mutableStateOf(false) }
    val status = state.clearing

    Section("Backing up everything and clearing the sensor") {
        SafetyToggle(engaged = safety.engaged, onToggle = safety::toggle)
    }
    GuardedSection(safety) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { armed = true },
                enabled = enabled && !status.running && !armed,
                modifier = Modifier.weight(1f),
            ) { Text("Back up everything and clear the sensor") }
        }
    }
    Text(
        "Copies everything the sensor holds onto this device - including the preserved " +
            "alert segments - a block at a time, and frees each block only once the sensor " +
            "has confirmed this device has it. The sensor keeps recording throughout, and " +
            "what it records during the copy stays on it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Said here rather than left to be discovered. Nothing this app sends is
    // encrypted, and a control that empties the sensor is the wrong place for a
    // user to assume otherwise.
    Text(
        "This copy is not encrypted. Nothing this app sends or receives is, yet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (armed) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                "Copy everything off and clear the sensor?",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Nothing is removed from the sensor until it has confirmed this device " +
                    "holds it. You can stop at any point, and stopping is safe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { armed = false }) { Text("Cancel") }
                OutlinedButton(onClick = {
                    armed = false
                    viewModel.backUpAndClearSensor()
                }) { Text("Start") }
            }
        }
    }

    when (status.phase) {
        DeviceDrain.Phase.IDLE -> Unit

        DeviceDrain.Phase.STARTING,
        DeviceDrain.Phase.COPYING,
        DeviceDrain.Phase.CONFIRMING,
        -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val fraction = status.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                clearHeadline(status),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                clearDetail(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Always reachable while it runs, and it is the whole of the
            // cancel story: the sensor is told to give the claim back and
            // frees nothing on the way.
            OutlinedButton(onClick = viewModel::stopBackUpAndClear) { Text("Stop") }
        }

        DeviceDrain.Phase.DONE -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                clearHeadline(status),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                clearDetail(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The location stays until dismissed rather than flashing past: it
            // is the one thing the user needs afterwards, and this copy is the
            // only one of those readings there is.
            if (status.location.isNotEmpty()) {
                Text(
                    "Saved to ${status.location}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = viewModel::dismissBackUpAndClear) { Text("Done") }
        }

        DeviceDrain.Phase.CANCELLED, DeviceDrain.Phase.FAILED ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    clearHeadline(status),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    status.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.phase == DeviceDrain.Phase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    clearDetail(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.location.isNotEmpty()) {
                    Text(
                        "What was copied is in ${status.location}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = viewModel::dismissBackUpAndClear) { Text("Dismiss") }
            }
    }
}

package com.sensirion.sps30.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.net.TransportAvailability

/**
 * Wi-Fi / Bluetooth / Disconnect, where the greyed-out button *is* the state
 * indicator: whichever mode you are in cannot be tapped, because tapping it would
 * do nothing.
 *
 * Grey alone is ambiguous though - a radio that is switched off is also unusable -
 * so the current mode additionally carries an accent ring and a "Connected" caption,
 * while a genuinely unavailable one is plain grey with the reason underneath. The
 * distinguishing cue is shape plus text rather than colour, so it survives both
 * themes and colour-blindness.
 */
@Composable
fun ConnectionSelector(
    current: ConnectionMode,
    availability: Map<ConnectionMode, TransportAvailability>,
    connected: Boolean,
    onSelect: (ConnectionMode) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The link still carrying the stream while [current] is being brought up.
     *
     * Non-null exactly during a switch, and it is what stops the newly chosen
     * button captioning itself "Connected" off a connection that belongs to the
     * transport it is replacing.
     */
    servingDuringSwitch: ConnectionMode? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ConnectionMode.entries) {
            val available = availability[mode] ?: TransportAvailability.USABLE
            val isCurrent = mode == current
            ConnectionButton(
                label = connectionModeLabel(mode),
                isCurrent = isCurrent,
                // The link still working stays tappable during a switch: that tap is
                // how a switch is called off, and it is the obvious thing to reach
                // for when the new transport is taking too long.
                enabled = !isCurrent && available.usable,
                caption = when {
                    isCurrent -> currentCaption(mode, connected && servingDuringSwitch == null)
                    // The one still doing the work, said plainly, so a switch that
                    // is taking a while does not look like a dead connection.
                    mode == servingDuringSwitch -> "Still connected"
                    !available.usable -> available.reason
                    else -> null
                },
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Why a connection the user asked for did not happen.
 *
 * A dialog rather than a line somewhere, because the thing being explained is a
 * minute of waiting that ended in nothing: the user tapped Bluetooth, watched, and
 * by now may well have put the tablet down. The banner cannot carry it - after a
 * failed switch the banner is describing the Wi-Fi link that was never dropped,
 * and it is healthy - and the selector is behind a sheet that may be shut.
 *
 * Plain words and one button. There is nothing to decide here; the app has already
 * done the right thing with the connection.
 */
@Composable
fun ConnectionNoticeDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No sensor found") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun ConnectionButton(
    label: String,
    isCurrent: Boolean,
    enabled: Boolean,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            // The ring is what separates "you are here" from "unavailable"; both
            // render with the disabled content colour.
            border = if (isCurrent) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
        ) {
            Text(label, maxLines = 1)
        }
        // Reserve the caption row so the buttons never shift as state changes.
        Text(
            text = caption.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp),
        )
    }
}

/**
 * What each mode's button says.
 *
 * Shared rather than private because anything that tells the user to tap one of
 * them has to name it exactly as the button does - "Tap Bluetooth again" is an
 * instruction, and an instruction that names a control which is not on screen
 * under that name is worse than none.
 */
internal fun connectionModeLabel(mode: ConnectionMode) = when (mode) {
    ConnectionMode.WIFI -> "Wi-Fi"
    ConnectionMode.BLUETOOTH -> "Bluetooth"
    ConnectionMode.NONE -> "Disconnect"
}

/** The current mode's caption doubles as link status, so a mode we are *trying*
 *  to use does not falsely claim to be connected. */
private fun currentCaption(mode: ConnectionMode, connected: Boolean) = when {
    mode == ConnectionMode.NONE -> "Not connected"
    connected -> "Connected"
    else -> "Connecting…"
}

/** Which button, if any, should say it is still doing the work. */
internal fun servingDuringSwitch(
    activeMode: ConnectionMode,
    switchingTo: ConnectionMode?,
): ConnectionMode? = activeMode.takeIf { switchingTo != null && it != switchingTo }

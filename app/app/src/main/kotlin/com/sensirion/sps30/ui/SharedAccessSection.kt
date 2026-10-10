package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.wire.ServerMessage

/**
 * Who else is watching this sensor, and who may currently change it.
 *
 * The sensor serves everyone on the network at once, but only one person at a
 * time may pause it, clean it or change its mode - otherwise one tap could stop
 * everybody else's recording without them ever knowing why. Watching is always
 * free; control is a lease that expires on its own, so a phone that goes out of
 * range cannot leave the sensor stuck.
 *
 * ## Everything here is a claim about somewhere else
 *
 * Both lines describe the *controller's* roster, which this phone knows only
 * because the controller told it, and which nothing clears when the link drops.
 * So both are written through [SharedAccess], which takes the connection state
 * as an argument and refuses to speak in the present tense about a controller
 * this phone is not talking to. Before this they read the roster fields alone:
 * an empty roster said "Connected." while the app was searching for a sensor it
 * had never found, and a roster left over from a dropped link said "You and Bob
 * are connected" about a link that had gone.
 */
@Composable
fun SharedAccessSection(
    state: DashboardUiState,
    onClaim: () -> Unit,
    onRelease: () -> Unit,
) {
    val connected = state.connection is ConnectionState.Connected
    val others = state.watchers.filter { it.id != state.clientId }
    val holderIsMe = state.controlHolder != null && state.controlHolder == state.clientId

    Text(
        SharedAccess.watchersLine(connected, state.watchers, others),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            SharedAccess.controlLine(
                connected = connected,
                holderIsMe = holderIsMe,
                holder = state.controlHolder,
                holderName = state.controlHolderName,
                told = state.controlTold,
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (!connected) MaterialTheme.colorScheme.onSurfaceVariant
                else if (state.hasControl) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        // Neither button does anything without a link to send the command down,
        // so neither is offered without one.
        if (connected && holderIsMe) {
            OutlinedButton(onClick = onRelease) { Text("Release") }
        } else if (connected && state.controlHolder != null) {
            OutlinedButton(onClick = onClaim) { Text("Take control") }
        }
    }

    Text(
        SharedAccess.leaseNote(connected, state.controlHolder),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The three sentences of this section, as arithmetic over the state.
 *
 * Pure so the one thing that can be wrong about them - which condition each is
 * shown under - can be pinned by an ordinary JVM test with no Compose, in the
 * same spirit as [com.sensirion.sps30.wear.WatchLink]. See `SharedAccessTest`.
 */
object SharedAccess {

    /**
     * Who is connected to the controller.
     *
     * The disconnected case comes first and is not negotiable: with no link,
     * every roster field this phone holds is either empty because it was never
     * filled or left over from a connection that has gone, and neither is a fact
     * about now.
     */
    fun watchersLine(
        connected: Boolean,
        watchers: List<ServerMessage.Watcher>,
        others: List<ServerMessage.Watcher>,
    ): String = when {
        !connected ->
            "Not connected to a sensor, so who else is watching it is not known."
        // Connected, but the controller has not sent its roster yet. "Connected."
        // was the old answer and it was a claim about this phone standing in for
        // an answer about everyone else.
        watchers.isEmpty() -> "Waiting for the sensor to say who else is connected."
        others.isEmpty() -> "You are the only one connected."
        others.size == 1 -> "You and ${others.single().name} are connected."
        else -> "You and ${others.size} others are connected: " +
            others.joinToString(", ") { it.name }
    }

    /**
     * Who holds the control lock.
     *
     * [told] is the distinction that was missing: `holder == null` means "nobody
     * holds it" only once the controller has said so, and until then it is
     * simply the field's default. Stating "Nobody has control" off a default is
     * the same shape of error as telling somebody their watch has no app on it
     * because the question was never asked.
     */
    fun controlLine(
        connected: Boolean,
        holderIsMe: Boolean,
        holder: Int?,
        holderName: String?,
        told: Boolean,
    ): String = when {
        !connected -> "Not connected"
        !told -> "Who has control is not known yet"
        holderIsMe -> "You have control"
        holder == null -> "Nobody has control"
        else -> "${holderName ?: "Someone else"} has control"
    }

    /** How the lease behaves, which is only worth saying while there is one. */
    fun leaseNote(connected: Boolean, holder: Int?): String = when {
        !connected ->
            "Control of the sensor is claimed over the connection, so there is " +
                "nothing to claim until this phone is connected to one."
        holder == null -> "Control is taken automatically the first time you change something."
        else -> "Control returns automatically if it goes unused for two minutes."
    }
}

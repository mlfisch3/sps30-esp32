package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.wear.WatchHealth
import com.sensirion.sps30.wear.WatchLink
import kotlinx.coroutines.delay

/**
 * Everything about the phone-to-watch link: what it is doing, and what to do
 * about it.
 *
 * ## Why this tab exists
 *
 * The relay was one-way and said nothing. The captain's watch had no companion
 * app on it, so every reading this phone published was accepted by the Data
 * Layer, synced to a watch where nothing was listening, and dropped - and the
 * only visible symptom was a complication that stayed blank. Nothing anywhere
 * said why, because nothing anywhere was asking.
 *
 * The tab is built around that one failure. An absence cannot be read off a
 * screen: "no app on the watch" has to be **written down in a sentence**, next
 * to the thing to do about it, or it is indistinguishable from every other
 * reason a number might not have shown up yet.
 *
 * ## What is on it, and in what order
 *
 * The four questions in the order somebody actually asks them, each answered by
 * the platform rather than by a mechanism of ours (see
 * [com.sensirion.sps30.wear.WearLink]):
 *
 *  1. is there a watch,
 *  2. does it have the receiving app,
 *  3. when did a reading last go out, and is that recent,
 *  4. can the phone reach it right now.
 *
 * Then the send button, which is the only control here that produces evidence.
 * Everything above it is this phone describing its own end of a one-way link;
 * the button asks the far end to answer, so the indicator can be **checked**
 * rather than believed.
 *
 * The update interval sits at the bottom, under the connectivity it governs.
 * It used to be the only watch control in the app, filed under Customize, which
 * put the one thing that changes how often the link is used on a different tab
 * from any way of telling whether the link works at all.
 */
@Composable
fun WatchSection(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    val facts = state.watch
    val config = state.config

    // Ask on arrival. An indicator that was right when the menu was last closed
    // is not an indicator, and the probe is two Data Layer lookups.
    LaunchedEffect(Unit) { viewModel.refreshWatchLink() }

    // A local second hand, purely so "sent 4 minutes ago" ages while it is being
    // looked at. The model's own tick only runs during a sensor phase countdown,
    // and a frozen relative time is exactly the kind of quiet lie this tab is
    // here to remove.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    val report = WatchLink.assess(facts, state.readingsArriving)

    Section("The watch", divider = false)
    Text(
        "${mark(report.health)}  ${report.headline}",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = healthColor(report.health),
    )
    if (report.detail.isNotBlank()) ControlNote(report.detail)

    Section("Readings")
    ControlNote(
        WatchLink.lastSentLine(facts, nowMs, config.watchRelaySeconds, state.readingsArriving),
    )
    ControlNote(WatchLink.deliveryLine(facts, nowMs))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = viewModel::refreshWatchLink,
            enabled = !facts.probing && !facts.sending,
            modifier = Modifier.weight(1f),
        ) { Text(if (facts.probing) "Checking..." else "Check again") }
        Button(
            onClick = viewModel::sendTestReadingToWatch,
            // Greyed out when there is provably nowhere to send: a button that
            // can only ever report the thing already written above it is a
            // button that teaches people to distrust the ones that work.
            enabled = report.canSend && !facts.sending,
            modifier = Modifier.weight(1f),
        ) { Text(if (facts.sending) "Sending..." else "Send a reading now") }
    }
    facts.lastSendResult?.let { ControlNote(it) }
    ControlNote(
        "Sending puts the current reading on the watch out of turn and waits for " +
            "the watch to acknowledge it. It is the only thing here that proves " +
            "the link rather than describing it, and it does not disturb the " +
            "sensor or the recording.",
    )

    Section("Update interval")
    WatchRelayRow(config) { viewModel.setWatchRelaySeconds(it) }
}

/**
 * A symbol before the headline, so the state survives being read in sunlight, in
 * greyscale, or by somebody who does not see the colour difference. The colour
 * is the emphasis; the mark is the information.
 */
private fun mark(health: WatchHealth): String = when (health) {
    WatchHealth.UNKNOWN -> "..."
    WatchHealth.GOOD -> "OK"
    WatchHealth.WARN -> "!"
    WatchHealth.BAD -> "X"
}

@Composable
private fun healthColor(health: WatchHealth): Color = when (health) {
    WatchHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    WatchHealth.GOOD -> MaterialTheme.colorScheme.primary
    WatchHealth.WARN -> MaterialTheme.colorScheme.tertiary
    WatchHealth.BAD -> MaterialTheme.colorScheme.error
}

/**
 * How often the phone pushes a reading to the watch. Every push wakes the watch's
 * radio and forces a complication redraw, so this is the main lever on watch
 * battery life - hence the explicit note rather than a bare row of chips.
 */
@Composable
private fun WatchRelayRow(config: AppConfig, onPick: (Int) -> Unit) {
    Text("Send a reading to the watch at most every", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seconds in AppConfig.WATCH_RELAY_CHOICES) {
            FilterChip(
                selected = config.watchRelaySeconds == seconds,
                onClick = { onPick(seconds) },
                label = { Text(if (seconds < 60) "${seconds}s" else "${seconds / 60}m") },
            )
        }
    }
    ControlNote(
        "This is the fastest the watch will ever be updated - longer means less " +
            "watch battery used. Within that limit a reading is only sent when it " +
            "has actually changed, so in steady air the watch is updated less often " +
            "than this, not more.",
    )
}

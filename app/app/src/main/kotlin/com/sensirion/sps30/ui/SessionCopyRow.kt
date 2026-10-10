package com.sensirion.sps30.ui

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.config.Exports
import com.sensirion.sps30.data.RecordStanding
import com.sensirion.sps30.data.RecordWork
import com.sensirion.sps30.data.SessionBackup
import com.sensirion.sps30.data.SessionErase
import com.sensirion.sps30.data.SessionTransfer
import com.sensirion.sps30.net.ConnectionState
import kotlinx.coroutines.delay

/**
 * The one line a finished transfer leads with.
 *
 * A function rather than a `when` buried in the composable, because the order of
 * its arms is the part that can be got wrong and the part worth a test. A
 * refusal and a file the sensor already held **both** report nothing added, and
 * only one of them means the readings are now on the sensor - so the refusal has
 * to be asked about first. Getting that order wrong tells a user their data is
 * safe on a controller that never looked at it.
 *
 * The opposite fault is just as bad and is the one this got wrong next: the
 * first arm asked [SessionTransfer.Done.refused], which is the controller's
 * state and not whether the data arrived. A send of 72,814 readings that the
 * sensor placed every one of - 71,548 already held, 1,266 refused for having no
 * clock, so the state came back `error` with a reason - was headed "The sensor
 * did not take it" over a body saying exactly what it had taken. So the question
 * asked first is now whether anything landed at all, and a heading never claims
 * less than the body does.
 */
internal fun transferHeadline(transfer: SessionTransfer.Done): String = when {
    transfer.ranOutOfRoom -> "The sensor ran out of room"
    transfer.nothingLanded -> "The sensor did not take it"
    transfer.rejected > 0 ->
        "The sensor has all but ${"%,d".format(transfer.rejected)} of the readings"
    transfer.added == 0 -> "The sensor already had all of it"
    else -> "Added ${"%,d".format(transfer.added)} readings to the sensor"
}

/**
 * The line under it: the counts, or the controller's own words if it took none.
 *
 * A refused transfer has no counts worth printing - nought of everything - and
 * the one thing it does have is the reason and what to do about it, which the
 * controller wrote and this end has no business paraphrasing. A transfer the
 * controller had something to say about but still placed readings from keeps its
 * words too, because they are where the reason lives.
 */
internal fun transferDetail(transfer: SessionTransfer.Done): String =
    if (transfer.refused) {
        transfer.message
    } else {
        "${"%,d".format(transfer.duplicate)} were already there and were kept · " +
            "${transfer.sessions} session(s)" +
            if (transfer.rejected > 0) {
                " · ${"%,d".format(transfer.rejected)} rows could not be read"
            } else {
                ""
            }
    }

/**
 * The heading of the progress block while a copy runs.
 *
 * Says which of the two things it is, because [SessionBackup.Receiving] carries
 * both: a controller that streams its sessions in turn, counted in sessions, and
 * one that sends a single sized archive, counted in bytes. This line used to
 * print `"session ${received + 1} of $total"` for both, which on the second is a
 * byte count with the word "session" in front of it - "session 1 of 4,182,993".
 */
internal fun copyProgressHeadline(backup: SessionBackup.Receiving): String =
    if (backup.receivedAreSessions) {
        val total = maxOf(backup.sessionsTotal, 1)
        "Copying from the sensor · session ${(backup.sessionsDone + 1).coerceAtMost(total)} of $total"
    } else {
        "Receiving an archive from the sensor · " +
            "${"%,d".format(backup.received)} of ${"%,d".format(backup.total)} bytes"
    }

/**
 * The counts under it, and the one line the whole repair turns on.
 *
 * The captain watched this figure climb past 66,900 and 75,000 directly beneath
 * a panel correctly reporting that nothing was missing, and there was no way to
 * tell from the screen which of the two was wrong. Neither was. The copy was
 * re-reading readings this device already held in order to write them into an
 * archive file, so the number racing was rows-into-a-file and the number sitting
 * still was readings-this-device-lacks - two honest meters of two different
 * things with nothing saying they were different things.
 *
 * So it now reports both: what has been read, and how much of it this device did
 * not already have. When the second is nought the sentence says so outright,
 * because "none of them new" is the fact that makes a climbing counter under a
 * settled panel make sense.
 */
internal fun copyProgressDetail(backup: SessionBackup.Receiving): String {
    if (!backup.receivedAreSessions) {
        return "${backup.sessions} session(s) · ${"%,d".format(backup.rows)} readings in it"
    }
    val newHere = if (backup.newHere > 0) {
        "${"%,d".format(backup.newHere)} of them new to this device"
    } else {
        "none of them new to this device"
    }
    return "${"%,d".format(backup.rows)} readings read so far · $newHere"
}

/**
 * Why a copy can be reading tens of thousands of readings and adding none.
 *
 * Null once anything new has landed, and null before anything at all has been
 * read - at nought of nought there is nothing yet to explain.
 */
internal fun copyProgressExplanation(backup: SessionBackup.Receiving): String? = when {
    !backup.receivedAreSessions -> null
    backup.rows <= 0L -> null
    backup.newHere > 0L -> null
    else ->
        "This device already had all of them. The copy is reading them back to write an " +
            "archive file into Downloads/SPS30, which is why the count climbs while the " +
            "figures above do not."
}

/**
 * The file picker for sending sessions back, opened on this app's own archives.
 *
 * `OpenDocument` on its own opens wherever the picker was last left, which in
 * practice is the device's general Downloads folder - hundreds of unrelated
 * files, none of them ours, as the first screen of the feature. The archives this
 * app writes are all in one folder ([Exports.EXPORT_FOLDER_DOCUMENT_ID]) and that
 * is where the file being looked for almost always is, so that is where it opens.
 *
 * `EXTRA_INITIAL_URI` is a hint: a picker that cannot honour it, or a folder that
 * does not exist yet because nothing has been exported, falls back to its default
 * rather than failing. So this can only improve the starting point, never block
 * reaching a file somewhere else.
 */
internal class OpenArchive : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(
                Exports.EXTERNAL_STORAGE_AUTHORITY,
                Exports.EXPORT_FOLDER_DOCUMENT_ID,
            ),
        )
}

/**
 * Moving recorded sessions between the sensor and this device, both ways.
 *
 * Worded throughout to say which way the data travels and what it costs, because
 * the two directions are not symmetrical. Copying *off* the sensor is a read and
 * cannot go wrong. Sending data *back* changes what the sensor holds - and the
 * one thing a user needs to be sure of is that it can only ever add. That
 * promise is kept by the controller (see `store_merge.h`), and it is stated here
 * because a promise nobody is told about is not reassuring.
 *
 * Erasing is deliberately the least prominent thing on the row and refuses
 * unless a backup can be proved. That is not a safety rail bolted on; it is the
 * only way the controller will do it.
 */
@Composable
fun SessionCopyRow(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    eraseSafety: SafetyState,
) {
    var explain by remember { mutableStateOf(false) }
    val backup = state.backup
    val transfer = state.transfer
    val busy = transfersAreInFlight(state)

    val picker = rememberLauncherForActivityResult(
        // OpenDocument rather than GetContent: it gives a URI that can be opened
        // twice, which this needs - the file is read through once to check it and
        // once to send it, and nothing is sent until the first pass has passed.
        remember { OpenArchive() },
    ) { uri ->
        if (uri != null) viewModel.importArchive(uri, viewModel.displayNameOf(uri))
    }

    // The caption here used to read "Everything the sensor has recorded", which
    // named the scope of the button and said nothing about whether any of it had
    // arrived. The comparison says both, and it sits directly on top of the
    // button so the gap and the thing that closes it are read together.
    //
    // Built here rather than held on the UI state because every part of it is
    // already there, and a derived field would be one more thing that can fall
    // out of step with the states it is derived from - which is the species of
    // fault this panel was rebuilt over. See [RecordStanding].
    DataInventoryRow(
        RecordStanding(
            inventory = state.inventory,
            gaps = state.gaps,
            work = RecordWork.of(state.backup, state.storedImport, state.transfer, state.gaps),
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = viewModel::copySessionsToThisDevice,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text("Copy sessions to this device") }
        Text(
            if (explain) "▲" else "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { explain = !explain }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                // Zip first, then anything: the export is a zip, but a user may
                // well pick a single session CSV they kept, and "*/*" is what
                // makes providers that type files loosely still show them.
                picker.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "*/*"))
            },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text("Send sessions back to the sensor") }
    }

    AnimatedVisibility(visible = explain) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "The sensor records continuously and keeps every session it has room " +
                    "for, including the ones from before this app was installed or " +
                    "while it was closed, asleep or out of range. Copying puts all of " +
                    "them into the plot and writes a zip of session CSVs into " +
                    "Downloads/SPS30 that you can open or share. The originals stay " +
                    "on the sensor - copying is only ever a read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Sending data back only ever adds. If the sensor already has a " +
                    "reading, it keeps its own and counts yours as a duplicate; it " +
                    "never overwrites one and never deletes anything to make room. " +
                    "If it runs out of space it stops and says so rather than " +
                    "clearing older data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.storage?.let { storage ->
                // Past tense off a link that has gone. Nothing clears `storage`
                // on disconnect, and the sensor keeps recording while this phone
                // is not listening, so a percentage from an hour ago stated in
                // the present tense is a number that is no longer true.
                val connected = state.connection is ConnectionState.Connected
                Text(
                    (if (connected) "The sensor's storage is " else "The sensor's storage was ") +
                        "${"%.0f".format(storage.fraction * 100)}% full " +
                        "(${"%,d".format(storage.readings)} readings)" +
                        (if (connected) ". " else " when this phone last heard from it. ") +
                        "Everything is copied here automatically once it passes " +
                        "${"%.0f".format(storage.warnAt * 100)}%.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when (backup) {
        is SessionBackup.Requested -> Text(
            "Asking the sensor what it has…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is SessionBackup.Receiving -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LinearProgressIndicator(
                progress = { backup.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                copyProgressHeadline(backup),
                style = MaterialTheme.typography.bodySmall,
                // The one block on the tab whose numbers move while it is being
                // read. It says which operation they belong to, and it says so
                // in the weight the summary above uses for its own claims, so
                // the two are read as one account rather than as two panels
                // disagreeing. See [copyProgressDetail].
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                copyProgressDetail(backup),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            copyProgressExplanation(backup)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is SessionBackup.Saved -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Saved to ${backup.location}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // The location stays on screen until dismissed rather than flashing
            // past in a toast: it is the one piece of information the user needs
            // afterwards, and a copy this large is not something to repeat
            // because the message vanished before it was read.
            Text(
                "${backup.sessions} sessions · ${"%,d".format(backup.rows)} readings · " +
                    "the sensor still has all of it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (backup.missing > 0) {
                // Never let a partial archive read as a complete backup. The
                // erase gate will refuse the sessions that are not in it, which
                // is correct but silent, and the user should hear it here.
                Text(
                    "${backup.missing} session(s) the sensor holds are NOT in this " +
                        "file. It is not a complete backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = viewModel::dismissBackupResult) { Text("Done") }
        }

        is SessionBackup.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Same species of line as a failed transfer, and it retires for the
            // same reason: it explains something that did not happen.
            RetireAfterAWhile(backup, viewModel::dismissBackupResult)
            Text(
                "Copy failed: ${backup.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::dismissBackupResult) { Text("Dismiss") }
        }

        SessionBackup.Idle -> Unit
    }

    TransferStatus(transfer, onDismiss = viewModel::dismissTransferResult)
}

/**
 * Whether a copy or a send is in flight, and so whether either may be started.
 *
 * Lifted out of [SessionCopyRow] because [EraseRow] is no longer inside it and
 * asks the same question: the erase is refused while a transfer holds the
 * controller's cursor, and two answers to "is it busy" would eventually be two
 * different answers.
 */
internal fun transfersAreInFlight(state: DashboardUiState): Boolean {
    val backup = state.backup
    val transfer = state.transfer
    // [SessionTransfer.Awaiting] counts: the rows have gone but the controller
    // still holds the import claim until it answers, and a second file offered
    // in that window is refused as `busy` by the board rather than sent.
    return backup is SessionBackup.Receiving || backup is SessionBackup.Requested ||
        transfer is SessionTransfer.Sending || transfer is SessionTransfer.Checking ||
        transfer is SessionTransfer.Awaiting
}

/**
 * How long an explanation of something that did not happen stays on screen
 * before retiring itself.
 *
 * Long enough to read twice over and to still be there when the user looks back
 * from the file picker; short enough that it cannot outlive what it is about.
 * On the tablet a failed transfer's message sat through a dozen unrelated
 * actions - loading sessions, changing the window, opening other tabs - because
 * the only way out of it was the Dismiss button, and a line explaining something
 * that stopped happening minutes ago reads as the current state of the app.
 */
private const val FAILURE_NOTICE_MS = 45_000L

/**
 * Retires a notice that has outlived what it was explaining.
 *
 * Only for the lines that carry no result: a failure and a refusal say what did
 * *not* happen, and once read there is nothing in them to come back to. The
 * outcomes that carry numbers - what was saved, where it was saved, how much the
 * controller took - are deliberately left to be dismissed by hand, because they
 * are the reason the operation was run and losing them to a timer would be the
 * old "flashed past in a toast" fault in a slower costume.
 *
 * Keyed on the notice itself, so a second failure gets its own full reading time
 * rather than inheriting the remains of the first one's.
 */
@Composable
private fun RetireAfterAWhile(notice: Any, onRetire: () -> Unit) {
    val retire by rememberUpdatedState(onRetire)
    LaunchedEffect(notice) {
        delay(FAILURE_NOTICE_MS)
        retire()
    }
}

/** What sending data back is doing, or did. */
@Composable
internal fun TransferStatus(transfer: SessionTransfer, onDismiss: () -> Unit) {
    when (transfer) {
        SessionTransfer.Idle -> Unit

        is SessionTransfer.Checking -> Text(
            "Checking ${transfer.file} before sending anything…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is SessionTransfer.Sending -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { transfer.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Sending ${transfer.file} · ${"%,d".format(transfer.rowsSent)} of " +
                    "${"%,d".format(transfer.rowsTotal)} readings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Not the tail of the progress bar. Everything asked for has been sent
        // and the sensor has not yet said what it did with it, which is a
        // different thing to be waiting on and reads as one - a bar sitting full
        // says "still working" when the work is finished and the answer is late.
        is SessionTransfer.Awaiting -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Sent ${"%,d".format(transfer.rowsSent)} readings from ${transfer.file}. " +
                    "Waiting for the sensor to say what it did with them…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SessionTransfer.Done -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                transferHeadline(transfer),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                transferDetail(transfer),
                style = MaterialTheme.typography.bodySmall,
                // Red for the reason nothing arrived, not for the reason a few
                // rows of an otherwise placed transfer did not. Same rule as the
                // heading: the colour must not contradict the counts either.
                color = if (transfer.nothingLanded) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (transfer.ranOutOfRoom) {
                Text(
                    "Nothing was removed to make space. Copy the sessions off first, " +
                        "then erase what is backed up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onDismiss) { Text("Done") }
        }

        is SessionTransfer.Rejected -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RetireAfterAWhile(transfer, onDismiss)
            Text(
                "Not sent: ${transfer.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            // Said explicitly. "Rejected" on its own leaves a user wondering what
            // state the sensor was left in, and the answer - none, it was never
            // told - is the whole reason the file is read through first.
            Text(
                "Nothing was sent, so nothing on the sensor changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }

        is SessionTransfer.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RetireAfterAWhile(transfer, onDismiss)
            // "Transfer" is the wrong word for this and for everything on this
            // screen: nothing here moves. Sending a file back copies readings to
            // the sensor and leaves this device's copy exactly where it was, and
            // copying sessions here leaves the sensor's. Saying "sending" and
            // "copying" is not a nicety - a word that implies moving is what
            // makes a user hesitate over an operation that cannot lose anything.
            Text(
                "Sending stopped: ${transfer.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Nothing was taken off this device, and whatever had already arrived " +
                    "on the sensor was kept. Sending the same file again is safe - the " +
                    "sensor skips what it already has.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * Erasing the sensor, and the override behind a gesture that has to be meant.
 *
 * The plain button asks the controller to erase what this device can prove it
 * already holds; without a copy it refuses, which is the designed answer rather
 * than a fault. The unconditional erase is behind five deliberate taps on the
 * explanatory text - obscure enough not to be arrived at, discoverable enough to
 * be documented, and impossible to reach by a slip.
 *
 * ## The safety on top of that
 *
 * The backup proof above is the real protection and is enforced by the
 * controller, not here. What it does not protect against is the slip: this is the
 * one control in the sheet that destroys data, and until now it was also the
 * easiest to hit, sitting bare while Measurement and Sensor - which only change
 * behaviour - each had a guard. So it gets the same guard, deliberately the same
 * one rather than a second kind of safety with its own rules.
 */
@Composable
internal fun EraseRow(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    enabled: Boolean,
    safety: SafetyState,
) {
    var taps by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    val erase = state.erase

    // The one group heading in the menu that had no rule above it, so it bound
    // upwards to the copy controls instead of downwards to the thing it guards.
    Section("Erasing the sensor") { SafetyToggle(engaged = safety.engaged, onToggle = safety::toggle) }
    GuardedSection(safety) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = viewModel::eraseController,
                enabled = enabled && erase !is SessionErase.Asked,
                modifier = Modifier.weight(1f),
            ) { Text("Erase what is backed up") }
        }
    }
    Text(
        "The sensor erases only the sessions this device can prove it already has.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = {
                taps++
                if (taps >= 5) {
                    armed = true
                    taps = 0
                }
            })
        },
    )

    if (armed) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Erase everything without checking for a backup?",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "This cannot be undone and there is no copy to restore from.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { armed = false }) { Text("Cancel") }
                OutlinedButton(onClick = {
                    armed = false
                    viewModel.eraseControllerWithoutBackup()
                }) { Text("Erase anyway") }
            }
        }
    }

    when (erase) {
        SessionErase.Idle, SessionErase.Proving -> Unit

        SessionErase.Asked -> Text(
            "Asking the sensor to erase what it can confirm…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is SessionErase.Done -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Erased ${"%,d".format(erase.readingsErased)} readings from " +
                    "${erase.sessionsErased} session(s)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (erase.sessionsKept > 0) {
                Text(
                    "${erase.sessionsKept} session(s) were kept: this device could not " +
                        "prove it has them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = viewModel::dismissEraseResult) { Text("Done") }
        }

        is SessionErase.Refused -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Nothing was erased: ${erase.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::dismissEraseResult) { Text("Dismiss") }
        }

        is SessionErase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Erase failed: ${erase.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::dismissEraseResult) { Text("Dismiss") }
        }
    }
}

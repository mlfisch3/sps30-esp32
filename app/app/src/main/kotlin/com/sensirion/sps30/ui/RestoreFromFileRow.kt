package com.sensirion.sps30.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.sensirion.sps30.data.RecordRestore
import com.sensirion.sps30.data.SessionRestore
import com.sensirion.sps30.data.TimeSeriesHistory
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The one line a finished restore leads with.
 *
 * A function rather than a `when` inside the composable, for the same reason
 * [transferHeadline] is one: the order of the arms is the part that can be got
 * wrong and the part worth a test. Two very different outcomes both report zero
 * added - a file the record already held in full, and a file whose readings
 * nothing could date - and only the first of those means the data is here.
 */
internal fun restoreHeadline(report: SessionRestore.Report): String = when {
    report.added > 0 -> "Added ${"%,d".format(report.added)} readings to the record"
    report.alreadyHeld > 0 -> "This device already had all of it"
    else -> "Nothing in that file could be added"
}

/**
 * The line under it: what became of every reading the file held.
 *
 * Exhaustive on purpose. `added` on its own is the number that flatters, and a
 * user who has just repaired their record needs to see that the counts add up
 * before believing the hole is closed.
 */
internal fun restoreDetail(report: SessionRestore.Report): String = buildString {
    append("${"%,d".format(report.readings)} readings in the file · ")
    append("${"%,d".format(report.added)} added · ")
    append("${"%,d".format(report.alreadyHeld)} already here and kept")
    if (report.undated > 0) {
        append(" · ${"%,d".format(report.undated)} could not be dated, so were not stored")
    }
    if (report.unreadableRows > 0) {
        append(" · ${"%,d".format(report.unreadableRows)} rows could not be read")
    }
}

/**
 * What the record now covers because of this file, or null when it covers
 * nothing new that can be dated.
 *
 * The point of the whole feature in one line: the span the file put back is what
 * says the gap on the plot is closed, rather than leaving the user to scroll to
 * the date and hope.
 */
internal fun restoreSpan(report: SessionRestore.Report): String? {
    val start = report.fileStart ?: return null
    val end = report.fileEnd ?: return null
    return "Now covered: " + spanText(start, end)
}

/** Warnings that must not be buried in the counts. Empty in the ordinary case. */
internal fun restoreCaveats(report: SessionRestore.Report): List<String> = buildList {
    if (report.clocklessSkipped > 0) {
        add(
            "${report.clocklessSkipped} session(s) were recorded while the sensor had no " +
                "clock and nothing here can date them, so they were left out entirely.",
        )
    }
    if (report.unplaced > 0) {
        add(
            "${"%,d".format(report.unplaced)} of the readings have no date yet - they were " +
                "recorded against the sensor's own clock. They are in the record and in " +
                "exports; they reach the plot once the run they belong to is placed.",
        )
    }
}

/**
 * Putting readings back into the record from a file this device already holds.
 *
 * The route home. Every other path here moves data outwards - the plot CSV, the
 * session archive, the send back to the sensor - so a copy kept on a laptop
 * preserved readings that could never return, and a gap the sensor's ring had
 * wrapped past was permanent even when the data plainly still existed.
 *
 * Worded around the one promise that makes it safe to press: the merge can only
 * add. It is keyed on the session and the reading's own number, so nothing
 * already held is overwritten, reordered or dropped, and the same file offered
 * twice changes nothing the second time. That promise is [SessionRestore]'s, and
 * it is stated here because a promise nobody is told about is not reassuring.
 */
@Composable
fun RestoreFromFileRow(state: DashboardUiState, viewModel: DashboardViewModel) {
    var explain by remember { mutableStateOf(false) }
    val restore = state.restore
    val busy = restore is RecordRestore.Reading

    val picker = rememberLauncherForActivityResult(
        // The same contract the send-back picker uses, so this opens on
        // Downloads/SPS30 rather than wherever the system picker was last left -
        // which is the device's general Downloads folder. See [OpenArchive].
        remember { OpenArchive() },
    ) { uri ->
        if (uri != null) viewModel.restoreFromFile(uri, viewModel.displayNameOf(uri))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                picker.launch(
                    arrayOf(
                        "text/csv", "text/comma-separated-values", "application/zip", "*/*",
                    ),
                )
            },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text("Restore readings from a file") }
        Text(
            if (explain) "▲" else "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { explain = !explain }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }

    AnimatedVisibility(visible = explain) {
        Text(
            "Reads a session file or an archive kept on this device and merges it " +
                "into the record, at full resolution. For readings the sensor no " +
                "longer holds - its storage wraps, so an old gap eventually stops " +
                "being fillable from it - a copy saved elsewhere is the only way " +
                "back, and this is that way. It can only ever add: nothing already " +
                "here is overwritten, reordered or removed, and restoring the same " +
                "file twice does nothing the second time. A file that is damaged, " +
                "or is not the session its name says it is, is refused and changes " +
                "nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (restore) {
        is RecordRestore.Reading -> Text(
            buildString {
                append("Reading ${restore.file}…")
                if (restore.session.isNotEmpty()) append(" · ${restore.session}")
                if (restore.readings > 0) append(" · ${"%,d".format(restore.readings)} readings")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is RecordRestore.Done -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                restoreHeadline(restore.report),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                restoreDetail(restore.report),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            restoreSpan(restore.report)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            restoreCaveats(restore.report).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = viewModel::dismissRestoreResult) { Text("Dismiss") }
        }

        is RecordRestore.Refused -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // "Nothing was added" leads, because the reason is long and the fact
            // a user needs first is that their record is exactly as it was.
            Text(
                "Nothing was added",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                restore.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::dismissRestoreResult) { Text("Dismiss") }
        }

        is RecordRestore.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "That file could not be read: ${restore.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::dismissRestoreResult) { Text("Dismiss") }
        }

        RecordRestore.Idle -> Unit
    }
}

private val RESTORE_DAY = DateTimeFormatter.ofPattern("MMM d")
private val RESTORE_TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * A covered range worth checking against a gap, so to the minute.
 *
 * Within one day the day is said once, which is the case this exists for: a hole
 * in a morning is read as "Aug 4, 07:44 to 11:18" and compared with the plot at
 * a glance.
 */
internal fun spanText(start: Double, end: Double): String {
    fun at(e: Double) = Instant.ofEpochSecond(e.toLong()).atZone(TimeSeriesHistory.EASTERN)
    val from = at(start)
    val to = at(end)
    val fromDay = from.format(RESTORE_DAY)
    val toDay = to.format(RESTORE_DAY)
    return if (fromDay == toDay) {
        "$fromDay, ${from.format(RESTORE_TIME)} to ${to.format(RESTORE_TIME)}"
    } else {
        "$fromDay ${from.format(RESTORE_TIME)} to $toDay ${to.format(RESTORE_TIME)}"
    }
}

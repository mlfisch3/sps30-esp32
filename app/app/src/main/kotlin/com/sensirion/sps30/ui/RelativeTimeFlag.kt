package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/*
 * What the screen says when the controller had no clock - which is as little
 * as it can honestly get away with.
 *
 * It began as a coloured banner across the top of the main view, which was
 * read as a fault report; it became a two-word flag on the chip cluster's
 * row; and it now lives inside the plot's own top strip, beside the unit
 * mark, because it qualifies exactly the axis it sits above - see
 * `ChartRender.timeFlag` and `topStripPlan` for the drawing, and
 * `PlotChromeOverlays` for the tap target. The words are unchanged: a label,
 * never a sentence ("derived times", or "3 unplaced"), because a sentence is
 * a notice and the notice was the fault. The measured drift it plays down is
 * 0.029 s/hour - 0.7 s/day - which no user of this instrument can act on.
 *
 * The genuinely different case keeps its own affordance. A run recorded with
 * no clock that never met a time source cannot be placed at all - its
 * readings and their spacing are exact, only its position is unknown - and
 * there the user has a fact no measurement here does ("I plugged it in when
 * I left the house"). That is an action, so the flag counts the waiting runs
 * and its tap opens [UnplacedRunsDialog].
 */

/**
 * Placing runs the app cannot place itself.
 *
 * A dialog rather than a strip on the main screen: it is rare, it is a piece of
 * data entry, and it is over once done. Nothing is pre-filled - a guess the user
 * accepted is indistinguishable afterwards from a time that was measured.
 */
@Composable
fun UnplacedRunsDialog(
    state: RelativeTimeState,
    onPlaceRun: (boot: Int, epochSeconds: Double?) -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place these readings in time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Readings from ${state.unanchoredRuns.size} run(s) are stored and " +
                        "shown, and the time between them is exact. Nothing recorded " +
                        "when they started, so the app will not guess. If you know, " +
                        "say so and the whole run moves to fit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (boot in state.unanchoredRuns) {
                    UnplacedRun(boot = boot, onPlace = onPlaceRun)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onOpenHelp) { Text("Explain") } },
    )
}

/** One run the user can place. The field is empty until they type. */
@Composable
private fun UnplacedRun(
    boot: Int,
    onPlace: (boot: Int, epochSeconds: Double?) -> Unit,
) {
    var text by remember(boot) { mutableStateOf("") }
    var bad by remember(boot) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Run $boot - start time unknown",
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; bad = false },
                singleLine = true,
                label = { Text("When it started (2026-08-03 09:15)") },
                isError = bad,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val epoch = parseLocal(text)
                if (epoch == null) bad = true else onPlace(boot, epoch)
            }) { Text("Place") }
            TextButton(onClick = { text = ""; bad = false; onPlace(boot, null) }) {
                Text("Clear")
            }
        }
        if (bad) {
            Text(
                text = "Could not read that. Use 2026-08-03 09:15, local time.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val LOCAL_FORMATS = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
)

/**
 * Epoch seconds from what the user typed, or null.
 *
 * Refusing is deliberate. Something unparseable must not fall back to a default
 * that happens to be plausible - the block staying visibly unplaced is the
 * correct outcome of a typo.
 */
private fun parseLocal(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    for (format in LOCAL_FORMATS) {
        try {
            return LocalDateTime.parse(trimmed, format)
                .atZone(java.time.ZoneId.systemDefault())
                .toEpochSecond()
                .toDouble()
        } catch (e: DateTimeParseException) {
            continue
        }
    }
    return null
}

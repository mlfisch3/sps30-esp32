package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.compactDuration
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.net.ConnectionState
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * How many holes are named on screen before the list stops.
 *
 * The rest are still detected, still drawn on the plot and still filled; this is
 * only how long a list a person will read. The worst are kept, which is the ones
 * that cannot be recovered.
 */
private const val GAPS_LISTED = 6

private val FMT_GAP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm:ss")
private val FMT_GAP_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun zoned(epoch: Double) =
    Instant.ofEpochSecond(epoch.toLong()).atZone(TimeSeriesHistory.EASTERN)

private fun at(epoch: Double): String = zoned(epoch).format(FMT_GAP_TIME)

/**
 * When one hole ran, in the sensor's own zone.
 *
 * Both ends spelled out rather than one end and a duration, because the thing
 * being answered is "what was I not recording", and that is a question about
 * clock times - the captain's was "07:42:18 to 11:18:52" and reading it back as
 * "3h 36m at some point on Tuesday" would be a worse answer to the same words.
 * The length follows in the detail line, where it belongs.
 *
 * The date is repeated on the right only when the hole crosses midnight, where it
 * is the whole point. Printing it twice on the ordinary case makes the two ends
 * harder to compare at a glance, which is the one thing the line exists for.
 */
internal fun gapWhen(gap: RecordGap): String {
    val from = zoned(gap.startEpoch)
    val to = zoned(gap.endEpoch)
    val end = if (from.toLocalDate() == to.toLocalDate()) {
        to.format(FMT_GAP_CLOCK)
    } else {
        to.format(FMT_GAP_TIME)
    }
    return "${from.format(FMT_GAP_TIME)} → $end"
}

/**
 * What can be done about one hole, in the plainest words there are.
 *
 * The three are worded to be unmistakable for one another when skimmed, because
 * that is the entire requirement: a hole the sensor can still supply and a hole
 * the ring has eaten look identical on a plot, and only one of them is worth
 * doing anything about. "There is nothing you can do about this" is not a
 * softened phrasing of "not fetched yet"; it is the opposite claim, and the
 * third arm exists so that it is never printed on a guess. See [GapStanding].
 */
internal fun gapStandingLabel(gap: RecordGap): String = when (val standing = gap.standing) {
    is GapStanding.Fillable ->
        if (gap.partlyBeyondReach) {
            "the sensor still has this from ${at(standing.fromEpoch)}; before that it is gone"
        } else {
            "the sensor still has this"
        }

    GapStanding.Lost -> "gone - the sensor has recorded over it, and nothing can bring it back"

    is GapStanding.Unknown -> "not known yet - ${standing.because}"
}

/** The length and the rough count, under the clock times. */
internal fun gapDetail(gap: RecordGap): String =
    "${compactDuration(gap.seconds)} · about ${"%,d".format(gap.expectedReadings)} readings"

/**
 * The one line the whole list is summed up in.
 *
 * It has to be able to say "nothing has looked yet", which an empty list on its
 * own reads as "there are no gaps" - the same confident-zero fault
 * [sensorSideSummary] one row up exists to avoid. [RecordGapReport.sweptAtMs] is
 * what tells the two apart.
 *
 * This and [everRecordedSummary] answer the same question at two altitudes: that
 * one is the verdict in the record's own panel, this one heads the list of where
 * the holes actually are. They are kept from disagreeing by both being pure
 * functions of the same [RecordGapReport] and by `TheTwoGapVerdictsAgree` in
 * `DataInventoryTextTest`, because two answers to "is any of my data gone" on
 * one screen would be worse than either.
 */
internal fun gapSummary(report: RecordGapReport): String = when {
    report.sweeping && report.gaps.isEmpty() -> "Comparing this device's record against the sensor…"
    report.sweptAtMs == 0L && report.gaps.isEmpty() ->
        "Not compared against the sensor yet."
    report.gaps.isEmpty() -> "No gaps. Every reading between the ends of the record is here."
    report.lost == 0 && report.undecided == 0 ->
        "${report.gaps.size} gap(s), and the sensor still has all of it."
    report.lost > 0 ->
        "${report.gaps.size} gap(s) · ${report.lost} beyond recovery " +
            "(about ${"%,d".format(report.readingsLost)} readings the sensor has recorded over)."
    else -> "${report.gaps.size} gap(s), and whether the sensor still has them is not known."
}

/**
 * Holes in this device's record, and the two things that can be done about them.
 *
 * ## Why the tab needs this as well as the inventory above it
 *
 * [DataInventoryRow] compares *counts*, session by session, and answers "is my
 * data here yet". It cannot answer "where is it not", and the difference is not
 * academic: a session whose front never arrived is short by a count the inventory
 * would show, and a session the sensor no longer holds is short by nothing at
 * all, because the inventory can only compare against what the sensor still has.
 * The hole the captain lost on 2026-08-04 was invisible to every figure on this
 * tab right up until the sensor's ring wrapped past it, at which point it became
 * invisible for good.
 *
 * ## The button
 *
 * A sweep runs on its own every half hour, which is the answer to "can it be
 * changed so this doesn't happen at all" - a gap that closes itself beats one
 * that needs remembering. The button is here because somebody looking at a hole
 * wants to act on it rather than wait, and because it is the only way to ask
 * again for a gap an automatic sweep has already tried once and left alone. It
 * starts the same sweep rather than a second path to the controller.
 */
@Composable
fun RecordGapsRow(state: DashboardUiState, viewModel: DashboardViewModel) {
    val report = state.gaps
    val connected = state.connection is ConnectionState.Connected

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            gapSummary(report),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (report.lost > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (report.lost > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        // Worst first: a hole nothing can recover is the one the reader has to
        // see, and it is the one they can do least about, so it must not be
        // pushed off the end of the list by three that are already being fetched.
        val listed = report.gaps
            .sortedWith(compareBy({ standingOrder(it) }, { -it.expectedReadings }))
            .take(GAPS_LISTED)
        for (gap in listed) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    gapWhen(gap),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${gapDetail(gap)} · ${gapStandingLabel(gap)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = standingColor(gap),
                )
            }
        }
        if (report.gaps.size > listed.size) {
            Text(
                // Not "shaded on the plot": the shading is a display preference
                // and off by default, and this line has to be true either way.
                "and ${report.gaps.size - listed.size} more, all counted in the summary above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (report.fetching > 0) {
            Text(
                "Fetching ${report.fetching} session(s) to close what can be closed. " +
                    "Nothing already here is touched: filling only ever adds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = viewModel::findAndFillGaps,
            // Nothing to compare against without a sensor on the other end. The
            // sweep would only stand itself down a minute at a time, and a button
            // that appears to work and does nothing is worse than one that says
            // it cannot.
            enabled = connected && !report.sweeping && !transfersAreInFlight(state),
            modifier = Modifier.weight(1f),
        ) { Text(if (report.sweeping) "Looking for gaps…" else "Find and fill gaps now") }
    }
    Text(
        "The app compares its own record against the sensor's every half hour and " +
            "fetches whatever is missing and still there. Filling only ever adds " +
            "readings - nothing already on this device is overwritten, reordered or " +
            "dropped.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Lost first, then not-known, then the ones already being dealt with. */
private fun standingOrder(gap: RecordGap): Int = when (gap.standing) {
    GapStanding.Lost -> 0
    is GapStanding.Unknown -> 1
    is GapStanding.Fillable -> 2
}

@Composable
private fun standingColor(gap: RecordGap): Color = when (gap.standing) {
    GapStanding.Lost -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

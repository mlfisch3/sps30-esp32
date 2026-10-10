package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.DataInventory
import com.sensirion.sps30.data.RecordCount
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.RecordStanding
import com.sensirion.sps30.data.RecordWork

/**
 * The width the four labels share, so the figures line up in a column.
 *
 * A comparison is read down the values, not across the rows, and values that
 * start at four different x positions are four separate sentences rather than
 * one table. Wide enough for "Gone for good" at `bodyMedium` on the narrowest
 * screen the app runs on, which is what keeps the labels off one line each.
 */
private val INVENTORY_LABEL_WIDTH = 118.dp

/** Thousands separators, the way every other count on this tab is written. */
private fun readings(n: Long): String = "%,d".format(n)

/**
 * One side of the record as a line: "12 sessions · 148,893 readings", or the
 * sentence saying why there is no number.
 *
 * Sessions first because that is the unit the rest of the tab works in - the
 * copy, the listing, the erase gate all count sessions - and readings second
 * because it is the figure that says how much is at stake.
 */
internal fun inventoryValue(count: RecordCount): String = when (count) {
    is RecordCount.Unknown -> count.because
    is RecordCount.Counted ->
        if (count.isNothing) {
            "nothing"
        } else {
            "${count.sessions} session(s) · ${readings(count.readings)} readings"
        }
}

/**
 * The fourth row: readings that were recorded, never arrived, and no longer are.
 *
 * Its own row rather than a share of "Not here yet", because the two call for
 * opposite reactions and only one of them has a button. "Not here yet" is data
 * sitting on the sensor waiting to be fetched; this is data the ring has
 * recorded over, and nothing above or below this panel can bring it back.
 *
 * It has the same three-way honesty every other figure here has: a count, a
 * "nothing" that has actually been established, or a sentence saying why nobody
 * knows. An empty list of holes before a sweep has run is *not* the absence of
 * holes - that conflation is exactly how three hours of the captain's record
 * went missing with nothing on screen to say so.
 */
internal fun goneForGoodValue(gaps: RecordGapReport): String {
    // "gap(s)", the word the section further down uses, rather than a synonym.
    // The two blocks are counting the same holes and a reader who has to work out
    // that they are is a reader reconciling two figures instead of reading one.
    val lost = if (gaps.lost > 0) {
        "about ${readings(gaps.readingsLost.toLong())} readings, in ${gaps.lost} gap(s)"
    } else {
        null
    }
    val unjudged = if (gaps.undecided > 0) {
        "${gaps.undecided} gap(s) not yet judged against the sensor"
    } else {
        null
    }
    return when {
        lost != null && unjudged != null -> "$lost · and $unjudged"
        lost != null -> lost
        unjudged != null -> "none so far · $unjudged"
        gaps.sweeping -> "still comparing this device's record against the sensor"
        gaps.sweptAtMs == 0L -> "not compared against the sensor yet"
        else -> "nothing"
    }
}

/** True when [goneForGoodValue] is a figure rather than a reason there is none. */
internal fun goneForGoodIsKnown(gaps: RecordGapReport): Boolean =
    gaps.lost > 0 || (gaps.sweptAtMs > 0L && gaps.undecided == 0)

/**
 * What is running, in a clause that can be appended to any claim.
 *
 * Null when nothing is. This is the whole repair: the summary is no longer a
 * function of the inventory alone, so a settled sentence can never be printed
 * over a moving number. Every arm ends in the same words on purpose - the reader
 * does not need to learn six phrasings of "these figures are still moving", they
 * need to notice it, and a clause that reads the same every time is one that is
 * noticed by its shape.
 */
internal fun workClause(work: RecordWork): String? = when (work) {
    RecordWork.None -> null

    RecordWork.Asking ->
        "A copy has been asked for and the sensor has not answered yet, so these figures " +
            "are still moving."

    is RecordWork.Copying ->
        "A copy is running, so these figures are still moving."

    RecordWork.Loading ->
        "Stored sessions are being read back in, so these figures are still moving."

    is RecordWork.ClosingGaps ->
        if (work.sessions > 0) {
            "${work.sessions} session(s) are being fetched to close gaps, so these figures " +
                "are still moving."
        } else {
            "This device's record is being compared against the sensor's, so these figures " +
                "are still moving."
        }

    RecordWork.Sending ->
        "Readings are being sent to the sensor, so its own figures are still moving. " +
            "Nothing leaves this device."
}

/**
 * The bare comparison, in one sentence, with nothing said about time.
 *
 * Every arm is true at the instant it is drawn and not one word further. The
 * sentence it replaces - "Everything the sensor is holding has reached this
 * device" - reads as a finished state, and on a sensor that is still recording
 * there is no such state; it was already less true than it sounded by the time
 * it had been read. What makes each of these safe is the clause
 * [sensorSideSummary] puts around it, which is why this is not called directly.
 */
private fun sensorSideClaim(inventory: DataInventory): String {
    val gap = inventory.gap
    return when {
        gap is RecordCount.Unknown -> "Whether anything is still on the sensor is not known yet."

        inventory.sensor.let { it is RecordCount.Counted && it.isNothing } ->
            "The sensor is not holding any recorded sessions."

        inventory.worthCopying ->
            "${readings((gap as RecordCount.Counted).readings)} readings are on the sensor and " +
                "not here yet. \"Copy sessions to this device\" below brings them here, and " +
                "the sensor keeps its own."

        // Past tense off a link that has gone. The sensor goes on recording
        // while this phone is not listening, so present tense here would be a
        // claim about a device nobody is talking to.
        inventory.everythingIsHere && !inventory.live ->
            "Everything the sensor was holding when this phone last heard from it is here."

        inventory.everythingIsHere -> "Everything the sensor is holding is here."

        else -> "Whether anything is still on the sensor is not known yet."
    }
}

/**
 * The first of the two claims: **has this device got what the sensor holds?**
 *
 * Three parts, and the order of them is the repair:
 *
 *  1. **What is running**, if anything. It goes first because the fault was not
 *     that the panel said something false - it was that it asserted completeness
 *     above a progress row plainly still working, and put the reassuring
 *     sentence where the eye lands first.
 *  2. **The comparison itself**, from [sensorSideClaim].
 *  3. **That the figures are live**, but only when nothing is running and the
 *     answer is parity. Without it "Everything the sensor is holding is here"
 *     reads as an achievement that will keep; with it, it reads as what it is,
 *     a comparison of two numbers that are both still moving. With a work clause
 *     already present it would be the same fact said twice.
 */
internal fun sensorSideSummary(standing: RecordStanding): String {
    val parts = ArrayList<String>(3)
    workClause(standing.work)?.let(parts::add)
    parts.add(sensorSideClaim(standing.inventory))
    if (standing.atParityAndSettled && standing.inventory.live) {
        parts.add("Both figures are live and move as it records.")
    }
    return parts.joinToString(" ")
}

/**
 * The second claim, and the one actually being asked: **is any of it gone?**
 *
 * A different question from [sensorSideSummary] and not a stronger version of
 * it. The sensor's ring wraps; a device can hold every reading the sensor still
 * has and be missing three hours the sensor destroyed last Tuesday, and the
 * first sentence would be entirely true throughout. That is the captain's
 * 2026-08-04, and it is why these are two sentences and never one.
 *
 * The wording is bounded by what the detection can actually see: holes are found
 * *between* readings this device holds, so what is claimed is that there are
 * none between the ends of the record - never that nothing preceded it. Kept
 * consistent with the fuller list under "Gaps in the record" by both being pure
 * functions of the same [RecordGapReport]; see `gapSummary`.
 */
internal fun everRecordedSummary(gaps: RecordGapReport): String = when {
    gaps.lost > 0 ->
        "About ${readings(gaps.readingsLost.toLong())} readings are gone for good: the sensor " +
            "recorded over them before they reached this device, and nothing can bring them " +
            "back. They are listed under \"Gaps in the record\" below."

    gaps.undecided > 0 ->
        "${gaps.undecided} gap(s) in this device's record have not been judged against the " +
            "sensor yet, so whether any of it is gone is still open. \"Find and fill gaps " +
            "now\" below settles it."

    gaps.gaps.isNotEmpty() ->
        "${gaps.gaps.size} gap(s) in this device's record, and the sensor still has all of " +
            "it. \"Find and fill gaps now\" below closes them."

    gaps.sweeping -> "Whether anything earlier is missing is being checked now."

    gaps.sweptAtMs == 0L ->
        "Whether anything earlier is missing has not been checked yet. It is checked " +
            "automatically every half hour while connected."

    // Worded apart from `gapSummary`, which heads the list of gaps further down
    // and makes the same claim. Two sentences on one screen that are nearly the
    // same words are read as one sentence printed twice, and the second one stops
    // being read - which on this tab means the detail stops being read.
    else ->
        "This device holds an unbroken record: no reading is missing between its first and " +
            "its last."
}

/**
 * What is on the sensor, what is on this device, what is outstanding, what is
 * gone - and whether anything is moving while it is being read.
 *
 * ## Why this is the top of the Data tab
 *
 * The tab could already say what one finished copy had transferred, which is a
 * report about an action rather than an account of the record. The question it
 * did not answer is the one actually being asked - *is my data here* - and
 * answering that takes both sides on screen at once with the difference between
 * them stated, so the next thing to do is visible instead of worked out.
 *
 * The one action that closes the gap is the copy button immediately below this,
 * in [SessionCopyRow]. It is deliberately not repeated here: two buttons doing
 * the same thing is the set of choices this was asked not to be.
 *
 * ## The two claims, which are not the same claim
 *
 * [sensorSideSummary] answers *has this device got what the sensor holds*, and
 * [everRecordedSummary] answers *is any of it gone for good*. They are printed
 * as two sentences, always both, because the second is the one a worried user
 * wants and the first can be a confident yes while the answer to the second is
 * three lost hours. See [RecordStanding].
 *
 * ## What it will not do
 *
 * Show a number nobody has established. Every figure comes through
 * [RecordCount], or through [goneForGoodValue] which follows the same rule, so
 * "the sensor has not been asked" can never render as a confident zero. See
 * [DataInventory] for the rule and the fault it comes from.
 *
 * Nor state anything as finished while something is running. Every sentence here
 * is built from [RecordStanding] rather than from the inventory alone, which is
 * what stops a settled claim being printed over a climbing figure - see
 * [RecordWork] for the screen that did exactly that.
 */
@Composable
fun DataInventoryRow(standing: RecordStanding) {
    val inventory = standing.inventory
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        InventoryLine(
            label = "On the sensor",
            value = inventoryValue(inventory.sensor),
            known = inventory.sensor is RecordCount.Counted,
        )
        InventoryLine(
            label = "On this device",
            value = inventoryValue(inventory.here),
            known = inventory.here is RecordCount.Counted,
        )
        InventoryLine(
            label = "Not here yet",
            value = inventoryValue(inventory.gap),
            known = inventory.gap is RecordCount.Counted,
            // The one figure worth finding without reading the other two. It is
            // emphasised only when there is something in it: a bold "nothing" is
            // a claim drawing attention to itself for no reason.
            emphasis = inventory.worthCopying,
        )
        // Outside the arithmetic of the three above, and that is the point of
        // having it: those three reconcile against what the sensor holds now,
        // and this counts what it does not hold any more. A user who reads only
        // the rows still cannot come away thinking the record is whole.
        InventoryLine(
            label = "Gone for good",
            value = goneForGoodValue(standing.gaps),
            known = goneForGoodIsKnown(standing.gaps),
            emphasis = standing.gaps.lost > 0,
            alarming = standing.gaps.lost > 0,
        )

        Text(
            sensorSideSummary(standing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            everRecordedSummary(standing.gaps),
            style = MaterialTheme.typography.bodySmall,
            // The only sentence on the tab that reports data nobody can get
            // back. It is allowed to look like it.
            fontWeight = if (standing.gaps.lost > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (standing.gaps.lost > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        // Past tense off a link that has gone, exactly as the storage line in
        // [SessionCopyRow] does it. Nothing clears `storage` on a disconnect and
        // the sensor keeps recording while this phone is not listening, so these
        // figures stop being current the moment the link does.
        if (inventory.sensor is RecordCount.Counted && !inventory.live) {
            Text(
                "These are the last figures the sensor sent. It has gone on recording " +
                    "since, so there is very likely more there now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // A comparison that is short of sessions is not a comparison, and the
        // difference lands in the gap where it reads as data this device is
        // missing. It may be; it may equally be here already. Say which it is.
        if (inventory.unnamedSessions > 0) {
            Text(
                "${inventory.unnamedSessions} of the sensor's session(s) have not been " +
                    "named to this device yet, so they are counted as missing until they " +
                    "are. Copying asks for the whole list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The known disagreement between this block and the stored-sessions
        // overview further down the same tab. They are counted here, they copy
        // and plot like any other session, and the overview below reads them as
        // holding nothing - because it buckets on a timestamp and these rows
        // deliberately carry none. Leaving it unsaid would leave two totals on
        // one screen that cannot be made to agree.
        if (inventory.clocklessSessions > 0) {
            Text(
                "${inventory.clocklessSessions} of them were recorded while the sensor " +
                    "had no clock. They are copied and plotted like any other, but they " +
                    "carry no date - so \"Sessions already stored on this device\" below " +
                    "cannot place them and counts them as holding no readings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One row of the comparison: a fixed-width label and the figure beside it.
 *
 * The value has no line limit. It is either a count, which is short, or the
 * sentence explaining why there is no count - and an explanation is the one
 * thing on the screen that carries something nothing else carries, so it may
 * wrap but it may not be cut. Same rule as [ControlNote].
 */
@Composable
private fun InventoryLine(
    label: String,
    value: String,
    known: Boolean,
    emphasis: Boolean = false,
    alarming: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(INVENTORY_LABEL_WIDTH),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            // An unknown reads as the aside it is, so it cannot be mistaken at a
            // glance for one of the figures either side of it.
            color = when {
                alarming -> MaterialTheme.colorScheme.error
                known -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
    }
}

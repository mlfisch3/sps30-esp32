package com.sensirion.sps30.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Help tab: a tutorial, the questions the app actually provokes, and the
 * explanations that used to be shouted at the user from the main screen.
 *
 * Everything here is collapsed by default. A help section that opens as a wall
 * of prose is the same long scroll the tabs were introduced to get rid of, and
 * nobody reads it; a list of questions is scannable, and opening one is a
 * deliberate act by somebody who wanted that answer.
 *
 * The writing rule for this file: **explain, do not warn.** These topics are
 * here because a curious user asked, not because anything is wrong. Where a
 * fact has been measured - the clock drift, the fan's counter - the measurement
 * is quoted, because a number ends a worry that a reassurance only postpones.
 */
@Composable
fun HelpTab(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "How this works, and the questions it tends to raise. Tap a heading " +
                "to open it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Section("Getting started")
        for (topic in GETTING_STARTED) HelpTopic(topic)

        Section("Frequently asked")
        for (topic in FREQUENTLY_ASKED) HelpTopic(topic)

        GapLegendSection()

        Section("Under the hood")
        for (topic in UNDER_THE_HOOD) HelpTopic(topic)
    }
}

/**
 * The key to the plot's gap patterns, moved here from the plot itself.
 *
 * It used to be drawn in the plot's bottom-left corner whenever a marked gap was
 * on screen. Now that the marking is a preference (off by default - see
 * `AppConfig.markGaps`) the key lives with the rest of the explanatory material,
 * where it does not spend plot space and can carry every pattern's full sentence.
 *
 * All four patterns are shown whether or not any gap is on screen, because this
 * is reference material rather than an annotation. Worst first, in [GapPattern]
 * order - the same order the Data tab lists gaps in. Each swatch is painted by
 * [drawGapFill], the same painter that shades the plot, so the key cannot drift
 * into showing something the plot does not draw; and the wording is [GAP_LEGEND],
 * which is pinned to the Data tab's own vocabulary.
 */
@Composable
internal fun GapLegendSection() {
    Section("The plot's gap markings")
    Text(
        "\"Mark gaps in the record\" on the Display tab shades the plot where " +
            "readings are missing. It is off by default; with it off the trace " +
            "still lifts its pen across a hole, and the Data tab still reports " +
            "every gap either way. The patterns, worst first:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (pattern in GapPattern.entries) {
        val entry = GAP_LEGEND.getValue(pattern)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Canvas(Modifier.padding(end = 12.dp).size(width = 44.dp, height = 26.dp)) {
                drawGapFill(pattern, 0f, size.width, 0f, size.height)
            }
            Column {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A topic: the heading somebody scans, and the answer they open. */
private data class Topic(val question: String, val answer: String)

@Composable
private fun HelpTopic(topic: Topic) {
    var open by rememberSaveable(topic.question) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 6.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // A caret rather than an icon: it rotates nothing, costs no asset,
            // and reads the same in both themes.
            Text(
                if (open) "▾" else "▸",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                topic.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (open) {
            Text(
                topic.answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp),
            )
        }
    }
}

private val GETTING_STARTED = listOf(
    Topic(
        "What this app is",
        "A window onto a Sensirion SPS30 particle sensor. The sensor is wired to " +
            "a small controller board, and the board is what this app talks to. " +
            "The board does the measuring and keeps its own record; the app finds " +
            "it, draws what it is measuring, and keeps a copy of the readings on " +
            "this device.\n\n" +
            "That split is worth knowing, because it explains most of the app's " +
            "behaviour. Closing the app does not stop the sensor. Losing the " +
            "connection does not lose readings - the board keeps recording and " +
            "the gap is fetched back when you reconnect.",
    ),
    Topic(
        "Finding the sensor",
        "There is no address to type in. On Wi-Fi the app looks over the local " +
            "network for a board that answers, and connects to the first one that " +
            "does, so both have to be on the same network.\n\n" +
            "Bluetooth is the alternative when there is no Wi-Fi to share: it " +
            "connects to the board directly. It carries one viewer at a time, " +
            "where Wi-Fi carries several. Switching between the two keeps the old " +
            "connection until the new one answers.",
    ),
    Topic(
        "Reading the plot",
        "Two curves: PM1.0 and PM2.5, in micrograms per cubic metre. PM2.5 means " +
            "particles up to 2.5 micrometres across, so it includes PM1.0 and its " +
            "curve normally sits above it - in clean indoor air the two run close " +
            "together, and a gap opening between them means larger particles have " +
            "arrived.\n\n" +
            "The readouts at the top show the newest value of each. The board " +
            "records more size bands than the plot draws; they are all in an " +
            "exported CSV.",
    ),
    Topic(
        "Moving around in time",
        "The buttons above the plot set how much time is on screen - the window. " +
            "Drag the plot sideways to move through time, pinch to zoom. \"All\" " +
            "fits everything loaded.\n\n" +
            "The window buttons are yours to change: Customize → Window buttons " +
            "takes a list like \"10s, 1m, 1h, all\".\n\n" +
            "\"Lock graph\" makes the plot ignore touch entirely, so a stray " +
            "finger cannot move the view or lose a crosshair you have placed. " +
            "Everything off the plot keeps working, including the button itself - " +
            "it reads \"Release graph\" while the lock is on.\n\n" +
            "Each axis can be held on its own instead. Once you have dragged or " +
            "pinched one, an \"X axis\" or \"Y axis\" row appears with Lock and a " +
            "Release beside it: Lock keeps that axis exactly where you put it " +
            "while the other stays free, and Release hands it back - time to " +
            "following the newest reading, values to autoscaling.",
    ),
    Topic(
        "Setting how high the plot reaches",
        "The value axis normally fits itself to whatever is on screen. Two " +
            "fingers on the plot take it over: drag them up or down to shift the " +
            "range, or pinch to scale it.\n\n" +
            "A pinch scales about the point between your fingers, and that point " +
            "stays where it is - so putting them near the bottom of the plot and " +
            "spreading holds the bottom of the axis and stretches the trace " +
            "upwards, and pinching there keeps the bottom while raising the top. " +
            "Fingers near the top do the same to the top. That is how you keep " +
            "one end of the range and move only the other.\n\n" +
            "The axis never goes below zero, whatever you do to it: a reading " +
            "cannot be negative, so there is nothing down there to see.\n\n" +
            "Two fingers, always - one finger on the plot places the crosshair or " +
            "moves through time, and touching the glass to read a value must " +
            "never drag the range out from under you. The narrow strip where the " +
            "value labels sit is the exception: it is the axis itself, so one " +
            "finger drags it there.\n\n" +
            "Once you have moved it, a \"Y axis\" row appears with Lock and " +
            "Release. Autoscaling stays switched on underneath, so Release hands " +
            "the axis straight back to it.",
    ),
    Topic(
        "Pin, and the crosshair",
        "Pin holds the view at a moment instead of following the newest reading, " +
            "so you can study something while measurement carries on. Tapping Pin " +
            "again steps back through your recent framings; Unpin leaves pinning " +
            "in one press without walking through them. Tap and hold Pin to set a " +
            "mark you can come back to, and tap and hold again while pinned to " +
            "return to it.\n\n" +
            "The crosshair is separate: it reads out the exact values under it at " +
            "the instant you place it. Follow makes it ride the newest reading, " +
            "which turns it into a live numeric display with the time of day on " +
            "it.\n\n" +
            "The crosshair can also be pinned, three ways. \"Pin time\" - also " +
            "the small button at the bottom left of the plot - fixes it to the " +
            "data point it is on, so it stays on that event whatever the view " +
            "does. \"From right\" fixes how many points it sits in from the " +
            "right edge of the graph, so it rides the incoming data at that " +
            "distance. \"From left\" does the same from the left edge. If the " +
            "time axis is not pinned to a mark on the left, from-left and " +
            "from-right come to the same thing; when it is pinned, from-left " +
            "travels leftward with its data point and gets increasingly " +
            "scrunched as new data arrives, while from-right holds its distance " +
            "from the edge.\n\n" +
            "While any pin is engaged, touching the plot does not move the " +
            "crosshair at all - a single finger pans instead, as it does with " +
            "the crosshair off. Unpin to place it by touch again.\n\n" +
            "\"Peak\" jumps the crosshair to the highest point in view and pins " +
            "it there as an ordinary Pin time - so if the view later shows a " +
            "higher point, the crosshair stays on the one it snapped to. It " +
            "takes the highest of the curves that are switched on.\n\n" +
            "\"Start here\" and \"End here\" go the other way: instead of moving " +
            "the crosshair to the view, they move the view to the crosshair. " +
            "Start here makes its moment the left edge of the window - " +
            "everything after it stays on screen - and End here makes it the " +
            "right edge. Both keep the window's duration: they pan the view, " +
            "never zoom it. At the ends of the record the view stops so a full " +
            "window of data stays on screen.",
    ),
    Topic(
        "Averaging",
        "Each plotted point can stand for one reading or for an average over " +
            "several seconds. Longer averaging makes a trend legible where " +
            "second-by-second noise would bury it, and shorter shows the noise.\n\n" +
            "The Average button above the plot is the whole control. Tap it and " +
            "the chart picks a sensible window from how much time is on screen, " +
            "adjusting it as you zoom; tap again to turn averaging off. Press " +
            "and hold to pick a size by hand - the chooser stays open while " +
            "you try sizes against the graph, and a touch anywhere else puts " +
            "it away. The sizes it offers are edited in Options.\n\n" +
            "It only changes what is drawn. Nothing is recorded differently, and " +
            "no reading is lost - turning Average off brings the detail back.",
    ),
)

private val FREQUENTLY_ASKED = listOf(
    Topic(
        "Where do the times come from?",
        "The controller board has no clock of its own. It counts from the moment " +
            "it powers up, and the app turns that count into times of day using an " +
            "equivalence the board records the first time anything with a clock " +
            "reaches it - usually this device, on connecting.\n\n" +
            "The spacing between readings is measured by the board itself and is " +
            "exact either way; only the placement of the whole block on the " +
            "calendar comes from that conversion. The board's free-running clock " +
            "was measured drifting 0.029 seconds per hour - about 0.7 seconds a " +
            "day - so for anything this sensor is used for, a session timed by a " +
            "clock and one timed this way are the same thing.\n\n" +
            "An exported CSV records which it was, in its time_source column, so a " +
            "file can always be traced back to how its times were arrived at.\n\n" +
            "The one case worth knowing: if the board records a run and loses " +
            "power before anything with a clock ever connects, no equivalence was " +
            "made and nothing knows when that block happened. Its readings and " +
            "their spacing are still exact - only its position is unknown. The app " +
            "marks it unplaced rather than guessing, and lets you set the start " +
            "time yourself if you remember it.",
    ),
    Topic(
        "Why do some controls sit behind a red guard?",
        "Because they act on the physical sensor rather than on the display. " +
            "Pausing it, cleaning it, restarting it or changing how it measures " +
            "all interrupt the record, and none of them is something to do by " +
            "brushing the screen.\n\n" +
            "Flip the safety and the buttons are live. It re-engages by itself " +
            "about twenty seconds after you stop touching the menu, so it cannot " +
            "be left off by accident.",
    ),
    Topic(
        "Why does the fan clean itself?",
        "Dust settles on the optics, and a dirty sensor does not fail - it reads " +
            "low, which is worse, because nothing looks wrong. The self-clean is a " +
            "ten-second full-speed fan burst that clears it.\n\n" +
            "The app counts the seconds the fan has actually run, not calendar " +
            "time, which is why in low-power mode a week of fan time is months of " +
            "wall clock. Readings during the clean and just after it are flagged " +
            "as disturbed.",
    ),
    Topic(
        "What are flagged readings?",
        "Readings the sensor itself took while it was not settled - the first " +
            "moments after starting up, and the period around a fan clean. They " +
            "are real measurements of a disturbed airflow, so they are recorded " +
            "rather than thrown away, but they show as a spike that has nothing " +
            "to do with the air.\n\n" +
            "\"Exclude sensor-spike readings\" in Customize keeps them out of the " +
            "plot. It never deletes them; an export still contains them, flagged.",
    ),
    Topic(
        "What is the difference between CSV and image?",
        "CSV saves the numbers - everything loaded on this device, including what " +
            "is scrolled or zoomed out of view, with all the size bands the board " +
            "records. It is the file to keep.\n\n" +
            "Image saves a picture of the plot exactly as it looks right now. It " +
            "is for showing somebody, not for keeping.\n\n" +
            "Both are different from copying a session off the board, which " +
            "fetches the board's own record across the connection - including " +
            "anything measured while this device was not listening.",
    ),
    Topic(
        "There is a shaded band on the plot. What is it?",
        "A stretch where this device has no readings. A bare hole in the trace " +
            "looks exactly like a quiet trace, so \"Mark gaps in the record\" on " +
            "the Display tab shades gaps instead, and the shading says which " +
            "kind it is - the patterns are keyed below, under \"The plot's gap " +
            "markings\". The switch is off by default; off or on, the Data tab " +
            "reports every gap just the same.\n\n" +
            "Hatched diagonals mean the board still has those readings and the " +
            "app is going to fetch them. It compares its own record against the " +
            "board's every half hour and collects whatever is missing, so a band " +
            "like this normally closes itself; Options → Data → Gaps in the " +
            "record has a button if you would rather not wait.\n\n" +
            "A solid block with hard edges means the readings are gone. The " +
            "board's storage is a ring: it records over its oldest data to make " +
            "room, and once it has passed a stretch nothing can bring it back. " +
            "There is nothing to do about a band like this, which is why it is " +
            "drawn differently from one that is merely waiting.\n\n" +
            "A faint dotted band means the board has not yet said enough for the " +
            "app to tell which of the two it is. It is a question, not an answer, " +
            "and it settles as soon as the board has listed everything it holds.\n\n" +
            "Filling a gap only ever adds readings. Nothing already on this " +
            "device is overwritten, reordered or removed, and running a fill " +
            "twice does nothing the second time.",
    ),
    Topic(
        "Will it keep recording if I close the app?",
        "The board always keeps recording; it does not care whether anyone is " +
            "watching. What closing the app stops is this device's own copy.\n\n" +
            "Background monitoring keeps that copy running, and alerts working, " +
            "while the app is not on screen. Without it, whatever was measured " +
            "while the app was closed is fetched back the next time it connects.",
    ),
    Topic(
        "Why did an alert not fire?",
        "An alert needs a rule with a threshold, and the reading has to cross it " +
            "on this device - so with the app closed and background monitoring " +
            "off, nothing was watching.\n\n" +
            "Excursions the board noticed while nothing was listening are kept by " +
            "the board and handed over on reconnect, so they appear after the " +
            "fact rather than at the moment they happened. Test on a rule fires " +
            "it deliberately, which is the quick way to check notifications are " +
            "allowed through at all.",
    ),
)

private val UNDER_THE_HOOD = listOf(
    Topic(
        "What the sensor actually measures",
        "A laser shines across a stream of air pulled through by the fan, and the " +
            "sensor counts the flashes of scattered light and sizes them. So the " +
            "primary measurement is a count of particles per size band; the " +
            "microgram figures are computed from those counts by assuming a " +
            "density and a shape.\n\n" +
            "That is worth knowing because it sets the honest precision. The " +
            "counts are what was observed; the mass concentrations are a good " +
            "standardised estimate derived from them, and both are in the CSV.",
    ),
    Topic(
        "Continuous and low-power modes",
        "Continuous keeps the fan and laser running and reads at a fixed " +
            "interval. Low power runs the sensor only for part of each cycle: it " +
            "wakes, lets the airflow settle, averages for a while, and sleeps " +
            "again.\n\n" +
            "The settle time is not measurement - the readings taken during it are " +
            "the sensor stabilising, not the air changing. Low power trades detail " +
            "between cycles for a sensor that can run for a very long time on a " +
            "battery.",
    ),
    Topic(
        "How the connection recovers",
        "Every reading the board makes is stored on the board with its own " +
            "position in the run. When this device reconnects it says how far it " +
            "had got, and the board sends what is missing.\n\n" +
            "This is why disconnecting is not destructive and why switching " +
            "between Wi-Fi and Bluetooth costs nothing but a moment: the record is " +
            "the board's, and this device holds a copy that can always be caught " +
            "up.",
    ),
    Topic(
        "Who is connected, and control",
        "Several viewers can watch the same board at once over Wi-Fi. Watching is " +
            "unrestricted, but only one of them at a time may change the sensor, " +
            "so two people cannot fight over the measurement mode without " +
            "noticing.\n\n" +
            "Taking control takes it from whoever had it, and they are told. " +
            "Nobody is ever prevented from watching.",
    ),
    Topic(
        "Saved configurations",
        "Everything in this menu - windows, averaging, alerts, layout - can be " +
            "saved under a name and loaded back. It is the fastest way to keep a " +
            "setup for one kind of measurement without losing another.\n\n" +
            "Reset to defaults affects this app only. It changes nothing on the " +
            "board and deletes no readings.",
    ),
)

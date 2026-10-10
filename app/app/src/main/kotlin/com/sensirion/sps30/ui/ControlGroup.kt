package com.sensirion.sps30.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * What the named group on the front panel is called.
 *
 * This is the whole of the label now - there is no "▲"/"▼" any more, and the
 * word no longer sits in a gap hoping to be read as pointing at a row. See
 * [ControlGroup] for why. A named constant because the layout tests have to
 * find the group by its heading in order to say which buttons are under it.
 * The averaging group that used to stand beside this one is gone: averaging is
 * the single Average button in the chip cluster now, its sizes in a chooser
 * behind a press-and-hold (see `AveragingControl.kt`).
 */
internal const val WINDOW_GROUP_LABEL = "window"

/** The tag a layout test finds a group's outer box by, to measure what it encloses. */
internal fun controlGroupTag(label: String): String = "control-group-$label"

/** The tag for the inner box that holds only the group's value buttons. */
internal fun controlGroupValuesTag(label: String): String = "control-group-$label-values"

/**
 * A named group of controls: the name and the group's mode buttons on a top row,
 * the value buttons in a thin box under them, and an outer line around the whole.
 *
 * ## The fault this exists for
 *
 * The window row and the averaging row were labelled from the strip of space
 * between them: "▲ window" on the left pointing up, "average ▼" on the right
 * pointing down. Two small triangles at the edges of a line of text do not read
 * as arrows - they read as the disclosure carets they look exactly like, invited
 * a tap that did nothing, and only worked on a reader who already knew what they
 * meant. A box does the whole job with no glyph at all: enclosure is the
 * strongest grouping cue there is, and it cannot be mistaken for something to
 * press. So the groups are boxed and the triangles stay gone.
 *
 * ## Why the label sits on a row of its own, outside the values box
 *
 * The first boxed shape put the label inside the box, over the buttons, and left
 * whatever did not belong to the flow of values - Pin, on the window group - to
 * wrap onto a line by itself. On the tablet in landscape that line was mostly
 * air, and the captain read it as the mistake it looked like: a button floating
 * in an empty row, over a box with a word buried in it.
 *
 * This shape is the one he described instead. The label heads the group from its
 * own top row, and the group's *mode* buttons - Pin and Unpin, the controls that
 * change what the group is doing rather than pick one of its values - sit
 * beside the word, where they fill the row that was empty. The value buttons
 * live below in a thin box of their own ([controlGroupValuesTag]), so "one of
 * the sizes" and "how the group behaves" are told apart by geometry rather than
 * by reading. The outer line then encloses the lot, which is what makes the top
 * row part of the group rather than a stray caption above it.
 *
 * The top row is a [ControlChips] flow, not a `Row`: on the narrowest column a
 * label and two chips may genuinely not fit one line, and wrapping is the answer
 * everywhere else on this panel. The label centres itself against the chips on
 * its line, so the word and the buttons read as one row rather than a word
 * pinned to a corner.
 *
 * ## Why it is Columns with borders and nothing cleverer
 *
 * The classic fieldset - the label notched into the stroke - needs a custom
 * layout that measures text and cuts the border, and every such arrangement is
 * one more thing to re-check at four screen shapes. These are plain [Column]s:
 * no fixed height, no fixed width, no measurement of their own, so the flows
 * inside wrap exactly as they would unboxed and both boxes grow to whatever
 * their flow became. Adding a chip to a group costs a wrapped line and nothing
 * else, at any width - which is the property [ControlChips] exists to give and
 * which a box must not take away.
 *
 * ## The weight it carries
 *
 * `outlineVariant` at 1 dp for both lines, the app's quietest stroke - the same
 * one [ConnectionSelector] uses for an unselected card. A group boundary is
 * structure, not a control: it has to be seen when looked for and disappear when
 * not. The 8 dp corner matches the readout cells and the plot's own overlays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlGroup(
    label: String,
    modifier: Modifier = Modifier,
    labelChips: @Composable FlowRowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(controlGroupTag(label))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            // Enough to read as a group rather than as a line drawn through the
            // chips, and no more: this sits above the plot, and every dp of it
            // in portrait is a dp the chart does not get.
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        ControlChips {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(start = 2.dp, end = 2.dp),
            )
            labelChips()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .testTag(controlGroupValuesTag(label))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            content()
        }
    }
}

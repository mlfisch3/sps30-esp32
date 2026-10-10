package com.sensirion.sps30.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A horizontally scrolling chip strip that says so when it has more to show.
 *
 * ## Why a mark and not the fade it replaces
 *
 * This used to end in a 24 dp gradient to the surface colour, and the captain's
 * objection to it was that it does not say anything: a strip whose last chip goes
 * dim reads as a chip that is disabled, or as an edge the theme happens to draw
 * that way, long before it reads as "there is more over here". A cue you have to
 * be taught is a cue that does not work on the person meeting it first.
 *
 * So the fade is gone and a small solid triangle takes its place, pointing the way
 * the content continues. It is drawn rather than typed, because a "▸" in a `Text`
 * is the disclosure caret [HelpSection] uses and would put back exactly the
 * "somewhere to press for more" reading that [ControlGroup] was built to remove.
 *
 * ## Why it is not a scroll bar
 *
 * A bar is a control: it looks draggable, so it gets dragged, and dragging a 30 dp
 * bar to move a strip of chips is clumsy work with a fingertip when a flick at the
 * chips themselves does the same job better. The mark is deliberately not a
 * control - it has no click, it is 6 dp wide, and there is nothing about it to
 * grab. It answers the one question the fade was trying to answer and stops.
 *
 * ## Why the mark takes its own space rather than sitting over the chips
 *
 * The fade could overlay because fading a chip still leaves it legible. A solid
 * triangle over a chip is a glyph stuck to that chip. So the strip is a `Row` of
 * three: an optional mark, the scrolling content with the weight, and an optional
 * mark. The viewport gives up 12 dp on a side that has one.
 *
 * That cannot flicker. Showing a mark narrows the viewport, which can only make
 * the overflow it is reporting larger, never turn it off; and a mark goes away
 * only when the content genuinely fits, which needs the viewport to grow. So each
 * side settles on its first pass and stays there.
 *
 * Both sides are marked, not just the right one: once a user has scrolled, the
 * chips that went off the left are exactly as hidden as the ones on the right, and
 * Auto being pinned outside this strip is what makes that survivable rather than
 * a way to lose a control.
 *
 * [what] names the chips for the screen reader - "window sizes" - since a
 * triangle has nothing for TalkBack to read.
 */
@Composable
internal fun ScrollingChips(
    what: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverflowMark(visible = scroll.canScrollBackward, forward = false, what = what)
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        OverflowMark(visible = scroll.canScrollForward, forward = true, what = what)
    }
}

/** What the screen reader is told when a strip has more chips one way. */
internal fun moreChipsThisWay(what: String, forward: Boolean): String =
    "More $what to the ${if (forward) "right" else "left"}. Drag the chips to reach them."

/**
 * The triangle itself: 6 x 9 dp with 3 dp of air on each side, in the same quiet
 * `onSurfaceVariant` the group headings and [ControlNote] use. Structure rather
 * than a control, and coloured to say so.
 */
@Composable
private fun OverflowMark(visible: Boolean, forward: Boolean, what: String) {
    if (!visible) return
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    val description = moreChipsThisWay(what, forward)
    Canvas(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(width = 6.dp, height = 9.dp)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        val mark = Path().apply {
            if (forward) {
                moveTo(0f, 0f)
                lineTo(w, h / 2f)
                lineTo(0f, h)
            } else {
                moveTo(w, 0f)
                lineTo(0f, h / 2f)
                lineTo(w, h)
            }
            close()
        }
        drawPath(mark, colour)
    }
}

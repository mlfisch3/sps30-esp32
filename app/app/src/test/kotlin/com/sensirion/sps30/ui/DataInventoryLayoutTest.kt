package com.sensirion.sps30.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.DataInventory
import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordCount
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.RecordStanding
import com.sensirion.sps30.data.RecordWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The inventory is a table, and a table that stops lining up is prose.
 *
 * The four rows are read **down the values**, not across, which is the whole
 * reason the labels share a fixed width: the figures start at the same x and the
 * comparison can be taken in without reading a word. Two things break that, and
 * neither is visible to any assertion about strings.
 *
 * - **A label that wraps.** A fixed-width column is a promise the label fits in
 *   it, and a wrapped label makes its row two lines tall while its neighbours are
 *   one, so the values no longer sit on an even pitch. The label column is the
 *   same width on a phone as on a tablet, so this is checked at the narrowest the
 *   sheet ever gets and not at a representative width.
 * - **A value or an explanation that is cut.** A count cut mid-number is worse
 *   than no count, and the sentences here are the ones that say a figure is *not
 *   known* - the single thing on the block nothing else carries. Same rule as
 *   [ControlNote]: a label may be cut, a sentence may not.
 *
 * Run at the four screen shapes in [TABLET_LANDSCAPE] and friends, because the
 * sheet's width is not the screen's - a `ModalBottomSheet` caps itself - and both
 * the cap and the phone's own width have to hold.
 *
 * Slow for the reason in `ui/LayoutTest.kt`: without
 * `GraphicsMode.Mode.NATIVE` Robolectric measures text at about a pixel per
 * character and nothing ever overflows, so every assertion here would pass
 * against any bug it exists to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class DataInventoryLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** One hole the ring has eaten, for the row that has to be able to say so. */
    private val lostGap = RecordGap(
        startEpoch = 1_754_300_538.0,
        endEpoch = 1_754_313_532.0,
        standing = GapStanding.Lost,
        expectedReadings = 12_994,
    )

    /** The four row labels, which is the set that shares the fixed column. */
    private val labels =
        listOf("On the sensor", "On this device", "Not here yet", "Gone for good")

    /**
     * The fullest the block ever is: every figure long, every note showing.
     *
     * Deliberately worse than the captain's board. Six figures wide, a listing a
     * page short, clockless sessions in it, a copy running and readings lost to
     * the ring - every note and both summary sentences on screen at once, which
     * is the state with the most to fit and the least chance of being seen
     * before it ships.
     */
    private val crowded = RecordStanding(
        inventory = DataInventory(
            sensor = RecordCount.Counted(sessions = 128, readings = 1_486_293),
            here = RecordCount.Counted(sessions = 96, readings = 1_210_048),
            gap = RecordCount.Counted(sessions = 32, readings = 276_245),
            live = false,
            clocklessSessions = 12,
            unnamedSessions = 64,
        ),
        gaps = RecordGapReport(
            gaps = List(9) { lostGap },
            sweptAtMs = 1L,
            fetching = 3,
        ),
        work = RecordWork.Copying(session = 7, ofSessions = 128, readings = 964_112, newHere = 0),
    )

    /** The other extreme: nothing known, so every value is a whole sentence. */
    private val blank = RecordStanding(
        inventory = DataInventory.UNKNOWN,
        gaps = RecordGapReport(),
        work = RecordWork.None,
    )

    @Test @Config(qualifiers = PHONE_PORTRAIT) fun phonePortraitLinesUp() = assertItReads()
    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun phoneLandscapeLinesUp() = assertItReads()
    @Test @Config(qualifiers = TABLET_PORTRAIT) fun tabletPortraitLinesUp() = assertItReads()
    @Test @Config(qualifiers = TABLET_LANDSCAPE) fun tabletLandscapeLinesUp() = assertItReads()

    /**
     * Both states, at the sheet width this screen shape gives.
     *
     * The phone's sheet is the screen; the tablet's is capped by
     * `BottomSheetDefaults.SheetMaxWidth`, and either way the tab's own 20 dp of
     * horizontal padding comes off it. The narrow case is the one that can fail,
     * so it is the one measured rather than assumed away.
     */
    private fun assertItReads() {
        // One `setContent` per test - the rule allows no second - so the state
        // is swapped instead and the tree recomposed between the two.
        var showing by mutableStateOf(blank)
        compose.setContentAtWidth(sheetContentWidth()) { DataInventoryRow(showing) }

        assertNothingIsCut(blank) { showing = it }
        assertNothingIsCut(crowded) { showing = it }
    }

    private fun assertNothingIsCut(standing: RecordStanding, show: (RecordStanding) -> Unit) {
        val situation =
            if (standing === blank) "with nothing known" else "with everything on screen at once"
        show(standing)
        compose.waitForIdle()

        for (label in labels) {
            val layout = compose.onNodeWithText(label, useUnmergedTree = true).textLayout()
            assertEquals(
                "\"$label\" wrapped onto ${layout.lineCount} lines $situation, so its row is " +
                    "taller than its neighbours and the figures stop lining up",
                1,
                layout.lineCount,
            )
            assertEquals(
                "\"$label\" was ellipsised $situation",
                emptyList<Int>(),
                layout.ellipsizedLines(),
            )
        }

        // Every string the block draws, found by walking rather than by naming:
        // the notes are long sentences and repeating them here would guard the
        // copy in this file instead of the copy on the screen.
        for ((text, layout) in everyTextLayout()) {
            assertEquals(
                "$situation, this was cut: \"$text\"",
                emptyList<Int>(),
                layout.ellipsizedLines(),
            )
        }

        // The structural half, and the one that would survive a font change: the
        // values all start at the same x. That is what makes it a table.
        val starts = labels.map {
            compose.onNodeWithText(inventoryValueBeside(it, standing), useUnmergedTree = true)
                .getUnclippedBoundsInRoot().left
        }.toSet()
        assertTrue(
            "the four figures start at ${starts.size} different x positions $situation: $starts",
            starts.size == 1,
        )
    }

    /**
     * Every text node in the block, with the layout Compose really produced.
     *
     * The unmerged tree, because a merged node reports its descendants' strings
     * concatenated and one layout for the lot - which is a different question
     * from "was this sentence cut".
     */
    private fun everyTextLayout(): List<Pair<String, TextLayoutResult>> {
        val out = mutableListOf<Pair<String, TextLayoutResult>>()
        fun walk(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            if (text != null && node.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) {
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                results.firstOrNull()?.let { out.add(text to it) }
            }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        check(out.isNotEmpty()) { "the block drew no text at all" }
        return out
    }

    private fun inventoryValueBeside(label: String, standing: RecordStanding): String =
        when (label) {
            "On the sensor" -> inventoryValue(standing.inventory.sensor)
            "On this device" -> inventoryValue(standing.inventory.here)
            "Not here yet" -> inventoryValue(standing.inventory.gap)
            else -> goneForGoodValue(standing.gaps)
        }

    /**
     * The width the tab's content really gets on this screen shape.
     *
     * `min(screen, sheet cap)` less the 20 dp of horizontal padding the scrolling
     * column applies on each side. Taken from the configuration rather than
     * written down per shape, so adding a fifth shape needs nothing here.
     */
    private fun sheetContentWidth(): Dp {
        val screen = org.robolectric.RuntimeEnvironment.getApplication()
            .resources.configuration.screenWidthDp.dp
        val sheetCap = 640.dp
        return minOf(screen, sheetCap) - 40.dp
    }
}

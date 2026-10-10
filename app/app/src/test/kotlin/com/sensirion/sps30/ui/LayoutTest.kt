package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartMode
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.ui.theme.Sps30Theme

/**
 * Shared plumbing for the layout tests - the ones that assert about *where* the
 * app draws something rather than what it says.
 *
 * ## Why these exist at all, and why they are slow
 *
 * Two of the device test's findings were faults nothing in the suite could have
 * caught, because both were purely geometric: a badge drawn over a button, and a
 * sentence cut off at the width of its column. Every assertion the suite could
 * make about either - the button is in the tree, the string is the right string -
 * passed while the screen was wrong. Semantics do not carry occlusion, and a
 * `Text` reports its full string whether or not the glyphs fit.
 *
 * So these tests measure. They run Compose for real on the JVM under Robolectric
 * in `GraphicsMode.NATIVE`, which is the part that matters: in Robolectric's
 * default graphics mode text measures at about one pixel per character, so
 * nothing ever overflows and a truncation test would pass against the bug. With
 * native graphics, fonts and layout are the real ones and a width in `dp` means
 * what it means on the tablet.
 *
 * The price is startup: a native-graphics Compose test costs seconds, not
 * milliseconds, against a standard pack that runs in about six. So they carry
 * `@Category(Slow::class)` and run with
 *
 * ```
 * ./gradlew :app:testDebugUnitTest -Pslow
 * ```
 *
 * That is a deliberate trade and not a way of hiding them: the fault classes they
 * guard are ones the captain met within a minute of opening the app, and an
 * unguarded regression there is worth far more than six seconds.
 */

/**
 * A landscape control column, narrower than the tablet's own.
 *
 * The Galaxy Tab S7+ column where the y-axis hint was cut measures around 560
 * device pixels, which is wider than this. That is deliberate and not sloppiness:
 * the width at which a particular font happens to stop fitting is not the property
 * worth pinning, and a test tuned to one device's metrics passes on that device and
 * nowhere else. These widths are chosen to be *at least as hard* as the real thing,
 * so a fix that survives them survives the tablet.
 */
internal val CONTROL_COLUMN_NARROW = 196.dp

/**
 * The captain's two devices, in both orientations - the four shapes a screen fault
 * has to be checked against, as `@Config(qualifiers = ...)` strings.
 *
 * The Tab S7+ is 2800x1752 and he runs it at density 300, which is where the
 * landscape control column was measured. The phone is an S24, and it is the harder
 * of the two in both directions: in portrait it is 411 dp wide, and in landscape it
 * has 411 dp of *height* to hold a control column the tablet gives 934 to. That
 * asymmetry is the whole of the cursor-controls fault, so a test that means to
 * cover it runs at all four and not at a representative one.
 *
 * Shared from here rather than copied into each test, so the four shapes stay the
 * four shapes.
 */
internal const val TABLET_LANDSCAPE = "w1493dp-h934dp-land"
internal const val TABLET_PORTRAIT = "w934dp-h1493dp-port"
internal const val PHONE_PORTRAIT = "w411dp-h891dp-port"
internal const val PHONE_LANDSCAPE = "w891dp-h411dp-land"

/** The options sheet on a phone in portrait - the narrowest a guarded section gets. */
internal val OPTIONS_SHEET_WIDTH = 360.dp

/** Renders [content] in the app's own theme, clamped to [width] as the real column is. */
internal fun ComposeContentTestRule.setContentAtWidth(
    width: Dp,
    content: @Composable () -> Unit,
) = setContent {
    Sps30Theme(useDarkTheme = true) {
        Box(modifier = Modifier.width(width)) { content() }
    }
}

/**
 * The text layout Compose actually produced for this node.
 *
 * The only route to the truth about truncation. The semantics tree reports a
 * `Text`'s whole string whether or not the glyphs fit, so `onNodeWithText` and
 * `assertTextEquals` both pass happily against a sentence the user can only see
 * two thirds of.
 */
internal fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    check(results.isNotEmpty()) { "node reported no text layout" }
    return results.first()
}

/**
 * The lines of this layout that were truncated with an ellipsis, by index.
 *
 * ## Not `hasVisualOverflow`
 *
 * That was the obvious flag to reach for and it is useless here: under Robolectric
 * it reads `true` for text that fits with room to spare, because
 * `didOverflowWidth` compares the paragraph's own width against a size the layout
 * has already rounded down, and the fractional advance widths real fonts produce
 * make that comparison fire on almost everything. Measured: "autoscale is holding
 * your range" laying out on one 183 px line inside a 196 px constraint reports
 * `hasVisualOverflow = true`.
 *
 * [TextLayoutResult.isLineEllipsized] asks the question actually being asked - did
 * this line get cut and an ellipsis put in - and it answered correctly on both
 * sides of the fix.
 */
internal fun TextLayoutResult.ellipsizedLines(): List<Int> =
    (0 until lineCount).filter { isLineEllipsized(it) }

/**
 * Every node the screen presents whose text is exactly [text] - the count is the
 * assertion wherever this is used.
 *
 * The merged tree, deliberately, and it is the one place in these layout tests that
 * is not read unmerged. [PanelFit] measures a second copy of the panel that it never
 * places, and `clearAndSetSemantics` takes that copy out of the merged tree - which
 * is the tree a screen reader consumes and therefore the one where "how many
 * Crosshair chips does this screen offer" is a real question. The unmerged tree
 * still holds the probe, by construction, and counting there would be counting
 * Compose's scratch paper.
 */
internal fun ComposeContentTestRule.onAllNodesWithTextExactly(text: String): List<SemanticsNode> =
    onAllNodes(hasText(text, substring = false)).fetchSemanticsNodes()

/** True when the two rectangles share any area at all. */
internal fun DpRect.overlaps(other: DpRect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

internal fun DpRect.pretty(): String =
    "[${left.value}..${right.value} x ${top.value}..${bottom.value}]"

/**
 * A plot with enough in it to draw: two series, real time ticks, a date line and
 * a named zone, so the margins carry everything they carry in the app.
 *
 * Shared by the tests that measure the chart rather than compute it, and built
 * here so they all measure the same plot.
 */
internal fun chartRenderFor(
    viewMode: ViewMode,
    inspectedEpoch: Double? = null,
): ChartRender {
    val start = 1000.0
    val end = 2000.0
    val x = DoubleArray(21) { start + it * 50.0 }
    val data = ChartData(
        x = x,
        series = mapOf(
            "pm2_5" to DoubleArray(x.size) { 0.5 + (it % 5) * 0.2 },
            "pm1_0" to DoubleArray(x.size) { 0.4 + (it % 3) * 0.2 },
        ),
        start = start,
        end = end,
        xTicks = doubleArrayOf(1200.0, 1500.0, 1800.0),
        xLabels = listOf("01:25", "01:28", "01:31"),
        xDates = listOf("Aug 4", "", ""),
        timeZoneLabel = "EDT",
        dataYMin = 0.4,
        dataYMax = 1.5,
        spanSeconds = end - start,
        sampleCount = x.size,
        mode = if (viewMode == ViewMode.PIN) ChartMode.PINNED else ChartMode.MOVING,
        viewMode = viewMode,
        windowSeconds = end - start,
        yAuto = true,
        yMin = null,
        yMax = null,
        yFloor = 0.5,
        yCeil = 2.0,
        yStep = 0.5,
        xFrozen = false,
        hasReferencePin = false,
        // A real step, so the tick-density control renders its face - the
        // margins carry everything they carry in the app. 15m, deliberately:
        // no window preset and no averaging chip says "15m", so a finder for
        // a chip's label can never land on the tick control's face instead.
        tickStepSeconds = 900,
    )
    return ChartRender(
        data = data,
        visible = listOf("pm2_5", "pm1_0"),
        inspectedEpoch = inspectedEpoch,
        viewInfo = "window 1000s  ·  ${x.size} pts",
        timeZoneLabel = "EDT",
    )
}

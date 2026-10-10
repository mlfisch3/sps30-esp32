package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.move
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.ui.theme.Sps30Theme
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
 * Shifting the y axis from the plot, and the rule that it takes two fingers.
 *
 * ## What is being defended
 *
 * The value axis can now be dragged up and down the way time already could, but
 * on a stricter rule: **two fingers, always, whatever the crosshair is doing.**
 * Time shifts under one finger when the crosshair is off, because with it off no
 * single finger is spoken for. The y axis makes no such bargain - a finger put on
 * the glass to read a value must never be understood as a request to move the
 * range out from under it, and a rule that varies with a setting is one the hand
 * has to remember the state of. So the one-finger cases below are run in *both*
 * crosshair states, and both must report nothing.
 *
 * The other half is that the two gestures sharing those two fingers do not do
 * each other's work: a drag must not rescale, and a pinch must not slide. Both
 * are decided by [isScaling], the deadzone that was already here to stop a drag
 * zooming time, rather than by a mechanism of its own - so a pinch made by one
 * finger moving and the other staying still, whose centre therefore travels half
 * the distance the finger did, still only scales.
 *
 * ## Why through the real composable
 *
 * The arithmetic has tests of its own ([ChartYAnchorTest] for what a factor and
 * an anchor do to a range, [PinchYAxisTest] for what finger positions make a
 * factor). None of it can tell whether the gesture loop calls any of it, or with
 * which fingers, and that join is where every fault in this area has actually
 * lived. So this drives real pointers at the real chart and watches which
 * callbacks come out.
 *
 * ## Why two sizes
 *
 * The chart's margins are raw canvas pixels, so the plotting rectangle left
 * inside them is a different shape on a phone and on the tablet this is used on -
 * and the finger positions here are worked out from that rectangle. [Phone] and
 * [Tablet] run the identical body over both.
 */
abstract class YAxisShiftGestureTest {

    @get:Rule
    val compose = createComposeRule()

    /** The chart's size in canvas pixels; the subclasses differ only in this. */
    protected abstract val plotWidthPx: Int
    protected abstract val plotHeightPx: Int

    private val yShifts = mutableListOf<Double>()
    private val yScales = mutableListOf<Pair<Double, Double>>()
    private val timeZooms = mutableListOf<Double>()
    private val timePans = mutableListOf<Double>()

    /**
     * @param cursorMovable the crosshair, which is what changes the *time* axis'
     *   one-finger behaviour and must change nothing about the value axis'.
     */
    private fun plot(cursorMovable: Boolean = true, locked: Boolean = false) {
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                // Density 1 under these qualifiers, so a dp here is the canvas
                // pixel the chart's own geometry is written in.
                Box(modifier = Modifier.size(plotWidthPx.dp, plotHeightPx.dp)) {
                    PmChart(
                        render = chartRenderFor(ViewMode.LIVE),
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                        onZoom = { timeZooms += it },
                        onPan = { timePans += it },
                        onZoomY = { factor, anchor -> yScales += factor to anchor },
                        onPanY = { yShifts += it },
                        cursorMovable = cursorMovable,
                        graphLocked = locked,
                    )
                }
            }
        }
    }

    // --- the plotting rectangle, and places to put fingers in it --------------

    /** The rectangle the trace is drawn in, inside the chart's pixel margins. */
    private class PlotBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun plotBox(): PlotBox {
        val node = compose.onNodeWithTag("plot").fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        val box = PlotBox(
            left = ChartGeometry.leftPad,
            top = ChartGeometry.topPad,
            right = w - ChartGeometry.leftPad / 5f,
            bottom = h - ChartGeometry.bottomPad,
        )
        check(box.right - box.left >= 400f && box.bottom - box.top >= 400f) {
            "the plot measured ${w}x$h, leaving too little inside its margins to touch"
        }
        return box
    }

    /**
     * A point in the plot, given as fractions across it and **up** it: (0.5, 0.5)
     * is the middle and (0.5, 0.1) is a tenth of the way up from the x axis, so
     * the numbers here read the way the axis does rather than the way pixels do.
     */
    private fun at(across: Float, up: Float): Offset {
        val box = plotBox()
        return Offset(
            box.left + (box.right - box.left) * across,
            box.bottom - (box.bottom - box.top) * up,
        )
    }

    /** Fingers side by side about [centre] - the posture a hand falls into. */
    private fun level(centre: Offset, gap: Float) =
        Offset(centre.x - gap / 2f, centre.y) to Offset(centre.x + gap / 2f, centre.y)

    /** Fingers one above the other about [centre] - the posture that claims the y axis. */
    private fun stacked(centre: Offset, gap: Float) =
        Offset(centre.x, centre.y - gap / 2f) to Offset(centre.x, centre.y + gap / 2f)

    private fun shift(pair: Pair<Offset, Offset>, by: Offset) =
        (pair.first + by) to (pair.second + by)

    // --- the gestures --------------------------------------------------------

    /** Two fingers moving from one arrangement to another, in eight steps. */
    private fun twoFingers(from: Pair<Offset, Offset>, to: Pair<Offset, Offset>) {
        compose.onNodeWithTag("plot").performTouchInput {
            down(0, from.first)
            down(1, from.second)
            for (i in 1..STEPS) {
                val t = i.toFloat() / STEPS
                updatePointerTo(0, lerp(from.first, to.first, t))
                updatePointerTo(1, lerp(from.second, to.second, t))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
    }

    /** One finger travelling from [from] to [to], in eight steps. */
    private fun oneFinger(from: Offset, to: Offset) {
        compose.onNodeWithTag("plot").performTouchInput {
            down(0, from)
            for (i in 1..STEPS) {
                updatePointerTo(0, lerp(from, to, i.toFloat() / STEPS))
                move()
            }
            up(0)
        }
        compose.waitForIdle()
    }

    private fun lerp(a: Offset, b: Offset, t: Float) =
        Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    // --- two fingers shift the range -----------------------------------------

    /**
     * The gesture that was missing. Two fingers dragged down the plot carry the
     * trace down with them, which means the range moves up - the same sense the
     * axis' own gutter has always had.
     */
    @Test
    fun `two fingers dragged down the plot shift the range`() {
        plot()
        val start = level(at(0.5f, 0.5f), gap = 300f)
        twoFingers(start, shift(start, Offset(0f, 160f)))

        assertTrue("a two-finger drag did not shift the y axis", yShifts.isNotEmpty())
        assertTrue(
            "dragging down must move the range up, so every shift is positive: $yShifts",
            yShifts.all { it > 0.0 },
        )
        assertEquals("the drag rescaled the axis as well", emptyList<Pair<Double, Double>>(), yScales)
    }

    /** And upwards, for the same reason in the other direction. */
    @Test
    fun `two fingers dragged up the plot shift the range the other way`() {
        plot()
        val start = level(at(0.5f, 0.5f), gap = 300f)
        twoFingers(start, shift(start, Offset(0f, -160f)))

        assertTrue("a two-finger drag did not shift the y axis", yShifts.isNotEmpty())
        assertTrue("dragging up must move the range down: $yShifts", yShifts.all { it < 0.0 })
    }

    /**
     * Whichever way the hand happens to be holding them. Fingers stacked one
     * above the other is the arrangement that claims the y axis for scaling, and
     * fingers side by side is the one that claims time - neither has anything to
     * say about a drag, which is the same gesture either way.
     */
    @Test
    fun `stacked fingers shift the range too`() {
        plot()
        val start = stacked(at(0.5f, 0.5f), gap = 300f)
        twoFingers(start, shift(start, Offset(0f, 140f)))

        assertTrue("stacked fingers did not shift the y axis", yShifts.isNotEmpty())
        assertTrue(yShifts.all { it > 0.0 })
    }

    /**
     * But a scroll through time does not take the value axis with it. Two fingers
     * dragged sideways drift down the glass as they travel, and acting on that
     * drift would leave the y axis off autoscale with a hold to release, on a
     * gesture that was only ever about time.
     */
    @Test
    fun `two fingers dragged sideways move time and leave the range alone`() {
        plot()
        val start = level(at(0.25f, 0.5f), gap = 200f)
        // A long sideways drag with the hand sagging as it goes.
        twoFingers(start, shift(start, Offset(400f, 30f)))

        assertTrue("the sideways drag did not move through time", timePans.isNotEmpty())
        assertEquals("a scroll through time shifted the y axis", emptyList<Double>(), yShifts)
    }

    // --- one finger never does, in either crosshair state ---------------------

    /**
     * The promise, with the crosshair on: this drag is placing a crosshair, and
     * placing a crosshair may not move the range out from under it.
     */
    @Test
    fun `one finger does not shift the range while the crosshair is on`() {
        plot(cursorMovable = true)
        oneFinger(at(0.5f, 0.7f), at(0.5f, 0.2f))

        assertEquals("one finger shifted the y axis", emptyList<Double>(), yShifts)
    }

    /**
     * And with the crosshair off, which is the case that has to be stated
     * separately: with it off a single finger *does* pan time, so "nothing
     * happened" would be no evidence at all unless something demonstrably did.
     */
    @Test
    fun `one finger does not shift the range while the crosshair is off`() {
        plot(cursorMovable = false)
        oneFinger(at(0.2f, 0.7f), at(0.8f, 0.2f))

        assertTrue("the one-finger gesture was not live at all", timePans.isNotEmpty())
        assertEquals("one finger shifted the y axis", emptyList<Double>(), yShifts)
    }

    // --- the two gestures do not do each other's work -------------------------

    /**
     * A drag is never perfectly parallel: the gap between the fingers wanders by
     * a pixel or two, and reading that as a pinch would rescale the axis while
     * the user was only moving it. The deadzone is what absorbs it.
     */
    @Test
    fun `a drag with the fingers wobbling apart still does not rescale`() {
        plot()
        val centre = at(0.5f, 0.5f)
        // Down the plot by 160px while the gap opens by 1% - well inside the
        // deadzone, and more wander than a hand actually produces.
        twoFingers(level(centre, gap = 300f), level(centre + Offset(0f, 160f), gap = 303f))

        assertTrue("the drag did not shift the y axis", yShifts.isNotEmpty())
        assertEquals("a wobbling drag rescaled the axis", emptyList<Pair<Double, Double>>(), yScales)
    }

    /**
     * The mirror: a pinch made the way hands actually make them - one finger
     * planted, the other doing the moving - has a centre that travels half the
     * distance the moving finger did. Following that centre would slide the range
     * while the user was only setting its height.
     */
    @Test
    fun `a lopsided pinch scales without sliding the range`() {
        plot()
        val centre = at(0.5f, 0.5f)
        val (top, bottom) = stacked(centre, gap = 400f)
        // The upper finger stays where it is; the lower one comes up to meet it.
        twoFingers(top to bottom, top to Offset(bottom.x, bottom.y - 300f))

        assertTrue("the pinch did not scale the y axis", yScales.isNotEmpty())
        assertEquals("the pinch shifted the range as well", emptyList<Double>(), yShifts)
    }

    // --- where the pinch is made is what it holds ----------------------------

    /**
     * The anchor the gesture reports is where the fingers are, so the model can
     * hold the value under them. Down at the bottom of the plot it is near 0 -
     * which is what makes "keep the bottom, raise the top" reachable at all.
     */
    @Test
    fun `a pinch near the bottom of the plot anchors near the bottom of the range`() {
        plot()
        val low = at(0.5f, 0.08f)
        twoFingers(stacked(low, gap = 120f), stacked(low, gap = 40f))

        assertTrue("the pinch did not scale the y axis", yScales.isNotEmpty())
        val anchors = yScales.map { it.second }
        assertTrue("the anchor was not near the bottom: $anchors", anchors.all { it < 0.2 })
    }

    /** And near the top of the plot it is near 1, so the top is what is held. */
    @Test
    fun `a pinch near the top of the plot anchors near the top of the range`() {
        plot()
        val high = at(0.5f, 0.88f)
        twoFingers(stacked(high, gap = 120f), stacked(high, gap = 40f))

        assertTrue("the pinch did not scale the y axis", yScales.isNotEmpty())
        val anchors = yScales.map { it.second }
        assertTrue("the anchor was not near the top: $anchors", anchors.all { it > 0.8 })
    }

    /**
     * The anchor is settled once and held, so every frame of one pinch scales
     * about the same place. Frames that each chose their own would compose into a
     * scale *and* a shift, and [MonitorSession] coalesces them by multiplying the
     * factors together, which is only sound while the anchor is common.
     */
    @Test
    fun `every frame of one pinch reports the same anchor`() {
        plot()
        val low = at(0.5f, 0.15f)
        val (top, bottom) = stacked(low, gap = 400f)
        // Lopsided again, so the fingers' centre demonstrably moves during it.
        twoFingers(top to bottom, top to Offset(bottom.x, bottom.y - 300f))

        val anchors = yScales.map { it.second }.distinct()
        assertEquals("the pinch changed the point it was scaling about: $anchors", 1, anchors.size)
    }

    // --- and the whole-graph lock takes both away ----------------------------

    /**
     * Lock means nothing on the plot answers. Both of these gestures went where
     * every other plot gesture goes, so they are gated by construction - and this
     * is what says so if one of them is ever moved out.
     */
    @Test
    fun `a locked graph neither shifts nor scales the y axis`() {
        plot(locked = true)
        val start = level(at(0.5f, 0.5f), gap = 300f)
        twoFingers(start, shift(start, Offset(0f, 160f)))
        val low = at(0.5f, 0.1f)
        twoFingers(stacked(low, gap = 400f), stacked(low, gap = 100f))

        assertEquals("a locked graph shifted the y axis", emptyList<Double>(), yShifts)
        assertEquals(
            "a locked graph scaled the y axis",
            emptyList<Pair<Double, Double>>(), yScales,
        )
    }

    // --- what the gesture asks for, put through the real model ----------------

    /**
     * The captain's own case, from the finger to the axis: 8 to 10 on the plot,
     * fingers at the bottom, squeezed together - and the bottom stays at 8 while
     * the top goes up.
     *
     * The factors come out of the real gesture; the range they land on comes out
     * of the real [ChartView]. Neither half is stubbed, because the fault this
     * fixes was in neither half - it was that the gesture had no way to say
     * *where* it was being made.
     */
    @Test
    fun `a squeeze at the bottom of the plot keeps the bottom and raises the top`() {
        plot()
        val low = at(0.5f, 0.05f)
        // The same 4:1 squeeze as before, in a smaller hand: the bottom margin
        // gave most of its depth back to the plot when the x-axis caption was
        // removed, and a 400px opening centred this close to the axis now puts
        // the lower finger past the bottom edge of the canvas, where the
        // injected pointer never reaches the chart and the pinch dissolves.
        twoFingers(stacked(low, gap = 340f), stacked(low, gap = 85f))

        assertTrue("the pinch did not scale the y axis", yScales.isNotEmpty())
        val view = ChartView().apply { setYRange(8.0, 10.0) }
        for ((factor, anchor) in yScales) view.zoomY(factor, null, null, anchor)

        val (lo, hi) = view.resolveYRange(null, null)
        // The fingers were a twentieth of the way up the plot rather than exactly
        // on the x axis, so the value they hold is a twentieth of the way up the
        // range - and that value, not the bound, is what may not move.
        val anchor = yScales.first().second
        assertEquals(
            "the value under the fingers moved",
            8.0 + 2.0 * anchor, lo + (hi - lo) * anchor, 1e-6,
        )
        // Where a pinch about the middle of the view would have taken the bottom
        // to 5 and the top to 13, which is the complaint this answers.
        assertTrue("the bottom slid away: $lo..$hi", lo > 7.5)
        assertTrue("the top did not rise: $lo..$hi", hi > 14.0)
    }
}

/** How many frames a gesture is delivered in - a hand's worth, not one jump. */
private const val STEPS = 8

/**
 * A phone-shaped chart: about what the plot is given in portrait on the S24, in
 * the raw canvas pixels the chart's geometry is written in.
 *
 * The screen qualifier is a big one at density 1 for both of these, so a `dp`
 * above is a canvas pixel and the chart gets the rectangle it is asked for
 * instead of being measured down to whatever fits - see [PinchGestureTest], where
 * exactly that made the fingers land in the axis gutter. The size under test is
 * the composable's, which is what the geometry actually depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2600dp-h1500dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class YAxisShiftOnAPhoneTest : YAxisShiftGestureTest() {
    override val plotWidthPx = 1080
    override val plotHeightPx = 900
}

/** And the tablet the captain actually uses, landscape. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2600dp-h1500dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class YAxisShiftOnATabletTest : YAxisShiftGestureTest() {
    override val plotWidthPx = 2400
    override val plotHeightPx = 1200
}

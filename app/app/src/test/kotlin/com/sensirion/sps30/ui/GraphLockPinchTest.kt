package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.move
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
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
 * The lock has to cover a gesture written after it.
 *
 * The point of putting the gate at the plot's one gesture entry point rather than
 * a flag in each handler was that a gesture added later would be covered without
 * being told about the lock. The vertical pinch that scales the y axis is the
 * first test of that claim: it landed independently, knows nothing of the lock,
 * and is gated only because it went where every other plot gesture goes. If
 * someone moves it out of the `chartGestures` block in `PmChart`, this is what
 * says so - the arbiter's own tests and [PinchGestureTest] would both stay green.
 *
 * The same pinch is run unlocked in the same test, because "nothing happened" is
 * only evidence when the identical gesture demonstrably does something.
 */
@RunWith(RobolectricTestRunner::class)
// A tablet-sized screen at density 1, for the same reason [PinchGestureTest]
// needs one: the chart's margins are raw canvas pixels, and a small surface has
// too little plotting rectangle left inside them to put two fingers in.
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class GraphLockPinchTest {

    @get:Rule
    val compose = createComposeRule()

    private val yZooms = mutableListOf<Double>()
    private val timeZooms = mutableListOf<Double>()
    private val pans = mutableListOf<Double>()
    private val gutterPans = mutableListOf<Double>()

    private fun plot(locked: Boolean): (Boolean) -> Unit {
        var isLocked by mutableStateOf(locked)
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Box(modifier = Modifier.size(width = 1200.dp, height = 760.dp)) {
                    PmChart(
                        render = chartRenderFor(ViewMode.LIVE),
                        modifier = Modifier.fillMaxSize().testTag("plot"),
                        onZoom = { timeZooms += it },
                        onZoomY = { factor, _ -> yZooms += factor },
                        onPan = { pans += it },
                        onPanY = { gutterPans += it },
                        graphLocked = isLocked,
                    )
                }
            }
        }
        return { isLocked = it }
    }

    /** Two fingers moving between the given pairs of positions, in eight steps. */
    private fun pinch(from: Pair<Offset, Offset>, to: Pair<Offset, Offset>) {
        compose.onNodeWithTag("plot").performTouchInput {
            down(0, from.first)
            down(1, from.second)
            for (i in 1..8) {
                val t = i.toFloat() / 8
                updatePointerTo(0, lerp(from.first, to.first, t))
                updatePointerTo(1, lerp(from.second, to.second, t))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
    }

    private fun lerp(a: Offset, b: Offset, t: Float) =
        Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** The middle of the plotting rectangle, inside the axis gutter and the time axis. */
    private fun plotCentre(): Offset {
        val node = compose.onNodeWithTag("plot").fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        check(w >= 600f && h >= 500f) { "the plot measured ${w}x$h, too small to pinch in" }
        return Offset((ChartGeometry.leftPad + w) / 2f, (h - ChartGeometry.bottomPad) / 2f)
    }

    /** Fingers one above the other - the arrangement that claims the y axis. */
    private fun stacked(gap: Float): Pair<Offset, Offset> {
        val c = plotCentre()
        return Offset(c.x, c.y - gap / 2f) to Offset(c.x, c.y + gap / 2f)
    }

    /** Fingers side by side - the arrangement that scales time. */
    private fun level(gap: Float): Pair<Offset, Offset> {
        val c = plotCentre()
        return Offset(c.x - gap / 2f, c.y) to Offset(c.x + gap / 2f, c.y)
    }

    @Test fun aLockedGraphIgnoresTheVerticalPinchThatScalesTheYAxis() {
        val setLocked = plot(locked = true)
        pinch(stacked(400f), stacked(120f))
        assertEquals("a locked plot scaled the y axis: $yZooms", 0, yZooms.size)

        // The same gesture, released: it must genuinely do something, or the
        // assertion above is only evidence that the pinch never worked here.
        compose.runOnUiThread { setLocked(false) }
        compose.waitForIdle()
        pinch(stacked(400f), stacked(120f))
        assertTrue("the unlocked plot ignored the vertical pinch", yZooms.isNotEmpty())
    }

    @Test fun aLockedGraphIgnoresTheLevelPinchThatScalesTime() {
        val setLocked = plot(locked = true)
        pinch(level(400f), level(120f))
        assertEquals("a locked plot zoomed time: $timeZooms", 0, timeZooms.size)
        assertEquals("a locked plot panned: $pans", 0, pans.size)

        compose.runOnUiThread { setLocked(false) }
        compose.waitForIdle()
        pinch(level(400f), level(120f))
        assertTrue("the unlocked plot ignored the level pinch", timeZooms.isNotEmpty())
    }

    /**
     * The y-axis gutter is inside the gate too. It is reached by a different
     * branch of the gesture loop - a lone finger there drags the range with no
     * arbitration at all - so it is the branch most easily left outside a fix.
     */
    @Test fun aLockedGraphIgnoresADragInTheYAxisGutter() {
        val setLocked = plot(locked = true)
        dragGutter()
        assertEquals("a locked gutter drag moved the y range: $gutterPans", 0, gutterPans.size)

        compose.runOnUiThread { setLocked(false) }
        compose.waitForIdle()
        dragGutter()
        assertTrue("the unlocked gutter ignored a drag", gutterPans.isNotEmpty())
    }

    /** A lone finger sliding down the tick-value strip, which drags the range. */
    private fun dragGutter() {
        compose.onNodeWithTag("plot").performTouchInput {
            val x = ChartGeometry.leftPad / 2f
            down(0, Offset(x, 200f))
            for (i in 1..8) {
                updatePointerTo(0, Offset(x, 200f + i * 20f))
                move()
            }
            up(0)
        }
        compose.waitForIdle()
    }
}

package com.sensirion.sps30.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ConnectionDot
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.ui.theme.Sps30Theme
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The connection dot on the glass: one solid colour beside the readout tiles
 * while all is quiet, the full banner while there is something to say, and
 * the old banner's detail one tap away in a popup that opens *below* the dot.
 *
 * The colours are read off the rendered pixels, not from the spec that asked
 * for them - the spec is pinned in :core (`ConnectionDotTest`), and what can
 * rot independently of it is the drawing: a stray border, a reordered
 * modifier chain, a clip. The screen is taken by hand with `view.draw` for
 * the reason `AutoChosenShapeTest` documents: `captureToImage` waits under
 * Robolectric for a draw report that never comes.
 *
 * The placement half is the captain's 2026-08-12 instruction: the dot must
 * not occupy a row of its own - it shares the readout row - and the expanded
 * detail may cover something unimportant below, never the readouts above.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class ConnectionDotUiTest {

    @get:Rule
    val compose = createComposeRule()

    /** A backdrop nothing in the theme paints, so "not the dot" is unambiguous. */
    private val backdropArgb = 0xFF101013L
    private val backdrop = Color(backdropArgb)

    private val endpoint = "192.168.1.226:8730"

    private fun running(mode: ConnectionMode = ConnectionMode.WIFI) = DashboardUiState(
        connection = ConnectionState.Connected(endpoint),
        activeMode = mode,
        daemonState = Wire.STATE_MEASURING,
        sensor = SensorInfo("SPS30", "E3164D2544338487", "2.1"),
        config = AppConfig(connectionMode = mode),
    )

    private fun disconnectedByChoice() = DashboardUiState(
        connection = ConnectionState.Idle,
        activeMode = ConnectionMode.NONE,
        config = AppConfig(connectionMode = ConnectionMode.NONE),
    )

    /** The header and the readout row, as the dashboard stacks them. */
    private fun show(state: DashboardUiState) {
        compose.setContent {
            val viewModel = DashboardViewModel(RuntimeEnvironment.getApplication())
            Sps30Theme(useDarkTheme = true) {
                Box(Modifier.background(backdrop).padding(8.dp)) {
                    Column {
                        StatusHeader(state)
                        ReadoutCells(state, viewModel)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // --- the dot while running, the banner while connecting ------------------

    @Test fun runningCollapsesTheBannerToTheDot() {
        show(running())
        compose.onNodeWithTag(CONNECTION_DOT_TAG).assertExists()
        compose.onNodeWithTag(STATUS_BANNER_TAG).assertDoesNotExist()
        // And the row of words it replaced is genuinely gone, not just untagged.
        compose.onNodeWithText(endpoint, substring = true).assertDoesNotExist()
    }

    /** The captain's rule: the dot may not cost a row. It shares the readout
     *  tiles' own line, in the standalone upper-right spot he asked for. */
    @Test fun theDotSharesTheReadoutRowRatherThanOwningOne() {
        show(running())
        val dot = compose.onNodeWithTag(CONNECTION_DOT_TAG)
            .fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithText("PM2.5").fetchSemanticsNode().boundsInRoot
        val centreY = (dot.top + dot.bottom) / 2f
        assertTrue(
            "the dot (centre y $centreY) does not share the readout row " +
                "(${tile.top}..${tile.bottom}); it was meant to stop costing a row",
            centreY >= tile.top && centreY <= tile.bottom,
        )
        assertTrue(
            "the dot at ${dot.left}..${dot.right} is not at the row's right end " +
                "(tile ends at ${tile.right})",
            dot.left >= tile.right,
        )
    }

    @Test fun connectingIsTheRedDotAndCostsNoRow() {
        show(running().copy(connection = ConnectionState.Connecting(endpoint)))
        compose.onNodeWithTag(CONNECTION_DOT_TAG).assertExists()
        compose.onNodeWithTag(STATUS_BANNER_TAG).assertDoesNotExist()
        assertEquals(argb(ConnectionDot.NOT_CONNECTED), dotCentrePixel())
    }

    @Test fun searchingIsTheRedDotAndNotAFullWidthBar() {
        // The captain's 2026-08-12 finding: "Wi-Fi · searching for the
        // sensor..." drawn as a pale pink bar across the whole screen, pushing
        // everything below it down by a row. There is no bar now, and the
        // sentence is one tap away.
        show(
            running().copy(
                connection = ConnectionState.Searching("sweeping the local network"),
                discovering = true,
            ),
        )
        compose.onNodeWithTag(STATUS_BANNER_TAG).assertDoesNotExist()
        assertEquals(argb(ConnectionDot.NOT_CONNECTED), dotCentrePixel())
        compose.onNodeWithTag(CONNECTION_DOT_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("searching", substring = true).assertExists()
    }

    @Test fun aLostConnectionIsTheRedDotWithTheWordsOneTapAway() {
        // The judgement this replaced: a genuine disconnection is words, never
        // just a small red dot. The words are still there - they are behind the
        // dot rather than across the top of the plot. See [statusIndicator].
        show(running().copy(connection = ConnectionState.Reconnecting("connection lost")))
        compose.onNodeWithTag(STATUS_BANNER_TAG).assertDoesNotExist()
        assertEquals(argb(ConnectionDot.NOT_CONNECTED), dotCentrePixel())
        compose.onNodeWithTag(CONNECTION_DOT_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("reconnecting", substring = true).assertExists()
    }

    // --- the dot's own pixels: three solid colours, no ring ------------------

    @Test fun theWifiDotIsSolidGreen() {
        show(running(ConnectionMode.WIFI))
        assertEquals(argb(ConnectionDot.WIFI), dotCentrePixel())
        assertEquals("no ring: the dot is one colour edge to edge",
            argb(ConnectionDot.WIFI), dotRimPixel())
    }

    @Test fun theBluetoothDotIsSolidBlue() {
        show(running(ConnectionMode.BLUETOOTH))
        assertEquals(argb(ConnectionDot.BLUETOOTH), dotCentrePixel())
        assertEquals(argb(ConnectionDot.BLUETOOTH), dotRimPixel())
    }

    @Test fun theDisconnectedDotIsSolidRed() {
        show(disconnectedByChoice())
        assertEquals(argb(ConnectionDot.NOT_CONNECTED), dotCentrePixel())
        assertEquals(argb(ConnectionDot.NOT_CONNECTED), dotRimPixel())
    }

    @Test fun theWifiDotSaysWhatItIsToTheScreenReader() {
        show(running(ConnectionMode.WIFI))
        compose.onNodeWithContentDescription("Connected over Wi-Fi", substring = true)
            .assertExists()
    }

    @Test fun theBluetoothDotSaysWhatItIsToTheScreenReader() {
        show(running(ConnectionMode.BLUETOOTH))
        compose.onNodeWithContentDescription("Connected over Bluetooth", substring = true)
            .assertExists()
    }

    @Test fun theBareDotSaysWhatItIsToTheScreenReader() {
        show(disconnectedByChoice())
        compose.onNodeWithContentDescription("Not connected", substring = true).assertExists()
    }

    // --- the hidden detail stays reachable -----------------------------------

    @Test fun theOldBannersDetailIsOneTapAway() {
        show(running())
        compose.onNodeWithTag(CONNECTION_DOT_TAG).performClick()
        compose.waitForIdle()
        // Everything the collapsed banner used to keep on screen: transport,
        // address and port, phase, sensor model and firmware.
        compose.onNodeWithText("Wi-Fi · connected $endpoint", substring = true).assertExists()
        compose.onNodeWithText("measuring", substring = true).assertExists()
        compose.onNodeWithText("SPS30 · fw 2.1", substring = true).assertExists()
        // And a tap on the revealed banner puts it away again.
        compose.onNodeWithTag(STATUS_BANNER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CONNECTION_DOT_TAG).assertExists()
        compose.onNodeWithText(endpoint, substring = true).assertDoesNotExist()
    }

    /**
     * The captain's rule, and the one this file asserted only in its own
     * prose: **the particle concentration must never be invisible.**
     *
     * The detail used to hang off the dot, and the dot is centred in the row -
     * so the popup started at the dot's bottom edge and covered the strip of
     * row below it, which is where the tiles draw their `ug/m3` unit lines. It
     * clipped both of them through the middle of the glyphs on the tablet on
     * 2026-08-12 and every test here passed, because semantics do not carry
     * geometry: a clipped `Text` still reports its whole string.
     *
     * Measured in window coordinates on purpose - a popup is its own root, so
     * `boundsInRoot` would compare two different origins and pass whatever the
     * screen looked like.
     */
    @Test fun theDetailNeverCoversTheReadouts() {
        show(running())
        compose.onNodeWithTag(CONNECTION_DOT_TAG).performClick()
        compose.waitForIdle()
        val detailTop = screenTop(compose.onNodeWithTag(CONNECTION_DETAIL_TAG))
        for (label in listOf("PM1.0", "PM2.5")) {
            // The whole tile, not just its label: the unit line is the part
            // that was lost, and it is at the tile's bottom edge.
            val node = compose.onNodeWithText(label).fetchSemanticsNode()
            val tileBottom = rootOnScreen(node)[1] + node.boundsInRoot.bottom
            assertTrue(
                "the connection detail (screen y $detailTop) starts inside the " +
                    "$label tile (bottom $tileBottom) and covers part of the reading",
                detailTop >= tileBottom,
            )
        }
    }

    /**
     * A node's top edge in *screen* pixels.
     *
     * A popup is its own window, so `boundsInRoot` and even `boundsInWindow`
     * are measured from that window's own origin and read 0 for its first
     * child - which compares the popup against the tiles in two different
     * coordinate spaces and passes whatever the screen looks like. The window's
     * placement is the missing term, and it is what `getLocationOnScreen`
     * carries.
     */
    private fun screenTop(node: androidx.compose.ui.test.SemanticsNodeInteraction): Float {
        val semantics = node.fetchSemanticsNode()
        return rootOnScreen(semantics)[1] + semantics.boundsInRoot.top
    }

    private fun rootOnScreen(node: androidx.compose.ui.semantics.SemanticsNode): IntArray {
        val view = (node.root as ViewRootForTest).view
        return IntArray(2).also { view.getLocationOnScreen(it) }
    }

    // --- reading the dot off the glass ---------------------------------------

    private fun argb(colour: Long): Int = colour.toInt()

    /** The centre of the drawn circle. */
    private fun dotCentrePixel(): Int {
        val body = drawnDot()
        return screenshot().getPixel((body.left + body.right) / 2, (body.top + body.bottom) / 2)
    }

    /**
     * Just inside the circle's left edge at mid height: where the ring used to
     * be. Inset past the anti-aliased rim, so a solid dot reads as its fill -
     * and a resurrected ring would be caught red- (or orange-) handed.
     */
    private fun dotRimPixel(): Int {
        val body = drawnDot()
        val inset = (px(4.dp) / 2).coerceAtLeast(3)
        return screenshot().getPixel(body.left + inset, (body.top + body.bottom) / 2)
    }

    /** The bounding box of what the dot actually painted, in screen pixels. */
    private fun drawnDot(): Bounds {
        val node = compose.onNodeWithTag(CONNECTION_DOT_TAG).fetchSemanticsNode()
        val box = node.boundsInRoot
        val screen = screenshot()
        var left = box.right.roundToInt()
        var right = -1
        var top = box.bottom.roundToInt()
        var bottom = -1
        for (y in box.top.roundToInt() until box.bottom.roundToInt()) {
            for (x in box.left.roundToInt() until box.right.roundToInt()) {
                if (screen.getPixel(x, y) == argb(backdropArgb)) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        check(right >= left && bottom >= top) { "the dot painted nothing at all" }
        return Bounds(left, top, right, bottom)
    }

    private fun px(dp: androidx.compose.ui.unit.Dp): Int = (
        dp.value * RuntimeEnvironment.getApplication().resources.displayMetrics.density
        ).roundToInt()

    private fun screenshot(): Bitmap {
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
}

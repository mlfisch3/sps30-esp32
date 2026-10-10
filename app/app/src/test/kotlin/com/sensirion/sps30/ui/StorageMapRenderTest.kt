package com.sensirion.sps30.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.sensirion.sps30.Slow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The seat map as ink: a known occupancy state, rendered, read back pixel by
 * pixel.
 *
 * [StorageMapGridTest] holds the arithmetic that places the cells, which is a
 * claim about data. This is the other half, and it can only be settled in
 * pixels: that a written page paints, an empty one leaves its interior bare,
 * an anchor and a torn page each carry their own mark, and - the one the
 * captain asked for by name - that the write-position ring lands around the
 * head's cell and nowhere else. Four states that all quietly painted the same
 * teal would satisfy every assertion in the pure file.
 *
 * Drawn on black at density 1 so a cell's centre pixel is exactly its fill
 * composited over nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class StorageMapRenderTest {

    private val w = 80
    private val h = 14

    /** Eight pages, one of everything, the head parked on page 4. */
    private val states = listOf(1, 0, 2, 3, 1, 0, 0, 0)
    private val head = 4

    // width 80, target 9, gap 1: eight columns of pitch 10, one row.
    private val grid = mapGrid(states.size, w.toFloat(), targetCellPx = 9f, gapPx = 1f)

    private fun rendered(): Bitmap {
        val image = ImageBitmap(w, h)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(Color.Black)
            drawStorageSection(states, head, grid)
        }
        return image.asAndroidBitmap()
    }

    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    @Test
    fun `each state paints its own mark where its page is`() {
        assertEquals(8, grid.columns)
        val bitmap = rendered()
        val midY = (grid.cellPx / 2f).toInt()
        fun centreOf(page: Int) = bitmap.getPixel(
            (cellOrigin(grid, page).x + grid.cellPx / 2f).toInt(),
            midY,
        )

        // Page 0 holds readings: the solid teal, exactly.
        assertEquals(Color(0xFF3BAFCB).toArgb(), centreOf(0))

        // Page 1 is erased flash: an outline only, so its centre stays bare.
        assertEquals(0xFF000000.toInt(), centreOf(1))

        // Page 2 is a time anchor: the punched-out centre reads near-white
        // over the fill, which no other state produces.
        val anchor = centreOf(2)
        assertTrue(
            "anchor centre should be its dot, got #%08X".format(anchor),
            channel(anchor, 16) > 200 && channel(anchor, 8) > 200 && channel(anchor, 0) > 200,
        )

        // Page 3 is unreadable: the slash crosses the centre in the bad red.
        assertEquals(Color(0xFFB0453C).toArgb(), centreOf(3))
    }

    @Test
    fun `the write position ring lands around the head's cell and nowhere else`() {
        val bitmap = rendered()
        val headColor = Color(0xFFFFB300).toArgb()
        var found = 0
        var minX = w
        var maxX = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (bitmap.getPixel(x, y) == headColor) {
                    found++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        assertTrue("the head ring left no ink at all", found > 0)
        // Page 4's cell spans x 40..49; its ring's stroke reaches about three
        // pixels beyond either edge. Ink outside that is a ring around some
        // other page - the exact failure "the write position lands where it
        // should" exists to catch.
        val origin = cellOrigin(grid, head).x
        assertTrue(
            "head ink at x=$minX, before the head's cell",
            minX >= (origin - 4f).toInt(),
        )
        assertTrue(
            "head ink at x=$maxX, past the head's cell",
            maxX <= (origin + grid.cellPx + 4f).toInt(),
        )
    }
}

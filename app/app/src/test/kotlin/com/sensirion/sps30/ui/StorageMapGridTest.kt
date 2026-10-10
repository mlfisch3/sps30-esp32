package com.sensirion.sps30.ui

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that places four and a half thousand cells, tested as data.
 *
 * The claim of the seat map is that cell N is page N: the captain reads "how
 * far round is the wrap" off the picture, so a grid that put page 1,200 one
 * row off would be precise-looking and wrong. The geometry is pure so it can
 * be held here; the ink over it is [StorageMapRenderTest]'s half.
 */
class StorageMapGridTest {

    @Test
    fun `every page gets a cell and no row is invented`() {
        for (pages in listOf(1, 4, 12, 128, 384, 4480, 4992)) {
            val grid = mapGrid(pages, widthPx = 320f, targetCellPx = 3.5f, gapPx = 1f)
            assertTrue("pages $pages need to fit", grid.columns * grid.rows >= pages)
            assertTrue(
                "pages $pages must not leave an empty last row",
                (grid.rows - 1) * grid.columns < pages,
            )
        }
    }

    @Test
    fun `the grid spans its width exactly`() {
        val width = 320f
        val gap = 1f
        val grid = mapGrid(4480, width, targetCellPx = 3.5f, gapPx = gap)
        // Columns cells and the gaps between them (one gap trails per pitch,
        // minus the final one) come back to the width the layout offered.
        assertEquals(width, grid.columns * grid.pitchPx - gap, 0.01f)
    }

    @Test
    fun `a section smaller than one row gets exactly its pages`() {
        val grid = mapGrid(3, widthPx = 320f, targetCellPx = 3.5f, gapPx = 1f)
        assertEquals(3, grid.columns)
        assertEquals(1, grid.rows)
    }

    @Test
    fun `cells land in reading order`() {
        val grid = mapGrid(100, widthPx = 100f, targetCellPx = 9f, gapPx = 1f)
        assertEquals(10, grid.columns)
        assertEquals(0f, cellOrigin(grid, 0).x, 0.001f)
        assertEquals(0f, cellOrigin(grid, 0).y, 0.001f)
        // One full row on: same column, one pitch down.
        assertEquals(0f, cellOrigin(grid, 10).x, 0.001f)
        assertEquals(grid.pitchPx, cellOrigin(grid, 10).y, 0.001f)
        // The write head of the vectors' wrapped board: page 1200 of 4480.
        val ring = mapGrid(4480, widthPx = 320f, targetCellPx = 3.5f, gapPx = 1f)
        val head = cellOrigin(ring, 1200)
        assertEquals((1200 % ring.columns) * ring.pitchPx, head.x, 0.001f)
        assertEquals((1200 / ring.columns) * ring.pitchPx, head.y, 0.001f)
    }

    @Test
    fun `no two states are told apart by colour alone`() {
        // The gap fills' accessibility rule, applied here: each state's fill
        // must differ from every other in *structure* - solid, outline, dot,
        // slash - so the map still reads where colour does not.
        val shapes = CELL_FILLS.mapValues { (_, fill) ->
            listOf(fill.fill != null, fill.outline != null, fill.dot != null, fill.slash != null)
        }
        assertEquals(4, shapes.size)
        assertEquals(
            "two states share a structural shape and differ only by colour",
            shapes.size,
            shapes.values.toSet().size,
        )
    }

    @Test
    fun `the legend and the fills cover the same four states`() {
        assertEquals(CELL_FILLS.keys, CELL_LEGEND.map { it.first }.toSet())
        assertEquals(
            setOf(
                Wire.PAGE_STATE_EMPTY, Wire.PAGE_STATE_DATA,
                Wire.PAGE_STATE_ANCHOR, Wire.PAGE_STATE_BAD,
            ),
            CELL_FILLS.keys,
        )
    }

    private fun section(pages: Int, head: Int, wraps: Int) = ServerMessage.MapSection(
        id = Wire.MAP_SECTION_RING,
        start = 0,
        pages = pages,
        head = head,
        wraps = wraps,
        states = List(pages) { Wire.PAGE_STATE_DATA },
    )

    @Test
    fun `progress speaks in passes and how far round the head is`() {
        assertEquals(
            "first pass - write head at page 5 of 12",
            sectionProgress(section(pages = 12, head = 5, wraps = 0)),
        )
        // The vectors' wrapped board: pass eight, 1200 of 4480 pages round.
        assertEquals(
            "pass 8 - overwriting, 26% of the way round",
            sectionProgress(section(pages = 4480, head = 1200, wraps = 7)),
        )
    }

    @Test
    fun `a section id this build does not know is shown, not dropped`() {
        assertEquals("Readings ring", sectionLabel(Wire.MAP_SECTION_RING))
        assertEquals("Alert summaries (reserved)", sectionLabel(Wire.MAP_SECTION_ALERT_HEADERS))
        assertEquals("Alert samples (reserved)", sectionLabel(Wire.MAP_SECTION_ALERT_BODIES))
        // The tiered generations arrive as new ids one day; they get their id
        // as a label rather than silence.
        assertEquals("g1", sectionLabel("g1"))
    }
}

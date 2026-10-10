package com.sensirion.sps30.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage occupancy map, held to what `wire.py` says it is.
 *
 * The long line is the one `firmware/test/gen_wire_vectors.py` generates from
 * `wire.py`'s own `storage_map_message` and `firmware/test/test_wire.c` asserts
 * the C emits byte for byte (`PY_STORAGE_MAP_LINE`). Carrying the same bytes on
 * this side is what makes it three implementations of one protocol rather than
 * two that happen to agree.
 *
 * The strictness half matters as much as the happy half here: every cell is a
 * claim about one specific flash page, so a frame whose cells cannot be placed
 * must drop whole rather than render shifted. That is the one place this
 * parser refuses where the rest of it repairs.
 */
class StorageMapMessagesTest {

    /** `PY_STORAGE_MAP_LINE`: a board a few hours into its first pass. */
    private val youngBoard =
        """{"type":"storage_map","page_bytes":512,"pages":20,"sections":[""" +
            """{"id":"ring","start":0,"pages":12,"head":5,"wraps":0,"cells":"ZQMA"},""" +
            """{"id":"alert_headers","start":12,"pages":4,"head":1,"wraps":0,"cells":"AQ=="},""" +
            """{"id":"alert_bodies","start":16,"pages":4,"head":0,"wraps":0,"cells":"AA=="}]}"""

    @Test
    fun `the command is the name wire py gives it`() {
        val line = Commands.storageMap()
        assertTrue(line, line.contains(""""cmd":"storage_map""""))
        assertTrue(line.endsWith("\n"))
        assertEquals(1, line.count { it == '\n' })
    }

    @Test
    fun `the generated line parses to the board it describes`() {
        val msg = Messages.parse(youngBoard) as ServerMessage.StorageMap
        assertEquals(512, msg.pageBytes)
        assertEquals(20, msg.pages)
        assertEquals(3, msg.sections.size)

        val ring = msg.sections[0]
        assertEquals(Wire.MAP_SECTION_RING, ring.id)
        assertEquals(0, ring.start)
        assertEquals(12, ring.pages)
        assertEquals(5, ring.head)
        assertEquals(0, ring.wraps)
        // The exact states the generator packed: data, data, anchor, data, a
        // torn page, then erased flash to the end of the section.
        assertEquals(
            listOf(1, 1, 2, 1, 3, 0, 0, 0, 0, 0, 0, 0),
            ring.states,
        )

        val headers = msg.sections[1]
        assertEquals(Wire.MAP_SECTION_ALERT_HEADERS, headers.id)
        assertEquals(12, headers.start)
        assertEquals(listOf(1, 0, 0, 0), headers.states)

        val bodies = msg.sections[2]
        assertEquals(Wire.MAP_SECTION_ALERT_BODIES, bodies.id)
        assertEquals(16, bodies.start)
        assertEquals(listOf(0, 0, 0, 0), bodies.states)
    }

    @Test
    fun `the packing's worked example unpacks as documented`() {
        // wire.py's own docstring case: [DATA, EMPTY, ANCHOR, BAD] is
        // 0b11_10_00_01 = 0xE1, which is "4Q==" in base64.
        val line = """{"type":"storage_map","page_bytes":512,"pages":4,"sections":[""" +
            """{"id":"ring","start":0,"pages":4,"head":0,"wraps":0,"cells":"4Q=="}]}"""
        val msg = Messages.parse(line) as ServerMessage.StorageMap
        assertEquals(
            listOf(
                Wire.PAGE_STATE_DATA, Wire.PAGE_STATE_EMPTY,
                Wire.PAGE_STATE_ANCHOR, Wire.PAGE_STATE_BAD,
            ),
            msg.sections.single().states,
        )
    }

    @Test
    fun `a section this build has never heard of is still carried`() {
        // The tiered generations will arrive as new ids. The list is open on
        // purpose, so a future controller's map renders on today's app.
        val line = """{"type":"storage_map","page_bytes":512,"pages":8,"sections":[""" +
            """{"id":"g1","start":4,"pages":4,"head":2,"wraps":9,"cells":"VQ=="}]}"""
        val msg = Messages.parse(line) as ServerMessage.StorageMap
        assertEquals("g1", msg.sections.single().id)
        assertEquals(9, msg.sections.single().wraps)
        assertEquals(listOf(1, 1, 1, 1), msg.sections.single().states)
    }

    @Test
    fun `cells that cannot be placed drop the whole frame`() {
        // Not base64 at all.
        assertNull(
            Messages.parse(
                """{"type":"storage_map","page_bytes":512,"pages":4,"sections":[""" +
                    """{"id":"ring","start":0,"pages":4,"head":0,"wraps":0,"cells":"!!"}]}"""
            )
        )
        // Decodes, but to the wrong number of bytes for the section: one byte
        // short and every later cell would land one page early.
        assertNull(
            Messages.parse(
                """{"type":"storage_map","page_bytes":512,"pages":8,"sections":[""" +
                    """{"id":"ring","start":0,"pages":8,"head":0,"wraps":0,"cells":"AA=="}]}"""
            )
        )
        // A head outside its own section points at a page that does not exist.
        assertNull(
            Messages.parse(
                """{"type":"storage_map","page_bytes":512,"pages":4,"sections":[""" +
                    """{"id":"ring","start":0,"pages":4,"head":4,"wraps":0,"cells":"AA=="}]}"""
            )
        )
        // Cells missing entirely: there is no honest map to draw.
        assertNull(
            Messages.parse(
                """{"type":"storage_map","page_bytes":512,"pages":4,"sections":[""" +
                    """{"id":"ring","start":0,"pages":4,"head":0,"wraps":0}]}"""
            )
        )
    }
}

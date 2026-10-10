package com.sensirion.sps30.ui

import com.sensirion.sps30.data.DeviceDrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the back-up-and-clear row says, and the one rule it must never break.
 *
 * A heading must never claim more than the body. That rule already cost this app
 * once - a transfer that landed every reading was headed "The sensor did not take
 * it" - and it costs more here than anywhere else: "the sensor is clear" over a
 * body saying eleven blocks were kept back would be telling someone their data is
 * safe when it is not, about the one control that empties the sensor.
 */
class BackupAndClearTest {

    private fun status(
        phase: DeviceDrain.Phase,
        blocksLeft: Int = 0,
        blocksAtStart: Int = 0,
        blocksFreed: Int = 0,
        readings: Int = 0,
        segments: Int = 0,
        rows: Int = 0,
        reason: String = "",
    ) = DeviceDrain.Status(
        phase = phase,
        reason = reason,
        blocksLeft = blocksLeft,
        blocksAtStart = blocksAtStart,
        blocksFreed = blocksFreed,
        readingsCopied = readings,
        segmentsCopied = segments,
        rowsWritten = rows,
    )

    @Test
    fun `nothing is claimed before anything has been removed`() {
        val starting = status(DeviceDrain.Phase.STARTING)
        assertEquals("Asking the sensor what it holds…", clearHeadline(starting))
        assertTrue(clearDetail(starting).contains("Nothing has been removed"))
    }

    @Test
    fun `while it runs it says what is here and what is still on the sensor`() {
        val running = status(
            DeviceDrain.Phase.COPYING,
            blocksLeft = 461, blocksAtStart = 560, blocksFreed = 99, rows = 32_472,
        )
        assertEquals("Copying the sensor to this device and clearing it", clearHeadline(running))
        val detail = clearDetail(running)
        assertTrue(detail, detail.contains("32,472 rows written here"))
        assertTrue(detail, detail.contains("461 block(s) left on the sensor"))
    }

    @Test
    fun `preserved alert segments are named as soon as any have been copied`() {
        val without = status(DeviceDrain.Phase.COPYING, rows = 10)
        assertFalse(clearDetail(without).contains("preserved"))

        val with = status(DeviceDrain.Phase.COPYING, rows = 10, segments = 6)
        assertTrue(clearDetail(with).contains("6 preserved alert segment(s)"))
    }

    /**
     * The finished line says both halves: what came off, and what did not.
     *
     * The readings that arrived during the copy are still on the sensor, on
     * purpose, and a heading that said "the sensor is clear" with nothing under
     * it would have someone believe the board is empty when it is recording into
     * the block this operation deliberately kept.
     */
    @Test
    fun `a finished run says the sensor is clear and that it is still recording`() {
        val done = status(
            DeviceDrain.Phase.DONE, blocksFreed = 559, readings = 183_204, segments = 41,
        )
        val headline = clearHeadline(done)
        assertTrue(headline, headline.contains("183,204"))
        assertTrue(headline, headline.contains("the sensor is clear"))
        val detail = clearDetail(done)
        assertTrue(detail, detail.contains("559 block(s) freed"))
        assertTrue(detail, detail.contains("41 preserved alert segment(s) included"))
        assertTrue(detail, detail.contains("arrived during the copy are still on the sensor"))
    }

    /**
     * A run that stopped says where the readings are, both sets of them.
     *
     * Not a failure to explain away: the blocks it copied are here and are gone
     * from the sensor, and the ones it did not are still on the sensor. Someone
     * reading this needs to know both, and needs to know that running it again
     * is safe.
     */
    @Test
    fun `a run that stopped never reads as a finished one`() {
        for (phase in listOf(DeviceDrain.Phase.CANCELLED, DeviceDrain.Phase.FAILED)) {
            val stopped = status(phase, blocksFreed = 12, rows = 3_940)
            val headline = clearHeadline(stopped)
            assertTrue(headline, headline.startsWith("Stopped after 12 block(s)"))
            assertFalse(headline, headline.contains("clear"))
            val detail = clearDetail(stopped)
            assertTrue(detail, detail.contains("3,940 rows are on this device"))
            assertTrue(detail, detail.contains("no longer on the sensor"))
            assertTrue(detail, detail.contains("untouched"))
            assertTrue(detail, detail.contains("picks up where it left off"))
        }
    }

    @Test
    fun `an idle row says nothing at all`() {
        assertEquals("", clearHeadline(DeviceDrain.Status()))
        assertEquals("", clearDetail(DeviceDrain.Status()))
    }

    /**
     * The bar has no denominator until the sensor has given it one, and never
     * goes backwards after that.
     *
     * The sensor keeps recording throughout, so what is left can genuinely grow -
     * and a bar that retreated would be telling the truth in the least useful way
     * available.
     */
    @Test
    fun `progress is null until the sensor has said how much there is`() {
        assertNull(DeviceDrain.Status(phase = DeviceDrain.Phase.STARTING).fraction)

        val started = status(DeviceDrain.Phase.COPYING, blocksLeft = 10, blocksAtStart = 10)
        assertEquals(0f, started.fraction!!, 1e-6f)

        val halfway = status(DeviceDrain.Phase.COPYING, blocksLeft = 5, blocksAtStart = 10)
        assertEquals(0.5f, halfway.fraction!!, 1e-6f)

        // Never past the end, and never behind the start, whatever the sensor
        // says about a ring it is still writing into.
        val overrun = status(DeviceDrain.Phase.COPYING, blocksLeft = 12, blocksAtStart = 10)
        assertEquals(0f, overrun.fraction!!, 1e-6f)
    }

    @Test
    fun `only a run in flight counts as running`() {
        for (phase in listOf(
            DeviceDrain.Phase.STARTING, DeviceDrain.Phase.COPYING, DeviceDrain.Phase.CONFIRMING,
        )) {
            assertTrue(phase.name, status(phase).running)
        }
        for (phase in listOf(
            DeviceDrain.Phase.IDLE, DeviceDrain.Phase.DONE,
            DeviceDrain.Phase.CANCELLED, DeviceDrain.Phase.FAILED,
        )) {
            assertFalse(phase.name, status(phase).running)
        }
    }
}

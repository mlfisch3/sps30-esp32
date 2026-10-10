package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry of the excursion shading: where the region between a series and
 * its alert threshold begins and ends.
 *
 * These are the boundary cases, which are the part worth testing: a crossing is
 * *interpolated* so the region comes to a point where the curve meets the line
 * rather than starting blockily at the first sample above it; an excursion cut
 * off by the edge of the view fills to the edge instead of vanishing; and a gap
 * in the record splits the region the same way it lifts the trace's pen. The
 * fill itself is one translucent path per excursion - the drawing has nothing
 * left to decide once these come out right.
 */
class AlertFillTest {

    private fun single(regions: List<AlertExcursion>): AlertExcursion {
        assertEquals("expected exactly one excursion", 1, regions.size)
        return regions[0]
    }

    @Test
    fun `one excursion inside the view enters and leaves through the line`() {
        // Rises through 10 between x=100 and x=200, falls back through it
        // between x=300 and x=400.
        val x = doubleArrayOf(100.0, 200.0, 300.0, 400.0)
        val v = doubleArrayOf(5.0, 15.0, 15.0, 5.0)
        val e = single(alertExcursions(x, v, threshold = 10.0))
        // First and last points sit exactly on the threshold, at the
        // interpolated crossings; the samples above lie between them.
        assertEquals(150.0, e.xs.first(), 1e-9)
        assertEquals(10.0, e.ys.first(), 1e-9)
        assertEquals(350.0, e.xs.last(), 1e-9)
        assertEquals(10.0, e.ys.last(), 1e-9)
        assertEquals(listOf(150.0, 200.0, 300.0, 350.0), e.xs.toList())
    }

    @Test
    fun `a sharp spike comes out as the tall thin triangle it is`() {
        // One sample over the line, both neighbours under it: crossing in,
        // peak, crossing out - three points, a triangle onto the threshold.
        val x = doubleArrayOf(0.0, 10.0, 20.0)
        val v = doubleArrayOf(0.0, 20.0, 0.0)
        val e = single(alertExcursions(x, v, threshold = 10.0))
        assertEquals(listOf(5.0, 10.0, 15.0), e.xs.toList())
        assertEquals(listOf(10.0, 20.0, 10.0), e.ys.toList())
    }

    @Test
    fun `two separate excursions each get their own region`() {
        val x = DoubleArray(7) { it * 10.0 }
        val v = doubleArrayOf(0.0, 20.0, 0.0, 0.0, 20.0, 20.0, 0.0)
        val regions = alertExcursions(x, v, threshold = 10.0)
        assertEquals(2, regions.size)
        // Each begins and ends on the line, and they do not touch.
        for (e in regions) {
            assertEquals(10.0, e.ys.first(), 1e-9)
            assertEquals(10.0, e.ys.last(), 1e-9)
        }
        assertTrue(
            "the second region starts before the first one ends",
            regions[0].xs.last() < regions[1].xs.first(),
        )
    }

    @Test
    fun `an excursion already in progress at the left edge fills from the edge`() {
        // The view opens with the series above the line: no crossing exists in
        // view, so the region starts at the first sample, not at an invented
        // crossing and not nowhere.
        val x = doubleArrayOf(100.0, 200.0, 300.0)
        val v = doubleArrayOf(15.0, 15.0, 5.0)
        val e = single(alertExcursions(x, v, threshold = 10.0))
        assertEquals(100.0, e.xs.first(), 1e-9)
        assertEquals(15.0, e.ys.first(), 1e-9)
        // It still leaves through the line on the right.
        assertEquals(250.0, e.xs.last(), 1e-9)
        assertEquals(10.0, e.ys.last(), 1e-9)
    }

    @Test
    fun `an excursion still in progress at the right edge fills to the edge`() {
        val x = doubleArrayOf(100.0, 200.0, 300.0)
        val v = doubleArrayOf(5.0, 15.0, 15.0)
        val e = single(alertExcursions(x, v, threshold = 10.0))
        assertEquals(150.0, e.xs.first(), 1e-9)
        assertEquals(10.0, e.ys.first(), 1e-9)
        assertEquals(300.0, e.xs.last(), 1e-9)
        assertEquals(15.0, e.ys.last(), 1e-9)
    }

    @Test
    fun `a view entirely above the line is one region edge to edge`() {
        val x = doubleArrayOf(0.0, 10.0, 20.0)
        val v = doubleArrayOf(15.0, 12.0, 18.0)
        val e = single(alertExcursions(x, v, threshold = 10.0))
        assertEquals(0.0, e.xs.first(), 1e-9)
        assertEquals(20.0, e.xs.last(), 1e-9)
        assertEquals(3, e.xs.size)
    }

    /**
     * The crossing is where the segment meets the line, not where the nearest
     * sample is: on an uneven rise the crossing lands proportionally between
     * the two samples, which is what stops the region looking blocky when the
     * samples are far apart on screen.
     */
    @Test
    fun `the crossing is interpolated along the segment`() {
        val x = doubleArrayOf(0.0, 100.0)
        val v = doubleArrayOf(2.0, 42.0)
        val e = single(alertExcursions(x, v, threshold = 12.0))
        // 12 is a quarter of the way from 2 to 42.
        assertEquals(25.0, e.xs.first(), 1e-9)
        assertEquals(12.0, e.ys.first(), 1e-9)
    }

    /** "Above" includes the line itself, matching the alert engine's at-or-above. */
    @Test
    fun `a sample exactly on the line is inside the region`() {
        val x = doubleArrayOf(0.0, 10.0, 20.0)
        val v = doubleArrayOf(5.0, 10.0, 5.0)
        val e = single(alertExcursions(x, v, threshold = 10.0))
        // The sample on the line is the whole excursion; both crossings land on
        // it, so the region has no width and the fill will draw no area - the
        // trace touching the line is not an excursion over it.
        assertTrue(e.xs.all { it == 10.0 })
        assertTrue(e.ys.all { it == 10.0 })
    }

    /**
     * Both series over their own lines at once: each fills against its own
     * threshold, independently. The per-series levels themselves are
     * `ChartRenderTest`'s subject; here the same instant must yield a region
     * from each series, each bounded by that series' line.
     */
    @Test
    fun `two series above at once each fill against their own threshold`() {
        val x = doubleArrayOf(0.0, 10.0, 20.0)
        val pm25 = doubleArrayOf(10.0, 30.0, 10.0)
        val pm10 = doubleArrayOf(20.0, 60.0, 20.0)
        val e25 = single(alertExcursions(x, pm25, threshold = 20.0))
        val e10 = single(alertExcursions(x, pm10, threshold = 40.0))
        assertEquals(20.0, e25.ys.first(), 1e-9)
        assertEquals(40.0, e10.ys.first(), 1e-9)
        // Same instants, different geometry: each region is that series' own.
        assertEquals(5.0, e25.xs.first(), 1e-9)
        assertEquals(5.0, e10.xs.first(), 1e-9)
        assertEquals(30.0, e25.ys[1], 1e-9)
        assertEquals(60.0, e10.ys[1], 1e-9)
    }

    /**
     * A gap in the record splits the region exactly as it lifts the trace's
     * pen: nothing was measured inside the hole, so no fill may span it, and
     * no crossing may be interpolated across it.
     */
    @Test
    fun `a record gap splits the region and is never interpolated across`() {
        // Above the line either side of a 1000-second hole; gapSeconds = 30.
        val x = doubleArrayOf(0.0, 10.0, 1010.0, 1020.0)
        val v = doubleArrayOf(15.0, 15.0, 15.0, 15.0)
        val regions = alertExcursions(x, v, threshold = 10.0, gapSeconds = 30.0)
        assertEquals(2, regions.size)
        assertEquals(10.0, regions[0].xs.last(), 1e-9)
        assertEquals(1010.0, regions[1].xs.first(), 1e-9)
        // The run ends at the last real sample before the hole, on the curve -
        // not at the threshold, because the exit crossing never happened.
        assertEquals(15.0, regions[0].ys.last(), 1e-9)
        assertEquals(15.0, regions[1].ys.first(), 1e-9)
    }

    /**
     * At wide windows a drawn point is a bucket's mean, and a bucket whose raw
     * seconds straddled the line may average out below it. The fill takes the
     * drawn value's word: it bounds the curve on screen, so a drawn point
     * below the line contributes nothing, whatever the raw record did inside
     * its bucket - the fill and the curve it hangs from may not disagree.
     */
    @Test
    fun `an aggregated point below the line is below it, full stop`() {
        val x = doubleArrayOf(0.0, 10.0, 20.0)
        val v = doubleArrayOf(9.9, 9.9, 9.9)
        assertTrue(alertExcursions(x, v, threshold = 10.0).isEmpty())
    }
}

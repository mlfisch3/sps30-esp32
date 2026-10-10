package com.sensirion.sps30.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the selection's statistics text lands: never over the selected
 * stretch, and clear of the drawn curve wherever a clear corner exists.
 *
 * The card this idiom replaced was rejected for covering the plot, so the
 * placement is the promise worth pinning. [regionReadoutRect] is pure and
 * works in plot pixels, so the promise is held here at both orientations'
 * plot shapes - the tall narrow portrait plot and the wide short landscape
 * one - without a renderer, against synthetic curves whose tops are known
 * exactly.
 *
 * The block's measurements are a six-line readout's: the width of the longest
 * line [regionPlotLines] produces at caption type (which is why that function
 * keeps its lines narrow - see `everyLineStaysNarrowEnoughForAPhoneCorner`),
 * and the readout's line pitch times its line count.
 */
class RegionReadoutPlacementTest {

    /** A portrait-shaped plot on the phone, in canvas pixels. */
    private val portrait = Plot(left = 134f, right = 1050f, top = 20f, bottom = 1400f)

    /** A landscape-shaped plot, wide and short. */
    private val landscape = Plot(left = 134f, right = 2200f, top = 20f, bottom = 700f)

    private data class Plot(val left: Float, val right: Float, val top: Float, val bottom: Float)

    /** The block a six-line readout occupies, roughly, in canvas pixels. */
    private val blockW = 560f
    private val blockH = 38f + 5 * 52f + 54f * 0.62f

    /**
     * A curve of [n] points across the plot: quiet along the bottom, with a
     * peak rising to [peakTopY] between [peakLoFrac] and [peakHiFrac] of the
     * width. `tops[i]` is the pixel y of the highest drawn point there -
     * smaller is higher, as on the canvas.
     */
    private fun curve(
        plot: Plot,
        peakLoFrac: Float,
        peakHiFrac: Float,
        peakTopY: Float,
        n: Int = 200,
    ): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(n) { plot.left + (plot.right - plot.left) * it / (n - 1) }
        val tops = FloatArray(n) {
            val frac = it.toFloat() / (n - 1)
            if (frac in peakLoFrac..peakHiFrac) peakTopY else plot.bottom - 80f
        }
        return xs to tops
    }

    private fun place(
        plot: Plot,
        selLoFrac: Float,
        selHiFrac: Float,
        xs: FloatArray,
        tops: FloatArray,
    ): Rect = regionReadoutRect(
        plotLeft = plot.left, plotRight = plot.right, plotTop = plot.top,
        blockW = blockW, blockH = blockH,
        selectionLeft = plot.left + (plot.right - plot.left) * selLoFrac,
        selectionRight = plot.left + (plot.right - plot.left) * selHiFrac,
        curveXs = xs, curveTopYs = tops,
    )

    private fun assertClearOfCurve(rect: Rect, xs: FloatArray, tops: FloatArray) {
        for (i in xs.indices) {
            assertFalse(
                "a drawn point at x=${xs[i]} rises to y=${tops[i]}, inside the " +
                    "readout block $rect - the statistics text is over the curve",
                xs[i] in rect.left..rect.right && tops[i] <= rect.bottom,
            )
        }
    }

    private fun assertInsideThePlot(rect: Rect, plot: Plot) {
        assertTrue("block $rect leaks left of the plot", rect.left >= plot.left)
        assertTrue("block $rect leaks right of the plot", rect.right <= plot.right)
        assertTrue("block $rect leaks above the plot", rect.top >= plot.top)
    }

    private fun assertClearOfSelection(rect: Rect, plot: Plot, lo: Float, hi: Float) {
        val selLeft = plot.left + (plot.right - plot.left) * lo
        val selRight = plot.left + (plot.right - plot.left) * hi
        assertFalse(
            "the readout block $rect sits over the selected stretch " +
                "$selLeft..$selRight it describes",
            rect.left < selRight && selLeft < rect.right,
        )
    }

    /**
     * The captain's case, both ways up: a peak toward one side is selected,
     * the rest of the record is quiet, and the numbers land in the opposite
     * top corner - over empty plot, not over the selection and not over the
     * curve.
     */
    @Test
    fun theTextAvoidsTheCurveAndTheSelectionInPortrait() {
        val (xs, tops) = curve(portrait, peakLoFrac = 0.75f, peakHiFrac = 0.95f, peakTopY = 120f)
        val rect = place(portrait, selLoFrac = 0.75f, selHiFrac = 0.95f, xs = xs, tops = tops)
        assertInsideThePlot(rect, portrait)
        assertClearOfSelection(rect, portrait, 0.75f, 0.95f)
        assertClearOfCurve(rect, xs, tops)
    }

    @Test
    fun theTextAvoidsTheCurveAndTheSelectionInLandscape() {
        val (xs, tops) = curve(landscape, peakLoFrac = 0.6f, peakHiFrac = 0.9f, peakTopY = 100f)
        val rect = place(landscape, selLoFrac = 0.6f, selHiFrac = 0.9f, xs = xs, tops = tops)
        assertInsideThePlot(rect, landscape)
        assertClearOfSelection(rect, landscape, 0.6f, 0.9f)
        assertClearOfCurve(rect, xs, tops)
    }

    /** A selection on the left pushes the text to the right corner instead. */
    @Test
    fun theTextTakesTheOppositeCornerFromTheSelection() {
        val (xs, tops) = curve(landscape, peakLoFrac = 0.05f, peakHiFrac = 0.3f, peakTopY = 100f)
        val rect = place(landscape, selLoFrac = 0.05f, selHiFrac = 0.3f, xs = xs, tops = tops)
        assertTrue(
            "selection on the left, but the text did not take the right corner: $rect",
            rect.left > (landscape.left + landscape.right) / 2f,
        )
        assertClearOfSelection(rect, landscape, 0.05f, 0.3f)
        assertClearOfCurve(rect, xs, tops)
    }

    /**
     * When the corner away from the selection has curve rising into it, the
     * block flips to the clear one rather than printing over the data.
     */
    @Test
    fun theTextFlipsCornersWhenTheCurveRisesIntoThePreferredOne() {
        // A selected peak just right of centre prefers the left corner - but
        // an earlier excursion fills the top-left, and only the top-right of
        // the plot is clear.
        val n = 200
        val xs = FloatArray(n) { landscape.left + (landscape.right - landscape.left) * it / (n - 1) }
        val tops = FloatArray(n) {
            val frac = it.toFloat() / (n - 1)
            when {
                frac < 0.4f -> 60f                     // high curve under the left corner
                frac in 0.45f..0.62f -> 150f           // the selected peak
                else -> landscape.bottom - 80f
            }
        }
        val rect = place(landscape, selLoFrac = 0.45f, selHiFrac = 0.62f, xs = xs, tops = tops)
        assertTrue(
            "the preferred left corner is under curve, but the text stayed there: $rect",
            rect.left > (landscape.left + landscape.right) / 2f,
        )
        assertClearOfCurve(rect, xs, tops)
    }

    /**
     * With every corner under curve there is nowhere honest to go, and the
     * block keeps the corner away from the selection - the documented
     * fallback, the same graceful failure the crosshair readout has always
     * had - rather than wandering somewhere unpredictable.
     */
    @Test
    fun theTextKeepsTheFarCornerWhenNoCornerIsClear() {
        val n = 200
        val xs = FloatArray(n) { landscape.left + (landscape.right - landscape.left) * it / (n - 1) }
        val tops = FloatArray(n) { 60f }
        val rect = place(landscape, selLoFrac = 0.6f, selHiFrac = 0.9f, xs = xs, tops = tops)
        assertTrue(
            "selection right, so the fallback is the left corner: $rect",
            rect.left < (landscape.left + landscape.right) / 2f,
        )
        assertInsideThePlot(rect, landscape)
    }
}

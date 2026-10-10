package com.sensirion.sps30.ui

import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.TimeSeriesHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The global pin time mark stays a *visible* mark.
 *
 * The captain's report was not only that the window-size buttons abandoned his
 * pin - it was that the mark "just vanished": nothing on the plot showed where
 * the pin was, so a view that moved away from it was indistinguishable from a
 * pin that had been thrown away. The renderer now draws the mark whenever its
 * instant is in view; these tests hold the one rule that decides that
 * ([pinMarkVisible]) and that the mark actually reaches the renderer's input
 * through the workflow that lost it.
 */
class PinMarkTest {

    private val origin = 1_785_800_000.0
    private fun t(seconds: Double) = origin + seconds

    @Test fun drawnExactlyWhenItsInstantIsInsideTheView() {
        assertTrue(pinMarkVisible(150.0, 100.0, 300.0))
        assertTrue("on the left edge is in view", pinMarkVisible(100.0, 100.0, 300.0))
        assertTrue("on the right edge is in view", pinMarkVisible(300.0, 100.0, 300.0))
        assertFalse("before the view", pinMarkVisible(99.0, 100.0, 300.0))
        assertFalse("after the view", pinMarkVisible(301.0, 100.0, 300.0))
        assertFalse("no mark set", pinMarkVisible(null, 100.0, 300.0))
    }

    /** The captain's workflow, followed to the renderer's own input. */
    @Test fun theMarkSurvivesASizeTapAndIsStillDrawn() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        cv.pan(-7_200.0, now)
        val mark = cv.visibleRange(now).first
        cv.pinLongPress(now)
        cv.applyWindow(1_800.0, now, t(0.0), now)   // the tap that used to lose it

        val data = cv.compute(TimeSeriesHistory(), now)
        assertEquals(mark, data.referencePinEpoch!!, 0.0)
        assertTrue("the mark must be drawn on the framed window",
            pinMarkVisible(data.referencePinEpoch, data.start, data.end))
    }

    /** Unpinning leaves the mark set; it is simply off-view until scrolled to. */
    @Test fun unpinKeepsTheMarkForTheRecord() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        cv.pan(-40_000.0, now)
        val mark = cv.visibleRange(now).first
        cv.pinLongPress(now)
        cv.unpin(now)

        val live = cv.compute(TimeSeriesHistory(), now)
        assertEquals("the mark survives unpin", mark, live.referencePinEpoch!!, 0.0)
        assertFalse("a mark far behind a live view is not drawn on it",
            pinMarkVisible(live.referencePinEpoch, live.start, live.end))

        // Scroll back across it and it is drawn again.
        cv.setXRange(mark - 900.0, mark + 900.0)
        val back = cv.compute(TimeSeriesHistory(), now)
        assertTrue(pinMarkVisible(back.referencePinEpoch, back.start, back.end))
    }
}

package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin time mark against the window-size buttons.
 *
 * The captain's own workflow, which is where the fault was reported: pan the
 * instant that matters to the left edge of the plot, press and hold Pin to make
 * it the global pin time mark - and then tap a window size. The mark used to
 * just vanish: the button treated the view as live, jumped it to now, and the
 * framing the mark anchored was gone.
 *
 * The contract these tests hold: while pinned, a window-size button means
 * "that much time, beginning at the pin time mark". The pin survives every
 * size tap until the user unpins, and near the end of the record the framing
 * clamps exactly as [ChartView.snapWindowStart] clamps - the record that
 * exists is preferred over empty invented space, and the mark stays set
 * wherever it is.
 */
class PinWindowTest {

    private val origin = 1_785_800_000.0
    private fun t(seconds: Double) = origin + seconds

    /** A record running t(0)..t(recEnd), handed to applyWindow as its bounds. */
    private val recStart = t(0.0)

    /**
     * The reported fault, step for step: free view, pan the point of interest
     * to the left edge, hold Pin, tap a size. The window must begin at the pin.
     */
    @Test fun theCaptainsWorkflow_aSizeTapBeginsAtThePin() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        cv.pan(-7_200.0, now)                       // the view is held by the hand
        val framed = cv.visibleRange(now)
        val mark = framed.first                     // the instant at the x-axis origin

        cv.pinLongPress(now)                        // press and hold Pin
        assertEquals("the long press must not move the view",
            framed, cv.visibleRange(now))
        assertEquals("the long press pins the held view at the mark",
            ViewMode.PIN, cv.viewMode())
        assertEquals(mark, cv.referencePinTime!!, 0.0)

        cv.applyWindow(1_800.0, now, recStart, now) // tap "30m"
        assertEquals("a size tap while pinned must stay pinned",
            ViewMode.PIN, cv.viewMode())
        assertEquals("the window must begin at the pin time mark",
            mark to mark + 1_800.0, cv.visibleRange(now))
        assertEquals("the pin mark itself must survive the tap",
            mark, cv.referencePinTime!!, 0.0)
        assertEquals(mark, cv.pinStart!!, 0.0)
    }

    /** Every successive size tap measures from the same mark, not from the last view. */
    @Test fun successiveSizeTapsAllHonourTheSamePin() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        cv.pan(-30_000.0, now)
        val mark = cv.visibleRange(now).first
        cv.pinLongPress(now)

        for (seconds in listOf(900.0, 1_800.0, 3_600.0, 900.0)) {
            cv.applyWindow(seconds, now, recStart, now)
            assertEquals("a ${seconds.toInt()} s tap abandoned the pin",
                mark to mark + seconds, cv.visibleRange(now))
            assertEquals(ViewMode.PIN, cv.viewMode())
            assertEquals(mark, cv.referencePinTime!!, 0.0)
        }
    }

    /** And the mark stays carried to the renderer through every one of them. */
    @Test fun theMarkStaysOnTheChartDataThroughSizeTaps() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        cv.pan(-7_200.0, now)
        val mark = cv.visibleRange(now).first
        cv.pinLongPress(now)
        cv.applyWindow(1_800.0, now, recStart, now)
        val data = cv.compute(TimeSeriesHistory(), now)
        assertTrue(data.hasReferencePin)
        assertEquals(mark, data.referencePinEpoch!!, 0.0)
        assertEquals("the mark must be inside the framed window",
            true, data.referencePinEpoch!! in data.start..data.end)
    }

    /**
     * A pin near the record's end: the requested window would run past the
     * newest reading, and the framing clamps the way [ChartView.snapWindowStart]
     * clamps - slid left to hold a full span of record - rather than framing
     * empty space. The pin stays set, inside the view.
     */
    @Test fun aPinNearTheRecordsEndPrefersTheRecordOverEmptySpace() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        val recEnd = now
        cv.pan(-300.0, now)                          // held; left edge 300 s back
        val mark = cv.visibleRange(now).first        // recEnd - 3900... deep enough
        cv.pinLongPress(now)

        // A pin 3900 s from the end with a 30 m window still starts at the pin.
        cv.applyWindow(1_800.0, now, recStart, recEnd)
        assertEquals(mark to mark + 1_800.0, cv.visibleRange(now))

        // Re-pin 5 minutes from the record's end, then ask for 30 minutes:
        // the window slides left to hold a full 30 m of record, and the pin sits
        // inside it, still set, 5 minutes before the end.
        // Re-pin 5 minutes from the record's end the captain's way: leave Pin,
        // frame a view whose origin is the late instant, and hold Pin there.
        val lateMark = recEnd - 300.0
        cv.unpin(now)
        cv.setXRange(lateMark, lateMark + 1_800.0)
        cv.pinLongPress(now)
        assertEquals(lateMark, cv.referencePinTime!!, 0.0)
        assertEquals(ViewMode.PIN, cv.viewMode())

        cv.applyWindow(1_800.0, now, recStart, recEnd)
        val clamped = cv.visibleRange(now)
        assertEquals("the window must slide to hold the record that exists",
            recEnd - 1_800.0 to recEnd, clamped)
        assertTrue("the pin must stay inside the clamped view",
            lateMark in clamped.first..clamped.second)
        assertEquals("the pin mark itself must not move",
            lateMark, cv.referencePinTime!!, 0.0)
        assertEquals(lateMark, cv.pinStart!!, 0.0)
    }

    /** A record shorter than the window: the framing starts where the record does. */
    @Test fun aRecordShorterThanTheWindowStartsWhereTheRecordDoes() {
        val cv = ChartView(windowSeconds = 600.0)
        val now = t(1_200.0)
        val shortStart = t(600.0)                    // 10 minutes of record
        cv.setXRange(t(700.0), t(900.0))
        cv.pinLongPress(now)                         // pin at 700
        cv.applyWindow(1_800.0, now, shortStart, now)
        assertEquals("the window starts where the record does",
            shortStart to shortStart + 1_800.0, cv.visibleRange(now))
        assertEquals(t(700.0), cv.referencePinTime!!, 0.0)
    }

    /**
     * Unpin afterwards goes back to the live view at the span on the glass -
     * the one place Unpin has always led - and the global mark survives it,
     * so a later long press in Pin still snaps back to the same instant.
     */
    @Test fun unpinReturnsToLiveAndKeepsTheGlobalMark() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        cv.pan(-7_200.0, now)
        val mark = cv.visibleRange(now).first
        cv.pinLongPress(now)
        cv.applyWindow(1_800.0, now, recStart, now)

        cv.unpin(now)
        assertEquals(ViewMode.LIVE, cv.viewMode())
        assertEquals("unpin resumes following now at the framed span",
            now - 1_800.0 to now, cv.visibleRange(now))
        assertEquals("unpin must not clear the global mark",
            mark, cv.referencePinTime!!, 0.0)

        // And the mark is still the instant a long press in Pin snaps to.
        cv.pinTap(t(95_000.0))
        cv.pinLongPress(t(95_000.0))
        assertEquals(mark, cv.pinStart!!, 0.0)
    }

    /**
     * A long press in a framed pin view snaps the frame to the reference at
     * the same span. Only moving the origin underneath the frame would leave
     * the glass still - a snap that visibly does nothing reads as a dead
     * button.
     */
    @Test fun aLongPressInAFramedPinSnapsTheFrameToTheReference() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        val refMark = t(50_000.0)
        cv.setXRange(refMark, refMark + 1_000.0)
        cv.pinLongPress(now)                          // the reference mark, at refMark
        cv.unpin(now)

        cv.setXRange(t(70_000.0), t(71_000.0))        // frame somewhere else…
        cv.pinTap(now)                                // …pin there (a tap: reference untouched)
        cv.applyWindow(900.0, now, recStart, now)     // a framed pin view off the reference
        assertEquals(t(70_000.0) to t(70_900.0), cv.visibleRange(now))
        assertEquals(refMark, cv.referencePinTime!!, 0.0)

        cv.pinLongPress(now)                          // snap: the frame moves to the reference
        assertEquals("the frame must move to the reference, keeping its span",
            refMark to refMark + 900.0, cv.visibleRange(now))
        assertEquals(refMark, cv.pinStart!!, 0.0)
    }

    /** The long press keeps a lock the held range carried: the glass is untouched. */
    @Test fun theLongPressKeepsAHeldRangesLock() {
        val cv = ChartView(windowSeconds = 3_600.0)
        val now = t(90_000.0)
        cv.pan(-7_200.0, now)
        cv.setXLocked(true)
        val framed = cv.visibleRange(now)
        cv.pinLongPress(now)
        assertEquals(framed, cv.visibleRange(now))
        assertTrue("the lock must survive a press that only marks", cv.xLocked)
        // A size tap is a fresh framing and releases the lock, as every framing does.
        cv.applyWindow(1_800.0, now, recStart, now)
        assertEquals(false, cv.xLocked)
    }
}

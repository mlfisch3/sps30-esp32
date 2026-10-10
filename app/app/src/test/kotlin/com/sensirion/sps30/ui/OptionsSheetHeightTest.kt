package com.sensirion.sps30.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The menu stops below the particle readouts, so the live readings stay
 * visible while it is open - the captain's 2026-08-12 instruction, removing
 * the one exception to "the particle concentration must never be invisible".
 *
 * The numbers, so the trade is on the record: on the phone in portrait
 * (891 dp window) the readouts' bottom sits about 88 dp down, against the
 * 48 dp scrim strip the sheet always left - so the menu gives up ~40 dp.
 * The tallest tab wants 1490 dp of body and already scrolls inside the fixed
 * frame on every real screen, so nothing new starts scrolling; the tall tabs
 * scroll ~40 dp further. On the smallest supported window (640 dp) the same
 * arithmetic holds: 592 dp of sheet becomes ~552 dp, and every tab scrolled
 * there already.
 */
class OptionsSheetHeightTest {

    @Test
    fun `the sheet stops below the measured readouts`() {
        // Phone portrait: 891 dp window, readouts end 88 dp down.
        assertEquals(803.dp, optionsSheetHeightFor(window = 891.dp, keepClearTop = 88.dp))
    }

    @Test
    fun `the scrim strip is the floor when the readouts sit above it`() {
        // A caller that measures nothing (or a screen whose readouts end inside
        // the strip) still leaves the tap-outside exit its 48 dp target.
        assertEquals(843.dp, optionsSheetHeightFor(window = 891.dp, keepClearTop = 0.dp))
        assertEquals(843.dp, optionsSheetHeightFor(window = 891.dp, keepClearTop = SCRIM_STRIP))
    }

    @Test
    fun `a tall window is still capped where the content stops needing it`() {
        // Nothing above 1506 dp of sheet is ever useful - past it the menu only
        // adds a band of nothing under its longest tab.
        assertEquals(1506.dp, optionsSheetHeightFor(window = 2000.dp, keepClearTop = 88.dp))
    }

    @Test
    fun `a pathological measurement can never halve the menu away`() {
        // The floor at half the window guards the arithmetic, not a real screen.
        assertEquals(320.dp, optionsSheetHeightFor(window = 640.dp, keepClearTop = 500.dp))
    }

    @Test
    fun `the smallest supported window keeps a usable menu`() {
        // 360x640: the readouts end ~88 dp down there too, and the menu keeps
        // 552 dp - every tab scrolled inside the frame on this window already,
        // so the cap changes how far, never whether.
        assertEquals(552.dp, optionsSheetHeightFor(window = 640.dp, keepClearTop = 88.dp))
    }
}

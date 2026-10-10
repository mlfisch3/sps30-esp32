package com.sensirion.sps30.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth search has to end.
 *
 * Choosing Bluetooth with no controller in range used to start a scan/backoff loop
 * with no exit in it: the radio kept waking for as long as the app was open, which
 * on a tablet in a bag is a battery cost paid for nothing. The opposite fault is
 * the worse one though - a controller that is slow to advertise, or carried in and
 * out of range, is a real controller, and an app that gave up after a scan or two
 * would report it absent. So the bound is a minute, and inside the minute the radio
 * rests between looks.
 *
 * The clock is injected, so this spends microseconds proving what takes a minute on
 * a tablet.
 */
class BleSearchTest {

    private var nowMs = 0L
    private val scanWindows = mutableListOf<Long>()
    private val pauses = mutableListOf<Long>()

    /**
     * Run a search on a fake clock.
     *
     * @param foundOnScan which scan answers, or null for a search that finds
     *   nothing at all. A scan that finds something is deliberately made to end
     *   early, because that is what a real one does: it returns on the first
     *   advertisement rather than sitting out its window.
     */
    private fun search(budgetMs: Long?, foundOnScan: Int? = null): String? = runBlocking {
        searchWithin(
            budgetMs = budgetMs,
            scanWindowMs = BleTransport.SCAN_TIMEOUT_MS,
            firstPauseMs = BleTransport.FIRST_PAUSE_MS,
            maxPauseMs = BleTransport.MAX_PAUSE_MS,
            now = { nowMs },
            pause = { pauses += it; nowMs += it },
        ) { window ->
            scanWindows += window
            if (scanWindows.size == foundOnScan) {
                nowMs += 200 // seen almost at once, as an advertiser in range is
                "a sensor"
            } else {
                nowMs += window
                null
            }
        }
    }

    private val budget = BleTransport.SEARCH_BUDGET_MS

    @Test fun anEmptySearchStopsAtTheBound() {
        assertNull("gives up rather than scanning for ever", search(budget))
        assertEquals("and stops exactly at the bound", budget, nowMs)
    }

    @Test fun nothingIsStartedPastTheBound() {
        search(budget)
        // Every scan and every pause fits inside the minute: the last window is
        // clipped to what is left rather than overrunning it.
        assertEquals(budget, scanWindows.sum() + pauses.sum())
        assertTrue(
            "no scan longer than one window",
            scanWindows.all { it <= BleTransport.SCAN_TIMEOUT_MS },
        )
    }

    @Test fun theRadioRestsBetweenLooks() {
        search(budget)
        // The point of the pauses: the minute is not a minute of scanning. Several
        // separate looks, and the radio off for a good part of the time - an
        // advertiser that appears late is caught by a later window.
        assertTrue("several separate looks, not one long one", scanWindows.size >= 4)
        assertTrue("with quiet in between", pauses.size >= 3)
        assertTrue(
            "the radio is off for a real share of the minute: scanned ${scanWindows.sum()} ms",
            scanWindows.sum() < budget * 3 / 5,
        )
    }

    @Test fun thePausesLengthenButAreCapped() {
        search(budget)
        assertEquals(BleTransport.FIRST_PAUSE_MS, pauses.first())
        assertTrue("backs off", pauses[1] > pauses[0])
        assertTrue("but never beyond the cap", pauses.all { it <= BleTransport.MAX_PAUSE_MS })
    }

    @Test fun aSensorFoundInsideTheWindowStillConnects() {
        // The whole risk of a bound: a controller that takes a while to appear must
        // still be found. Third look, most of the way through the minute.
        assertEquals("a sensor", search(budget, foundOnScan = 3))
        assertTrue("and it did not have to wait out the bound", nowMs < budget)
        assertEquals("no further scans once it answered", 3, scanWindows.size)
    }

    @Test fun aSensorOnTheVeryLastLookIsStillFound() {
        // Whichever look is the last one before the bound, answering on it counts.
        search(budget)
        val lastLook = scanWindows.size
        nowMs = 0; scanWindows.clear(); pauses.clear()
        assertNotNull("the last look is a real chance, not a formality", search(budget, lastLook))
    }

    @Test fun aProvenLinkIsNotBounded() {
        // A controller that has answered once is known to exist, so its reconnection
        // keeps trying: being carried out of range for a minute must not end the
        // connection. Null budget is how that is said.
        assertEquals("a sensor", search(budgetMs = null, foundOnScan = 20))
        assertTrue("kept looking well past the bound", nowMs > budget)
    }

    @Test fun theDregsOfTheBudgetAreNotWorthARadioStart() {
        assertNull(search(MIN_USEFUL_SCAN_MS - 1))
        assertTrue("no scan started at all", scanWindows.isEmpty())
    }
}

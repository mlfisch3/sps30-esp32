package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PM4.0 and PM10 are recorded, stored and exported - and never drawn.
 *
 * The two halves of that are tested together on purpose. The defect these guard
 * is not "the wrong series is on the chart" but "narrowing the chart quietly
 * narrowed the record", which is the expensive mistake of the two.
 */
class PlottedSeriesTest {

    @Test fun onlyPm1AndPm25AreEverPlotted() {
        assertEquals(listOf("pm1_0", "pm2_5"), PlottedSeries.FIELDS)
    }

    @Test fun theRecordStillCarriesAllFour() {
        assertEquals(listOf("pm1_0", "pm2_5", "pm4_0", "pm10"), Wire.PM_FIELDS)
    }

    @Test fun keepDropsTheCoarseCutsAndRestoresOrder() {
        assertEquals(
            listOf("pm1_0", "pm2_5"),
            PlottedSeries.keep(listOf("pm10", "pm2_5", "pm4_0", "pm1_0")),
        )
        assertEquals(emptyList<String>(), PlottedSeries.keep(listOf("pm4_0", "pm10")))
    }

    @Test fun aConfigFromAnOlderBuildIsNarrowedOnTheWayIn() {
        val stale = AppConfig(visiblePm = listOf("pm1_0", "pm2_5", "pm4_0", "pm10"))
        assertEquals(listOf("pm1_0", "pm2_5"), stale.normalized().visiblePm)
    }

    /**
     * The upgrade that reached the captain's tablet, and the reason this file did
     * not catch it.
     *
     * [keepDropsTheCoarseCutsAndRestoresOrder] already asserted that `pm4_0` plus
     * `pm10` narrows to nothing, and was right to: that is what `keep` is for. What
     * nobody asked was what the *app* should then do with a config that has been
     * narrowed to nothing. Someone running the older build with PM1.0 and PM2.5
     * switched off and the coarse cuts on had exactly that config in storage, so the
     * upgrade opened on a plot drawing nothing, with both tiles plain and no way to
     * tell why - and it came back on every launch, because it was stored.
     *
     * No user action is involved in reaching it, which is what makes it a trap
     * rather than a choice.
     */
    @Test fun aStoredConfigThatNarrowsToNothingComesBackPlottingBoth() {
        val coarseOnly = AppConfig(visiblePm = listOf("pm4_0", "pm10"))
        assertEquals(
            "an upgrade left the user with a plot that draws nothing",
            listOf("pm1_0", "pm2_5"),
            coarseOnly.normalized().visiblePm,
        )
    }

    /** The same for a config that names nothing at all. */
    @Test fun aStoredConfigWithNothingVisibleComesBackPlottingBoth() {
        assertEquals(
            listOf("pm1_0", "pm2_5"),
            AppConfig(visiblePm = emptyList()).normalized().visiblePm,
        )
    }

    /**
     * And normalising stays a no-op on a config that was already fine, so this
     * cannot quietly overwrite a user's single-series choice.
     */
    @Test fun oneSeriesOnItsOwnIsLeftAlone() {
        val justPm25 = AppConfig(visiblePm = listOf("pm2_5"))
        assertEquals(listOf("pm2_5"), justPm25.normalized().visiblePm)
        assertEquals(justPm25, justPm25.normalized())
    }

    @Test fun aFreshConfigPlotsBothAndNothingElse() {
        assertEquals(listOf("pm1_0", "pm2_5"), AppConfig().visiblePm)
    }

    @Test fun togglingACoarseCutIsANoOp() {
        val config = AppConfig()
        assertEquals(config, config.toggleVisible("pm10"))
        assertEquals(config, config.toggleVisible("pm4_0"))
    }

    @Test fun bothSeriesMayBeSwitchedOff() {
        // The last "off" is honoured: a button that stays lit when tapped is
        // worse than an empty plot the same tap undoes. That this remains true is
        // the reason `normalized` is where the empty set is refused and
        // `toggleVisible` is not - the user may clear the plot, and the tiles they
        // cleared it with are still on screen.
        val none = AppConfig().toggleVisible("pm1_0").toggleVisible("pm2_5")
        assertEquals(emptyList<String>(), none.visiblePm)
        assertEquals(listOf("pm2_5"), none.toggleVisible("pm2_5").visiblePm)
    }

    /** ...and restarting is the other way back, for the user who closed the app on it. */
    @Test fun anEmptyPlotTheUserChoseDoesNotSurviveARestart() {
        val none = AppConfig().toggleVisible("pm1_0").toggleVisible("pm2_5")
        assertEquals(listOf("pm1_0", "pm2_5"), none.normalized().visiblePm)
    }

    /**
     * The y extent is fitted to the drawn curves. PM mass concentrations are
     * cumulative, so a coarse-dust event puts PM10 far above PM2.5 - and an axis
     * that reached up to it would leave both drawn curves crushed at the bottom.
     */
    @Test fun theYExtentIgnoresTheSeriesItDoesNotDraw() {
        val h = TimeSeriesHistory()
        for (s in 0..4) {
            h.add(
                ServerMessage.Reading(
                    "a.csv", "000000$s", "2026-07-22T02:00:0$s-04:00",
                    mapOf("pm1_0" to 1.0, "pm2_5" to 2.0, "pm4_0" to 40.0, "pm10" to 90.0),
                ),
            )
        }
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(1.0, w.yMin!!, 1e-9)
        assertEquals(2.0, w.yMax!!, 1e-9)
        // ...but the coarse cuts are still in the window that was handed back.
        assertEquals(90.0, w.series["pm10"]!!.last(), 1e-9)
    }

    @Test fun theYExtentIgnoresThemWhenBucketedToo() {
        val h = TimeSeriesHistory()
        for (s in 0..9) {
            h.add(
                ServerMessage.Reading(
                    "a.csv", "000000$s", "2026-07-22T02:00:0$s-04:00",
                    mapOf("pm1_0" to 1.0, "pm2_5" to 2.0, "pm4_0" to 40.0, "pm10" to 90.0),
                ),
            )
        }
        val w = h.window(
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            bucketSeconds = 5.0,
        )
        assertTrue(w.epochs.isNotEmpty())
        assertEquals(1.0, w.yMin!!, 1e-9)
        assertEquals(2.0, w.yMax!!, 1e-9)
        assertFalse(w.series["pm4_0"]!!.isEmpty())
    }
}

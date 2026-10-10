package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire

/**
 * The only PM series the app will ever draw: PM1.0 and PM2.5.
 *
 * The SPS30 sizes particles optically below about 1.3um and derives the coarser
 * cuts from that distribution, so indoors pm2_5, pm4_0 and pm10 carry the same
 * number to two decimals nearly all the time. Drawing all four then puts three
 * curves on top of one another: the widest strokes show as a halo around PM2.5,
 * and PM4.0's red - the widest of the three still switched on when both series
 * buttons are off - reads as a live trace nobody asked for. None of that is
 * information; it is the same reading drawn three times.
 *
 * This is about the **plot only**. All four fields are still recorded by the
 * controller, still received, still stored, still exported and still available
 * to alerts. Nothing here narrows the record.
 *
 * Kept as a constant rather than a setting because it is a decision about what
 * the chart means, not a preference: there is no state of the app in which a
 * PM4.0 or PM10 curve should appear.
 */
object PlottedSeries {
    /** In [Wire.PM_FIELDS] order, which is the order everything else uses. */
    val FIELDS: List<String> = Wire.PM_FIELDS.filter { it == "pm1_0" || it == "pm2_5" }

    /** [fields] narrowed to what may be plotted, in canonical order. */
    fun keep(fields: List<String>): List<String> = FIELDS.filter { it in fields }
}

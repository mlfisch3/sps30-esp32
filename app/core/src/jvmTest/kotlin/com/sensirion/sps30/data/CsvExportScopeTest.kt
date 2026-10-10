package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "save as CSV" is allowed to leave out: nothing.
 *
 * The rule the captain set is that a CSV export writes **everything loaded into
 * the plot**, whatever the view happens to be showing - load a fortnight, zoom
 * into ten minutes, and the file still holds the fortnight. That asymmetry with
 * the image export is deliberate: data export is about completeness, image
 * export is about what can be seen.
 *
 * `MonitorSession.buildCsv` writes one row per `history.snapshot()` entry and
 * never consults the chart, so what these tests hold in place is the property
 * that makes that correct - that the snapshot is the whole store, unaffected by
 * the window, and that imported archives are in it beside the live readings.
 */
class CsvExportScopeTest {

    private fun values(v: Double) = DoubleArray(Wire.PM_FIELDS.size) { v }

    private fun named(v: Double) = Wire.PM_FIELDS.associateWith { v }

    /** One point per second, as the sensor delivers them. */
    private fun live(history: TimeSeriesHistory, from: Double, count: Int, csv: String = "live") {
        for (i in 0 until count) {
            history.add(csv, "$i", epochToStamp(from + i), named(1.0 + i % 5), "")
        }
    }

    /**
     * The store keeps ISO-ish timestamps, so build one the parser accepts rather
     * than reaching into the internals.
     */
    private fun epochToStamp(epoch: Double): String =
        java.time.Instant.ofEpochSecond(epoch.toLong())
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private val base = 1_760_000_000.0

    @Test
    fun `the snapshot is every point, not the visible window`() {
        val history = TimeSeriesHistory()
        live(history, base, 3600)               // an hour of readings

        val view = ChartView(windowSeconds = 300.0)   // looking at five minutes
        val chart = view.compute(history, nowEpoch = base + 3599)

        assertTrue(
            "the window should show far less than the hour: ${chart.sampleCount}",
            chart.sampleCount < 400,
        )
        assertEquals(3600, history.snapshot().size)
    }

    @Test
    fun `zooming and panning do not change what would be exported`() {
        val history = TimeSeriesHistory()
        live(history, base, 3600)
        val before = history.snapshot().size

        val view = ChartView(windowSeconds = 300.0)
        view.compute(history, nowEpoch = base + 3599)
        view.setWindow(60.0)                                  // zoom right in
        val zoomed = view.compute(history, nowEpoch = base + 3599)

        assertTrue("zoomed view should be small: ${zoomed.sampleCount}", zoomed.sampleCount < 100)
        assertEquals(before, history.snapshot().size)
    }

    /**
     * Sessions read back off this device land in the same store as the live
     * stream, so "everything loaded into the plot" includes them - which is the
     * case that matters, since that is when there is far more loaded than shown.
     */
    @Test
    fun `imported sessions are exported alongside the live readings`() {
        val history = TimeSeriesHistory()
        live(history, base, 100)
        for (i in 0 until 50) {
            history.addImported("SPS30-old.csv", i, base - 86_400 + i * 60.0, values(7.0))
        }
        history.finishImport()

        val rows = history.snapshot()
        assertEquals(150, rows.size)
        assertEquals(50, rows.count { it.values[Wire.PM_FIELDS[0]] == 7.0 })
        // And in time order, oldest first, as the CSV writes them.
        assertTrue(rows.zipWithNext().all { (a, b) -> a.epoch!! <= b.epoch!! })
    }
}

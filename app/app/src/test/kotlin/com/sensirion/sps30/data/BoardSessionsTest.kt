package com.sensirion.sps30.data

import com.sensirion.sps30.ui.MonitorSession
import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app half of the clockless path, driven with sessions the board recorded.
 *
 * [RelativeTimeTest] holds the rules with readings written for the purpose.
 * This file holds them with two sessions an ESP32 recorded overnight on
 * 2026-08-03 while nothing was connected to tell it the time - 1,000 readings
 * from run 1202 and 266 from run 1196 - and the three equivalences the board
 * actually holds. Every byte of input is in `src/test/resources/board`; see the
 * README there for how each file was obtained and how they were checked against
 * each other. Nothing here is synthetic or time-shifted.
 *
 * The board half is already evidenced on hardware
 * (`docs/evidence/relative-time-hardware-run.txt`): the indices are consecutive,
 * the values are real and the `timestamp` column is empty rather than invented.
 * What was never exercised is this half - the app turning that into times a user
 * sees - and that is what runs here, on the host, with no device.
 *
 * The five claims, in order:
 *
 * 1. Nothing is discarded. All 1,000 and all 266 survive.
 * 2. With an equivalence, the readings get real times at the recorded spacing.
 * 3. Without one, the run is kept and shown as elapsed - not dropped, not given
 *    an invented date, and not placed on the plot at all until something can
 *    place it.
 * 4. The export says which times were derived and which were read from a clock.
 * 5. And the file form carries all of it: archived and read back, every one of
 *    the 1,266 is still there, still where the board put it, and still honest
 *    about what its time was worth.
 *
 * The fifth was a finding when this file was written rather than a claim - the
 * backed-up form had no column for the board's clock, so the app could keep
 * nothing of it and the round trip dropped every reading. `wire.py` now defines
 * that header (`CSV_COLUMNS_RELATIVE`), so it is asserted here instead of
 * reported. The two `.csv` resources are the old export's output and still hold
 * nothing that can place them; the last test keeps that on the record.
 */
class BoardSessionsTest {

    /** The run that was recording when a clock finally reached the board. */
    private val placedBoot = 1202

    /** The run that had already ended by then, and that nothing can place. */
    private val unplaceableBoot = 1196

    /** The instant on the captain's tablet when the plot went unusable. */
    private val TABLET_NOW = 1_785_801_740.0

    /** Its plot width, clamped to the point budget - the 2000 that gave 248 h. */
    private val TABLET_POINT_BUDGET = 2000

    private val placed = "SPS30-R000004B2-00000821.csv"
    private val unplaceable = "SPS30-R000004AC-00000817.csv"

    /** A history wired to a basis, as `MonitorSession` wires it. */
    private class Model {
        val basis = TimeBasis()
        val history = TimeSeriesHistory(retentionSeconds = 1_000_000.0).also { it.basis = basis }
    }

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/board/$name")?.bufferedReader()?.readText()
            ?: throw AssertionError("missing test resource /board/$name")

    /** Header and rows of one session file, cell for cell as it is on disk. */
    private fun file(name: String): Pair<List<String>, List<List<String>>> {
        val lines = resource(name).trim('\n').split('\n')
        return lines.first().split(',') to lines.drop(1).map { it.split(',') }
    }

    /** As the board serves the session over `get_file`. */
    private fun wire(name: String) = file(name.removeSuffix(".csv") + ".wire.csv")

    /** The equivalences the board holds, read by the app's own wire parser. */
    private fun timeMap(): ServerMessage.TimeMap =
        Messages.parse(resource("time_map.json").trim()) as ServerMessage.TimeMap

    /** Both sessions folded in the way a backfill folds them in. */
    private fun loadBoth(model: Model): Pair<Int, Int> {
        val (placedCols, placedRows) = wire(placed)
        val (otherCols, otherRows) = wire(unplaceable)
        return model.history.addRows(placed, placedCols, placedRows, explicit = true) to
            model.history.addRows(unplaceable, otherCols, otherRows, explicit = true)
    }

    private fun samplesOf(model: Model, boot: Int): List<TimeSeriesHistory.Sample> =
        model.history.snapshot().filter { it.relBoot == boot }.sortedBy { it.relMs }

    /** Where a sample sits, failing the test if nothing places it. */
    private val TimeSeriesHistory.Sample.at: Double
        get() = epoch ?: throw AssertionError("reading $index of $csv has no time")

    // --- 1. nothing is discarded ---------------------------------------------

    @Test
    fun `the board's own rows carry its clock, and no invented instant`() {
        for (name in listOf(placed, unplaceable)) {
            val (columns, rows) = wire(name)
            assertTrue("$name has no rel_boot column", Wire.COL_REL_BOOT in columns)
            assertTrue("$name has no rel_ms column", Wire.COL_REL_MS in columns)
            val ts = columns.indexOf("timestamp")
            assertTrue(
                "$name claims an instant the board could not have known",
                rows.all { it[ts].isEmpty() },
            )
        }
    }

    @Test
    fun `every reading the board recorded survives being read in`() {
        val model = Model()
        val (placedRows, otherRows) = loadBoth(model)
        assertEquals("readings of run $placedBoot were dropped", 1000, placedRows)
        assertEquals("readings of run $unplaceableBoot were dropped", 266, otherRows)
        assertEquals(1266, model.history.size)
        assertTrue(model.history.hasRelativePoints())
    }

    @Test
    fun `nor are they trimmed away for having no time yet`() {
        // The store the app really runs keeps two days. Retention is an age, and
        // an unplaceable run has none, so a pass that reasoned on its epoch would
        // delete it the moment a placed reading arrived - deleting it *because*
        // it had no time, which is the one reason to keep it.
        val basis = TimeBasis()
        val history = TimeSeriesHistory(retentionSeconds = 172_800.0).also { it.basis = basis }
        val (columns, rows) = wire(unplaceable)
        history.addRows(unplaceable, columns, rows, explicit = true)
        val (placedCols, placedRows) = wire(placed)
        history.addRows(placed, placedCols, placedRows, explicit = true)
        basis.accept(timeMap())
        history.retime()
        assertEquals("a two-day retention deleted the run it could not date", 1266, history.size)
    }

    @Test
    fun `the indices are the board's, unbroken`() {
        val model = Model()
        loadBoth(model)
        for ((boot, count) in listOf(placedBoot to 1000, unplaceableBoot to 266)) {
            val indices = samplesOf(model, boot).map { it.index }
            assertEquals(count, indices.size)
            assertEquals(listOf(1, count), listOf(indices.first(), indices.last()))
            assertEquals("run $boot has a hole in it", count, indices.toSet().size)
        }
    }

    // --- 2. an equivalence gives them real times -----------------------------

    @Test
    fun `the equivalences place run 1202 where the board says it happened`() {
        val model = Model()
        loadBoth(model)
        val map = timeMap()
        assertEquals(3, map.anchors.size)
        assertTrue(
            "an anchor exists for a run it cannot belong to",
            map.anchors.all { it.boot == placedBoot },
        )
        assertTrue(model.basis.accept(map))
        assertEquals("re-placing missed readings", 1000, model.history.retime())

        val samples = samplesOf(model, placedBoot)
        assertEquals(1000, samples.size)
        // The board's clock read 2081 ms at the first reading and 1,013,022 ms at
        // the last. Through the nearest of the three equivalences that is
        // 2026-08-03T04:40:10.033-04:00 to 04:57:00.973-04:00 - the same instant
        // the hardware run printed for index 0000001.
        assertEquals(2081L, samples.first().relMs)
        assertEquals(1_013_022L, samples.last().relMs)
        assertEquals(1_785_746_410.033, samples.first().at, 1e-6)
        assertEquals(1_785_747_420.973, samples.last().at, 1e-6)
        assertEquals(
            "the run should last as long as the board says it did",
            1_010.94, samples.last().at - samples.first().at, 1e-6,
        )
    }

    @Test
    fun `and at the spacing the board recorded, bar the drift it measured`() {
        val model = Model()
        loadBoth(model)
        model.basis.accept(timeMap())
        model.history.retime()

        val samples = samplesOf(model, placedBoot)
        var moved = 0
        var worst = 0.0
        for (i in 1 until samples.size) {
            val recorded = (samples[i].relMs!! - samples[i - 1].relMs!!) / 1000.0
            val converted = samples[i].at - samples[i - 1].at
            val slip = Math.abs(converted - recorded)
            if (slip > 1e-6) moved++
            if (slip > worst) worst = slip
        }
        // Exactly two gaps differ, and only by milliseconds: they are where the
        // nearest equivalence changes from one to the next, and the step is the
        // crystal's real drift between the two measurements. Everywhere else the
        // interval the board recorded survives conversion untouched.
        assertEquals("conversion changed the spacing of more than the two seams", 2, moved)
        assertTrue("a seam moved a reading by ${worst}s", worst < 0.005)
    }

    @Test
    fun `the nearest equivalence is used, so the error is the gap to it`() {
        val model = Model()
        loadBoth(model)
        model.basis.accept(timeMap())
        // The board's three: 328,315 ms, 462,767 ms and 564,675 ms into the run.
        // The last reading is at 1,013,022 ms, so it is 448 s from the newest -
        // not the 685 s it would be from the first.
        val last = model.basis.resolve(placedBoot, 1_013_022L)
        assertTrue(last is TimeBasis.Resolution.Derived)
        assertEquals(448_347L, (last as TimeBasis.Resolution.Derived).distanceMs)
        assertEquals(-240, last.utcOffsetMinutes)
    }

    // --- 3. the run nothing can place is kept, and stays relative -------------

    @Test
    fun `run 1196 is kept, and presented as elapsed rather than dated`() {
        val model = Model()
        loadBoth(model)
        model.basis.accept(timeMap())
        model.history.retime()

        assertEquals(
            "the run with no equivalence was placed by one belonging to another run",
            listOf(unplaceableBoot), model.basis.unanchoredBoots(),
        )
        assertFalse(model.basis.isAnchored(unplaceableBoot))
        assertTrue(model.basis.resolve(unplaceableBoot, 2_071L) is TimeBasis.Resolution.Unanchored)

        val samples = samplesOf(model, unplaceableBoot)
        assertEquals("readings nothing could date were dropped", 266, samples.size)
        // No position at all, rather than a position of zero. The store used to
        // hold elapsed-within-the-run in the same column as an instant, and that
        // number - correct as an elapsed time - is 1970 read as a date, which is
        // what put this run on the chart fifty-six years before the other one.
        assertTrue("a reading nothing can date was given a time", samples.all { it.epoch == null })
        assertEquals(266, model.history.unplacedCount())
        // What it does know is its own internal timing, and that is intact: the
        // board's clock still spans the 267.5 s it recorded over.
        assertEquals(2_071L, samples.first().relMs)
        assertEquals(269_571L, samples.last().relMs)
        val elapsed = model.basis.resolve(unplaceableBoot, 269_571L)
        assertEquals(267.5, (elapsed as TimeBasis.Resolution.Unanchored).elapsedSeconds, 1e-6)
    }

    @Test
    fun `it is nowhere near the run that could be placed`() {
        val model = Model()
        loadBoth(model)
        model.basis.accept(timeMap())
        model.history.retime()
        assertTrue(
            "an unplaceable run was drawn as if it continued the placed one",
            samplesOf(model, unplaceableBoot).all { it.epoch == null },
        )
        assertTrue(samplesOf(model, placedBoot).all { it.epoch != null })
        assertEquals(
            "the two runs were merged into one session",
            2, model.history.snapshot().map { it.csv }.toSet().size,
        )
    }

    /**
     * The captain's blocker, in the form his tablet showed it.
     *
     * With run 1196 held at elapsed-time-from-zero *in the plot's own epoch
     * column*, "all" took its origin from the earliest reading it could find and
     * got 1970. The visible range became 1970-to-now, the header read
     * `window 1785801740s - 2 pts`, and the automatic averaging - correct
     * arithmetic on a fifty-six-year span - settled on 248 h, which folded 1,266
     * real readings into two averaged points joined by a line.
     *
     * Nothing here is constructed: both runs are the board's, [timeMap] is the
     * three equivalences it holds, and 1785801740 is the instant off the tablet.
     */
    @Test
    fun `and it cannot drag the plot's axis back to 1970`() {
        val model = Model()
        loadBoth(model)
        model.basis.accept(timeMap())
        model.history.retime()

        // Where "all" takes its left edge from. Run 1196 has no place on an axis
        // of dates, so it must not be what answers this - and it did: the origin
        // came back as 20.0, the twentieth second of a run nobody can date.
        val origin = model.history.firstStableEpoch()
        assertTrue("\"all\" found no origin at all", origin != null)
        val placedRun = samplesOf(model, placedBoot).map { it.at }
        assertTrue(
            "an undated reading became the start of the visible range: $origin",
            origin!! >= placedRun.first() && origin <= placedRun.last(),
        )

        val chart = ChartView()
        chart.applyAll(origin, TABLET_NOW)
        val raw = chart.compute(model.history, TABLET_NOW, maxPoints = TABLET_POINT_BUDGET)
        assertTrue(
            "the x axis spans ${raw.spanSeconds}s, drawn from one undated point",
            raw.spanSeconds < 86_400.0,
        )
        // Everything in the window comes from the run that can be dated, and all
        // of it that the origin admits - "all" starts at the first *stable*
        // reading, so the settling ones ahead of it are outside by design.
        assertEquals(
            "readings nothing can date were plotted anyway",
            placedRun.count { it >= origin }, raw.sampleCount,
        )
        assertEquals(1266 - 266 - 20, raw.sampleCount)
        assertTrue(
            "a point was drawn before this project existed",
            raw.x.all { it >= ChartView.EARLIEST_PLAUSIBLE_EPOCH },
        )

        // And with the automatic averaging the captain had switched on, which is
        // what turned the corrupt span into the two points he was shown. The
        // bucket was 892901 s - 248 h - because the span was fifty-six years.
        chart.setAutoInterval()
        val averaged = chart.compute(model.history, TABLET_NOW, maxPoints = TABLET_POINT_BUDGET)
        assertTrue(
            "auto averaging chose a ${averaged.bucketSeconds}s bucket",
            (averaged.bucketSeconds ?: 0.0) < 600.0,
        )
        assertTrue(
            "the plot collapsed to ${averaged.sampleCount} points",
            averaged.sampleCount > 20,
        )
    }

    /**
     * And the axis refuses the corrupt origin even if one reaches it.
     *
     * The leak above is fixed at its source, so this drives [ChartView] with an
     * origin of zero directly - a constructed input, because with the fix in
     * place the store can no longer produce one. It is the backstop for the whole
     * class: whatever hands the chart a start from before this project existed,
     * the answer is not to render it faithfully across five decades.
     */
    @Test
    fun `an origin from before this project existed is refused, not drawn`() {
        val model = Model()
        loadBoth(model)
        model.basis.accept(timeMap())
        model.history.retime()

        val chart = ChartView()
        chart.applyWindow(3_600.0, TABLET_NOW)
        chart.applyAll(0.0, TABLET_NOW)
        val data = chart.compute(model.history, TABLET_NOW, maxPoints = TABLET_POINT_BUDGET)
        assertTrue(
            "the chart drew from an epoch-zero origin: ${data.start}",
            data.start >= ChartView.EARLIEST_PLAUSIBLE_EPOCH,
        )
        // Refused rather than clamped to the floor: the window keeps the size it
        // had, so what the user gets is an hour of their own data, not a view
        // stretched from January to now because one number was nonsense.
        assertEquals(3_600.0, data.spanSeconds, 1e-6)

        // The same refusal one layer down, for anything that reaches the axis by
        // another door - a frozen range, a pan, a pin dragged into the past.
        chart.setXRange(0.0, TABLET_NOW)
        assertTrue(
            "a frozen range starting at the epoch was drawn as asked",
            chart.visibleRange(TABLET_NOW).first >= ChartView.EARLIEST_PLAUSIBLE_EPOCH,
        )
    }

    /**
     * And reopening the app does not put it back on the plot.
     *
     * The cache is the second door into the same conversion, and the one the
     * captain goes through every time he reopens the app: it carries an epoch per
     * reading, written when it was written. For run 1196 that epoch was the
     * corrupt one, so trusting it would have restored the 1970 point after the
     * live path had stopped producing it. The raw `(boot, ms)` is what is
     * authoritative, and re-placing from it is what this holds.
     */
    @Test
    fun `nor does it come back from the cache with a position`() {
        val dir = java.nio.file.Files.createTempDirectory("board-cache").toFile()
        try {
            val model = Model()
            val cache = PlotCache(java.io.File(dir, PlotCache.FILE_NAME), model.history, model.basis)
            cache.load()
            loadBoth(model)
            model.basis.accept(timeMap())
            model.history.retime()
            cache.flush()
            cache.close()

            val after = Model()
            val restored = PlotCache(
                java.io.File(dir, PlotCache.FILE_NAME), after.history, after.basis,
            ).load()
            assertEquals("the cache lost readings", 1266, restored)
            assertTrue(
                "the run nothing can date came back with a time",
                samplesOf(after, unplaceableBoot).all { it.epoch == null },
            )
            assertEquals(266, after.history.unplacedCount())
            // And the run that can be dated came back exactly where it was.
            val placedBack = samplesOf(after, placedBoot)
            assertEquals(1_785_746_410.033, placedBack.first().at, 1e-6)
            assertEquals(1_785_747_420.973, placedBack.last().at, 1e-6)
            // So "all" still frames the placed run, not five decades of nothing.
            assertTrue(
                (after.history.firstStableEpoch() ?: 0.0) >= ChartView.EARLIEST_PLAUSIBLE_EPOCH,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a recollection places it, and taking it back leaves it as recorded`() {
        val model = Model()
        loadBoth(model)
        model.basis.accept(timeMap())
        model.history.retime()

        val origin = 1_785_740_000.0
        model.basis.placeManually(unplaceableBoot, origin)
        model.history.retime()
        var samples = samplesOf(model, unplaceableBoot)
        assertEquals(origin, samples.first().at, 1e-6)
        assertEquals(origin + 267.5, samples.last().at, 1e-6)
        assertTrue(
            model.basis.resolve(unplaceableBoot, 2_071L) is TimeBasis.Resolution.Manual,
        )

        model.basis.placeManually(unplaceableBoot, null)
        model.history.retime()
        samples = samplesOf(model, unplaceableBoot)
        assertEquals(266, samples.size)
        // Taking the recollection back takes the position back with it, rather
        // than leaving the readings at wherever they had been put.
        assertTrue("withdrawing a placement left a time behind", samples.all { it.epoch == null })
        assertEquals(266, model.history.unplacedCount())
    }

    // --- 4. the export is honest about where each time came from --------------

    @Test
    fun `the exported CSV says which times were derived and which were measured`() {
        MonitorSession.history.clear()
        MonitorSession.timeBasis.clear()
        try {
            val (placedCols, placedRows) = wire(placed)
            val (otherCols, otherRows) = wire(unplaceable)
            MonitorSession.history.addRows(placed, placedCols, placedRows, explicit = true)
            MonitorSession.history.addRows(unplaceable, otherCols, otherRows, explicit = true)
            MonitorSession.timeBasis.accept(timeMap())
            MonitorSession.history.retime()

            val csv = runBlocking { MonitorSession.buildCsv() }
            val lines = csv.trim('\n').split('\n')
            val header = lines.first().split(',')
            assertEquals(
                "the export lost the column that says where a time came from",
                Wire.TIME_SOURCE_COLUMN, header.last(),
            )
            assertEquals("the export lost readings", 1266, lines.size - 1)

            val sourceCol = header.indexOf(Wire.TIME_SOURCE_COLUMN)
            val bySource = lines.drop(1).groupingBy { it.split(',')[sourceCol] }.eachCount()
            assertEquals(
                "a derived time was written as if a clock had been read",
                mapOf(Wire.TIME_DERIVED to 1000, Wire.TIME_UNANCHORED to 266),
                bySource,
            )

            // And the timestamp column matches that claim row for row: a date for
            // the run an equivalence places, a duration for the run nothing does.
            val derived = lines.drop(1).first { it.split(',')[sourceCol] == Wire.TIME_DERIVED }
            assertEquals("2026-08-03T04:40:10-04:00", derived.split(',').first())
            val unanchored =
                lines.drop(1).first { it.split(',')[sourceCol] == Wire.TIME_UNANCHORED }
            assertEquals("+0:00:00", unanchored.split(',').first())
        } finally {
            MonitorSession.history.clear()
            MonitorSession.timeBasis.clear()
        }
    }

    // --- 5. and the file form carries all of it, out and back -----------------

    // The two `.csv` resources are the same 1,266 readings as the `.wire.csv`
    // ones - identical in all thirteen columns they share - but whatever wrote
    // them kept only the classic header, which had no place for the board's
    // clock. A reading with an empty timestamp and no `(boot, ms)` is not
    // "unplaceable"; it is unplaceable *forever*, because the one thing that
    // could ever have dated it is not in the file.
    //
    // That was a gap in the format, and it is closed: `wire.CSV_COLUMNS_RELATIVE`
    // appends `rel_boot` and `rel_ms` for exactly the sessions that have them,
    // and `SessionArchive` writes and reads that header. The tests below hold
    // what that has to mean.

    /** [name] put through the real archive writer, as a copy would write it. */
    private fun archived(name: String): ByteArray {
        val (columns, rows) = wire(name)
        val out = java.io.ByteArrayOutputStream()
        SessionArchive.Writer(out).use { writer ->
            assertTrue("the archive refused to write $name", writer.begin(name))
            // Exactly what `MonitorSession.archiveRows` does with a `file_data`
            // frame: put the controller's cells onto the session's own column
            // list. Nothing here reaches past that into the test's knowledge of
            // the file, so what is written is what the app would write.
            val order = Wire.csvColumnsFor(name).map { columns.indexOf(it) }
            for (row in rows) writer.row(order.map { c -> if (c in row.indices) row[c] else "" })
        }
        return out.toByteArray()
    }

    /** Every session in [zip], read back the way an import reads it. */
    private fun readBack(zip: ByteArray): Map<String, Pair<List<String>, List<List<String>>>> {
        val out = LinkedHashMap<String, Pair<List<String>, MutableList<List<String>>>>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == SessionArchive.MANIFEST) continue
                val reader = zin.bufferedReader()
                val header = reader.readLine().split(',')
                val rows = ArrayList<List<String>>()
                while (true) rows.add((reader.readLine() ?: break).split(','))
                out[entry.name] = header to rows
            }
        }
        return out.mapValues { (_, v) -> v.first to v.second.toList() }
    }

    @Test
    fun `the archived form of a clockless session keeps the board's clock`() {
        for (name in listOf(placed, unplaceable)) {
            val (header, rows) = readBack(archived(name)).getValue(name)
            assertEquals(
                "$name was written with a header that has no room for its clock",
                Wire.CSV_COLUMNS_RELATIVE, header,
            )
            // The thirteen a reader has always known are in the thirteen places
            // it has always looked. That is what makes this additive rather than
            // a new format the frozen Pi build could not read.
            assertEquals(Wire.CSV_COLUMNS, header.take(Wire.CSV_COLUMNS.size))
            val ts = header.indexOf("timestamp")
            assertTrue(
                "$name claims an instant the board could not have known",
                rows.all { it[ts].isEmpty() },
            )
            assertTrue(
                "$name was archived with rows that have no position",
                rows.all { it[header.indexOf(Wire.COL_REL_MS)].isNotEmpty() },
            )
        }
    }

    @Test
    fun `exporting both sessions and reading them back loses nothing`() {
        val model = Model()
        var total = 0
        for (name in listOf(placed, unplaceable)) {
            val zip = archived(name)
            // The gate an import passes through before a single row moves. It
            // counts a session's readable rows, and a clockless row is readable
            // only because the file now carries what dates it - without that
            // every row tallied as unreadable and the whole file was refused
            // with "there are no readings in that file".
            val survey = SessionArchive.survey(
                { java.io.ByteArrayInputStream(zip) }, "sps30-export.zip",
            ).getOrThrow()
            assertEquals(listOf(name), survey.sessions.map { it.name })
            assertEquals(0, survey.unreadable)

            for ((entry, file) in readBack(zip)) {
                total += model.history.addRows(entry, file.first, file.second, explicit = true)
            }
        }
        assertEquals("readings were dropped by the round trip", 1266, total)
        assertEquals(1266, model.history.size)

        // Position intact, run by run: the same indices, the same board clock
        // readings, and the same span the sessions were recorded over.
        model.basis.accept(timeMap())
        model.history.retime()
        val placedSamples = samplesOf(model, placedBoot)
        assertEquals(1000, placedSamples.size)
        assertEquals(2081L, placedSamples.first().relMs)
        assertEquals(1_013_022L, placedSamples.last().relMs)
        assertEquals(1_785_746_410.033, placedSamples.first().at, 1e-6)
        assertEquals(1_785_747_420.973, placedSamples.last().at, 1e-6)
        assertEquals(listOf(1, 1000), listOf(placedSamples.first().index, placedSamples.last().index))

        val otherSamples = samplesOf(model, unplaceableBoot)
        assertEquals(266, otherSamples.size)
        assertFalse(model.basis.isAnchored(unplaceableBoot))
        assertTrue("a round trip invented a time", otherSamples.all { it.epoch == null })
        assertEquals(2_071L, otherSamples.first().relMs)
        assertEquals(269_571L, otherSamples.last().relMs)
    }

    @Test
    fun `and what each time was worth survives with it`() {
        // A round trip that kept the readings but forgot which of them had ever
        // been near a clock would be a quieter version of the same loss: 266 of
        // these have no time at all, and a file that came back calling them
        // `measured` would be the invented date this design exists to refuse.
        MonitorSession.history.clear()
        MonitorSession.timeBasis.clear()
        try {
            for (name in listOf(placed, unplaceable)) {
                for ((entry, file) in readBack(archived(name))) {
                    MonitorSession.history.addRows(entry, file.first, file.second, explicit = true)
                }
            }
            MonitorSession.timeBasis.accept(timeMap())
            MonitorSession.history.retime()

            val lines = runBlocking { MonitorSession.buildCsv() }.trim('\n').split('\n')
            val header = lines.first().split(',')
            assertEquals(Wire.TIME_SOURCE_COLUMN, header.last())
            assertEquals("the export lost readings", 1266, lines.size - 1)

            val sourceCol = header.indexOf(Wire.TIME_SOURCE_COLUMN)
            assertEquals(
                "a derived time came back as if a clock had been read",
                mapOf(Wire.TIME_DERIVED to 1000, Wire.TIME_UNANCHORED to 266),
                lines.drop(1).groupingBy { it.split(',')[sourceCol] }.eachCount(),
            )

            // And the shared file is itself readable back: its `rel_boot` and
            // `rel_ms` put every row back where the board had it. This one is a
            // plot export rather than a session archive - it carries no session
            // column, so the two runs are told apart by the run marker, which is
            // the other thing those two columns are for.
            val reread = Model()
            val bootCol = header.indexOf(Wire.COL_REL_BOOT)
            var restored = 0
            for ((boot, count) in listOf(placedBoot to 1000, unplaceableBoot to 266)) {
                val rows = lines.drop(1)
                    .map { it.split(',') }
                    .filter { it[bootCol].toIntOrNull() == boot }
                assertEquals("run $boot did not come back whole", count, rows.size)
                restored += reread.history.addRows("run-$boot.csv", header, rows, explicit = true)
            }
            assertEquals("the shared file lost readings on the way back in", 1266, restored)
            // Position intact: the run nothing can date still spans the 267.5 s
            // the board measured, rather than collapsing onto one instant.
            reread.basis.accept(timeMap())
            reread.history.retime()
            val back = samplesOf(reread, unplaceableBoot)
            assertEquals(266, back.size)
            assertTrue("a round trip invented a time", back.all { it.epoch == null })
            assertEquals(
                267.5,
                (back.last().relMs!! - back.first().relMs!!) / 1000.0, 1e-6,
            )
        } finally {
            MonitorSession.history.clear()
            MonitorSession.timeBasis.clear()
        }
    }

    @Test
    fun `the backup taken before the fix still cannot be placed, and says so`() {
        // The two `.csv` resources are what the old export wrote, and no change
        // to the format reaches back into a file already on disk: they hold an
        // empty timestamp and nothing else, so the app keeps none of it - still
        // correctly, and still by design. Kept as the record of what was lost
        // and of why the sessions had to be re-fetched from the board to test
        // this at all. The readings themselves are not lost: the board holds
        // them, and `.wire.csv` above is the same 1,266 rows with their clock.
        for (name in listOf(placed, unplaceable)) {
            val (columns, rows) = file(name)
            assertFalse("$name unexpectedly carries a clock", Wire.COL_REL_BOOT in columns)
            assertFalse("$name unexpectedly carries a clock", Wire.COL_REL_MS in columns)
            val ts = columns.indexOf("timestamp")
            assertTrue(rows.all { it[ts].isEmpty() })

            val model = Model()
            assertEquals(
                "$name: an old backup cannot suddenly hold what was never written to it",
                0, model.history.addRows(name, columns, rows, explicit = true),
            )
        }
    }
}

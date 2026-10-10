package com.sensirion.sps30.ui

import com.sensirion.sps30.data.SessionRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished restore says it did.
 *
 * The hazard is the one [transferHeadline] has in the other direction: two very
 * different outcomes both report nothing added. A file the record already held
 * in full is the feature working perfectly; a file whose readings nothing could
 * date is data that did not make it. Wording the second as the first would tell
 * a user their morning is back when it is not, which is precisely the kind of
 * quiet lie the whole record is supposed to be free of.
 *
 * The counts are asserted here rather than only in the engine's tests because
 * this is where they reach a person. A summary that prints only what worked
 * reads as "all of it".
 */
class RestoreWordingTest {

    private fun report(
        readings: Int,
        added: Int,
        alreadyHeld: Int,
        undated: Int = 0,
        unreadableRows: Int = 0,
        clocklessSkipped: Int = 0,
        unplaced: Int = 0,
        fileStart: Double? = 1_785_843_875.0,
        fileEnd: Double? = 1_785_856_694.0,
    ) = SessionRestore.Report(
        readings = readings,
        added = added,
        alreadyHeld = alreadyHeld,
        undated = undated,
        unreadableRows = unreadableRows,
        sessions = 1,
        clocklessSkipped = clocklessSkipped,
        unplaced = unplaced,
        fileStart = fileStart,
        fileEnd = fileEnd,
        recordStart = fileStart,
        recordEnd = fileEnd,
    )

    @Test fun `a restore that added readings leads with how many`() {
        val headline = restoreHeadline(report(readings = 12_669, added = 12_669, alreadyHeld = 0))
        assertEquals("Added 12,669 readings to the record", headline)
    }

    @Test fun `a file the record already held is a success, and says so`() {
        assertEquals(
            "This device already had all of it",
            restoreHeadline(report(readings = 12_669, added = 0, alreadyHeld = 12_669)),
        )
    }

    @Test fun `a file that contributed nothing at all is not worded as one that did`() {
        // Nothing added and nothing already held: the readings are not here, by
        // either route, and the heading must not suggest otherwise.
        val headline = restoreHeadline(report(readings = 40, added = 0, alreadyHeld = 0, undated = 40))
        assertEquals("Nothing in that file could be added", headline)
        assertFalse(
            "it must not read as the record already having them",
            headline.contains("already"),
        )
    }

    @Test fun `the detail accounts for every reading the file held`() {
        val detail = restoreDetail(
            report(readings = 1_000, added = 600, alreadyHeld = 395, undated = 5, unreadableRows = 2),
        )
        assertTrue(detail, detail.contains("1,000 readings in the file"))
        assertTrue(detail, detail.contains("600 added"))
        assertTrue(detail, detail.contains("395 already here and kept"))
        assertTrue(detail, detail.contains("5 could not be dated"))
        assertTrue(detail, detail.contains("2 rows could not be read"))
    }

    @Test fun `an ordinary restore is not cluttered with zeroes`() {
        val detail = restoreDetail(report(readings = 500, added = 500, alreadyHeld = 0))
        assertFalse(detail, detail.contains("could not be dated"))
        assertFalse(detail, detail.contains("could not be read"))
    }

    @Test fun `the covered span is the one to hold against the plot`() {
        // The captain's morning: 2026-08-04 07:44:35 to 11:18:14, Eastern.
        assertEquals(
            "Now covered: Aug 4, 07:44 to 11:18",
            restoreSpan(report(readings = 12_669, added = 12_669, alreadyHeld = 0)),
        )
    }

    @Test fun `a span across days names both`() {
        val across = restoreSpan(
            report(
                readings = 10, added = 10, alreadyHeld = 0,
                fileStart = 1_785_843_875.0, fileEnd = 1_785_843_875.0 + 86_400,
            ),
        )
        assertEquals("Now covered: Aug 4 07:44 to Aug 5 07:44", across)
    }

    @Test fun `readings nothing can date have no span rather than a made-up one`() {
        assertEquals(
            null,
            restoreSpan(
                report(
                    readings = 30, added = 30, alreadyHeld = 0, unplaced = 30,
                    fileStart = null, fileEnd = null,
                ),
            ),
        )
    }

    @Test fun `what was left out is said out loud, not folded into the counts`() {
        val caveats = restoreCaveats(
            report(readings = 25, added = 25, alreadyHeld = 0, clocklessSkipped = 1, unplaced = 4),
        )
        assertEquals(2, caveats.size)
        assertTrue(caveats[0], caveats[0].contains("no clock"))
        assertTrue(caveats[0], caveats[0].contains("left out"))
        assertTrue(caveats[1], caveats[1].contains("no date yet"))
        // And it must say where they did go, or "no date" reads as "discarded".
        assertTrue(caveats[1], caveats[1].contains("in the record"))
    }

    @Test fun `an ordinary restore has nothing to caveat`() {
        assertTrue(restoreCaveats(report(readings = 500, added = 500, alreadyHeld = 0)).isEmpty())
    }
}

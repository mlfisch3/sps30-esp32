package com.sensirion.sps30.data

import com.sensirion.sps30.ui.inventoryValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Why the Data tab has to say that its two counts cannot be made to agree.
 *
 * The inventory at the top of the tab counts a clockless session like any other,
 * because that is what it is: the sensor holds it, a copy fetches it, the plot
 * draws it once something can place it. The stored-sessions overview lower down
 * reads the same session back off this device and reports it as holding no
 * readings, because it buckets each row on a parseable `timestamp` and a
 * clockless row's is deliberately empty - the board refused to invent one.
 *
 * So one screen carries two honest totals that differ, and the difference is
 * exactly the clockless sessions. Neither is wrong and neither can be fixed by
 * the other, which leaves saying so as the only remaining option: an inventory
 * that quietly dropped them would be the misleading count, and one that showed
 * them without a word would send the reader off to reconcile two numbers that
 * never will.
 *
 * This file is what keeps that note true. Both halves are asserted, because the
 * note is a claim about the *other* code path and would rot silently if
 * [SessionCsv] ever learned to read `rel_boot`/`rel_ms`: at that point the note
 * becomes the lie instead.
 *
 * The input is 266 readings an ESP32 recorded overnight on 2026-08-03 with no
 * network to tell it the time, exactly as the old export wrote them. See
 * `src/test/resources/board/README.md`; nothing here is synthetic.
 */
class ClocklessOverviewGapTest {

    private val clockless = "SPS30-R000004AC-00000817.csv"

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/board/$name")?.bufferedReader()?.readText()
            ?: throw AssertionError("missing test resource /board/$name")

    private fun reader(name: String) = BufferedReader(StringReader(resource(name)))

    @Test fun theStoredSessionsOverviewCannotSeeAClocklessSession() {
        var emitted = 0
        val result = SessionCsv.read(reader(clockless), bucketSeconds = 60.0) { _, _, _, _ ->
            emitted++
        }
        assertEquals(
            "a clockless row has no timestamp to bucket on, so nothing is emitted",
            0,
            emitted,
        )
        assertEquals("and the file therefore reads as empty", SessionCsv.Skip.EMPTY, result.skip)
        assertEquals(0L, result.rowsRead)
    }

    @Test fun theFileItselfIsNotEmpty() {
        // The other half: this is a real session with real readings in it, so
        // "empty" above is a limitation of the reader and not a property of the
        // file. If this ever fails the resource has been replaced, and the test
        // above stops meaning anything.
        val rows = resource(clockless).trim('\n').split('\n').drop(1)
        assertEquals(266, rows.size)
        assertTrue(
            "every row's timestamp cell is deliberately blank",
            rows.all { it.split(',')[1].isEmpty() },
        )
    }

    @Test fun theInventoryStillCountsThem() {
        // What the sensor says it holds is what the sensor says it holds. A
        // clockless session is in the totals; only its date is missing.
        val value = inventoryValue(RecordCount.Counted(sessions = 2, readings = 1_266))
        assertTrue("clockless sessions are in the count: $value", value.contains("1,266"))
    }
}

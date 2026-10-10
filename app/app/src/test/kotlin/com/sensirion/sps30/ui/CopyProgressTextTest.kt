package com.sensirion.sps30.ui

import com.sensirion.sps30.data.SessionBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progress row the captain watched climb, and what it now says while it does.
 *
 * On 2026-08-06 it read "session 1 of 1 · 54,222 readings so far" and went past
 * 66,900 and 75,000 while he read the panel above it, which was correctly
 * reporting that nothing was missing. Neither was wrong. The copy was re-reading
 * readings this device already held in order to write them into an archive file,
 * so the racing number was rows-into-a-file and the still one was
 * readings-this-device-lacks - two honest meters of two different things, with
 * nothing on screen saying they were different things.
 *
 * These lines are what says it. They are pure functions for the reason
 * [transferHeadline] is: the wording is the part that can be got wrong in a way
 * no arithmetic test would notice, and it is what the user actually has.
 */
class CopyProgressTextTest {

    private fun streaming(
        done: Int,
        total: Int,
        rows: Long,
        newHere: Long,
    ) = SessionBackup.Receiving(
        received = done.toLong(),
        total = total.toLong(),
        sessions = done,
        rows = rows,
        newHere = newHere,
    )

    @Test fun theHeadlineSaysWhichSessionOfHowMany() {
        assertEquals(
            "Copying from the sensor · session 3 of 12",
            copyProgressHeadline(streaming(done = 2, total = 12, rows = 900, newHere = 900)),
        )
    }

    @Test fun theHeadlineNeverCountsPastTheTotal() {
        // The line it replaces printed `received + 1` unguarded, so the last
        // session of a copy read "session 13 of 12" until the archive closed.
        assertEquals(
            "Copying from the sensor · session 12 of 12",
            copyProgressHeadline(streaming(done = 12, total = 12, rows = 900, newHere = 900)),
        )
    }

    @Test fun aByteCountedArchiveIsNotLabelledInSessions() {
        // The legacy path puts bytes in the same two fields, and this row used to
        // print them with the word "session" in front: "session 1 of 4,182,993".
        val headline = copyProgressHeadline(
            SessionBackup.Receiving(
                received = 12_000,
                total = 4_182_993,
                sessions = 4,
                rows = 152_010,
                receivedAreSessions = false,
            ),
        )
        assertFalse("bytes are not sessions: $headline", headline.contains("session "))
        assertTrue("and they are named as what they are: $headline", headline.contains("bytes"))
    }

    @Test fun theDetailReportsWhatWasReadAndWhatOfItWasNew() {
        assertEquals(
            "54,222 readings read so far · 3,410 of them new to this device",
            copyProgressDetail(streaming(done = 0, total = 1, rows = 54_222, newHere = 3_410)),
        )
    }

    @Test fun aCopyThatIsAddingNothingSaysSoRatherThanShowingOnlyTheClimbingFigure() {
        // Exactly the captain's screen. The count races and the inventory does
        // not move, and until this line existed there was nothing anywhere on the
        // tab that reconciled the two.
        val backup = streaming(done = 0, total = 1, rows = 54_222, newHere = 0)
        assertEquals(
            "54,222 readings read so far · none of them new to this device",
            copyProgressDetail(backup),
        )
        val why = copyProgressExplanation(backup)
        assertTrue("it must say why: $why", why!!.contains("already had all of them"))
        assertTrue(
            "and tie it to the figures that are not moving: $why",
            why.contains("the figures above do not"),
        )
        assertTrue(
            "and say what the reading is for, since it is not to fill a gap: $why",
            why.contains("archive file"),
        )
    }

    @Test fun aCopyThatIsAddingReadingsNeedsNoExplanation() {
        // The ordinary case, where the climbing figure and the inventory move
        // together. A paragraph explaining an absence that is not there is one
        // more thing to read on the tab that can least afford them.
        assertNull(copyProgressExplanation(streaming(done = 0, total = 3, rows = 900, newHere = 900)))
    }

    @Test fun aCopyThatHasReadNothingYetExplainsNothingYet() {
        assertNull(copyProgressExplanation(streaming(done = 0, total = 3, rows = 0, newHere = 0)))
    }
}

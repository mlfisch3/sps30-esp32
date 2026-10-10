package com.sensirion.sps30.ui

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Copy everything the sensor has recorded" has to mean everything.
 *
 * The app's cap on how many sessions one backfill collects was written as 24
 * when the board could name 16, and it governed both situations that ask for
 * sessions: the automatic reconnect backfill, where a bound is the whole point
 * because a Raspberry Pi lists hundreds, and the copy the user explicitly asked
 * for, where a bound is a backup that is not one.
 *
 * The board's ceiling then moved to 32 and the cap did not. Measured on the
 * demo board on 2026-08-04: a copy against a board listing 32 sessions wrote 24
 * of them and skipped the eight oldest. The app said so in red rather than
 * hiding it, so nothing was silently lost - but two things followed. Those eight
 * sessions could never reach the plot either, and because the controller erases
 * only what the asking device can prove it holds, the board could never be fully
 * cleared through the app.
 *
 * So the two situations get two answers, and this is the test that holds them
 * apart.
 */
class FullBackupTest {

    /** A board at its ceiling: 32 named sessions, newest first. */
    private fun boardAtItsCeiling(count: Int = 32) = (count downTo 1).map { n ->
        ServerMessage.FileEntry(
            name = "SPS30-2026080%d_%06d.csv".format(n % 10, n),
            rows = 100 + n,
            bytes = 4_096,
            start = "2026-08-04T00:00:00Z",
        )
    }

    private fun plan(files: List<ServerMessage.FileEntry>, forArchive: Boolean) =
        backfillPlan(files, forArchive = forArchive, held = emptyMap(), receivedEver = { 0 })

    @Test fun aCopyOfEverythingTakesEverySessionTheBoardNames() {
        val files = boardAtItsCeiling()
        val plan = plan(files, forArchive = true)
        assertEquals(
            "a copy that stops short of what the sensor listed is not a backup",
            files.size, plan.size,
        )
        assertEquals(
            "and it is the oldest that used to be dropped",
            files.map { it.name }, plan.map { it.name },
        )
    }

    /**
     * The headline consequence, stated as the report stated it: an archive short
     * of the controller's own count is what the erase gate cannot prove, so a
     * board can only be cleared through the app when the copy was complete.
     */
    @Test fun everySessionTheSensorHoldsIsInTheArchive() {
        val files = boardAtItsCeiling()
        val captured = plan(files, forArchive = true).map { it.name }.toSet()
        val missing = files.map { it.name }.filterNot { it in captured }
        assertTrue("$missing would be missing from the archive", missing.isEmpty())
    }

    @Test fun aCopyIsNotCappedEvenByAPiWithHundredsOfSessions() {
        val files = boardAtItsCeiling(count = 300)
        assertEquals(300, plan(files, forArchive = true).size)
    }

    /**
     * And the other half of the decision: the automatic backfill keeps a bound.
     * It exists to fill a plot that holds two days, off a controller that may
     * list everything it has ever recorded.
     */
    @Test fun theAutomaticBackfillIsStillBounded() {
        val plan = plan(boardAtItsCeiling(count = 300), forArchive = false)
        assertEquals(MAX_BACKFILL_SESSIONS, plan.size)
        assertTrue("a reconnect must not ask a Pi for hundreds of sessions", plan.size < 300)
    }

    /**
     * But its bound is at least the controller's own ceiling, so a reconnect to a
     * full board still reaches every session on it. This is the number that was
     * left behind: `STORE_MAX_SESSIONS` is 32 and this was 24.
     */
    @Test fun theAutomaticBackfillStillReachesAWholeFullBoard() {
        assertTrue(
            "the board can name $STORE_MAX_SESSIONS sessions and the app would " +
                "only ever ask for $MAX_BACKFILL_SESSIONS of them",
            MAX_BACKFILL_SESSIONS >= STORE_MAX_SESSIONS,
        )
        val files = boardAtItsCeiling(count = STORE_MAX_SESSIONS)
        assertEquals(files.size, plan(files, forArchive = false).size)
    }

    /** `STORE_MAX_SESSIONS` in `firmware/components/store/include/store_sessions.h`. */
    private val STORE_MAX_SESSIONS = 32

    // --- a listing longer than one frame -------------------------------------
    //
    // The cap above was fixed and the shortfall came straight back by another
    // road. On 2026-08-04 the board held 84 sessions and `file_list` carried 64,
    // because 64 is what fits in one 8 KiB frame - so *Copy sessions to this
    // device* wrote 64 of 84 and said in red that it was not a backup, the other
    // twenty could not be fetched or plotted, and the shortfall grew by one on
    // every restart.
    //
    // Raising the frame is the same mistake at a distance, twice made already.
    // The listing is walked instead, and these hold the walk open.

    /** What `net_server.c`'s `FILE_LIST_MAX` carries in one frame. */
    private val ONE_FRAME = 64

    /** One `file_list` frame, built and parsed as the wire really carries it. */
    private fun page(
        board: List<ServerMessage.FileEntry>,
        offset: Int,
        limit: Int = ONE_FRAME,
    ): ServerMessage.FileList {
        val slice = board.drop(offset).take(limit)
        val entries = slice.joinToString(",") { f ->
            """{"name":"${f.name}","rows":${f.rows},"bytes":${f.bytes},"start":"${f.start}"}"""
        }
        val following = offset + slice.size
        val next = if (following < board.size) ""","next":$following""" else ""
        return Messages.parse(
            """{"type":"file_list","files":[$entries],""" +
                """"offset":$offset,"total":${board.size}$next}""",
        ) as ServerMessage.FileList
    }

    /** Walk a controller's listing the way [MonitorSession] does. */
    private fun walk(board: List<ServerMessage.FileEntry>, forArchive: Boolean): List<String> {
        val captured = mutableListOf<String>()
        var offset: Int? = 0
        var pages = 0
        while (offset != null) {
            val frame = page(board, offset)
            captured += plan(frame.files, forArchive).map { it.name }
            pages++
            assertTrue("the walk did not terminate", pages <= 8)
            if (pages > 8) break
            offset = listingWalkNext(frame, forArchive)
        }
        return captured
    }

    @Test fun aCopyWalksEveryPageOfALongerListing() {
        val board = boardAtItsCeiling(count = 84)
        val captured = walk(board, forArchive = true)
        assertEquals(
            "the board holds 84 sessions and one frame names $ONE_FRAME of them; " +
                "a copy that reads one frame is not a backup",
            board.map { it.name }, captured,
        )
    }

    /**
     * And the erase gate follows from it. The controller erases only what the
     * asking device can prove it holds, so a copy that stops at one frame is a
     * board that can never be cleared through the app - which is the state the
     * demo board was left in.
     */
    @Test fun everySessionOnAPagedBoardReachesTheArchive() {
        val board = boardAtItsCeiling(count = 84)
        val captured = walk(board, forArchive = true).toSet()
        val missing = board.map { it.name }.filterNot { it in captured }
        assertTrue("$missing would be missing from the archive", missing.isEmpty())
    }

    /**
     * The automatic backfill still reads one page, deliberately. It fills a plot
     * that keeps two days off a controller that may list hundreds, one page
     * already names more than [MAX_BACKFILL_SESSIONS] would queue, and
     * newest-first means that page is the part the plot can still show.
     */
    @Test fun aReconnectStillReadsOnePage() {
        val board = boardAtItsCeiling(count = 84)
        assertEquals(MAX_BACKFILL_SESSIONS, walk(board, forArchive = false).size)
        assertTrue(
            "one page has to name at least what a reconnect would queue from it",
            ONE_FRAME >= MAX_BACKFILL_SESSIONS,
        )
    }

    /** A listing that fits one frame says so, and the walk stops after it. */
    @Test fun aListingThatFitsOneFrameIsOnePage() {
        val board = boardAtItsCeiling(count = 12)
        val frame = page(board, 0)
        assertEquals(12, frame.total)
        assertEquals(null, frame.next)
        assertEquals(board.map { it.name }, walk(board, forArchive = true))
    }

    /**
     * A controller too old to page names what it can and says nothing else, and
     * that has to keep meaning what it has always meant: one frame, complete.
     */
    @Test fun aControllerThatCannotPageIsReadAsCompleteAsItAlwaysWas() {
        val frame = Messages.parse(
            """{"type":"file_list","files":[""" +
                """{"name":"SPS30-20260804_000001.csv","rows":5,"bytes":1,"start":""}""" +
                """]}""",
        ) as ServerMessage.FileList
        assertEquals(0, frame.offset)
        assertEquals(frame.files.size, frame.total)
        assertEquals(null, frame.next)
        assertEquals(null, listingWalkNext(frame, forArchive = true))
    }

    /**
     * Nothing on this wire is authenticated, so the walk has to be unloopable by
     * whatever answers. A `next` that does not advance is not obeyed.
     */
    @Test fun aNextThatDoesNotAdvanceIsRefused() {
        fun frameWith(offset: Int, next: Int) = Messages.parse(
            """{"type":"file_list","files":[],"offset":$offset,"total":900,"next":$next}""",
        ) as ServerMessage.FileList

        assertEquals(null, frameWith(offset = 64, next = 64).next)
        assertEquals(null, frameWith(offset = 64, next = 0).next)
        assertEquals(65, frameWith(offset = 64, next = 65).next)
    }
}

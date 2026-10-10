package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Commands
import java.io.InputStream

/**
 * Turning an archive into the `put_file` frames that carry it back to the
 * controller.
 *
 * This is the frame plan and nothing else: no socket, no progress, no UI. It
 * lives apart from `MonitorSession.importArchive` because the one thing that
 * decides whether a send back ever finishes is a single flag on a single frame,
 * and a rule that important should be statable without a radio. It is:
 *
 * > **exactly one frame carries `done`, and it is the last one.**
 *
 * The controller answers a transfer when it reads `done` and at no other moment
 * of its own choosing, so a `done` that is lost, early, duplicated or absent is
 * a send back that does not complete. Lose it and the only thing that ends the
 * transfer is the controller's fifteen-second idle close-out, which is a worse
 * answer arriving far later - see `NET_IMPORT_IDLE_MS` in
 * `firmware/components/net/include/net_import.h`.
 *
 * [com.sensirion.sps30.data.ArchiveSendTest] states that rule against real
 * archives. The board's half of the same contract is `handle_put_file` in
 * `net_server.c` and `test_boolean_fields` in `firmware/test/test_wire.c`, which
 * parses the exact bytes [Commands.putFile] produces.
 */
object ArchiveSend {

    /**
     * Rows per `put_file` frame.
     *
     * The controller's inbound buffer is 2 KiB and a row is about 110 bytes, so
     * eight is a frame of around 900 bytes - one whole frame and most of another
     * fit at once, which is what keeps the controller reading while it works
     * through the one it has.
     *
     * Sixteen was measured on the board and is too many. It made a frame of
     * about 1.7 KiB, so a second one could not land beside a partial first, and
     * the transfer proceeded one frame per turn of the controller's loop at
     * best. The controller now closes its TCP window rather than dropping what
     * it cannot hold, so an oversized frame is slow rather than lossy - but
     * there is no reason to make it slow.
     */
    const val ROWS_PER_FRAME = 8

    /**
     * One `put_file` frame, and what it means for the progress line.
     *
     * @param line the framed, newline-terminated command ready for the link.
     * @param session the session these rows belong to.
     * @param rows how many readings this frame carries. Zero is legal and means
     *   the closing frame of an archive whose last session divided exactly by
     *   [ROWS_PER_FRAME]; it still has to go, because it is the one carrying
     *   [done].
     * @param sessionsDone how many sessions were finished before this frame.
     * @param done whether this is the frame that ends the transfer.
     */
    data class Chunk(
        val line: String,
        val session: String,
        val rows: Int,
        val sessionsDone: Int,
        val done: Boolean,
    )

    /**
     * Walk [open] and hand [emit] every frame the transfer is made of, in order.
     *
     * Streamed rather than collected: an archive of a month of recording does
     * not fit in a tablet's heap, and the sender only ever needs one frame at a
     * time. [emit] is where the waiting happens, so a controller that stops
     * reading stops this walk rather than filling memory ahead of it.
     *
     * A new session does **not** end the transfer. The controller switches
     * sessions on the name changing, and `done` ends the whole thing, so marking
     * each file done would give one report per file where the user asked one
     * question about one send.
     */
    suspend fun eachChunk(
        open: () -> InputStream,
        displayName: String,
        rowsPerFrame: Int = ROWS_PER_FRAME,
        emit: suspend (Chunk) -> Unit,
    ) {
        var seq = 0
        var current = ""
        var sessionsDone = 0
        val batch = ArrayList<String>(rowsPerFrame)

        suspend fun flush(done: Boolean) {
            // An empty batch is nothing to say unless it is carrying `done`.
            if (batch.isEmpty() && !done) return
            // Nothing has been named yet, so there is no session to put rows in
            // and nothing for `done` to close.
            if (current.isEmpty()) return
            val rows = batch.size
            val line = Commands.putFile(current, batch.joinToString("\n"), seq++, done)
            batch.clear()
            emit(Chunk(line, current, rows, sessionsDone, done))
        }

        SessionArchive.eachSession(
            open = open,
            displayName = displayName,
            onSession = { name ->
                if (current.isNotEmpty()) {
                    flush(false)
                    sessionsDone++
                }
                current = name
                // Per session, because the controller reads `seq` as a position
                // within the session it is filling, not within the transfer.
                seq = 0
            },
            emit = { cells ->
                batch.add(cells.joinToString(","))
                if (batch.size >= rowsPerFrame) flush(false)
            },
        )
        // The last frame carries `done`, which is what makes the controller
        // close the import and answer. Unconditional: it goes even when the
        // batch above it came out even, because the flag is the message.
        flush(true)
    }
}

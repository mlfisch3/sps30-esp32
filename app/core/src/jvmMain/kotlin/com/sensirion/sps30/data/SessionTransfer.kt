package com.sensirion.sps30.data

/**
 * Sending stored sessions back to the controller.
 *
 * The dangerous direction, and the states say so. There is no "replacing" state
 * because there is no replacing: the controller adds the readings it is missing
 * and keeps every one it already has, so the only outcomes are *added some*,
 * *added none because it had them all*, and *stopped without changing anything*.
 *
 * [Rejected] is deliberately separate from [Failed]. A rejected file was read,
 * found wanting, and **never sent** - nothing on the controller was touched, and
 * saying so is the difference between a user retrying and a user worrying.
 *
 * ## Sending is not finishing
 *
 * [Sending] ends when the last frame goes out; the transfer does not. The
 * controller answers a transfer once, when it sees the frame marked `done`, and
 * until that answer arrives nobody knows what happened to the data - so
 * [Awaiting] is a state of its own rather than the tail of [Sending]. It used to
 * be the tail of [Sending], and that is precisely how "it reaches 100% and hangs
 * for ever" was possible: the progress line was the *only* thing on screen, it
 * was already at its end, and there was nothing left running to notice that the
 * answer never came.
 */
sealed interface SessionTransfer {
    data object Idle : SessionTransfer

    /** Reading the file through to see whether it is safe to send. */
    data class Checking(val file: String) : SessionTransfer

    data class Sending(
        val file: String,
        val session: String,
        val sessionsDone: Int,
        val sessionsTotal: Int,
        val rowsSent: Int,
        val rowsTotal: Int,
        /**
         * Readings the controller has already accounted for, in an answer that
         * arrived while this was still sending.
         *
         * It closes a transfer that has gone quiet for fifteen seconds and
         * answers it, knowing nothing of whether the sender has stopped - so a
         * slow file is answered in parts, and each part counts only itself.
         * Carrying the earlier parts here is what lets the last one be reported
         * as the file rather than as the tail of it.
         */
        val landedSoFar: Int = 0,
    ) : SessionTransfer {
        val fraction: Float
            get() = if (rowsTotal > 0) {
                (rowsSent.toDouble() / rowsTotal).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    /**
     * Every row has gone and the controller's one answer is what is left.
     *
     * A real phase with a real deadline, not a formality. The controller's reply
     * buffer is one line deep and its `import_result` is sent once and never
     * repeated, so there are several ordinary ways for that one line not to
     * arrive - the connection replaced underneath it, the board closing the
     * transfer out early, a reply armed into a socket that stopped draining.
     * Every one of them used to leave the progress bar sitting at its end for
     * ever with nothing running that could notice.
     *
     * @param sentAtMs when the last frame was handed to the link, which is what
     *   the deadline is measured from.
     * @param landedSoFar readings the controller has already accounted for in an
     *   answer that arrived *during* the send - it closes a transfer out itself
     *   after fifteen seconds of silence, and the file is then answered in more
     *   than one part. Zero for the ordinary case.
     */
    data class Awaiting(
        val file: String,
        val rowsSent: Int,
        val sentAtMs: Long,
        val landedSoFar: Int = 0,
    ) : SessionTransfer

    /**
     * The controller has said what it did.
     *
     * [added] is the only number that means anything changed on it. A round trip
     * of a file the controller is itself the source of reports zero added and
     * every row a duplicate, which is what makes it safe to repeat.
     */
    data class Done(
        val file: String,
        val added: Int,
        val duplicate: Int,
        val rejected: Int,
        val sessions: Int,
        /** True when the controller ran out of room. It evicted nothing. */
        val ranOutOfRoom: Boolean,
        /**
         * True when the controller took none of it and said why - another
         * device was importing, or it has no storage at all.
         *
         * Distinct from a transfer that added nothing because everything was
         * already there, and the distinction is the whole point: both report
         * `added = 0`, and reading a refusal as "the sensor already had all of
         * it" tells the user their data is safe on a controller that never
         * looked at it. [message] is the controller's own words.
         */
        val refused: Boolean,
        val message: String,
    ) : SessionTransfer {
        /**
         * Readings the controller holds because of this file: the ones it took
         * and the ones it already had.
         *
         * The number that decides whether a transfer got the data there, which
         * [added] alone does not - a round trip of a file the controller is
         * itself the source of adds nothing and lands all of it.
         */
        val landed: Int get() = added + duplicate

        /**
         * True when the controller ended up holding none of it.
         *
         * [refused] cannot answer this: it is the controller's *state*, and a
         * state of `error` does not by itself mean the readings did not arrive.
         * A file whose sessions the controller already held, with a few rows
         * refused for having no clock, is answered `error` with a reason - and
         * every row that could be placed was placed. Only this says nothing got
         * there, and only this may be worded as a failure.
         */
        val nothingLanded: Boolean get() = landed == 0
    }

    /** Read, found damaged, and not sent. The controller is untouched. */
    data class Rejected(val file: String, val reason: String) : SessionTransfer

    /** Something went wrong once sending had begun. */
    data class Failed(val file: String, val reason: String) : SessionTransfer
}

/**
 * Erasing the controller, and the proof it demands first.
 *
 * The controller refuses unless this device can show it already holds the data,
 * session by session. [Refused] is therefore the *normal* outcome of asking
 * without a current backup, and is not an error.
 */
sealed interface SessionErase {
    data object Idle : SessionErase

    /** Working out what this device can prove it holds. */
    data object Proving : SessionErase

    data object Asked : SessionErase

    data class Done(
        val sessionsErased: Int,
        val sessionsKept: Int,
        val readingsErased: Int,
        val overridden: Boolean,
        val message: String,
    ) : SessionErase

    data class Refused(val message: String, val sessionsKept: Int) : SessionErase

    data class Failed(val reason: String) : SessionErase
}

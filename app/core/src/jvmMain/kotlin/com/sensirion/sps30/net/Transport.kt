package com.sensirion.sps30.net

import com.sensirion.sps30.CoreLog
import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

/**
 * Where a connection is in its lifecycle. `endpoint` is a transport-neutral label
 * (e.g. "192.168.1.159:8730" for Wi-Fi, or a device name/MAC for Bluetooth) so the
 * same UI works for both transports.
 *
 * [Searching] and [NotFound] cover the phase *before* there is an endpoint at all.
 * Both transports have one - a subnet sweep over Wi-Fi, a BLE scan over Bluetooth -
 * and it is the phase that fails when no sensor host is switched on. Folding it into
 * [Connecting]/[Reconnecting] made the app report "reconnecting…" for a host it had
 * never once reached, which is both untrue and unactionable.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    /** Looking for a host; none targeted yet. */
    data class Searching(val detail: String) : ConnectionState
    /**
     * The search finished without a usable host. [detail] says what happened;
     * [advice] is the transport's own suggestion, used in place of the UI's generic
     * one when the cause is known precisely (a host that is full needs different
     * advice from a host that is absent).
     */
    data class NotFound(
        val detail: String,
        val advice: String? = null,
        /**
         * The transport has stopped looking, and [Transport.run] has returned for
         * good rather than between attempts.
         *
         * The ordinary [NotFound] means "this sweep came back empty and the next
         * one is on its way"; this one means nothing more will happen until the
         * user asks again. It is the difference between a retry cadence and a
         * radio that has been switched off, and the caller has to be able to tell
         * them apart or it will simply start the whole search over.
         *
         * Only [BleTransport] sets it, and only when a first search has spent its
         * budget without anything answering. A scan is the expensive kind of
         * waiting - see [searchWithin].
         */
        val stopped: Boolean = false,
    ) : ConnectionState
    data class Connecting(val endpoint: String) : ConnectionState
    data class Connected(val endpoint: String) : ConnectionState
    data class Reconnecting(val detail: String) : ConnectionState
}

/**
 * A source of the daemon's JSONL stream, independent of *how* the bytes move.
 * Both [TcpTransport] (Wi-Fi) and [BleTransport] (Bluetooth LE) implement this and
 * emit identical [ServerMessage]s, so all message-folding / history / chart code is
 * transport-agnostic. The payload is the same newline-delimited JSONL either way.
 */
interface Transport {
    val state: StateFlow<ConnectionState>
    /** Lossy (drop-oldest) - a slow collector must never back-pressure the link. */
    val messages: SharedFlow<ServerMessage>
    /** Queue an already-framed command line (see [com.sensirion.sps30.wire.Commands]). */
    fun send(line: String)

    /**
     * Queue a line, waiting for room rather than dropping it.
     *
     * [send] is `trySend` onto a bounded queue, which is right for commands: a
     * button press that arrives while sixty-four frames are already waiting is a
     * button press nobody is still waiting on. It is wrong for a bulk transfer -
     * a session goes out as thousands of frames back to back, and a dropped one
     * is a hole in the data the user is restoring, arriving silently.
     *
     * Anything carrying data uses this. Everything else can keep dropping.
     */
    suspend fun sendAwait(line: String)

    /**
     * Queue a line and wait until it has actually been handed to the link, not
     * merely accepted by the queue.
     *
     * The difference is the whole of "the send back completes". [sendAwait]
     * returns when a bounded in-memory queue takes the line, and that queue sits
     * in front of a socket which sits in front of the kernel's own send buffer,
     * so "sent" could be seconds of the controller's work ahead of "arrived".
     * Measured on 2026-08-04: the app declared 121,249 readings sent while about
     * six thousand of them were still on the tablet, and the controller was
     * still importing for fifteen more seconds. Everything the app then timed
     * from that moment - the answer deadline, the silence the live-stream
     * watchdog measures - was timing from a moment that had not happened yet.
     *
     * Only the frame carrying `done` needs this: the ones before it are pure
     * throughput and the queue in front of them is what keeps the socket fed.
     *
     * The default is [sendAwait], which is right for a link with no queue of its
     * own in front of the radio ([BleTransport] writes on the caller's turn).
     */
    suspend fun sendAndAwaitWrite(line: String) = sendAwait(line)

    /**
     * Find the sensor, connect, and stream until the coroutine is cancelled.
     *
     * Returning normally means "this attempt is over" - nothing was found, or the
     * link dropped - and the caller is expected to call again after its own backoff.
     * [TcpTransport] returns on both, so that a mid-session loss re-enters at
     * [ConnectionState.Searching] and reports what a cold start reports.
     * [BleTransport] handles its own retry and so returns only on cancellation or
     * on giving up; either is fine, because the caller loops regardless.
     *
     * The one thing the caller must honour is [ConnectionState.NotFound.stopped]:
     * a transport in that state has finished for good and calling this again would
     * undo the decision it just made.
     */
    suspend fun run()

    /**
     * Force-release the underlying link *now*, so a transport switch tears the old
     * connection down promptly. Needed for the TCP path: `java.net.Socket` blocking
     * reads ignore thread interruption, so coroutine cancellation alone leaves the
     * socket half-open until the daemon drops it. The BLE path unwinds on cancel, so
     * it keeps the default no-op.
     */
    fun stop() {}
}

/**
 * Reassembles a byte stream of newline-delimited JSONL into [ServerMessage]s,
 * tolerating arbitrary chunk boundaries - a JSONL line may span several chunks and
 * one chunk may hold parts of two lines. Shared by TCP reads and BLE notifications
 * (BLE notifications are MTU-capped, so a reading routinely spans notifications).
 * Pure and unit-testable; keeps a partial trailing line for the next [feed].
 */
class JsonlReassembler(private val onMessage: (ServerMessage) -> Unit) {
    private val residual = ByteArrayOutputStream()

    /** True while discarding an over-long line, until its newline arrives. */
    private var overrun = false

    fun feed(bytes: ByteArray, len: Int = bytes.size) {
        var from = 0
        if (overrun) {
            // Nothing of an over-long line is kept: a prefix of a JSON object
            // is not a shorter JSON object, and the tail of one parses as
            // whatever the sender arranged. Skip to past its newline.
            val end = indexOfNewline(bytes, from, len)
            if (end < 0) return
            overrun = false
            from = end + 1
        }

        residual.write(bytes, from, len - from)
        val all = residual.toByteArray()
        var start = 0
        var i = 0
        while (i < all.size) {
            if (all[i] == NEWLINE) {
                val line = String(all, start, i - start, Charsets.UTF_8)
                Messages.parse(line)?.let(onMessage)
                start = i + 1
            }
            i++
        }
        residual.reset()
        if (start < all.size) {
            val kept = all.size - start
            if (kept > MAX_LINE_BYTES) {
                // A peer that streams bytes and never sends a newline otherwise
                // grows this without bound until the app dies - and, because
                // every chunk copies the whole partial line out again, does it
                // in time quadratic in the length of that one line. The
                // controller drops an over-long line the same way, in
                // `read_link`.
                CoreLog.warn(TAG, "dropping a line over $MAX_LINE_BYTES bytes")
                overrun = true
            } else {
                residual.write(all, start, kept)
            }
        }
    }

    fun reset() {
        residual.reset()
        overrun = false
    }

    private fun indexOfNewline(bytes: ByteArray, from: Int, len: Int): Int {
        for (i in from until len) if (bytes[i] == NEWLINE) return i
        return -1
    }

    companion object {
        private const val TAG = "JsonlReassembler"
        private const val NEWLINE = '\n'.code.toByte()

        /**
         * The longest single frame this will assemble.
         *
         * Not the controller's `WIRE_LINE_MAX` of 640: that bounds a *command*,
         * which travels the other way. The largest thing a host legitimately
         * sends is a `backup_chunk`, whose payload is `wire.BACKUP_CHUNK_BYTES`
         * (256 KiB) of base64 - about 342 KiB on the wire - so the cap has to
         * clear that with room, and 1 MiB does while still being a bound.
         */
        const val MAX_LINE_BYTES = 1024 * 1024
    }
}

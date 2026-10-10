package com.sensirion.sps30.net

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.mustNotBeDropped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Coroutine TCP client for the SPS30 daemon. Frames the newline-delimited JSONL
 * stream into [ServerMessage]s on [messages], publishes its progress to [state],
 * and accepts already-framed command lines via [send].
 *
 * [connectOnce] is **one attempt**: it connects, streams, and returns when the link
 * drops. Retrying is deliberately not its job - see [connectOnce] for why. Blocking
 * socket reads run under [runInterruptible] so cancellation promptly tears the
 * connection down.
 *
 * @param states where connection phases are published. [TcpTransport] passes its own
 *   flow so that discovery phases ([ConnectionState.Searching] / [ConnectionState.NotFound],
 *   which only the transport can see) and connection phases share one writer and one
 *   flow. Mirroring one into the other instead left the two racing, and the order the
 *   banner sees is the whole point of these states.
 */
class DaemonClient(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val states: MutableStateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.Idle),
    /**
     * Where the socket comes from. Injectable so a test can keep a handle on the
     * one this connects with: how far this device may run ahead of the
     * controller is a property of that socket ([SEND_BUFFER_BYTES]), and it is
     * worth asserting rather than assuming.
     */
    private val newSocket: () -> Socket = { ControllerSockets.create() },
) {
    companion object {
        /**
         * How far ahead of the controller this device is allowed to get.
         *
         * A socket accepts a write into the kernel's send buffer and returns;
         * the peer may read it seconds later, or never. With the buffer left at
         * the system default that gap is unbounded in any way this app can see,
         * and on 2026-08-04 it was measured at about 700 KiB: the app finished
         * handing over a 121,249-reading archive at 08:08:23 and the board was
         * still importing it, without a pause, until 08:08:38. Every conclusion
         * the app drew in between - the progress line reading 100%, the answer
         * deadline starting, the live-stream silence starting - was drawn about
         * a transfer that was still fifteen seconds from arriving.
         *
         * Sixteen kibibytes is about seventeen `put_file` frames, or a quarter
         * of a second of the controller's work at the ~500 rows/s it was
         * measured storing them at. The link is a LAN with a round trip of a few
         * milliseconds and the controller is slower than it by three orders of
         * magnitude, so there is no throughput to lose here: the bandwidth-delay
         * product of this connection is on the order of a kilobyte. What is
         * gained is that a write returning means the controller is nearly caught
         * up, which is what the rest of the app already believed.
         *
         * Linux treats this as a request and typically doubles it for overhead,
         * so the real bound is around a half second of the controller's work.
         */
        internal const val SEND_BUFFER_BYTES = 16 * 1024
    }

    val state: StateFlow<ConnectionState> = states.asStateFlow()

    // Live stream. Lossy on overflow (drop-oldest) - matches the daemon's own
    // per-client lossy reading queue; a slow UI must never stall the socket.
    private val _messages = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    /**
     * A line on its way out, and whoever is waiting to hear it has gone.
     *
     * [written] is completed once the bytes have been handed to the socket, so
     * [sendAndAwaitWrite] can tell "the queue took it" from "the link has it".
     * Null for everything that does not care, which is almost everything.
     */
    private class Outgoing(val line: String, val written: CompletableDeferred<Unit>?)

    private val outbound = Channel<Outgoing>(capacity = 64)

    /** How long to hold the socket waiting for room before giving up on a
     *  message that must not be dropped. Generous: losing one corrupts a file. */
    private val DROP_GUARD_MS = 10_000

    // The live socket, exposed only so [stop] can force-close it. A blocking
    // socket read does not honour thread interruption, so closing the socket is
    // the only way to promptly unblock [readFramesBlocking] on cancellation.
    @Volatile private var activeSocket: Socket? = null

    /** Queue an already-framed command line (see [com.sensirion.sps30.wire.Commands]). */
    fun send(line: String) {
        outbound.trySend(Outgoing(line, null))
    }

    /** Wait for room in the queue rather than dropping the line. See [Transport]. */
    suspend fun sendAwait(line: String) {
        outbound.send(Outgoing(line, null))
    }

    /**
     * Queue a line and wait until the socket has taken it. See [Transport].
     *
     * Throws whatever the write threw, so a link that died under a line the
     * caller was waiting on is a failure at the caller rather than a wait that
     * never ends.
     */
    suspend fun sendAndAwaitWrite(line: String) {
        val written = CompletableDeferred<Unit>()
        outbound.send(Outgoing(line, written))
        written.await()
    }

    /** Close the current socket now, unblocking the reader so the coroutine can finish. */
    fun stop() {
        runCatching { activeSocket?.close() }
    }

    /**
     * Connect to [host]:[port], stream until the link drops, then return.
     *
     * **One attempt, not a loop.** This used to retry internally, re-discovering
     * the daemon between attempts through a callback. Both of those belong a layer
     * up, and keeping them here is what made a mid-session loss invisible:
     *
     * - The re-discovery sweep ran **silently**. This class knows nothing about
     *   discovery, so it could not report [ConnectionState.Searching] while the
     *   sweep ran, nor [ConnectionState.NotFound] when it came back empty. The
     *   state stayed on [ConnectionState.Reconnecting] however long the sensor was
     *   gone - so a cold start said "no sensor found on the network" and named the
     *   problem, while losing the very same sensor mid-session said "reconnecting…"
     *   for ever and named nothing.
     * - When the sweep found nothing it kept the previous target and connected to
     *   it anyway. That published [ConnectionState.Connecting] for a host with no
     *   evidence of being there, which is exactly what clears the "not found"
     *   latch in the UI - so even a reported sweep would have blinked.
     * - Its backoff (0.5 s -> 8 s) sat inside the caller's own (1.5 s -> 20 s), and
     *   the outer one is the one that matters: every sweep spends a client slot on
     *   every candidate address, and retrying too eagerly fills the host. Two
     *   backoffs meant neither was the policy.
     *
     * Returning after one session hands all of that back to [TcpTransport], whose
     * `run()` is then the *single* path for both a cold start and a mid-session
     * loss: sweep, report, connect, stream. Identical states by construction rather
     * than by two implementations agreeing.
     */
    suspend fun connectOnce(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 4000,
    ) {
        val endpoint = "$host:$port"
        states.value = ConnectionState.Connecting(endpoint)
        // On Android, [ControllerSockets] hands back a socket tied to the Wi-Fi
        // network from creation. A full-tunnel VPN would otherwise swallow LAN
        // traffic, and on a network with no internet Android would route us out
        // over mobile data instead.
        val socket = newSocket()
        // Before connect, which is when a socket takes its buffer sizes.
        // Best-effort: a kernel that declines leaves the default in place, and
        // the transfer is still correct, only less honest about its progress.
        runCatching { socket.sendBufferSize = SEND_BUFFER_BYTES }
        activeSocket = socket
        try {
            withContext(io) {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
            socket.soTimeout = 0
            states.value = ConnectionState.Connected(endpoint)
            streamUntilClosed(socket)
            // Clean EOF (server closed).
            states.value = ConnectionState.Reconnecting("connection closed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            states.value = ConnectionState.Reconnecting(e.message ?: "connection lost")
        } finally {
            runCatching { socket.close() }
            // Nothing live to force-close any more; [stop] must not reach for a
            // socket this attempt has finished with.
            activeSocket = null
        }
    }

    /** Runs one connected session: a reader and a writer, until either ends. */
    private suspend fun streamUntilClosed(socket: Socket) = coroutineScope {
        val writer = launch(io) { pumpOutbound(socket) }
        try {
            runInterruptible(io) { readFramesBlocking(socket) }
        } finally {
            writer.cancel()
        }
    }

    /** Blocking read loop: byte-level newline framing via the shared reassembler. */
    private fun readFramesBlocking(socket: Socket) {
        val input = socket.getInputStream()
        val chunk = ByteArray(4096)
        val reassembler = JsonlReassembler { message ->
            if (!_messages.tryEmit(message) && message.mustNotBeDropped()) {
                // File content cannot be dropped the way a stale reading can.
                // This runs on the blocking reader thread, so waiting here simply
                // applies backpressure to the socket - which is the correct
                // response to a collector that has fallen behind.
                var waited = 0
                while (!_messages.tryEmit(message) && waited < DROP_GUARD_MS) {
                    Thread.sleep(2)
                    waited += 2
                }
            }
        }
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break // EOF
            reassembler.feed(chunk, n)
        }
    }

    /**
     * Drains queued command lines to the socket until cancelled or the socket dies.
     *
     * A line whose sender is waiting on it is told either way. A sender whose
     * line is still queued when this ends is *not* told, because the queue
     * survives the connection and that line will go out over the next one; it is
     * unblocked by the cancellation of the work it belongs to, which is the same
     * thing that has always unblocked a [sendAwait] against a queue with no
     * writer behind it. See `abandonLinkBoundWork` in `MonitorSession`.
     */
    private suspend fun pumpOutbound(socket: Socket) {
        val output = socket.getOutputStream()
        for (out in outbound) {
            try {
                runInterruptible(io) {
                    output.write(out.line.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (e: Throwable) {
                out.written?.completeExceptionally(e)
                throw e
            }
            out.written?.complete(Unit)
        }
    }
}

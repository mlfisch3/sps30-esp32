package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Commands
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Integration tests: a real loopback TCP server stands in for the daemon. */
class DaemonClientTest {

    /**
     * Wait for [what] to turn up in a collected list, rather than asserting the
     * instant the call under test returns.
     *
     * The collector runs on its own dispatcher, so "the state was published" and
     * "the collector has been resumed with it" are different moments; asserting on
     * the first and reading the second is a race, and it failed about one run in
     * four. Waiting is not a sleep: it returns as soon as the value arrives.
     */
    private suspend fun assertSeen(
        what: String,
        seen: List<ConnectionState>,
        timeoutMs: Long = 2000,
        predicate: (ConnectionState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (seen.any(predicate)) return
            delay(10)
        }
        fail("$what was never published; saw $seen")
    }

    private val hello =
        """{"type":"hello","product":"00080000","serial":"E316","firmware":"2.1",""" +
            """"state":"measuring","csv":"c.csv","index":"0000001"}"""
    private val reading =
        """{"type":"reading","csv":"c.csv","index":"0000002","timestamp":"t","pm2_5":1.5}"""

    @Test
    fun connectsReachesConnectedAndStreamsFrames() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = DaemonClient(io = Dispatchers.IO)
        val received = CopyOnWriteArrayList<ServerMessage>()

        val collector = launch(Dispatchers.IO) { client.messages.collect { received.add(it) } }
        delay(150) // ensure the SharedFlow (replay=0) is subscribed before frames fly

        val serverJob = launch(Dispatchers.IO) {
            server.use {
                val conn = it.accept()
                conn.getOutputStream().apply {
                    write((hello + "\n").toByteArray())
                    write((reading + "\n").toByteArray())
                    flush()
                }
                Thread.sleep(400) // hold the session open
                conn.close()
            }
        }

        val clientJob = launch { client.connectOnce("127.0.0.1", server.localPort) }

        withTimeout(5000) {
            while (received.size < 2) delay(20)
        }

        assertTrue("state should reach Connected",
            client.state.value is ConnectionState.Connected)
        assertTrue("hello parsed", received.any { it is ServerMessage.Hello })
        val r = received.filterIsInstance<ServerMessage.Reading>().firstOrNull()
        assertTrue("reading parsed", r != null)
        assertEquals(1.5, r!!.values["pm2_5"]!!, 1e-9)

        clientJob.cancel(); collector.cancel(); serverJob.cancel()
        server.close()
    }

    @Test
    fun sendDeliversFramedCommandToServer() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = DaemonClient(io = Dispatchers.IO)
        val gotLine = CopyOnWriteArrayList<String>()

        val serverJob = launch(Dispatchers.IO) {
            server.use {
                val conn = it.accept()
                // send a hello so the client is happily connected
                conn.getOutputStream().apply { write((hello + "\n").toByteArray()); flush() }
                val line = conn.getInputStream().bufferedReader().readLine()
                if (line != null) gotLine.add(line)
                conn.close()
            }
        }

        val clientJob = launch { client.connectOnce("127.0.0.1", server.localPort) }
        // Queued before/around connect; the outbound channel buffers until connected.
        delay(200)
        client.send(Commands.pause())

        withTimeout(5000) {
            while (gotLine.isEmpty()) delay(20)
        }
        assertEquals("""{"cmd":"pause"}""", gotLine.first())

        clientJob.cancel(); serverJob.cancel()
        server.close()
    }

    /**
     * The drop must surface, not be swallowed. This used to loop internally and
     * silently re-sweep, so a mid-session loss never reached the transport and the
     * UI sat on "reconnecting…" for as long as the sensor stayed away.
     */
    @Test
    fun returnsWhenTheServerClosesTheConnection() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = DaemonClient(io = Dispatchers.IO)

        val serverJob = launch(Dispatchers.IO) {
            server.use {
                val conn = it.accept()
                conn.getOutputStream().apply { write((hello + "\n").toByteArray()); flush() }
                Thread.sleep(200)
                conn.close() // mid-session loss: a clean close from the other end
            }
        }

        withTimeout(5000) { client.connectOnce("127.0.0.1", server.localPort) }

        assertTrue(
            "a dropped session must end on Reconnecting, not stay Connected",
            client.state.value is ConnectionState.Reconnecting,
        )
        serverJob.cancel()
        server.close()
    }

    /**
     * A refused connect ends the attempt too, rather than retrying out of sight -
     * and it publishes Connecting then Reconnecting into the flow the caller
     * supplied, which is how [TcpTransport] gets one flow for both halves of a
     * connection's life. The dead port is what makes the intermediate state
     * observable: [kotlinx.coroutines.flow.StateFlow] conflates, so a phase that
     * lasts microseconds may never be collected.
     */
    @Test
    fun returnsWhenTheHostIsNotThere() = runBlocking {
        // Bind then close, so the port is almost certainly free and unlistened.
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val shared = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val client = DaemonClient(io = Dispatchers.IO, states = shared)
        val seen = CopyOnWriteArrayList<ConnectionState>()
        val watcher = launch(Dispatchers.IO) { shared.collect { seen.add(it) } }
        delay(100)

        withTimeout(10_000) { client.connectOnce("127.0.0.1", port, connectTimeoutMs = 500) }

        assertSeen("Connecting", seen) { it is ConnectionState.Connecting }
        assertTrue(
            "a failed connect must end on Reconnecting",
            client.state.value is ConnectionState.Reconnecting,
        )
        watcher.cancel()
    }

    // --- what "sent" means ----------------------------------------------------
    //
    // A socket takes a write into the kernel's send buffer and returns; the peer
    // may read it seconds later. With the buffer left at the system default that
    // gap is not small and is not visible from here, and the app drew every one
    // of its conclusions from the near side of it. Measured on the board on
    // 2026-08-04: the app reported a 121,249-reading archive fully sent at
    // 08:08:23 and the controller, which had never stopped importing, answered
    // it at 08:08:38 - so "sent" was fifteen seconds of the controller's work
    // early, and the app spent those fifteen seconds counting a silence the
    // protocol requires and then closing the socket thirty milliseconds after
    // the answer was written to it.
    //
    // The reference for what this should look like is the plain-socket client
    // that established the diagnosis (`replay_archive.py`): it sends the same
    // frames, marks the last one `done`, and gets its answer 0.7 s later,
    // because a blocking write on a modest buffer returns roughly when the peer
    // has caught up.

    @Test
    fun aFrameIsNotCalledSentUntilTheSocketHasTakenIt() = runBlocking {
        // A peer with a small window, which is what an ESP32 is: it reads one
        // 2 KiB line at a time and stores it before reading the next.
        val server = ServerSocket()
        server.receiveBufferSize = 2048
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val client = DaemonClient(io = Dispatchers.IO)
        val accepted = CompletableDeferred<java.net.Socket>()
        // The helpers live outside this coroutine's own job so that a failed
        // assertion ends the test rather than leaving `runBlocking` waiting on a
        // sender that is blocked on a peer that will never read. A test that
        // wedges instead of failing is worse than no test.
        val helpers = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            helpers.launch {
                val conn = server.accept()
                conn.getOutputStream().apply { write((hello + "\n").toByteArray()); flush() }
                accepted.complete(conn)
                // And then it reads nothing at all, which is a controller busy
                // storing what it already has.
            }
            helpers.launch { client.connectOnce("127.0.0.1", server.localPort) }
            val conn = withTimeout(5000) { accepted.await() }

            // Frames of the size the controller's own inbound buffer is cut for.
            val filler = Commands.putFile("SPS30-20260802_205313.csv", "x".repeat(1_900), 0, false)
            val done = Commands.putFile("SPS30-20260802_205313.csv", "last", 1, true)
            // Deliberately fewer than the outbound queue holds, so that "the
            // queue took it" would have returned at once and only "the socket
            // took it" can still be waiting. About a hundred kilobytes of frames
            // against a few tens of kilobytes of buffer, so most of it cannot
            // have gone anywhere.
            val queued = 50
            assertTrue("the queue would have blocked on its own", queued < 64)

            val returned = CompletableDeferred<Unit>()
            helpers.launch {
                repeat(queued) { client.sendAwait(filler) }
                client.sendAndAwaitWrite(done)
                returned.complete(Unit)
            }

            delay(1500)
            assertFalse(
                "the last frame was called sent while the peer had read nothing of the transfer",
                returned.isCompleted,
            )

            // Now the controller catches up, and the frame lands - last, and whole.
            val arrived = CompletableDeferred<String>()
            helpers.launch {
                val lines = conn.getInputStream().bufferedReader()
                var last = ""
                while (true) {
                    last = lines.readLine() ?: break
                    if (last == done.trim()) break
                }
                arrived.complete(last)
            }
            withTimeout(30_000) { returned.await() }
            assertEquals(
                "the frame carrying `done` did not reach the wire",
                done.trim(),
                withTimeout(5000) { arrived.await() },
            )
            conn.close()
        } finally {
            helpers.cancel()
            server.close()
        }
        Unit
    }

    /**
     * How far ahead of the controller this device is allowed to get.
     *
     * Stated against the socket because that is where it lives: the queue in
     * front of it is sixty-four lines and always was, and the buffer behind it
     * was the part with no bound. A default send buffer on this machine is
     * megabytes, which at the ~500 rows/s the board was measured storing at is
     * most of a minute of work this device would call sent.
     */
    @Test
    fun theSocketCannotHoldMoreThanTheControllerCanSoonRead() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        // Recorded as it is set rather than read back afterwards: the socket is
        // closed by the time an attempt ends, and a closed socket has no buffer
        // to ask about.
        val effective = CompletableDeferred<Int>()
        val client = DaemonClient(
            io = Dispatchers.IO,
            newSocket = {
                object : java.net.Socket() {
                    override fun setSendBufferSize(size: Int) {
                        super.setSendBufferSize(size)
                        effective.complete(sendBufferSize)
                    }
                }
            },
        )
        val serverJob = launch(Dispatchers.IO) {
            server.use {
                val conn = it.accept()
                conn.getOutputStream().apply { write((hello + "\n").toByteArray()); flush() }
                Thread.sleep(300)
                conn.close()
            }
        }
        withTimeout(5000) { client.connectOnce("127.0.0.1", server.localPort) }

        val used = withTimeout(5000) { effective.await() }
        val untouched = java.net.Socket().use { it.sendBufferSize }
        // The kernel rounds this up, typically by doubling it for its own
        // bookkeeping, so the assertion is on the order of magnitude rather than
        // the number. Four times 16 KiB is about a second of the controller's
        // work at the rate the board was measured storing rows.
        assertTrue(
            "the send buffer is $used bytes (this machine's default is $untouched), so this " +
                "device can run seconds of the controller's work ahead of the wire and call " +
                "it sent",
            used <= 4 * DaemonClient.SEND_BUFFER_BYTES,
        )
        serverJob.cancel()
    }

    /** The transport supplies the flow, so discovery and connection phases share one. */
    @Test
    fun publishesConnectionPhasesIntoTheSuppliedFlow() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val shared = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val client = DaemonClient(io = Dispatchers.IO, states = shared)
        val seen = CopyOnWriteArrayList<ConnectionState>()
        val watcher = launch(Dispatchers.IO) { shared.collect { seen.add(it) } }
        delay(100)

        val serverJob = launch(Dispatchers.IO) {
            server.use {
                val conn = it.accept()
                conn.getOutputStream().apply { write((hello + "\n").toByteArray()); flush() }
                Thread.sleep(200) // hold, so Connected cannot be conflated away
                conn.close()
            }
        }

        withTimeout(5000) { client.connectOnce("127.0.0.1", server.localPort) }

        assertSeen("Connected", seen) { it is ConnectionState.Connected }
        assertSeen("Reconnecting", seen) { it is ConnectionState.Reconnecting }
        watcher.cancel(); serverJob.cancel()
        server.close()
    }
}

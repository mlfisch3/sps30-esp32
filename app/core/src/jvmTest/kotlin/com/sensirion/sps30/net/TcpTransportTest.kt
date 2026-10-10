package com.sensirion.sps30.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The states a whole attempt goes through, with LAN discovery stubbed and a real
 * loopback server standing in for the daemon.
 *
 * These are the regression tests for the mid-session loss: the point is not that any
 * one state is right, it is that the *sequence* after a drop is the same sequence a
 * cold start produces. It could not be before - the re-sweep lived inside
 * [DaemonClient]'s own retry loop, which knows nothing about discovery and so
 * reported nothing while it ran.
 *
 * Discovery is stubbed at [TcpTransport]'s own seam rather than at [LanDiscovery]'s,
 * so these say nothing about which of the two paths found the host - which is the
 * property that matters here: a controller found by listening and one found by
 * sweeping reach this class as the same [Discovery.Result], and every state below
 * follows from that alone. [LanDiscoveryTest] is where the two paths are told apart.
 */
class TcpTransportTest {

    private val hello =
        """{"type":"hello","product":"00080000","serial":"E316","firmware":"2.1",""" +
            """"state":"measuring","csv":"c.csv","index":"0000001"}"""

    /**
     * Wait for [what] to turn up in a collected list, rather than asserting the
     * instant [TcpTransport.run] returns. The collector runs on its own dispatcher,
     * so "published" and "collected" are different moments and asserting across the
     * two is a race. It returns as soon as the value arrives; it is not a sleep.
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

    /** A daemon that says hello, holds the line briefly, then vanishes. */
    private fun oneSession(server: ServerSocket, holdMs: Long = 200) = server.accept().use { conn ->
        conn.getOutputStream().apply { write((hello + "\n").toByteArray()); flush() }
        Thread.sleep(holdMs)
    }

    /**
     * Discovery that takes a moment, as a real sweep takes seconds.
     *
     * Not padding: [kotlinx.coroutines.flow.StateFlow] conflates, so a Searching that
     * is replaced in the same instant is never delivered to a collector. The state
     * these tests are about is precisely the one the user stares at while discovery
     * runs, so the stub has to occupy time the way the real one does - including
     * announcing the phase it is in, which is what the banner shows.
     */
    private suspend fun searching(
        onPhase: (LanDiscovery.Phase) -> Unit,
        result: () -> Discovery.Result,
    ): Discovery.Result {
        onPhase(LanDiscovery.Phase.LISTENING)
        delay(30)
        onPhase(LanDiscovery.Phase.SWEEPING)
        delay(30)
        return result()
    }

    @Test
    fun coldStartWithNothingOnTheNetworkReportsSearchingThenNotFound() = runBlocking {
        val transport =
            TcpTransport(port = 8730, locate = { _, p -> searching(p) { Discovery.Result.None } })
        val seen = CopyOnWriteArrayList<ConnectionState>()
        val watcher = launch(Dispatchers.IO) { transport.state.collect { seen.add(it) } }
        delay(100)

        withTimeout(5000) { transport.run() }

        assertSeen("Searching", seen) { it is ConnectionState.Searching }
        val last = transport.state.value
        assertTrue("ends on NotFound", last is ConnectionState.NotFound)
        assertEquals(
            "no sensor found on the network",
            (last as ConnectionState.NotFound).detail,
        )
        watcher.cancel()
    }

    /**
     * The bug, stated as a test. A session that has been established and then lost
     * must, on the next attempt, produce exactly what the cold start above produces.
     */
    @Test
    fun aLostSessionThenReportsSearchingAndNotFoundJustLikeAColdStart() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        var sensorPresent = true
        val transport = TcpTransport(
            port = server.localPort,
            locate = { _, p ->
                searching(p) {
                    if (sensorPresent) Discovery.Result.Found("127.0.0.1") else Discovery.Result.None
                }
            },
        )
        val seen = CopyOnWriteArrayList<ConnectionState>()
        val watcher = launch(Dispatchers.IO) { transport.state.collect { seen.add(it) } }
        delay(100)

        val serverJob = launch(Dispatchers.IO) { oneSession(server) }
        // First attempt: connects, streams, and returns when the daemon drops it.
        withTimeout(5000) { transport.run() }
        assertSeen("Connected", seen) { it is ConnectionState.Connected }
        assertTrue("drop reported", transport.state.value is ConnectionState.Reconnecting)

        // The sensor is now gone. The next attempt is the one that used to be silent.
        sensorPresent = false
        seen.clear()
        withTimeout(5000) { transport.run() }

        assertSeen("Searching on the re-sweep", seen) { it is ConnectionState.Searching }
        assertTrue("absence reported", transport.state.value is ConnectionState.NotFound)
        assertEquals(
            "no sensor found on the network",
            (transport.state.value as ConnectionState.NotFound).detail,
        )
        watcher.cancel(); serverJob.cancel()
        server.close()
    }

    /**
     * And it comes back on its own. The caller retries; nothing here has to remember
     * that a session was ever lost, which is why the gap-filling `hello` -> `get_file`
     * backfill runs unchanged on the second connect.
     */
    @Test
    fun recoversWhenTheSensorReturns() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        var sensorPresent = true
        val transport = TcpTransport(
            port = server.localPort,
            locate = { _, p ->
                searching(p) {
                    if (sensorPresent) Discovery.Result.Found("127.0.0.1") else Discovery.Result.None
                }
            },
        )
        val connects = CopyOnWriteArrayList<ConnectionState.Connected>()
        val watcher = launch(Dispatchers.IO) {
            transport.state.collect { if (it is ConnectionState.Connected) connects.add(it) }
        }
        delay(100)

        var serverJob = launch(Dispatchers.IO) { oneSession(server) }
        withTimeout(5000) { transport.run() }
        serverJob.cancel()

        // Away for one attempt...
        sensorPresent = false
        withTimeout(5000) { transport.run() }
        assertTrue("reported absent", transport.state.value is ConnectionState.NotFound)

        // ...and back for the next, with no intervention.
        sensorPresent = true
        serverJob = launch(Dispatchers.IO) { oneSession(server) }
        withTimeout(5000) { transport.run() }

        withTimeout(2000) { while (connects.size < 2) delay(10) }
        assertEquals("connected twice: before the loss and after it", 2, connects.size)
        watcher.cancel(); serverJob.cancel()
        server.close()
    }

    /** A host that is there but full is said so in its own words, on any attempt. */
    @Test
    fun aFullHostIsReportedAsFullRatherThanAbsent() = runBlocking {
        val transport = TcpTransport(
            port = 8730,
            locate = { _, p ->
                searching(p) {
                    Discovery.Result.Busy("192.168.1.9", "sensor is at capacity (8 clients)")
                }
            },
        )
        withTimeout(5000) { transport.run() }

        val st = transport.state.value
        assertTrue("ends on NotFound", st is ConnectionState.NotFound)
        st as ConnectionState.NotFound
        assertTrue("names the host and the reason", st.detail.contains("192.168.1.9"))
        assertTrue("carries the transport's own advice", st.advice != null)
    }

    /**
     * Both phases of discovery reach the screen, and they say different things.
     *
     * Worth a test rather than a glance because of what the difference means to
     * whoever is holding the phone: listening is over in a fraction of a second, and
     * a sweep takes several. One unchanging "Searching…" across both would look
     * frozen for the whole of the slow one, and would hide the fact that the sensor
     * did not announce itself - which is the visible symptom of firmware old enough
     * to need the fallback.
     */
    @Test
    fun theBannerNamesWhichPhaseOfDiscoveryIsRunning() = runBlocking {
        val transport =
            TcpTransport(port = 8730, locate = { _, p -> searching(p) { Discovery.Result.None } })
        val seen = CopyOnWriteArrayList<ConnectionState>()
        val watcher = launch(Dispatchers.IO) { transport.state.collect { seen.add(it) } }
        delay(100)

        withTimeout(5000) { transport.run() }

        assertSeen("the listening phase", seen) {
            it is ConnectionState.Searching && it.detail == "listening for the sensor"
        }
        assertSeen("the sweeping phase", seen) {
            it is ConnectionState.Searching && it.detail == "sweeping the local network"
        }
        watcher.cancel()
    }
}

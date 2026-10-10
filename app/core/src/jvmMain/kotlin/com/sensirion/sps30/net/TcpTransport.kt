package com.sensirion.sps30.net

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wi-Fi transport: VPN-aware LAN discovery of the daemon, then JSONL over TCP.
 * A thin adapter over [DaemonClient] + [LanDiscovery].
 *
 * Discovery is reported as [ConnectionState.Searching] / [ConnectionState.NotFound]
 * rather than left silent, because it is the phase that fails when the sensor host
 * is simply not switched on - by far the most common thing to go wrong - and it can
 * run for several seconds per attempt. It is reported in the two phases
 * [LanDiscovery] actually has, so "listening" and "sweeping" are distinguishable on
 * screen: they take very different amounts of time, and which one is running says
 * whether the controller is announcing itself.
 *
 * [run] is **one attempt**: locate, then stream until the link drops. The caller
 * ([com.sensirion.sps30.ui.MonitorSession]) owns the retry cadence, and calls this
 * again. That is deliberate, and it is what makes losing the sensor mid-session
 * report what a cold start reports: there is only one path through this class, so a
 * drop re-enters at [ConnectionState.Searching] and ends at [ConnectionState.NotFound]
 * exactly as a first launch with nothing switched on does. It used to be two paths -
 * the discovery here, and a silent re-sweep buried in the client's own retry loop -
 * and the buried one reported nothing at all, so a sensor that went away mid-session
 * left "reconnecting…" on screen indefinitely.
 *
 * Recovery needs nothing extra: when discovery finds the sensor again the session
 * begins with a `hello`, and the ordinary hello -> `get_file since_index` backfill
 * collects everything recorded during the silence.
 */
class TcpTransport(
    private val port: Int = Wire.DEFAULT_PORT,
    /**
     * How this instance listens for controllers announcing themselves, or null to
     * use the fallback sweep alone.
     *
     * Null is what a caller with no [android.content.Context] gets, and it is
     * exactly the behaviour this class had before mDNS existed - which is why
     * nothing above had to change to accommodate it.
     */
    browser: ServiceBrowser? = null,
    /** Injectable so the state sequence can be tested without a LAN. See [LanDiscovery]. */
    private val locate: suspend (Int, (LanDiscovery.Phase) -> Unit) -> Discovery.Result =
        { p, onPhase ->
            LanDiscovery.locate(
                port = p,
                browser = browser,
                sweepFallback = { it -> Discovery.sweep(it, dispatcher = Dispatchers.IO) },
                onPhase = onPhase,
            )
        },
) : Transport {
    // One flow, one writer at a time: this class publishes the discovery phases and
    // hands the same flow to the client for the connection phases. See [DaemonClient].
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val client = DaemonClient(states = _state)

    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override val messages: SharedFlow<ServerMessage> get() = client.messages
    override fun send(line: String) = client.send(line)
    override suspend fun sendAwait(line: String) = client.sendAwait(line)
    override suspend fun sendAndAwaitWrite(line: String) = client.sendAndAwaitWrite(line)
    override fun stop() = client.stop()

    override suspend fun run() {
        // Return rather than throw when there is nothing to connect to: a missing
        // host is an ordinary, expected condition with its own banner, not a
        // connection that broke. Throwing made the caller overwrite this with a
        // generic "reconnecting" for a host it had never once reached.
        val host = findHost() ?: return
        client.connectOnce(host, port)
    }

    /**
     * One round of discovery, reported as it happens; the host, or null if there is
     * nothing to connect to.
     *
     * Discovery is what stands between a drop and a reconnect, so the host is always
     * re-found rather than assumed: the controller is on DHCP and may come back on a
     * different address, and returning null rather than falling back on the previous
     * one keeps [ConnectionState.Connecting] meaning "a host answered `hello` just
     * now" - which is what the UI's "not found" latch relies on to clear honestly.
     */
    private suspend fun findHost(): String? {
        _state.value = ConnectionState.Searching(phaseDetail(LanDiscovery.Phase.LISTENING))
        val found = locate(port) { phase ->
            // Each phase as it begins. The words differ because the two take very
            // different amounts of time - a fraction of a second against several
            // seconds - and a screen that said one thing throughout would look
            // stuck for the whole of the second one.
            _state.value = ConnectionState.Searching(phaseDetail(phase))
        }
        return when (found) {
            is Discovery.Result.Found -> found.host
            is Discovery.Result.Busy -> {
                // The host's own words, so its limit is reported accurately whether
                // it is the Pi (8 clients) or the ESP32 (fewer).
                val why = found.message.ifBlank { "the sensor is full" }
                _state.value = ConnectionState.NotFound(
                    "$why (${found.host})",
                    "Close the app on another device to free a slot.",
                )
                null
            }
            Discovery.Result.None -> {
                _state.value = ConnectionState.NotFound("no sensor found on the network")
                null
            }
        }
    }

    /** What the banner says while a phase of [LanDiscovery] is running. */
    private fun phaseDetail(phase: LanDiscovery.Phase): String = when (phase) {
        LanDiscovery.Phase.LISTENING -> "listening for the sensor"
        LanDiscovery.Phase.SWEEPING -> "sweeping the local network"
    }
}

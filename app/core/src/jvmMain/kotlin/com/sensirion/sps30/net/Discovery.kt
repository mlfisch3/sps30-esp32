package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * **The fallback path.** VPN-aware LAN discovery of the SPS30 daemon by sweeping
 * every address - a Kotlin port of the fixed `local_subnet_bases`/`discover`/
 * `verify_daemon` from the Termux launcher.
 *
 * ## Read this before adding to this file
 *
 * This is no longer how the app expects to find a controller. [LanDiscovery]
 * listens for the controller's own mDNS advertisement first and only comes here
 * when that finds nothing, and this file exists now for exactly one reason: **a
 * controller running firmware without mDNS in it is not discoverable any other
 * way**, and there is one in the field. When there is not, this file and its
 * whole call graph are deleted and [LanDiscovery.sweepFallback] goes with them.
 *
 * It is also on a clock that is not this project's. Sweeping a subnet with TCP
 * connects is precisely the pattern Android's Local Network Protections gate -
 * opt-in at target 36, which this app now targets, and mandatory at 37 - and
 * precisely what Apple's local-network permission is written to discourage in
 * favour of declared Bonjour service types. See `docs/PRD.md`, and `AGENTS.md`
 * under "What the app declares to Google Play".
 *
 * So: fix what is broken here, and add nothing. New behaviour belongs on the
 * mDNS path.
 *
 * ## What it does, and why it is shaped like this
 *
 * The Pi IP is DHCP and `.local` name *resolution* is unreliable on Android
 * (which is not the same thing as service *browsing*, which [NsdServiceBrowser]
 * uses and which works). And the S24 has a VPN active, so the default route (to
 * a public address) is a tunnel subnet while the real Wi-Fi LAN is only
 * reachable for private destinations. We therefore probe one representative
 * address in each RFC1918 block plus the default route, collect *every* distinct
 * local /24, and sweep them all, verifying each candidate genuinely speaks the
 * daemon `hello`.
 *
 * [probe] and [Probe] are the exception to "add nothing": they are used by the
 * mDNS path too, because verifying that a candidate really answers `hello` is
 * the same job whichever way the candidate was found.
 */
object Discovery {

    /** One anchor per RFC1918 block, plus a public address for the default route. */
    val PROBE_TARGETS: List<String> =
        listOf("192.168.0.1", "10.0.0.1", "172.16.0.1", "8.8.8.8")

    /**
     * Local source IPv4 the OS would use to reach [dst], or null. Uses a UDP
     * `connect` - no packets are actually sent. Never throws.
     */
    fun sourceIpFor(dst: String, timeoutMs: Int = 500): String? {
        return try {
            DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(dst, 80))
                val addr = sock.localAddress?.hostAddress
                // A disconnected/dummy socket can report the wildcard address.
                if (addr == null || addr == "0.0.0.0") null else addr
            }
        } catch (e: Exception) {
            null
        }
    }

    /** "a.b.c.d" -> "a.b.c." ; null for anything malformed. */
    fun baseOf(ip: String?): String? {
        if (ip == null) return null
        val parts = ip.split(".")
        if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return null
        return parts.take(3).joinToString(".") + "."
    }

    /** True if [base] ("a.b.c.") sits in an RFC1918 private range. */
    fun isPrivateBase(base: String): Boolean {
        val parts = base.trimEnd('.').split(".")
        val o1 = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val o2 = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return when {
            o1 == 10 -> true
            o1 == 192 && o2 == 168 -> true
            o1 == 172 && o2 in 16..31 -> true
            else -> false
        }
    }

    /**
     * The /24 bases of this device's own up, non-loopback IPv4 interfaces.
     *
     * This is the primary, routing-independent source on Android: unlike the
     * `ip` command (blocked in Termux) `NetworkInterface` works in an app and
     * lists the Wi-Fi and any VPN interface directly. It also sidesteps a
     * Java/Android quirk where `DatagramSocket.getLocalAddress()` after connect
     * returns the wildcard, which left [sourceIpFor] blind.
     */
    fun interfaceBases(): List<String> {
        val bases = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                        baseOf(addr.hostAddress)?.let { bases.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // fall through to whatever the caller's probe can find
        }
        return bases.toList()
    }

    /**
     * Ordered, de-duplicated list of this device's local /24 bases. Private-LAN
     * bases come before any public/tunnel base, since the daemon is on a private
     * LAN. Draws from interface enumeration first (robust on Android), then
     * augments with the routing probe. Both sources are injectable for tests.
     */
    fun localSubnetBases(
        interfaces: () -> List<String> = { interfaceBases() },
        probe: (String) -> String? = { sourceIpFor(it) },
    ): List<String> {
        val private = LinkedHashSet<String>()
        val public = LinkedHashSet<String>()
        fun consider(base: String?) {
            if (base == null) return
            if (isPrivateBase(base)) private.add(base) else public.add(base)
        }
        for (base in interfaces()) consider(base)
        for (dst in PROBE_TARGETS) consider(baseOf(probe(dst)))
        return (private + public).toList()
    }

    /** What one probe of a candidate address concluded. */
    enum class Probe {
        /** Answered with `hello`: the sensor host, and it has room. */
        DAEMON,

        /**
         * The sensor host, but it refused another viewer.
         *
         * This is emphatically **not** "nothing there", and treating it as such was
         * a self-reinforcing failure: each sweep spends a client slot on every
         * candidate, so once the host filled up, every later sweep saw this frame,
         * concluded "no sensor found", and retried - which kept the slots full.
         * Observed on the tablet against the live Pi, which serves 8 clients; the
         * ESP32 port is tighter still (lwIP sockets), so this matters more there.
         */
        AT_CAPACITY,

        /** Not the sensor host - no answer, or something else entirely. */
        NONE,
    }

    /** Where a sweep got to. */
    sealed interface Result {
        data class Found(val host: String) : Result
        /** The host was located but would not take another viewer. */
        data class Busy(val host: String, val message: String) : Result
        data object None : Result
    }

    /**
     * Classify `host:port`. Blocking; never throws. Reuse of [Messages.parse]
     * guarantees this matches how the app reads real frames.
     */
    fun probe(host: String, port: Int, timeoutMs: Long = 1000): Pair<Probe, String> {
        return try {
            ControllerSockets.create().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                sock.soTimeout = timeoutMs.toInt()
                val input = sock.getInputStream()
                val chunk = ByteArray(4096)
                val buf = StringBuilder()
                while (buf.indexOf("\n") < 0 && buf.length < 65536) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    buf.append(String(chunk, 0, n, Charsets.UTF_8))
                }
                val nl = buf.indexOf("\n")
                val line = if (nl >= 0) buf.substring(0, nl) else buf.toString()
                when (val msg = Messages.parse(line)) {
                    is ServerMessage.Hello -> Probe.DAEMON to ""
                    is ServerMessage.ErrorMsg ->
                        if (msg.atCapacity) Probe.AT_CAPACITY to msg.message else Probe.NONE to ""
                    else -> Probe.NONE to ""
                }
            }
        } catch (e: Exception) {
            Probe.NONE to ""
        }
    }

    /**
     * True only if `host:port` is the SPS30 daemon and has room for another viewer.
     * Kept for callers that only need the yes/no; [probe] carries the full verdict.
     */
    fun verifyDaemon(host: String, port: Int, timeoutMs: Long = 1000): Boolean =
        probe(host, port, timeoutMs).first == Probe.DAEMON

    /**
     * Sweep the local /24 subnet(s) concurrently; return the first confirmed
     * daemon, or null. With [bases] given, sweeps exactly those; otherwise uses
     * [basesProvider] (default: [localSubnetBases]). All bases are swept in one
     * bounded pool so a VPN default route cannot hide the real Wi-Fi LAN.
     */
    suspend fun discover(
        port: Int,
        bases: List<String>? = null,
        verify: (String, Int, Long) -> Boolean = ::verifyDaemon,
        timeoutMs: Long = 1000,
        workers: Int = 64,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        basesProvider: () -> List<String> = { localSubnetBases() },
    ): String? =
        (
            sweep(
                port = port,
                bases = bases,
                probe = { h, p, t -> if (verify(h, p, t)) Probe.DAEMON to "" else Probe.NONE to "" },
                timeoutMs = timeoutMs,
                workers = workers,
                dispatcher = dispatcher,
                basesProvider = basesProvider,
            ) as? Result.Found
            )?.host

    /**
     * Sweep as [discover] does, but keep the distinction between "nothing there" and
     * "there, and full". A host that answers at capacity does not end the sweep - a
     * second, free host would be the better answer - but it is remembered so the
     * caller can say which of the two happened.
     */
    suspend fun sweep(
        port: Int,
        bases: List<String>? = null,
        probe: (String, Int, Long) -> Pair<Probe, String> = ::probe,
        timeoutMs: Long = 1000,
        workers: Int = 64,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        basesProvider: () -> List<String> = { localSubnetBases() },
    ): Result {
        val effectiveBases = bases ?: basesProvider()
        if (effectiveBases.isEmpty()) return Result.None
        val hosts = effectiveBases.flatMap { base -> (1..254).map { "$base$it" } }
        val semaphore = Semaphore(workers.coerceAtLeast(1))

        return withContext(dispatcher) {
            supervisorScope {
                val found = CompletableDeferred<String?>()
                // Written from many sweep workers, read once at the end.
                val busy = AtomicReference<Result.Busy?>(null)
                val jobs = hosts.map { host ->
                    launch {
                        try {
                            semaphore.withPermit {
                                if (found.isCompleted) return@withPermit
                                val (verdict, message) = probe(host, port, timeoutMs)
                                when (verdict) {
                                    Probe.DAEMON -> found.complete(host)
                                    Probe.AT_CAPACITY -> busy.set(Result.Busy(host, message))
                                    Probe.NONE -> Unit
                                }
                            }
                        } catch (_: Throwable) {
                            // a single host failing must never abort the sweep
                        }
                    }
                }
                val watcher = launch {
                    jobs.joinAll()
                    found.complete(null)
                }
                val host = found.await()
                jobs.forEach { it.cancel() }
                watcher.cancel()
                when {
                    host != null -> Result.Found(host)
                    else -> busy.get() ?: Result.None
                }
            }
        }
    }
}

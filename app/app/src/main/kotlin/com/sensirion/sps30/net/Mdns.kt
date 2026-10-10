package com.sensirion.sps30.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

// [Advert] and [ServiceBrowser] live in :core (net/ServiceBrowser.kt): they are
// the portable half of this file, and [LanDiscovery]'s whole policy is written
// against them. What remains here is the Android machinery that implements them.

/**
 * [ServiceBrowser] over Android's own mDNS responder.
 *
 * Two things about `NsdManager` that shape this file:
 *
 * - **Discovery and resolution are separate.** A discovered service is a name
 *   and a type and nothing else; the address, the port and the TXT records
 *   arrive only from a resolve. So this browses first, stops, and then resolves
 *   what it collected.
 * - **`resolveService` is effectively single-flight.** A second resolve while
 *   one is in flight fails with `FAILURE_ALREADY_ACTIVE`, so the resolves below
 *   are sequential rather than concurrent. With one controller on the LAN that
 *   is one resolve.
 *
 * It uses the deprecated `resolveService` on every API level rather than
 * branching to `registerServiceInfoCallback` at 34, for the reason [BleTransport]
 * gives for the deprecated GATT accessors: it still works through API 35, and one
 * path that is exercised on every device beats two of which one is exercised on
 * some. When the app's target level moves this is worth revisiting - see the note
 * on local-network permissions in `docs/PRD.md`.
 */
class NsdServiceBrowser(
    context: Context,
    private val serviceType: String = Wire.MDNS_SERVICE_TYPE,
) : ServiceBrowser {

    private val appContext = context.applicationContext
    private val nsd: NsdManager? = appContext.getSystemService(NsdManager::class.java)

    /**
     * What this costs when it finds nothing - the case worth pricing, because it
     * is the one that happens on a network with no controller on it: exactly
     * [timeoutMs], since a browse that sees no name resolves nothing. That is
     * what is added in front of the fallback sweep, and it is a fraction of what
     * the sweep then spends.
     *
     * When it does find something it is far quicker: discovery returns on the
     * first answer plus a short settle, and one resolve on a LAN is milliseconds.
     */
    override suspend fun browse(timeoutMs: Long): List<Advert> {
        val manager = nsd ?: return emptyList()

        // The two phases get separate budgets deliberately. Sharing one deadline
        // meant a name that arrived late left too little of it to resolve in, so
        // the slowest controller on the network was the one guaranteed to be
        // missed - the opposite of what a timeout is for. The candidate cap is
        // what keeps that bounded.
        val heard = discover(manager, timeoutMs)
        if (heard.size > MAX_CANDIDATES) {
            Log.w(TAG, "${heard.size} hosts advertise $serviceType; resolving $MAX_CANDIDATES")
        }
        val names = heard.take(MAX_CANDIDATES)
        if (names.isEmpty()) return emptyList()

        val adverts = mutableListOf<Advert>()
        for (info in names) {
            resolve(manager, info, RESOLVE_TIMEOUT_MS)?.let { adverts += it }
        }
        return adverts
    }

    /** Names seen on the wire, in the order they arrived, de-duplicated. */
    private suspend fun discover(manager: NsdManager, timeoutMs: Long): List<NsdServiceInfo> {
        val seen = LinkedHashMap<String, NsdServiceInfo>()
        // A platform type, so the null is real rather than theoretical, and a
        // nameless service is one nothing can resolve anyway.
        fun remember(info: NsdServiceInfo) {
            info.serviceName?.takeIf { it.isNotBlank() }?.let { seen[it] = info }
        }
        val arrivals = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        var listener: NsdManager.DiscoveryListener? = null
        try {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { arrivals.trySend(info) }
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    Log.d(TAG, "discovery would not start ($code); the sweep still can")
                    arrivals.close()
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            }
            listener = l
            start(manager, l)

            withTimeoutOrNull(timeoutMs) {
                for (info in arrivals) {
                    remember(info)
                    // One controller is the expected case, so do not spend the
                    // whole budget waiting for a second. The grace is there
                    // because a second answer to the same query arrives within
                    // milliseconds of the first, not seconds.
                    withTimeoutOrNull(SETTLE_MS) {
                        for (more in arrivals) remember(more)
                    }
                    return@withTimeoutOrNull
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "browsing failed; falling back to the sweep", e)
        } finally {
            arrivals.close()
            listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        }
        return seen.values.toList()
    }

    private fun start(manager: NsdManager, listener: NsdManager.DiscoveryListener) {
        // Bound to the Wi-Fi network where the platform allows it, for the
        // reason WifiNetworkBinder gives for sockets: a Wi-Fi network with no
        // internet is not the default route, and unbound queries then go out of
        // the interface the controller is not on. Below API 33 there is no such
        // overload and the system's own choice is what there is - and when the
        // binding is refused, or the network is not known yet, an unbound browse
        // is still better than none. Neither case can lose the sensor: whatever
        // this misses, the sweep still sees.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val network = WifiNetworkBinder.wifiNetwork()
            if (network != null) {
                manager.discoverServices(
                    serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    network,
                    { it.run() },
                    listener,
                )
                return
            }
        }
        manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * One resolve, or null.
     *
     * A resolve that times out is abandoned rather than cancelled at the service:
     * `stopServiceResolution` is API 34 and deprecated in the same breath, so
     * there is no branch-free way to do it, and the cost of not doing it is that
     * the *next* resolve may be refused as already-active. That costs a candidate,
     * not the sensor - a candidate that does not resolve falls through to the
     * sweep like any other.
     */
    @Suppress("DEPRECATION") // see the class note: one path, working through API 35
    private suspend fun resolve(
        manager: NsdManager,
        info: NsdServiceInfo,
        timeoutMs: Long,
    ): Advert? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont: CancellableContinuation<Advert?> ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, code: Int) {
                    Log.d(TAG, "${failed.serviceName} did not resolve ($code)")
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(advertOf(resolved))
                }
            }
            runCatching { manager.resolveService(info, listener) }
                .onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private fun advertOf(info: NsdServiceInfo): Advert? {
        // IPv4 only, deliberately: the protocol server binds the IPv4 wildcard
        // and the sweep it falls back to is an IPv4 sweep, so an IPv6-only
        // answer would be a host this app cannot reach anyway.
        @Suppress("DEPRECATION")
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull { it is Inet4Address }
        } else {
            info.host?.takeIf { it is Inet4Address }
        } ?: return null

        val host = address.hostAddress ?: return null
        val txt = info.attributes.orEmpty().mapNotNull { (key, value) ->
            // A TXT value is bytes; every key this protocol defines is text, and
            // a key present with no value at all is legal DNS-SD.
            key?.let { it to (value?.toString(Charsets.UTF_8) ?: "") }
        }.toMap()
        return Advert(name = info.serviceName ?: "", host = host, port = info.port, txt = txt)
    }

    private companion object {
        const val TAG = "Mdns"

        /** How long a single resolve may take. */
        const val RESOLVE_TIMEOUT_MS = 1_500L

        /**
         * How many discovered names are resolved at all.
         *
         * A household has one controller and a demonstration bench has two, so
         * this is not a limit anybody meets; it is what stops a network with
         * something odd on it from turning a bounded browse into an unbounded
         * series of resolves. What it drops is logged rather than silently
         * ignored, because "we only looked at four" reads as "we looked".
         */
        const val MAX_CANDIDATES = 4

        /** How long to keep listening after the first answer, for a second one. */
        const val SETTLE_MS = 300L
    }
}

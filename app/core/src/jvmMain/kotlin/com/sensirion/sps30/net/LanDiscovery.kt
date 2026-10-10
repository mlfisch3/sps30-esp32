package com.sensirion.sps30.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finding the controller on the LAN: listen for it, and only sweep if that fails.
 *
 * ## The two paths, and why there are two
 *
 * **Listening** is the path this app should have had from the start. The
 * controller announces `_sensorq._tcp` over mDNS; the phone browses for that
 * service type, resolves what answers, and connects. It is one multicast query
 * instead of several hundred TCP connects, it takes tens of milliseconds instead
 * of seconds, and - the part no amount of sweeping achieves - what answers has
 * *said* it is a SENSORQ, rather than merely having accepted a connection on a
 * port. Both phone platforms are also in the middle of gating address sweeps
 * behind a permission built around declared service types, so the sweep is on a
 * clock: Android's Local Network Protections are opt-in at target 36 - which is
 * what this app targets, so both paths still run unrestricted today - and
 * mandatory at 37, and Apple's local-network prompt is written around Bonjour
 * types.
 *
 * Note that browsing is gated by the same restriction as sweeping. Listening is
 * the *shape* the restriction is designed around, and the shape that survives a
 * permission being required; it is not an exemption from one. The permission is
 * deliberately not declared or requested here - see `AGENTS.md`, "What the app
 * declares to Google Play", which also records that the right name for it is not
 * yet settled. See `docs/PRD.md` for the whole discovery specification.
 *
 * **Sweeping** is [Discovery], unchanged: every address of every local /24,
 * 64 at a time, keeping whichever answers `hello`. It is kept, and it is kept
 * working, for one concrete reason - **a controller running firmware without
 * mDNS in it is not discoverable any other way**, and there is one in the field
 * recording an event its owner cares about. It is the fallback, not the plan:
 * when every controller advertises, [sweepFallback] and its whole call graph go,
 * and nothing else here changes.
 *
 * ## Why an advertisement is not enough on its own
 *
 * Everything that resolves is still probed over TCP before it is called found.
 * That is not belt and braces, it is the app's existing contract: reaching
 * [ConnectionState.Connecting] has always meant "a host answered `hello` just
 * now", and the "not found" banner clears honestly because of it. An mDNS record
 * can be a stale cache entry for a board that has been unplugged for an hour, or
 * an address that no longer routes. Probing is also how the difference between
 * *absent* and *full* survives - [Discovery.Probe.AT_CAPACITY] - which the app
 * learned the hard way and must not lose by taking a shorter route.
 */
object LanDiscovery {

    /** Which of the two paths is running, so the caller can say so on screen. */
    enum class Phase {
        /** Browsing for the advertisement. */
        LISTENING,

        /** Nothing answered; the fallback sweep is running. */
        SWEEPING,
    }

    /** How long to browse before giving up on it. */
    const val BROWSE_TIMEOUT_MS = 2_500L

    /**
     * Locate the controller: listen, then sweep if listening found nothing.
     *
     * Returns the same [Discovery.Result] the sweep alone used to return, and
     * that is deliberate - every caller above this point, and the whole of the
     * connection flow, cannot tell which path produced the answer. A controller
     * found by listening and one found by sweeping are the same host reached the
     * same way.
     *
     * @param browser null to skip the mDNS path entirely - which is what a
     *   context-less caller gets, and what the sweep-only behaviour was.
     * @param sweepFallback **the path to retire.** Kept injectable, named for
     *   what it is, and called from exactly one place below.
     */
    suspend fun locate(
        port: Int,
        browser: ServiceBrowser?,
        browseTimeoutMs: Long = BROWSE_TIMEOUT_MS,
        probe: (String, Int, Long) -> Pair<Discovery.Probe, String> = Discovery::probe,
        probeTimeoutMs: Long = 1_000,
        sweepFallback: suspend (Int) -> Discovery.Result =
            { p -> Discovery.sweep(p, dispatcher = Dispatchers.IO) },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        onPhase: (Phase) -> Unit = {},
    ): Discovery.Result {
        onPhase(Phase.LISTENING)
        val heard = listen(port, browser, browseTimeoutMs, probe, probeTimeoutMs, dispatcher)
        when (heard) {
            is Discovery.Result.Found -> return heard
            // The controller announced itself and then refused another viewer.
            // It has been located, so there is nothing for a sweep to find: the
            // answer is "it is full", which is a different thing from "it is not
            // there" and the whole reason that distinction exists.
            is Discovery.Result.Busy -> return heard
            Discovery.Result.None -> Unit
        }

        // ---- the fallback -------------------------------------------------
        // Reached when: no controller advertises (firmware older than the mDNS
        // support), the platform's responder is unavailable, a VPN or a network
        // that drops multicast swallowed the query, or the browser found only
        // hosts that would not answer. Every one of those is a network the sweep
        // still works on, which is why it is still here.
        onPhase(Phase.SWEEPING)
        return sweepFallback(port)
    }

    /**
     * The mDNS path on its own: browse, then verify what answered.
     *
     * [Discovery.Result.None] means "nothing to connect to was heard", which is
     * the caller's cue to fall back - it is not a failure and is not reported as
     * one.
     */
    private suspend fun listen(
        port: Int,
        browser: ServiceBrowser?,
        browseTimeoutMs: Long,
        probe: (String, Int, Long) -> Pair<Discovery.Probe, String>,
        probeTimeoutMs: Long,
        dispatcher: CoroutineDispatcher,
    ): Discovery.Result {
        if (browser == null) return Discovery.Result.None
        val adverts = try {
            browser.browse(browseTimeoutMs)
        } catch (e: Exception) {
            // A browser that throws is a browser that found nothing. It must
            // never be the reason the captain's sensor becomes unreachable.
            return Discovery.Result.None
        }

        // On [dispatcher] for the same reason [Discovery.sweep] moves itself
        // there: [Discovery.probe] opens a socket and blocks on it for up to a
        // second per candidate, and it must not do that on whichever thread the
        // caller happened to be on. The browse above needs no such care - it is
        // callback-driven and suspends rather than blocking.
        return withContext(dispatcher) {
            var busy: Discovery.Result.Busy? = null
            for (advert in adverts) {
                // A host that has announced a protocol this build does not speak
                // is not a candidate. Silence on the point is not: see
                // [Advert.speaksOurProtocol].
                if (!advert.speaksOurProtocol) continue
                // The SRV record is the authority on where the server is; the
                // caller's port is what to use when an advertisement did not
                // carry one, which is the only case a zero can mean.
                val at = if (advert.port > 0) advert.port else port
                val (verdict, message) = probe(advert.host, at, probeTimeoutMs)
                when (verdict) {
                    Discovery.Probe.DAEMON ->
                        return@withContext Discovery.Result.Found(advert.host)
                    // Remembered rather than returned, exactly as the sweep does
                    // it: a second, free controller is the better answer if there
                    // is one.
                    Discovery.Probe.AT_CAPACITY ->
                        busy = Discovery.Result.Busy(advert.host, message)
                    Discovery.Probe.NONE -> Unit
                }
            }
            busy ?: Discovery.Result.None
        }
    }
}

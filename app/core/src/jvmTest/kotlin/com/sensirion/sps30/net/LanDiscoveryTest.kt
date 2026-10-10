package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listening first, sweeping only when listening found nothing.
 *
 * The half of this that matters most is the fallback. A controller running
 * firmware without mDNS in it does not advertise, and there is one in the field
 * recording an event its owner cares about; a change that made it undiscoverable
 * would be a serious failure however good the new path is. So the sweep is
 * exercised here as a path in its own right, not as an afterthought - including
 * the case where the browser is not merely empty but broken.
 */
class LanDiscoveryTest {

    private val port = Wire.DEFAULT_PORT

    /** A controller that advertises itself, as the current firmware does. */
    private fun advertising(host: String, at: Int = port) = Advert(
        name = "SENSORQ",
        host = host,
        port = at,
        txt = mapOf(
            Wire.MDNS_TXT_VERSION to "1",
            Wire.MDNS_TXT_PROTOCOL to Wire.MDNS_PROTOCOL_VERSION,
        ),
    )

    private fun browserOf(vararg adverts: Advert) = ServiceBrowser { adverts.toList() }

    /** Answers `hello` at [host] and nothing anywhere else. */
    private fun daemonAt(host: String): (String, Int, Long) -> Pair<Discovery.Probe, String> =
        { h, _, _ ->
            if (h == host) Discovery.Probe.DAEMON to "" else Discovery.Probe.NONE to ""
        }

    private val nothingAnswers: (String, Int, Long) -> Pair<Discovery.Probe, String> =
        { _, _, _ -> Discovery.Probe.NONE to "" }

    private val neverSweep: suspend (Int) -> Discovery.Result = {
        throw AssertionError("the sweep ran when listening had already found the sensor")
    }

    // --- the new path -------------------------------------------------------

    @Test
    fun anAdvertisingControllerIsFoundByListening() = runBlocking {
        val found = LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.42")),
            probe = daemonAt("192.168.1.42"),
            sweepFallback = neverSweep,
        )
        assertEquals(Discovery.Result.Found("192.168.1.42"), found)
    }

    /**
     * And the sweep does not run at all when it does not have to.
     *
     * That is the whole point of the change - hundreds of TCP connects replaced
     * by one multicast query - so it is asserted rather than assumed. [neverSweep]
     * throws, so this fails loudly rather than merely being slower than intended.
     */
    @Test
    fun listeningSuccessfullySkipsTheSweepEntirely() = runBlocking {
        val phases = mutableListOf<LanDiscovery.Phase>()
        LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("10.0.0.5")),
            probe = daemonAt("10.0.0.5"),
            sweepFallback = neverSweep,
            onPhase = { phases += it },
        )
        assertEquals(listOf(LanDiscovery.Phase.LISTENING), phases)
    }

    /**
     * The SRV record says where the server is, and that is what gets probed.
     *
     * A port in the advertisement is not decoration: it is how a controller that
     * ever moves off 8730 stays findable without the app being rebuilt.
     */
    @Test
    fun theAdvertisedPortIsTheOneProbed() = runBlocking {
        val probed = mutableListOf<Int>()
        val found = LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.42", at = 9999)),
            probe = { _, p, _ -> probed += p; Discovery.Probe.DAEMON to "" },
            sweepFallback = neverSweep,
        )
        assertEquals(Discovery.Result.Found("192.168.1.42"), found)
        assertEquals(listOf(9999), probed)
    }

    /** An advertisement with no port falls back on the port the caller knows. */
    @Test
    fun anAdvertisementWithoutAPortUsesTheDefault() = runBlocking {
        val probed = mutableListOf<Int>()
        LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.42", at = 0)),
            probe = { _, p, _ -> probed += p; Discovery.Probe.DAEMON to "" },
            sweepFallback = neverSweep,
        )
        assertEquals(listOf(port), probed)
    }

    /**
     * Something answered the browse, and it is not a host this app can talk to.
     *
     * Silence about the protocol is not the same as speaking a different one -
     * see [Advert.speaksOurProtocol] - so only a stated mismatch is skipped.
     */
    @Test
    fun aHostAnnouncingAProtocolWeDoNotSpeakIsNotProbed() = runBlocking {
        val future = advertising("192.168.1.42")
            .copy(txt = mapOf(Wire.MDNS_TXT_PROTOCOL to "7"))
        val probed = AtomicInteger()
        val result = LanDiscovery.locate(
            port = port,
            browser = browserOf(future),
            probe = { h, p, t -> probed.incrementAndGet(); daemonAt("192.168.1.42")(h, p, t) },
            sweepFallback = { Discovery.Result.None },
        )
        assertEquals(0, probed.get())
        assertEquals(Discovery.Result.None, result)
    }

    @Test
    fun anAdvertisementWithNoProtocolKeyIsStillProbed() = runBlocking {
        val quiet = advertising("192.168.1.42").copy(txt = emptyMap())
        assertTrue(quiet.speaksOurProtocol)
        assertEquals(
            Discovery.Result.Found("192.168.1.42"),
            LanDiscovery.locate(
                port = port,
                browser = browserOf(quiet),
                probe = daemonAt("192.168.1.42"),
                sweepFallback = neverSweep,
            ),
        )
    }

    /**
     * A record can outlive the board it describes: mDNS answers are cached for
     * over an hour, and a controller that was unplugged is still announced by
     * whatever remembers it. So what resolves is probed before it is believed,
     * and a stale one falls through to the sweep like any other empty result.
     */
    @Test
    fun anAdvertisementForAHostThatHasGoneFallsThroughToTheSweep() = runBlocking {
        var swept = false
        val result = LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.99")),
            probe = nothingAnswers,
            sweepFallback = { swept = true; Discovery.Result.Found("192.168.1.42") },
        )
        assertTrue("the sweep ran", swept)
        assertEquals(Discovery.Result.Found("192.168.1.42"), result)
    }

    /** Several advertisements, and the one that answers is the one taken. */
    @Test
    fun theFirstAdvertisementThatAnswersIsTheOneTaken() = runBlocking {
        val result = LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.7"), advertising("192.168.1.8")),
            probe = daemonAt("192.168.1.8"),
            sweepFallback = neverSweep,
        )
        assertEquals(Discovery.Result.Found("192.168.1.8"), result)
    }

    // --- the fallback, which is what protects the sensor in the field -------

    /**
     * The case the fallback exists for: firmware with no mDNS in it.
     *
     * Nothing advertises, so the browse is empty, so the sweep runs and finds the
     * controller exactly as it always has.
     */
    @Test
    fun aControllerThatDoesNotAdvertiseIsStillFoundBySweeping() = runBlocking {
        val phases = mutableListOf<LanDiscovery.Phase>()
        val result = LanDiscovery.locate(
            port = port,
            browser = browserOf(),
            probe = nothingAnswers,
            sweepFallback = { Discovery.Result.Found("192.168.1.226") },
            onPhase = { phases += it },
        )
        assertEquals(Discovery.Result.Found("192.168.1.226"), result)
        assertEquals(
            listOf(LanDiscovery.Phase.LISTENING, LanDiscovery.Phase.SWEEPING),
            phases,
        )
    }

    /**
     * A phone with no mDNS at all - no browser was supplied - is the app as it
     * was before this existed, and it still finds the sensor.
     */
    @Test
    fun withNoBrowserAtAllTheSweepIsTheWholeOfDiscovery() = runBlocking {
        val result = LanDiscovery.locate(
            port = port,
            browser = null,
            probe = nothingAnswers,
            sweepFallback = { Discovery.Result.Found("192.168.1.226") },
        )
        assertEquals(Discovery.Result.Found("192.168.1.226"), result)
    }

    /**
     * A browser that throws must not be able to take the sensor away.
     *
     * `NsdManager` is a system service that can be missing, refuse to start
     * discovery, or be strangled by a VPN or an access point that drops
     * multicast. Every one of those has to end in the sweep, because the sweep is
     * what still works on those networks.
     */
    @Test
    fun aBrowserThatFailsStillEndsInTheSweep() = runBlocking {
        val result = LanDiscovery.locate(
            port = port,
            browser = { throw IllegalStateException("no NSD on this device") },
            probe = nothingAnswers,
            sweepFallback = { Discovery.Result.Found("192.168.1.226") },
        )
        assertEquals(Discovery.Result.Found("192.168.1.226"), result)
    }

    /** Nothing anywhere, by either path, is still the one plain answer. */
    @Test
    fun nothingOnEitherPathIsNone() = runBlocking {
        assertEquals(
            Discovery.Result.None,
            LanDiscovery.locate(
                port = port,
                browser = browserOf(),
                probe = nothingAnswers,
                sweepFallback = { Discovery.Result.None },
            ),
        )
    }

    // --- what the rest of the app sees --------------------------------------

    /**
     * Both paths hand the rest of the app the same thing.
     *
     * This is the property that let [TcpTransport], [DaemonClient] and the whole
     * connection flow stay untouched: a host found by listening and one found by
     * sweeping are the same [Discovery.Result], carrying the same host, so nothing
     * above discovery can tell - or has to handle - which path produced it.
     */
    @Test
    fun bothPathsProduceTheSameResultForTheSameController() = runBlocking {
        val byListening = LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.42")),
            probe = daemonAt("192.168.1.42"),
            sweepFallback = neverSweep,
        )
        val bySweeping = LanDiscovery.locate(
            port = port,
            browser = browserOf(),
            probe = nothingAnswers,
            sweepFallback = { Discovery.Result.Found("192.168.1.42") },
        )
        assertEquals(byListening, bySweeping)
        assertEquals(Discovery.Result.Found("192.168.1.42"), byListening)
    }

    /**
     * "There, and full" survives the new path.
     *
     * The app learned this one the hard way: treating an `at_capacity` answer as
     * "nothing there" was self-reinforcing, because every retry spent another
     * client slot. Listening must not lose the distinction, and it must not sweep
     * afterwards either - the controller has been located, and the answer is that
     * it is full.
     */
    @Test
    fun aFullControllerThatAdvertisedIsReportedFullWithoutSweeping() = runBlocking {
        val result = LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.42")),
            probe = { _, _, _ -> Discovery.Probe.AT_CAPACITY to "sensor is already serving 8 clients" },
            sweepFallback = neverSweep,
        )
        assertEquals(
            Discovery.Result.Busy("192.168.1.42", "sensor is already serving 8 clients"),
            result,
        )
    }

    /** A free controller beats a full one, whichever order they were heard in. */
    @Test
    fun aFullControllerDoesNotHideAFreeOne() = runBlocking {
        val result = LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.7"), advertising("192.168.1.8")),
            probe = { h, _, _ ->
                when (h) {
                    "192.168.1.7" -> Discovery.Probe.AT_CAPACITY to "full"
                    else -> Discovery.Probe.DAEMON to ""
                }
            },
            sweepFallback = neverSweep,
        )
        assertEquals(Discovery.Result.Found("192.168.1.8"), result)
    }

    /**
     * The probe does not run on the caller's thread.
     *
     * [Discovery.probe] opens a socket and blocks on it for up to a second per
     * candidate. [Discovery.sweep] has always moved itself off the caller's
     * thread for that reason; the listening path has to as well, or the fast path
     * would be the one that stalls whatever called it. Asserted by identity of
     * thread rather than by reading the code, because a `withContext` deleted in
     * a later refactor reads like a simplification.
     */
    @Test
    fun probingHappensOffTheCallersThread() = runBlocking {
        val caller = Thread.currentThread()
        var probedOn: Thread? = null
        LanDiscovery.locate(
            port = port,
            browser = browserOf(advertising("192.168.1.42")),
            probe = { _, _, _ ->
                probedOn = Thread.currentThread()
                Discovery.Probe.DAEMON to ""
            },
            sweepFallback = neverSweep,
        )
        assertTrue("something was probed", probedOn != null)
        assertTrue("not the caller's thread", probedOn !== caller)
    }

    // --- the advertisement itself -------------------------------------------

    /**
     * The service type this app browses for, held against `wire.py`.
     *
     * There is no negotiation and no error path here: the app browses for a
     * string it was compiled with, and if it is not the string the controller
     * advertises then there is simply no sensor on the network as far as the app
     * is concerned. The firmware's copy is checked against the same source in
     * `firmware/test/test_net_mdns.c`.
     */
    @Test
    fun theServiceTypeIsTheOneTheProtocolDefines() {
        assertEquals("_sensorq._tcp", Wire.MDNS_SERVICE_TYPE)
        assertEquals("txtvers", Wire.MDNS_TXT_VERSION)
        assertEquals("proto", Wire.MDNS_TXT_PROTOCOL)
        assertEquals("1", Wire.MDNS_PROTOCOL_VERSION)
    }

    /**
     * Unknown TXT keys are carried, not refused.
     *
     * A controller newer than this app will advertise keys this app has never
     * heard of, and refusing it over one of them would make every future firmware
     * undiscoverable by every phone that has not been updated.
     */
    @Test
    fun anAdvertisementWithExtraKeysIsStillUsable() = runBlocking {
        val richer = advertising("192.168.1.42").let {
            it.copy(txt = it.txt + ("something_new" to "yes"))
        }
        assertTrue(richer.speaksOurProtocol)
        assertEquals(
            Discovery.Result.Found("192.168.1.42"),
            LanDiscovery.locate(
                port = port,
                browser = browserOf(richer),
                probe = daemonAt("192.168.1.42"),
                sweepFallback = neverSweep,
            ),
        )
    }

    @Test
    fun aStatedProtocolMismatchIsTheOnlyThingThatDisqualifies() {
        assertTrue(advertising("h").speaksOurProtocol)
        assertTrue(advertising("h").copy(txt = emptyMap()).speaksOurProtocol)
        assertFalse(
            advertising("h").copy(txt = mapOf(Wire.MDNS_TXT_PROTOCOL to "2")).speaksOurProtocol,
        )
    }
}

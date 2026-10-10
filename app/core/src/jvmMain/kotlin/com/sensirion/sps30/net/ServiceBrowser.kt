package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Wire

/**
 * One controller, as it announced itself - before anything has connected to it.
 *
 * [txt] is whatever the advertisement carried, verbatim and unfiltered, because
 * a client that only understands some of the keys must ignore the rest rather
 * than refuse the device; see `wire.MDNS_TXT_VERSION`. What the controller puts
 * there, and the much longer list of what it deliberately does not, is at the
 * definition in `wire.py` and `firmware/components/net/include/net_mdns_record.h`.
 */
data class Advert(
    /** The instance name, e.g. `SENSORQ`. A label, never an identity. */
    val name: String,
    val host: String,
    /** From the SRV record, which is the authority on where the server is. */
    val port: Int,
    val txt: Map<String, String> = emptyMap(),
) {
    /**
     * Whether this build can speak what the advertisement says the host speaks.
     *
     * An advertisement with no `proto` key at all passes: it is either a
     * controller older than that key or something else answering the same
     * service type, and in both cases the TCP probe is a better judge than a
     * missing field. A `proto` that is *present and different* is the one case
     * worth acting on - the host has said, without being asked, that it speaks a
     * protocol this app does not.
     */
    val speaksOurProtocol: Boolean
        get() = txt[Wire.MDNS_TXT_PROTOCOL].let { it == null || it == Wire.MDNS_PROTOCOL_VERSION }
}

/**
 * Listening for controllers that announce themselves.
 *
 * An interface with one method, so the policy in [LanDiscovery] can be tested
 * against a fake and the platform machinery (`NsdServiceBrowser` in :app, over
 * Android's own mDNS responder) can stay the thin part. The same split
 * `BleTransport` uses for the radio.
 */
fun interface ServiceBrowser {
    /**
     * Browse for at most [timeoutMs] and return what answered.
     *
     * Never throws and never blocks past the timeout: an empty list is the
     * ordinary result on a network with no controller on it, and it is what
     * sends [LanDiscovery] to the fallback sweep.
     */
    suspend fun browse(timeoutMs: Long): List<Advert>
}

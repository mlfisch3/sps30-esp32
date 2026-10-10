package com.sensirion.sps30.wear

/**
 * The Wearable Data Layer contract for relaying the latest SPS30 reading from the
 * phone to the Galaxy Watch 7 complication.
 *
 * Deliberately tiny and self-contained (no dependency on `wire/`): the watch only
 * needs the current PM values plus freshness, not the full daemon line protocol -
 * the phone does all the daemon talking. A byte-for-byte copy of this object lives
 * in the `:wear` module; the two MUST stay in lockstep (path + key names).
 */
object WearContract {
    /** DataItem path holding the single most-recent reading. */
    const val PATH_LATEST = "/sps30/latest"

    // DataMap keys. PM values are ug/m3 doubles, matching wire.FIELDS names.
    const val KEY_PM1_0 = "pm1_0"
    const val KEY_PM2_5 = "pm2_5"
    const val KEY_PM4_0 = "pm4_0"
    const val KEY_PM10 = "pm10"

    /** ISO-8601 timestamp string of the reading (from the daemon). */
    const val KEY_TIMESTAMP = "timestamp"

    /** Epoch millis when the phone published this item, quantised to
     *  [SENT_AT_BUCKET_MS]. The watch uses it to judge staleness; quantising it
     *  means an unchanged reading republished within the same bucket produces an
     *  identical DataItem, which the Data Layer drops instead of syncing. */
    const val KEY_SENT_AT = "sent_at"

    /** Quantisation of [KEY_SENT_AT]. Must stay well under the watch's staleness
     *  threshold so a heartbeat push still reads as fresh. */
    const val SENT_AT_BUCKET_MS = 60_000L

    /** The phone's configured relay interval, seconds. The watch derives its own
     *  staleness threshold from this: a 15-minute relay interval must not make a
     *  perfectly current reading look stale just because a fixed 10-minute
     *  threshold expired between two intended updates. */
    const val KEY_RELAY_SECONDS = "relay_seconds"

    /** Downsampled PM2.5 series over the last hour (oldest -> newest, ug/m3), for
     *  the watch Tile's sparkline. Small (<= a few dozen floats). May be empty. */
    const val KEY_SPARK_PM25 = "spark_pm25"

    /**
     * The capability the watch app declares, and the phone's only honest answer
     * to "is the receiving app actually installed over there".
     *
     * Declared in `wear/src/main/res/values/wear.xml`; read on the phone through
     * `CapabilityClient`. Without it the phone can see a watch and cannot see
     * whether anything on it is listening, which is exactly the failure the
     * Watch tab was built to name - see [WatchLink].
     */
    const val CAPABILITY_WATCH_APP = "sps30_watch_app"

    /**
     * Path of the delivery probe behind "Send a reading now".
     *
     * A `MessageClient` message rather than another DataItem, because the two
     * prove different things: `putDataItem` succeeds once the *phone* has stored
     * the item, while `sendMessage` succeeds only once the named node has taken
     * delivery. Nothing on the watch has to listen on this path for that to be
     * true - the platform is answering for the node, not for an app - so the
     * probe works against watch builds that predate it.
     */
    const val PATH_PING = "/sps30/ping"
}

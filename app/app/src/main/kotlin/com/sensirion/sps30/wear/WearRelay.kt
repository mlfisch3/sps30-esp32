package com.sensirion.sps30.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Phone-side publisher: pushes the latest [com.sensirion.sps30.wire.ServerMessage.Reading]
 * to the Wearable Data Layer so a paired Galaxy Watch 7 complication can glance at
 * the current PM2.5 without its own daemon connection.
 *
 * Battery-conscious by design:
 *  - It writes a single, overwritten DataItem at [WearContract.PATH_LATEST] (not a
 *    stream of messages), so only the most recent value is ever synced.
 *  - Readings arrive at ~1 Hz but [com.sensirion.sps30.ui.MonitorSession] only calls
 *    this when the reading actually moved, or on a slow heartbeat.
 *  - [publishedAtMs] is quantised (see [WearContract.SENT_AT_BUCKET_MS]) so an
 *    unchanged reading yields a byte-identical DataItem, which the Data Layer then
 *    drops for free rather than syncing it to the watch.
 *  - `setUrgent()` is deliberately NOT used: the OS may batch the sync to save
 *    radio power, which is the right trade-off for an ambient air-quality glance.
 */
object WearRelay {
    private const val TAG = "WearRelay"

    /** Publish the latest reading plus a [spark] PM2.5 series to the Data Layer.
     *  The caller ([com.sensirion.sps30.ui.MonitorSession]) already decides *whether*
     *  a push is warranted, so there is no throttle here. */
    fun publish(
        context: Context,
        values: Map<String, Double>,
        timestamp: String?,
        spark: FloatArray,
        publishedAtMs: Long = System.currentTimeMillis(),
        relaySeconds: Int = 0,
    ) {
        val bucketed =
            publishedAtMs / WearContract.SENT_AT_BUCKET_MS * WearContract.SENT_AT_BUCKET_MS
        Wearable.getDataClient(context)
            .putDataItem(request(values, timestamp, spark, bucketed, relaySeconds))
            .addOnFailureListener { e -> Log.w(TAG, "Data Layer publish failed", e) }
    }

    /**
     * Publish out of turn and wait for the Data Layer to accept it.
     *
     * Two differences from [publish], both of them the point of it existing:
     *
     *  - the sent-at millisecond is **not** bucketed, so the payload is
     *    guaranteed to differ from the last one. The bucketing is a battery
     *    optimisation that lets the Data Layer drop a byte-identical repeat, and
     *    a test send that got dropped for free would report success over a
     *    payload that never left the phone.
     *  - it suspends until the put resolves, so its caller can say what actually
     *    happened rather than hope.
     *
     * Throws whatever the Data Layer throws; [WearLink.sendTest] turns that into
     * a sentence.
     */
    suspend fun publishExact(
        context: Context,
        values: Map<String, Double>,
        timestamp: String?,
        spark: FloatArray,
        publishedAtMs: Long,
        relaySeconds: Int,
    ) {
        Wearable.getDataClient(context)
            .putDataItem(request(values, timestamp, spark, publishedAtMs, relaySeconds))
            .awaited()
    }

    private fun request(
        values: Map<String, Double>,
        timestamp: String?,
        spark: FloatArray,
        sentAtMs: Long,
        relaySeconds: Int,
    ) = PutDataMapRequest.create(WearContract.PATH_LATEST).apply {
        dataMap.putDouble(WearContract.KEY_PM1_0, values["pm1_0"] ?: Double.NaN)
        dataMap.putDouble(WearContract.KEY_PM2_5, values["pm2_5"] ?: Double.NaN)
        dataMap.putDouble(WearContract.KEY_PM4_0, values["pm4_0"] ?: Double.NaN)
        dataMap.putDouble(WearContract.KEY_PM10, values["pm10"] ?: Double.NaN)
        timestamp?.let { dataMap.putString(WearContract.KEY_TIMESTAMP, it) }
        dataMap.putFloatArray(WearContract.KEY_SPARK_PM25, spark)
        dataMap.putLong(WearContract.KEY_SENT_AT, sentAtMs)
        dataMap.putInt(WearContract.KEY_RELAY_SECONDS, relaySeconds)
    }.asPutDataRequest()
}

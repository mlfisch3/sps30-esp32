package com.sensirion.sps30.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The half of the watch link that talks to the platform, and does no judging.
 *
 * Everything here is a question the Wearable Data Layer already answers. There
 * is deliberately no invented heartbeat, no ack protocol and no state machine of
 * our own: a heartbeat we wrote could only report on itself, whereas
 * `NodeClient`, `CapabilityClient` and `MessageClient` report on the actual
 * pairing, and each of the three answers a different one of the user's
 * questions.
 *
 *  * **Is a watch there at all** - `NodeClient.getConnectedNodes()`.
 *  * **Does it have the receiving app** - `CapabilityClient`, against the
 *    capability the watch app declares (see [WearContract.CAPABILITY_WATCH_APP]).
 *  * **Can the phone reach it right now** - `MessageClient.sendMessage()`, whose
 *    task resolves only when the named node takes delivery.
 *
 * [WatchLink] turns the answers into sentences.
 */
object WearLink {

    private const val TAG = "WearLink"

    /** What a probe found, or why it could not look. */
    data class ProbeResult(val nodes: List<WatchNode>, val error: String?)

    /**
     * What a "send a reading now" achieved, per watch.
     *
     * It carries the [probe] it had to do on the way as well, because a send is
     * the freshest look at the link there is and the indicator above the button
     * would otherwise still be describing the world before the press.
     */
    data class SendAttempt(
        val probe: ProbeResult,
        val reached: List<String>,
        val missed: List<String>,
        val failure: String?,
    )

    /**
     * Ask the Data Layer what is out there.
     *
     * Never throws. A phone whose Play services are broken, missing or simply
     * asleep is a real state the user needs told about, so it comes back as
     * [ProbeResult.error] rather than as an exception nobody sees - and the
     * same path keeps this callable from a JVM test host with no GMS at all.
     */
    suspend fun probe(context: Context): ProbeResult = try {
        val connected = Wearable.getNodeClient(context).connectedNodes.awaited()
        // FILTER_REACHABLE and not FILTER_ALL: a node that once had the app and
        // is now switched off would otherwise be reported as ready to receive.
        //
        // Null when the lookup itself failed, and that distinction is the whole
        // reason this is not a plain set. A capability nobody declares resolves
        // to an *empty* set rather than failing, so a thrown exception here is a
        // services fault and says nothing at all about what is on the watch. It
        // used to be swallowed into `emptySet()`, which made every node report
        // `hasApp = false` and had the Watch tab tell the captain his watch had
        // no app on it while the app was installed and running. A failure to ask
        // is not an answer.
        val capable: Set<String>? = try {
            Wearable.getCapabilityClient(context)
                .getCapability(WearContract.CAPABILITY_WATCH_APP, CapabilityClient.FILTER_REACHABLE)
                .awaited()
                .nodes
                .map { it.id }
                .toSet()
        } catch (e: Exception) {
            // Still not a reason to throw away the node list, which is the more
            // basic of the two answers and is the one that came back.
            Log.w(TAG, "capability lookup failed", e)
            null
        }
        ProbeResult(
            nodes = connected.map {
                WatchNode(
                    id = it.id,
                    name = it.displayName,
                    nearby = it.isNearby,
                    hasApp = capable?.contains(it.id),
                )
            },
            error = null,
        )
    } catch (e: Exception) {
        Log.w(TAG, "node lookup failed", e)
        ProbeResult(emptyList(), describe(e))
    }

    /**
     * Publish [values] to the watch out of turn, then ask each watch to confirm
     * it is reachable.
     *
     * Two steps because they prove two different things and only the pair is
     * worth anything. The DataItem is the real reading, on the real path, so a
     * working watch visibly updates; the message is the receipt, because
     * `putDataItem` succeeding means only that this phone stored the item.
     *
     * The DataItem carries an exact millisecond rather than the bucketed one
     * [WearRelay.publish] uses. That is not a detail: the bucketing exists so an
     * unchanged reading republished within the same minute is byte-identical and
     * gets dropped for free, which for a *test* would mean the button reporting
     * success over a payload that never left the phone.
     */
    suspend fun sendTest(
        context: Context,
        values: Map<String, Double>,
        timestamp: String?,
        spark: FloatArray,
        relaySeconds: Int,
        nowMs: Long,
    ): SendAttempt {
        val probe = probe(context)
        if (probe.error != null) return SendAttempt(probe, emptyList(), emptyList(), probe.error)
        if (probe.nodes.isEmpty()) return SendAttempt(probe, emptyList(), emptyList(), null)

        if (values.isNotEmpty()) {
            try {
                WearRelay.publishExact(context, values, timestamp, spark, nowMs, relaySeconds)
            } catch (e: Exception) {
                return SendAttempt(probe, emptyList(), probe.nodes.map { it.name }, describe(e))
            }
        }

        val messages = Wearable.getMessageClient(context)
        val reached = mutableListOf<String>()
        val missed = mutableListOf<String>()
        for (node in probe.nodes) {
            try {
                messages.sendMessage(node.id, WearContract.PATH_PING, ByteArray(0)).awaited()
                reached += node.name
            } catch (e: Exception) {
                Log.w(TAG, "ping to ${node.name} failed", e)
                missed += node.name
            }
        }
        return SendAttempt(probe, reached, missed, null)
    }

    /** An exception in the words of the dialog it will be printed in. */
    private fun describe(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "unknown fault")
}

/**
 * A GMS [Task] as a suspending call.
 *
 * Hand-rolled rather than pulling in `kotlinx-coroutines-play-services` for one
 * function: the module's dependency list is short on purpose, and this is the
 * whole of what that artifact would be used for.
 */
internal suspend fun <T> Task<T>.awaited(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

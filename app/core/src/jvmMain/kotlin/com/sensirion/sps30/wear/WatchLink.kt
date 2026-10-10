package com.sensirion.sps30.wear

/**
 * What the phone knows about the watch on the other end of the relay, and how to
 * say it in words somebody can act on.
 *
 * ## The fault this exists for
 *
 * The relay was one-way and silent. [WearRelay] publishes a DataItem, the Data
 * Layer accepts it, and that is the last anything on the phone hears about it -
 * so a watch with no companion app on it looks exactly like a watch that is
 * working. That is the failure the captain actually hit: his watch had no
 * receiving app, nothing on either device said so, and the only symptom was a
 * complication that never showed a number. An absence is not a diagnosis. It has
 * to be stated.
 *
 * ## Why this file is pure Kotlin
 *
 * Everything here is arithmetic over facts somebody else gathered, so the
 * wording - which is the whole deliverable - can be pinned down by ordinary JVM
 * tests with no watch, no phone and no Google Play services. [WearLink] is the
 * half that talks to the platform and it does no judging; this half judges and
 * touches no platform.
 */

/** One device the Wearable Data Layer says this phone is connected to. */
data class WatchNode(
    /** The Data Layer node id. Opaque, and never shown to the user. */
    val id: String,
    /** What the watch calls itself, e.g. "Galaxy Watch7". */
    val name: String,
    /**
     * True when the watch is on a direct link (Bluetooth or the same Wi-Fi),
     * false when it is reachable only by going out through Google's servers.
     *
     * Both deliver, but the second can take minutes, so it is worth saying.
     */
    val nearby: Boolean,
    /**
     * Whether this node advertises [WearContract.CAPABILITY_WATCH_APP], which is
     * the platform's own answer to "is my watch app installed over there".
     * See `wear/src/main/res/values/wear.xml`, where the watch app declares it.
     *
     * **Three values, and the third is the point.** `null` means the question
     * could not be asked - the capability lookup itself failed - which is not the
     * same as the answer being no. It used to be a plain `Boolean`, and
     * [WearLink.probe] turned a failed lookup into an empty capability set, so
     * every node came back `false` and the tab told the captain the watch app was
     * not installed on a watch it was installed on. A local device cannot know a
     * remote absence it never managed to look for; saying it did is the exact
     * class of lie this whole file exists to remove.
     */
    val hasApp: Boolean?,
)

/**
 * Everything known about the link, with no judgement applied.
 *
 * Split from the judgement so that "what the Data Layer said" and "what we tell
 * the user about it" can be wrong independently, and so the second can be tested
 * without producing the first.
 */
data class WatchLinkFacts(
    /** True while a probe is in flight, so the tab can say it is looking. */
    val probing: Boolean = false,
    /** Null until a probe has finished at least once. */
    val probedAtMs: Long? = null,
    /** Set when the Data Layer itself would not answer, which is its own state:
     *  it means the question could not be asked, not that the answer is no. */
    val probeError: String? = null,
    val nodes: List<WatchNode> = emptyList(),
    /** When a reading was last handed to the Data Layer for the watch. */
    val lastSentMs: Long? = null,
    /** When a watch last took delivery of something from this phone. Only a
     *  round trip sets this, so it is proof rather than an assumption. */
    val lastDeliveredMs: Long? = null,
    /** True while "Send a reading now" is in flight. */
    val sending: Boolean = false,
    /** What the last "Send a reading now" turned out to be. */
    val lastSendResult: String? = null,
) {
    /** The nodes the platform confirmed have the watch app on them. */
    val appNodes: List<WatchNode> get() = nodes.filter { it.hasApp == true }

    /** The nodes whose capability could not be looked up at all. */
    val unknownAppNodes: List<WatchNode> get() = nodes.filter { it.hasApp == null }

    /** The nodes the platform positively did not list as having the app. */
    val noAppNodes: List<WatchNode> get() = nodes.filter { it.hasApp == false }
}

/** How worried the tab should look. */
enum class WatchHealth {
    /** Nothing has been asked yet. */
    UNKNOWN,

    /** A watch is there, has the app, and is directly reachable. */
    GOOD,

    /** It will work, but something about it is worth knowing. */
    WARN,

    /** Readings are going nowhere. */
    BAD,
}

/** A headline for the indicator, and the paragraph that says what to do. */
data class WatchReport(
    val health: WatchHealth,
    val headline: String,
    val detail: String,
    /** Whether pressing "Send a reading now" could tell the user anything. */
    val canSend: Boolean,
)

object WatchLink {

    /**
     * Twice the longest gap the relay is allowed to leave, so an update interval
     * of fifteen minutes does not make a perfectly healthy link look dead at
     * sixteen.
     *
     * The two gaps are the user's own interval and [RelayPolicy.HEARTBEAT_MS],
     * because a still reading is pushed on the heartbeat and nothing else. The
     * longer of the two is the real spacing, and doubling it is the slack for
     * one missed push.
     */
    fun staleAfterMs(relaySeconds: Int): Long =
        2 * maxOf(relaySeconds * 1000L, RelayPolicy.HEARTBEAT_MS)

    /**
     * The state of the link, in the order the questions actually matter.
     *
     * [readingsArriving] is the phone's own supply. Without it the relay has
     * nothing to send, and blaming the watch for that would point the user at
     * the wrong device entirely.
     */
    fun assess(facts: WatchLinkFacts, readingsArriving: Boolean): WatchReport {
        if (facts.probeError != null) {
            return WatchReport(
                WatchHealth.BAD,
                "This phone cannot tell whether a watch is there.",
                "Wear OS services on this phone did not answer (${facts.probeError}). " +
                    "Nothing can be sent to a watch until they do. Updating Google " +
                    "Play services, or restarting the phone, is what usually clears " +
                    "this.",
                canSend = false,
            )
        }
        if (facts.probedAtMs == null) {
            return WatchReport(
                WatchHealth.UNKNOWN,
                if (facts.probing) "Looking for a watch..." else "Not checked yet.",
                "",
                canSend = false,
            )
        }
        if (facts.nodes.isEmpty()) {
            return WatchReport(
                WatchHealth.BAD,
                "No watch is connected to this phone.",
                "Readings are going nowhere, because there is nowhere to send them. " +
                    "Pair the watch with this phone in the watch's own companion app " +
                    "and leave Bluetooth on. The relay goes out over that pairing; it " +
                    "does not use the sensor's network.",
                canSend = false,
            )
        }

        val withApp = facts.appNodes
        if (withApp.isEmpty()) {
            // "The lookup failed" and "the lookup answered and did not name this
            // watch" are two different states and only one of them is about the
            // watch at all. They were collapsed into the second, which is how a
            // watch with the app on it was told it had none.
            if (facts.noAppNodes.isEmpty()) {
                return WatchReport(
                    WatchHealth.WARN,
                    "${nameList(facts.nodes)} is connected; whether the SPS30 watch " +
                        "app is on it has not been checked.",
                    "Wear OS services on this phone answered the question about the " +
                        "watch itself but not the one about what is installed on it, " +
                        "so this phone genuinely does not know. It is not evidence " +
                        "either way. Press \"Send a reading now\" below - that asks " +
                        "the watch directly and comes back with an answer instead of " +
                        "a guess.",
                    canSend = true,
                )
            }
            return WatchReport(
                WatchHealth.BAD,
                // "This phone cannot see" and not "it is not there": the phone is
                // reporting its own view, which is all it has. A watch app that is
                // installed but does not announce itself is indistinguishable from
                // an absent one from here, and the tab must not pick one of those
                // and state it as fact.
                "${nameList(facts.noAppNodes)} is connected, but this phone cannot " +
                    "see the SPS30 watch app on it.",
                "This is the fault that looks like nothing at all: readings leave " +
                    "this phone correctly, and if no app on the watch is listening " +
                    "for them the watch face stays blank forever and never says why. " +
                    "Install the SPS30 watch app on the watch itself, then press " +
                    "\"Send a reading now\" below to confirm it took. If the app is " +
                    "already installed there, it is an older build that does not " +
                    "announce itself yet - the phone cannot tell those two apart - " +
                    "and updating it fixes this line.",
                canSend = true,
            )
        }

        val distant = withApp.none { it.nearby }
        if (distant) {
            return WatchReport(
                WatchHealth.WARN,
                "${nameList(withApp)} has the watch app, but is not on a direct link.",
                "It can only be reached the long way round, through the internet, so " +
                    "a reading may take minutes to land instead of seconds. Bringing " +
                    "the watch back within Bluetooth range of this phone restores the " +
                    "direct link.",
                canSend = true,
            )
        }

        val detail = if (readingsArriving) {
            "Readings are arriving at this phone and going out to the watch."
        } else {
            "The link is good, but this phone is not receiving any readings to " +
                "send. That is the sensor connection, not the watch."
        }
        return WatchReport(
            WatchHealth.GOOD,
            "${nameList(withApp)} is connected and has the SPS30 watch app.",
            detail,
            canSend = true,
        )
    }

    /**
     * When a reading last went out, and whether that counts as recent.
     *
     * Deliberately says "sent" and never "arrived". Handing a DataItem to the
     * Data Layer proves the phone did its part and nothing more; the only thing
     * that proves arrival is a round trip, which is what
     * [deliveryLine] reports and what the send button exists to produce.
     */
    fun lastSentLine(
        facts: WatchLinkFacts,
        nowMs: Long,
        relaySeconds: Int,
        readingsArriving: Boolean,
    ): String {
        val sent = facts.lastSentMs
            ?: return if (readingsArriving) {
                "No reading has gone out to the watch yet."
            } else {
                "No reading has gone out to the watch yet, because this phone is " +
                    "not receiving any."
            }
        val age = nowMs - sent
        val head = "Last reading sent to the watch ${ago(age)} ago."
        if (age < staleAfterMs(relaySeconds)) return head
        return head + if (readingsArriving) {
            " That is longer than the ${interval(relaySeconds)} update interval " +
                "allows for, so something is holding the relay up."
        } else {
            " Readings have stopped arriving at this phone, so there has been " +
                "nothing new to send."
        }
    }

    /** What the watch has actually confirmed, which is a different claim. */
    fun deliveryLine(facts: WatchLinkFacts, nowMs: Long): String {
        val delivered = facts.lastDeliveredMs
            ?: return "No watch has confirmed delivery yet. Sending a reading is " +
                "what asks one to."
        return "A watch last confirmed delivery ${ago(nowMs - delivered)} ago."
    }

    /**
     * The sentence printed under the send button.
     *
     * [reached] is the watches that took delivery, which the platform reports
     * per node, so a two-watch phone cannot be told "delivered" on the strength
     * of the wrong one.
     */
    fun sendOutcome(
        reached: List<String>,
        missed: List<String>,
        hadReading: Boolean,
        /**
         * True only when the capability lookup **answered** and named no watch.
         * A lookup that could not be made must not arrive here as `true`; see
         * [WatchNode.hasApp].
         */
        appMissing: Boolean,
        failure: String?,
    ): String {
        if (failure != null) return "Could not send: $failure"
        if (reached.isEmpty()) {
            if (missed.isEmpty()) return "Nothing to send to: no watch is connected."
            return "${nameList(missed)} did not take delivery. The watch is paired " +
                "but out of touch right now, so nothing reached it."
        }
        val head = if (hadReading) {
            "Delivered to ${nameList(reached)}."
        } else {
            "${nameList(reached)} answered, but there was no reading to send - this " +
                "phone has not received one yet."
        }
        val rest = when {
            appMissing ->
                " The watch is reachable, so the link itself is fine. Nothing on it " +
                    "is listening as far as this phone can see, so unless that is an " +
                    "older watch build that cannot announce itself, the reading " +
                    "arrived at a watch with no app to show it."
            missed.isNotEmpty() -> " ${nameList(missed)} did not answer."
            hadReading -> " It should be showing that reading within a few seconds."
            else -> ""
        }
        return head + rest
    }

    /** "Galaxy Watch7", or "Galaxy Watch7 and Watch5", or "three watches". */
    fun nameList(nodes: List<WatchNode>): String = nameList(nodes.map { it.name })

    @JvmName("nameListOfStrings")
    fun nameList(names: List<String>): String {
        val real = names.map { it.ifBlank { "An unnamed watch" } }
        return when (real.size) {
            0 -> "No watch"
            1 -> real[0]
            2 -> "${real[0]} and ${real[1]}"
            else -> real.dropLast(1).joinToString(", ") + " and " + real.last()
        }
    }

    /** The relay interval as the chip that set it reads. */
    fun interval(relaySeconds: Int): String =
        if (relaySeconds < 60) "$relaySeconds second" else "${relaySeconds / 60} minute"

    /**
     * An elapsed time in whole units, because "4 minutes" is a thing to act on
     * and "4 minutes 12 seconds" is a thing to read twice.
     *
     * Rounds down: claiming a reading is newer than it is would be the one
     * direction of error this whole tab exists to remove.
     */
    fun ago(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> plural(seconds, "second")
            seconds < 3600 -> plural(seconds / 60, "minute")
            seconds < 86_400 -> plural(seconds / 3600, "hour")
            else -> plural(seconds / 86_400, "day")
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

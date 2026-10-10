package com.sensirion.sps30.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Watch tab says, stated without a watch.
 *
 * ## The fault being guarded
 *
 * The captain's watch had no companion app on it. Every reading the phone
 * published was accepted by the Data Layer, synced to a watch where nothing was
 * listening, and dropped. The only symptom was a complication that never showed
 * a number, and nothing on either device said why - because the relay was
 * one-way and nothing was asking the far end anything.
 *
 * The fix is a sentence, so the test is about sentences. Every case below fixes
 * one thing the user must be told, and the two that matter most are the ones
 * that must never be told in the same words: **no watch** and **no app on the
 * watch** are different problems with different actions, and a tab that blurs
 * them is the tab we already had.
 *
 * Pure JVM, no Robolectric, no Play services: [WatchLink] is arithmetic over
 * facts, and [WearLink] is the half that gathers them. That split is what keeps
 * this in the fast pack.
 */
class WatchLinkTest {

    private val t0 = 1_754_000_000_000L
    private val watch = WatchNode(id = "n1", name = "Galaxy Watch7", nearby = true, hasApp = true)
    private val probed = WatchLinkFacts(probedAtMs = t0)

    // --- the fault this tab was built for -----------------------------------

    @Test fun aWatchWithNoAppOnItIsSaidInWords() {
        val report = WatchLink.assess(
            probed.copy(nodes = listOf(watch.copy(hasApp = false))),
            readingsArriving = true,
        )

        assertEquals(WatchHealth.BAD, report.health)
        assertTrue(
            "the headline must name the watch it is talking about: ${report.headline}",
            report.headline.contains("Galaxy Watch7"),
        )
        assertTrue(
            "the headline must say the app cannot be seen: ${report.headline}",
            report.headline.contains("cannot see the SPS30 watch app on it"),
        )
        // The phone reports its own view and stops there. It cannot tell an
        // absent watch app from an installed one that does not announce itself,
        // and the captain's watch was the second - so a flat "the app is not on
        // it" was a wrong certainty about a device this phone cannot inspect.
        assertFalse(
            "the phone must not assert an absence it cannot establish: ${report.headline}",
            report.headline.contains("is not on it"),
        )
        assertTrue(
            "an absence is not a diagnosis - it must say what to do: ${report.detail}",
            report.detail.contains("Install the SPS30 watch app"),
        )
    }

    @Test fun aCapabilityLookupThatFailedIsNeverReportedAsAMissingApp() {
        // The bug the captain hit. `WearLink.probe` swallowed a failed capability
        // lookup into an empty set, every node came back `hasApp = false`, and the
        // tab told him the watch app was not installed on a watch it was installed
        // on. "Could not ask" now travels as null and has its own sentence.
        val report = WatchLink.assess(
            probed.copy(nodes = listOf(watch.copy(hasApp = null))),
            readingsArriving = true,
        )

        assertEquals(
            "a question that was never answered is not a failure of the watch",
            WatchHealth.WARN,
            report.health,
        )
        assertFalse(
            "nothing here establishes the app is missing: ${report.headline}",
            report.headline.contains("cannot see") || report.headline.contains("not on it"),
        )
        assertTrue(
            "it must say the check did not happen: ${report.headline}",
            report.headline.contains("has not been checked"),
        )
        assertFalse(
            "telling the user to install an app they may already have is wrong advice",
            report.detail.contains("Install"),
        )
        assertTrue(
            "the way out is to ask the watch directly: ${report.detail}",
            report.detail.contains("Send a reading now"),
        )
        assertTrue("and that is exactly what the button does", report.canSend)
    }

    @Test fun oneWatchWithTheAppStillWinsOverOneThatCouldNotBeChecked() {
        // A phone paired to two watches must not be dragged to the worse of the
        // two answers by the one it could not look at.
        val report = WatchLink.assess(
            probed.copy(
                nodes = listOf(
                    watch,
                    watch.copy(id = "n2", name = "Watch5", hasApp = null),
                ),
            ),
            readingsArriving = true,
        )
        assertEquals(WatchHealth.GOOD, report.health)
        assertTrue(report.headline.contains("Galaxy Watch7"))
        assertFalse(
            "the unchecked one has nothing to say about the working one",
            report.headline.contains("Watch5"),
        )
    }

    @Test fun theHeadlineNamesOnlyTheWatchesItIsMakingAClaimAbout() {
        // A watch whose capability could not be read is not evidence of anything,
        // so it must not be swept into a sentence saying the app is not there.
        val report = WatchLink.assess(
            probed.copy(
                nodes = listOf(
                    watch.copy(hasApp = false),
                    watch.copy(id = "n2", name = "Watch5", hasApp = null),
                ),
            ),
            readingsArriving = true,
        )
        assertEquals(WatchHealth.BAD, report.health)
        assertTrue(report.headline.contains("Galaxy Watch7"))
        assertFalse(
            "Watch5 was never checked, so it is not one of the watches without the app",
            report.headline.contains("Watch5"),
        )
    }

    @Test fun noWatchAndNoAppAreNeverTheSameSentence() {
        // The distinction the whole tab turns on. "Pair a watch" and "install the
        // app on the watch you already paired" are different actions, and one of
        // them is wasted effort in the other's situation.
        val none = WatchLink.assess(probed, readingsArriving = true)
        val noApp = WatchLink.assess(
            probed.copy(nodes = listOf(watch.copy(hasApp = false))),
            readingsArriving = true,
        )

        assertNotEquals(none.headline, noApp.headline)
        assertTrue(none.headline.contains("No watch is connected"))
        // And neither is either of them the third state, "nobody looked".
        val notChecked = WatchLink.assess(
            probed.copy(nodes = listOf(watch.copy(hasApp = null))),
            readingsArriving = true,
        )
        assertNotEquals(notChecked.headline, noApp.headline)
        assertNotEquals(notChecked.headline, none.headline)
        assertFalse(
            "nothing is paired, so telling the user to install an app is wrong advice",
            none.detail.contains("Install"),
        )
        assertTrue("a watch IS paired here, so pairing is not the advice", noApp.detail.contains("Install"))
    }

    @Test fun aStaleWatchBuildIsCoveredByTheSameSentence() {
        // A watch app installed before it declared its capability cannot be told
        // apart from one that is absent - the phone genuinely does not know - so
        // the advice has to cover both or it is wrong half the time.
        val report = WatchLink.assess(
            probed.copy(nodes = listOf(watch.copy(hasApp = false))),
            readingsArriving = true,
        )
        assertTrue(
            "an older watch build must be offered as the other explanation: ${report.detail}",
            report.detail.contains("older build"),
        )
    }

    // --- the other states ----------------------------------------------------

    @Test fun nothingIsClaimedBeforeAnythingHasBeenAsked() {
        val report = WatchLink.assess(WatchLinkFacts(probing = true), readingsArriving = true)
        assertEquals(WatchHealth.UNKNOWN, report.health)
        assertFalse(
            "a probe in flight must not read as a verdict: ${report.headline}",
            report.headline.contains("No watch"),
        )
        assertFalse("nothing can be sent before anything is known", report.canSend)
    }

    @Test fun servicesThatWillNotAnswerAreTheirOwnState() {
        // "Could not ask" is not "the answer is no", and it has its own action.
        val report = WatchLink.assess(
            WatchLinkFacts(probedAtMs = t0, probeError = "API unavailable"),
            readingsArriving = true,
        )
        assertEquals(WatchHealth.BAD, report.health)
        assertTrue(report.headline.contains("cannot tell"))
        assertTrue("the fault itself belongs in the text", report.detail.contains("API unavailable"))
        assertFalse("there is nothing to send to until services answer", report.canSend)
    }

    @Test fun aGoodLinkSaysSoAndOffersTheTest() {
        val report = WatchLink.assess(probed.copy(nodes = listOf(watch)), readingsArriving = true)
        assertEquals(WatchHealth.GOOD, report.health)
        assertTrue(report.headline.contains("Galaxy Watch7"))
        assertTrue(report.headline.contains("has the SPS30 watch app"))
        assertTrue(report.canSend)
    }

    @Test fun aDeadSensorStreamIsNotBlamedOnTheWatch() {
        // The link is perfect and there is simply nothing to send. Reporting that
        // as a watch problem sends the user to the wrong device.
        val report = WatchLink.assess(probed.copy(nodes = listOf(watch)), readingsArriving = false)
        assertEquals(WatchHealth.GOOD, report.health)
        assertTrue(
            "it must name the sensor connection, not the watch: ${report.detail}",
            report.detail.contains("not receiving any readings"),
        )
    }

    @Test fun aWatchReachableOnlyTheLongWayRoundIsAWarningNotAFailure() {
        val report = WatchLink.assess(
            probed.copy(nodes = listOf(watch.copy(nearby = false))),
            readingsArriving = true,
        )
        assertEquals(WatchHealth.WARN, report.health)
        assertTrue("it still works, so sending must stay available", report.canSend)
        assertTrue(report.detail.contains("Bluetooth range"))
    }

    // --- when a reading last went out ----------------------------------------

    @Test fun theSentLineNeverClaimsArrival() {
        // Handing a DataItem to the Data Layer proves the phone did its part and
        // nothing whatever about the watch. Saying "reached" here would recreate
        // the original lie in a new place.
        val line = WatchLink.lastSentLine(
            probed.copy(lastSentMs = t0 - 30_000),
            nowMs = t0,
            relaySeconds = 60,
            readingsArriving = true,
        )
        assertTrue(line, line.contains("sent to the watch 30 seconds ago"))
        assertFalse(line, line.contains("arrived"))
        assertFalse(line, line.contains("received"))
    }

    @Test fun aGapLongerThanTheIntervalIsCalledOut() {
        val line = WatchLink.lastSentLine(
            probed.copy(lastSentMs = t0 - 40 * 60_000),
            nowMs = t0,
            relaySeconds = 300,
            readingsArriving = true,
        )
        assertTrue(line, line.contains("holding the relay up"))
    }

    @Test fun aLongGapWithNoReadingsBlamesTheSensorLink() {
        val line = WatchLink.lastSentLine(
            probed.copy(lastSentMs = t0 - 40 * 60_000),
            nowMs = t0,
            relaySeconds = 300,
            readingsArriving = false,
        )
        assertTrue(line, line.contains("stopped arriving at this phone"))
        assertFalse("nothing is holding the relay up: there is nothing to hold", line.contains("holding"))
    }

    @Test fun aSlowIntervalDoesNotMakeAHealthyRelayLookStalled() {
        // The 15-minute chip is a supported setting. A fixed staleness threshold
        // would paint the link red between two perfectly intended updates.
        val fifteenMinutes = 900
        val line = WatchLink.lastSentLine(
            probed.copy(lastSentMs = t0 - 16 * 60_000),
            nowMs = t0,
            relaySeconds = fifteenMinutes,
            readingsArriving = true,
        )
        assertFalse(line, line.contains("holding the relay up"))
    }

    @Test fun theStalenessThresholdFollowsWhicheverGapIsLonger() {
        // Below the heartbeat the heartbeat rules, because a still reading is
        // pushed on that and nothing else; above it the user's own interval does.
        assertEquals(2 * RelayPolicy.HEARTBEAT_MS, WatchLink.staleAfterMs(60))
        assertEquals(2 * 900_000L, WatchLink.staleAfterMs(900))
    }

    @Test fun deliveryIsReportedSeparatelyFromSending() {
        val never = WatchLink.deliveryLine(probed.copy(lastSentMs = t0), nowMs = t0)
        assertTrue(
            "a reading having been sent is not a watch having confirmed it: $never",
            never.contains("No watch has confirmed delivery yet"),
        )
        val once = WatchLink.deliveryLine(probed.copy(lastDeliveredMs = t0 - 120_000), nowMs = t0)
        assertTrue(once, once.contains("confirmed delivery 2 minutes ago"))
    }

    // --- the send button's verdict -------------------------------------------

    @Test fun aDeliveredTestSaysWhichWatchTookIt() {
        val text = WatchLink.sendOutcome(
            reached = listOf("Galaxy Watch7"),
            missed = emptyList(),
            hadReading = true,
            appMissing = false,
            failure = null,
        )
        assertTrue(text, text.contains("Delivered to Galaxy Watch7"))
    }

    @Test fun deliveryToAWatchWithNoAppIsNotReportedAsSuccess() {
        // The trap: the node takes delivery and there is still nothing there to
        // show it. Saying only "delivered" would be the original silent failure
        // dressed up as a green tick.
        val text = WatchLink.sendOutcome(
            reached = listOf("Galaxy Watch7"),
            missed = emptyList(),
            hadReading = true,
            appMissing = true,
            failure = null,
        )
        assertTrue(text, text.contains("Nothing on it is listening"))
    }

    @Test fun aWatchThatDidNotAnswerIsNamed() {
        val text = WatchLink.sendOutcome(
            reached = emptyList(),
            missed = listOf("Galaxy Watch7"),
            hadReading = true,
            appMissing = false,
            failure = null,
        )
        assertTrue(text, text.contains("did not take delivery"))
    }

    @Test fun sendingWithNoReadingSaysThatRatherThanClaimingOne() {
        val text = WatchLink.sendOutcome(
            reached = listOf("Galaxy Watch7"),
            missed = emptyList(),
            hadReading = false,
            appMissing = false,
            failure = null,
        )
        assertTrue(text, text.contains("no reading to send"))
        assertFalse(text, text.contains("Delivered to"))
    }

    @Test fun aFailureIsQuotedRatherThanSummarised() {
        val text = WatchLink.sendOutcome(
            reached = emptyList(),
            missed = emptyList(),
            hadReading = true,
            appMissing = false,
            failure = "Wearable API unavailable",
        )
        assertEquals("Could not send: Wearable API unavailable", text)
    }

    // --- the small print -----------------------------------------------------

    @Test fun elapsedTimeRoundsDownAndNeverPluralisesOne() {
        // Rounding up would make a reading look newer than it is, which is the
        // one direction of error this tab exists to remove.
        assertEquals("59 seconds", WatchLink.ago(59_999))
        assertEquals("1 minute", WatchLink.ago(60_000))
        assertEquals("1 minute", WatchLink.ago(119_000))
        assertEquals("2 hours", WatchLink.ago(2 * 3_600_000 + 59_000))
        assertEquals("1 day", WatchLink.ago(86_400_000))
        assertEquals("0 seconds", WatchLink.ago(-5))
    }

    @Test fun watchesAreNamedAsAListAndNeverAsBlank() {
        assertEquals("Galaxy Watch7", WatchLink.nameList(listOf(watch)))
        assertEquals(
            "Galaxy Watch7 and Watch5",
            WatchLink.nameList(listOf(watch, watch.copy(id = "n2", name = "Watch5"))),
        )
        assertEquals("An unnamed watch", WatchLink.nameList(listOf(watch.copy(name = ""))))
    }

    @Test fun oneWatchWithTheAppIsEnoughEvenBesideOneWithout() {
        // Two paired watches, one of them a bare one. The tab must describe the
        // one that can actually show a reading rather than the alphabetically
        // first.
        val bare = WatchNode("n2", "Watch5", nearby = true, hasApp = false)
        val report = WatchLink.assess(
            probed.copy(nodes = listOf(bare, watch)),
            readingsArriving = true,
        )
        assertEquals(WatchHealth.GOOD, report.health)
        assertTrue(report.headline, report.headline.contains("Galaxy Watch7"))
        assertFalse(report.headline, report.headline.contains("Watch5"))
    }
}

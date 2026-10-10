package com.sensirion.sps30.ui

import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Sensor tab says about the controller's roster, and when it may say it.
 *
 * Every line here is a claim about a machine somewhere else, held in fields the
 * controller filled in and nothing clears when the link drops. The three faults
 * guarded below are all the same shape as the Watch tab's - a default or a stale
 * value being read out as a present-tense fact - and it is the shape the captain
 * asked to have swept out of the options tabs.
 */
class SharedAccessTest {

    private fun watcher(id: Int, name: String) = ServerMessage.Watcher(id = id, name = name)

    private val me = watcher(1, "This phone")
    private val bob = watcher(2, "Bob")

    // --- who is connected ----------------------------------------------------

    @Test fun aDisconnectedAppDoesNotSayItIsConnected() {
        // The first fault: `watchers.isEmpty() -> "Connected."`. An app that had
        // never found a sensor said "Connected." on its Sensor tab, because an
        // empty roster is the default and the line never asked about the link.
        val line = SharedAccess.watchersLine(connected = false, watchers = emptyList(), others = emptyList())
        assertFalse(line, line.contains("Connected."))
        assertTrue(line, line.contains("Not connected"))
        assertTrue("it says why it cannot answer: $line", line.contains("not known"))
    }

    @Test fun aRosterLeftOverFromADroppedLinkIsNotReportedAsNow() {
        // The second: nothing clears `watchers` on disconnect, so the last
        // connection's roster sat there naming people who were no longer
        // reachable, in the present tense.
        val line = SharedAccess.watchersLine(
            connected = false,
            watchers = listOf(me, bob),
            others = listOf(bob),
        )
        assertFalse("Bob is not connected to anything this phone can see: $line", line.contains("Bob"))
        assertTrue(line, line.contains("Not connected"))
    }

    @Test fun aRosterNotYetSentIsWaitedFor() {
        // Connected, but the controller has not sent `clients` yet. "Connected."
        // answered a question about this phone in the place of an answer about
        // everybody else.
        val line = SharedAccess.watchersLine(connected = true, watchers = emptyList(), others = emptyList())
        assertTrue(line, line.contains("Waiting for the sensor"))
    }

    @Test fun aRealRosterIsStillReportedExactly() {
        assertEquals(
            "You are the only one connected.",
            SharedAccess.watchersLine(connected = true, watchers = listOf(me), others = emptyList()),
        )
        assertEquals(
            "You and Bob are connected.",
            SharedAccess.watchersLine(connected = true, watchers = listOf(me, bob), others = listOf(bob)),
        )
        val many = SharedAccess.watchersLine(
            connected = true,
            watchers = listOf(me, bob, watcher(3, "Cat")),
            others = listOf(bob, watcher(3, "Cat")),
        )
        assertTrue(many, many.contains("You and 2 others are connected: Bob, Cat"))
    }

    // --- who has control -----------------------------------------------------

    @Test fun nobodyHavingControlIsNotTheSameAsNobodyHavingBeenAsked() {
        // The third, and the exact flavour of the watch-tab bug: `controlHolder`
        // is null both when the lock is free and when the controller has never
        // said, and "Nobody has control" was printed for either.
        val notAsked = SharedAccess.controlLine(
            connected = true, holderIsMe = false, holder = null, holderName = null, told = false,
        )
        assertFalse(notAsked, notAsked.contains("Nobody has control"))
        assertTrue(notAsked, notAsked.contains("not known"))

        val asked = SharedAccess.controlLine(
            connected = true, holderIsMe = false, holder = null, holderName = null, told = true,
        )
        assertEquals("Nobody has control", asked)
    }

    @Test fun aDisconnectedAppClaimsNothingAboutTheLock() {
        val line = SharedAccess.controlLine(
            connected = false, holderIsMe = true, holder = 1, holderName = "This phone", told = true,
        )
        assertEquals("Not connected", line)
    }

    @Test fun theHolderIsStillNamedWhenThereIsOne() {
        assertEquals(
            "You have control",
            SharedAccess.controlLine(
                connected = true, holderIsMe = true, holder = 1, holderName = "This phone", told = true,
            ),
        )
        assertEquals(
            "Bob has control",
            SharedAccess.controlLine(
                connected = true, holderIsMe = false, holder = 2, holderName = "Bob", told = true,
            ),
        )
        assertEquals(
            "an unnamed holder is still a holder",
            "Someone else has control",
            SharedAccess.controlLine(
                connected = true, holderIsMe = false, holder = 2, holderName = null, told = true,
            ),
        )
    }

    // --- the lease note ------------------------------------------------------

    @Test fun theLeaseNoteDoesNotDescribeALeaseThatCannotBeTaken() {
        // "Control is taken automatically the first time you change something" is
        // false with no connection: there is nothing to change it over.
        val offline = SharedAccess.leaseNote(connected = false, holder = null)
        assertFalse(offline, offline.contains("the first time you change something"))
        assertTrue(offline, offline.contains("until this phone is connected"))

        assertTrue(
            SharedAccess.leaseNote(connected = true, holder = null)
                .contains("taken automatically"),
        )
        assertTrue(
            SharedAccess.leaseNote(connected = true, holder = 2).contains("two minutes"),
        )
    }
}

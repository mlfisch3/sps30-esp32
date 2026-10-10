package com.sensirion.sps30.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The options menu was one long scroll, and the fix was tabs with a stated
 * ceiling. The ceiling is the point - sparse tabs waste the width of the ones
 * carrying real content, and a fixed tab strip divides a phone's width equally
 * however many there are, so every tab added shortens every label.
 *
 * It was three to five until the Watch tab; six is the honest limit of that
 * strip on a 411dp phone, and the sixth was paid for by shortening the fifth's
 * label from "Customize" to "Display". A seventh has to displace one of these
 * rather than join them.
 *
 * Layout itself is not testable here; what is testable is the shape of the
 * grouping, which is where the decision lives.
 */
class OptionsTabsTest {

    /**
     * Characters a label may run to before it has to wrap or shrink, at the
     * width a fixed strip of this many tabs leaves on a phone.
     */
    private fun labelBudget(tabs: Int) = when {
        tabs <= 5 -> 10
        else -> 8
    }

    @Test fun thereAreBetweenThreeAndSixTabs() {
        val count = OptionsTab.entries.size
        assertTrue("$count tabs is too few to be worth tabbing", count >= 3)
        assertTrue(
            "$count tabs: a seventh group must replace one, not join them",
            count <= 6,
        )
    }

    @Test fun everyTabHasAShortDistinctLabel() {
        val labels = OptionsTab.entries.map { it.label }
        assertEquals("labels must be distinct", labels.size, labels.toSet().size)
        val budget = labelBudget(labels.size)
        for (label in labels) {
            assertTrue("\"$label\" is blank", label.isNotBlank())
            assertTrue(
                "\"$label\" is too long for one of ${labels.size} tabs across a phone",
                label.length <= budget,
            )
        }
    }

    @Test fun helpIsOneOfThem() {
        // Item 3: the clockless explanation was taken off the main screen on the
        // understanding that it had somewhere to go.
        assertTrue(OptionsTab.entries.contains(OptionsTab.HELP))
    }

    @Test fun theWatchHasATabOfItsOwn() {
        // The relay is one-way, so the app's only honest account of it is what
        // this tab says. Before it existed the sole watch control was an update
        // interval filed under display settings: how often to send to a link
        // whose state nothing anywhere reported. See `ui/WatchSection.kt`.
        assertTrue(OptionsTab.entries.contains(OptionsTab.WATCH))
    }

    @Test fun displayIsLeftmost() {
        // Asked for directly, and the ordering principle agrees with the ask:
        // the tabs run outward from the plot in front of you, and Display is the
        // one whose settings act on that plot. See the enum's own KDoc.
        assertEquals(OptionsTab.CUSTOMIZE, OptionsTab.entries.first())
        assertEquals("Display", OptionsTab.entries.first().label)
    }

    @Test fun theTabsRunOutwardsFromThePlotWithHelpLast() {
        // The whole order, asserted as one thing, because the principle is about
        // the sequence and not about any single neighbour: what you look at,
        // what the app watches for you, the instrument, the record, the far end
        // of the relay - then reference.
        assertEquals(
            listOf(
                OptionsTab.CUSTOMIZE,
                OptionsTab.ALERTS,
                OptionsTab.SENSOR,
                OptionsTab.DATA,
                OptionsTab.WATCH,
                OptionsTab.HELP,
            ),
            OptionsTab.entries.toList(),
        )
    }

    @Test fun theGuardedTabsAreNotUnderTheThumbThatOpenedTheMenu() {
        // Sensor and Data are the two that can disturb the instrument or destroy
        // data. Neither should be where the menu lands, and neither should be
        // one slot from it either.
        for (guarded in listOf(OptionsTab.SENSOR, OptionsTab.DATA)) {
            assertTrue(
                "$guarded is at index ${guarded.ordinal}, next to the tab the gear opens on",
                guarded.ordinal >= 2,
            )
        }
    }

    @Test fun theSheetOpensOnTheSameTabTheGearAlwaysOpens() {
        // Not an arbitrary default: DashboardScreen sends the gear to
        // `entries.first()`, and a menu that opens somewhere different each time
        // cannot be learned.
        assertEquals(OptionsTab.CUSTOMIZE, OptionsTab.entries.first())
    }
}

/**
 * Leaving a tab closes the guards on it. The 20 s idle timeout does not cover
 * this case: switching tabs is interaction, so it restarts the countdown on a
 * guard nobody can see any more.
 */
class SafetyOnLeavingTest {

    @Test fun leavingClosesAnOpenGuard() {
        val safety = SafetyState()
        safety.toggle()
        assertFalse("precondition: the user opened it", safety.engaged)

        safety.engageOnLeaving()
        assertTrue("leaving the tab must close it", safety.engaged)
    }

    @Test fun leavingDoesNotClaimTheTimeoutFired() {
        // The distinction the user sees: "the safety re-engaged after 20 seconds
        // unused" is an apology, and it would be a false one here.
        val safety = SafetyState()
        safety.toggle()
        safety.engageOnLeaving()
        safety.reportBlockedTap()

        val notice = safety.notice
        assertTrue("a blocked tap must still say something", notice != null)
        assertFalse(
            "nothing timed out, so it must not say so: $notice",
            notice!!.contains("20 seconds"),
        )
    }

    @Test fun leavingClearsAnyNoticeOnScreen() {
        val safety = SafetyState()
        safety.reportBlockedTap()
        assertTrue(safety.notice != null)

        safety.engageOnLeaving()
        assertNull("a notice about the tab you left is noise", safety.notice)
    }

    @Test fun aTimeoutStillApologises() {
        // Guarding the other half of the distinction, so this does not quietly
        // become "never mentions the timeout".
        val safety = SafetyState()
        safety.toggle()
        safety.reengage()
        safety.reportBlockedTap()
        assertTrue(safety.notice!!.contains("20 seconds"))
    }
}

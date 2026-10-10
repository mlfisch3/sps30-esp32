package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sensirion.sps30.Slow
import com.sensirion.sps30.data.SessionTransfer
import com.sensirion.sps30.ui.theme.Sps30Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A line explaining something that did not happen has to be able to retire.
 *
 * On the tablet, "Sending stopped: the connection dropped while sending" sat in
 * the Data tab across a dozen unrelated actions - sessions loaded, the
 * window changed, other tabs opened and closed - because the only way out of it
 * was the Dismiss button. A stale explanation of a minutes-old failure reads as
 * the current state of the app, and the longer it stays the more it is believed.
 *
 * What must *not* happen is the opposite fault: an outcome that carries the
 * numbers the operation was run for cannot be taken away on a timer, so the
 * cases with counts in them stay until they are dismissed by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(Slow::class)
class TransferNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    private fun showing(transfer: SessionTransfer) {
        // The clock is driven by hand: with it advancing on its own, the very
        // first idle would jump past the whole interval and there would be no
        // "before" to assert about.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Sps30Theme(useDarkTheme = true) {
                Column { TransferStatus(transfer, onDismiss = { dismissed++ }) }
            }
        }
    }

    private val failed = SessionTransfer.Failed(
        file = "sps30-sessions.zip",
        reason = "the connection dropped while sending",
    )

    @Test
    fun aFailedTransfersMessageRetiresItself() {
        showing(failed)
        compose.onNodeWithText("Sending stopped", substring = true).assertExists()
        assertEquals("it went before it could be read", 0, dismissed)

        // Long enough after that nothing on screen is about what is happening now.
        compose.mainClock.advanceTimeBy(60_000L)
        assertEquals(
            "the failure message is still there, and the only way out of it is " +
                "still the Dismiss button",
            1,
            dismissed,
        )
    }

    @Test
    fun itIsStillThereLongEnoughToRead() {
        showing(failed)
        compose.mainClock.advanceTimeBy(10_000L)
        assertEquals("the message retired before it could be read", 0, dismissed)
        compose.onNodeWithText("Sending stopped", substring = true).assertExists()
    }

    @Test
    fun aRefusedFileRetiresTooBecauseItSaysNothingHappened() {
        showing(SessionTransfer.Rejected(file = "notes.csv", reason = "it has no readings in it"))
        compose.mainClock.advanceTimeBy(60_000L)
        assertEquals("a refusal is an explanation too, and outlived what it explained", 1, dismissed)
    }

    /**
     * The counts stay. "Added 52,226 readings" is the answer to the question the
     * user asked, and a timer that takes it away is the old vanishing-toast fault
     * wearing a slower costume.
     */
    @Test
    fun anOutcomeWithCountsInItIsNotTakenAway() {
        showing(
            SessionTransfer.Done(
                file = "sps30-sessions.zip",
                added = 52_226,
                duplicate = 0,
                rejected = 0,
                sessions = 16,
                ranOutOfRoom = false,
                refused = false,
                message = "",
            ),
        )
        compose.mainClock.advanceTimeBy(120_000L)
        assertEquals("the result of the transfer was taken off the screen", 0, dismissed)
        compose.onNodeWithText("Added 52,226", substring = true).assertExists()
    }
}

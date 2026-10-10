package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.Slow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tag the guarded section under test carries, so its bounds can be read. */
private const val GUARDED = "guarded-section"

/**
 * The least clearance a line of content may have from the plate's own edge.
 *
 * More than the 1.5dp border stroke on purpose: a glyph that merely misses the
 * stroke still sits in the corner radius and reads as cramped.
 */
private val BORDER_CLEARANCE = 4.dp

/**
 * The "SAFETY ON" badge must not be drawn on top of anything.
 *
 * ## The fault this guards
 *
 * On the tablet the badge landed exactly on the middle of the Sensor section's
 * three buttons and hid **Clean sensor** outright. A user saw two buttons where
 * there are three, and nothing on the screen said otherwise - the only way to
 * discover the third control was to flip the safety and watch it appear. The same
 * badge covered a line of the Measurement section's explanatory paragraph.
 *
 * Neither was a guard failure: the guard blocked taps correctly throughout. It was
 * only where the label was drawn, which is why nothing in the suite noticed. A
 * covered button is still in the semantics tree, still `assertIsDisplayed()`, and
 * still clickable by a test that never has to look at it.
 *
 * ## Why testing [GuardedSection] tests every guard
 *
 * `SafetyGuard` - the plate itself - is private to `Safety.kt`. [GuardedSection]
 * is the only way to raise a guard from anywhere else in the app, so the three
 * real guarded sections (Measurement, Sensor, Erasing) all get their geometry from
 * the code measured here, and a fourth cannot be added that bypasses it without
 * failing to compile.
 *
 * The two content shapes below are the two that failed on the tablet: a row of
 * buttons first, and a paragraph first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class SafetyBadgeLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val badge = "SAFETY ON"

    /** The Sensor section's shape: three buttons across, then a closing note. */
    @Composable
    private fun buttonsFirst() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Pause sensor") }
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Clean sensor") }
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Restart") }
        }
        ControlNote("This controller has nothing to shut down.")
    }

    /** The Measurement section's shape: the scope paragraph, then the mode chips. */
    @Composable
    private fun paragraphFirst() {
        ControlNote(
            "These set what the controller records and how often. They do not " +
                "power the sensor down - only Low power ever sleeps it.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {}) { Text("Continuous") }
            Button(onClick = {}) { Text("Low power") }
            Button(onClick = {}) { Text("Paused") }
        }
    }

    private fun assertBadgeCovers(nothing: List<String>) {
        // Unmerged: the plate is `clickable`, which merges its descendants'
        // semantics, so the merged tree reports the badge's bounds as the whole
        // plate and every comparison below would trivially "overlap".
        val badgeBounds = compose
            .onNodeWithText(badge, substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        for (label in nothing) {
            val covered = compose
                .onNodeWithText(label, substring = true, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "the SAFETY ON badge ${badgeBounds.pretty()} overlaps " +
                    "\"$label\" ${covered.pretty()}",
                !badgeBounds.overlaps(covered),
            )
        }
    }

    @Test
    fun theBadgeDoesNotCoverAnyOfThreeButtons() {
        compose.setContentAtWidth(OPTIONS_SHEET_WIDTH) {
            GuardedSection(rememberSafetyState(interaction = 0)) { buttonsFirst() }
        }
        // "Clean sensor" is the one that vanished on the tablet, and it vanished
        // because it is the middle of three under a centred badge. The other two
        // are here so a fix that only moves the badge sideways cannot pass.
        assertBadgeCovers(listOf("Pause sensor", "Clean sensor", "Restart"))
    }

    @Test
    fun theBadgeDoesNotCoverTheExplanatoryParagraph() {
        compose.setContentAtWidth(OPTIONS_SHEET_WIDTH) {
            GuardedSection(rememberSafetyState(interaction = 0)) { paragraphFirst() }
        }
        assertBadgeCovers(listOf("These set what the controller records", "Continuous"))
    }

    /**
     * The narrow case, where a badge sized in `sp` has least room to miss things.
     *
     * A guarded section in the options sheet on a small phone is the worst width
     * this has to survive; the tablet was merely where it was noticed.
     */
    @Test
    fun theBadgeStillCoversNothingInANarrowSheet() {
        compose.setContentAtWidth(280.dp) {
            GuardedSection(rememberSafetyState(interaction = 0)) { buttonsFirst() }
        }
        assertBadgeCovers(listOf("Pause sensor", "Clean sensor", "Restart"))
    }

    /**
     * The guard's border must not be drawn through the content it guards.
     *
     * ## A second, separate way for a plate to spoil what is under it
     *
     * The badge covers no control - that was the first fault and it stayed
     * fixed - but the plate also has a *border*, drawn at full width, and the
     * content was laid out at full width too. So the border ran down the first
     * and last column of pixels of every line, and on the Sensor tab it sliced
     * the "T" of "These set what the controller records" and the "R" of "Read
     * the sensor every" lengthwise. Nothing was covered and nothing overflowed:
     * the container and its content disagreed about where the container's edge
     * was, which is a fault a screenshot shows instantly and no assertion about
     * text or semantics can see.
     *
     * Measured against the section's own bounds rather than a fixed number of
     * dp, so it is the *relationship* being held - a wider border or a bigger
     * corner radius has to bring its own room with it.
     */
    private fun assertContentClearsTheBorder(labels: List<String>, whole: Boolean = false) {
        val section = compose.onNodeWithTag(GUARDED).getUnclippedBoundsInRoot()
        for (label in labels) {
            // A button's own box is what meets the border, not the words inside
            // it, and the merged tree is where that box is: `Button` merges its
            // label's semantics into itself. Text stands for itself, unmerged.
            val bounds = compose
                .onNodeWithText(label, substring = true, useUnmergedTree = !whole)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "\"$label\" ${bounds.pretty()} starts at the guard's own left edge " +
                    "${section.pretty()}, so the border is drawn through its first glyph",
                bounds.left.value - section.left.value >= BORDER_CLEARANCE.value,
            )
            assertTrue(
                "\"$label\" ${bounds.pretty()} runs to the guard's own right edge " +
                    "${section.pretty()}, so the border is drawn through its last glyph",
                section.right.value - bounds.right.value >= BORDER_CLEARANCE.value,
            )
        }
    }

    @Test
    fun theGuardsBorderIsNotDrawnThroughTheParagraph() {
        compose.setContentAtWidth(OPTIONS_SHEET_WIDTH) {
            GuardedSection(
                rememberSafetyState(interaction = 0),
                modifier = Modifier.testTag(GUARDED),
            ) { paragraphFirst() }
        }
        assertContentClearsTheBorder(listOf("These set what the controller records"))
    }

    @Test
    fun theGuardsBorderIsNotDrawnThroughTheButtons() {
        compose.setContentAtWidth(OPTIONS_SHEET_WIDTH) {
            GuardedSection(
                rememberSafetyState(interaction = 0),
                modifier = Modifier.testTag(GUARDED),
            ) { buttonsFirst() }
        }
        // The first and last of a full-width row are the two that met the edges.
        assertContentClearsTheBorder(listOf("Pause sensor", "Restart"), whole = true)
    }

    /** The narrow sheet again: least room, same rule. */
    @Test
    fun theGuardsBorderIsNotDrawnThroughANarrowSheetsText() {
        compose.setContentAtWidth(280.dp) {
            GuardedSection(
                rememberSafetyState(interaction = 0),
                modifier = Modifier.testTag(GUARDED),
            ) { paragraphFirst() }
        }
        assertContentClearsTheBorder(listOf("These set what the controller records"))
    }

    /**
     * Disengaging the safety must not move the controls.
     *
     * The badge is kept clear of the buttons by a reserved strip at the top of the
     * section, and the tempting saving is to give that strip back when the guard
     * lifts. It must not be: the user disengages a safety in order to press
     * something, so reclaiming the gap would slide every button upwards at the
     * exact moment a finger is on the way down, and a tap meant for Clean sensor
     * would land on whatever rose into its place.
     */
    @Test
    fun liftingTheGuardDoesNotMoveTheButtons() {
        val safety = SafetyStateProbe()
        compose.setContentAtWidth(OPTIONS_SHEET_WIDTH) {
            GuardedSection(safety.remembered()) { buttonsFirst() }
        }
        val engaged = compose.onNodeWithText("Clean sensor", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        compose.runOnUiThread { safety.toggle() }
        compose.waitForIdle()

        val disengaged = compose.onNodeWithText("Clean sensor", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals(
            "Clean sensor moved when the guard lifted: " +
                "${engaged.pretty()} -> ${disengaged.pretty()}",
            engaged.top.value,
            disengaged.top.value,
            0.01f,
        )
    }
}

/** Holds one [SafetyState] across recompositions so a test can work its switch. */
private class SafetyStateProbe {
    private var state: SafetyState? = null

    @Composable
    fun remembered(): SafetyState = rememberSafetyState(interaction = 0).also { state = it }

    fun toggle() = state!!.toggle()
}

package com.sensirion.sps30.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The sentence that explains a row of controls, printed under it.
 *
 * ## Why this is a composable and not just a `Text`
 *
 * There is one rule here and it is easy to break by accident: **an explanation
 * wraps, it never truncates.** A label may be cut - "Continuous", "Wi-Fi",
 * "1 unplaced" are recognisable from their first few letters and the control they
 * sit on says the rest. A sentence is not: it is carrying the one piece of
 * information nothing else on screen carries, and cutting it removes exactly
 * that.
 *
 * The y-axis override hint is what taught this. It was a `maxLines = 1` Text
 * sharing a Row with the Lock and Release chips, and in the landscape control
 * column the chips took the width and left it "autoscale is holding yo…" - cut
 * one word before the word that says autoscale is still on. Worse, the *locked*
 * message was shorter and fit, so the state that rendered correctly was the
 * second one the user reached and the broken one was the first.
 *
 * So a note is never measured against a control row's leftovers. It goes on its
 * own line below the controls, at full width, with no line limit, and then no
 * column is narrow enough to cut it - only tall enough to hold more of it.
 *
 * `bodySmall` in the muted colour throughout, so notes read as one voice wherever
 * they appear.
 */
@Composable
internal fun ControlNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

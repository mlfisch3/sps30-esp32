package com.sensirion.sps30.service

import com.sensirion.sps30.Slow
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.ui.DashboardUiState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The readout has to fit the line a collapsed notification actually gives it.
 *
 * A notification's content text is one line and it ellipsizes: anything past the
 * end is not shortened, it is *gone*. Adding a second decimal to each number and
 * a unit that has to stay legible is exactly the kind of change that pushes the
 * right-hand value off the edge on a phone while looking fine in a string
 * assertion - so this measures it with the real font rather than counting
 * characters.
 *
 * ## The width
 *
 * The captain's S24 in portrait is 360dp wide. The platform notification
 * template insets the text by 16dp on the left and leaves the right-hand column
 * to the expander and the app icon, about 40dp plus its own 16dp margin, so the
 * content text gets roughly 288dp. [CONTENT_DP] is set below that, at 280dp, so
 * a phone slightly narrower than an S24 is still covered.
 *
 * Body text in the notification template is 14sp. Font scale is deliberately not
 * modelled: at a large accessibility scale nothing of this length fits any
 * notification, and the platform's own header does not fit either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(Slow::class)
class NotificationWidthTest {

    private companion object {
        /** Text width on a 360dp phone, less the template's insets and icon column. */
        const val CONTENT_DP = 280f

        /** `Notification.TextAppearance.Material.Notification` body size. */
        const val TEXT_SP = 14f

        /** The S24's density. Both sides scale by it, but real fonts hint per size. */
        const val DENSITY = 3f
    }

    private fun readoutFor(latest: Map<String, Double>): String =
        Notifications.contentOf(
            DashboardUiState(
                connection = ConnectionState.Connected("192.168.1.226:8730"),
                daemonState = "measuring",
                latest = latest,
            ),
        ).text

    private fun widthPx(text: String): Float =
        android.graphics.Paint().apply { textSize = TEXT_SP * DENSITY }.measureText(text)

    private fun assertFits(what: String, latest: Map<String, Double>) {
        val text = readoutFor(latest)
        val used = widthPx(text)
        val available = CONTENT_DP * DENSITY
        // A measurement of nothing would pass every assertion below it. If the
        // host has no font for Robolectric to load, this says so instead.
        assertTrue(
            "the font measured \"$text\" as ${used}px, so nothing was really measured " +
                "and the width below is not being checked at all",
            used > text.length * TEXT_SP * DENSITY * 0.2f,
        )
        println("notification readout: \"$text\" = ${"%.0f".format(used)}px of ${"%.0f".format(available)}px")
        assertTrue(
            "$what draws as \"$text\", which measures ${"%.0f".format(used)}px at 14sp " +
                "and the notification's line is only ${"%.0f".format(available)}px - the " +
                "right-hand value or the unit is cut off on a 360dp phone",
            used <= available,
        )
    }

    @Test fun anIndoorReadingFits() {
        assertFits("a normal indoor reading", mapOf("pm1_0" to 8.4, "pm2_5" to 9.7))
    }

    /**
     * Smoke events reach the hundreds, which is when the notification matters
     * most and when the string is at its longest.
     */
    @Test fun aSmokeEventStillFitsWithItsUnit() {
        assertFits("a smoke event", mapOf("pm1_0" to 123.45, "pm2_5" to 234.56))
    }

    /** Four digits and two decimals on both, past anything the sensor reports. */
    @Test fun theWidestNumberTheSensorCanReportFits() {
        assertFits("the sensor's ceiling", mapOf("pm1_0" to 1000.0, "pm2_5" to 1000.0))
    }
}

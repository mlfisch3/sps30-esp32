package com.sensirion.sps30.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sensirion.sps30.MainActivity
import com.sensirion.sps30.R
import com.sensirion.sps30.data.PlottedSeries
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.ui.DashboardUiState
import com.sensirion.sps30.ui.SeriesColors

/** Builds and channels the ongoing foreground-monitoring notification. */
object Notifications {
    const val ID = 42
    private const val CHANNEL = "sps30_monitor"

    /**
     * Everything about the ongoing notification that a user can see.
     *
     * Named so that "has it changed?" is a question that can be asked, which it
     * could not be of a `Notification` - that object has no useful equality, so
     * the service posted one for every emission of the session's state, whether
     * or not anything on it had moved. Measured on the tablet: 2-3.5 posts a
     * second at idle and up to 53 a second during a transfer, 23,157 of them in
     * 39 minutes, all of them redrawing the same two lines.
     */
    data class Content(val title: String, val text: String)

    /** The two lines [build] will draw, for a state. */
    fun contentOf(state: DashboardUiState): Content = Content(title(state), readout(state))

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(
                CHANNEL,
                "SPS30 monitoring",
                NotificationManager.IMPORTANCE_LOW, // ongoing, silent
            ).apply {
                description = "Keeps the live particulate-matter stream running in the background."
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, state: DashboardUiState): Notification =
        build(context, contentOf(state))

    fun build(context: Context, content: Content): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, Sps30Service::class.java).setAction(Sps30Service.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sps30)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun title(state: DashboardUiState): String {
        if (state.discovering) return "SPS30 · searching for the sensor…"
        if (state.discoveryFailed) return "SPS30 · no sensor found"
        return when (val c = state.connection) {
            is ConnectionState.Connected ->
                "SPS30 · ${state.daemonState ?: "connected"} · ${c.endpoint}"
            is ConnectionState.Connecting -> "SPS30 · connecting…"
            is ConnectionState.Reconnecting -> "SPS30 · reconnecting…"
            is ConnectionState.Searching -> "SPS30 · searching for the sensor…"
            is ConnectionState.NotFound -> "SPS30 · ${c.detail}"
            ConnectionState.Idle -> "SPS30 monitoring"
        }
    }

    /**
     * The same two series the chart plots, in the same order, drawn the same way.
     *
     * Taken from [PlottedSeries] rather than named here, because "which sizes are
     * worth showing" is one decision and this used to hold a second, older answer
     * to it: PM2.5 and PM10, from before the coarse cuts came off the plot. All
     * four are still recorded, stored and exported - see [PlottedSeries] for why
     * only two are ever *shown*.
     *
     * One line, both values side by side, with the unit carried once at the end:
     * a collapsed notification gives roughly 40 characters, and repeating
     * `µg/m³` after each number costs 6 of them to say nothing new.
     */
    private fun readout(state: DashboardUiState): String {
        val shown = PlottedSeries.FIELDS.map { it to state.latest[it] }
        if (shown.all { (_, value) -> value == null }) return "Waiting for readings…"
        return shown.joinToString(" · ") { (field, value) ->
            "${SeriesColors.label[field] ?: field} ${SeriesColors.reading(value ?: 0.0)}"
        } + " " + SeriesColors.unit
    }
}

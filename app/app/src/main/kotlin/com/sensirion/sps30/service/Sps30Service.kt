package com.sensirion.sps30.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sensirion.sps30.ui.MonitorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps [MonitorSession] streaming while the app is
 * backgrounded, surfacing status + the latest PM readout in an ongoing
 * notification.
 *
 * Declared as a **`connectedDevice`** foreground service, in the manifest and
 * in [startForegroundCompat], and the two must always name the same type - the
 * system refuses the promotion if they disagree.
 *
 * It was `dataSync` until 2026-08-06 and that was wrong twice over. Play's
 * approved `dataSync` uses are backup, restore, upload, download, import and
 * export: transfers that a user starts and that then finish. This service does
 * none of them - it holds a link to the controller and streams from it for as
 * long as monitoring is left on, which is exactly `connectedDevice`'s published
 * case, "Continuous Data Transfer to an External Device" over Bluetooth or
 * network. And from Android 15 `dataSync` carries a six-hour daily budget, after
 * which the system calls [onTimeout] and the stream stops; `connectedDevice` has
 * no such budget, so honest is also unlimited. Overnight monitoring used to end
 * itself and it no longer does.
 */
class Sps30Service : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private var wifiLock: WifiManager.WifiLock? = null

    /** What is on screen already, so the same two lines are not re-posted. */
    private val gate = NotificationGate()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to the foreground FIRST, before any other work.
        //
        // startForegroundService() gives roughly five seconds to reach
        // startForeground(), and missing it does not fail quietly - the system
        // kills the whole process with ForegroundServiceDidNotStartInTime. Work
        // that ran ahead of this call (starting the session, which now also
        // registers a network request) was enough to lose that race, and because
        // background monitoring is on by default the app crash-looped on launch.
        // Nothing goes above this line.
        Notifications.ensureChannel(this)
        if (!startForegroundCompat()) {
            // The system refused to let us run in the foreground - most likely
            // started from the background under Android 12+ restrictions. Give up
            // quietly rather than leaving a half-started service around.
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        MonitorSession.ensureStarted(applicationContext)
        MonitorSession.setBackgroundEnabled(true)
        acquireWifiLock()

        if (!running) {
            running = true
            scope.launch {
                MonitorSession.ui.collect { state ->
                    if (!running) return@collect
                    // Only when something the notification draws has actually
                    // moved; see [NotificationGate].
                    val content = gate.postFor(state) ?: return@collect
                    NotificationManagerCompat.from(this@Sps30Service)
                        .notify(Notifications.ID, Notifications.build(this@Sps30Service, content))
                }
            }
        }
        return START_STICKY
    }

    /**
     * The system is taking the foreground away; exit cleanly rather than be
     * killed with an ANR.
     *
     * No timed-out type is declared any more - `connectedDevice` has no daily
     * budget, unlike the `dataSync` this service used to be - so on today's
     * platforms this should never be called. It is kept because "the system may
     * one day time this service out" is a much cheaper thing to already handle
     * than to discover, and because a service that ignores `onTimeout` is force
     * -stopped rather than merely stopped.
     */
    override fun onTimeout(startId: Int) {
        stopMonitoring()
    }

    override fun onDestroy() {
        releaseWifiLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Promote to the foreground. False if the system refused. */
    private fun startForegroundCompat(): Boolean = try {
        val state = MonitorSession.ui.value
        // This one goes up whatever the gate thinks; telling the gate about it is
        // what stops the collector immediately posting the same thing again.
        gate.postFor(state)
        val notification = Notifications.build(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID, notification, FOREGROUND_TYPE)
        } else {
            startForeground(Notifications.ID, notification)
        }
        true
    } catch (e: Exception) {
        // Android 12+ can refuse a background start outright. Streaming while
        // the app is open still works; only the backgrounded case is lost, and
        // that is a far better outcome than taking the app down with it.
        android.util.Log.w("Sps30Service", "could not enter the foreground", e)
        false
    }

    /**
     * Hold the Wi-Fi radio awake while monitoring. A foreground service keeps
     * the process alive but does NOT stop the Wi-Fi radio from powering down in
     * doze - observed on the S24, where Wi-Fi drops off the LAN once the screen
     * sleeps. This keeps the stream reachable in the background. (For 24/7 use
     * the app should also be exempt from battery optimisation - see README.)
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sps30:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun stopMonitoring() {
        running = false
        // The notification is about to be taken away, so nothing is on screen for
        // the gate to compare the next one against.
        gate.reset()
        releaseWifiLock()
        MonitorSession.setBackgroundEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.sensirion.sps30.action.STOP"

        /**
         * The foreground service type this service promotes itself with.
         *
         * Named here so there is one place it is written in code, and so
         * [Sps30ServiceTypeTest] can hold it against the `android:foreground
         * ServiceType` in the manifest. The system compares the two itself and
         * throws when they disagree, but only on a device, at the moment
         * monitoring is switched on - which is the wrong place to find out.
         *
         * `InlinedApi` is suppressed rather than worked around: this is a
         * compile-time `int`, so the constant is baked into the bytecode and no
         * API 29 symbol is ever looked up at runtime, and the only place it is
         * *passed* to the platform is behind an `SDK_INT >= Q` check in
         * [startForegroundCompat]. `minSdk` is 26.
         */
        @SuppressLint("InlinedApi")
        internal const val FOREGROUND_TYPE =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, Sps30Service::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, Sps30Service::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

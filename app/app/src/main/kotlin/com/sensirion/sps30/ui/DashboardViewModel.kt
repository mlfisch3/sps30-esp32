package com.sensirion.sps30.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensirion.sps30.config.ConfigStore
import com.sensirion.sps30.config.Exports
import com.sensirion.sps30.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin bridge to the process-lifetime [MonitorSession], plus config persistence
 * (via [ConfigStore], which needs a Context - hence AndroidViewModel). The
 * session owns the connection so streaming survives Activity recreation.
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    init {
        MonitorSession.ensureStarted(app)
        MonitorSession.applyConfig(ConfigStore.loadCurrent(app))
    }

    val ui: StateFlow<DashboardUiState> = MonitorSession.ui

    // --- daemon commands ---
    fun pause() = MonitorSession.pause()
    fun resume() = MonitorSession.resume()
    fun clean() = MonitorSession.clean()
    fun restart() = MonitorSession.restart()
    fun shutdownSensorHost() = MonitorSession.shutdownSensorHost()
    fun requestStorageMap() = MonitorSession.requestStorageMap()
    fun setProfile(
        mode: String,
        intervalSeconds: Double,
        cycleSeconds: Double,
        warmupSeconds: Double,
        averageSeconds: Double,
    ) = MonitorSession.setProfile(
        mode, intervalSeconds, cycleSeconds, warmupSeconds, averageSeconds,
    )
    fun requestPowerEstimate() = MonitorSession.requestPowerEstimate()

    // --- chart interaction ---
    fun onWindow(seconds: Double) = MonitorSession.onWindow(seconds)
    fun onPinTap() = MonitorSession.onPinTap()
    fun onUnpin() = MonitorSession.onUnpin()
    fun onPinLongPress() = MonitorSession.onPinLongPress()
    fun beginGesture() = MonitorSession.beginGesture()
    fun endGesture() = MonitorSession.endGesture()
    fun goLive() = MonitorSession.goLive()
    fun zoomBy(factor: Double) = MonitorSession.zoomBy(factor)
    fun panBy(deltaSeconds: Double) = MonitorSession.panBy(deltaSeconds)
    fun snapViewStart(epoch: Double) = MonitorSession.snapViewStart(epoch)
    fun snapViewEnd(epoch: Double) = MonitorSession.snapViewEnd(epoch)
    /** A drag on the plot selected this stretch; its statistics follow in [ui]. */
    fun selectRegion(start: Double, end: Double) = MonitorSession.selectRegion(start, end)
    fun clearRegion() = MonitorSession.clearRegion()
    /** The card's explicit confirmation: frame the selection as the window. */
    fun zoomToRegion() = MonitorSession.zoomToRegion()
    fun returnFromRegionZoom() = MonitorSession.returnFromRegionZoom()
    fun zoomYBy(factor: Double, anchor: Double) = MonitorSession.zoomYBy(factor, anchor)
    fun panYBy(fraction: Double) = MonitorSession.panYBy(fraction)
    fun setChartWidthPx(width: Int) = MonitorSession.setChartWidthPx(width)
    fun clearPlot() = MonitorSession.clearPlot()
    fun setPlotInterval(seconds: Double?) = MonitorSession.setPlotInterval(seconds)
    /** Engage span-following averaging without moving the view; the Average tap. */
    fun setAutoAveraging() = MonitorSession.setAutoPlotInterval()
    fun autoSet() = MonitorSession.autoSet()
    /** Show or hide a PM series on the plot, from its readout button. */
    fun toggleSeries(field: String) = updateConfig(ui.value.config.toggleVisible(field))

    fun setReadoutStyle(style: com.sensirion.sps30.data.ReadoutStyle) =
        updateConfig(ui.value.config.copy(readoutStyle = style))

    fun setLogY(on: Boolean) = updateConfig(ui.value.config.copy(logY = on))

    /** Whether the y autoscale keeps the alert level in view as well as the data. */
    fun setAlertInAutoscale(on: Boolean) =
        updateConfig(ui.value.config.copy(alertInAutoscale = on))

    fun setPlotStyle(style: com.sensirion.sps30.data.PlotStyle) =
        updateConfig(ui.value.config.copy(plotStyle = style))

    /** Whether the plot shades its gaps. Display only - detection is untouched. */
    fun setMarkGaps(on: Boolean) = updateConfig(ui.value.config.copy(markGaps = on))

    // --- alerts ---
    fun updateAlert(rule: com.sensirion.sps30.data.AlertRule) {
        val next = ui.value.config.alerts.map { if (it.id == rule.id) rule else it }
        updateConfig(ui.value.config.copy(alerts = next))
    }

    fun testAlert(rule: com.sensirion.sps30.data.AlertRule) = MonitorSession.testAlert(rule)

    // --- watch ---
    /** Ask the Data Layer what is on the other end of the relay. */
    fun refreshWatchLink() = MonitorSession.refreshWatchLink()

    /** Send the current reading to the watch now, and report what came back. */
    fun sendTestReadingToWatch() = MonitorSession.sendTestReadingToWatch()

    fun setWatchRelaySeconds(seconds: Int) =
        updateConfig(ui.value.config.copy(watchRelaySeconds = seconds))

    // --- multi-user control ---
    fun copySessionsToThisDevice() = MonitorSession.copySessionsToThisDevice()
    fun dismissBackupResult() = MonitorSession.dismissBackupResult()

    /**
     * Look for holes in this device's record now, and fill what the sensor still
     * has. The same sweep that runs on its own every half hour; see
     * [MonitorSession.findAndFillGaps].
     */
    fun findAndFillGaps() = MonitorSession.findAndFillGaps()

    fun importArchive(uri: android.net.Uri, displayName: String) =
        MonitorSession.importArchive(uri, displayName)
    fun dismissTransferResult() = MonitorSession.dismissTransferResult()

    /**
     * Put readings back into this device's record from a file it already has.
     *
     * The other direction from [importArchive], which sends a file to the
     * sensor. This one changes what is on the plot; see [MonitorSession.restoreFromFile].
     */
    fun restoreFromFile(uri: android.net.Uri, displayName: String) =
        MonitorSession.restoreFromFile(uri, displayName)
    fun dismissRestoreResult() = MonitorSession.dismissRestoreResult()

    fun eraseController() = MonitorSession.eraseController()
    fun eraseControllerWithoutBackup() = MonitorSession.eraseControllerWithoutBackup()
    fun dismissEraseResult() = MonitorSession.dismissEraseResult()

    // --- backing everything up and clearing the sensor ---
    // Its own three, next to the erase rather than folded into it: they drive a
    // separate path on the controller and must not be able to reach this one.
    fun backUpAndClearSensor() = MonitorSession.backUpAndClearSensor()
    fun stopBackUpAndClear() = MonitorSession.stopBackUpAndClear()
    fun dismissBackUpAndClear() = MonitorSession.dismissBackUpAndClear()

    /**
     * The name a picked document goes by, for telling an archive from a lone
     * session CSV and for naming the session in the second case.
     *
     * Read from the provider rather than guessed from the URI: a SAF URI's last
     * path segment is a provider-internal id on most providers and is not the
     * file's name at all.
     */
    fun displayNameOf(uri: android.net.Uri): String {
        val resolver = getApplication<android.app.Application>().contentResolver
        resolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) return c.getString(col) ?: ""
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /** Put away the explanation of a connection the user asked for and did not get. */
    fun dismissConnectionNotice() = MonitorSession.dismissConnectionNotice()

    fun claimControl() = MonitorSession.claimControl()
    fun releaseControl() = MonitorSession.releaseControl()
    fun dismissControlDenied() = MonitorSession.dismissControlDenied()
    fun setYAutoscale(on: Boolean) = MonitorSession.setYAutoscale(on)
    fun setYRange(min: Double, max: Double) = MonitorSession.setYRange(min, max)
    fun setYLocked(locked: Boolean) = MonitorSession.setYLocked(locked)
    fun releaseYOverride() = MonitorSession.releaseYOverride()
    fun setXLocked(locked: Boolean) = MonitorSession.setXLocked(locked)
    fun releaseXRange() = MonitorSession.releaseXRange()
    fun cycleTickDensity() = MonitorSession.cycleTickDensity()

    // --- config: persist current, apply live, and manage named saves ---
    fun updateConfig(config: AppConfig) {
        ConfigStore.saveCurrent(getApplication(), config)
        MonitorSession.applyConfig(config)
    }

    fun resetConfigToDefault() = updateConfig(AppConfig.default())

    fun setBackgroundMonitoring(on: Boolean) =
        updateConfig(ui.value.config.copy(backgroundMonitoring = on))

    /** Toggle excluding disturbed settling/clean-spike readings from the plot. */
    fun setExcludeFlagged(on: Boolean) = updateConfig(ui.value.config.copy(excludeFlagged = on))

    /**
     * Stop (or restart) collecting what the controller preserved against a wrap.
     *
     * Only about this phone. The controller preserves either way - it has to,
     * because the case the feature exists for is the one where no phone is
     * present - so this is a choice about a copy, not about the record.
     */
    fun setReceivePreservedAlerts(on: Boolean) =
        updateConfig(ui.value.config.copy(receivePreservedAlerts = on))

    /** Switch transport (Wi-Fi <-> Bluetooth). Persisted; MonitorSession brings the
     *  new one up beside the working one and hands over when it answers. */
    fun setConnectionMode(mode: com.sensirion.sps30.data.ConnectionMode) =
        updateConfig(ui.value.config.copy(connectionMode = mode))

    fun saveConfigAs(name: String) =
        ConfigStore.saveNamed(getApplication(), name, ui.value.config)

    fun savedConfigNames(): List<String> = ConfigStore.savedNames(getApplication())

    fun loadSavedConfig(name: String) {
        ConfigStore.loadNamed(getApplication(), name)?.let { updateConfig(it) }
    }

    /**
     * Place a run of a clockless controller by hand.
     *
     * The user's own knowledge, not an inference. See [UnplacedRunsDialog].
     */
    fun placeRunManually(boot: Int, epochSeconds: Double?) =
        MonitorSession.placeRunManually(boot, epochSeconds)

    // --- stored sessions ---
    fun loadStoredSessions() = MonitorSession.loadStoredSessions()
    fun clearStoredSessions() = MonitorSession.clearStoredSessions()

    // --- export ---
    fun exportCsv(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val csv = MonitorSession.buildCsv()
            val location = withContext(Dispatchers.IO) { Exports.writeCsv(getApplication(), csv) }
            onResult(location)
        }
    }

    fun savePng(bitmap: Bitmap, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val location = withContext(Dispatchers.IO) { Exports.writePng(getApplication(), bitmap) }
            onResult(location)
        }
    }
}

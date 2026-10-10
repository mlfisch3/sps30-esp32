package com.sensirion.sps30.ui

import android.content.Context
import com.sensirion.sps30.CoreLog
import com.sensirion.sps30.data.AlertEngine
import com.sensirion.sps30.data.ArchiveSend
import com.sensirion.sps30.data.AlertRule
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.data.DataInventory
import com.sensirion.sps30.data.PlotCache
import com.sensirion.sps30.data.RecordGapReport
import com.sensirion.sps30.data.RecordGaps
import com.sensirion.sps30.data.RecordRestore
import com.sensirion.sps30.data.SensorRetention
import com.sensirion.sps30.config.ConfigStore
import com.sensirion.sps30.config.StoredSessions
import com.sensirion.sps30.data.SessionArchive
import com.sensirion.sps30.data.DeviceDrain
import com.sensirion.sps30.data.DrainStore
import com.sensirion.sps30.data.SessionBackup
import com.sensirion.sps30.data.SessionErase
import com.sensirion.sps30.data.SessionRestore
import com.sensirion.sps30.data.SessionTransfer
import com.sensirion.sps30.data.TimeBasis
import com.sensirion.sps30.data.StoredImport
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.net.BleTransport
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.net.ControllerSockets
import com.sensirion.sps30.net.NsdServiceBrowser
import com.sensirion.sps30.net.TcpTransport
import com.sensirion.sps30.net.Transport
import com.sensirion.sps30.net.WifiNetworkBinder
import com.sensirion.sps30.service.AlertNotifier
import com.sensirion.sps30.service.Sps30Service
import com.sensirion.sps30.wear.RelayPolicy
import com.sensirion.sps30.wear.WatchLink
import com.sensirion.sps30.wear.WatchLinkFacts
import com.sensirion.sps30.wear.WearLink
import com.sensirion.sps30.wear.WearRelay
import com.sensirion.sps30.wire.Commands
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

data class SensorInfo(val product: String, val serial: String, val firmware: String)

/**
 * What [DashboardUiState] exposes about the discovery phase. [detail] is the reason
 * the last search failed, and latches with [failed] - the banner must not read it off
 * the instantaneous connection state, which reverts to `Searching` on every retry and
 * would leave the sentence rewording itself every few seconds.
 */
data class DiscoveryFlags(
    val discovering: Boolean,
    val failed: Boolean,
    val detail: String? = null,
    val advice: String? = null,
)

/**
 * Fold one transport state into the discovery flags, given what the last sweep
 * concluded.
 *
 * "Not found" **latches** until something is actually reached. Both transports retry
 * their search on a short cycle, so a flag derived from the instantaneous state alone
 * would blink between "searching…" and "no sensor found" every few seconds - motion
 * that reads as progress while telling the user nothing. Latching it leaves one
 * stable, actionable sentence on screen, and the retries continue underneath;
 * [ConnectionState.Connecting] is the first honest evidence a host exists, so that is
 * what clears it.
 */
fun discoveryFlagsFor(
    state: ConnectionState,
    wasFailed: Boolean,
    wasDetail: String? = null,
    wasAdvice: String? = null,
): DiscoveryFlags {
    val failed = when (state) {
        is ConnectionState.NotFound -> true
        is ConnectionState.Connecting, is ConnectionState.Connected -> false
        else -> wasFailed
    }
    val detail = when {
        state is ConnectionState.NotFound -> state.detail
        failed -> wasDetail
        else -> null
    }
    val advice = when {
        state is ConnectionState.NotFound -> state.advice
        failed -> wasAdvice
        else -> null
    }
    return DiscoveryFlags(
        discovering = state is ConnectionState.Searching && !failed,
        failed = failed,
        detail = detail,
        advice = advice,
    )
}

/** First retry gap after an attempt ends, and the ceiling a fruitless one backs off to. */
const val MIN_RETRY_MS = 1500L
const val MAX_RETRY_MS = 20_000L

/**
 * How long to wait before sweeping again, having found nothing last time.
 *
 * A flat 1.5 s here was actively harmful, not merely wasteful. Every sweep opens a
 * connection to each candidate address, and the host counts those against its client
 * limit (8 on the Pi, and tighter on the ESP32). Retrying every 1.5 s filled the host
 * with the app's own probes; it then answered "at capacity" instead of `hello`, so
 * the sweep concluded nothing was there and retried - which kept it full. Measured on
 * the tablet: the app connected on first launch, then wedged on "no sensor found"
 * until it was stopped, after which the host recovered within a minute.
 *
 * Backing off to 20 s bounds that pressure while still finding a host - the ESP32,
 * when its network layer lands - within one cycle of it appearing.
 */
fun nextRetryMs(current: Long): Long = (current * 2).coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)

/**
 * How long to keep the working link alive while the newly chosen one is brought up.
 *
 * A backstop, not the bound that normally decides anything. The transport being
 * brought up says when it has given up - [com.sensirion.sps30.net.BleTransport]
 * searches for a minute and then stops, and it knows far more about why than a
 * timer here does - so this only has to outlast that: a minute of scanning plus a
 * GATT connect of up to 12 s, with room. It is what stops a transport that never
 * gives up (the TCP sweep does not) from leaving the app "switching to…" for ever.
 *
 * When either fires, the switch is **abandoned** and the working link is left
 * exactly as it was. It used to commit the switch anyway, which quietly undid the
 * one thing make-before-break is for: a Bluetooth choice with no controller in
 * range dropped a perfectly good Wi-Fi connection after thirty seconds and left
 * the app scanning on a radio with nothing on it.
 */
const val HANDOVER_TIMEOUT_MS = 80_000L

/**
 * What is left behind when a search gives up.
 *
 * Kept apart from [MonitorSession] for the same reason as [transportChangeFor]:
 * it is the whole rule, and it is worth being able to state - and test - without a
 * Context, a radio or a board.
 */
data class SearchAbandoned(
    /** The mode the app is left on, and the one the selector will show as current. */
    val fallback: ConnectionMode,
    /** True when a link is still carrying the stream and is not to be touched. */
    val keepsServingLink: Boolean,
    /** A plain sentence for the user, saying what happened and what to do. */
    val notice: String,
)

/**
 * Decide what a failed attempt leaves behind.
 *
 * The rule is one sentence: **fall back to whatever is actually working**. A link
 * that is delivering readings is worth more than the mode the user asked for and
 * could not have, so it keeps running and the selector goes back to naming it;
 * with nothing working there is nothing to keep, so the app is left plainly
 * disconnected. Either way the mode the user chose is released, which is what
 * makes its button live again for a deliberate second try - no separate retry
 * control, because tapping the transport is already how one is asked for.
 *
 * @param gaveUp the mode whose attempt failed.
 * @param serving the mode still carrying the stream, or null if none is.
 */
fun searchAbandonedFor(gaveUp: ConnectionMode, serving: ConnectionMode?): SearchAbandoned {
    val tried = connectionModeLabel(gaveUp)
    return SearchAbandoned(
        fallback = serving ?: ConnectionMode.NONE,
        keepsServingLink = serving != null,
        notice = if (serving != null) {
            "No sensor answered over $tried. Nothing was changed - you are still " +
                "connected over ${connectionModeLabel(serving)}. Tap $tried again to try " +
                "once more."
        } else {
            "No sensor answered over $tried. Check the sensor is powered on and in " +
                "range, then tap $tried to try again."
        },
    )
}

/**
 * Whether a link whose attempt has just returned should start another one.
 *
 * Every transport but one returns between attempts and expects the loop to call it
 * again; a transport that has stopped for good says so, and calling it again would
 * undo the decision it just made.
 */
fun keepsTrying(state: ConnectionState): Boolean =
    (state as? ConnectionState.NotFound)?.stopped != true

/**
 * What choosing a connection mode should actually do to the links that are running.
 *
 * Kept apart from [MonitorSession] because it is the whole rule and it is worth
 * being able to state - and test - without a Context, a radio or a board.
 */
sealed interface TransportChange {
    /** Already on that mode with nothing else in flight: do nothing at all. */
    data object Nothing : TransportChange

    /** A switch was in progress and the user has chosen the link already serving,
     *  so drop the half-built one and stay where we are. */
    data object CancelSwitch : TransportChange

    /** Break, then make: there is no working link to protect, so tear down and
     *  start the new one immediately. Also the answer for Disconnect, which has
     *  nothing to hand over to. */
    data class Restart(val mode: ConnectionMode) : TransportChange

    /**
     * Make, then break: bring the new transport up **beside** the working one and
     * only retire the old one once the new one has said `hello`.
     *
     * The board serves the identical protocol over TCP and BLE as two ordinary
     * clients (`net_link.h`), so both links can be open at once, and readings
     * de-duplicate by `(csv, index)` - so the overlap costs a few duplicate frames
     * and loses nothing. The `hello` is the promotion signal rather than the
     * socket coming up, because it is the first proof the protocol travels over
     * the new link and it carries the session name the backfill needs.
     */
    data class Handover(val mode: ConnectionMode) : TransportChange
}

/**
 * Decide what a mode selection means.
 *
 * @param target what the user just chose.
 * @param serving the mode of the link currently carrying the stream, null if none.
 * @param pending the mode of a link already being brought up beside it, if any.
 * @param servingConnected whether [serving] is actually connected right now. A link
 *   that is only searching has no stream to protect, so there is nothing to hand
 *   over and waiting would just delay the mode the user asked for.
 */
fun transportChangeFor(
    target: ConnectionMode,
    serving: ConnectionMode?,
    pending: ConnectionMode?,
    servingConnected: Boolean,
): TransportChange = when {
    target == serving && pending != null -> TransportChange.CancelSwitch
    target == serving -> TransportChange.Nothing
    target == pending -> TransportChange.Nothing // already on its way; let it finish
    target == ConnectionMode.NONE -> TransportChange.Restart(target)
    serving == null || !servingConnected -> TransportChange.Restart(target)
    else -> TransportChange.Handover(target)
}

data class DashboardUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    /**
     * The transport actually carrying the stream, which is not always the one the
     * config asks for: during a switch the old link keeps delivering readings until
     * the new one has said hello. Everything that describes the *live* connection -
     * the banner especially - reads this, or it would announce "Bluetooth ·
     * connected 192.168.1.159:8730" about a Wi-Fi socket.
     */
    val activeMode: ConnectionMode = ConnectionMode.NONE,
    /** The transport being brought up beside [activeMode]; null when not switching. */
    val switchingTo: ConnectionMode? = null,
    /** How far that one has got, e.g. "scanning for the sensor". A Bluetooth search
     *  takes up to a minute and silence for that long reads as a wedge. */
    val switchingDetail: String? = null,
    /**
     * Why a connection the user asked for did not happen, in one plain sentence.
     *
     * It cannot be left to the banner. The banner describes the *live* connection,
     * and after a failed switch the live connection is the Wi-Fi one that was never
     * dropped - which is healthy, so the banner would have nothing but good news to
     * report about a thing the user asked for and did not get. A minute of waiting
     * followed by silence is exactly the silent failure this exists to prevent.
     */
    val connectionNotice: String? = null,
    val discovering: Boolean = false,
    val discoveryFailed: Boolean = false,
    /** Why the last search failed, e.g. "no sensor found on the network". */
    val discoveryDetail: String? = null,
    /** The transport's own suggestion for that failure, when it has a specific one. */
    val discoveryAdvice: String? = null,
    /**
     * The socket says connected but no readings have arrived for a while.
     *
     * A distinct state from disconnected, because it looks identical to a healthy
     * connection from the inside and identical to clean air from the outside.
     * Naming it is the whole point: a frozen plot with a confident banner is the
     * failure the captain kept hitting.
     */
    val streamStalled: Boolean = false,
    val sensor: SensorInfo? = null,
    val daemonState: String? = null,
    val daemonDetail: String = "",
    /**
     * Latched when the daemon announced it was powering off, and cleared only by
     * hearing a live state from it again.
     *
     * [daemonState] alone cannot carry this: the shutdown announcement is the last
     * thing the host sends, so a moment later the link drops and every ordinary
     * reading of the UI ("reconnecting", "disconnected, still recording") describes
     * a sensor that no longer exists. The user watched the fan stop; the app must
     * not then claim it is recording.
     */
    val sensorShutDown: Boolean = false,
    /** Wall-clock millis at which the current daemon phase ends, when known. */
    val phaseEndsAtMs: Long? = null,
    val latest: Map<String, Double> = emptyMap(),
    val latestTimestamp: String? = null,
    val chart: ChartData? = null,
    val windowSeconds: Double = 300.0,
    /**
     * The selected stretch of the curve and its statistics, or null with
     * nothing selected. Computed from the raw history on the model dispatcher -
     * see [buildRegionReport] - never from [chart]'s drawn points.
     */
    val region: RegionReport? = null,
    val backgroundEnabled: Boolean = false,
    val config: AppConfig = AppConfig(),
    /** The daemon's active acquisition profile, once it has told us. */
    val profile: ServerMessage.AcquisitionProfile? = null,
    val power: ServerMessage.PowerStatus? = null,
    /** The controller's modelled power estimate, once asked for and answered.
     *  Null until the power panel's request comes back; kept across reopens so
     *  the panel has something to show while a fresh answer is in flight. */
    val powerEstimate: ServerMessage.PowerEstimate? = null,
    /** Set when this host answered `get_power_estimate` with an error, and says
     *  which refusal it was: a controller on firmware from before the power
     *  model, the Pi daemon that never models power, or words the app has no
     *  rule for. Classified where the error arrives (see [classifyPowerRefusal])
     *  so the panel can answer accurately instead of waiting forever. */
    val powerEstimateRefusal: PowerRefusal? = null,
    /** False if the sensor firmware cannot sleep, so low-power must be offered. */
    val supportsSleep: Boolean = true,
    /** False on a controller with nothing to power off, so the shutdown control
     *  is left out entirely rather than shown and refused. */
    val canPowerOff: Boolean = true,
    /** Advances once a second; only there to drive countdown recomposition. */
    val clockTick: Int = 0,
    /** Ids of alert rules currently breached, for painting the UI. */
    val activeAlerts: Set<String> = emptySet(),
    /** This connection's roster id, so it can recognise itself as the holder. */
    val clientId: Int? = null,
    /** Who currently holds the control lock; null means nobody does. */
    val controlHolder: Int? = null,
    val controlHolderName: String? = null,
    /**
     * Whether the controller has said who holds the lock on *this* connection.
     *
     * [controlHolder] is null both when nobody holds control and when nobody has
     * been asked, and the UI used to print "Nobody has control" for either. False
     * until a `control` message arrives, and forced back to false the moment the
     * link is not `Connected`, so a reconnect starts from "not known" rather than
     * from the last connection's answer.
     */
    val controlTold: Boolean = false,
    /** Everyone connected to the sensor right now. */
    val watchers: List<ServerMessage.Watcher> = emptyList(),
    val maxClients: Int = 0,
    /**
     * The last refusal the controller sent, shown to whoever caused it.
     *
     * Named for the case it was built for - a command refused because someone
     * else holds control - but it carries every refusal now. It used to carry
     * only that one, and the rest were dropped on the floor: the controller
     * answered a `set_profile` with "acquisition profiles are not implemented on
     * this controller yet" and the app said nothing at all, so a Low power chip
     * that was being clearly and correctly refused looked like a chip that did
     * not work. A refusal nobody sees is worse than no refusal, because the user
     * concludes the button is broken and stops trusting the ones that do work.
     */
    val controlDenied: String? = null,
    /**
     * Whether [controlDenied] is the control-lock refusal or an ordinary one.
     *
     * It decides which dialog is shown, and the distinction matters: "take
     * control" is the answer to exactly one of the two, and offering it for
     * "low power needs firmware 2.0 or newer" would point the user at the wrong
     * problem.
     */
    val refusalNeedsControl: Boolean = false,
    /** Progress of copying the sensor's recorded sessions to this device. */
    val backup: SessionBackup = SessionBackup.Idle,
    /** Progress of reading those stored sessions back into the plot. */
    val storedImport: StoredImport = StoredImport.Idle,
    /** Progress of sending a previously exported file back to the controller. */
    val transfer: SessionTransfer = SessionTransfer.Idle,
    /**
     * Progress of merging a session file from this device back into the record.
     *
     * The one direction that used not to exist. Distinct from [storedImport],
     * which draws an archive on the plot averaged and namespaced away, and from
     * [transfer], which moves data the other way entirely. See [SessionRestore].
     */
    val restore: RecordRestore = RecordRestore.Idle,
    /** Progress of asking the controller to erase what is already backed up. */
    val erase: SessionErase = SessionErase.Idle,
    /**
     * Progress of backing everything up and clearing the sensor.
     *
     * A separate state from [backup] and [erase] because it is a separate path
     * on the controller as well as here, and the three must never be able to
     * report each other's outcome: this one is the only one that empties the
     * sensor. See [DeviceDrain].
     */
    val clearing: DeviceDrain.Status = DeviceDrain.Status(),
    /** How full the controller's store is, once it has said. */
    val storage: ServerMessage.Storage? = null,
    /**
     * The per-page occupancy map, once the open map dialog has asked for one.
     *
     * Kept alongside [storage] rather than derived from it: [storage] is two
     * counters, this is one state per flash page, read fresh from the page
     * headers each time the dialog asks. The two describe the same ring and
     * may disagree by a page for the moment between their snapshots; the map
     * dialog quotes its own cells, the Data tab quotes [storage], and neither
     * invents the other's number.
     */
    val storageMap: ServerMessage.StorageMap? = null,
    /**
     * What is on the sensor, what of it is here, and what is missing.
     *
     * Derived rather than reported: see [DataInventory] for what it is built
     * from and why the Data tab needs a comparison rather than a total. It
     * starts at [DataInventory.UNKNOWN], which says every figure is unknown -
     * which it is, until something has answered.
     */
    val inventory: DataInventory = DataInventory.UNKNOWN,
    /**
     * Where this device's own record is discontinuous, and what can be done.
     *
     * A different question from [inventory], which compares *counts* per session
     * and answers "is my data here yet". This answers "where is it not", in time,
     * across every session at once - and, for each hole, whether the sensor can
     * still supply it or the ring has wrapped past it. See [RecordGaps].
     */
    val gaps: RecordGapReport = RecordGapReport(),
    /** What the controller has preserved against its ring wrapping, once it has said. */
    val alerts: ServerMessage.Alerts? = null,
    /**
     * The preserved excursions this device has been sent, newest first.
     *
     * A summary each, and every one of them stands on its own: a segment whose
     * samples the controller has since reduced away still carries its start and
     * stop, its first and last values, its midpoint, mean, variance, extremes
     * and point count. That is what it was kept for.
     */
    val alertSegments: List<ServerMessage.AlertSegment> = emptyList(),
    /**
     * Where the last export of every session went, and what it proved.
     *
     * Held because it is the evidence the controller's erase asks for: without a
     * backup this device can name, session by session, there is nothing to
     * offer and the erase is refused - which is the intended behaviour, not a
     * failure to work around.
     */
    val lastExport: LastExport? = null,
    /**
     * Present only when the plot holds readings from a controller with no clock.
     *
     * Null is the ordinary case and means nothing on screen needs qualifying.
     */
    val relativeTime: RelativeTimeState? = null,
    /**
     * What is known about the watch on the far end of the relay.
     *
     * Empty until something asks - see [MonitorSession.refreshWatchLink] - which
     * is deliberate: the Data Layer is not free to interrogate and nothing needs
     * the answer until the Watch tab is looked at.
     */
    val watch: WatchLinkFacts = WatchLinkFacts(),
) {
    /** True when nobody else is driving, so the controls are live. */
    val hasControl: Boolean
        get() = controlHolder == null || controlHolder == clientId

    /**
     * Whether readings are reaching this phone at all.
     *
     * The Watch tab has to be able to tell "the relay is broken" from "there is
     * nothing to relay". They look identical from the watch and they call for
     * opposite actions, and only one of them is the watch's fault - a stalled
     * stream is the sensor connection, and saying so points the user at the
     * right device instead of at their wrist.
     */
    val readingsArriving: Boolean
        get() = connection is ConnectionState.Connected && !streamStalled && latest.isNotEmpty()
}

/**
 * What the screen has to admit about readings recorded with no clock.
 *
 * The distinction that matters is [unanchoredRuns]. A run with an equivalence is
 * simply plotted - it has a real time, arrived at by arithmetic rather than by a
 * clock, and there is nothing to warn anybody about. A run without one has
 * readings whose spacing is exactly right and whose position is genuinely
 * unknown, and the only honest thing to do is say so and let the user place it.
 */
data class RelativeTimeState(
    /** Equivalences the controller has sent, across every run. */
    val anchors: Int,
    /** Runs on the plot that nothing can place. Each can be shifted by hand. */
    val unanchoredRuns: List<Int>,
    /** The controller's current run, so "this recording" can be named. */
    val currentBoot: Int,
) {
    val hasUnplaceable: Boolean get() = unanchoredRuns.isNotEmpty()
}

/** The archive this device last wrote, and what it can therefore prove. */
data class LastExport(
    val location: String,
    val sessions: List<SessionArchive.Entry>,
    val whenMs: Long,
) {
    val rows: Int get() = sessions.sumOf { it.rows }
}

// --- noticing that the stream has died -------------------------------------
//
// A TCP peer that vanishes without a FIN - a reboot, a power cut, a board
// carried out of range - leaves the reader blocked in read() forever. The socket
// has no read deadline (soTimeout = 0, deliberately: a live stream must not time
// out between readings) and nothing else notices, so the app sat showing
// "connected" with a frozen plot for as long as it was left. That is
// indistinguishable from clean air, and it is what made every gap in the
// captain's chart permanent: backfill only runs on reconnect, and the reconnect
// never came.
//
// The sensor publishes at 1 Hz, so silence is normally unambiguous. Fifteen
// seconds is the same idle window the controller's own TCP keepalive uses.

/** How long a live stream may go quiet before the link is treated as dead. */
const val STREAM_SILENT_MS = 15_000L

/**
 * How long a transfer may go without a frame being accepted before the link
 * under it is treated as dead.
 *
 * Thirty seconds, and deliberately not fifteen. The controller closes out an
 * import that has gone quiet for `NET_IMPORT_IDLE_MS` (15 s) and *answers* it,
 * which is a far better thing for the user to see than a dropped connection, so
 * that mechanism is given its turn first. Thirty is also the controller's own
 * `CLIENT_STALL_MS` - the patience it extends to a client that has stopped
 * accepting lines - so both ends give up at the same point rather than one
 * quietly pre-empting the other.
 */
const val TRANSFER_STALLED_MS = 30_000L

/**
 * Whether the link has stopped proving it is alive, and should be forced down so
 * the reconnect loop can rebuild it.
 *
 * **Readings are not the proof while a transfer is running.** The controller
 * deliberately sends *nothing* to a client that is uploading - see the "Nothing
 * is volunteered to a client that is uploading" branch in `client_task`
 * (`net_server.c`). Its reply buffer is one line deep and it cannot read a
 * client while a line is waiting to go out to it, so a reading queued mid-import
 * would stop the controller reading the very transfer it is being sent. It skips
 * the live stream instead, and counts on the app asking for the gap afterwards.
 *
 * Judging that silence by [STREAM_SILENT_MS] is what made "send my data back to
 * the sensor" fail every time on a real archive: fifteen seconds into a transfer
 * the app closed its own socket, which the board read as the client going away.
 * It failed at 6,991, 11,334 and 13,742 rows of the same 72,814 - three
 * different counts, one duration, because the trigger was the clock and not the
 * data.
 *
 * So while frames are going out, the proof of life is the transfer itself. That
 * is a real signal rather than an exemption: `sendAwait` waits for room in a
 * bounded queue that drains only as fast as the socket accepts, and the socket
 * stops accepting when the controller stops reading. A board that has genuinely
 * died stops taking bytes, progress stops, and this fires on
 * [TRANSFER_STALLED_MS] - so the watchdog keeps doing its real job during the
 * one operation it used to break.
 *
 * **And the silence does not end when the sending does.** The controller stays
 * silent from the first frame until it has read the last one and answered, and
 * the last frame reaching the queue is not the last frame reaching the board.
 * Standing the transfer rule down at the last frame therefore handed the link
 * straight back to a fifteen-second reading clock while the controller was still
 * working through what this device had already queued, which is how a send that
 * both ends completed correctly still failed: on 2026-08-04 the board answered a
 * 121,249-reading archive at 15.07 s and 15.41 s after the app's last frame, and
 * the app closed the socket on [STREAM_SILENT_MS] about thirty milliseconds
 * later, both times, without ever reading the answer. So the wait for the answer
 * is the third arm here, and it is [TRANSFER_ANSWER_MS] long, not fifteen
 * seconds: the deadline that ends that wait is [transferAfterAnswerDeadline],
 * which produces a true account of what was sent, and this must not pre-empt it
 * with a dropped connection.
 *
 * @param lastTransferProgressAtMs when a transfer frame was last accepted, or 0
 *   when no transfer is putting frames on the link - which is what selects the
 *   rule. A transfer that is still surveying the file has not touched the link
 *   yet, so the live stream is still flowing and is still what to judge.
 * @param lastReadingAtMs when a reading last arrived, or 0 when none has since
 *   this link connected - nothing to be silent against yet.
 * @param awaitingAnswerSinceMs when the last frame of a transfer went out, while
 *   its answer is still outstanding; 0 when nothing is waiting on one.
 */
fun streamIsDead(
    nowMs: Long,
    lastReadingAtMs: Long,
    lastTransferProgressAtMs: Long,
    awaitingAnswerSinceMs: Long = 0L,
): Boolean = when {
    lastTransferProgressAtMs != 0L -> nowMs - lastTransferProgressAtMs >= TRANSFER_STALLED_MS
    // Strictly past, so that on the tick where both come due the answer's own
    // deadline - which runs later in the tick - gets to say what happened.
    awaitingAnswerSinceMs != 0L -> nowMs - awaitingAnswerSinceMs > TRANSFER_ANSWER_MS
    else -> lastReadingAtMs != 0L && nowMs - lastReadingAtMs >= STREAM_SILENT_MS
}

/**
 * The reading clock to carry into the next tick.
 *
 * The other half of the third arm of [streamIsDead], and it is not optional. A
 * wait for an answer is silence the protocol requires, so the ordinary
 * fifteen-second reading clock must not be *running* through it either: left to
 * run, it is already fifteen seconds overdue at the moment the answer arrives,
 * and the tick after a send back finally succeeded would drop the link that had
 * just proved itself. Holding it means the link comes out of the wait with a
 * full window, exactly as `importArchive`'s `finally` hands it one at the end of
 * the sending.
 *
 * @param awaitingAnswerSinceMs as in [streamIsDead]: 0 when nothing is waiting.
 */
fun readingClockAfterTick(
    nowMs: Long,
    lastReadingAtMs: Long,
    awaitingAnswerSinceMs: Long,
): Long = if (awaitingAnswerSinceMs != 0L) nowMs else lastReadingAtMs

/**
 * How long the controller has to answer a transfer once its last frame has gone.
 *
 * The other half of [TRANSFER_STALLED_MS], and the one that was missing. That
 * one covers a transfer that is *sending*, and it is stood down the moment the
 * last frame goes out. Nothing then owned the wait: a send that finished and was
 * never answered looked, to every watchdog in this file, exactly like a healthy
 * connection. Observed on 2026-08-04: "111,056 of 111,056 readings" for ten
 * minutes, the app connected and streaming throughout, and the only way out was
 * force-stopping it.
 *
 * This is the sole deadline over that window, and [streamIsDead] defers to it
 * for the whole of it. The link is *not* judged by its readings in the meantime:
 * the controller resumes the live stream only once it has answered, so a reading
 * clock started here measures a silence the protocol requires and finds it dead
 * every time. See the third arm of [streamIsDead].
 *
 * Sixty seconds, and every part of that is the controller's arithmetic rather
 * than a round number:
 *
 *  - it closes a transfer that has gone quiet after `NET_IMPORT_IDLE_MS` (15 s)
 *    and **answers it**, which is a far better thing for the user to see than a
 *    timeout here;
 *  - it then gives a client `CLIENT_STALL_MS` (30 s) to accept that answer
 *    before dropping it.
 *
 * Forty-five of the sixty is therefore the controller's own worst case, and this
 * only fires past it - so the board's answer always wins the race when the board
 * is capable of answering at all, and this is left to catch the case where it
 * never will. A deadline that fired first would replace a true account of what
 * happened with a guess.
 */
const val TRANSFER_ANSWER_MS = 60_000L

/**
 * Whether the controller has taken too long to say what it did with a transfer.
 *
 * @param sentAtMs when the last frame was handed to the link.
 */
fun transferAnswerOverdue(nowMs: Long, sentAtMs: Long): Boolean =
    sentAtMs != 0L && nowMs - sentAtMs >= TRANSFER_ANSWER_MS

/** What a transfer that was sent in full and never answered is told to the user. */
fun transferUnansweredReason(why: String): String =
    "every reading was sent, but $why. Nothing was removed on the sensor - send the file " +
        "again to find out what it holds; anything that did arrive comes back as already held."

/**
 * The transfer state an `import_result` produces, given the one it arrives into.
 *
 * The message cannot say which of two things it is, so what this device was
 * doing when it arrived decides:
 *
 *  - **mid-send** it is the controller closing the transfer out on its own after
 *    `NET_IMPORT_IDLE_MS` of silence. It is the only account there will ever be
 *    of the readings it covers, and the frames still going out will open a fresh
 *    transfer with its own counters. So it is *kept*, not acted on - writing the
 *    result on screen and letting the next progress update overwrite it is how
 *    those readings stop being accounted for anywhere.
 *  - **after the last frame** it is the answer the transfer has been waiting
 *    for, and the parts kept above are added back in. `duplicate` is where they
 *    go: they are readings the controller demonstrably holds, which is what that
 *    number means, and it is what [SessionTransfer.Done.landed] adds up.
 *
 * An answer that arrives *after* this device gave up waiting still replaces the
 * failure. Late is better than never, and the controller's own account beats a
 * timeout every time.
 */
fun transferAfterAnswer(
    current: SessionTransfer,
    file: String,
    msg: ServerMessage.ImportResult,
): SessionTransfer {
    if (current is SessionTransfer.Sending) {
        return current.copy(landedSoFar = current.landedSoFar + msg.added + msg.duplicate)
    }
    val earlier = when (current) {
        is SessionTransfer.Awaiting -> current.landedSoFar
        else -> 0
    }
    if (current is SessionTransfer.Idle || current is SessionTransfer.Rejected ||
        current is SessionTransfer.Checking
    ) {
        // Nothing of ours is out there for this to be about.
        return current
    }
    return SessionTransfer.Done(
        file = file.ifEmpty { msg.name },
        added = msg.added,
        duplicate = msg.duplicate + earlier,
        rejected = msg.rejected,
        sessions = msg.sessions,
        ranOutOfRoom = msg.full,
        refused = msg.tookNothing,
        message = if (earlier > 0) {
            "${msg.message}; $earlier more were taken earlier, while the file was still going out"
        } else {
            msg.message
        },
    )
}

/**
 * The transfer state the answer deadline produces, or [current] unchanged.
 *
 * Only [SessionTransfer.Awaiting] has a deadline. A transfer that is still
 * sending is covered by [streamIsDead], and everything else has already ended.
 */
fun transferAfterAnswerDeadline(current: SessionTransfer, nowMs: Long): SessionTransfer {
    if (current !is SessionTransfer.Awaiting) return current
    if (!transferAnswerOverdue(nowMs, current.sentAtMs)) return current
    return SessionTransfer.Failed(
        current.file,
        transferUnansweredReason(
            "the sensor never said what it did with them, for ${TRANSFER_ANSWER_MS / 1000} seconds",
        ),
    )
}

/**
 * Process-lifetime owner of the live connection, history, and chart view. A
 * singleton so the stream survives Activity recreation and can be shared with the
 * foreground service. It runs whichever [Transport] the configured
 * [ConnectionMode] selects (Wi-Fi/TCP or Bluetooth-LE) - the message-folding /
 * history / chart code is transport-agnostic because both emit the same
 * [ServerMessage]s.
 *
 * All non-thread-safe model mutation is confined to a single-threaded dispatcher,
 * so the message collector, the 1 Hz recompute ticker, and UI control calls never
 * race.
 */
object MonitorSession {

    private const val PORT = Wire.DEFAULT_PORT
    private val CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    private const val SPARK_WINDOW_SECONDS = 3600.0
    private const val SPARK_MAX_POINTS = 48

    // Point budget bounds, before the chart has reported its width.
    private const val MIN_POINTS = 120
    private const val MAX_POINTS = 2000
    private const val DEFAULT_POINTS = 600

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val confinement = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confinement)

    /**
     * The plot.
     *
     * `internal` rather than private only so a JVM test can fill it and read
     * [buildCsv] back. The export is the one place the derived/measured
     * distinction reaches the user as a file, and there is no other way to
     * exercise it without a device.
     */
    internal val history = TimeSeriesHistory(retentionSeconds = 172_800.0)

    /**
     * What is known about a clockless controller's clock.
     *
     * Owned here rather than by the history because two things consult it: the
     * store, to place points, and the UI, to say plainly when a block cannot be
     * placed at all. `internal` for the same reason as [history].
     */
    internal val timeBasis = TimeBasis().also { history.basis = it }
    private val chart = ChartView(windowSeconds = 300.0)

    private val _ui = MutableStateFlow(DashboardUiState(windowSeconds = 300.0))
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    @Volatile private var appContext: Context? = null

    /**
     * One transport and the coroutine running it.
     *
     * Two of these exist only during a switch: the one still carrying the stream
     * and the one being brought up beside it. [serving] is what tells a link's own
     * collectors whether they are allowed to drive the model, and it is the single
     * flag that keeps a half-built link from reaching into the backfill queue,
     * the banner or the chart while the working link is still in charge.
     */
    private class Link(val mode: ConnectionMode, val transport: Transport) {
        var job: Job? = null
        @Volatile var serving = false
    }

    /** The link whose messages drive the model and whose socket commands go out on. */
    @Volatile private var active: Link? = null
    /** A link being brought up beside [active] during a switch. */
    private var pending: Link? = null
    /** Gives up on [pending] after [HANDOVER_TIMEOUT_MS] and commits the switch anyway. */
    private var handoverJob: Job? = null
    /** The mode of [active] - what the app is connected *by*. */
    private var currentMode: ConnectionMode? = null
    /** The mode the config asks for, which leads [currentMode] during a switch. */
    private var desiredMode: ConnectionMode? = null
    @Volatile private var started = false

    /** Commands and requests go to whichever link is serving. */
    private val transport: Transport? get() = active?.transport

    /** Start the recompute ticker once per process (idempotent). The connection is
     *  started by the first [applyConfig], which knows the persisted mode. */
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        // Held for the process lifetime: requestNetwork is not cheap, and losing
        // the binding between reconnects would drop it exactly when a flaky link
        // needs it most.
        WifiNetworkBinder.start(context)
        // The portable core must not name an Android class, so it asks
        // ControllerSockets for its sockets and CoreLog for its one warning
        // path; this is where Android answers both.
        ControllerSockets.factory = WifiNetworkBinder::createSocket
        CoreLog.warn = { tag, message -> android.util.Log.w(tag, message) }
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        // The plot this device already holds, put back before anything else on
        // this dispatcher gets a turn. Queued first and deliberately not
        // suspending anywhere inside: everything else the model does - including
        // the connection, which the first applyConfig starts - lines up behind it
        // on the same single thread. A `hello` folded in against an empty history
        // would ask the controller for a day of readings that are already here,
        // which is the whole thing this is for.
        scope.launch { restorePlot() }
        // While a gesture is in flight the flush loop is already recomputing at
        // its own pace; a live tick on top of that is pure contention.
        scope.launch {
            while (isActive) {
                if (!gestureActive) {
                    pendingRedraw = false
                    recompute()
                }
                tickClock()
                refreshInventory()
                checkStream()
                checkBackfill()
                checkReconcile()
                checkTransferAnswer()
                flushPlotCache()
                delay(1000)
            }
        }
    }

    // --- the plot, kept on this device ---------------------------------------
    //
    // The history above is memory, and memory goes when the app does. The board
    // still has the readings and the reconnect backfill still fetches them, but
    // "reopen the app and wait for two days of transfer" is not what anyone means
    // by keeping a chart. [PlotCache] writes what arrives to a journal in the
    // app's own storage and reads it back at startup.
    //
    // What it deliberately does NOT change is the reconciliation with the board.
    // The cache restores the per-session totals and the coverage the backfill
    // reasons from, so `hello` -> `list_files` -> `get_file since_index` runs
    // afterwards exactly as it did before and asks only for what is genuinely
    // missing. Persisted history is a head start, not a second source of truth.

    private var plotCache: PlotCache? = null

    /**
     * Read the cache back, and frame what came out of it.
     *
     * Framed with Auto Set rather than left on the default five-minute live
     * window, because a cache is by definition older than now: the readings
     * would all be off the left edge of the plot and the screen would say the
     * restore had done nothing. Nothing is being overridden by that - the window
     * has never been persisted across a restart, so there is no preference here
     * to lose.
     */
    private fun restorePlot() {
        val context = appContext ?: return
        val cache = PlotCache(File(context.filesDir, PlotCache.FILE_NAME), history, timeBasis)
        plotCache = cache
        val readings = try {
            cache.load()
        } catch (e: Exception) {
            android.util.Log.w("MonitorSession", "the plot cache could not be read: ${e.message}")
            0
        }
        reportCacheTrouble(cache)
        if (readings <= 0) return
        android.util.Log.i("MonitorSession", "$readings readings restored from this device")
        publishRelativeState()
        chart.setAutoInterval()
        chart.applyAll(history.firstStableLiveEpoch() ?: currentNow(), currentNow())
        recompute()
    }

    /**
     * Write the cache out, and never let it stop the tick.
     *
     * This runs in the same loop as the redraw, the stream watchdog and the
     * backfill deadline. A cache is the least important thing in that list by a
     * wide margin - the readings are in memory and on the controller either
     * way - so anything it throws is caught here rather than taking the loop
     * that keeps the app alive down with it.
     */
    private fun flushPlotCache() {
        val cache = plotCache ?: return
        try {
            cache.flush()
        } catch (e: Exception) {
            android.util.Log.w("MonitorSession", "the plot cache could not be written: ${e.message}")
        }
        reportCacheTrouble(cache)
    }

    /** Say what went wrong with the cache, once per distinct complaint. */
    private var lastCacheError: String? = null

    private fun reportCacheTrouble(cache: PlotCache) {
        val trouble = cache.lastError ?: return
        if (trouble == lastCacheError) return
        lastCacheError = trouble
        android.util.Log.w(
            "MonitorSession",
            if (cache.degraded) "$trouble; the plot will not survive a restart"
            else "$trouble; the plot is still being kept",
        )
    }

    // --- which transport is carrying the stream -------------------------------
    //
    // Switching between Wi-Fi and Bluetooth used to break the working link before
    // building the new one, so the plot stopped for as long as a BLE scan takes -
    // fifteen seconds and change - and whatever the board recorded meanwhile only
    // came back if the reconnect backfill happened to reach it. Both transports are
    // ordinary clients of the same protocol on the board (`net_link.h`) and it
    // serves several at once, so there is no reason to have neither.
    //
    // So a switch is now **make-before-break**: the new transport comes up beside
    // the working one, which keeps delivering readings until the new one says
    // `hello`. At that moment the new link takes over, the old one is retired, and
    // the ordinary hello -> `list_files` / `get_file since_index` backfill collects
    // anything that fell in the seam. Nothing is interpolated: the board's own
    // record is what fills it, and de-dup by `(csv, index)` makes the overlap free.

    /** Route a mode selection through [transportChangeFor] and carry out the verdict. */
    private fun selectMode(mode: ConnectionMode) {
        desiredMode = mode
        when (val change = transportChangeFor(
            target = mode,
            serving = currentMode,
            pending = pending?.mode,
            servingConnected = _ui.value.connection is ConnectionState.Connected,
        )) {
            is TransportChange.Nothing -> Unit
            is TransportChange.CancelSwitch -> cancelHandover()
            is TransportChange.Restart -> startConnection(change.mode)
            is TransportChange.Handover -> beginHandover(change.mode)
        }
    }

    /** (Re)start the connection using [mode]'s transport, replacing any current one.
     *  [ConnectionMode.NONE] tears the link down and leaves it down. */
    private fun startConnection(mode: ConnectionMode) {
        cancelHandover()
        active?.let { retire(it, "the connection was changed") }
        active = null
        currentMode = mode
        desiredMode = mode
        lastReadingAtMs = 0L
        lastTransferProgressAtMs = 0L
        // A deliberate (re)start is a fresh verdict: never inherit the last one.
        _ui.update {
            it.copy(
                activeMode = mode,
                switchingTo = null,
                switchingDetail = null,
                discovering = false,
                discoveryFailed = false,
                discoveryDetail = null,
                discoveryAdvice = null,
                streamStalled = false,
            )
        }
        if (mode == ConnectionMode.NONE) {
            // Keep the plotted history on screen - the sensor is still recording and
            // reconnecting will backfill whatever was missed.
            _ui.update { it.copy(connection = ConnectionState.Idle, discovering = false) }
            appContext?.let { Sps30Service.stop(it) }
            _ui.update { it.copy(backgroundEnabled = false) }
            return
        }
        val t = createTransport(mode) ?: return
        val link = Link(mode, t)
        link.serving = true
        active = link
        launchLink(link)
    }

    /**
     * Bring [mode] up beside the link that is working, and switch over when it
     * answers.
     *
     * The old link is left completely alone until then - same socket, same
     * collectors, same backfill queue - so from the user's side the stream simply
     * does not stop.
     */
    private fun beginHandover(mode: ConnectionMode) {
        cancelHandover()
        val t = createTransport(mode) ?: run { startConnection(mode); return }
        val link = Link(mode, t)
        pending = link
        // A switch that has just been asked for is not the moment to still be
        // showing why the last one failed.
        _ui.update { it.copy(switchingTo = mode, switchingDetail = null, connectionNotice = null) }
        launchLink(link)
        handoverJob = scope.launch {
            delay(HANDOVER_TIMEOUT_MS)
            if (pending === link) {
                // It has had a full search and connect to say hello and has not.
                // Abandon it rather than commit: a link that is delivering readings
                // is worth more than the mode that could not be had, and the user
                // can ask again the moment there is something to find.
                android.util.Log.i(
                    "MonitorSession", "switch to $mode timed out; keeping $currentMode",
                )
                abandonSearch(link)
            }
        }
    }

    /**
     * Give up on [link], keep whatever is actually working, and say so.
     *
     * Reached two ways: the transport stopped looking of its own accord (the BLE
     * search spending its minute), or [HANDOVER_TIMEOUT_MS] elapsed on a transport
     * that never would. The verdict is the same either way and is stated in
     * [searchAbandonedFor].
     *
     * Launched on the session's own scope rather than run where it is called from,
     * because both callers are coroutines this is about to cancel.
     */
    private fun abandonSearch(link: Link) = scope.launch {
        val switching = link === pending
        if (!switching && link !== active) return@launch // already retired; nothing to say
        // A switch on its way somewhere else is the user's own answer to this, and
        // must not be torn down by it. Only a link that has never connected gives
        // up, and a switch is only ever started from one that has, so this cannot
        // happen - but the consequence if it ever did is losing what was asked for.
        if (!switching && pending != null) return@launch
        // A pending link has never served, so what is serving is untouched by this.
        // An active one that gave up leaves nothing behind.
        val verdict = searchAbandonedFor(link.mode, if (switching) currentMode else null)
        if (switching) cancelHandover()
        if (currentMode != verdict.fallback) startConnection(verdict.fallback)
        // Release the mode that could not be had, so its button is live again and a
        // second try is one tap. Persisted, or the next launch would spend the whole
        // budget again looking for something the app has already been told is absent.
        desiredMode = verdict.fallback
        val config = _ui.value.config.copy(connectionMode = verdict.fallback)
        _ui.update { it.copy(config = config, connectionNotice = verdict.notice) }
        appContext?.let { ConfigStore.saveCurrent(it, config) }
    }

    /** Put the failed-connection notice away; the user has read it. */
    fun dismissConnectionNotice() = onModel {
        _ui.update { it.copy(connectionNotice = null) }
    }

    /** Abandon a switch in progress, leaving the working link exactly as it was. */
    private fun cancelHandover() {
        handoverJob?.cancel()
        handoverJob = null
        pending?.let { retire(it, null) }
        pending = null
        _ui.update { it.copy(switchingTo = null, switchingDetail = null) }
    }

    /**
     * Hand the stream over to [link] and retire whatever was serving before it.
     *
     * Called from the promotion path (the new link said hello) and from the
     * handover timeout, which is why the banner state is taken from the link
     * itself rather than assumed to be `Connected`.
     */
    private fun makeActive(link: Link) {
        val old = active
        if (old === link) return
        if (pending === link) {
            pending = null
            handoverJob?.cancel()
            handoverJob = null
        }
        active = link
        link.serving = true
        currentMode = link.mode
        // Requests belong to the link that made them: the old one's cursor died
        // with it, and the hello about to be folded in refills the queue.
        backfill.clear()
        val st = link.transport.state.value
        lastReadingAtMs = if (st is ConnectionState.Connected) System.currentTimeMillis() else 0L
        // A new link is a fresh verdict, exactly as a deliberate restart is.
        val flags = discoveryFlagsFor(st, wasFailed = false)
        _ui.update {
            it.copy(
                activeMode = link.mode,
                switchingTo = null,
                switchingDetail = null,
                connection = st,
                discovering = flags.discovering,
                discoveryFailed = flags.failed,
                discoveryDetail = flags.detail,
                discoveryAdvice = flags.advice,
                streamStalled = false,
            )
        }
        old?.let { retire(it, "the connection was changed") }
    }

    /**
     * Stop [link] for good.
     *
     * [why] is non-null when a bulk transfer bound to this link has to be given
     * up with it; see [abandonLinkBoundWork]. It is null for a link that was never
     * serving, which cannot have had any.
     */
    private fun retire(link: Link, why: String?) {
        link.serving = false
        link.job?.cancel()
        link.job = null
        // Force-close now: TCP blocking reads ignore cancellation, so without this
        // the old socket lingers until the board drops it.
        link.transport.stop()
        if (why != null) abandonLinkBoundWork(why)
    }

    /** Start [link]'s connect/retry loop and its two collectors. */
    private fun launchLink(link: Link) {
        val t = link.transport
        link.job = scope.launch {
            // Whether this attempt ever reached the host, so a fruitless sweep backs
            // off while a genuine drop still retries briskly.
            var reachedHost = false
            val stateJob = launch {
                t.state.collect { st ->
                    if (st is ConnectionState.Connected) reachedHost = true
                    onLinkState(link, st)
                }
            }
            val msgJob = launch { t.messages.collect { onLinkMessage(link, it) } }
            try {
                var retryMs = MIN_RETRY_MS
                while (isActive) {
                    reachedHost = false
                    try {
                        // One attempt over TCP - it returns when the sweep finds
                        // nothing and when an established link drops, which is why
                        // this loop, not the transport, owns the retry cadence.
                        // BLE retries internally and returns only on cancellation.
                        t.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onLinkState(link, ConnectionState.Reconnecting(e.message ?: "connection lost"))
                    }
                    if (!isActive) break
                    // A transport that has stopped looking has decided something
                    // this loop must not undo: calling it again would start the
                    // whole search over and be exactly the endless scan the bound
                    // exists to end.
                    if (!keepsTrying(t.state.value)) { abandonSearch(link); break }
                    delay(retryMs)
                    retryMs = if (reachedHost) MIN_RETRY_MS else nextRetryMs(retryMs)
                }
            } finally {
                stateJob.cancel(); msgJob.cancel()
            }
        }
    }

    /**
     * One link's connection state.
     *
     * Only the serving link touches the banner. A link being built beside a working
     * one reports through [DashboardUiState.switchingDetail] instead, because
     * writing "searching for the sensor…" over a connection that is delivering
     * readings describes the app as disconnected while it plainly is not.
     */
    private fun onLinkState(link: Link, st: ConnectionState) {
        if (!link.serving) {
            if (link === pending) {
                _ui.update { it.copy(switchingDetail = handoverDetail(st)) }
            }
            return
        }
        // A bulk transfer cannot survive the link under it going away: the board
        // abandons an in-flight import when its client goes, and a resumed export
        // would append sessions the archive already holds. Say so, once.
        if (st !is ConnectionState.Connected) abandonLinkBoundWork("the connection dropped")
        _ui.update {
            val flags = discoveryFlagsFor(
                st, it.discoveryFailed, it.discoveryDetail, it.discoveryAdvice,
            )
            it.copy(
                connection = st,
                discovering = flags.discovering,
                discoveryFailed = flags.failed,
                discoveryDetail = flags.detail,
                discoveryAdvice = flags.advice,
                // The lock belongs to a connection. Once this one is not up, what
                // the last one said about it is no longer an answer, and the next
                // one has to say so again before the Sensor tab claims anything.
                controlTold = it.controlTold && st is ConnectionState.Connected,
            )
        }
    }

    /**
     * One link's messages.
     *
     * The serving link's drive the model. A pending link's are dropped until its
     * `hello` arrives, which is the moment it takes over - the old link is still
     * delivering those same readings, and letting two links fold into one history
     * would double every message for the length of the switch.
     */
    private fun onLinkMessage(link: Link, msg: ServerMessage) {
        if (link.serving) {
            onMessage(msg)
            return
        }
        if (link !== pending) return
        if (msg is ServerMessage.Hello) {
            makeActive(link)
            onMessage(msg) // ... and its backfill request now goes out on this link
        }
    }

    /** A short phrase for how far the link being switched to has got. */
    private fun handoverDetail(st: ConnectionState): String? = when (st) {
        is ConnectionState.Searching -> st.detail
        is ConnectionState.NotFound -> st.detail
        is ConnectionState.Connecting -> "connecting"
        is ConnectionState.Connected -> "waiting for the sensor"
        is ConnectionState.Reconnecting -> st.detail
        ConnectionState.Idle -> null
    }

    /**
     * Give up whatever was riding on a link that has just gone.
     *
     * Both directions of bulk transfer are tied to one connection and neither
     * survives it changing. An export writes one zip entry per session and cannot
     * reopen one it has closed, so resuming it after a reconnect used to fail with
     * `duplicate entry: SPS30-….csv`; an import is abandoned at the board the
     * moment its client disappears (`close_client`), and the frames still queued
     * for a retired transport have nothing draining them, so the progress bar sat
     * at "Sending" for good. Saying plainly what happened beats both.
     */
    private fun abandonLinkBoundWork(why: String) {
        // The drain first, because it is the only one of these whose interruption
        // could have cost something. It has not: a block is freed on the sensor
        // only after this device has written it down and proved it, so the worst
        // a lost link can do is leave a block half-received here - which is
        // thrown away, because the sensor still has it.
        drain?.let { run ->
            run.interrupted("$why while copying the sensor off")
            _ui.update { it.copy(clearing = run.status) }
            drain = null
        }
        // A `get_file` is bound to its link too: the controller serves it from a
        // cursor that dies with the client, so what is queued here belongs to a
        // connection that has gone. The hello on the way back rebuilds it from
        // what this device holds by then.
        backfill.clear()
        // The listing walk is bound to the link too: the page it is waiting for
        // was asked of a controller that has gone.
        listingPageDueAtMs = 0L
        // And a sweep waiting on that walk. Left set, it would swallow the next
        // link's ordinary `list_files` as though it were the sweep's own answer
        // and reconcile against one page - which is exactly the short listing
        // that must never be allowed to say "lost".
        if (reconciling) {
            reconciling = false
            _ui.update { it.copy(gaps = it.gaps.copy(sweeping = false)) }
        }
        if (fetchingAll) {
            fetchingAll = false
            closeArchiveWriter()
            _ui.update {
                it.copy(backup = SessionBackup.Failed("$why while copying; nothing was saved"))
            }
        }
        if (transferJob?.isActive == true) {
            transferJob?.cancel()
            transferJob = null
            // The job's own `finally` does this too, but it is not joined and
            // the link is being replaced now: a stale timestamp would have the
            // next link judged as though it were mid-transfer.
            lastTransferProgressAtMs = 0L
            android.util.Log.w(
                TAG_TRANSFER,
                "$importFile abandoned after $importSentRows of $importTotalRows readings: $why",
            )
            _ui.update {
                it.copy(
                    transfer = SessionTransfer.Failed(
                        importFile,
                        "$why while sending; the sensor kept the readings that had already arrived",
                    ),
                )
            }
            return
        }
        // A transfer whose frames have all gone is not a job any more, and this
        // is where it used to be lost: nothing was running, so nothing noticed
        // the link go, and the state sat on "sending" for ever. The controller
        // answers a transfer once and over the connection it arrived on, so a
        // connection that has gone is an answer that is never coming.
        val awaiting = _ui.value.transfer as? SessionTransfer.Awaiting ?: return
        android.util.Log.w(
            TAG_TRANSFER,
            "${awaiting.file}: ${awaiting.rowsSent} readings had gone out and $why before the " +
                "sensor said what it did with them",
        )
        _ui.update {
            it.copy(
                transfer = SessionTransfer.Failed(
                    awaiting.file,
                    // The same words as the deadline's, because it is the same
                    // thing that happened: everything went, and nothing came back.
                    transferUnansweredReason("$why before the sensor said what it did with them"),
                ),
            )
        }
    }

    /** Which `Wire.TRANSPORT_*` the serving link is actually talking over. */
    private fun localTransport(): String? = when (currentMode) {
        ConnectionMode.WIFI -> Wire.TRANSPORT_TCP
        ConnectionMode.BLUETOOTH -> Wire.TRANSPORT_BLE
        ConnectionMode.NONE, null -> null
    }

    private fun createTransport(mode: ConnectionMode): Transport? = when (mode) {
        // With a context the Wi-Fi transport can listen for the controller's own
        // announcement before falling back to the sweep; without one it sweeps,
        // which is what it always did. See LanDiscovery.
        ConnectionMode.WIFI -> TcpTransport(PORT, appContext?.let { NsdServiceBrowser(it) })
        ConnectionMode.BLUETOOTH -> appContext?.let { BleTransport(it) }
        ConnectionMode.NONE -> null // handled before this is reached
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        _ui.update { it.copy(backgroundEnabled = enabled) }
    }

    private fun onMessage(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Hello -> {
                _ui.update {
                    it.copy(
                        sensor = SensorInfo(msg.product, msg.serial, msg.firmware),
                        daemonState = msg.state,
                        daemonDetail = msg.detail,
                        // A hello from a host that is up clears the shutdown latch:
                        // it was power-cycled, which is the only way back.
                        sensorShutDown = msg.state == Wire.STATE_SHUTDOWN,
                        phaseEndsAtMs = msg.phaseSeconds?.let { s ->
                            System.currentTimeMillis() + (s * 1000).toLong()
                        },
                        profile = msg.profile ?: it.profile,
                        power = msg.power ?: it.power,
                        // A hello may be a different host than the last one, and
                        // an estimate priced on another board's state is not a
                        // stale copy but a wrong one. Asked again when the panel
                        // next opens.
                        powerEstimate = null,
                        powerEstimateRefusal = null,
                        supportsSleep = msg.supportsSleep,
                        canPowerOff = msg.canPowerOff,
                        clientId = msg.clientId ?: it.clientId,
                        maxClients = Wire.effectiveMaxClients(msg.maxClients, localTransport()),
                    )
                }
                // Name ourselves so other users see a person, not an IP address.
                lastReadingAtMs = System.currentTimeMillis()
                _ui.update { it.copy(streamStalled = false) }
                transport?.send(Commands.identify(deviceName()))
                // Sent on every connection, before anything else this device
                // wants. Two things it does: tells the controller which line to
                // keep readings against when its ring wraps, and declines the
                // delivery if the user has turned it off. Declining early
                // matters - the controller starts sending unasked, which is the
                // whole point, so the opt-out has to be the first thing it
                // hears rather than something it learns halfway through.
                sendPreservationPolicy()
                // And the time, to a controller that has said it has none. Only
                // when it asks: a host that keeps its own clock declines, and an
                // unsolicited refusal arriving in the middle of a backfill is an
                // error to explain away rather than an answer to anything.
                if (!msg.hasClock) offerTheTime()
                requestBackfill(msg.csv)
            }

            is ServerMessage.AcquisitionProfile -> _ui.update { it.copy(profile = msg) }

            is ServerMessage.Control -> _ui.update {
                it.copy(
                    controlHolder = msg.holder,
                    controlHolderName = msg.holderName,
                    controlTold = true,
                    // Clear a stale refusal once control actually moves.
                    controlDenied = if (msg.holder == null || msg.holder == it.clientId) {
                        null
                    } else {
                        it.controlDenied
                    },
                )
            }

            is ServerMessage.Clients -> _ui.update {
                // The daemon counts sockets, so its figure is the Wi-Fi one even for a
                // phone that arrived over the BLE bridge. Report what THIS link can
                // actually carry, so Bluetooth never claims room for a second device.
                it.copy(
                    watchers = msg.clients,
                    maxClients = Wire.effectiveMaxClients(msg.max, localTransport()),
                )
            }

            is ServerMessage.ErrorMsg -> {
                // A refusal that answers one of the drain commands stops the
                // drain, and stops it here rather than leaving it waiting for a
                // block that is never coming. Checked before the general rule
                // below because this one has a `ref` that says so exactly.
                if (msg.ref in DRAIN_COMMANDS) {
                    onDrain { it.onError(msg.message) }
                }
                // An error answering `get_power_estimate` is not a refusal to
                // show in a dialog - a dialog for tapping an informational icon
                // would punish the tap. It is classified here, while the hello's
                // description of the host is at hand, and the power panel gives
                // each refusal its own accurate words: a controller on firmware
                // from before the power model is not the same situation as the
                // Pi daemon that never models power, and neither may borrow the
                // other's explanation.
                if (msg.ref == Wire.CMD_GET_POWER_ESTIMATE) {
                    _ui.update {
                        it.copy(
                            powerEstimateRefusal = classifyPowerRefusal(
                                message = msg.message,
                                helloSeen = it.sensor != null,
                                canPowerOff = it.canPowerOff,
                            ),
                        )
                    }
                    return
                }
                // Every refusal is shown, not just the control-lock one, and a
                // running backfill is no longer a reason to hide one or to give up
                // on the transfer. `ref` now says which command the error answers,
                // so where the controller states it this is a fact rather than a
                // guess. See [errorHandlingFor] for the whole rule.
                val handling = errorHandlingFor(
                    needsControl = msg.needsControl,
                    copyRequested = _ui.value.backup is SessionBackup.Requested,
                    backfillInFlight = backfill.current != null,
                    ref = msg.ref,
                )
                if (handling.showDialog) {
                    _ui.update {
                        it.copy(controlDenied = msg.message, refusalNeedsControl = msg.needsControl)
                    }
                }
                if (handling.failsCopy) {
                    fetchingAll = false
                    // The half-written zip goes with it. It was being left open on
                    // this path alone, so a refused copy held its temp file until
                    // the next one happened to replace it.
                    closeArchiveWriter()
                    failBackup(msg.message)
                }
            }

            is ServerMessage.BackupMeta -> onBackupMeta(msg)

            is ServerMessage.BackupChunk -> onBackupChunk(msg)

            is ServerMessage.PowerStatus -> _ui.update { it.copy(power = msg) }

            is ServerMessage.PowerEstimate -> _ui.update {
                it.copy(powerEstimate = msg, powerEstimateRefusal = null)
            }

            is ServerMessage.Reading -> {
                lastReadingAtMs = System.currentTimeMillis()
                if (_ui.value.streamStalled) _ui.update { it.copy(streamStalled = false) }
                history.add(msg)
                // The screen has to say a controller has no clock as soon as one
                // of its readings arrives, not only when the next `time_map`
                // does - on a board with no equivalences yet, that would be
                // never. Cheap to repeat: the state is a data class, so an
                // unchanged one does not re-emit.
                if (msg.relative) publishRelativeState()
                _ui.update { it.copy(latest = msg.values, latestTimestamp = msg.timestamp) }
                checkAlerts(msg.values)
                // The watch shows an instant, so it is sent one only when there
                // is one. A relative reading relays its values with no timestamp
                // rather than an empty string the watch would render as a blank
                // clock - the values are current either way, which is what the
                // complication is for.
                maybeRelayToWatch(msg.values, msg.timestamp.ifEmpty { null })
                recompute()
            }

            is ServerMessage.StateMsg -> _ui.update {
                it.copy(
                    daemonState = msg.state,
                    daemonDetail = msg.detail,
                    sensorShutDown = msg.state == Wire.STATE_SHUTDOWN,
                    phaseEndsAtMs = msg.phaseSeconds?.let { s ->
                        System.currentTimeMillis() + (s * 1000).toLong()
                    },
                )
            }

            // The controller's list of what it still holds. Everything the
            // plot is missing is asked for from here.
            is ServerMessage.FileList -> onFileList(msg)

            // Backfill: rows we missed while disconnected, backgrounded, asleep
            // or simply not yet installed. De-dup by (csv, index) makes overlap
            // safe, so asking for more than we need only costs time.
            is ServerMessage.FileData -> {
                backfill.progressed(msg.name, System.currentTimeMillis())
                // Into the archive first: the export is a copy of what the
                // controller holds, so every row of a session the copy asked for
                // belongs in the file whether or not the plot had it already.
                // Streamed rather than buffered - weeks of recording does not fit
                // in a tablet's heap, and nothing here needs a whole session at
                // once.
                if (backfill.current?.forArchive == true) archiveRows(msg)
                // `explicit`: a session asked for by name, so retention must not
                // delete it on the way in - that is what made an hour-old copy
                // vanish as fast as it arrived.
                val added = history.addRows(msg.name, msg.columns, msg.rows, explicit = true)
                // Count what was STORED, not what arrived. Asking for a session
                // from index 0 re-sends rows already held, and counting those was
                // how the button claimed 2,590 readings it had not added.
                backfillRows += added
                pendingRedraw = pendingRedraw || added > 0
                // Deliberately NOT recomputing per frame. A copy is hundreds of
                // frames and recomputing the whole chart on each one starved this
                // collector badly enough that the transport dropped live readings
                // to keep up - so the copy cost live data. The redraw is coalesced
                // onto the 1 Hz tick, and forced when the transfer ends.
                if (msg.done) {
                    pendingRedraw = false
                    recompute()
                    onBackfillFinished(msg.name)
                }
                if (fetchingAll) publishFetchProgress()
            }

            is ServerMessage.TimeMap -> {
                if (timeBasis.accept(msg)) {
                    // Everything already on the plot was placed with less
                    // knowledge than there is now, so it is placed again. The
                    // readings are untouched; only where they sit moves.
                    val moved = history.retime()
                    publishRelativeState()
                    if (moved > 0) recompute()
                } else {
                    publishRelativeState()
                }
            }

            is ServerMessage.Storage -> {
                _ui.update { it.copy(storage = msg) }
                refreshInventory()
                autoExportIfNearlyFull(msg)
            }

            is ServerMessage.StorageMap -> _ui.update { it.copy(storageMap = msg) }

            // What the controller preserved against its ring wrapping. It sends
            // this unasked; the only thing this side decides is whether to keep
            // receiving it and what line to preserve against.
            is ServerMessage.Alerts -> onAlerts(msg)

            is ServerMessage.AlertSegment -> {
                _ui.update { state ->
                    // Newest first, and one entry per sequence: the controller
                    // may resend a segment after a reconnect, and a second copy
                    // in the list would read as a second excursion.
                    val kept = state.alertSegments.filter { it.seq != msg.seq }
                    state.copy(alertSegments = (listOf(msg) + kept).take(MAX_ALERT_SEGMENTS))
                }
                if (msg.seq > highestAlertSeq) highestAlertSeq = msg.seq
                android.util.Log.i(
                    "MonitorSession",
                    "preserved alert segment ${msg.seq}: ${msg.points} points of " +
                        "${msg.field}, mean ${"%.1f".format(msg.mean)}, " +
                        "max ${"%.1f".format(msg.maximum)}" +
                        if (msg.hasBody) "" else " (summary only; the samples were reduced away)",
                )
            }

            // The samples of a preserved segment. They are readings of a real
            // session, so they go in through the same door a backfill uses -
            // same de-dup by (csv, index), same timestamp handling. `explicit`
            // because retention must not drop them on the way in: these are the
            // rows that cannot be fetched again once the controller's ring has
            // moved past them.
            is ServerMessage.AlertData -> {
                val added = history.addRows(msg.name, msg.columns, msg.rows, explicit = true)
                pendingRedraw = pendingRedraw || added > 0
                if (msg.done) {
                    pendingRedraw = false
                    recompute()
                }
            }

            is ServerMessage.ImportResult -> onImportResult(msg)

            is ServerMessage.EraseResult -> onEraseResult(msg)

            // Backing everything up and clearing the sensor. Both frames go to
            // the one object that owns the rule, and whatever it hands back is
            // sent - there is no decision about the drain in this file.
            is ServerMessage.DrainState -> onDrain { it.onState(msg) }

            is ServerMessage.DrainData -> onDrain { it.onData(msg) }

            else -> Unit
        }
    }

    // --- backfill -------------------------------------------------------------
    //
    // One request at a time. The controller serves a `get_file` from a single
    // cursor, so two overlapping requests would truncate the first, and a phone
    // that has been away for two days may have a dozen sessions to collect.
    // Serialising also means the live stream keeps arriving between frames
    // instead of being buried under a bulk transfer.

    private val backfill = BackfillQueue()
    private var backfillRows = 0
    // Written from the UI thread (the copy button, the auto-export) and read on
    // the model thread that folds messages, so it has to be published.
    @Volatile private var fetchingAll = false
    private var fetchedSessions = 0
    /** Rows landed since the last redraw; see the FileData branch for why. */
    private var pendingRedraw = false

    /**
     * Preserved excursions kept in the UI list.
     *
     * A bound on what is *shown*, never on what is received: the samples of
     * every segment have already gone into the history by the time this list is
     * trimmed, so an excursion that falls off the end has lost its summary card
     * and none of its readings. The controller holds hundreds of segments and a
     * list that long is not a thing anyone reads.
     */
    private const val MAX_ALERT_SEGMENTS = 50

    /**
     * On (re)connect, ask the controller for everything it holds that we do not.
     *
     * The Hello names the current session, which is asked for straight away
     * because it is the data someone is most likely looking at. `list_files`
     * then names the rest, including sessions from before this app was
     * installed and from before the last reboot, and those are collected behind
     * it.
     *
     * This used to request the current session and nothing else, which was
     * right when the controller was a Pi holding one long CSV and wrong for a
     * board that starts a new session every time it reboots: every earlier
     * session was unreachable, so a gap in the plot could never be filled.
     */
    private fun requestBackfill(csv: String?) {
        backfill.clear()
        val name = csv.orEmpty()
        val held = if (name.isEmpty()) null else history.coverage()[name]
        // Only when this device can already see it is missing something; see
        // [liveSessionNeedsHeadStart] for why the rest waits for `file_list`.
        if (name.isNotEmpty() && liveSessionNeedsHeadStart(held)) {
            backfill.enqueue(name, held?.contiguousTo ?: 0)
        }
        // And a reconciliation behind it, once this has had time to land. The
        // reconnect closes what it can see; the sweep closes what it cannot. See
        // [RECONCILE_AFTER_CONNECT_MS].
        sweepDueAtMs = System.currentTimeMillis() + RECONCILE_AFTER_CONNECT_MS
        transport?.send(Commands.listFiles())
        pumpBackfill()
    }

    /**
     * Tell the controller what to preserve, and whether to send it here.
     *
     * The thresholds are the minimum enabled rule per field and nothing else -
     * see [AppConfig.preservationThresholds] for why the minimum and why so
     * little reaches the controller at all.
     */
    private fun sendPreservationPolicy() {
        val config = _ui.value.config
        transport?.send(
            Commands.setAlerts(
                auto = config.receivePreservedAlerts,
                thresholds = config.preservationThresholds,
            )
        )
        // And what this device already holds, so a reconnect does not re-send
        // every segment the controller has ever preserved. Not persisted across
        // an app restart on purpose: a fresh install holds nothing, and asking
        // for everything once is exactly right then.
        //
        // A segment or two may still arrive twice - the controller starts
        // sending the moment it says hello, which is the feature - and that
        // costs nothing: the rows de-duplicate by (session, index) like every
        // other row this app takes in.
        if (highestAlertSeq > 0 && config.receivePreservedAlerts) {
            transport?.send(Commands.getAlerts(highestAlertSeq))
        }
    }

    /** The newest preserved segment this device has been sent. */
    private var highestAlertSeq = 0

    /**
     * The controller has said what it is holding, and what it is doing about it.
     *
     * Nothing is requested here. The delivery is already on its way - that is
     * the design - so this only records the figures and says, once, when the
     * controller has had to slow its recording down. That last one is worth a
     * log line and a banner rather than silence: a board quietly recording once
     * a minute looks exactly like a board recording once a second until someone
     * counts the points.
     */
    private fun onAlerts(msg: ServerMessage.Alerts) {
        val wasReduced = _ui.value.alerts?.rateReduced ?: false
        _ui.update { it.copy(alerts = msg) }
        if (msg.rateReduced && !wasReduced) {
            android.util.Log.w(
                "MonitorSession",
                "the controller's preserved alert area is " +
                    "${(msg.pressure * 100).toInt()}% full; it is now recording every " +
                    "${msg.rateFloorSeconds.toInt()} s",
            )
        }
    }

    /**
     * What the controller's listing has named, in the order it named it.
     *
     * Kept because it is the only per-session account of the controller there
     * is, and the inventory on the Data tab is a per-session comparison - see
     * [DataInventory]. `storage` gives totals and no names, and a total cannot
     * say *which* sessions are still only on the sensor.
     *
     * Replaced from the first page of a listing and appended to by the pages
     * after it, so a fresh walk never merges with the remains of an older one.
     * A name that has left the ring since would otherwise sit here for ever,
     * counted as a session this device is missing and can never be sent.
     */
    private val sensorListing = LinkedHashMap<String, ServerMessage.FileEntry>()

    /**
     * Rebuild the Data tab's inventory from what the controller has said.
     *
     * Called on the second tick as well as on the two messages that feed it,
     * because the device side of the comparison moves with every reading that
     * arrives and the tense of the sensor side moves with the connection.
     * `receivedRows` is a map lookup per named session - some tens of them - so
     * this is affordable at 1 Hz in a way `coverage()` would not be.
     */
    private fun refreshInventory() {
        val state = _ui.value
        val inventory = DataInventory.of(
            connected = state.connection is ConnectionState.Connected,
            storage = state.storage,
            listing = sensorListing.values.toList(),
            receivedRows = history::receivedRows,
        )
        if (inventory != state.inventory) _ui.update { it.copy(inventory = inventory) }
    }

    /**
     * True once a listing has been walked to its end, so nothing held is unnamed.
     *
     * The fact [SensorRetention.of] needs and the one thing that lets a gap ever
     * be called lost. Maintained here rather than by the sweep, because it is a
     * property of the last listing that arrived and not of who asked for it: the
     * automatic pass reads one page and leaves this false on any board with more
     * sessions than a frame carries, which is the honest answer.
     */
    private var listingIsComplete = false

    private fun onFileList(msg: ServerMessage.FileList) {
        listingPageDueAtMs = 0L
        if (msg.offset == 0) sensorListing.clear()
        for (entry in msg.files) sensorListing[entry.name] = entry
        listingIsComplete = msg.next == null
        refreshInventory()
        // The whole rule, including whether it is bounded at all, is in
        // [backfillPlan] - a copy the user asked for has no cap.
        val plan = backfillPlan(
            files = msg.files,
            forArchive = fetchingAll,
            held = history.coverage(),
            receivedEver = history::receivedRows,
        )
        for (request in plan) backfill.enqueue(request)
        // And the rest of the listing, when there is a rest and this pass is one
        // that has to be complete. See [listingWalkNext].
        walkTheRestOfTheListing(
            listingWalkNext(msg, forArchive = fetchingAll, reconciling = reconciling),
        )
        // A sweep is finished when the listing is: it has everything it can be
        // told about what the sensor still holds, and the comparison is its own.
        if (reconciling && listingPageDueAtMs == 0L) finishReconcile()
        pumpBackfill()
    }

    // --- reconciling this device's record against the sensor's ----------------
    //
    // The addition the captain asked for: "can it be changed so this doesn't
    // happen at all". A backfill that only runs on reconnect can only ever close
    // the holes it happens to be pointed at, and the one that mattered was left
    // behind by exactly that - see [gapFillSince]. So the app now compares its own
    // coverage against the sensor's on a timer as well, and closes what it can
    // while the readings still exist. The cadence, and what a sweep costs when
    // there is nothing to do, are in [RECONCILE_EVERY_MS].
    //
    // Nothing here replaces the reconnect backfill. That still runs first, still
    // runs on every connect and every transport switch, and a sweep deliberately
    // stands aside while it or any other transfer owns the controller's cursor.

    /** When the next sweep is due, or 0 when none is scheduled. */
    private var sweepDueAtMs = 0L

    /** True from a sweep's `list_files` until the comparison has been made. */
    private var reconciling = false

    /** When the sweep in progress asked, so it can be given up on. */
    private var sweepStartedAtMs = 0L

    /**
     * Gaps a fill has already been queued for, by [gapKey].
     *
     * The guard that keeps an automatic sweep from being a load. Its full
     * reasoning is on [gapKey]; the short version is that a gap the sensor cannot
     * actually supply would otherwise be chased for ever. Cleared when the user
     * asks by hand, because that is precisely them saying "try again".
     */
    private val gapsAlreadyAskedFor = HashSet<String>()

    /**
     * Start a sweep if one is due and the link is free.
     *
     * Called from the one-second tick, beside [checkStream] and [checkBackfill].
     * "Free" means no copy, no send and an empty backfill queue: the controller
     * serves `get_file` from a single cursor, and a sweep that queued behind work
     * of unknown length would be asking about a listing that is stale before it
     * arrives. It stands down and asks again rather than waiting.
     */
    private fun checkReconcile() {
        val now = System.currentTimeMillis()
        val step = sweepStep(
            nowMs = now,
            dueAtMs = sweepDueAtMs,
            running = reconciling,
            startedAtMs = sweepStartedAtMs,
            connected = _ui.value.connection is ConnectionState.Connected,
            // An ordinary backfill counts: it is already collecting what a
            // reconnect could see was missing, and a listing taken across it
            // would describe a device that is about to be more complete than it
            // says. The sweep waits for it and then looks at the remainder.
            linkBusy = fetchingAll || backfill.current != null || backfill.size > 0 ||
                transfersAreInFlight(_ui.value),
        )
        when (step) {
            SweepAction.WAIT -> Unit
            SweepAction.STAND_DOWN -> sweepDueAtMs = now + RECONCILE_RETRY_MS
            SweepAction.START -> startReconcile()
            SweepAction.GIVE_UP -> {
                android.util.Log.i("MonitorSession", "the reconciliation sweep was never answered")
                reconciling = false
                _ui.update { it.copy(gaps = it.gaps.copy(sweeping = false)) }
            }
        }
    }

    private fun startReconcile() {
        reconciling = true
        sweepStartedAtMs = System.currentTimeMillis()
        sweepDueAtMs = System.currentTimeMillis() + RECONCILE_EVERY_MS
        _ui.update { it.copy(gaps = it.gaps.copy(sweeping = true)) }
        // On the same deadline every other listing is on. A `list_files` that is
        // never answered would otherwise leave `reconciling` set for ever: no
        // further sweep would run, the button would stay disabled, and the one
        // failure this app leaves no way out of is the operation with no end.
        listingPageDueAtMs = System.currentTimeMillis() + BACKFILL_STALL_MS
        transport?.send(Commands.listFiles())
    }

    /**
     * Compare the two records, say what is missing, and go for what is still there.
     *
     * The order matters. The report is published whether or not anything can be
     * done about it, because a hole that cannot be filled is the thing the user
     * most needs told - "there is nothing you can do about this, it does not
     * exist anymore" is only sayable if it is said. The fill is then queued for
     * the gaps that are still within reach, through the ordinary backfill queue
     * and the ordinary de-duplicating merge, so it can only ever add readings.
     */
    private fun finishReconcile() {
        if (!reconciling) return
        reconciling = false
        val gaps = currentGaps()
        // A gap that has closed - or that retention has carried off the back of
        // the record - is forgotten, which is both what bounds this set and what
        // lets a hole that genuinely reopens be gone for again.
        gapsAlreadyAskedFor.retainAll(gaps.mapTo(HashSet()) { gapKey(it) })
        val fresh = gaps.filter { it.fillableFrom != null && gapKey(it) !in gapsAlreadyAskedFor }
        val plan = gapFillPlan(
            gaps = fresh,
            files = sensorListing.values.toList(),
            held = history.coverage(),
        )
        for (gap in fresh) gapsAlreadyAskedFor.add(gapKey(gap))
        for (request in plan) backfill.enqueue(request)
        if (plan.isNotEmpty()) {
            android.util.Log.i(
                "MonitorSession",
                "reconciliation found ${gaps.size} gap(s); asking for ${plan.size} session(s)",
            )
        }
        _ui.update {
            it.copy(
                gaps = RecordGapReport(
                    gaps = gaps.takeLast(RecordGaps.MAX_REPORTED),
                    sweeping = false,
                    sweptAtMs = System.currentTimeMillis(),
                    fetching = plan.size,
                ),
            )
        }
    }

    /**
     * The holes in what this device holds, judged against what the sensor said.
     *
     * Recomputed rather than maintained, and only at the two moments the answer
     * can have changed usefully: a sweep, and a backfill queue running dry. It
     * walks every placed reading - ~170k of them over 48 hours - which is nothing
     * twice an hour and far too much once a second, which is why it is not on the
     * tick with [refreshInventory].
     */
    private fun currentGaps() = RecordGaps.of(
        epochs = history.placedEpochs(),
        cadenceSeconds = RecordGaps.cadenceSeconds(_ui.value.profile),
        retention = SensorRetention.of(sensorListing.values.toList(), listingIsComplete),
        covered = history.importedRanges(),
    )

    /**
     * Look for gaps now, and fill what can be filled.
     *
     * The manual half of the feature. A sweep on a timer is the better answer to
     * "so this doesn't happen at all", but somebody looking at a hole in their
     * chart wants to act on it rather than wait up to half an hour, and the two
     * are the same operation - so this schedules the sweep rather than opening a
     * second path to the controller that could disagree with it.
     *
     * Clearing [gapsAlreadyAskedFor] is the part that is only right here: an
     * automatic sweep must not chase a gap the sensor will not actually give up,
     * and a person pressing a button is saying to try it anyway.
     */
    fun findAndFillGaps() {
        scope.launch {
            gapsAlreadyAskedFor.clear()
            sweepDueAtMs = System.currentTimeMillis()
            // So the row says something immediately, from whatever is already
            // known, rather than looking inert until the sensor answers. The
            // *sweeping* flag is deliberately left to [startReconcile]: set here,
            // a press that finds the link busy would leave the button disabled
            // with nothing running that could ever re-enable it.
            _ui.update { it.copy(gaps = it.gaps.copy(gaps = currentGaps())) }
            checkReconcile()
        }
    }

    /**
     * Ask for the next page of a listing too long for one frame.
     *
     * Whether there is a next page to ask for, and whether this pass is one that
     * wants it, is [listingWalkNext]. This is what happens once it has said yes.
     */
    private fun walkTheRestOfTheListing(next: Int?) {
        if (next == null) return
        transport?.send(Commands.listFiles(next))
        // A page that is never answered must not leave the copy waiting for ever:
        // that is what a transfer with no deadline looks like from the outside,
        // and this app has been there. [checkBackfill] gives up on it.
        listingPageDueAtMs = System.currentTimeMillis() + BACKFILL_STALL_MS
    }

    /**
     * When the page of the listing being waited for stops being worth waiting
     * for, or 0 when none is outstanding.
     */
    private var listingPageDueAtMs = 0L

    /** Send the next request, if nothing is in flight. */
    private fun pumpBackfill() {
        val next = backfill.start(System.currentTimeMillis()) ?: run {
            // Not while a page of the listing is still coming: the sessions it
            // names have not been queued yet, and finishing here would close the
            // archive on a copy that is short and call it a backup.
            if (fetchingAll && backfill.current == null && listingPageDueAtMs == 0L) {
                finishFetchAll()
            }
            if (backfill.current == null) reportWhatTheFillClosed()
            return
        }
        transport?.send(Commands.getFile(next.name, next.since))
    }

    /**
     * Say what a fill actually closed, once every session it asked for has come.
     *
     * Only after a sweep queued something - `fetching` is what says so - because
     * this walks the whole record and the answer cannot have changed otherwise.
     * Without it a fill would land silently and the row would go on naming holes
     * that are no longer there until the next sweep half an hour later, which
     * reads as the button having done nothing.
     */
    private fun reportWhatTheFillClosed() {
        if (reconciling || _ui.value.gaps.fetching == 0) return
        val gaps = currentGaps()
        gapsAlreadyAskedFor.retainAll(gaps.mapTo(HashSet()) { gapKey(it) })
        _ui.update {
            it.copy(gaps = it.gaps.copy(gaps = gaps.takeLast(RecordGaps.MAX_REPORTED), fetching = 0))
        }
    }

    private fun onBackfillFinished(name: String) {
        val done = backfill.finish(name)
        // Only sessions the copy asked for count towards its progress: a narrow
        // request that was already running when it started is not part of it.
        if (done != null && (!fetchingAll || done.forArchive)) fetchedSessions++
        pumpBackfill()
    }

    /**
     * Give up on a request the controller has stopped answering.
     *
     * Called from the one-second tick. This is what an unanswered `get_file` used
     * to be resolved by an `error` for, which meant guessing that any error at all
     * was about the transfer, and cancelling a perfectly healthy one whenever it
     * was not.
     */
    private fun checkBackfill() {
        val now = System.currentTimeMillis()
        // A page of the listing that never came back. The copy is short by
        // whatever that page would have named and says so in the same red line
        // it uses for every other shortfall, which is better than waiting on a
        // controller that has stopped answering - an operation with no end is
        // the one failure this app leaves a user no way out of.
        if (listingPageDueAtMs != 0L && now >= listingPageDueAtMs) {
            listingPageDueAtMs = 0L
            android.util.Log.i(
                "MonitorSession",
                "no answer to list_files; finishing with whatever of the listing arrived",
            )
            // A sweep whose walk was cut short still reports, on a listing that
            // is short - so [listingIsComplete] is false and every hole below
            // the oldest named session comes back as not known rather than lost.
            // That is the point of having the third answer at all.
            if (reconciling) finishReconcile()
            pumpBackfill()
        }
        val given = backfill.giveUpIfStalled(now) ?: return
        android.util.Log.i("MonitorSession", "no answer to get_file ${given.name}; moving on")
        pumpBackfill()
    }

    // --- noticing that the stream has died -----------------------------------
    //
    // The rule, and why a transfer is judged by its own progress rather than by
    // readings that the controller is deliberately not sending, is in
    // [streamIsDead].

    @Volatile private var lastReadingAtMs = 0L

    /**
     * When a transfer frame was last accepted, or 0 when no transfer is putting
     * frames on the link. See [streamIsDead], which this selects the rule for.
     */
    @Volatile private var lastTransferProgressAtMs = 0L

    /**
     * Called once a second. If the link has stopped proving it is alive, say so
     * and force the connection down so the reconnect loop can rebuild it.
     *
     * Closing the socket is what actually unblocks the reader; without it the
     * loop below never gets a turn. Reconnecting then runs the ordinary hello ->
     * backfill path, so whatever the board recorded during the silence is
     * collected rather than lost. Nothing is fabricated and nothing is
     * interpolated: the gap stays a gap until the board hands over the readings
     * that fill it.
     */
    private fun checkStream() {
        if (currentMode == null || currentMode == ConnectionMode.NONE) return
        if (_ui.value.connection !is ConnectionState.Connected) return
        val now = System.currentTimeMillis()
        val awaitingAnswerSince = (_ui.value.transfer as? SessionTransfer.Awaiting)?.sentAtMs ?: 0L
        if (!streamIsDead(now, lastReadingAtMs, lastTransferProgressAtMs, awaitingAnswerSince)) {
            lastReadingAtMs = readingClockAfterTick(now, lastReadingAtMs, awaitingAnswerSince)
            return
        }

        if (!_ui.value.streamStalled) {
            _ui.update { it.copy(streamStalled = true) }
        }
        // Re-arm before tearing down, so a slow reconnect does not retrigger
        // this every second while it is already in progress.
        lastReadingAtMs = now
        lastTransferProgressAtMs = 0L
        transport?.stop()
    }

    // --- watch relay (phone -> Galaxy Watch 7 over the Wearable Data Layer) ----
    @Volatile private var lastRelayMs = 0L
    private var lastRelayed: Map<String, Double> = emptyMap()

    private fun updateWatch(edit: (WatchLinkFacts) -> WatchLinkFacts) {
        _ui.update { it.copy(watch = edit(it.watch)) }
    }

    /**
     * Ask the Data Layer what is on the other end, and publish the answer.
     *
     * Cheap enough to run every time the Watch tab is opened, which is what the
     * tab does: an indicator that was right when the menu was last closed is not
     * an indicator.
     */
    fun refreshWatchLink() {
        val ctx = appContext ?: return
        scope.launch {
            updateWatch { it.copy(probing = true) }
            val found = WearLink.probe(ctx)
            updateWatch {
                it.copy(
                    probing = false,
                    probedAtMs = System.currentTimeMillis(),
                    probeError = found.error,
                    nodes = found.nodes,
                )
            }
        }
    }

    /**
     * Send the current reading to the watch right now and report what happened.
     *
     * The one control on the tab that produces evidence rather than an opinion.
     * Everything else there is the phone describing its own end of a one-way
     * link; this is the only thing that asks the far end to answer.
     */
    fun sendTestReadingToWatch() {
        val ctx = appContext ?: return
        scope.launch {
            updateWatch { it.copy(sending = true, lastSendResult = null) }
            val state = _ui.value
            val nowMs = System.currentTimeMillis()
            val attempt = WearLink.sendTest(
                context = ctx,
                values = state.latest,
                timestamp = state.latestTimestamp,
                spark = buildSpark(),
                relaySeconds = state.config.watchRelaySeconds,
                nowMs = nowMs,
            )
            val delivered = attempt.reached.isNotEmpty()
            val nodes = attempt.probe.nodes
            val outcome = WatchLink.sendOutcome(
                reached = attempt.reached,
                missed = attempt.missed,
                hadReading = state.latest.isNotEmpty(),
                // Positively answered "no", never "could not ask". A node whose
                // capability lookup failed carries `null` and must not be
                // reported as a watch with nothing on it - see [WatchNode.hasApp].
                appMissing = nodes.any { n -> n.hasApp == false } &&
                    nodes.none { n -> n.hasApp == true },
                failure = attempt.failure,
            )
            // A send is also a probe - it asked the same questions on the way
            // past - so the indicator above the button is refreshed from that
            // same trip rather than left describing the world before the press.
            updateWatch {
                it.copy(
                    sending = false,
                    probedAtMs = System.currentTimeMillis(),
                    probeError = attempt.probe.error,
                    nodes = nodes,
                    lastSentMs = if (delivered && state.latest.isNotEmpty()) nowMs else it.lastSentMs,
                    lastDeliveredMs = if (delivered) nowMs else it.lastDeliveredMs,
                    lastSendResult = outcome,
                )
            }
        }
    }

    /**
     * Rate-limited push of the latest reading + a 1h PM2.5 sparkline to the watch.
     * Runs on the model dispatcher (called from [onMessage]) so [history] access is
     * safe.
     *
     * [RelayPolicy] decides whether a push is warranted; see it for why.
     */
    private fun maybeRelayToWatch(values: Map<String, Double>, timestamp: String?) {
        val ctx = appContext ?: return
        val nowMs = System.currentTimeMillis()
        val warranted = RelayPolicy.shouldPublish(
            nowMs = nowMs,
            lastPublishMs = lastRelayMs,
            minGapMs = _ui.value.config.watchRelaySeconds * 1000L,
            heartbeatMs = RelayPolicy.HEARTBEAT_MS,
            lastPublished = lastRelayed,
            current = values,
        )
        if (!warranted) return

        lastRelayMs = nowMs
        lastRelayed = Wire.PM_FIELDS.associateWith { values[it] ?: Double.NaN }
        WearRelay.publish(
            ctx, values, timestamp, buildSpark(), nowMs,
            relaySeconds = _ui.value.config.watchRelaySeconds,
        )
        // Published to the screen as well as to the watch. Until this existed
        // the relay's whole history was one private field, so the app could not
        // answer the first question anybody asks of it - when did a reading last
        // go out - and the Watch tab could only shrug.
        updateWatch { it.copy(lastSentMs = nowMs) }
    }

    /** Last-hour PM2.5 values (oldest -> newest), evenly decimated to at most
     *  [SPARK_MAX_POINTS] so the Data Layer payload stays tiny. */
    private fun buildSpark(): FloatArray {
        val cutoff = currentNow() - SPARK_WINDOW_SECONDS
        val pm = ArrayList<Float>()
        for (s in history.snapshot()) {
            // "The last hour" is a question about when, so a reading with no when
            // is not in it. Written as an explicit null check because the natural
            // shorthand quietly says the opposite: an unknown time is not less
            // than the cutoff, so it would have been let through.
            val at = s.epoch ?: continue
            if (at < cutoff) continue
            val v = s.values["pm2_5"] ?: continue
            pm.add(v.toFloat())
        }
        if (pm.size <= SPARK_MAX_POINTS) return pm.toFloatArray()
        val step = (pm.size - 1).toDouble() / (SPARK_MAX_POINTS - 1)
        return FloatArray(SPARK_MAX_POINTS) { pm[Math.round(it * step).toInt()] }
    }

    /** Plot width in px, reported by the chart. Drawing more points than there are
     *  pixels is invisible work, so this caps what [recompute] extracts. */
    @Volatile private var chartWidthPx = 0

    fun setChartWidthPx(width: Int) { chartWidthPx = width }

    private val pointBudget: Int
        get() = chartWidthPx.takeIf { it > 0 }?.coerceIn(MIN_POINTS, MAX_POINTS) ?: DEFAULT_POINTS

    private fun currentNow(): Double {
        val wallNow = System.currentTimeMillis() / 1000.0
        return maxOf(wallNow, history.latestEpoch() ?: wallNow)
    }

    /** Bumped once a second so a countdown banner re-renders while the sensor
     *  sleeps and no readings are arriving to drive recomposition. */
    private fun tickClock() {
        if (_ui.value.phaseEndsAtMs != null) {
            _ui.update { it.copy(clockTick = it.clockTick + 1) }
        }
    }

    private fun recompute() {
        // "all" tracks the *current* session dynamically: re-derive its origin from
        // the earliest stable reading each tick, so it self-corrects after a Restart
        // (which clears history and starts a new session) with no need to re-tap.
        if (chart.viewMode() == ViewMode.ALL) {
            chart.applyAll(history.firstStableEpoch() ?: currentNow(), currentNow())
        }
        // Whether the fit reaches for the alert line is a setting, and this is
        // the one place the chart hears about it - every mutation funnels
        // through here before the next frame, config edits included.
        chart.alertY = _ui.value.config.autoscaleAlertLevel
        val data = chart.compute(
            history, currentNow(), maxPoints = pointBudget,
            excludeFlagged = _ui.value.config.excludeFlagged,
            // Autoscale fits the curves on screen, so it has to know which they
            // are. Switching a series off changes the axis, which is the point.
            visible = _ui.value.config.visiblePm,
            // A log axis is decaded by the renderer, so the model must not also
            // snap it to linear steps on the way.
            logY = _ui.value.config.logY,
        )
        // The selection's statistics, refreshed on the same tick as the plot so
        // backfill landing inside the stretch reaches the numbers too - and
        // always from the raw history, never from the drawn points above.
        val region = statsRegion?.let {
            buildRegionReport(
                history, it.first, it.second, _ui.value.config,
                canReturn = regionReturn != null,
            )
        }
        _ui.update {
            it.copy(chart = data, windowSeconds = chart.windowSeconds, region = region)
        }
    }

    // --- copying recorded sessions to this device ----------------------------
    //
    // Chunks are appended to a temp file as they arrive rather than accumulated
    // in memory: a sensor left running for weeks holds hundreds of megabytes of
    // sessions, and buffering that on the heap would be a needless way to fail.

    private var backupFile: java.io.File? = null
    private var backupSink: java.io.OutputStream? = null
    private var backupExpectedSeq = 0

    /**
     * What the controller called the archive. Shown and logged, never obeyed.
     *
     * This used to be the file name `Exports.writeArchive` was given, which
     * meant a controller chose the name of a file written into the user's
     * Downloads folder - and on Android 8, or whenever the MediaStore insert
     * falls through to `File(dir, name)`, chose the directory too. Nothing about
     * a controller is authenticated, so "the controller" means anyone who can
     * answer a `hello` before the real board does. The name the app writes is
     * now its own; this is kept because discarding what the peer said silently
     * would be worse than recording it.
     */
    private var peerBackupName: String = ""

    /**
     * Pull every session the controller still holds onto this device.
     *
     * This used to send `backup`, which asks the controller to build a zip of
     * CSVs. A Raspberry Pi can do that; an ESP32 cannot - it has no filesystem,
     * no compressor and nowhere to put an archive - and it answered by saying
     * it had no stored sessions and that the phone was the record. That is the
     * wrong way round: the board is the record, and this is how the phone takes
     * a copy of it.
     *
     * So it now asks what the controller holds and streams each session across
     * in turn, which is the same path a reconnect uses - the difference is only
     * that this one asks for every session from the beginning rather than from
     * where the plot left off, because the point of it is to be complete.
     */
    fun copySessionsToThisDevice() {
        if (fetchingAll) return
        val ctx = appContext ?: return
        fetchingAll = true
        fetchedSessions = 0
        backfillRows = 0
        archivedRows = 0
        // Whatever is in flight was asked for to fill the plot, not the archive.
        // It is left to finish - the controller's cursor cannot be recalled - and
        // its rows stay out of the file; the full copy of that session is queued
        // behind it by `file_list`.
        backfill.clearWaiting()
        // A walk left over from an earlier copy would hold this one open.
        listingPageDueAtMs = 0L
        try {
            closeArchiveWriter()
            val file = java.io.File.createTempFile("sps30-export", ".zip", ctx.cacheDir)
            archiveFile = file
            archiveWriter = SessionArchive.Writer(file.outputStream())
        } catch (e: Exception) {
            fetchingAll = false
            failBackup("could not open a file to write into: ${e.message}")
            return
        }
        _ui.update { it.copy(backup = SessionBackup.Requested) }
        transport?.send(Commands.listFiles())
    }

    /**
     * Start a copy because the controller says its store is filling.
     *
     * The whole rule, and why it is keyed on the crossing rather than on a
     * number, is in [autoExportStep] - which is where it can be tested without a
     * board. This is only the part that has to touch the model.
     */
    private fun autoExportIfNearlyFull(storage: ServerMessage.Storage) {
        val step = autoExportStep(
            state = autoExport,
            warn = storage.warn,
            readings = storage.readings,
            copyInFlight = fetchingAll,
            nowMs = System.currentTimeMillis(),
        )
        autoExport = step.state
        if (!step.startCopy) return
        android.util.Log.i(
            "MonitorSession",
            "store is ${(storage.fraction * 100).toInt()}% full; copying everything here",
        )
        copySessionsToThisDevice()
    }

    private var autoExport = AutoExportState()

    private var archiveWriter: SessionArchive.Writer? = null
    private var archiveFile: java.io.File? = null

    /**
     * Rows written into the archive, as distinct from rows added to the plot.
     *
     * The two are not the same number and the difference is not small: the plot
     * de-duplicates, so exporting a second time adds nothing to it while writing
     * every row to the file. Reporting the plot's figure during an export showed
     * "0 readings so far" while the archive filled up perfectly - a progress line
     * that says nothing is happening during the one operation a user most needs
     * to believe is happening.
     */
    private var archivedRows = 0

    /**
     * Put one `file_data` frame's rows into the archive being written.
     *
     * The cells are re-ordered onto the session's own column list here rather
     * than trusted as they arrive, because `file_data` names its own columns and
     * a controller that ever reorders them would otherwise write a file whose
     * header and rows disagree - the one corruption a CSV cannot survive.
     *
     * That list is [Wire.CSV_COLUMNS] for a dated session and the two-wider one
     * for a session recorded with no clock, matching what
     * [SessionArchive.Writer.begin] puts at the top of the entry. Reducing a
     * clockless session onto the narrow list is what used to throw the board's
     * own clock away here, leaving rows with no instant and no position: not a
     * session that could not be dated, but one that could never be dated again.
     */
    private fun archiveRows(msg: ServerMessage.FileData) {
        val writer = archiveWriter ?: return
        try {
            // A name the format will not write is a name that must not become a
            // zip entry - see [SessionArchive.Writer.begin]. Its rows are left
            // out rather than filed under it, and `missing` in [finishFetchAll]
            // is what tells the user the copy is short.
            if (!writer.begin(msg.name)) {
                android.util.Log.w("MonitorSession", "refused a session named \"${msg.name}\"")
                return
            }
            val order = Wire.csvColumnsFor(msg.name).map { msg.columns.indexOf(it) }
            for (row in msg.rows) {
                writer.row(order.map { c -> if (c in row.indices) row[c] else "" })
                archivedRows++
            }
        } catch (e: Exception) {
            closeArchiveWriter()
            failBackup("could not write the archive: ${e.message}")
        }
    }

    private fun closeArchiveWriter() {
        runCatching { archiveWriter?.close() }
        archiveWriter = null
        archiveFile?.delete()
        archiveFile = null
    }

    private fun publishFetchProgress() {
        _ui.update {
            it.copy(
                backup = SessionBackup.Receiving(
                    // There is no byte total to count towards: the controller
                    // streams rows rather than sending a sized archive. The bar
                    // therefore measures sessions, which is the honest unit, and
                    // the row count says how much has actually arrived.
                    received = fetchedSessions.toLong(),
                    total = (fetchedSessions + backfill.size +
                        if (backfill.current != null) 1 else 0).toLong(),
                    sessions = fetchedSessions,
                    // What has gone into the file, which is what this operation
                    // is about. `backfillRows` counts what the plot took, and
                    // the plot takes nothing on a second export.
                    rows = archivedRows.toLong(),
                    // And that difference is now said out loud rather than left
                    // for the reader to infer from a number that will not move.
                    // A copy of sessions this device already holds reads every
                    // row and adds none, and a progress line that reports only
                    // the first of those is a climbing figure under a panel
                    // correctly saying nothing is missing. See [RecordWork].
                    newHere = backfillRows.toLong(),
                ),
            )
        }
    }

    /**
     * Close the archive and put it where the user can reach and share it.
     *
     * Until this point an export was a copy into the plot, which is not a
     * backup: it is averaged, bounded, and gone when the app's data is cleared.
     * The Raspberry Pi's own `backup` always produced a real file, and a
     * controller whose entire archive is a flash partition needs one more, not
     * less.
     */
    private fun finishFetchAll() {
        if (!fetchingAll) return
        fetchingAll = false
        listingPageDueAtMs = 0L
        val ctx = appContext
        val writer = archiveWriter
        val file = archiveFile

        if (fetchedSessions == 0 || ctx == null || writer == null || file == null) {
            closeArchiveWriter()
            _ui.update {
                it.copy(backup = SessionBackup.Failed("the controller is not holding any sessions yet"))
            }
            return
        }

        try {
            writer.close()
            archiveWriter = null
            val location = com.sensirion.sps30.config.Exports.writeArchive(
                ctx,
                file,
                com.sensirion.sps30.config.Exports.archiveName("export"),
            )
            val entries = writer.sessions
            val captured = entries.count { e -> e.rows > 0 }
            // The controller says how many sessions it holds. If the archive has
            // fewer, the difference is data this device does NOT have, and the
            // user has to be told rather than shown a confident total.
            val claimed = _ui.value.storage?.sessions ?: 0
            _ui.update {
                it.copy(
                    backup = SessionBackup.Saved(
                        location = location,
                        bytes = file.length(),
                        sessions = captured,
                        rows = entries.sumOf { e -> e.rows }.toLong(),
                        missing = (claimed - captured).coerceAtLeast(0),
                    ),
                    lastExport = LastExport(location, entries, System.currentTimeMillis()),
                )
            }
        } catch (e: Exception) {
            _ui.update { it.copy(backup = SessionBackup.Failed("could not save the archive: ${e.message}")) }
        } finally {
            file.delete()
            archiveFile = null
            archiveWriter = null
        }
    }

    fun dismissBackupResult() {
        val current = _ui.value.backup
        if (current is SessionBackup.Saved || current is SessionBackup.Failed) {
            _ui.update { it.copy(backup = SessionBackup.Idle) }
        }
    }

    /**
     * The legacy Pi archive stream, and why it is now answered only when asked
     * for.
     *
     * `backup_meta` used to be handled unconditionally, and it is what opens the
     * file the chunks are written into. So an impostor controller could, with no
     * user interaction at all, put a file of its choosing into the user's
     * Downloads folder the moment the app attached to it. Requiring a copy the
     * user actually started closes that without changing the protocol: a
     * controller that was never asked for an archive is not sending one.
     */
    private fun backupIsInFlight(): Boolean =
        _ui.value.backup.let { it is SessionBackup.Requested || it is SessionBackup.Receiving }

    private fun onBackupMeta(msg: ServerMessage.BackupMeta) {
        val ctx = appContext ?: return
        if (_ui.value.backup !is SessionBackup.Requested) {
            android.util.Log.w("MonitorSession", "ignoring an archive nothing asked for")
            return
        }
        try {
            closeBackupSink()
            val file = java.io.File.createTempFile("sps30-backup", ".zip", ctx.cacheDir)
            backupFile = file
            backupSink = java.io.BufferedOutputStream(file.outputStream(), 64 * 1024)
            peerBackupName = msg.name
            backupExpectedSeq = 0
            _ui.update {
                it.copy(
                    // Bytes, not sessions - this controller sends one sized
                    // archive rather than streaming its sessions in turn. Said
                    // in the value so the row cannot print a byte count with the
                    // word "session" in front of it. See [SessionBackup.Receiving].
                    backup = SessionBackup.Receiving(
                        received = 0,
                        total = msg.bytes,
                        sessions = msg.sessions,
                        rows = msg.rows,
                        receivedAreSessions = false,
                    ),
                )
            }
        } catch (e: Exception) {
            failBackup("could not open a file to receive into: ${e.message}")
        }
    }

    private fun onBackupChunk(msg: ServerMessage.BackupChunk) {
        // `backupSink` is only ever opened by [onBackupMeta], so gating the meta
        // already stops a chunk arriving out of nowhere. Both are checked: they
        // are the two halves of writing an unrequested file to the device, and a
        // later edit that loosens one should still have to get past the other.
        if (!backupIsInFlight()) return
        val state = _ui.value.backup as? SessionBackup.Receiving ?: return
        val sink = backupSink ?: return
        // A gap means a chunk was lost in transit. Better to fail loudly than to
        // hand someone a corrupt archive they believe is a backup.
        if (msg.seq != backupExpectedSeq) {
            failBackup("the copy lost data (expected chunk ${backupExpectedSeq}, got ${msg.seq})")
            return
        }
        backupExpectedSeq++
        try {
            var received = state.received
            if (msg.data.isNotEmpty()) {
                val bytes = android.util.Base64.decode(msg.data, android.util.Base64.DEFAULT)
                sink.write(bytes)
                received += bytes.size
                _ui.update { it.copy(backup = state.copy(received = received)) }
            }
            if (msg.done) finishBackup(state.copy(received = received))
        } catch (e: Exception) {
            failBackup("could not write the archive: ${e.message}")
        }
    }

    private fun finishBackup(state: SessionBackup.Receiving) {
        val ctx = appContext ?: return
        val file = backupFile ?: return
        try {
            backupSink?.flush()
            backupSink?.close()
            backupSink = null
            if (state.total > 0 && file.length() != state.total) {
                failBackup("archive is incomplete (${file.length()} of ${state.total} bytes)")
                return
            }
            // The name is this app's, not the controller's. See [peerBackupName].
            if (peerBackupName.isNotEmpty()) {
                android.util.Log.i(
                    "MonitorSession",
                    "controller called the archive \"$peerBackupName\"; saving under our own name",
                )
            }
            val location = com.sensirion.sps30.config.Exports.writeArchive(
                ctx,
                file,
                com.sensirion.sps30.config.Exports.archiveName("backup"),
            )
            _ui.update {
                it.copy(
                    backup = SessionBackup.Saved(
                        location = location,
                        bytes = file.length(),
                        sessions = state.sessions,
                        rows = state.rows,
                    ),
                )
            }
        } catch (e: Exception) {
            failBackup("could not save the archive: ${e.message}")
        } finally {
            file.delete()
            backupFile = null
        }
    }

    private fun failBackup(reason: String) {
        closeBackupSink()
        _ui.update { it.copy(backup = SessionBackup.Failed(reason)) }
    }

    private fun closeBackupSink() {
        runCatching { backupSink?.close() }
        backupSink = null
        backupFile?.delete()
        backupFile = null
    }


    // --- sending sessions back to the controller ------------------------------
    //
    // The one direction that can do harm, so the order is: read the file
    // through, decide whether it is intact, and only then send a byte. A file
    // that fails the check is reported and NOT sent, which is what makes "a
    // corrupt file changes nothing" a fact about the code rather than a hope.
    //
    // What happens on the controller is a merge that cannot remove a reading -
    // see `store_merge.h`. This end's job is not to send anything it has not
    // read, and to report honestly what came back.

    /** The transfer in flight. Named apart from the stored-sessions reader,
     *  which is a different job with a different meaning of "import". */
    @Volatile private var transferJob: Job? = null

    /**
     * Send a previously exported file back to the controller.
     *
     * [displayName] is used to tell an archive from a single session CSV and, for
     * a lone CSV, to name the session it belongs to - so it must be the file's
     * real name rather than a label.
     */
    fun importArchive(uri: android.net.Uri, displayName: String) {
        val ctx = appContext ?: return
        if (transferJob?.isActive == true) return
        val open = { ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("could not open that file") }

        transferJob = scope.launch {
            _ui.update { it.copy(transfer = SessionTransfer.Checking(displayName)) }

            // Pass one: read it all, send none of it.
            val survey = withContext(Dispatchers.IO) {
                SessionArchive.survey(open, displayName)
            }
            val checked = survey.getOrElse { e ->
                val reason = (e as? SessionArchive.ArchiveRejected)?.rejection?.reason
                    ?: e.message ?: "that file could not be read"
                android.util.Log.w(TAG_TRANSFER, "$displayName was not sent: $reason")
                _ui.update { it.copy(transfer = SessionTransfer.Rejected(displayName, reason)) }
                return@launch
            }

            val link = transport
            if (link == null) {
                android.util.Log.w(
                    TAG_TRANSFER,
                    "$displayName was not sent: there is no connection to send it over",
                )
                _ui.update {
                    it.copy(
                        transfer = SessionTransfer.Rejected(
                            displayName,
                            "not connected to a controller; nothing was sent",
                        ),
                    )
                }
                return@launch
            }

            importTotalRows = checked.rows
            importSentRows = 0
            importSessionsDone = 0
            importSessionsTotal = checked.sessions.count { it.rows > 0 }
            importFile = displayName
            // The app's half of "a failed send leaves a trace". Nothing here
            // used to log anything at all, in either direction, so a send that
            // did not finish left the device with no record that it had been
            // attempted. The board's half is `handle_put_file` in net_server.c.
            android.util.Log.i(
                TAG_TRANSFER,
                "sending $displayName: $importSessionsTotal session(s), " +
                    "$importTotalRows readings",
            )
            _ui.update {
                it.copy(
                    transfer = SessionTransfer.Sending(
                        file = displayName,
                        session = "",
                        sessionsDone = 0,
                        sessionsTotal = importSessionsTotal,
                        rowsSent = 0,
                        rowsTotal = importTotalRows,
                    ),
                )
            }

            // Pass two: send it. Reading happens on IO; the frames go out with
            // `sendAwait`, which waits for room rather than dropping - a dropped
            // frame here is a hole in the data being restored.
            // From here the link is carrying frames, and the controller stops
            // volunteering readings for as long as it is. That makes progress
            // here - not readings - the proof the connection is alive; see
            // [streamIsDead], which this hands its evidence to.
            // What the frames are is [ArchiveSend]; this is only what to do with
            // each one.
            lastTransferProgressAtMs = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    ArchiveSend.eachChunk(open, displayName) { chunk ->
                        // `sendAwait`, not `send`: the command queue is bounded
                        // and drops when full, which is right for a button press
                        // and catastrophic for a row of data.
                        //
                        // The last frame waits for the socket rather than the
                        // queue. It is the one the controller answers, so the
                        // instant it goes is the instant every deadline below
                        // starts counting from, and a queue can be a quarter of
                        // a minute of the controller's work ahead of the wire.
                        // See [Transport.sendAndAwaitWrite].
                        if (chunk.done) {
                            link.sendAndAwaitWrite(chunk.line)
                        } else {
                            link.sendAwait(chunk.line)
                        }
                        // It returned, so the queue took it, which it only does
                        // as fast as the socket drains and the controller reads.
                        lastTransferProgressAtMs = System.currentTimeMillis()
                        importSentRows += chunk.rows
                        importSessionsDone = chunk.sessionsDone
                        publishImportProgress(chunk.session)
                    }
                    importSessionsDone = importSessionsTotal
                }
                // Sending is over; the transfer is not. See [SessionTransfer.Awaiting]
                // - the controller answers once, and until it does nobody knows
                // what became of the data. Leaving the progress line on screen
                // here is what made a lost answer indistinguishable from a
                // transfer still in progress, for ever.
                android.util.Log.i(
                    TAG_TRANSFER,
                    "$displayName: $importSentRows readings sent, waiting for the sensor's " +
                        "answer",
                )
                awaitAnswer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: "sending stopped part way"
                android.util.Log.w(
                    TAG_TRANSFER,
                    "$displayName failed after $importSentRows of $importTotalRows readings: " +
                        reason,
                )
                _ui.update { it.copy(transfer = SessionTransfer.Failed(displayName, reason)) }
            } finally {
                // The link goes back to being judged by its readings. They have
                // not been arriving for the length of the transfer, by the
                // controller's own choice, so the silence starts counting from
                // here - measuring it from the last reading before the transfer
                // would fire the watchdog on the very next tick and drop the
                // connection a completed transfer had just proved was healthy.
                // In a `finally` because every way out of a transfer - the last
                // frame, a failure, or the cancel in [abandonLinkBoundWork] -
                // hands the link back the same way. When what follows is a wait
                // for the answer, the silence has not ended either, and
                // [checkStream] holds this clock for the length of that wait.
                lastReadingAtMs = System.currentTimeMillis()
                lastTransferProgressAtMs = 0L
            }
        }
    }

    private var importTotalRows = 0
    private var importSentRows = 0
    private var importSessionsDone = 0
    private var importSessionsTotal = 0
    private var importFile = ""

    /** Where a transfer's own account of itself goes. */
    private const val TAG_TRANSFER = "MonitorSession"

    private fun publishImportProgress(session: String) {
        _ui.update { state ->
            // Never over anything but a send in progress. An answer that arrives
            // while frames are still going out is the controller closing the
            // transfer out early, and it is the only one it will ever send for
            // the readings it covers - so a progress line written on top of it
            // destroys the account of them. [transferAfterAnswer] folds such an
            // answer into the `Sending` state rather than ending the transfer,
            // and this carries it forward rather than resetting it to zero.
            val landed = when (val t = state.transfer) {
                is SessionTransfer.Sending -> t.landedSoFar
                is SessionTransfer.Checking -> 0
                else -> return@update state
            }
            state.copy(
                transfer = SessionTransfer.Sending(
                    file = importFile,
                    session = session,
                    sessionsDone = importSessionsDone,
                    sessionsTotal = importSessionsTotal,
                    rowsSent = importSentRows,
                    rowsTotal = importTotalRows,
                    landedSoFar = landed,
                ),
            )
        }
    }

    /**
     * The last frame has gone. Say so, and start the clock on the answer.
     *
     * Not a suspend wait: the answer arrives on the message collector, and the
     * deadline is [checkTransferAnswer] on the one-second tick, which is where
     * every other deadline in this file lives.
     */
    private fun awaitAnswer() {
        _ui.update { state ->
            // A controller that answered the whole thing before the last frame
            // was even acknowledged has already ended this; do not reopen it.
            val sending = state.transfer as? SessionTransfer.Sending ?: return@update state
            state.copy(
                transfer = SessionTransfer.Awaiting(
                    file = importFile,
                    rowsSent = importSentRows,
                    sentAtMs = System.currentTimeMillis(),
                    landedSoFar = sending.landedSoFar,
                ),
            )
        }
    }

    /**
     * The controller's account of a transfer, or of one part of one.
     *
     * The rule is [transferAfterAnswer]; this is the logging and the follow-up
     * around it.
     */
    private fun onImportResult(msg: ServerMessage.ImportResult) {
        android.util.Log.i(
            TAG_TRANSFER,
            "sensor answered ${msg.name}: ${msg.state} - ${msg.added} added, " +
                "${msg.duplicate} already there, ${msg.rejected} not placed, " +
                "${msg.sessions} session(s): ${msg.message}",
        )
        if (_ui.value.transfer is SessionTransfer.Sending) {
            android.util.Log.w(
                TAG_TRANSFER,
                "the sensor closed $importFile out after $importSentRows of $importTotalRows " +
                    "readings and answered that much of it; the rest is still going out and " +
                    "will be answered separately",
            )
        }
        _ui.update { it.copy(transfer = transferAfterAnswer(it.transfer, importFile, msg)) }
        // What the controller now holds has changed, so what this device shows of
        // it is out of date. Ask again rather than assume.
        transport?.send(Commands.listFiles())
    }

    /**
     * Give up on an answer that is never coming, and say what was sent.
     *
     * Called from the one-second tick, beside [checkStream] and [checkBackfill],
     * and it is the same shape as both: a bulk operation gets a deadline, and
     * missing it produces an account rather than a wait. See
     * [TRANSFER_ANSWER_MS] for why the bound sits past the controller's own.
     */
    private fun checkTransferAnswer() {
        val awaiting = _ui.value.transfer as? SessionTransfer.Awaiting ?: return
        val next = transferAfterAnswerDeadline(awaiting, System.currentTimeMillis())
        if (next === awaiting) return
        android.util.Log.w(
            TAG_TRANSFER,
            "${awaiting.file}: all ${awaiting.rowsSent} readings went out and the sensor never " +
                "said what it did with them; giving up after ${TRANSFER_ANSWER_MS / 1000} s",
        )
        _ui.update { it.copy(transfer = next) }
    }

    fun dismissTransferResult() {
        val current = _ui.value.transfer
        if (current is SessionTransfer.Done || current is SessionTransfer.Rejected ||
            current is SessionTransfer.Failed
        ) {
            _ui.update { it.copy(transfer = SessionTransfer.Idle) }
        }
    }

    // --- erasing, and the backup it demands first -----------------------------

    /**
     * Ask the controller to erase what this device can prove it already holds.
     *
     * The proof is the manifest of the last export: one `name,readings,digest`
     * line per session. The controller recomputes both figures from its own
     * pages and keeps everything whose claim does not match - so an export taken
     * before the last hour of recording confirms the older sessions and leaves
     * the newest alone, which is the intended behaviour rather than a shortfall.
     *
     * With no export at all there is nothing to offer and the controller refuses,
     * which is the whole point of the gate.
     */
    fun eraseController() {
        val export = _ui.value.lastExport
        if (export == null || export.sessions.none { it.rows > 0 }) {
            _ui.update {
                it.copy(
                    erase = SessionErase.Refused(
                        "copy the sessions to this device first - the controller will not " +
                            "erase anything it cannot see a backup of",
                        sessionsKept = _ui.value.storage?.sessions ?: 0,
                    ),
                )
            }
            return
        }
        _ui.update { it.copy(erase = SessionErase.Asked) }
        sendCommand(Commands.erase(SessionArchive.backupClaim(export.sessions)))
    }

    /**
     * Erase without proving anything. `HANDOFF.md`'s obscure override.
     *
     * Reachable only from a deliberate, hard-to-hit gesture - see
     * `SessionCopyRow`. It exists for a board that has to be cleared and cannot
     * be backed up, and it is a separate call rather than a flag on the one
     * above so that no ordinary path can reach it by accident.
     */
    fun eraseControllerWithoutBackup() {
        _ui.update { it.copy(erase = SessionErase.Asked) }
        sendCommand(Commands.erase("", override = true))
    }

    private fun onEraseResult(msg: ServerMessage.EraseResult) {
        _ui.update {
            it.copy(
                erase = if (msg.done) {
                    SessionErase.Done(
                        sessionsErased = msg.sessionsConfirmed,
                        sessionsKept = msg.sessionsRefused,
                        readingsErased = msg.readingsErased,
                        overridden = msg.overridden,
                        message = msg.message,
                    )
                } else {
                    SessionErase.Refused(msg.message, msg.sessionsRefused)
                },
            )
        }
        transport?.send(Commands.listFiles())
        transport?.send(Commands.storage())
    }

    fun dismissEraseResult() {
        val current = _ui.value.erase
        if (current is SessionErase.Done || current is SessionErase.Refused ||
            current is SessionErase.Failed
        ) {
            _ui.update { it.copy(erase = SessionErase.Idle) }
        }
    }

    // --- backing everything up and clearing the sensor -------------------------
    //
    // A separate path from the erase above and from the copy above that, in this
    // file as well as on the controller. Nothing here calls into either of them,
    // and the rule that decides what may be freed is not in this file at all -
    // it is in [DeviceDrain] and, where it counts, in the sensor's own firmware.
    //
    // This half is the wiring: somewhere durable to write, and sending whatever
    // the drain says to send.

    /** The four commands a `drain_*` error can be answering. */
    private val DRAIN_COMMANDS = setOf(
        Wire.CMD_DRAIN_BEGIN, Wire.CMD_DRAIN_NEXT,
        Wire.CMD_DRAIN_CONFIRM, Wire.CMD_DRAIN_CANCEL,
    )

    private var drain: DeviceDrain? = null

    /**
     * Run [step] against the drain and send whatever it asks for.
     *
     * Every entry point goes through here, so there is exactly one place that
     * turns the drain's decisions into bytes and exactly one place that publishes
     * its state. A path that sent a command without publishing would leave the
     * screen saying something the sensor had already moved past.
     */
    private fun onDrain(step: (DeviceDrain) -> List<String>) {
        val run = drain ?: return
        val commands = step(run)
        _ui.update { it.copy(clearing = run.status) }
        for (line in commands) transport?.send(line)
        if (!run.status.running) drain = null
    }

    /**
     * Start a run: copy everything the sensor holds here, and clear it.
     *
     * The folder is opened before a single command goes out, so a device with
     * nowhere to write finds out now rather than after the sensor has been asked
     * to hand over its oldest block.
     */
    fun backUpAndClearSensor() {
        if (drain?.status?.running == true) return
        val ctx = appContext ?: return
        if (transfersAreInFlight(_ui.value)) return
        val root = java.io.File(ctx.getExternalFilesDir(null), "backups")
        val runId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val store = DrainStore(root, runId) { folder -> publishClearedCopy(ctx, folder) }
        val run = DeviceDrain(store)
        drain = run
        onDrain { run.start() }
    }

    /** Stop. Safe at any moment; the sensor frees nothing on this. */
    fun stopBackUpAndClear() = onDrain { it.cancel() }

    /** Clear a finished run off the screen. Never touches what was written. */
    fun dismissBackUpAndClear() {
        val run = drain
        if (run != null && run.status.running) return
        run?.dismiss()
        drain = null
        _ui.update { it.copy(clearing = DeviceDrain.Status()) }
    }

    /**
     * Zip the folder into Downloads once every block is in it.
     *
     * Deliberately last, and deliberately allowed to fail: the folder *is* the
     * copy - each block file was forced to storage before the sensor was told it
     * could free that block - and this only makes it a single thing the user can
     * open and share. A run whose zip failed has still saved every reading, and
     * says where they are.
     */
    private fun publishClearedCopy(ctx: android.content.Context, folder: java.io.File): String {
        val zip = java.io.File.createTempFile("sps30-clear", ".zip", ctx.cacheDir)
        try {
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
                for (file in folder.listFiles().orEmpty().sortedBy { it.name }) {
                    out.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            return com.sensirion.sps30.config.Exports.writeArchive(
                ctx, zip, com.sensirion.sps30.config.Exports.archiveName("cleared"),
            )
        } catch (e: Exception) {
            android.util.Log.w(
                "MonitorSession",
                "the cleared copy is in ${folder.absolutePath} but would not zip: ${e.message}",
            )
            return folder.absolutePath
        } finally {
            zip.delete()
        }
    }

    // --- multi-user control --------------------------------------------------

    /** Take the control lock, including from a holder who has walked away. */
    fun claimControl() {
        _ui.update { it.copy(controlDenied = null) }
        sendCommand(Commands.claim())
    }

    fun releaseControl() = sendCommand(Commands.release())

    fun dismissControlDenied() = _ui.update { it.copy(controlDenied = null) }

    /** A name other users will recognise. Falls back to the model name. */
    private fun deviceName(): String {
        val user = android.provider.Settings.Global.getString(
            appContext?.contentResolver, "device_name",
        )
        return (user ?: android.os.Build.MODEL ?: "phone").take(40)
    }

    // --- alerts --------------------------------------------------------------
    private val alertEngine = AlertEngine()

    /** Evaluate a reading against the configured rules and carry out any that fire. */
    private fun checkAlerts(values: Map<String, Double>) {
        val ctx = appContext ?: return
        val rules = _ui.value.config.alerts
        val fired = alertEngine.evaluate(rules, values)
        for (rule in fired) {
            AlertNotifier.fire(ctx, rule, values[rule.field] ?: rule.threshold)
        }
        val active = alertEngine.active
        if (active != _ui.value.activeAlerts) _ui.update { it.copy(activeAlerts = active) }
    }

    /**
     * Fire [rule] as if the air had just crossed it.
     *
     * Waiting for genuinely bad air to find out whether alerts are wired up is
     * not a reasonable test loop, so this exercises the real reaction path -
     * same notifier, same channels - and only the wording differs.
     */
    fun testAlert(rule: AlertRule) {
        val ctx = appContext ?: return
        alertEngine.rearm(rule)
        AlertNotifier.fire(ctx, rule, rule.threshold + 1.0, simulated = true)
    }

    // --- gesture coalescing -------------------------------------------------
    // Pointer events arrive at display rate (~120Hz on the S24). Applying and
    // recomputing per event queued work faster than the model thread drained it,
    // so the chart kept tracking stale deltas after the finger lifted - the
    // "momentum" effect. Instead deltas accumulate and exactly one apply+recompute
    // is ever in flight; anything that arrives meanwhile lands in the next flush.
    private val gestureLock = Any()
    private var pendingPan = 0.0
    private var pendingZoom = 1.0
    private var pendingPanY = 0.0
    private var pendingZoomY = 1.0

    /**
     * The point the coalesced y scale is about, as a fraction of the range from
     * its bottom.
     *
     * Last writer wins, and that is exact rather than a compromise: a pinch holds
     * one focal point for as long as the fingers are down (see
     * [PinchArbiter.focusY]), so every factor landing in one flush was scaling
     * about the same place, and scales about a common point compose by simple
     * multiplication - the product [pendingZoomY] already accumulates.
     */
    private var pendingZoomYAnchor = ChartView.Y_ANCHOR_CENTRE
    private var flushInFlight = false
    @Volatile private var gestureActive = false

    /** Finger down on the chart: the 1Hz ticker stands aside until [endGesture]. */
    fun beginGesture() { gestureActive = true }

    /** Finger up: drain any residual delta, then commit a manually dragged pin. */
    fun endGesture() {
        gestureActive = false
        scope.launch {
            applyPending()
            chart.commitManualPin()
            recompute()
        }
    }

    fun zoomBy(factor: Double) {
        synchronized(gestureLock) { pendingZoom *= factor }
        scheduleFlush()
    }

    fun panBy(deltaSeconds: Double) {
        synchronized(gestureLock) { pendingPan += deltaSeconds }
        scheduleFlush()
    }

    /** Drag on the y axis, as a fraction of the plot height (positive downwards). */
    fun panYBy(fraction: Double) {
        synchronized(gestureLock) { pendingPanY += fraction }
        scheduleFlush()
    }

    /**
     * Pinch on the y axis; a factor above 1 widens the range, and [anchor] is the
     * point it grows or shrinks about - a fraction of the range from its bottom,
     * so 0 holds the bottom still.
     */
    fun zoomYBy(factor: Double, anchor: Double = ChartView.Y_ANCHOR_CENTRE) {
        synchronized(gestureLock) {
            pendingZoomY *= factor
            pendingZoomYAnchor = anchor
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        synchronized(gestureLock) {
            if (flushInFlight) return
            flushInFlight = true
        }
        scope.launch {
            try {
                applyPending()
                recompute()
            } finally {
                val more = synchronized(gestureLock) {
                    flushInFlight = false
                    pendingPan != 0.0 || pendingZoom != 1.0 ||
                        pendingPanY != 0.0 || pendingZoomY != 1.0
                }
                if (more) scheduleFlush()
            }
        }
    }

    /** Apply and clear the accumulated deltas. Runs on the model dispatcher. */
    private fun applyPending() {
        val pan: Double
        val zoom: Double
        val panY: Double
        val zoomY: Double
        val zoomYAnchor: Double
        synchronized(gestureLock) {
            pan = pendingPan
            zoom = pendingZoom
            panY = pendingPanY
            zoomY = pendingZoomY
            zoomYAnchor = pendingZoomYAnchor
            pendingPan = 0.0
            pendingZoom = 1.0
            pendingPanY = 0.0
            pendingZoomY = 1.0
            pendingZoomYAnchor = ChartView.Y_ANCHOR_CENTRE
        }
        if (zoom == 1.0 && pan == 0.0 && zoomY == 1.0 && panY == 0.0) return
        val now = currentNow()
        if (zoom != 1.0) chart.zoom(zoom, now)
        if (pan != 0.0) chart.pan(pan, now)
        // The y gesture needs the fit it is departing from, which is the extent
        // of what is on screen - the same number the axis is drawn from.
        val current = _ui.value.chart
        if (zoomY != 1.0) chart.zoomY(zoomY, current?.dataYMin, current?.dataYMax, zoomYAnchor)
        if (panY != 0.0) chart.panY(panY, current?.dataYMin, current?.dataYMax)
    }

    // --- commands (routed through the active transport) ---------------------
    private fun sendCommand(line: String) { transport?.send(line) }
    fun pause() = sendCommand(Commands.pause())
    fun resume() = sendCommand(Commands.resume())
    fun clean() = sendCommand(Commands.clean())
    fun shutdownSensorHost() = sendCommand(Commands.shutdown())

    /**
     * Ask for the per-page occupancy map. The open map dialog is the only
     * caller, on a cadence it owns; nothing else polls, so a closed dialog
     * costs the wire nothing. Gated on [DashboardUiState.storage] like every
     * control a Pi host cannot serve: the Pi answers `unknown cmd` to this,
     * and a command that will be refused is not sent.
     */
    fun requestStorageMap() {
        if (_ui.value.storage != null) sendCommand(Commands.storageMap())
    }

    /** Switch the daemon's acquisition profile (continuous vs low-power). */
    fun setProfile(
        mode: String,
        intervalSeconds: Double,
        cycleSeconds: Double,
        warmupSeconds: Double,
        averageSeconds: Double,
    ) = sendCommand(
        Commands.setProfile(mode, intervalSeconds, cycleSeconds, warmupSeconds, averageSeconds)
    )

    /**
     * Ask the controller what it is drawing and what the other modes would
     * cost. Sent when the power panel opens, so the figures describe the board
     * as it is now - the answer depends on which radios are serving whom, and
     * that changes with every connection.
     */
    fun requestPowerEstimate() = sendCommand(Commands.getPowerEstimate())

    /** Restart begins a *new session*: the sensor is restarted AND the plot is
     *  cleared (a fresh session start for "all"). Pause+Resume keeps the plot. */
    fun restart() = onModel {
        sendCommand(Commands.restart())
        history.clear()
        // The cache is a copy of the plot, so clearing one clears the other.
        // Left alone it would put the old session back on the next start.
        plotCache?.reset()
    }

    // --- chart interaction (confined to the model dispatcher) ---------------

    /** A window-size button: a fixed span, of any size - a 2 d button is a two
     *  day window, not the old "all" (whose chip is gone; [autoSet] frames the
     *  whole record now). How much of a long span holds data is the record's
     *  business, and the empty remainder is honest. The record's ends ride
     *  along because in Pin mode the span begins at the pin and clamps at the
     *  record exactly as the view snaps do; see [ChartView.applyWindow]. */
    fun onWindow(seconds: Double) = onModel {
        chart.applyWindow(
            seconds, currentNow(),
            history.earliestEpoch(), history.latestEpoch(),
        )
    }
    /**
     * The Pin button, which is still one button rather than the Pin/Live pair it
     * replaced - but the press steps round a cycle rather than flipping a switch.
     *
     * Answering the press here with goLive is what cost the cycle: whatever
     * [ChartView.pinTap] did with a second press could never be reached, so the
     * recent marks were recorded and never offered. Live is a stop on the cycle
     * now instead of an interception of it, which leaves the two-stop case - one
     * mark and Live - exactly the toggle this button has always been.
     */
    fun onPinTap() = onModel { chart.pinTap(currentNow()) }
    /** The Unpin button: out of Pin in one press, without walking the cycle. */
    fun onUnpin() = onModel { chart.unpin(currentNow()) }
    fun onPinLongPress() = onModel { chart.pinLongPress(currentNow()) }
    fun goLive() = onModel { chart.goLive(currentNow()) }
    /**
     * Snap the view so the crosshair's instant becomes its left edge - "start
     * the view here". A pan that keeps the displayed span, clamped at the
     * record's ends; see [ChartView.snapWindowStart].
     */
    fun snapViewStart(epoch: Double) = onModel {
        chart.snapWindowStart(epoch, history.earliestEpoch(), history.latestEpoch(), currentNow())
    }
    /** The mirror image: the crosshair's instant becomes the view's right edge. */
    fun snapViewEnd(epoch: Double) = onModel {
        chart.snapWindowEnd(epoch, history.earliestEpoch(), history.latestEpoch(), currentNow())
    }

    // --- the selected stretch and its statistics ----------------------------
    //
    // Owned here, not by the screen, for two reasons. The statistics must come
    // from the full-resolution history, which is model-confined - the drawn
    // points are decimated or bucket means, and a maximum quietly read off a
    // bucket mean is exactly the smoothed peak the captain must not be shown.
    // And they must stay current: backfill can land inside the selection at
    // any moment, and [recompute] already runs on every such change, so the
    // numbers ride the same tick everything else on the plot rides.

    /** The committed selection's bounds, or null with none. Model-confined. */
    private var statsRegion: Pair<Double, Double>? = null

    /** The view to give back when the selection zoom is released. */
    private var regionReturn: RegionReturnView? = null

    /** A drag on the plot finished: this stretch is selected. */
    fun selectRegion(start: Double, end: Double) = onModel {
        if (end > start) statsRegion = start to end
    }

    /** The selection is dismissed; any zoom's way back goes with its card. */
    fun clearRegion() = onModel {
        statsRegion = null
        regionReturn = null
    }

    /**
     * The explicit confirmation: frame the selection as the visible window.
     * The view it replaces is kept for [returnFromRegionZoom] - and kept from
     * the *first* zoom when the captain re-zooms an adjusted selection, because
     * "back" means back to before the whole excursion into the excursion.
     */
    fun zoomToRegion() = onModel {
        val region = statsRegion ?: return@onModel
        if (regionReturn == null) regionReturn = captureReturnView(chart, currentNow())
        zoomViewToRegion(chart, region.first, region.second, currentNow())
    }

    /** Releasing the zoom returns to where he was; see [restoreReturnView]. */
    fun returnFromRegionZoom() = onModel {
        val saved = regionReturn ?: return@onModel
        regionReturn = null
        restoreReturnView(chart, saved, history.firstStableEpoch(), currentNow())
    }
    fun clearPlot() = onModel {
        history.clear()
        plotCache?.reset()
    }

    /** An explicit averaging interval from the main view; null/1s means raw. */
    fun setPlotInterval(seconds: Double?) = onModel { chart.setPlotInterval(seconds) }

    /**
     * Averaging that follows the visible span - the Average button's plain tap.
     *
     * Only the averaging flag, deliberately: [autoSet] is this *plus* framing
     * the whole session, and the button must not move the view. The maths is
     * unchanged - `ChartView.effectiveBucketSeconds` has always derived the
     * bucket from the visible span in auto mode; this just makes that mode
     * reachable without re-framing.
     */
    fun setAutoPlotInterval() = onModel { chart.setAutoInterval() }

    /**
     * Auto Set: frame the whole session and pick its own averaging.
     *
     * This is the "show me what I have" button - it fits x to every reading in
     * the session (what the old "all" button did) and then lets the chart choose
     * an averaging bucket from the visible span, so a multi-day session arrives
     * as a readable curve rather than a solid band. Zooming in afterwards walks
     * the averaging back down on its own.
     */
    fun autoSet() = onModel {
        chart.setAutoInterval()
        chart.applyAll(history.firstStableLiveEpoch() ?: currentNow(), currentNow())
    }
    fun setPinCycleCount(n: Int) = onModel { chart.setPinCycleCount(n) }

    // --- stored sessions ----------------------------------------------------

    private var importJob: Job? = null

    /**
     * Read every session stored on this device into the plot.
     *
     * Runs on the model dispatcher rather than an IO one, because it mutates the
     * same history the live stream writes to and that store is single-threaded
     * by design. The cost of that choice is paid back by suspending between
     * sessions: the message collector and the 1 Hz recompute interleave there,
     * so a minute-long read does not stall a live connection.
     */
    fun loadStoredSessions() {
        val context = appContext ?: return
        if (importJob?.isActive == true) return
        importJob = scope.launch {
            _ui.update { it.copy(storedImport = StoredImport.Scanning) }
            try {
                val sources = withContext(Dispatchers.IO) { StoredSessions.list(context) }
                if (sources.isEmpty()) {
                    _ui.update {
                        it.copy(
                            storedImport = StoredImport.Failed(
                                "No stored sessions found in Downloads/SPS30. " +
                                    "Copy them from the sensor first."
                            )
                        )
                    }
                    return@launch
                }
                // Classify before committing to a parse. This decides both what
                // the progress bar counts and how coarsely to average, and it
                // costs a directory read plus the header of each small file.
                val plan = withContext(Dispatchers.IO) {
                    StoredSessions.plan(context, sources, history)
                }
                // Size the averaging against what is *left* of the point
                // budget, never the whole of it. The plot already holds the
                // sensor's own readings; an import that budgets for an empty
                // plot pushes the combined total past the cap and the oldest
                // points go - which are the very history being imported. See
                // [StoredImport.freePoints]. The imported points already held
                // are not counted as occupied: they are what this run is
                // re-deciding, and they either de-duplicate away or are what
                // the new budget buys. Read once, before any file is opened, so
                // the bucket ordinal - the de-duplication key - is fixed for
                // the whole run.
                val heldLive = history.size - history.importedPointCount()
                val budget = StoredImport.freePoints(heldLive)
                val bucket = StoredImport.bucketFor(plan.bytes, budget)
                val tally = StoredSessions.importInto(
                    context, plan, history, bucket, budget,
                ) { name, done, total, points ->
                    _ui.update {
                        it.copy(
                            storedImport = StoredImport.Loading(
                                name, done, total, points, plan.empty,
                            ),
                        )
                    }
                    // The whole point of suspending here: let anything else on
                    // this single-threaded dispatcher have a turn.
                    yield()
                }
                _ui.update {
                    it.copy(
                        storedImport = StoredImport.Loaded(
                            sessions = tally.sessions,
                            points = tally.points,
                            rows = tally.rows,
                            empty = tally.empty,
                            unrecognised = tally.unrecognised,
                            duplicate = tally.duplicate,
                            bucketSeconds = bucket,
                            start = history.earliestEpoch() ?: 0.0,
                            end = history.latestEpoch() ?: 0.0,
                            truncated = tally.truncated,
                        )
                    )
                }
                // Frame what just arrived, so the answer to "what is in those
                // files" is on screen without a second gesture.
                chart.setAutoInterval()
                chart.applyAll(history.firstStableEpoch() ?: currentNow(), currentNow())
                recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(storedImport = StoredImport.Failed(e.message ?: "could not read stored sessions")) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { StoredSessions.releaseCache(context) }
            }
        }
    }

    /** Drop the imported sessions again, leaving the live stream alone. */
    fun clearStoredSessions() {
        importJob?.cancel()
        onModel {
            history.clearImported()
            _ui.update { it.copy(storedImport = StoredImport.Idle) }
            chart.goLive(currentNow())
        }
    }

    // --- putting readings back from a file ------------------------------------
    //
    // The way in, and the only one. Data could leave this app by four routes and
    // return by none, which made a host backup a place readings went to be kept
    // and never came out of. See [SessionRestore] for what it reuses and why the
    // session name is canonicalised rather than trusted.
    //
    // Deliberately not the "stored sessions" path above. That one averages into
    // a namespace of its own that can be dropped whole, which is right for
    // *looking* at an archive and wrong for repairing the record: these readings
    // go into the live key space at full resolution, so they close a gap, they
    // count towards coverage, and they leave in an export.

    @Volatile private var restoreJob: Job? = null

    /**
     * Merge a session file the user picked into this device's record.
     *
     * Runs on the model dispatcher for the same reason [loadStoredSessions]
     * does: it mutates the history the live stream writes to, and that store is
     * single-threaded by design. The reads inside are blocking, so it suspends
     * between sessions to let the collector and the recompute interleave.
     *
     * [displayName] must be the file's real name rather than a label. It decides
     * archive from lone session, and for a lone session it is the only thing
     * that says which session the readings belong to.
     */
    fun restoreFromFile(uri: android.net.Uri, displayName: String) {
        val ctx = appContext ?: return
        if (restoreJob?.isActive == true) return
        val open = {
            ctx.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("could not open that file")
        }

        restoreJob = scope.launch {
            _ui.update { it.copy(restore = RecordRestore.Reading(displayName, "", 0)) }
            val result = SessionRestore.restore(open, displayName, history) { session, readings ->
                _ui.update {
                    it.copy(restore = RecordRestore.Reading(displayName, session, readings))
                }
                // The whole point of suspending here: hand this single-threaded
                // dispatcher back between sessions.
                yield()
            }
            result.onSuccess { report ->
                android.util.Log.i(
                    TAG_TRANSFER,
                    "restored $displayName: ${report.added} added, " +
                        "${report.alreadyHeld} already here, ${report.undated} undated",
                )
                _ui.update { it.copy(restore = RecordRestore.Done(displayName, report)) }
                // What the record now holds has changed, so everything derived
                // from it is stale: the comparison against the sensor, the list
                // of holes, and the plot itself. The gaps are recomputed from
                // what is held rather than swept, because a restore answers the
                // question locally and there is nothing to ask the sensor for.
                refreshInventory()
                _ui.update { it.copy(gaps = it.gaps.copy(gaps = currentGaps())) }
                recompute()
            }.onFailure { e ->
                val refusal = (e as? SessionRestore.FileRefused)?.refusal
                val reason = refusal?.reason ?: e.message ?: "that file could not be read"
                android.util.Log.w(TAG_TRANSFER, "$displayName was not restored: $reason")
                _ui.update {
                    it.copy(
                        restore = if (refusal != null) {
                            RecordRestore.Refused(displayName, reason)
                        } else {
                            RecordRestore.Failed(displayName, reason)
                        },
                    )
                }
            }
        }
    }

    fun dismissRestoreResult() {
        val current = _ui.value.restore
        if (current is RecordRestore.Done || current is RecordRestore.Refused ||
            current is RecordRestore.Failed
        ) {
            _ui.update { it.copy(restore = RecordRestore.Idle) }
        }
    }

    /**
     * CSV of every buffered point (Eastern timestamps), read on the model thread.
     *
     * The `time_source` column says what each row's time actually came from -
     * `measured` when the controller had a clock, `derived` when it did not and
     * the time was computed from an equivalence, `manual` when the user placed
     * the run themselves, and `unanchored` when nothing places it and the
     * timestamp column is elapsed time rather than a date.
     *
     * The captain's rule: an export is honest about what the device knew. Every
     * one of those four is a different level of confidence in the same column,
     * and a file that flattened them would look like four measurements.
     *
     * `rel_boot` and `rel_ms` carry the board's own clock for the rows that have
     * one, and are empty for the rows that never did. Without them the honesty
     * was one-way: the file said a row was `unanchored`, wrote `+0:00:00` where
     * a date would go, and then held nothing that could ever place it - so
     * reading the file back dropped every such row on the floor. `time_source`
     * says what the time is worth; these two are the time itself.
     */
    suspend fun buildCsv(): String = withContext(confinement) {
        buildString {
            append("timestamp,index,").append(Wire.PM_FIELDS.joinToString(","))
                .append(',').append(Wire.COL_EVENT)
                .append(',').append(Wire.COL_REL_BOOT)
                .append(',').append(Wire.COL_REL_MS)
                .append(',').append(Wire.TIME_SOURCE_COLUMN).append('\n')
            for (s in history.snapshot()) {
                // Locals rather than s.relBoot/s.relMs in the condition: the
                // sample now comes from :core, and a smart cast cannot cross a
                // module boundary.
                val relBoot = s.relBoot
                val relMs = s.relMs
                val resolution = if (relBoot != null && relMs != null) {
                    timeBasis.resolve(relBoot, relMs)
                } else {
                    null
                }
                val source = resolution?.source ?: Wire.TIME_MEASURED
                // The elapsed figure comes from the resolution, not from the
                // store: the store holds no time for such a reading, which is the
                // whole point. Its own run is what it is measured against.
                val at = s.epoch
                val ts = when {
                    resolution is TimeBasis.Resolution.Unanchored ->
                        // Elapsed time, not a date, because nothing knows the
                        // date. Written as a duration so it cannot be misread.
                        elapsedLabel(resolution.elapsedSeconds)
                    at == null -> elapsedLabel(0.0)
                    else -> Instant.ofEpochSecond(at.toLong())
                        .atZone(TimeSeriesHistory.EASTERN).format(CSV_TIME)
                }
                append(ts).append(',').append(s.index)
                for (f in Wire.PM_FIELDS) append(',').append(s.values[f] ?: 0.0)
                append(',').append(s.event)
                append(',').append(s.relBoot ?: "").append(',').append(s.relMs ?: "")
                append(',').append(source).append('\n')
            }
        }
    }

    /** `+1:04:12` - time since the run began, for a block nothing can place. */
    private fun elapsedLabel(seconds: Double): String {
        val total = seconds.toLong()
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        return "%s%d:%02d:%02d".format(sign, abs / 3600, (abs % 3600) / 60, abs % 60)
    }

    /** Tell a controller what time it is. Harmless to one that already knows. */
    private fun offerTheTime() {
        val now = System.currentTimeMillis()
        val offsetMinutes = java.util.TimeZone.getDefault().getOffset(now) / 60000
        transport?.send(Commands.setTime(now, offsetMinutes))
    }

    /**
     * Publish what the UI needs to be honest about relative data.
     *
     * Deliberately not folded into the ordinary chart state: "these readings are
     * real but nobody knows when they happened" is a different kind of fact from
     * a value, and the screen has to be able to say it in words.
     */
    private fun publishRelativeState() {
        val unplaceable = timeBasis.unanchoredBoots()
        _ui.update {
            it.copy(
                relativeTime = if (!history.hasRelativePoints()) {
                    null
                } else {
                    RelativeTimeState(
                        anchors = timeBasis.anchorCount,
                        unanchoredRuns = unplaceable,
                        currentBoot = timeBasis.currentBoot,
                    )
                }
            )
        }
    }

    /**
     * Place a run of a clockless controller by hand.
     *
     * The user knows something no measurement here does - "I plugged it in when
     * I left the house". It shifts the whole block and leaves its internal
     * timing untouched, because that part was never in doubt. Passing null takes
     * the placement away again.
     */
    fun placeRunManually(boot: Int, epochSeconds: Double?) = onModel {
        timeBasis.placeManually(boot, epochSeconds)
        history.retime()
        publishRelativeState()
    }

    /** Apply a full config: pushes pin-cycle to the chart, publishes it for the UI,
     *  and (re)starts the connection when the connection mode changes. */
    fun applyConfig(config: AppConfig) = onModel {
        chart.setPinCycleCount(config.pinCycleCount)
        // Against what was *asked for*, not what is serving: during a switch the two
        // differ for as long as the handover takes, and comparing with the serving
        // mode would restart the switch on every unrelated config change.
        val modeChanged = config.connectionMode != desiredMode
        val previous = _ui.value.config
        _ui.update { it.copy(config = config) }
        // A rule the user just turned off, or a threshold they just moved, changes
        // what the controller keeps when its ring wraps - so it has to hear about
        // it now rather than at the next reconnection. Only when it actually
        // changed: this runs on every config edit, including the ones about
        // colours.
        if (previous.preservationThresholds != config.preservationThresholds ||
            previous.receivePreservedAlerts != config.receivePreservedAlerts
        ) {
            sendPreservationPolicy()
        }
        if (modeChanged) selectMode(config.connectionMode)
    }

    fun setYAutoscale(on: Boolean) = onModel {
        chart.yAutoscale(on, _ui.value.chart?.dataYMin, _ui.value.chart?.dataYMax)
    }

    fun setYRange(min: Double, max: Double) = onModel { chart.setYRange(min, max) }

    /** Pin the temporary y override so panning time cannot nudge it. */
    fun setYLocked(locked: Boolean) = onModel { chart.setYLocked(locked) }

    /** Hand the y axis back to autoscaling, locked or not. */
    fun releaseYOverride() = onModel { chart.releaseYOverride() }

    /** Pin the held time range so a stray finger cannot pan or zoom it. */
    fun setXLocked(locked: Boolean) = onModel { chart.setXLocked(locked) }

    /** Hand the x axis back to following now, locked or not. */
    fun releaseXRange() = onModel { chart.releaseXRange() }

    /** Step the x gridlines one rung finer, wrapping back to the window's own. */
    fun cycleTickDensity() = onModel { chart.cycleTickDensity(currentNow()) }

    private inline fun onModel(crossinline block: () -> Unit) {
        scope.launch {
            block()
            recompute()
        }
    }
}

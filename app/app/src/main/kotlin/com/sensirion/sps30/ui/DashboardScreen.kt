package com.sensirion.sps30.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.foundation.layout.size
import com.sensirion.sps30.data.ConnectionDot
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.data.DotTransport
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.PlottedSeries
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.data.XAxisControl
import com.sensirion.sps30.data.YAxisControl
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.net.WifiNetworkBinder
import com.sensirion.sps30.service.Sps30Service
import com.sensirion.sps30.wire.Wire
import kotlin.math.roundToInt


@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var showYDialog by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    // Which tab the sheet opens on. The gear always lands on the same one, so
    // the menu is where you left it in the sense that matters - predictable -
    // while the time flag can send you straight to the answer it is flagging.
    var optionsTab by remember { mutableStateOf(OptionsTab.entries.first()) }
    var showPlaceRuns by remember { mutableStateOf(false) }
    var showStorageMap by remember { mutableStateOf(false) }
    var showPowerPanel by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Theme colours captured here so the off-screen PNG render matches the UI.
    // These have to be the values [PmChart] itself uses, not merely similar ones:
    // the grid was exported at 0.4 alpha against 0.85 on screen, so the saved
    // picture came out with fainter gridlines than the plot it was a picture of.
    val pngBg = MaterialTheme.colorScheme.surface
    val pngAxis = MaterialTheme.colorScheme.outline
    val pngGrid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    val pngLabel = MaterialTheme.colorScheme.onSurfaceVariant

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Start regardless of the grant: the service still runs; the notification
        // simply won't be visible until the user allows notifications.
        Sps30Service.start(context)
    }
    val toggleBackground: (Boolean) -> Unit = { enable ->
        if (enable) {
            val needsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPerm) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else Sps30Service.start(context)
        } else {
            Sps30Service.stop(context)
        }
    }
    // Bluetooth mode needs runtime BLE permissions (SCAN/CONNECT on 12+, else location).
    val blePerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Switch either way: if denied, BleTransport simply can't scan and the banner
        // shows the failure, prompting the user to grant it in Settings.
        viewModel.setConnectionMode(ConnectionMode.BLUETOOTH)
    }
    val onSelectMode: (ConnectionMode) -> Unit = { mode ->
        if (mode == ConnectionMode.BLUETOOTH) {
            val need = blePerms.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (need) blePermLauncher.launch(blePerms)
            else viewModel.setConnectionMode(ConnectionMode.BLUETOOTH)
        } else {
            viewModel.setConnectionMode(mode) // WIFI or NONE
        }
    }
    // Background monitoring defaults on, so start it once per launch unless the
    // user has turned it off. The permission request is fired at the same time;
    // the service runs either way, the notification just stays hidden if denied.
    var backgroundStarted by remember { mutableStateOf(false) }
    LaunchedEffect(state.config.backgroundMonitoring) {
        if (state.config.backgroundMonitoring && !backgroundStarted) {
            backgroundStarted = true
            toggleBackground(true)
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    val onExportCsv: () -> Unit = { viewModel.exportCsv { loc -> toast("CSV saved to $loc") } }

    // The plot's own pixel size, reported by the layout that holds it. The PNG is
    // rendered at exactly this size so it is the same plot at the same
    // proportions, not a re-layout into some other rectangle - tick spacing and
    // label density both follow the canvas size, so a fixed 16:9 export of a
    // taller on-screen plot came back subtly rearranged.
    var chartSize by remember { mutableStateOf(IntSize.Zero) }

    // Side-by-side on wide screens (Tab S7+, landscape); stacked on phones.
    // The inspection crosshair persists after the finger lifts, so it needs an
    // explicit dismissal: any touch on the dashboard outside the plot clears it.
    //
    // The whole cursor family - the crosshair's anchor, the on/off switch,
    // Follow, and the pin state machine - lives in one saveable holder, so all
    // of it survives rotation together. It used to be four declarations here,
    // and the one that was not saveable is exactly the one a rotation silently
    // destroyed: the placed-but-unpinned crosshair, the state the crosshair is
    // used in most. See [CursorState] for what restoring each part means; the
    // local names below are the same fields, kept short because this function
    // says them forty times.
    val cursorState = rememberCursorState()
    var cursor by cursorState::cursor
    var cursorEnabled by cursorState::enabled
    var cursorFollows by cursorState::follows
    var cursorPin by cursorState::pin
    // Lock: the graph stops answering touch at all, so a stray finger cannot cost
    // the user a crosshair they took trouble to place. Everything off the graph
    // stays live, including this button. Saved across a rotation only, like the
    // cursor modes - a way of working rather than a preference.
    var graphLocked by rememberSaveable { mutableStateOf(false) }
    // Select: while armed, a lone finger on the plot drags out a selection
    // instead of touching the crosshair. The armed flag is a way of working and
    // survives rotation like the lock; the committed selection and its
    // statistics live in the session (see [MonitorSession.selectRegion]), so
    // only the drag in flight is held here - losing a half-made drag to a
    // rotation costs one swipe, losing the committed stretch would cost work.
    var regionArmed by rememberSaveable { mutableStateOf(false) }
    var regionDraft by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val chartData = state.chart
    val pinnedNow = cursorPin !is CursorPin.FreeCursor
    // Where the crosshair is. A pinned cursor's position is the pin's own
    // business - its anchor is the pin's payload - and the free cursor's is the
    // [ChartCursor] anchor the view mode called for.
    val inspected =
        if (pinnedNow) cursorPin.epochIn(chartData) else cursor?.epochIn(chartData)
    // Which anchor the *free* cursor gets is decided by what the view is doing,
    // and switching must not move the crosshair.
    //
    // Every view whose right edge is now follows now. A frozen one does not -
    // nothing flows past it - so holding a place on the plot there buys nothing
    // and costs everything the moment the range is navigated: the crosshair would
    // sit at its pixel while the data slid out from under it. LIVE_FROZEN used
    // to be counted as following, which is exactly why zooming and panning
    // dragged the data away from the sample the crosshair was placed on.
    //
    // A pin is the user overruling that inference altogether; while one is
    // engaged the free anchor is out of the picture and nothing here runs. The
    // whole rule is [cursorFollowsNow], so it is one thing that can be checked
    // rather than a condition restated wherever the anchor is needed.
    val followsNow = cursorFollowsNow(chartData, pinnedNow)
    LaunchedEffect(followsNow) {
        // A pinned cursor's anchor is the pin itself; a view-mode change has
        // nothing to re-express. And a following cursor is not anchored to
        // either an instant or a place, so there is nothing to re-express there
        // either.
        if (pinnedNow) return@LaunchedEffect
        if (cursor is ChartCursor.AtLatest) return@LaunchedEffect
        val here = cursor?.epochIn(chartData) ?: return@LaunchedEffect
        cursor = anchorFor(here, chartData, followsNow)
    }
    // The one place a following cursor is established. Keyed on the mode rather
    // than set by the control, so following is re-established wherever the mode
    // arrives from - the chip, or a restored rotation.
    LaunchedEffect(cursorFollows) {
        if (cursorFollows) cursor = ChartCursor.AtLatest
    }
    val onInspect: (Double?) -> Unit = { epoch ->
        // A finger on the plot takes the crosshair back. Leaving Follow engaged
        // would snap it to the newest sample a second later, which reads as the
        // touch not having registered.
        //
        // Reachable only in the free-cursor state: the chart's gesture layer
        // asks [cursorMovableByTouch] before letting a finger near the
        // crosshair. The guard here is the same rule stated once more at the
        // state's own door, so no future caller can move a pinned cursor.
        if (cursorPin is CursorPin.FreeCursor) {
            cursorFollows = false
            cursor = epoch?.let { anchorFor(it, chartData, followsNow) }
        }
    }
    // A navigation gesture holds the free crosshair to its instant for as long
    // as it lasts, so zoom and pan carry it along with its own data point
    // instead of leaving it at a fixed x. Releasing hands it back to whichever
    // anchor the resulting view mode calls for, without moving it.
    // A following cursor has no instant of its own to hold, and a pinned one
    // already holds its own anchor - the pin *is* what pan and zoom must not
    // dislodge - so navigation leaves both alone at both ends.
    val onGestureStart: () -> Unit = {
        if (cursorPin is CursorPin.FreeCursor && cursor !is ChartCursor.AtLatest) {
            cursor = cursor?.epochIn(chartData)?.let { ChartCursor.AtTime(it) }
        }
        viewModel.beginGesture()
    }
    val onGestureEnd: () -> Unit = {
        if (cursorPin is CursorPin.FreeCursor && cursor !is ChartCursor.AtLatest) {
            cursor = cursor?.epochIn(state.chart)?.let { anchorFor(it, state.chart, followsNow) }
        }
        viewModel.endGesture()
    }
    val onToggleCursor: () -> Unit = {
        cursorEnabled = !cursorEnabled
        if (!cursorEnabled) {
            cursor = null
            cursorFollows = false
            cursorPin = CursorPin.FreeCursor
        }
    }
    // Engaging any pin is a decision about the point the crosshair is on now,
    // so Follow stops and the pin captures that point in its own terms - an
    // instant, or an offset from an edge - without moving the crosshair.
    // Releasing hands the cursor back to whichever anchor the view calls for,
    // at the spot the pin left it.
    fun engagePin(pin: CursorPin?) {
        if (pin != null && pin !is CursorPin.FreeCursor) {
            cursorFollows = false
            cursor = null
            cursorPin = pin
        }
    }
    fun releasePin() {
        val here = cursorPin.epochIn(chartData)
        cursorPin = CursorPin.FreeCursor
        cursor = here?.let { anchorFor(it, chartData, cursorFollowsNow(chartData, false)) }
    }
    // Each pin chip toggles its own state and takes over from any other: the
    // pins are one machine, so selecting another type is a transition, not a
    // second flag. The corner button on the plot shares onPinToTime.
    val onPinToTime: () -> Unit = {
        if (cursorPin is CursorPin.ToTime) releasePin()
        else inspected?.let { engagePin(CursorPin.ToTime(it)) }
    }
    val onPinFromRight: () -> Unit = {
        if (cursorPin is CursorPin.FromRight) releasePin()
        else inspected?.let { engagePin(pinFromRight(chartData, it)) }
    }
    val onPinFromLeft: () -> Unit = {
        if (cursorPin is CursorPin.FromLeft) releasePin()
        else inspected?.let { engagePin(pinFromLeft(chartData, it)) }
    }
    // Snap to peak: jump to the highest visible point and pin there, as an
    // ordinary pin-to-time - released the same way, and if the view moves on so
    // some other point becomes the visible maximum, the cursor stays on the
    // point it snapped to. See [peakEpoch] for which series' peak it is.
    val onSnapToPeak: () -> Unit = {
        peakEpoch(chartData, state.config.visiblePm)?.let {
            cursorEnabled = true
            engagePin(CursorPin.ToTime(it))
        }
    }
    // Snap the view to the crosshair: its instant becomes the left (Start
    // here) or right (End here) edge of the window, duration kept - the model
    // pans the view (see [ChartView.snapWindowStart]) and the cursor is left
    // holding the moment it snapped on, still pinned if it was pinned
    // ([CursorState.onViewSnapped] is that rule, stated once).
    val onSnapViewStart: () -> Unit = {
        inspected?.let {
            viewModel.snapViewStart(it)
            cursorState.onViewSnapped(it)
        }
    }
    val onSnapViewEnd: () -> Unit = {
        inspected?.let {
            viewModel.snapViewEnd(it)
            cursorState.onViewSnapped(it)
        }
    }
    val onToggleFollows: () -> Unit = {
        if (cursorFollows) {
            // Let go where it is, not where it was before it started following.
            cursorFollows = false
            cursor = cursor?.epochIn(chartData)?.let { anchorFor(it, chartData, followsNow) }
        } else {
            // Following implies there is a crosshair to follow with. It also
            // rules out pinning: riding the newest reading and staying on one
            // chosen point are opposite instructions, and the last one given
            // wins rather than being silently ignored.
            cursorEnabled = true
            cursorPin = CursorPin.FreeCursor
            cursorFollows = true
        }
    }
    // A locked graph swallows its own touches (see `chartGestures` in PmChart), so
    // this handler never hears them; it stays armed for taps genuinely away from
    // the plot, which is the gesture it is for. A pinned cursor is not dismissed
    // this way at all: the pin exists so that no stray touch can cost the user
    // the point they fixed, and that includes a tap on dead panel space. The
    // way out of a pin is its own control, not an accident.
    val dismissModifier = Modifier.dismissCursorOnTapAway(
        active = cursorPin is CursorPin.FreeCursor && cursor != null,
    ) {
        cursor = null
        cursorFollows = false
    }

    val cursorControls = CursorControls(
        enabled = cursorEnabled,
        onToggleEnabled = onToggleCursor,
        follows = cursorFollows,
        onToggleFollows = onToggleFollows,
        pin = cursorPin,
        pinnable = inspected != null,
        peakAvailable = peakEpoch(chartData, state.config.visiblePm) != null,
        onPinToTime = onPinToTime,
        onPinFromRight = onPinFromRight,
        onPinFromLeft = onPinFromLeft,
        onSnapToPeak = onSnapToPeak,
        onSnapViewStart = onSnapViewStart,
        onSnapViewEnd = onSnapViewEnd,
    )

    // One description of the plot, drawn to the screen and saved to the PNG. The
    // export cannot drift from the view because there is nothing for it to drift
    // from: both are this value. The selection drawn is the drag in flight while
    // there is one, else the committed stretch the session is reporting on.
    val render = state.chartRender(
        inspected,
        selection = regionDraft ?: state.region?.let { it.start to it.end },
    )

    // Saving the image is a picture of the plot, so it is drawn from exactly what
    // the plot is drawn from - including the crosshair when one is showing, and
    // nothing where there is none. Defined here rather than beside the CSV export
    // because this is where [render] and the chart's measured size become known;
    // the CSV deliberately depends on neither.
    val onSavePng: () -> Unit = {
        val bmp = renderChartToBitmap(
            render = render,
            widthPx = if (chartSize.width > 0) chartSize.width else 1600,
            heightPx = if (chartSize.height > 0) chartSize.height else 900,
            background = pngBg, axisColor = pngAxis, gridColor = pngGrid, labelColor = pngLabel,
        )
        viewModel.savePng(bmp) { loc -> toast("PNG saved to $loc (check your gallery)") }
    }

    val chartTouch = ChartTouchCallbacks(
        inspected, onInspect, cursorControls, onGestureStart, onGestureEnd,
        graphLocked = graphLocked,
        onToggleGraphLock = { graphLocked = !graphLocked },
        regionSelecting = regionArmed,
        // Leaving Select keeps the committed selection and its statistics - the
        // stretch is work, and stopping the gesture is not an instruction to
        // throw it away. Only the drag in flight goes.
        onToggleRegionSelect = {
            regionArmed = !regionArmed
            if (!regionArmed) regionDraft = null
        },
        onRegionDrag = { s, e -> regionDraft = s to e },
        onRegionCommit = { s, e ->
            regionDraft = null
            viewModel.selectRegion(s, e)
        },
        onRegionCancel = { regionDraft = null },
        onRegionTap = {
            regionDraft = null
            viewModel.clearRegion()
        },
    )
    val wide = sideBySide(LocalConfiguration.current)
    // The leftmost tab by name rather than by identity: the gear landing on the
    // tab the strip already shows as first is what makes the strip readable, and
    // it stays true if the order is revised again.
    val openOptions = { showOptions = true; optionsTab = OptionsTab.entries.first() }
    val openHelp = { showOptions = true; optionsTab = OptionsTab.HELP }
    // Tapping the derived-times flag: the answer if there is nothing to do, the
    // form if there is. Runs nothing could place are the only case here that
    // asks anything of the user.
    val onTimeFlag = {
        if (state.relativeTime?.hasUnplaceable == true) showPlaceRuns = true else openHelp()
    }
    // Ask on open, every open: the answer depends on which radios are serving
    // whom at this moment, so a figure from the last look is a figure about a
    // different board state. The stale copy stays visible while the fresh one
    // is in flight, which the panel labels rather than hides.
    val openPowerPanel = {
        viewModel.requestPowerEstimate()
        showPowerPanel = true
    }
    // Where the readouts end, so the options menu can stop below them: the
    // captain's rule is that the particle concentration must never be
    // invisible, and the menu was the one exception left.
    var readoutsBottom by remember { mutableStateOf(SCRIM_STRIP) }
    if (wide) {
        WideDashboard(
            modifier.then(dismissModifier), state, viewModel, render, chartTouch,
            onChartSize = { chartSize = it },
            onTimeFlag = onTimeFlag,
            onOpenOptions = openOptions,
            onReadoutsBottom = { readoutsBottom = it },
        )
    } else {
        StackedDashboard(
            modifier.then(dismissModifier), state, viewModel, render, chartTouch,
            onChartSize = { chartSize = it },
            onTimeFlag = onTimeFlag,
            onOpenOptions = openOptions,
            onReadoutsBottom = { readoutsBottom = it },
        )
    }

    if (showPowerPanel) {
        PowerPanelDialog(state = state, onDismiss = { showPowerPanel = false })
    }

    if (showPlaceRuns) {
        state.relativeTime?.let { rt ->
            UnplacedRunsDialog(
                state = rt,
                onPlaceRun = viewModel::placeRunManually,
                onOpenHelp = { showPlaceRuns = false; openHelp() },
                onDismiss = { showPlaceRuns = false },
            )
        }
    }

    if (showStorageMap) {
        StorageMapDialog(
            state = state,
            onRefresh = viewModel::requestStorageMap,
            onDismiss = { showStorageMap = false },
        )
    }

    if (showYDialog) {
        // Opens holding the range the axis is actually spanning. Turning
        // autoscale off adopts the fit it had just chosen for this view, so on
        // first open these are exactly the values autoscaling picked - which is
        // what makes the dialog a place to adjust a range rather than one to
        // invent a pair of numbers from nothing.
        val chart = state.chart
        val fallback = com.sensirion.sps30.data.ChartView.autoYRange(chart?.dataYMin, chart?.dataYMax)
        val lo = chart?.yFloor ?: fallback.first
        val hi = chart?.yCeil ?: fallback.second
        YRangeDialog(
            initialMin = formatYField(lo, hi - lo),
            initialMax = formatYField(hi, hi - lo),
            onDismiss = { showYDialog = false },
            onApply = { min, max -> viewModel.setYRange(min, max); showYDialog = false },
        )
    }

    // A refused command is worth interrupting for: the user pressed something and
    // nothing happened, and the only thing worse than being told why is not being
    // told at all. Two shapes, because there are two kinds of refusal and only one
    // of them has "take control" as its answer - offering that button for "low
    // power needs firmware 2.0" would send the user off to fix the wrong thing.
    state.controlDenied?.let { message ->
        if (state.refusalNeedsControl) {
            AlertDialog(
                onDismissRequest = viewModel::dismissControlDenied,
                title = { Text("Someone else has control") },
                text = {
                    Text(
                        "$message.\n\nYou can keep watching without taking control. " +
                            "Taking it over stops them from changing the sensor until " +
                            "they take it back."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.claimControl()
                    }) { Text("Take control") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissControlDenied) { Text("Keep watching") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = viewModel::dismissControlDenied,
                title = { Text("The sensor refused that") },
                // The controller's own words. It knows why - which firmware it
                // found, which command it does not have - and paraphrasing here
                // would only lose that.
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissControlDenied) { Text("OK") }
                },
            )
        }
    }

    // A transport the user chose and could not have. Nothing was broken by it -
    // whatever was working is still working - but a minute of waiting that ends in
    // silence is the failure they cannot tell from a wedged app.
    state.connectionNotice?.let { message ->
        ConnectionNoticeDialog(message, onDismiss = viewModel::dismissConnectionNotice)
    }

    if (showOptions) {
        OptionsSheet(
            state = state,
            viewModel = viewModel,
            tab = optionsTab,
            onSelectTab = { optionsTab = it },
            onSetY = { showOptions = false; showYDialog = true },
            onToggleBackground = { on ->
                viewModel.setBackgroundMonitoring(on)
                toggleBackground(on)
            },
            onExportCsv = onExportCsv,
            onSavePng = onSavePng,
            onSelectMode = onSelectMode,
            onOpenStorageMap = { showStorageMap = true },
            onOpenPowerPanel = openPowerPanel,
            onDismiss = { showOptions = false },
            keepClearTop = readoutsBottom,
        )
    }
}

/**
 * Whether the screen is shaped for the controls to sit beside the chart.
 *
 * Side-by-side needs width to spare *relative to height*. A tablet held in
 * portrait is still 600dp+ wide, so width alone put the controls beside the chart
 * there and squeezed both. Portrait always stacks: controls above, the chart full
 * width below.
 *
 * Named and lifted out of the screen so a layout test can branch the way the app
 * branches, from the same configuration, rather than deciding for itself which
 * layout a given screen shape ought to get.
 */
internal fun sideBySide(config: Configuration): Boolean =
    config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp

/**
 * Clears the crosshair when the user touches the dashboard away from it.
 *
 * The crosshair persists after the finger lifts - that is the point of it - so
 * it needs an explicit way to go, and "touch anything that is not about it" is
 * the one gesture nobody has to be taught.
 *
 * ## What "away from it" rests on
 *
 * `requireUnconsumed = true`, and therefore on every control the press could
 * have been aimed at consuming it first. The chart claims touches inside the
 * plot; a button claims its own. What has no claim is a control that is showing
 * but inert, and there the press arrived here and was read as a tap on empty
 * space - which is how pressing the crosshair's own corner button, in the
 * default view where it has nothing to toggle, destroyed the crosshair. The
 * button is excluded from the *chart's* dismissal by name; it was not excluded
 * from this one, because this one does not know about it and should not.
 *
 * So the rule stays where it belongs - a control absorbs the press aimed at it;
 * the corner button now always toggles, so its press is always consumed - rather
 * than this handler growing a list of exceptions it would have to be kept in
 * step with.
 *
 * Named and lifted out of the screen so a test can put a real control under a
 * real dismiss handler and press it, which is the only place the fault existed:
 * both halves were individually correct.
 */
internal fun Modifier.dismissCursorOnTapAway(active: Boolean, onDismiss: () -> Unit): Modifier =
    if (!active) {
        this
    } else {
        this.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = true)
                onDismiss()
            }
        }
    }

/**
 * Everything the chart needs to talk to the crosshair, passed as one value so
 * the two layouts do not each carry six near-identical parameters.
 */
internal data class ChartTouchCallbacks(
    val inspected: Double?,
    val onInspect: (Double?) -> Unit,
    /** The cursor's controls, pin state machine included; see [CursorControls]. */
    val cursor: CursorControls,
    val onGestureStart: () -> Unit,
    val onGestureEnd: () -> Unit,
    /** Whether the graph is refusing touch entirely; see [GraphLockControl]. */
    val graphLocked: Boolean,
    val onToggleGraphLock: () -> Unit,
    /** Whether Select is armed, and the selection callbacks; see `RegionSelect.kt`. */
    val regionSelecting: Boolean = false,
    val onToggleRegionSelect: () -> Unit = {},
    val onRegionDrag: (Double, Double) -> Unit = { _, _ -> },
    val onRegionCommit: (Double, Double) -> Unit = { _, _ -> },
    val onRegionCancel: () -> Unit = {},
    val onRegionTap: () -> Unit = {},
)

/**
 * Phone / portrait: everything stacked, chart taking the remaining height.
 *
 * The Cursor group rides in the chip cluster here, always. There is no column
 * beside the chart to put a section in, and the height it would cost is the
 * chart's - in this layout the vertical axis is the scarce one and it belongs to
 * the plot. It used to be left out of this layout altogether and offered only in
 * the options sheet, which is why the crosshair controls were not merely cut off
 * in portrait but entirely absent.
 */
@Composable
internal fun StackedDashboard(
    modifier: Modifier,
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    render: ChartRender,
    touch: ChartTouchCallbacks,
    onChartSize: (IntSize) -> Unit,
    onTimeFlag: () -> Unit,
    onOpenOptions: () -> Unit,
    onReadoutsBottom: (Dp) -> Unit = {},
) {
    val chooser = rememberAveragingChooserState()
    Column(
        modifier = modifier
            .closeAveragingChooserOnTouchOutside(chooser)
            .fillMaxSize()
            .padding(12.dp),
    ) {
        StatusHeader(state)
        // Everything above the plot shares one [AveragingChooserHost], because
        // the chooser is allowed over any of it and over none of the plot: the
        // host's bottom edge is the boundary the drawer cannot cross. See
        // [AveragingChooserHost] for why the rule is structural.
        AveragingChooserHost(chooser, state, viewModel) {
            ReadoutCells(
                state, viewModel, Modifier.padding(top = 6.dp),
                onBottomInWindow = onReadoutsBottom,
            )
            // roomToWrap stays false here on purpose: stacked means portrait, where
            // the vertical axis belongs to the chart, and a wrapped preset line is a
            // line taken straight from the plot. The strips say when they hold more.
            //
            // The controls sit inside [SteadyControls] rather than directly in this
            // column for a harder version of the same reason: every row here is paid
            // for out of the chart's height, and the selection's actions row comes
            // and goes with chart state - so its arrival must never be the plot's
            // to pay for. The graph must never change size in a given orientation.
            SteadyControls(
                state, viewModel,
                cursor = touch.cursor,
                regionSelecting = touch.regionSelecting,
                onToggleRegionSelect = touch.onToggleRegionSelect,
                onOpenOptions = onOpenOptions,
                chooser = chooser,
            )
        }
        PmChart(
            render = render,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
                .onSizeChanged(onChartSize).testTag("plot"),
            onZoom = viewModel::zoomBy,
            onPan = viewModel::panBy,
            onZoomY = viewModel::zoomYBy,
            onPanY = viewModel::panYBy,
            onGestureStart = touch.onGestureStart,
            onGestureEnd = touch.onGestureEnd,
            onWidthChanged = viewModel::setChartWidthPx,
            onInspect = touch.onInspect,
            cursorMovable = touch.cursor.movableByTouch,
            cursorPinnedToTime = touch.cursor.pin is CursorPin.ToTime,
            onPinCursorToTime = touch.cursor.onPinToTime,
            graphLocked = touch.graphLocked,
            regionSelecting = touch.regionSelecting,
            onRegionDrag = touch.onRegionDrag,
            onRegionCommit = touch.onRegionCommit,
            onRegionCancel = touch.onRegionCancel,
            onRegionTap = touch.onRegionTap,
            chrome = plotChromeFor(touch, viewModel, onTimeFlag),
        )
    }
}

/**
 * The plot's on-graph controls, wired once for both layouts - see
 * [PlotChromeCallbacks] for what lives on the plot and why.
 */
private fun plotChromeFor(
    touch: ChartTouchCallbacks,
    viewModel: DashboardViewModel,
    onTimeFlag: () -> Unit,
): PlotChromeCallbacks = PlotChromeCallbacks(
    onToggleGraphLock = touch.onToggleGraphLock,
    cursorEnabled = touch.cursor.enabled,
    onToggleCursor = touch.cursor.onToggleEnabled,
    follows = touch.cursor.follows,
    onToggleFollows = touch.cursor.onToggleFollows,
    onCycleTicks = viewModel::cycleTickDensity,
    onSetXLocked = viewModel::setXLocked,
    onReleaseX = viewModel::releaseXRange,
    onSetYLocked = viewModel::setYLocked,
    onReleaseY = viewModel::releaseYOverride,
    onTimeFlag = onTimeFlag,
)

/** [SteadyControls]' two slots: the copy it measures by, and the one it shows. */
private enum class ControlsSlot { Probe, Shown }

/**
 * The stacked layout's controls, in a viewport whose height the transient
 * rows cannot change.
 *
 * ## The fault this exists for
 *
 * In the stacked layout the chart takes whatever height the rows above it
 * leave, so any row that appears is paid for out of the plot. The axis-hold
 * rows were the first tenants caught doing that - a real hand's pinch always
 * engages a hold, so *zooming shrank the graph* - and they have since moved
 * onto the plot itself, at the axes they hold (see PlotChrome.kt). What
 * remains transient here is the selection's actions row, and the rule is the
 * captain's and absolute either way: the graph never changes size while the
 * screen keeps its orientation. A zoom changes what is drawn inside the
 * plot, never the size of the surface it is drawn on. `ChartSizeStableTest`
 * measures exactly that, across the real layout.
 *
 * ## How the height is chosen
 *
 * The viewport is exactly as tall as the *steady* controls - a probe copy
 * composed without the transient rows, measured and never placed, the same
 * trick [PanelFit] uses and for the same reason: the right number is the one
 * this frame's real content wants, not a guess recorded some other frame.
 * With nothing transient showing, the shown content is the probe, so nothing
 * scrolls, nothing is blank, and the layout is pixel-for-pixel what it
 * always was.
 *
 * The selection's actions row ([RegionActions]) joins the top of the viewport
 * when a selection exists - first, so it surfaces where the eye already is -
 * and the steady strips slide down within it, the column scrolling as the way
 * to whatever no longer fits. Nothing about the selection may sit over the
 * plot - the statistics themselves are plot text for exactly that reason -
 * and the row is absent from the probe, so the plot's size never moves when
 * it appears.
 *
 * The probe's semantics are cleared for the reason [PanelFit] documents: an
 * unplaced node still reaches the screen reader, and every control offered
 * twice with one copy pointing at nothing is its own fault.
 */
@Composable
private fun SteadyControls(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    cursor: CursorControls?,
    regionSelecting: Boolean = false,
    onToggleRegionSelect: () -> Unit = {},
    onOpenOptions: () -> Unit = {},
    chooser: AveragingChooserState = rememberAveragingChooserState(),
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val steady = subcompose(ControlsSlot.Probe) {
            Column(modifier = Modifier.clearAndSetSemantics {}) {
                ChartControls(
                    state, viewModel,
                    cursor = cursor,
                    regionSelecting = regionSelecting,
                    onToggleRegionSelect = onToggleRegionSelect,
                    onOpenOptions = onOpenOptions,
                    chooser = chooser,
                )
            }
        }.maxOfOrNull { it.measure(unbounded).height } ?: 0
        val height = constraints.constrainHeight(steady)
        val placeables = subcompose(ControlsSlot.Shown) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                RegionActions(state, viewModel, Modifier.padding(top = 2.dp))
                ChartControls(
                    state, viewModel,
                    cursor = cursor,
                    regionSelecting = regionSelecting,
                    onToggleRegionSelect = onToggleRegionSelect,
                    onOpenOptions = onOpenOptions,
                    chooser = chooser,
                )
            }
        }.map { it.measure(constraints.copy(minHeight = height, maxHeight = height)) }
        layout(constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0), height) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * Tablet / landscape: a controls panel on the left, the chart filling the right.
 *
 * The panel is the same 300 dp column on every landscape screen but nothing like
 * the same height: about 880 dp on the tablet and about 330 on a phone. So it does
 * not decide its shape from the device - [PanelFit] measures what the controls
 * want against what the column has and gives the Cursor group a headed section
 * only where there is genuinely room for one, moving it up into the chip cluster
 * where there is not.
 *
 * The column scrolls as its last resort. A control panel that has run out of room
 * must reflow first and scroll second, but it must never do the third thing, which
 * is to hand its last child eight dp and let a sentence end in mid-air.
 */
@Composable
internal fun WideDashboard(
    modifier: Modifier,
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    render: ChartRender,
    touch: ChartTouchCallbacks,
    onChartSize: (IntSize) -> Unit,
    onTimeFlag: () -> Unit,
    onOpenOptions: () -> Unit,
    onReadoutsBottom: (Dp) -> Unit = {},
) {
    val chooser = rememberAveragingChooserState()
    Column(
        modifier = modifier
            .closeAveragingChooserOnTouchOutside(chooser)
            .fillMaxSize()
            .padding(12.dp),
    ) {
        StatusHeader(state)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The chooser may overlap anything in this column and nothing
            // outside it - the plot is beside the panel here, and the host's
            // width is the panel's, so the drawer cannot reach it. The host
            // wraps [PanelFit] rather than living inside it, because the
            // compact branch scrolls and a scrolling column clips: a drawer
            // anchored to the bottom of scrolled *content* could sit below the
            // viewport, opened and invisible. Outside it, the anchor is the
            // panel as seen.
            AveragingChooserHost(chooser, state, viewModel, Modifier.width(300.dp)) {
                PanelFit { compact ->
                    // The selection's actions lead the column - the statistics
                    // themselves are plot text, and nothing about the selection
                    // may sit over the plot. First, because anywhere lower in
                    // this column starts under the fold on a phone in landscape.
                    // The column's width is fixed, so the row's arrival costs the
                    // chart nothing; [PanelFit] reflows and scrolls around it.
                    RegionActions(state, viewModel, Modifier.padding(bottom = 4.dp))
                    ReadoutCells(state, viewModel, onBottomInWindow = onReadoutsBottom)
                    // The wrap question is the fit question: [PanelFit]'s probe
                    // measures the roomy shape with every value chip wrapped into
                    // view, so !compact means precisely "wrapping shows everything
                    // in the height there is". Where it does not, the groups keep
                    // their one-line strips and the column scrolls as its last
                    // resort.
                    ChartControls(
                        state, viewModel,
                        cursor = touch.cursor.takeIf { compact },
                        roomToWrap = !compact,
                        regionSelecting = touch.regionSelecting,
                        onToggleRegionSelect = touch.onToggleRegionSelect,
                        onOpenOptions = onOpenOptions,
                        chooser = chooser,
                    )
                    // Where the panel runs out of controls before it runs out of
                    // height, this is the landscape space that was going spare.
                    if (!compact) {
                        CursorSection(touch.cursor, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            PmChart(
                render = render,
                modifier = Modifier.fillMaxHeight().weight(1f).onSizeChanged(onChartSize)
                    .testTag("plot"),
                onZoom = viewModel::zoomBy,
                onPan = viewModel::panBy,
                onZoomY = viewModel::zoomYBy,
                onPanY = viewModel::panYBy,
                onGestureStart = touch.onGestureStart,
                onGestureEnd = touch.onGestureEnd,
                onWidthChanged = viewModel::setChartWidthPx,
                onInspect = touch.onInspect,
                cursorMovable = touch.cursor.movableByTouch,
                cursorPinnedToTime = touch.cursor.pin is CursorPin.ToTime,
                onPinCursorToTime = touch.cursor.onPinToTime,
                graphLocked = touch.graphLocked,
                regionSelecting = touch.regionSelecting,
                onRegionDrag = touch.onRegionDrag,
                onRegionCommit = touch.onRegionCommit,
                onRegionCancel = touch.onRegionCancel,
                onRegionTap = touch.onRegionTap,
                chrome = plotChromeFor(touch, viewModel, onTimeFlag),
            )
        }
    }
}

@Composable
internal fun ReadoutCells(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    /**
     * Where this row's bottom edge sits in the window, reported so the options
     * menu can stop below it - the captain's rule is that the particle
     * concentration must never be invisible, and this is what removes the one
     * exception the menu used to be. See [optionsSheetHeightFor].
     */
    onBottomInWindow: (Dp) -> Unit = {},
) {
    val density = LocalDensity.current
    // PM1.0 and PM2.5 only. The SPS30 sizes particles optically below ~1.3um and
    // derives the coarser cuts from that distribution, so in clean air
    // pm2_5 == pm4_0 == pm10 exactly - three identical numbers read as a fault
    // rather than a result. The chart now agrees (see [PlottedSeries]); all four
    // remain recorded, stored and exported.
    //
    // These double as the plot's series toggles. They were large panels showing
    // one number each, which is a poor trade for scarce main-screen height; as
    // buttons they earn the space, and putting show/hide here rather than buried
    // in the options menu matches where the user is looking when they want it.
    // The connection detail's anchor is this row, not the dot inside it.
    //
    // The dot is centred in the row, so the row goes on below the dot's own
    // box - and a popup hung under the *dot* landed in that remaining strip,
    // which is where the tiles draw their `ug/m3` unit lines. Measured on the
    // captain's tablet on 2026-08-12: the detail clipped both unit lines
    // through the middle of the glyphs. The captain's rule is absolute - the
    // particle concentration must never be invisible - so the anchor is the
    // row's own height, which by construction is below every tile. What it may
    // still cover is the controls beneath, which he allows.
    var detailShown by remember { mutableStateOf(false) }
    var rowHeightPx by remember { mutableStateOf(0) }
    val indicator = statusIndicator(state)
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { c ->
                onBottomInWindow(with(density) { c.boundsInWindow().bottom.toDp() })
            }
            .onSizeChanged { rowHeightPx = it.height },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (field in PlottedSeries.FIELDS) {
                ReadoutCell(
                    label = SeriesColors.label[field] ?: field,
                    value = state.latest[field],
                    color = SeriesColors.color[field] ?: Color.Gray,
                    shown = field in state.config.visiblePm,
                    style = state.config.readoutStyle,
                    onClick = { viewModel.toggleSeries(field) },
                    modifier = Modifier.weight(1f),
                )
            }
            // The connection dot's standalone spot, upper right, while all is
            // quiet - the row it used to occupy went back to the plot. Beside
            // the readings whose freshness it vouches for, and worth its
            // ~34 dp of tile width: see [ConnectionDotCorner].
            if (indicator is StatusIndicator.Dot) {
                ConnectionDotCorner(
                    transport = indicator.transport,
                    onTap = { detailShown = !detailShown },
                )
            }
        }
        if (detailShown && indicator is StatusIndicator.Dot) {
            ConnectionDetailPopup(
                state = state,
                belowPx = rowHeightPx,
                onDismiss = { detailShown = false },
            )
        }
    }
}

/**
 * The one way into every setting in the app.
 *
 * The three-dot overflow glyph and nothing else: "more menus" is what those
 * dots have meant on every phone for a decade, so the word "Options" (and the
 * gear that used to sit beside it) bought no meaning for the width it spent.
 * The word survives as the [OPTIONS_BUTTON_DESC] the screen reader speaks.
 *
 * Bare, deliberately: the grey disc it used to float on read as clutter, not
 * as a button. What says "tappable" now is the glyph itself - those dots are
 * an affordance on their own everywhere - and where it sits: at the far end of
 * the control cluster's row, across a gap wide enough that it cannot be read
 * as a fifth member of that group. See [ChartControls] for the row.
 */
@Composable
private fun OptionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "⋮",
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Clickable before padding, so the padding is part of the touch target
        // rather than a dead ring round a glyph-sized one.
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = OPTIONS_BUTTON_DESC }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** What a screen reader (and a UI test) calls the three-dot menu button. */
internal const val OPTIONS_BUTTON_DESC = "Options"

/**
 * The chip cluster at the top of the controls, and the window group under it.
 *
 * ## What is grouped, and what is not
 *
 * The window sizes answer one question - how much time is on screen - and they
 * are boxed and named for it: see [ControlGroup] for the group's whole shape,
 * the label and the group's mode buttons on a top row, the value buttons in a
 * thin box under them, an outer line around the lot. Pin and Unpin are the
 * window group's mode buttons - they change what the window is doing, not which
 * size it is - so they sit beside the word "window" rather than among the sizes.
 *
 * How finely the plot is drawn used to be the second named group, a whole row
 * of averaging buttons. It is now the single [AverageButton] in the cluster,
 * with the hand-picked sizes behind a press-and-hold - the row's height went
 * back to the plot, which is what the captain asked of it.
 *
 * The ungrouped chips lead, in a cluster of their own - Select and Average
 * today. The graph lock, the crosshair toggle and Follow used to lead this
 * cluster and now live on the plot itself, each at the thing it affects
 * (see PlotChrome.kt and docs/PLOT_FURNITURE.md); the line their wrap
 * borrowed went back to the plot. What [cursor] still brings here is the pin
 * family, below the groups - see [CursorPinChips] for why it must not spend
 * the cluster's height - and its explanatory sentence under the cluster.
 *
 * The cluster's row has one more thing on it that is not a control chip at all:
 * the three-dot [OptionsButton], pushed to the far end. It shares the line
 * because the row that used to hold it held nothing else worth a line - the
 * battery and the storage map moved into the menu, and the height went to the
 * plot. The wide gap between the chips and the dots is deliberate, and it is
 * the only separator: it is what says the dots are not a third chip.
 *
 * ## [roomToWrap], or which shape the value buttons take
 *
 * Each group's values are laid out by [GroupValues], which has two shapes: every
 * button visible, wrapping onto as many lines as it takes, or one line with the
 * overflow strip. [roomToWrap] says which, and the caller answers it from
 * measurement rather than from the device: the landscape panel passes the result
 * of [PanelFit]'s probe - whether everything, wrapped, genuinely fits the height
 * - and the stacked portrait layout always passes false, because there the
 * vertical axis belongs to the chart and lines spent on chips are lines taken
 * from the plot. See [GroupValues] for why both shapes have to exist.
 *
 * A group's chips are a [ControlChips] flow and not a `Row`: chips get added to
 * this screen steadily and a flow makes that a safe edit at every width, boxed or
 * not. Add new ones inside the flow of whichever group they belong to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartControls(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    cursor: CursorControls? = null,
    roomToWrap: Boolean = false,
    regionSelecting: Boolean = false,
    onToggleRegionSelect: () -> Unit = {},
    onOpenOptions: () -> Unit = {},
    /** The averaging chooser's visibility, owned by the layout hosting it. */
    chooser: AveragingChooserState = rememberAveragingChooserState(),
) {
    val chart = state.chart
    val viewMode = chart?.viewMode ?: ViewMode.LIVE
    val isPin = viewMode == ViewMode.PIN
    val isAll = viewMode == ViewMode.ALL
    val isLive = viewMode == ViewMode.LIVE

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        // Bottom, not centre: should the chip flow ever wrap again, the dots
        // belong on the line where the flow would place its next chip, not
        // floating centred between lines. Today it does not wrap: the lock,
        // crosshair and Follow chips moved onto the plot itself and the
        // derived-times flag into its top strip (see PlotChrome.kt and
        // docs/PLOT_FURNITURE.md), which is what gave the borrowed second
        // line back to the plot.
        verticalAlignment = Alignment.Bottom,
    ) {
        ControlChips(modifier = Modifier.weight(1f)) {
            // Select stays a chip here rather than joining the plot's own
            // icons: it arms a mode whose explanation is a sentence, and
            // sentences live on the controls' ground ([ControlNote]).
            RegionSelectControl(selecting = regionSelecting, onToggle = onToggleRegionSelect)
            // Average is about what the plot shows, but a single toggle does
            // not earn a boxed group of its own - and its chooser is a drawer
            // that may overlap the controls and never the plot, so it cannot
            // move onto the graph. See [AverageButton].
            AverageButton(state, viewModel, chooser)
        }
        // The way into the menu, at the far end of the cluster's row. The gap
        // the weight above leaves is deliberate and is the whole separator: it
        // is what keeps these dots from reading as a third member of the chip
        // group, without spending a divider line or a box on saying so.
        OptionsButton(onClick = onOpenOptions, modifier = Modifier.padding(start = 12.dp))
    }
    if (regionSelecting) {
        ControlNote(REGION_SELECT_NOTE, modifier = Modifier.padding(top = 4.dp))
    }
    cursor?.let {
        ControlNote(cursorNote(it.follows, it.pin), modifier = Modifier.padding(top = 4.dp))
    }
    // Pin and Unpin ride beside the word "window": they are the group's mode
    // buttons, not one of its sizes, and the label row is where a mode button
    // says so - see [ControlGroup]. Auto stays with the sizes it picks between:
    // it is one of the window choices, first in the values so it cannot scroll
    // out of reach when the strip is the shape in use.
    ControlGroup(
        WINDOW_GROUP_LABEL,
        modifier = Modifier.padding(top = 6.dp),
        labelChips = {
            LongPressChip(
                selected = isPin,
                label = "Pin",
                onClick = viewModel::onPinTap,
                onLongClick = viewModel::onPinLongPress,
                cycleStops = chart?.pinCycleStops ?: 0,
                cycleAt = chart?.pinCycleAt ?: 0,
            )
            // The way out, in one press. Shown only while there is something to
            // leave: a chip that does nothing when tapped teaches the user that
            // the app ignores them, which is worse than an absent one.
            if (isPin) {
                FilterChip(
                    selected = false,
                    onClick = viewModel::onUnpin,
                    label = { Text("Unpin") },
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Leave Pin and go back to the live view, without stepping " +
                                "through the saved marks."
                    },
                )
            }
        },
    ) {
        GroupValues(
            wrap = roomToWrap,
            what = "window sizes",
            fixed = {
                FilterChip(
                    selected = isAll && chart?.autoInterval == true,
                    onClick = viewModel::autoSet,
                    label = { Text("Auto") },
                )
            },
        ) {
            for (preset in state.config.windowPresets) {
                // Only the legacy "all" token has no chip - Auto absorbed it.
                // A fixed span of any size gets its button; "≥ 48 h means all"
                // used to swallow every long window here.
                if (preset.isAll) {
                    continue
                }
                val selected = isLive && chart != null &&
                    kotlin.math.abs(chart.windowSeconds - preset.seconds) <
                    preset.seconds * 0.02
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.onWindow(preset.seconds) },
                    label = { Text(preset.label) },
                )
            }
        }
    }
    // The cursor's pin family rides below the groups, not in the cluster at the
    // top: four more chips up there pushed the window buttons - the panel's
    // most-pressed controls - under the fold on a phone in landscape. Down
    // here the column's scroll reaches them, and the everyday pin has the
    // plot's corner button anyway. See [CursorPinChips].
    cursor?.let {
        ControlChips(modifier = Modifier.padding(top = 6.dp)) { CursorPinChips(it) }
    }
    // The axis holds are not here: each lives on the plot at the axis it
    // holds, which is both the captain's principle (a control belongs where
    // the thing it affects is) and what stopped their arrival being paid for
    // out of the chart's height. See `AxisHoldIcons` in PlotChrome.kt.
}

/**
 * Every word this screen uses to hold something still or let it go, in one place.
 *
 * ## Why they are gathered rather than written at their chips
 *
 * There are now three holds on this screen - the whole graph against touch, the y
 * axis at a range the fingers chose, and the time axis at a range they framed -
 * and each of them has a way out. Written at their own call sites, three of those
 * ways out are all naturally called "Release", which is the collision this app
 * has already had once: two chips a few millimetres apart both offering to
 * release something, with only their position to say which, is a control the user
 * has to experiment on - and experimenting costs them the very thing they set up.
 *
 * So each label names its object, the set is written down together where the
 * repetition is visible, and `AxisHoldFamilyTest` fails if any two of them ever
 * say the same thing again. The two axis holds deliberately *do* share their
 * shape and their lock word: they are the same idea applied to the two axes, and
 * making them look unrelated would be the opposite mistake. What tells them apart
 * is the heading beside them and what their release chip names.
 *
 * The controls that speak these words are icons on the plot now - the graph
 * lock's padlock at the top-right corner, each axis hold's pair at the axis
 * it holds; see `GraphLockIcon` and `AxisHoldIcons` in PlotChrome.kt - so the
 * whole family is spoken rather than shown. It stays in this set on the same
 * terms: a screen reader hears all four ways out in one sitting, so they must
 * not collide there either.
 */
internal object AxisHoldLabels {
    const val LOCK_GRAPH = "Lock graph"
    const val RELEASE_GRAPH = "Release graph"
    const val Y_HEADING = "Y axis"
    const val X_HEADING = "X axis"
    const val RELEASE_Y = "Release Y scale"
    const val RELEASE_X = "Release X range"
    const val LOCK = "Lock"
    const val LOCKED = "Locked"
    const val HELD_AGAINST_TOUCH = "held against stray touches"
    const val Y_HELD = "autoscale is holding your range"
    const val X_HELD = "new readings are not scrolling it"
}

/**
 * A group's value buttons, in whichever of two shapes the space calls for.
 *
 * ## The two shapes, and why both have to exist
 *
 * With [wrap], every value is a chip in a [ControlChips] flow: the line breaks
 * where the width runs out and the buttons use the lines below, so all of them
 * are simply visible. That is the shape for a panel with height to spare - the
 * tablet's landscape column, where the captain found the sizes he wanted hidden
 * behind an overflow mark while most of the column stood empty.
 *
 * Without it, the values share one line: the [fixed] chips, then the rest in a
 * [ScrollingChips] strip that says with a mark when it is hiding some. That is
 * the shape for a panel whose height is spoken for - portrait, where every line
 * is taken from the chart, and the landscape column [PanelFit] has already
 * measured as too short for the wrapped shape. The strip is not removed by the
 * wrapped shape; it is the fallback for exactly the screens that cannot afford
 * the lines.
 *
 * ## What [fixed] means
 *
 * The chips that must never scroll out of reach - Auto on the window group,
 * because it is the one button that picks a size by itself. In the strip shape
 * they are pinned outside the scrolling part, which is what makes a scrolled
 * strip survivable rather than a way to lose a control. In the wrapped shape
 * nothing can scroll out of reach, so they simply lead the flow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupValues(
    wrap: Boolean,
    what: String,
    fixed: @Composable () -> Unit,
    values: @Composable () -> Unit,
) {
    if (wrap) {
        ControlChips {
            fixed()
            values()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fixed()
            ScrollingChips(what = what, modifier = Modifier.weight(1f)) { values() }
        }
    }
}

/**
 * A chip that supports both a tap and a long-press (used for Pin).
 *
 * [cycleStops] and [cycleAt] add the row of marks that says where the Pin
 * button's tap cycle stands - see [PinCycleDots]. Zero stops draws nothing, so
 * the chip is a plain chip everywhere else.
 *
 * ## Why it asks for the interactive minimum
 *
 * This is a hand-built `Surface` and not a `FilterChip`, because a chip has no
 * long press. It has to match one all the same, and the part that is easy to miss
 * is not the height of the body - both are 32 dp - but the box around it: a
 * `FilterChip` claims the 48 dp interactive minimum and centres its body inside
 * it, so a bare 32 dp `Surface` next to one is 8 dp shorter and, in a container
 * that lines its children up by their tops, sits 8 dp high. That is exactly what
 * the captain saw: Pin riding above Auto in both orientations, for no reason he
 * could see, which reads as a slip rather than as a decision.
 *
 * Asking for the same minimum here fixes it at the source rather than at the one
 * call site - the alignment then holds in whatever this chip is put inside next,
 * which for a panel that gains a control a week is the property worth having.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LongPressChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cycleStops: Int = 0,
    cycleAt: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = content,
        border = BorderStroke(1.dp, if (selected) scheme.secondaryContainer else scheme.outline),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (cycleStops > 0) PinCycleDots(cycleStops, cycleAt, content)
        }
    }
}

/**
 * Where the Pin button's tap cycle stands: one mark per stop, the current one
 * solid, and the last stop drawn as a play mark because it is Live.
 *
 * A cycle whose length and position are invisible is a cycle the user has to
 * count taps to navigate, which is what made the old repeat-tapping something to
 * be remembered rather than seen. Four or five dots cost almost no width and
 * answer both questions the button raises at once - how many framings are there
 * to come back to, and which one am I looking at.
 */
@Composable
private fun PinCycleDots(stops: Int, at: Int, color: Color) {
    val dot = 5.dp
    val gap = 3.dp
    Canvas(modifier = Modifier.size(width = dot * stops + gap * (stops - 1), height = dot)) {
        val d = size.height
        val step = d + gap.toPx()
        for (i in 0 until stops) {
            val cx = d / 2f + i * step
            val cy = d / 2f
            val c = if (i == at) color else color.copy(alpha = 0.32f)
            if (i == stops - 1) {
                drawPath(
                    Path().apply {
                        moveTo(cx - d * 0.40f, cy - d * 0.52f)
                        lineTo(cx + d * 0.52f, cy)
                        lineTo(cx - d * 0.40f, cy + d * 0.52f)
                        close()
                    },
                    c,
                )
            } else {
                drawCircle(c, d / 2f, Offset(cx, cy))
            }
        }
    }
}

/**
 * Enough decimals for the range to be edited, and no more.
 *
 * Indoor air sits around 1-3 ug/m3 and moves in hundredths, so a range of 2.85
 * to 3.15 written to one decimal opens as "2.9" and "3.2" - numbers the user
 * never chose, and which move the axis the moment Apply is pressed.
 */
private fun formatYField(value: Double, span: Double): String = when {
    span >= 10.0 -> "%.0f".format(value)
    span >= 1.0 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}

@Composable
private fun YRangeDialog(
    initialMin: String,
    initialMax: String,
    onDismiss: () -> Unit,
    onApply: (Double, Double) -> Unit,
) {
    var minText by remember { mutableStateOf(initialMin) }
    var maxText by remember { mutableStateOf(initialMax) }
    val lo = minText.toDoubleOrNull()
    val hi = maxText.toDoubleOrNull()
    val valid = lo != null && hi != null && lo < hi
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Y range (${SeriesColors.unit})") },
        text = {
            Column {
                OutlinedTextField(minText, { minText = it }, label = { Text("min") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(maxText, { maxText = it }, label = { Text("max") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onApply(lo!!, hi!!) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What the top of the dashboard says about the connection: the full banner, or
 * the small dot that stands in for it while there is nothing to say.
 *
 * The dot appears in exactly two situations, both quiet by nature: connected
 * and running (green, ringed by the transport), and disconnected *by choice*
 * (red, no ring - [ConnectionMode.NONE] is a state the user put the app in, and
 * a banner restating their own decision at them is noise). Everything else -
 * connecting, switching, searching, a failed search, a stalled stream, a
 * reconnect, a host that announced shutdown - keeps the expanded banner: those
 * are the moments the words are for, and a genuine loss of connection announced
 * only by a dot turning red would be the frozen-plot-with-a-confident-banner
 * failure in a new coat. Loud on trouble, silent when healthy.
 */
internal sealed interface StatusIndicator {
    /** Something is happening or wrong; the banner must say it in words. */
    data object Expanded : StatusIndicator

    /** All quiet; the dot carries the state, its ring the transport. */
    data class Dot(val transport: DotTransport?) : StatusIndicator
}

internal fun statusIndicator(state: DashboardUiState): StatusIndicator {
    // The one thing that is not about the link at all: the sensor itself has
    // announced it is going away, which is a fact about the instrument and
    // needs a sentence, not a colour.
    if (state.sensorShutDown) return StatusIndicator.Expanded
    // Everything else is the dot, disconnected included - the captain's rule
    // stated on 2026-08-12: red not connected, green Wi-Fi, blue Bluetooth. It
    // replaces the reading this file used to take, that trouble earns a
    // full-width banner: searching for the sensor put a pale pink bar across
    // the whole screen and pushed the plot down a row, for a state that is
    // ordinary on a tablet that has just woken. The words are not lost - the
    // whole banner is one tap away on the dot, which is where a user who wants
    // to know *why* goes.
    //
    // Which colour is the honest one is decided by whether readings are
    // actually arriving, not by whether a socket is open: a stalled stream is
    // red, because a green dot beside a frozen number is exactly the
    // confident-banner-over-a-dead-plot failure this app has been bitten by
    // before.
    val live = state.connection is ConnectionState.Connected && !state.streamStalled
    if (!live) return StatusIndicator.Dot(null)
    // The transport that is *serving*, not the one that is configured: a
    // make-before-break switch runs both for a while, and the dot vouches for
    // the readings on screen, which arrive over the active link.
    val serving =
        if (state.switchingTo != null) state.activeMode else state.config.connectionMode
    return StatusIndicator.Dot(
        when (serving) {
            ConnectionMode.WIFI -> DotTransport.WIFI
            ConnectionMode.BLUETOOTH -> DotTransport.BLUETOOTH
            ConnectionMode.NONE -> null
        },
    )
}

/**
 * The dashboard's connection header: the [StatusBanner], and only while there
 * is something to say in words.
 *
 * While all is quiet the header renders **nothing at all** - the connection
 * dot lives beside the readout tiles instead (see [ConnectionDotCorner]), so
 * the row the dot used to occupy goes back to the plot. That is the captain's
 * instruction verbatim: the dot must not cost a row, and the particle
 * concentration must never be invisible. Trouble still gets the full banner,
 * because a genuine disconnection is words, never just a small red dot.
 */
@Composable
internal fun StatusHeader(state: DashboardUiState) {
    if (statusIndicator(state) is StatusIndicator.Expanded) {
        StatusBanner(state, onTap = null)
    }
}

/**
 * The connection dot, in its standalone spot at the readout row's end: one
 * solid colour - red not connected, green Wi-Fi, blue Bluetooth, see
 * [ConnectionDot] - beside the readings whose freshness it vouches for.
 *
 * It carries no detail of its own: what a tap reveals is
 * [ConnectionDetailPopup], hung off the readout *row* by [ReadoutCells], for
 * the reason recorded there.
 */
@Composable
private fun ConnectionDotCorner(
    transport: DotTransport?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = ConnectionDot.spec(transport)
    val described = when (transport) {
        null -> "Not connected"
        DotTransport.WIFI -> "Connected over Wi-Fi"
        DotTransport.BLUETOOTH -> "Connected over Bluetooth"
    }
    // The visible dot is a good deal smaller than its tap box, because a
    // target the size of the dot fails the too-small-to-use rule.
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 44.dp)
            .clickable(onClick = onTap)
            .testTag(CONNECTION_DOT_TAG)
            .semantics { contentDescription = "$described. Tap for connection details." },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(ConnectionDot.DIAMETER_DP.dp)
                .clip(CircleShape)
                .background(Color(spec.fillArgb)),
        )
    }
}

/**
 * What the dot says when it is asked: the old banner's whole detail -
 * transport, address and port, phase, sensor model, firmware - in a popup
 * that starts [belowPx] down from the readout row's top, which is to say
 * immediately under the row.
 *
 * The offset is the row's height and not the dot's, and that difference is
 * the whole point of this being a separate composable: see [ReadoutCells].
 * A tap on the revealed banner, or anywhere else, puts it away.
 */
@Composable
private fun ConnectionDetailPopup(
    state: DashboardUiState,
    belowPx: Int,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, belowPx),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 400.dp).testTag(CONNECTION_DETAIL_TAG),
        ) {
            StatusBanner(state, onTap = onDismiss)
        }
    }
}

internal const val CONNECTION_DOT_TAG = "connection-dot"
internal const val CONNECTION_DETAIL_TAG = "connection-detail"
internal const val STATUS_BANNER_TAG = "status-banner"

@Composable
private fun StatusBanner(state: DashboardUiState, onTap: (() -> Unit)?) {
    val (text, bg, fg) = bannerText(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .let { if (onTap != null) it.clickable(onClick = onTap) else it }
            .testTag(STATUS_BANNER_TAG)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        state.sensor?.let {
            Text(
                text = "SPS30 · fw ${it.firmware}",
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * The daemon state, with a countdown when the phase has a known length.
 *
 * Low-power mode spends most of its time doing nothing on purpose, so a bare
 * "sleeping" gives no clue whether the thing is working or wedged. The remaining
 * time is what makes the wait legible.
 */
@Composable
private fun phaseLabel(state: DashboardUiState, daemonState: String): String {
    @Suppress("UNUSED_EXPRESSION") state.clockTick // re-read each tick to recompose
    val name = when (daemonState) {
        Wire.STATE_WARMUP -> "warming up"
        Wire.STATE_SLEEPING -> "asleep"
        Wire.STATE_MEASURING ->
            if (state.profile?.dutyCycling == true) "averaging" else "measuring"
        else -> daemonState
    }
    val endsAt = state.phaseEndsAtMs ?: return name
    val remaining = ((endsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
    return "$name ${formatRemaining(remaining)}"
}

/** "8s", "1:23", "14:05" - a countdown, not a duration. */
private fun formatRemaining(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * What the sensor was doing when we last heard from it, phrased for the
 * disconnected banner.
 *
 * Said while the link is down, so every branch has to be true of a sensor we can
 * no longer see. That rules out the confident "still recording" this once used
 * for every case: measurement can be off, or the fan can be self-cleaning, and
 * claiming otherwise sends the user to look for a fault that is not there.
 * An unknown state gets no claim at all rather than an optimistic guess.
 */
internal fun lastKnownSensorActivity(daemonState: String?): String = when (daemonState) {
    Wire.STATE_MEASURING, Wire.STATE_WARMUP, Wire.STATE_SLEEPING ->
        "the sensor is still recording"
    Wire.STATE_PAUSED -> "measurement was off at the sensor"
    Wire.STATE_CLEANING -> "the sensor was cleaning its fan"
    Wire.STATE_CHECKING -> "the sensor host was still looking for the sensor"
    Wire.STATE_SHUTDOWN -> "the sensor was shutting down"
    else -> "not connected to the sensor"
}

/** Banner text with a background and a foreground that is legible on it. */
private data class BannerStyle(val text: String, val background: Color, val foreground: Color)

/** How a transport is named to the user. One place, so the two banners that
 *  mention one during a switch cannot word it differently. */
internal fun transportLabel(mode: ConnectionMode): String = when (mode) {
    ConnectionMode.BLUETOOTH -> "Bluetooth (BLE)"
    ConnectionMode.WIFI -> "Wi-Fi"
    ConnectionMode.NONE -> "Disconnected"
}

/** The scheme's matching "on" colour for one of the roles the banner uses. */
private fun onColorFor(scheme: androidx.compose.material3.ColorScheme, bg: Color): Color = when (bg) {
    scheme.primary -> scheme.onPrimary
    scheme.secondary -> scheme.onSecondary
    scheme.tertiary -> scheme.onTertiary
    scheme.error -> scheme.onError
    else -> scheme.onSurface
}

@Composable
private fun bannerText(state: DashboardUiState): BannerStyle {
    val scheme = MaterialTheme.colorScheme
    // A short note appended by every banner below, set only when a switch is under
    // way *and* the link being switched away from has its own problem to report.
    // The healthy case gets a sentence of its own instead - see below.
    var switching = ""
    // Each background gets its matching "on" colour rather than always onPrimary,
    // which was legible on the primary banner and marginal on the others.
    fun style(text: String, bg: Color) = BannerStyle(text + switching, bg, onColorFor(scheme, bg))
    // A host that announced its own shutdown outranks every other banner, including
    // the reconnect churn that follows it. The user watched the fan stop; a banner
    // saying "reconnecting" or "still recording" would be describing a machine that
    // is off. This clears itself the moment the host says hello again.
    if (state.sensorShutDown) {
        return style("Sensor shut down · safe to unplug · not recording", scheme.secondary)
    }
    // Lead every banner with the transport type so the connection is never ambiguous:
    // "Bluetooth (BLE)" over BLE, "Wi-Fi" over the network/TCP path.
    // Disconnected is a state the user chose, not a fault - say so plainly, and say
    // what it does NOT mean, because the obvious worry is that recording stopped.
    // What it does not mean depends on what the sensor was last seen doing: this
    // used to assert "still recording" unconditionally, which was a leftover from
    // when recording was the only thing it could be doing.
    if (state.config.connectionMode == ConnectionMode.NONE) {
        return style("Disconnected · ${lastKnownSensorActivity(state.daemonState)}", scheme.secondary)
    }
    // Mid-switch the config already names the transport the user chose while the
    // stream is still arriving over the old one. Every sentence below describes the
    // live connection, so it has to be named after the link actually carrying it -
    // otherwise the banner reads "Bluetooth (BLE) · connected 192.168.1.159:8730".
    val liveMode = if (state.switchingTo != null) state.activeMode else state.config.connectionMode
    val via = transportLabel(liveMode)
    state.switchingTo?.let { to ->
        // Ordered so it degrades correctly: the banner is one line with an ellipsis,
        // and what a user needs first during a switch is that nothing has stopped.
        // The progress clause is last because it is the part that can be lost.
        if (state.connection is ConnectionState.Connected) {
            // A Bluetooth search takes up to a minute; naming its progress is what
            // stops the wait after tapping Bluetooth reading as nothing having happened.
            val how = state.switchingDetail?.let { " · $it" } ?: ""
            return style("Still on $via · switching to ${transportLabel(to)}$how…", scheme.primary)
        }
        // The link being switched away from is not delivering either, so there is
        // nothing to reassure anyone about: let the banners below say what is wrong
        // with it, and note the switch briefly on the end.
        switching = " · switching to ${transportLabel(to)}…"
    }
    if (state.discovering) return style("$via · searching for the sensor…", scheme.tertiary)
    if (state.discoveryFailed) {
        // Say what was actually looked for. The transports word this themselves
        // ("no sensor found on the network", "Bluetooth is off"), because a wrong
        // guess here is worse than no guess - the user acts on it. Taken from the
        // latched verdict, not from `connection`, which reverts to Searching on
        // every retry sweep and made the sentence reword itself every few seconds.
        val what = state.discoveryDetail ?: "no sensor found"
        // A VPN that captures LAN traffic cannot be routed around from inside the
        // app - Android forbids it - so the only honest thing is to name it as a
        // likely cause rather than leaving the user to guess. Wi-Fi only: it has
        // nothing to do with a BLE scan, and offering it there would misdirect.
        val onWifi = liveMode == ConnectionMode.WIFI
        val hint = when {
            // The transport knows exactly what went wrong (a host that is full, say)
            // and its advice beats any guess made from the connection mode alone.
            state.discoveryAdvice != null -> " ${state.discoveryAdvice}"
            onWifi && WifiNetworkBinder.vpnActive(LocalContext.current) ->
                " A VPN may be blocking local network access - try turning it off, or use Bluetooth."
            onWifi -> " Check that the sensor host is powered on and on this Wi-Fi network."
            else -> " Check the sensor is powered on and in range."
        }
        return style("$via · $what.$hint", scheme.error)
    }
    // A connection that has stopped delivering outranks the fact that the socket
    // is nominally up. Saying "connected" over a plot that stopped minutes ago is
    // the one thing the banner must never do - the user cannot tell that from
    // clean air, and it is what turned every dropout into a permanent gap.
    if (state.streamStalled) {
        return style("$via · no readings arriving · reconnecting…", scheme.error)
    }
    return when (val c = state.connection) {
        is ConnectionState.Searching -> style("$via · ${c.detail}…", scheme.tertiary)
        is ConnectionState.NotFound -> style("$via · ${c.detail}", scheme.error)
        is ConnectionState.Connected -> {
            val st = state.daemonState?.let { " · ${phaseLabel(state, it)}" } ?: ""
            style("$via · connected ${c.endpoint}$st", scheme.primary)
        }
        is ConnectionState.Connecting -> style("$via · connecting to ${c.endpoint}…", scheme.tertiary)
        is ConnectionState.Reconnecting -> style("$via · reconnecting…", scheme.error)
        ConnectionState.Idle -> style("$via · starting…", scheme.tertiary)
    }
}

@Composable
private fun ReadoutCell(
    label: String,
    value: Double?,
    color: Color,
    shown: Boolean,
    style: com.sensirion.sps30.data.ReadoutStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two decimals - see [SeriesColors.reading], which is also what the ongoing
    // notification draws, so the tile and the notification never disagree about
    // the same reading. The tile is not claiming that precision about the air:
    // it is showing that the reading is alive.
    val reading = value?.let { SeriesColors.reading(it) } ?: "--"
    // The ring is the selection state: it says both "this trace is on the plot"
    // and "this is the colour to look for", so the tiles double as the chart's
    // legend without one being drawn. Shared by both layouts.
    val tile = modifier
        .clip(RoundedCornerShape(12.dp))
        .background(
            if (shown) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        )
        .border(
            // The unselected ring uses `outline` rather than `outlineVariant`:
            // the fainter tone sat so close to the tile's own fill that the "off"
            // state barely read as a button at all, while the "on" state was
            // unmistakable. Both states need to look deliberate.
            width = if (shown) 2.dp else 1.5.dp,
            color = if (shown) color else MaterialTheme.colorScheme.outline,
            shape = RoundedCornerShape(12.dp),
        )
        .clickable(onClick = onClick)

    val labelColor = if (shown) color else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (shown) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    // Everything is bold: at these sizes a regular weight goes thin against its
    // neighbours and reads as frail rather than as deliberately quieter. The
    // unit's demotion is carried by its light grey alone.
    val unitColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

    when (style) {
        com.sensirion.sps30.data.ReadoutStyle.STACKED -> Column(
            modifier = tile.padding(vertical = 5.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // lineHeight pinned close to the font size throughout. Default
            // leading is generous - sensible for paragraphs, wasteful for three
            // single-line labels stacked on top of each other, where it was
            // adding well over half the tile's height in empty space.
            Text(
                label,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor,
                maxLines = 1,
            )
            Text(
                reading,
                // Tabular figures: proportional digits shift the number sideways
                // as it updates once a second, and that wobble is what makes a
                // live readout tiring rather than glanceable.
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                fontSize = if (reading.length > 4) 24.sp else 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
            )
            Text(
                SeriesColors.unit,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                color = unitColor,
                maxLines = 1,
            )
        }

        com.sensirion.sps30.data.ReadoutStyle.INLINE -> Row(
            // Less padding vertically than horizontally: the number's line box
            // carries ascender and descender space it never fills, so equal
            // padding would push the tile taller than the glyphs justify.
            modifier = tile.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The unit is anchored to the right edge and the label-plus-reading
            // pair is centred in everything left of it. Laid out as a plain row,
            // a change in the reading's width shifted its neighbours around, so
            // the number drifted as digits came and went and the two tiles
            // stopped lining up with each other - which is exactly what makes
            // them comparable at a glance.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = labelColor, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        reading,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                        // Sized down only for the rare wide reading. Smoke events
                        // reach the hundreds, and a readout that clips its own
                        // number when it matters most is a poor trade.
                        fontSize = when (reading.length) {
                            in 0..4 -> 32.sp
                            5 -> 26.sp
                            else -> 21.sp
                        },
                        fontWeight = FontWeight.Bold,
                        color = valueColor,
                        maxLines = 1,
                    )
                }
            }
            Text(
                SeriesColors.unit,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = unitColor,
                maxLines = 1,
                // Pulled in off the right edge so the tile reads as one group.
                modifier = Modifier.padding(end = 11.dp),
            )
        }
    }
}

/**
 * What the inspection crosshair is anchored to.
 *
 * Which one is right depends on what the plot is doing, and both behaviours are
 * wanted:
 *
 *  - [AtTime] - the cursor holds its **instant**. As the range moves it travels
 *    with its own data point, staying on the event the user put it on. What a
 *    frozen view always gets, what a navigation gesture imposes for its duration,
 *    and what the plot's Pin button asks for.
 *  - [AtPlace] - the cursor holds its **place on the plot** and the data flows
 *    underneath it. The readout then keeps updating at a fixed age - park it at
 *    the right edge and it reports each new sample as it lands, the way `less +F`
 *    follows a file still being written. What any view running to now gets unless
 *    the crosshair is pinned.
 *  - [AtLatest] - Follow: the cursor holds neither, and is simply wherever the
 *    newest sample is.
 */
sealed interface ChartCursor {
    /** Fixed to an instant; moves across the plot as the range changes. */
    data class AtTime(val epoch: Double) : ChartCursor

    /** Fixed to a position across the plot, as a fraction of the visible span. */
    data class AtPlace(val fraction: Double) : ChartCursor

    /**
     * Fixed to the newest sample drawn, so the readout is a live display.
     *
     * Distinct from [AtPlace] at the right edge, which only *approximates* this
     * and only while the view is live: a place is a fraction of the span, so a
     * zoom or a pan moves it onto whatever sample now happens to lie under that
     * fraction, and averaging a wide window can leave the last bucket short of
     * the edge entirely. Naming the sample instead of a coordinate means the
     * readout is the newest reading in every view mode, and by construction it
     * is never off the plot.
     */
    data object AtLatest : ChartCursor
}

/** The epoch the cursor currently sits on, given what is on screen. */
fun ChartCursor.epochIn(data: ChartData?): Double? = when (this) {
    is ChartCursor.AtTime -> epoch
    is ChartCursor.AtPlace ->
        data?.let { it.start + fraction * (it.end - it.start) }
    // Last, not `data.end`: the window's right edge is a moment, and usually a
    // moment slightly after the newest sample rather than on it.
    ChartCursor.AtLatest -> data?.x?.lastOrNull()
}

/**
 * True when the view's right edge is *now*, so the range advances by itself as
 * readings arrive.
 *
 * The distinction the crosshair's anchor turns on, and the one that used to be
 * got wrong. [ViewMode.LIVE], [ViewMode.PIN] and [ViewMode.ALL] all end at now -
 * a live window slides, a pinned one grows rightwards from its mark, and the
 * session view grows from the session start - so in every one of them data flows
 * under a stationary crosshair. Only [ViewMode.LIVE_FROZEN] is a fixed range: it
 * moves when the user moves it and at no other time, which is what makes an
 * instant and a place the same thing there and leaves the Pin button nothing to
 * offer.
 *
 * Pin and Auto were classed with the frozen view as "showing the past". They are
 * not; they are showing the present arriving from a different origin.
 */
internal val ViewMode.rangeTracksNow: Boolean
    get() = this != ViewMode.LIVE_FROZEN

/**
 * Whether the crosshair should hold its place on the plot rather than the
 * instant it was put on.
 *
 * The default in any view whose range advances on its own (see [rangeTracksNow]):
 * the crosshair stays where the user put it on screen and the time under it
 * changes as new readings arrive. In a frozen view there is nothing to flow past
 * it, so it holds its instant and a zoom or a pan carries it along with its own
 * data point instead of sliding the data out from under it.
 *
 * [pinned] is the user overruling the inference altogether: any engaged
 * [CursorPin] anchors the cursor in its own terms, so the free anchor stops
 * following. Written as one function so the rule is one thing that can be
 * checked, rather than a condition spelled out wherever the anchor happens to
 * be needed.
 */
fun cursorFollowsNow(data: ChartData?, pinned: Boolean): Boolean =
    !pinned && (data == null || data.viewMode.rangeTracksNow)

/**
 * Anchor [epoch] the way the current view mode calls for, without moving it.
 *
 * "Without moving it" is the whole contract, and it is why an instant outside
 * the visible range stays an instant even in a live view: there is no place on
 * the plot that means "45 seconds before the left edge", so the only way to
 * express it as a fraction is to clamp - and clamping *moves the crosshair*, by
 * however far off-screen it had got. A zoom that shrinks the window past the
 * crosshair used to do exactly that, jumping it onto a sample the user never
 * chose. Held as an instant it keeps what it was put on, and the plot marks
 * which edge it went out by.
 */
fun anchorFor(epoch: Double, data: ChartData?, followsNow: Boolean): ChartCursor =
    if (followsNow && data != null && data.end > data.start &&
        epoch >= data.start && epoch <= data.end
    ) {
        ChartCursor.AtPlace((epoch - data.start) / (data.end - data.start))
    } else {
        ChartCursor.AtTime(epoch)
    }

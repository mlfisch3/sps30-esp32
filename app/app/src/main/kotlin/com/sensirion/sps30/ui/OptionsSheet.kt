package com.sensirion.sps30.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AppConfig
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.data.PlotStyle
import com.sensirion.sps30.data.ReadoutStyle
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.net.TransportAvailability
import com.sensirion.sps30.wire.Wire
import kotlin.math.roundToInt

/**
 * The height of the window this composable is being drawn in.
 *
 * ## Why the sheet states its own ceiling instead of inheriting one
 *
 * A `Modifier.verticalScroll` scrolls only when its viewport is smaller than its
 * content, and its viewport is whatever height its parent allowed it. A
 * `ModalBottomSheet` is the wrong parent to trust for that. It measures its
 * content, derives the resting position from the height that came back, and
 * places the sheet there - so a sheet that measures taller than the window is
 * placed with the excess *below the screen*, where the scrolling column cannot
 * reach it because as far as that column is concerned everything fits. That is
 * the shape of what the device pass found on the tablet: a menu with no scrollable
 * container in its tree, four swipes that moved nothing, and a guard plate whose
 * last line was readable while its bottom border was off the screen for good.
 *
 * Bounding the sheet by this removes the possibility rather than the symptom. The
 * sheet cannot be laid out taller than the window, so there is never an excess to
 * hang off the bottom; anything the tab cannot fit becomes scroll range in the one
 * column that is allowed to be shorter than its content. Under a parent that
 * already bounds the sheet to the window it changes nothing. [sheetHeight] is
 * where it is applied.
 *
 * `LocalConfiguration` and not the incoming constraints, because the incoming
 * constraints are the thing not being trusted. It is the window and not the
 * display, so on the versions that report it that way it already excludes the
 * system bars - which errs towards a slightly shorter sheet, never a taller one.
 */
@Composable
private fun windowHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp

/**
 * The strip of window the menu leaves uncovered at the top.
 *
 * The menu closes three ways and one of them is a tap outside it, so there has to
 * *be* an outside: a sheet drawn edge to edge has no scrim left to tap and quietly
 * loses one of its three exits. This is the width of a finger - the Material
 * interactive minimum - so the strip is a target and not a hairline. It also keeps
 * the sheet reading as a sheet, with the plot it belongs to still visible above it.
 */
internal val SCRIM_STRIP = 48.dp

/**
 * How tall the menu gets when the window has more height than it can use.
 *
 * Measured, not chosen - and measured as the *whole body*, because that is what
 * the number caps. The frame holds the "Options" header and the tab strip as well
 * as the showing tab, so the ceiling has to cover their height too, not just the
 * content's. Laid out at the widest a `ModalBottomSheet` allows itself
 * (`BottomSheetDefaults.SheetMaxWidth`, 640dp), the tallest of the six tabs -
 * Customize, since the window buttons field gained its caption - needs 1490dp
 * of body: chrome, content and [BOTTOM_CLEARANCE], with nothing left to scroll.
 * (Display, which the gap-marking switch grew to 1472dp, is next.) This is
 * that, with a little over for the text metrics to drift into. There is nothing
 * to be gained by going further: past this the menu would only be adding a band
 * of nothing under its longest tab.
 *
 * An earlier value was derived from the tallest tab's *content* alone (1364dp)
 * and forgot the chrome sharing the frame with it, which left the ceiling 38dp
 * short: the menu stopped growing while Display still scrolled, on a window that
 * had 113dp more to give. `OptionsSheetScrollTest.aTallEnoughWindowNeedsNoScrollingAtAll`
 * is the guard on this number and is what caught it.
 *
 * This is a *ceiling* and not the height. On every screen the app actually runs
 * on - now including a tablet held upright, whose 1445dp of room the tallest
 * tabs have outgrown - the window is shorter than this and the window wins, so
 * the tallest tab scrolls there by exactly the shortfall; see [sheetHeight].
 */
private val OPTIONS_SHEET_MAX_HEIGHT = 1506.dp

/**
 * The one height the menu is ever laid out at, whichever tab is showing.
 *
 * ## Why the frame is fixed rather than fitted
 *
 * A bottom sheet is placed at the height its content measures, and the six tabs
 * do not measure the same: on a tablet in portrait the tallest is 1364dp and the
 * shortest 640dp, so every tab switch used to move the whole surface - the top
 * edge, the header, the tab strip and the row under the finger - by most of a
 * screen. There is no arrangement of content that fixes that, because the cause is
 * the content: the frame has to stop being a function of it.
 *
 * So the sheet states one height and the tab scrolls inside it. The alternatives
 * were weighed and lost: a *minimum* height still grows for the tall tabs, and an
 * anchored top edge is the same thing said differently, since a bottom sheet's
 * top edge is derived from its height. Only a fixed frame makes the strip and the
 * header land in the same place every time - which is the whole point, because
 * they are what the finger is aiming at when it switches tabs.
 *
 * ## Why it is the window's height and not a number
 *
 * That fixed frame used to be a flat 640dp, and it was fixed at the size of the
 * *shortest* tab rather than of the room available. On a tablet upright that left
 * 853dp of unused screen above a menu the captain still had to scroll, which is
 * what he reported: scrolling that buys nothing, in a window that could simply have
 * been taller. So the height is taken from the space that is genuinely there -
 * everything but the [SCRIM_STRIP] - and capped where the content stops needing it.
 *
 * Note what this does *not* do: it never consults the tab. Both terms are
 * properties of the window and of the tallest content there could ever be, so the
 * answer is the same on all six tabs and switching between them still moves
 * nothing. A tab that does not fill the frame gets space below it - that is the
 * deliberate half of the bargain, and the alternative is the sheet jumping about
 * again.
 *
 * The floor at half the window is for a window too short to spare the strip at all;
 * it cannot be reached by any real screen, and it is there so the arithmetic can
 * never hand `Modifier.height` a negative number.
 */
@Composable
private fun sheetHeight(keepClearTop: Dp): Dp =
    optionsSheetHeightFor(windowHeight(), keepClearTop)

/**
 * The sheet-height arithmetic, pure so `OptionsSheetHeightTest` can hold it
 * to numbers.
 *
 * [keepClearTop] is how much window the menu must leave visible above itself -
 * the dashboard passes the measured bottom edge of the particle readouts, so
 * the menu stops below them and the live readings stay on screen while it is
 * open. That is the captain's 2026-08-12 instruction, and it removes the one
 * exception he had granted to "the particle concentration must never be
 * invisible" - an exception he was never thrilled about.
 *
 * What it costs, measured: the readout row's bottom sits ~88 dp from the
 * window's top on the phone against the 48 dp scrim strip, so the sheet loses
 * ~40 dp of height. Every real window is already shorter than the tallest
 * tab (1490 dp of body against 891 dp of phone), so the tall tabs scroll
 * inside the fixed frame today and simply scroll ~40 dp further; no new
 * behaviour appears, and the frame stays one height for all six tabs. The
 * scrim strip remains the floor so the tap-outside exit always has a target.
 */
internal fun optionsSheetHeightFor(window: Dp, keepClearTop: Dp): Dp {
    val clear = if (keepClearTop > SCRIM_STRIP) keepClearTop else SCRIM_STRIP
    val room = (window - clear).coerceAtLeast(window / 2)
    return minOf(room, OPTIONS_SHEET_MAX_HEIGHT)
}

/**
 * Empty space kept below the last control on every tab.
 *
 * Enough to clear a guard plate's border and its corner radius, so the bottom of
 * a tab reads as the bottom of a tab and not as a page that was cut. It is inside
 * the scrolling column, so it is scrollable content rather than a permanent band
 * of nothing: on a tab that fits, it is simply the sheet's bottom margin.
 */
internal val BOTTOM_CLEARANCE = 24.dp

/**
 * The six groups the options menu is divided into.
 *
 * Six is the ceiling, and each one has to carry its weight: tabs that are mostly
 * empty waste the width of the ones that are not. So Connection, which is three
 * buttons and a paragraph, does not get a tab - it sits at the top of Sensor,
 * which is where the thing it connects to lives. Measurement and Sensor share a
 * tab because they share a property that matters more than their subject matter:
 * everything on it acts on the physical instrument and is therefore behind a
 * safety.
 *
 * The ceiling was five until [WATCH] was added. Six is the honest limit of a
 * fixed tab strip on a phone - it divides the width equally, so six across the
 * 411dp the S24 gives the sheet is about 68dp each - and the price of the sixth
 * was paid in the fifth's label: "Customize" no longer fits that width, and
 * "Display" is a truer name for it anyway, since the whole rule of that tab is
 * that nothing on it changes what is recorded. A seventh group has to displace
 * one of these rather than join them, and would need the labels shorter again.
 *
 * ## The order
 *
 * The tabs run **outward from the plot in front of you**: what you are looking
 * at, then what the app watches on your behalf, then the instrument producing
 * it, then the record it leaves, then the readout at the far end of a one-way
 * link - with reference material last, because it is not a setting at all.
 *
 * Display leads because that is what the captain asked for, and the principle
 * agrees: it is the tab whose settings act on the thing already on the screen,
 * it is the one opened most, and it is the only one where nothing can be got
 * wrong. Nothing on it is behind a safety, and the two tabs that are - Sensor
 * and Data - sit in the middle of the strip rather than under the thumb that
 * just opened the menu.
 *
 * The order used to be a claim about frequency, and the claim did not survive
 * contact: Display was fifth.
 */
enum class OptionsTab(val label: String) {
    /** Everything that changes what you see and never what is recorded. */
    CUSTOMIZE("Display"),

    /** Alert rules, the excursions the controller preserved, and testing. */
    ALERTS("Alerts"),

    /** Connection, who has control, and everything behind a safety. */
    SENSOR("Sensor"),

    /** Getting readings off this device, and off the controller. */
    DATA("Data"),

    /**
     * Whether the watch on the far end of the relay is there, listening, and
     * being reached - and how often it is sent to. See [WatchSection] for why a
     * one-way relay needed a tab of its own.
     */
    WATCH("Watch"),

    /** Tutorial, FAQ, and the explanations that do not belong on the plot. */
    HELP("Help"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    tab: OptionsTab,
    onSelectTab: (OptionsTab) -> Unit,
    onSetY: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onSavePng: () -> Unit,
    onSelectMode: (ConnectionMode) -> Unit,
    onDismiss: () -> Unit,
    onOpenStorageMap: () -> Unit = {},
    onOpenPowerPanel: () -> Unit = {},
    /**
     * The measured bottom of the particle readouts, so the menu stops below
     * them and the live readings stay visible while it is open - see
     * [optionsSheetHeightFor]. Defaults to the scrim strip for callers with
     * no readouts above the sheet.
     */
    keepClearTop: Dp = SCRIM_STRIP,
) {
    // The menu closes three ways - the X, Back, and a tap outside the panel - and
    // all three go through onDismissRequest. What must *not* close it is a swipe
    // down over the menu itself: the sheet's top edge sits right under the status
    // bar, and a notification-shade pull that starts a few pixels low would throw
    // the menu away mid-edit.
    //
    // That used to be expressed as `confirmValueChange = { it != Hidden }`, which
    // is a veto on the hidden *state* rather than on the swipe - and a scrim tap
    // asks for exactly that state, so it silently vetoed the outside tap too and
    // left the X as the only way out. A bottom sheet is dragged away by nested
    // scroll, so the swipe is stopped at its source instead: see
    // [swallowLeftoverScroll] on the content below.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Eats whatever the menu's own scrolling leaves unconsumed, so nothing is ever
    // handed up to the sheet to drag it off screen. Pre-scroll is deliberately not
    // touched: that is the pass the menu's own scroll runs on.
    val swallowLeftoverScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                available
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        OptionsSheetBody(
            state = state,
            viewModel = viewModel,
            tab = tab,
            onSelectTab = onSelectTab,
            onSetY = onSetY,
            onToggleBackground = onToggleBackground,
            onExportCsv = onExportCsv,
            onSavePng = onSavePng,
            onSelectMode = onSelectMode,
            onDismiss = onDismiss,
            onOpenStorageMap = onOpenStorageMap,
            onOpenPowerPanel = onOpenPowerPanel,
            swallowLeftoverScroll = swallowLeftoverScroll,
            keepClearTop = keepClearTop,
        )
    }
}

/**
 * Everything inside the sheet: the header, the tab strip, and the scrolling body.
 *
 * Separate from [OptionsSheet] because a `ModalBottomSheet` puts its content in a
 * dialog window of its own, which a test cannot put a constraint on. This one can
 * be measured under any parent, which is what the scrolling tests need - see
 * `OptionsSheetScrollTest`.
 */
@Composable
internal fun OptionsSheetBody(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    tab: OptionsTab,
    onSelectTab: (OptionsTab) -> Unit,
    onSetY: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onSavePng: () -> Unit,
    onSelectMode: (ConnectionMode) -> Unit,
    onDismiss: () -> Unit,
    swallowLeftoverScroll: NestedScrollConnection,
    onOpenStorageMap: () -> Unit = {},
    onOpenPowerPanel: () -> Unit = {},
    /** Window kept visible above the sheet; see [optionsSheetHeightFor]. */
    keepClearTop: Dp = SCRIM_STRIP,
) {
    val config = state.config
    val measuring = state.daemonState == "measuring"
    val context = LocalContext.current

    var showShutdownConfirm by remember { mutableStateOf(false) }
    // `menuInteraction` is bumped by any touch in the sheet (below); each safety
    // below re-engages 20 s after it is opened with no further interaction
    // anywhere in the menu. See [SafetyState] for why a blocked press answers back.
    var menuInteraction by remember { mutableIntStateOf(0) }
    // Sensor-button safety.
    val sensorSafety = rememberSafetyState(menuInteraction)
    // Switching acquisition mode stops/starts and sleeps the physical sensor, so
    // it gets the same guard treatment as the Sensor buttons - and its own
    // toggle, because unguarding one group is not consent to unguard the other.
    val modeSafety = rememberSafetyState(menuInteraction)
    // Erasing is the one control here that destroys data, so it gets the same
    // guard as the two that only change behaviour. It lives on the Data tab, but
    // all three are held here so a touch anywhere in the menu - including on a
    // tab that is not showing them - keeps the timers honest.
    val eraseSafety = rememberSafetyState(menuInteraction)
    // And its own for backing everything up and clearing the sensor, rather than
    // sharing the erase guard: unguarding "remove what I already have" is not
    // consent to unguard "empty this sensor", and the second is the control that
    // will take days of the captain's record off the board.
    val clearSafety = rememberSafetyState(menuInteraction)

    // A guard left open on a tab you have walked away from is a guard that is
    // not guarding. Switching tabs closes all three; the 20 s timeout still
    // covers the case where nobody switches anything.
    LaunchedEffect(tab) {
        sensorSafety.engageOnLeaving()
        modeSafety.engageOnLeaving()
        eraseSafety.engageOnLeaving()
        clearSafety.engageOnLeaving()
    }

    Column(
        modifier = Modifier
            // As much of the window as there is to use, and one height for every
            // tab so switching tabs never moves the sheet - and never taller than
            // the window, which is the guarantee the scrolling below depends on.
            // See [sheetHeight] and [windowHeight].
            .height(sheetHeight(keepClearTop))
            .fillMaxWidth()
            .statusBarsPadding() // keep the header/X clear of the status bar when tall
            .pointerInput(Unit) {
                // Observe (don't consume) every touch-down to reset the safety timer.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    menuInteraction++
                }
            }
            .nestedScroll(swallowLeftoverScroll),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Options", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) {
                Text("✕", style = MaterialTheme.typography.titleLarge)
            }
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            for (entry in OptionsTab.entries) {
                Tab(
                    selected = entry == tab,
                    onClick = { onSelectTab(entry) },
                    text = {
                        Text(
                            entry.label,
                            // Six tabs across a phone leaves about 68dp each,
                            // and "Customize" does not fit at labelLarge. One
                            // line without wrapping is the point: a tab strip
                            // whose rows are different heights reads as broken.
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }
        }

        // Each tab scrolls from its own top. Carrying one scroll position
        // across tabs lands the next one halfway down, which reads as the
        // tab having lost its heading.
        val scroll = remember(tab) { ScrollState(0) }
        // The showing tab is composed one frame after the frame that holds it.
        //
        // Composing a tab is the most expensive single thing this menu does -
        // it is a screenful of text, fields and chips - and it used to happen
        // between the tap and the menu's first frame, so the menu could not
        // appear until its heaviest content had been built. That was the pause
        // the captain felt, and it would have grown with whatever a tab learns
        // to show next. Deferred, the first frame is only the sheet, the header
        // and the strip; the content lands a frame later, while the sheet is
        // still sliding in, where the same work cannot be seen.
        //
        // This is safe precisely because the frame is fixed (see [sheetHeight]):
        // the sheet's size and resting place owe nothing to the content, so
        // composing the content late moves nothing. A tab switch takes the same
        // path, which is what makes the strip answer the finger at once instead
        // of after the next tab has been built.
        //
        // `withFrameNanos` and not a bare state write, so the handoff is pinned
        // to the frame after the sheet's own rather than left to wherever the
        // recomposer happens to put it.
        var tabComposed by remember(tab) { mutableStateOf(false) }
        LaunchedEffect(tab) {
            withFrameNanos {}
            tabComposed = true
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Everything the header and the strip left over, and exactly
                // that: the frame above is a fixed height, so this is a fixed
                // viewport too, and it is the one part of the sheet allowed to
                // be shorter than its content. A tab that outgrows it gains
                // scroll range; a tab that does not fill it leaves space at the
                // bottom rather than shortening the sheet.
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 8.dp)
                // Inside the scroll on purpose: this is trailing space in the
                // content, so the last control can be brought clear of the
                // navigation bar rather than sitting permanently under it.
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (if (tabComposed) tab else null) {
                null -> Unit

                OptionsTab.SENSOR -> SensorTab(
                    state = state,
                    viewModel = viewModel,
                    context = context,
                    menuInteraction = menuInteraction,
                    measuring = measuring,
                    modeSafety = modeSafety,
                    sensorSafety = sensorSafety,
                    onSelectMode = onSelectMode,
                    onShutdown = { showShutdownConfirm = true },
                    onOpenPowerPanel = onOpenPowerPanel,
                )

                OptionsTab.ALERTS -> AlertsSection(
                    rules = config.alerts,
                    activeIds = state.activeAlerts,
                    onUpdate = viewModel::updateAlert,
                    onTest = viewModel::testAlert,
                    preserved = state.alerts,
                    receivePreserved = config.receivePreservedAlerts,
                    onReceivePreserved = viewModel::setReceivePreservedAlerts,
                )

                OptionsTab.DATA -> DataTab(
                    state = state,
                    viewModel = viewModel,
                    config = config,
                    eraseSafety = eraseSafety,
                    clearSafety = clearSafety,
                    onExportCsv = onExportCsv,
                    onSavePng = onSavePng,
                    onToggleBackground = onToggleBackground,
                    onOpenStorageMap = onOpenStorageMap,
                )

                OptionsTab.WATCH -> WatchSection(state = state, viewModel = viewModel)

                OptionsTab.CUSTOMIZE -> CustomizeTab(
                    state = state,
                    viewModel = viewModel,
                    config = config,
                    onSetY = onSetY,
                )

                OptionsTab.HELP -> HelpTab()
            }

            // Clearance under the last control, and it has to be real height:
            // this used to be `Spacer(Modifier.width(1.dp))`, which in a Column
            // is nothing at all. The last thing on the Sensor tab is a guard
            // plate, and a plate's border is drawn on the outside of the last
            // line it covers - so with nothing below it the border lands exactly
            // on the fold, which is where it was found: readable text, and a
            // bottom edge nobody could bring into view.
            Spacer(Modifier.height(BOTTOM_CLEARANCE))
        }
    }

    if (showShutdownConfirm) {
        AlertDialog(
            onDismissRequest = { showShutdownConfirm = false },
            title = { Text("Shut down the sensor?") },
            text = {
                // Scrollable for the same reason the sheet is: an `AlertDialog`
                // gives its text slot whatever is left after the title and the
                // buttons and clips what does not fit, and on a phone held
                // sideways that is most of this warning. The one paragraph a
                // user must read before powering the instrument down is not a
                // thing to lose to a short screen.
                Text(
                    "Recording stops and the sensor host powers off. Wait for its " +
                        "activity light to stop flickering before unplugging it - " +
                        "pulling power while it is still writing risks its storage.\n\n" +
                        "To start it again you will need to power-cycle it by hand.",
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.shutdownSensorHost()
                    showShutdownConfirm = false
                    onDismiss()
                }) { Text("Shut down") }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Connection, control, and everything that touches the physical instrument.
 *
 * Connection leads because it is the precondition for the rest of the tab: with
 * nothing connected, none of the controls below it can do anything.
 */
@Composable
private fun SensorTab(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    context: android.content.Context,
    menuInteraction: Int,
    measuring: Boolean,
    modeSafety: SafetyState,
    sensorSafety: SafetyState,
    onSelectMode: (ConnectionMode) -> Unit,
    onShutdown: () -> Unit,
    onOpenPowerPanel: () -> Unit = {},
) {
    val config = state.config

    Section("Connection", divider = false)
    ConnectionSelector(
        current = config.connectionMode,
        // Re-checked on every touch in the sheet, so toggling Bluetooth in
        // the shade and coming back shows the button as live again.
        availability = remember(menuInteraction) {
            ConnectionMode.entries.associateWith { TransportAvailability.of(context, it) }
        },
        connected = state.connection is ConnectionState.Connected,
        servingDuringSwitch = servingDuringSwitch(state.activeMode, state.switchingTo),
        onSelect = onSelectMode,
    )
    Text(
        "Bluetooth (BLE) connects directly to the sensor host - use it when no " +
            "Wi-Fi network is available. Switching between them keeps the old " +
            "connection until the new one answers, and anything missed in " +
            "between is fetched back from the sensor's own record. Disconnect " +
            "stops this phone streaming; the sensor keeps recording and " +
            "reconnecting fills in the gap.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Section("Who is connected")
    SharedAccessSection(
        state = state,
        onClaim = viewModel::claimControl,
        onRelease = viewModel::releaseControl,
    )

    MeasurementHeader(safety = modeSafety)
    AcquisitionSection(
        profile = state.profile,
        power = state.power,
        supportsSleep = state.supportsSleep,
        daemonState = state.daemonState,
        safety = modeSafety,
        onSetMode = { mode ->
            val p = state.profile
            when (mode) {
                MeasurementMode.PAUSED -> viewModel.pause()
                MeasurementMode.CONTINUOUS, MeasurementMode.LOW_POWER -> {
                    // Leaving Paused has to resume explicitly. set_profile
                    // says what to record, not that recording should start,
                    // and the controller deliberately leaves a paused sensor
                    // paused - so picking the mode you were already in before
                    // stopping would otherwise change nothing at all.
                    if (state.daemonState == Wire.STATE_PAUSED) viewModel.resume()
                    viewModel.setProfile(
                        mode = if (mode == MeasurementMode.LOW_POWER) {
                            Wire.MODE_DUTY_CYCLE
                        } else {
                            Wire.MODE_CONTINUOUS
                        },
                        intervalSeconds = p?.intervalSeconds ?: 1.0,
                        cycleSeconds = p?.cycleSeconds ?: 900.0,
                        warmupSeconds = p?.warmupSeconds ?: 30.0,
                        averageSeconds = p?.averageSeconds ?: 30.0,
                    )
                }
            }
        },
        onSetInterval = { seconds ->
            val p = state.profile
            viewModel.setProfile(
                mode = Wire.MODE_CONTINUOUS,
                intervalSeconds = seconds,
                cycleSeconds = p?.cycleSeconds ?: 900.0,
                warmupSeconds = p?.warmupSeconds ?: 30.0,
                averageSeconds = p?.averageSeconds ?: 30.0,
            )
        },
        onSetCycle = { seconds ->
            val p = state.profile
            viewModel.setProfile(
                mode = Wire.MODE_DUTY_CYCLE,
                intervalSeconds = p?.intervalSeconds ?: 1.0,
                cycleSeconds = seconds,
                warmupSeconds = p?.warmupSeconds ?: 30.0,
                averageSeconds = p?.averageSeconds ?: 30.0,
            )
        },
    )

    // The power readout, moved off the main screen: today there is not even a
    // battery, so a permanent slot on the display was width spent on a glyph
    // that never changes. Hidden like the pill always was while no host has
    // reported power at all - a readout of nothing is not a control.
    if (state.power != null) {
        Section("Power")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPowerPanel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PowerPill(state, onTap = onOpenPowerPanel)
            Text(
                "The estimated draw, and what the other modes would cost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SensorSection(
        measuring = measuring,
        canPowerOff = state.canPowerOff,
        cleanDueInHours = state.power?.cleanDueInHours,
        viewModel = viewModel,
        safety = sensorSafety,
        onShutdown = onShutdown,
    )
}

/**
 * Where readings end up: this device's own copy, and the controller's.
 *
 * Background monitoring belongs here rather than with the display settings.
 * What it decides is whether this device's copy keeps being written while the
 * app is not on screen, which is a question about the record, not about the
 * plot.
 */
@Composable
private fun DataTab(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    config: AppConfig,
    eraseSafety: SafetyState,
    clearSafety: SafetyState,
    onExportCsv: () -> Unit,
    onSavePng: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onOpenStorageMap: () -> Unit = {},
) {
    // The record leads the tab. What is on the sensor against what is on
    // this device is the question every other control here is an answer
    // to, and it used to be answerable only by running a copy and reading
    // what the copy reported afterwards. See [DataInventoryRow].
    Section("The record", divider = false)
    SessionCopyRow(state, viewModel, eraseSafety)
    StoredSessionsRow(state, viewModel)

    // The way back in, beside the two ways out. It belongs with session handling
    // rather than under "Gaps in the record" below: it repairs a gap, but it is
    // not a thing done *to* a gap - the file decides what it covers, and it is
    // just as often used to fold in a session this device never saw at all.
    RestoreFromFileRow(state, viewModel)

    // The sensor's flash ring, page by page. Moved off the main screen into the
    // record's own tab: not something the user needs to be checking often, and
    // this is where the questions it answers live. Hidden, not disabled, on a
    // host with no flash ring: the Pi answers `unknown cmd` to the map, and a
    // control this host cannot serve is hidden rather than shown and refused.
    if (state.storage != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenStorageMap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StorageMapIcon(
                fraction = state.storage.fraction.takeIf { state.storage.ready },
                onClick = onOpenStorageMap,
            )
            Text(
                "Storage map: how full the sensor's own record is, page by page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Directly under the comparison it completes. The inventory above says how
    // much is missing; this says *where*, and - the part no count can - whether
    // the sensor can still be asked for it. See [RecordGapsRow].
    Section("Gaps in the record")
    RecordGapsRow(state, viewModel)

    // Its own group, after both halves of "the record", rather than in the
    // middle of them: it used to be emitted from inside [SessionCopyRow], which
    // put its heading above "Sessions already stored on this device" and left
    // that row reading as part of erasing the sensor.
    EraseRow(state, viewModel, enabled = !transfersAreInFlight(state), safety = eraseSafety)

    // A separate group under its own heading, below erasing rather than folded
    // into it. The two answer different questions - "remove what I already have"
    // against "give me everything and start clean" - and the second is the one
    // that empties the sensor, so it must not read as a variant of the first.
    BackupAndClearRow(
        state,
        viewModel,
        enabled = !transfersAreInFlight(state),
        safety = clearSafety,
    )

    // Two different things that both used to be called "export": writing
    // out what is on the plot, and fetching the sensor's whole archive
    // across the network. The labels now say which direction the data
    // moves and where it ends up, because mistaking the first for the
    // second means believing you have a backup that you do not.
    Section("Saving what is on the plot")
    // The two do deliberately different things, and one caption over
    // both said otherwise: "what is on the plot right now" is true of
    // the image and false of the CSV, which writes the whole of what is
    // loaded. Someone who zoomed in and then found a full day in the
    // file would reasonably read that as a bug rather than the point.
    Text(
        "CSV saves everything loaded, including what is scrolled or zoomed out " +
            "of view. The image is the plot exactly as it looks now.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onExportCsv, modifier = Modifier.weight(1f)) {
            Text("Save as CSV")
        }
        OutlinedButton(onClick = onSavePng, modifier = Modifier.weight(1f)) {
            Text("Save as image")
        }
    }

    Section("Monitoring")
    SwitchRow("Background monitoring", config = config.backgroundMonitoring) { onToggleBackground(it) }
}

/**
 * Everything that changes what you see and nothing that changes what is
 * recorded - which is the line this tab is drawn along, and worth keeping to as
 * it grows. Anything here can be got wrong and put back with no consequence,
 * which is exactly why none of it is behind a safety.
 *
 * The one exception is the "Saved settings" group at the foot of it, which spans
 * the whole menu and can put back alert rules and the transport along with the
 * display. It is labelled to say so - see [ConfigSaveLoad] - because being the
 * exception on this tab is precisely what made it misread.
 */
@Composable
private fun CustomizeTab(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    config: AppConfig,
    onSetY: () -> Unit,
) {
    Section("Plot", divider = false)
    val yAuto = state.chart?.yAuto ?: true
    SwitchRow("Y-axis autoscale", config = yAuto) { viewModel.setYAutoscale(it) }
    // Under the autoscale switch because it is a refinement of it: off (the
    // default), autoscale fits the data alone; on, the fitted range also
    // reaches the alert line, so an alert set far above clean air pulls the
    // axis up instead of sitting off-screen.
    SwitchRow("Autoscale includes alert level", config = config.alertInAutoscale) {
        viewModel.setAlertInAutoscale(it)
    }
    SwitchRow("Logarithmic Y axis", config = config.logY) { viewModel.setLogY(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Greyed out while autoscale is on: a range set there would be
        // overwritten by the next reading, so the button would look as though it
        // had done nothing. Nothing should look tappable when tapping it changes
        // nothing.
        OutlinedButton(
            onClick = onSetY,
            enabled = !yAuto,
            modifier = Modifier.weight(1f),
        ) { Text("Set Y range") }
        OutlinedButton(onClick = viewModel::clearPlot, modifier = Modifier.weight(1f)) { Text("Clear plot") }
    }
    if (yAuto) {
        Text(
            "Turn autoscale off to set the range by hand. To move the axis without " +
                "leaving autoscale, use two fingers on the plot: drag them to shift " +
                "the range, or pinch to scale it about the point between them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PlotStyleRow(config) { viewModel.setPlotStyle(it) }
    // Off by default, and a preference about ink only: gaps are still detected
    // and the Data tab still reports them in full either way. The patterns the
    // shading uses are keyed on the Help tab. See AppConfig.markGaps for why
    // default-off is the calibration for where storage is going.
    SwitchRow("Mark gaps in the record", config = config.markGaps) {
        viewModel.setMarkGaps(it)
    }

    // The Cursor group used to be repeated here, because the stacked layout had
    // nowhere to show it. It now travels with the chart controls in every layout
    // and every orientation - see [ChartControls] and [PanelFit] - so a copy in
    // the menu would be a second place to find the same two chips and a second
    // thing to keep in step.

    Section("Controls")
    PinCycleRow(config) { viewModel.updateConfig(config.withPinCycleCount(it)) }
    WindowPresetsEditor(config) { viewModel.updateConfig(config.copy(windowPresets = it)) }
    PlotIntervalsEditor(config) { viewModel.updateConfig(config.copy(plotIntervals = it)) }
    // Tucked in low: rarely changed. Hides the unreliable startup/clean spike.
    SwitchRow("Exclude sensor-spike readings", config = config.excludeFlagged) {
        viewModel.setExcludeFlagged(it)
    }

    Section("Readouts")
    ReadoutStyleRow(config) { viewModel.setReadoutStyle(it) }

    // The watch relay interval used to sit here, and that was the whole of what
    // the app said about the watch: how often to send to a link nobody could
    // see the state of. It now lives on the Watch tab, under the indicators that
    // say whether sending achieves anything - see [WatchSection].

    // The one group on this tab that is not about display, and it has to say so:
    // see [ConfigSaveLoad]. It stays at the bottom of Display because that is
    // where it has always been and where it is looked for; what changes is that
    // its scope is now written on it.
    Section("Saved settings")
    ConfigSaveLoad(viewModel)
}

/** "Measurement" heading with its own safety toggle on the right. */
@Composable
private fun MeasurementHeader(safety: SafetyState) {
    Section("Measurement") { SafetyToggle(engaged = safety.engaged, onToggle = safety::toggle) }
    // No notice here: this safety's guard - and so its "that press did nothing"
    // line - belongs to AcquisitionSection just below.
}

/**
 * The heading that divides a tab into groups.
 *
 * Shared so every tab's headings are the same object and not merely the same
 * size. There used to be four near-copies of this - here, on Sensor, on
 * Measurement and on Erasing - which had drifted apart by a few dp each and, in
 * one case, had lost its rule entirely, so one group on the Data tab read as a
 * heading that belonged to whatever was above it.
 *
 * ## The spacing, and why it is lopsided
 *
 * The rule takes 16dp above it and 6dp below (the 10dp here plus the 6dp the
 * scrolling column puts between every child). A heading has to bind to the group
 * it opens rather than float between two, and that only reads if the space above
 * it is clearly the larger. It used to be 12 against 8, which is a difference
 * nobody sees.
 *
 * [divider] is off for the group that opens a tab: `TabRow` already draws a rule
 * under itself, and a second one 10dp below it reads as a mistake rather than as
 * a division.
 *
 * [trailing] is the slot for a control that belongs to the whole group - in
 * practice a [SafetyToggle], placed opposite its own heading so it is obvious
 * which group it unlocks.
 */
@Composable
internal fun Section(
    title: String,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (divider) HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(label: String, config: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = config, onCheckedChange = onChange)
    }
}

/**
 * When the fan is next due a clean, in the units the count is actually kept in.
 *
 * "Of measurement" is not padding: the controller counts seconds the fan has
 * run, so in low power mode a week of them is about four months of calendar,
 * and a figure that read as elapsed time would be wrong by that much.
 */
private fun cleanDueNote(hours: Double): String = when {
    hours <= 0.0 ->
        "The fan is due its self-clean. It takes about ten seconds, and the readings " +
            "during and just after it are flagged as disturbed."
    hours < 48.0 ->
        "Fan self-clean due after another ${hours.roundToInt()} h of measurement."
    else ->
        "Fan self-clean due after another ${(hours / 24.0).roundToInt()} days of measurement."
}

/**
 * The Sensor controls, protected by a missile-switch-style safety. The three
 * buttons (Pause/Resume, Clean, Restart) disturb acquisition, so a translucent red
 * guard plate covers them until the user flips the safety toggle - placed top-right,
 * opposite the "Sensor" label. The safety re-engages automatically after 20 s
 * without any interaction in this section, and says so if it absorbs a press.
 */
@Composable
private fun SensorSection(
    measuring: Boolean,
    canPowerOff: Boolean,
    cleanDueInHours: Double?,
    viewModel: DashboardViewModel,
    safety: SafetyState,
    onShutdown: () -> Unit,
) {
    Section("Sensor") { SafetyToggle(engaged = safety.engaged, onToggle = safety::toggle) }
    Text(
        "These act on the physical sensor and disturb its readings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // The only warning a dirty fan gives. It does not fail, it reads low, so
    // without this line nothing tells anyone until the numbers are already
    // wrong - and the controller is the only thing that knows, because the
    // sensor's own counter is reset by every power cycle.
    cleanDueInHours?.let { hours ->
        Text(
            cleanDueNote(hours),
            style = MaterialTheme.typography.bodySmall,
            color = if (hours <= 0.0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // One guard over both rows: two stacked "SAFETY ON" overlays read as a bug.
    GuardedSection(safety) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (measuring) {
                Button(onClick = viewModel::pause, modifier = Modifier.weight(1f)) { Text("Pause sensor") }
            } else {
                Button(onClick = viewModel::resume, modifier = Modifier.weight(1f)) { Text("Resume sensor") }
            }
            OutlinedButton(onClick = viewModel::clean, modifier = Modifier.weight(1f)) { Text("Clean sensor") }
            OutlinedButton(onClick = viewModel::restart, modifier = Modifier.weight(1f)) { Text("Restart") }
        }
        // Only where there is something to shut down. A controller with no
        // operating system says `can_power_off: false` in its hello, and the
        // control is left out rather than shown and refused - the button was
        // never anything but an error dialog there. The note takes its place
        // so its absence reads as an answer instead of a missing feature.
        if (canPowerOff) {
            OutlinedButton(onClick = onShutdown, modifier = Modifier.fillMaxWidth()) {
                Text("Shut down sensor")
            }
        } else {
            ControlNote(
                "This controller has nothing to shut down: it runs no operating " +
                    "system and its stored readings survive losing power, so " +
                    "unplugging it is the way to stop it.",
            )
        }
    }
}

@Composable
private fun PinCycleRow(config: AppConfig, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Pin cycle length (n)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onChange(config.pinCycleCount - 1) }, enabled = config.pinCycleCount > 1) { Text("–") }
            Text("${config.pinCycleCount}", modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedButton(onClick = { onChange(config.pinCycleCount + 1) }) { Text("+") }
        }
    }
}

@Composable
internal fun WindowPresetsEditor(config: AppConfig, onApply: (List<com.sensirion.sps30.data.WindowPreset>) -> Unit) {
    var text by remember(config.windowPresets) { mutableStateOf(AppConfig.presetsToText(config.windowPresets)) }
    // What Apply refused and why, one line per token. Cleared by editing: the
    // message belongs to the text it judged, not to whatever comes next.
    var errors by remember(config.windowPresets) { mutableStateOf(emptyList<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Window buttons")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; errors = emptyList() },
                singleLine = true,
                isError = errors.isNotEmpty(),
                modifier = Modifier.weight(1f),
                placeholder = { Text("10s, 1m, 1h, 2d") },
            )
            Button(onClick = {
                val parsed = AppConfig.checkPresets(text)
                errors = parsed.errors
                if (parsed.errors.isEmpty()) onApply(parsed.values)
            }) { Text("Apply") }
        }
        for (error in errors) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // One line, deliberately: every line here is height the whole tab
        // scrolls by on short screens (see [OPTIONS_SHEET_MAX_HEIGHT]).
        Text(
            "Any span works: 12h, 2d, 3w, 1M, 1y.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only when "all" is in the field: it is the one token that Applies
        // and then seems to do nothing, so it explains itself right there.
        if (remember(text) { AppConfig.checkPresets(text).values.any { it.isAll } }) {
            Text(
                "\"all\" adds no button of its own: Auto already frames the whole record.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The sizes in the Average button's chooser, edited as a comma-separated list. */
@Composable
private fun PlotIntervalsEditor(config: AppConfig, onApply: (List<Double>) -> Unit) {
    var text by remember(config.plotIntervals) {
        mutableStateOf(AppConfig.intervalsToText(config.plotIntervals))
    }
    var errors by remember(config.plotIntervals) { mutableStateOf(emptyList<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Averaging sizes")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; errors = emptyList() },
                singleLine = true,
                isError = errors.isNotEmpty(),
                modifier = Modifier.weight(1f),
                placeholder = { Text("1s, 5s, 30s, 1m, 1h") },
            )
            Button(onClick = {
                val parsed = AppConfig.checkIntervals(text)
                errors = parsed.errors
                if (parsed.errors.isEmpty()) onApply(parsed.values)
            }) { Text("Apply") }
        }
        for (error in errors) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "The sizes offered when you press and hold Average on the main view. " +
                "Changes what you see, never what is recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only when a 1s entry is in the field: it is the one size that Applies
        // and then never appears, so it explains itself right there.
        if (remember(text) { AppConfig.checkIntervals(text).values.any { it <= 1.0 } }) {
            Text(
                "\"1s\" adds no size of its own: with Average off, every reading is drawn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How the traces themselves are drawn.
 *
 * Presented like [ReadoutStyleRow] because it is the same kind of choice - a look,
 * with nothing at stake either way - and two controls that do the same sort of
 * thing should not be two different kinds of control.
 */
@Composable
private fun PlotStyleRow(config: AppConfig, onPick: (PlotStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Trace style")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.plotStyle == PlotStyle.MARKERS,
                onClick = { onPick(PlotStyle.MARKERS) },
                label = { Text("Line and points") },
            )
            FilterChip(
                selected = config.plotStyle == PlotStyle.LINE,
                onClick = { onPick(PlotStyle.LINE) },
                label = { Text("Line only") },
            )
        }
        Text(
            "Line and points draws a hairline with every reading ringed by a small " +
                "hollow circle, so you can see where the samples actually fall. The " +
                "rings step aside once the readings are packed closer together than " +
                "a ring is wide - zoom in to bring them back. Line only is the " +
                "heavier plain line.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Layout of the two PM readout buttons on the main view. */
@Composable
private fun ReadoutStyleRow(config: AppConfig, onPick: (ReadoutStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Readout layout")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.readoutStyle == ReadoutStyle.STACKED,
                onClick = { onPick(ReadoutStyle.STACKED) },
                label = { Text("Stacked") },
            )
            FilterChip(
                selected = config.readoutStyle == ReadoutStyle.INLINE,
                onClick = { onPick(ReadoutStyle.INLINE) },
                label = { Text("One line") },
            )
        }
        Text(
            "Stacked puts the name, reading and unit on three lines, which gives " +
                "the number the most presence. One line fills the width instead, " +
                "and is easier on space at the cost of a busier readout.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Save every setting in this menu under a name, load one back, or reset the lot.
 *
 * ## Why the labels say "all"
 *
 * This sits at the bottom of the Display tab, under a heading that used to read
 * "Configuration" and a button that read "Save", and the reasonable inference
 * from that placement is that it saves the display settings. It does not. It
 * writes the whole of `AppConfig` - the alert rules and their thresholds, the
 * transport, background monitoring, the watch relay interval, the preserved-alert
 * opt-in, along with everything on this tab - and "Reset to defaults" takes all
 * of it back, alert rules included.
 *
 * That is a control whose blast radius was three times what its label implied,
 * and the reset is destructive: somebody clearing a display experiment would have
 * silently thrown their alert levels away. The scope is stated on every one of
 * the three controls now, rather than left to be inferred from which tab it
 * happens to be on.
 *
 * It is not moved. This is where it has been and where it is looked for, and no
 * other tab is a better home for a control that spans all of them.
 */
@Composable
private fun ConfigSaveLoad(viewModel: DashboardViewModel) {
    var refresh by remember { mutableIntStateOf(0) }
    val names = remember(refresh) { viewModel.savedConfigNames() }
    var saveName by remember { mutableStateOf("") }

    Text(
        "Every setting in this menu - display, alerts, connection and the rest - " +
            "saved under one name and brought back together. Not this tab alone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = saveName,
            onValueChange = { saveName = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text("name") },
        )
        Button(
            onClick = { viewModel.saveConfigAs(saveName); saveName = ""; refresh++ },
            enabled = saveName.isNotBlank(),
        ) { Text("Save all") }
    }
    if (names.isNotEmpty()) {
        Text("Load all settings from:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in names) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.loadSavedConfig(name) },
                    label = { Text(name) },
                )
            }
        }
    }
    TextButton(onClick = { viewModel.resetConfigToDefault() }) {
        Text("Reset all settings to defaults")
    }
}

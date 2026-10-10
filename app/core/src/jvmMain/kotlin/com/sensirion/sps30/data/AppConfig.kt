package com.sensirion.sps30.data

import kotlinx.serialization.Serializable

/** A labelled window-size button (e.g. "5m" -> 300s). Any span; there is no cap. */
@Serializable
data class WindowPreset(val label: String, val seconds: Double) {
    /**
     * The legacy "all" token. It gets no chip of its own - Auto Set is what
     * frames the whole record now - and is told by its label, not its seconds:
     * "≥ 48 h means all" is exactly the aliasing that used to make every long
     * window silently vanish from the row.
     */
    val isAll: Boolean get() = label.equals("all", ignoreCase = true)
}

/**
 * What a comma-separated options field parses to: the tokens that were read,
 * and one line per token that was not, saying why. An editor applies [values]
 * only when [errors] is empty - a field that silently eats what it cannot
 * read looks like a cap to whoever typed it.
 */
data class ParsedField<T>(val values: List<T>, val errors: List<String>)

/**
 * How the app reaches the sensor host: Wi-Fi/TCP, Bluetooth LE (no network needed),
 * or [NONE] - deliberately not connected.
 *
 * [NONE] is a phone-side state only. The sensor host owns the serial port and writes
 * its CSV regardless of who is watching, so disconnecting (or the phone's battery
 * dying) never interrupts recording; reconnecting backfills the gap. It exists so
 * stepping away is a deliberate action rather than force-quitting the app.
 */
enum class ConnectionMode { WIFI, BLUETOOTH, NONE }

/**
 * How the PM readouts are laid out.
 *
 * [STACKED] is the compact original - label, reading and unit on three centred
 * lines - and is the default because the number carries best when nothing shares
 * its line. [INLINE] puts all three on one line, which fills a wide tile
 * handsomely but asks the eye to take in more at once and leaves the number
 * competing for attention.
 */
@Serializable
enum class ReadoutStyle { STACKED, INLINE }

/**
 * How the plotted series are drawn.
 *
 * [LINE] is the original: a solid connecting line and nothing on the samples
 * themselves, with the two series graduated in width so they stay told apart
 * where they coincide.
 *
 * [MARKERS] is the default and is the look of a graph plotted in a spreadsheet
 * with the thinnest line it offers: a hairline connecting line, and every sample
 * ringed by a small hollow circle whose hole is wide enough to show the line
 * passing through it with a little clear space either side. It says something the
 * bare line cannot - **where the samples actually are** - which on a duty-cycled
 * record is exactly the question being asked, one point every fifteen minutes
 * being indistinguishable from one point a second once a line has joined them up.
 *
 * The rings are drawn only while every sample can carry one without its neighbour
 * overlapping it; see `MARKER_DIAMETER` in `PmChart`. Past that the line is drawn
 * alone, because a subset of the samples ringed would say the record is sparser
 * than it is, and all of them ringed is a smear.
 */
@Serializable
enum class PlotStyle { LINE, MARKERS }

/** User-customizable settings, persisted and editable from the options menu. */
@Serializable
data class AppConfig(
    /** How many recent pin origins the Pin button cycles through. */
    val pinCycleCount: Int = ChartView.DEFAULT_PIN_CYCLE,
    /**
     * Which of the plottable PM series are drawn on the chart.
     *
     * A subset of [PlottedSeries.FIELDS] - PM4.0 and PM10 are never drawn and
     * so are never in here, whatever a config written by an older build says.
     * See [normalized], which is what enforces that on the way in.
     */
    val visiblePm: List<String> = PlottedSeries.FIELDS,
    /** The window-size buttons, in order. */
    val windowPresets: List<WindowPreset> = DEFAULT_PRESETS,
    /** Wi-Fi/TCP (default) or Bluetooth LE. */
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    /** Exclude disturbed settling/clean-spike readings from the plot (on by default). */
    val excludeFlagged: Boolean = true,
    /** Minimum gap between pushes to the watch. Each push wakes the watch's radio,
     *  rewrites its cache and forces a complication redraw, so this is the main
     *  lever on watch battery drain. */
    val watchRelaySeconds: Int = DEFAULT_WATCH_RELAY_SECONDS,
    /** Keep streaming when the app is not in front. On by default: the sensor
     *  records regardless, but without this the phone's own history develops
     *  gaps the moment the screen sleeps, which reads as data loss. */
    val backgroundMonitoring: Boolean = true,
    /** Averaging buttons offered on the main plot view, in seconds. 1 = raw. */
    val plotIntervals: List<Double> = DEFAULT_PLOT_INTERVALS,
    /** Plot PM on a log y-axis, so a spike two decades up does not flatten the rest. */
    val logY: Boolean = false,
    /**
     * Autoscale keeps the alert level in view, not just the data.
     *
     * Off by default: an alert set well above clean air would otherwise stretch
     * the axis to reach it and press the trace into the bottom of the plot. On,
     * the fitted range extends upward to the level the plot draws its alert
     * line at, so the line and the data share the frame. It only ever raises
     * the top of a *fitted* range - a manual range and a gesture override are
     * the user's own numbers and are left alone.
     */
    val alertInAutoscale: Boolean = false,
    /** Hairline with hollow sample markers, or the older plain line. */
    val plotStyle: PlotStyle = PlotStyle.MARKERS,
    /**
     * Shade the plot where readings are missing, in the pattern that says why.
     *
     * Display only, and off by default. Off suits where the record is going, not
     * a retreat from marking: once storage keeps a decimated trace of what it
     * would otherwise drop, a hole will usually hold *something*, and a plot that
     * flags every hole is calibrated for the behaviour before that, not after.
     *
     * What this must never touch is detection. The gaps are found, judged and
     * reported on the Data tab identically with this off; the only thing the
     * switch reaches is `chartRender`, which decides what the plot is handed to
     * draw. The Data tab's account of the record is not a preference.
     */
    val markGaps: Boolean = false,
    /** Layout of the two PM readout buttons on the main view. */
    val readoutStyle: ReadoutStyle = ReadoutStyle.STACKED,
    /**
     * Threshold rules. The rules themselves are phone-side only.
     *
     * One number derived from them does reach the controller - the lowest
     * enabled threshold per field - because a controller whose ring has wrapped
     * has to have known which readings were worth copying out of its way, and
     * the case that matters is the one where no phone was there to ask. That is
     * a retention policy and not a notification setting: no id, no label, no
     * reaction, nothing per user. See [preservationThresholds].
     */
    val alerts: List<AlertRule> = AlertEngine.defaults(),
    /**
     * Take the controller's preserved alert data on connection.
     *
     * On by default, which is the behaviour: the controller offers it unasked
     * and this is the opt-out. Turning it off does not stop the controller
     * *preserving* anything - it only stops this device being sent it, which is
     * a choice about a phone's storage and battery, not about the record.
     */
    val receivePreservedAlerts: Boolean = true,
) {
    /**
     * The line the controller should preserve against, per field.
     *
     * The **minimum** enabled threshold for each watched field. The minimum
     * because keeping a superset of what any rule would fire on is right and
     * keeping less than some rule would fire on is not - a rule that fires on
     * air the controller did not think worth keeping is a rule whose evidence
     * is gone. Mirrors `wire.alert_thresholds`.
     *
     * Empty when every rule is off, which the user is allowed to choose and
     * which the controller reads as "watch nothing".
     */
    val preservationThresholds: Map<String, Double>
        get() = alerts
            .filter { it.enabled && it.threshold.isFinite() && it.threshold > 0.0 }
            .groupBy { it.field }
            .mapValues { (_, rules) -> rules.minOf { it.threshold } }

    /**
     * The level autoscale must keep in view, or null to fit the data alone.
     *
     * Null whenever [alertInAutoscale] is off. Otherwise the same level the
     * plot draws its alert line at - the lowest enabled rule that asked to be
     * on the plot (see `ChartRender`) - because an axis stretched to a level
     * that is not drawn would be reaching for something invisible.
     */
    val autoscaleAlertLevel: Double?
        get() = if (!alertInAutoscale) null
        else alerts
            .filter { it.enabled && it.highlightPlot && it.threshold.isFinite() && it.threshold > 0.0 }
            .minOfOrNull { it.threshold }

    fun withPinCycleCount(n: Int) = copy(pinCycleCount = n.coerceIn(1, 20))

    /**
     * A config that can only ask for what the app is willing to draw, and that
     * always asks for something.
     *
     * Applied wherever a config arrives from outside this process - which in
     * practice means [com.sensirion.sps30.config.ConfigStore]. A config saved by
     * a build that plotted all four series still names PM4.0 and PM10 in its
     * stored JSON, and without this an upgrade would keep drawing them.
     *
     * ## Why an empty set becomes the default rather than staying empty
     *
     * Narrowing can empty the set without the user having asked for anything. A
     * config written by a build that plotted all four, by someone who had PM1.0
     * and PM2.5 switched off and the coarse cuts on, names only `pm4_0` and
     * `pm10` - and those are exactly the two this drops. The upgrade then opened
     * on a plot that drew nothing, both tiles plain, no message, and the state
     * survived every restart because it was in the stored config. That is what
     * reached the captain's tablet.
     *
     * An empty set the *user* asked for, by switching both tiles off, is a
     * different thing and is left alone - see [toggleVisible]. The two are told
     * apart by where they arrive: this is the door configs come in through, and
     * a plot with nothing on it is not a thing to be opened on. So the app can
     * be left showing nothing, deliberately, for as long as that session lasts,
     * and restarting it is always a way back.
     */
    fun normalized(): AppConfig {
        val plottable = PlottedSeries.keep(visiblePm).ifEmpty { PlottedSeries.FIELDS }
        return if (plottable == visiblePm) this else copy(visiblePm = plottable)
    }

    fun toggleVisible(field: String): AppConfig {
        if (field !in PlottedSeries.FIELDS) return this
        val next = if (field in visiblePm) visiblePm - field else visiblePm + field
        // Canonical order, and an empty plot is allowed: with two series left,
        // refusing the last "off" would make a lit button that does nothing when
        // tapped. Both buttons are on screen and unlit, so the way back is one tap
        // - `DashboardControlsPressTest` is what says that tap still lands. An
        // empty set that arrives from *storage* is not this and is not kept; see
        // [normalized].
        return copy(visiblePm = PlottedSeries.keep(next))
    }

    companion object {
        /**
         * Recording intervals offered for CONTINUOUS mode, in seconds.
         *
         * These change how much DATA is recorded, not how much power is used -
         * the fan and laser stay on regardless, so the sensor still draws ~55 mA.
         * Past about a minute the honest answer is low-power mode instead, which
         * is what [SUGGEST_DUTY_CYCLE_ABOVE] drives.
         */
        val CONTINUOUS_INTERVALS = listOf(1.0, 10.0, 30.0, 60.0)

        /** Above this continuous interval, prompt the user towards duty cycling. */
        const val SUGGEST_DUTY_CYCLE_ABOVE = 60.0

        /**
         * Low-power cycle periods, in seconds. 15 min is Sensirion's own
         * recommendation - their field data shows it catches events like cooking
         * while cutting average current from ~55 mA to ~3.5 mA.
         *
         * 12 h and 24 h go past anything Sensirion discusses, deliberately: for a
         * gadget in storage or left behind while away, the useful question is no
         * longer "will it catch a cooking event" but "will there be any charge
         * left in a month", and at that duty cycle self-discharge dominates
         * measurement entirely.
         */
        val DUTY_CYCLE_PERIODS = listOf(300.0, 900.0, 1800.0, 3600.0, 43_200.0, 86_400.0)

        /**
         * What the legacy "all" token stores as its seconds - the old Pi
         * daemon's 48 h retention horizon, from when showing everything and
         * showing 48 h were the same thing. Kept only so stored configs keep
         * round-tripping; nothing reads it as a limit any more. Whether a
         * preset means "all" is [WindowPreset.isAll], which goes by label.
         */
        const val ALL_SECONDS = 172_800.0 // 48h

        /** 3 min: readings arrive ~1Hz but an ambient air-quality glance does not
         *  need that, and the complication's own refresh period is 5 min anyway. */
        const val DEFAULT_WATCH_RELAY_SECONDS = 180

        /** Offered in the options sheet, in seconds. */
        val WATCH_RELAY_CHOICES = listOf(60, 180, 300, 900)

        val DEFAULT_PRESETS: List<WindowPreset> = listOf(
            WindowPreset("1m", 60.0),
            WindowPreset("5m", 300.0),
            WindowPreset("30m", 1800.0),
            WindowPreset("1h", 3600.0),
        )

        /** 1s means raw samples; the rest average over that many seconds. */
        val DEFAULT_PLOT_INTERVALS = listOf(1.0, 5.0, 10.0, 30.0, 60.0, 3600.0)

        fun default() = AppConfig()

        /** Parse "1s,5s,30s,1m,1h" into plot intervals; bad tokens are dropped. */
        fun parseIntervals(text: String): List<Double> =
            checkIntervals(text).values.ifEmpty { DEFAULT_PLOT_INTERVALS }

        /** [parseIntervals], but each token the app cannot read is named with why. */
        fun checkIntervals(text: String): ParsedField<Double> {
            val values = mutableListOf<Double>()
            val errors = mutableListOf<String>()
            for (token in text.split(",")) {
                val t = token.trim()
                if (t.isEmpty()) continue
                runCatching { Durations.parse(t) }
                    .onSuccess { values.add(it) }
                    .onFailure { errors.add(it.message ?: "cannot read '$t'") }
            }
            if (values.isEmpty() && errors.isEmpty()) {
                errors.add("no intervals given - e.g. 1s, 5s, 30s, 1m, 1h")
            }
            return ParsedField(values, errors)
        }

        /** "1s", "30s", "5m", "1h" - matches how the buttons are labelled. */
        fun intervalLabel(seconds: Double): String = when {
            seconds < 60 -> "${seconds.toInt()}s"
            seconds < 3600 -> "${(seconds / 60).toInt()}m"
            else -> "${(seconds / 3600).toInt()}h"
        }

        fun intervalsToText(values: List<Double>): String =
            values.joinToString(", ") { intervalLabel(it) }

        /** Parse "10s,30s,1m,10m,1h,2d,all" into presets; bad tokens are dropped. */
        fun parsePresets(text: String): List<WindowPreset> =
            checkPresets(text).values.ifEmpty { DEFAULT_PRESETS }

        /**
         * [parsePresets], but each token the app cannot read is named with why.
         *
         * There is no upper bound on a window: any span [Durations.parse]
         * accepts is a preset, and how much of it holds data is the record's
         * business, not this field's. The old behavior - dropping what did not
         * parse and, when nothing did, silently restoring the defaults - is
         * kept only behind [parsePresets] for callers with nowhere to put an
         * error; the editor shows these instead.
         */
        fun checkPresets(text: String): ParsedField<WindowPreset> {
            val values = mutableListOf<WindowPreset>()
            val errors = mutableListOf<String>()
            for (token in text.split(",")) {
                val t = token.trim()
                when {
                    t.isEmpty() -> {}
                    t.equals("all", ignoreCase = true) ->
                        values.add(WindowPreset("all", ALL_SECONDS))
                    else -> runCatching { WindowPreset(t, Durations.parse(t)) }
                        .onSuccess { values.add(it) }
                        .onFailure { errors.add(it.message ?: "cannot read '$t'") }
                }
            }
            if (values.isEmpty() && errors.isEmpty()) {
                errors.add("no window sizes given - e.g. 10s, 1m, 1h, 2d")
            }
            return ParsedField(values, errors)
        }

        fun presetsToText(presets: List<WindowPreset>): String =
            presets.joinToString(", ") { it.label }
    }
}

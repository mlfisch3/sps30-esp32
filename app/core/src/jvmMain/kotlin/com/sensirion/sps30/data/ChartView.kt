package com.sensirion.sps30.data

import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import java.time.format.DateTimeFormatter

enum class ChartMode { MOVING, PINNED }

/**
 * The user-facing view mode, derived from the raw state:
 *  - [LIVE]: right edge tracks now, left = now - window (a clean live window).
 *  - [LIVE_FROZEN]: still following-capable but the x range was frozen by a
 *    touch gesture; neither Live nor Pin reads as "selected".
 *  - [PIN]: left edge pinned, right grows with now.
 *  - [ALL]: left edge pinned at the session start, right grows with now - the
 *    whole current acquisition session, autoscaled to fill the plot width.
 */
enum class ViewMode { LIVE, LIVE_FROZEN, PIN, ALL }

/**
 * How the y axis is being decided right now.
 *
 *  - [FITTED]: autoscale is on and nothing has overridden it - the axis follows
 *    the data in the current view.
 *  - [OVERRIDDEN]: autoscale is still on as a *setting*, but a gesture on the
 *    axis has taken temporary control of the range. New data still widens it;
 *    nothing narrows it back.
 *  - [LOCKED]: an override that has been pinned, so no further gesture moves it.
 *  - [MANUAL]: autoscale is off - the range came from "Set Y range" and is the
 *    persistent answer until the setting changes.
 *
 * The distinction that matters: [OVERRIDDEN] and [LOCKED] are *not* autoscale
 * being switched off. The setting in the options menu still says auto, and
 * touching the plot never changes it - which is why Release exists as a way back
 * that does not involve reopening the menu.
 */
enum class YAxisControl { FITTED, OVERRIDDEN, LOCKED, MANUAL }

/**
 * How the x axis is being decided right now - the time axis' half of the same
 * idea, and deliberately the same shape as [YAxisControl].
 *
 *  - [FOLLOWING]: the range is derived from now on every frame. Live, Pin and
 *    Auto are all this: their right edge grows with the clock.
 *  - [HELD]: a gesture has frozen an absolute range, so new readings arrive
 *    without moving what is on screen.
 *  - [LOCKED]: a held range that has been pinned, so no further gesture moves it.
 *
 * The distinction that matters is the same one the y axis draws: [HELD] and
 * [LOCKED] are not the live view being switched off. Every button that frames a
 * view - Live, Pin, Auto, a window preset - still works and hands the axis back,
 * which is why Release exists as a way out that does not require knowing which
 * of them to press.
 */
enum class XAxisControl { FOLLOWING, HELD, LOCKED }

/** Immutable drawable state produced by [ChartView.compute]. */
data class ChartData(
    val x: DoubleArray,
    val series: Map<String, DoubleArray>,
    val start: Double,
    val end: Double,
    val xTicks: DoubleArray,
    val xLabels: List<String>,
    /** Date shown under a tick where the day changes; blank on other ticks. */
    val xDates: List<String> = emptyList(),
    /**
     * True when the ticks are a day or more apart, so [xLabels] are dates rather
     * than times of day. The axis caption follows this - "Time of day" under a
     * row reading "Aug 3, Aug 4" would be naming the wrong quantity.
     */
    val xLabelsAreDates: Boolean = false,
    /** Short name of the zone the x axis is plotted in, e.g. "EDT". */
    val timeZoneLabel: String = "",
    /**
     * Extent of the *drawn* series over the current view, or null when nothing
     * is in view. Not the extent of every field: the four PM cuts are
     * cumulative, so an extent over all of them is pm10's extent, and fitting
     * the axis to a curve that is not on screen is what left the drawn trace in
     * a thin band however far the user zoomed in.
     */
    val dataYMin: Double?,
    val dataYMax: Double?,
    val spanSeconds: Double,
    val sampleCount: Int,
    val mode: ChartMode,
    val viewMode: ViewMode,
    val windowSeconds: Double,
    val yAuto: Boolean,
    val yMin: Double?,
    val yMax: Double?,
    /**
     * The range the axis must actually span, already resolved: the manual range,
     * the temporary override, or the fit of this view - whichever is in force.
     * The renderer draws this rather than re-deriving it, so the axis on screen,
     * the axis in the exported PNG and the numbers the range dialog opens with
     * cannot disagree.
     */
    val yFloor: Double? = null,
    val yCeil: Double? = null,
    /** Gridline interval for [yFloor]..[yCeil]; the one the range was snapped to. */
    val yStep: Double = 1.0,
    /** How [yFloor]..[yCeil] was arrived at - and which controls that implies. */
    val yControl: YAxisControl = YAxisControl.FITTED,
    val xFrozen: Boolean,
    /** How [start]..[end] was arrived at - and which controls that implies. */
    val xControl: XAxisControl = XAxisControl.FOLLOWING,
    val hasReferencePin: Boolean,
    /**
     * The instant of the global pin time mark, or null while none is set - the
     * same fact [hasReferencePin] states, carried as the instant itself so the
     * renderer can draw the mark where it falls. The mark is the one navigation
     * anchor the user places deliberately (the long press on Pin), so the plot
     * shows it as a mark whenever it is inside the view; a mark that could only
     * be found by navigating back to it would not be a mark.
     */
    val referencePinEpoch: Double? = null,
    /** Averaging bucket applied to these points, or null when they are raw. */
    val bucketSeconds: Double? = null,
    /**
     * Time gap beyond which two consecutive points must not be joined by a line,
     * or null when the run is too short to judge.
     *
     * Nothing was recorded across such a gap, so a line drawn over it is an
     * invention - and a confident-looking one, since it interpolates smoothly
     * between two moments that may be days apart. Left to the renderer rather
     * than expressed by splitting the series here, because every consumer other
     * than the polyline (autoscale, the crosshair, export) wants the points
     * whole.
     */
    val gapSeconds: Double? = null,
    /** True when the bucket is being chosen automatically from the visible span. */
    val autoInterval: Boolean = false,
    /** The averaging auto last settled on, offered as its own button. */
    val autoChosenSeconds: Double? = null,
    /**
     * How many stops the Pin button's tap cycle has here - the recent marks plus
     * Live - or 0 when there is no cycle worth showing. See
     * [ChartView.pinCycleStops].
     */
    val pinCycleStops: Int = 0,
    /** Which of [pinCycleStops] the view is on; the last index is Live. */
    val pinCycleAt: Int = 0,
    /**
     * The interval the x gridlines are drawn at, in seconds - what the
     * tick-density control shows on its face, so the axis states its own
     * gradation. Zero only in a hand-built [ChartData] that never met
     * [ChartView.compute].
     */
    val tickStepSeconds: Long = 0,
    /** How many rungs finer than the window's own interval those ticks are. */
    val tickDensity: Int = 0,
)

/**
 * View state plus the computation of what to draw - a full Kotlin port of
 * `chartview.ChartView`. Holds no drawing; [compute] returns plain data.
 *
 * Modes: [ChartMode.MOVING] (right edge tracks now, left = now - window) and
 * [ChartMode.PINNED] (left edge fixed at [pinStart], right grows with now). An
 * [xFixed] absolute range, when set, overrides mode. Y is either autoscaled or
 * pinned to (yMin, yMax).
 */
class ChartView(windowSeconds: Double = 300.0) {

    var windowSeconds: Double = windowSeconds
        private set
    var mode: ChartMode = ChartMode.MOVING
        private set
    var pinStart: Double? = null
        private set
    /**
     * The absolute x range in force, or null while the axis follows now.
     *
     * The setter is what keeps [xLocked] honest. Ten places clear this field -
     * Live, Pin, Auto, every window preset, each of the pin-cycle stops - and a
     * lock left standing after one of them would refuse every later gesture over
     * a range that no longer exists, with no control on screen offering to undo
     * it, because the control only appears while a range is held. Dropping the
     * lock here means a hold cannot outlive the thing it holds, whoever writes
     * the next button.
     */
    var xFixed: Pair<Double, Double>? = null
        private set(value) {
            field = value
            if (value == null) xLocked = false
        }
    // When set, the view shows the whole session [allStart, now] ("all" button),
    // overriding mode/xFixed/pinStart until any other navigation clears it.
    private var allStart: Double? = null
    var yAuto: Boolean = true
        private set
    var yMin: Double? = null
        private set
    var yMax: Double? = null
        private set

    // Recent pin origins (newest first) that the Pin button cycles through, plus
    // a separate user-set reference pin the long-press snaps to.
    var referencePinTime: Double? = null
        private set
    var pinCycleCount: Int = DEFAULT_PIN_CYCLE
        private set
    private val pinHistory = ArrayDeque<Double>()
    private var pinCycleIndex = 0

    /**
     * How many rungs finer than the window's own interval the x gridlines are
     * drawn - the tick-density control's state.
     *
     * A rung offset rather than an absolute interval, deliberately: the
     * absolute steps it produces are always relative to the current window
     * (see [TimeTicks.finerInterval]), so zooming from days into an hour keeps
     * the control meaning "this much finer than the axis would choose itself"
     * instead of pinning minutes onto a month. Survives navigation for the
     * same reason.
     */
    var tickDensity: Int = 0
        private set

    /**
     * Step the gridlines one rung finer, wrapping back to the window's own
     * interval - immediate and reversible, no dialog and no mode. The wrap
     * point comes from [TimeTicks.densityStops], so the cycle never contains
     * two stops that draw the same axis.
     */
    fun cycleTickDensity(nowEpoch: Double) {
        val (start, end) = visibleRange(nowEpoch)
        tickDensity = (tickDensity + 1) % TimeTicks.densityStops(end - start)
    }

    /**
     * True while the Pin cycle is parked on its Live stop.
     *
     * The cycle's last stop is Live, so one button both walks the recent marks
     * and hands the view back to the present. That leaves one thing to
     * distinguish: arriving at Live *by stepping round the cycle* is navigation
     * and must not mark anything, whereas pressing Pin from a view the user
     * framed by hand is the deliberate act that creates a mark. This flag is the
     * difference between the two, and any navigation clears it - once the user
     * has moved the view themselves they are no longer on the cycle, and the
     * next press marks where they now are.
     */
    private var parkedAtLive = false

    /** Derived, user-facing mode. */
    fun viewMode(): ViewMode = when {
        allStart != null -> ViewMode.ALL
        mode == ChartMode.PINNED -> ViewMode.PIN
        xFixed != null -> ViewMode.LIVE_FROZEN
        else -> ViewMode.LIVE
    }

    fun setPinCycleCount(n: Int) {
        pinCycleCount = n.coerceAtLeast(1)
    }

    // --- window / mode ------------------------------------------------------
    fun setWindow(seconds: Double) {
        require(seconds > 0) { "window must be positive, got $seconds" }
        parkedAtLive = false
        windowSeconds = seconds
    }

    fun setMode(mode: ChartMode, nowEpoch: Double) {
        allStart = null
        parkedAtLive = false
        if (mode == ChartMode.PINNED && pinStart == null) {
            pinStart = nowEpoch - windowSeconds
        }
        this.mode = mode
    }

    fun toggleMode(nowEpoch: Double): ChartMode {
        setMode(if (mode == ChartMode.MOVING) ChartMode.PINNED else ChartMode.MOVING, nowEpoch)
        return mode
    }

    /**
     * The x range to draw, with any impossible start refused.
     *
     * Every path that asks where the axis is - drawing, panning, zooming,
     * freezing, "Live" - comes through here, which is why the guard lives here
     * and not at each of them. See [sane].
     */
    fun visibleRange(nowEpoch: Double): Pair<Double, Double> {
        val (start, end) = proposedRange(nowEpoch)
        return sane(start, end, nowEpoch)
    }

    private fun proposedRange(nowEpoch: Double): Pair<Double, Double> {
        allStart?.let { return it to nowEpoch }
        xFixed?.let { return it }
        if (mode == ChartMode.PINNED) {
            val start = pinStart ?: (nowEpoch - windowSeconds)
            return start to nowEpoch
        }
        return (nowEpoch - windowSeconds) to nowEpoch
    }

    /**
     * Refuse a range that starts before this project could have recorded anything.
     *
     * A view is built from the data, so a corrupt reading becomes a corrupt view:
     * one point that landed at the epoch once took the left edge with it, and the
     * chart drew a fifty-six-year axis, labelled it with dates from the 1970s and
     * averaged the whole recording into two points. Each of those steps was
     * correct given its input. The axis is where that stops being an excuse.
     *
     * The leak that produced it is fixed at its source ([TimeSeriesHistory] can no
     * longer place an undated reading), so this is a backstop rather than the
     * repair - and it is kept because the class of fault is "something handed the
     * chart a nonsense instant", which no single fix upstream can rule out
     * forever. Refusing is a clamp rather than an error: the user gets the newest
     * [windowSeconds] of a plausible axis, which is a view of their data, where
     * rendering the request faithfully is a view of nothing.
     */
    private fun sane(start: Double, end: Double, nowEpoch: Double): Pair<Double, Double> {
        if (start >= EARLIEST_PLAUSIBLE_EPOCH) return start to end
        val safeEnd = maxOf(end, nowEpoch, EARLIEST_PLAUSIBLE_EPOCH + MIN_WINDOW)
        val safeStart = maxOf(EARLIEST_PLAUSIBLE_EPOCH, safeEnd - windowSeconds)
        return safeStart to maxOf(safeEnd, safeStart + MIN_WINDOW)
    }

    // --- x-axis freeze / pan / zoom -----------------------------------------
    //
    // The time axis has the same two states the y axis does and for the same
    // reason. A range framed by hand is work - find the excursion, get its
    // beginning and its end onto the plot - and until now the only thing
    // defending it was that no reading moves a frozen range. That covers the
    // sensor and not the hand: a stray finger on the glass still panned it away,
    // and the way back was to re-frame it from scratch.
    //
    // So a held range can be locked, exactly as an overridden y range can, and
    // released the same way. What lock stops is gestures, which is what lock is
    // for; the buttons that frame a view are untouched, because a control that
    // could strand the user in a range they cannot leave would be a worse fault
    // than the stray touch it defends against.

    fun xFrozen(): Boolean = xFixed != null

    /** True when a held range is pinned against further gestures. */
    var xLocked: Boolean = false
        private set

    fun xControl(): XAxisControl = when {
        xFixed == null -> XAxisControl.FOLLOWING
        xLocked -> XAxisControl.LOCKED
        else -> XAxisControl.HELD
    }

    /** Pin (or unpin) the held range. Meaningless, and ignored, without one. */
    fun setXLocked(locked: Boolean) {
        if (xFrozen()) xLocked = locked
    }

    /**
     * Hand the axis back to following now. Works whether or not it was locked,
     * for the reason [releaseYOverride] does: an unlock-then-release pair is a
     * second tap that answers no question the user asked.
     */
    fun releaseXRange() = clearXRange()

    fun setXRange(xMin: Double, xMax: Double) {
        require(xMin < xMax) { "xMin must be < xMax, got $xMin >= $xMax" }
        allStart = null
        parkedAtLive = false
        // A stated range is a fresh statement of intent, as it is on the y axis,
        // so it replaces the hold rather than arriving underneath a lock the user
        // set on some earlier range.
        xLocked = false
        xFixed = xMin to xMax
    }

    fun clearXRange() {
        parkedAtLive = false
        xFixed = null
    }

    fun freezeX(nowEpoch: Double) {
        val (start, end) = visibleRange(nowEpoch)
        allStart = null
        parkedAtLive = false
        xFixed = start to end
    }

    fun xAutoscale(on: Boolean, nowEpoch: Double) {
        if (on) clearXRange() else freezeX(nowEpoch)
    }

    fun pan(deltaSeconds: Double, nowEpoch: Double) {
        if (xLocked) return
        parkedAtLive = false
        if (allStart != null) freezeX(nowEpoch) // leave "all": freeze the session view, then pan it
        val fixed = xFixed
        when {
            fixed != null -> xFixed = (fixed.first + deltaSeconds) to (fixed.second + deltaSeconds)
            mode == ChartMode.PINNED -> {
                val start = pinStart ?: visibleRange(nowEpoch).first
                pinStart = start + deltaSeconds
            }
            else -> {
                val (start, end) = visibleRange(nowEpoch)
                xFixed = (start + deltaSeconds) to (end + deltaSeconds)
            }
        }
    }

    fun shiftPin(deltaSeconds: Double, nowEpoch: Double) {
        allStart = null
        parkedAtLive = false
        if (mode != ChartMode.PINNED) setMode(ChartMode.PINNED, nowEpoch)
        pinStart = (pinStart ?: (nowEpoch - windowSeconds)) + deltaSeconds
    }

    fun zoom(factor: Double, nowEpoch: Double) {
        require(factor > 0) { "zoom factor must be positive, got $factor" }
        if (xLocked) return
        parkedAtLive = false
        if (allStart != null) freezeX(nowEpoch) // leave "all": freeze the session view, then zoom it
        val fixed = xFixed
        when {
            fixed != null -> {
                val center = (fixed.first + fixed.second) / 2.0
                val half = (fixed.second - fixed.first) / 2.0 * factor
                xFixed = (center - half) to (center + half)
            }
            mode == ChartMode.PINNED -> {
                val pin = pinStart ?: visibleRange(nowEpoch).first
                pinStart = nowEpoch - (nowEpoch - pin) * factor
            }
            else -> setWindow(windowSeconds * factor)
        }
    }

    // --- y-axis -------------------------------------------------------------
    //
    // Three things decide the range, in priority order: the manual range (auto
    // off), the temporary override (a gesture on the axis while auto is on), and
    // otherwise the fit of whatever is in view.
    //
    // The override exists because the axis a reading deserves and the axis a
    // person wants to look at are different things. Readings hovering between
    // 2.9 and 3.1 fill a tightly fitted plot with dramatic-looking activity; what
    // that actually is is "about 3, wiggling", and saying so needs an axis
    // deliberately wider than the data requires. An autoscale that re-fitted on
    // the next reading would undo that within the second, so the override has to
    // survive - and it survives without touching the setting, because the
    // setting is a rarely-made decision and re-opening a menu after every stray
    // touch would be intolerable.

    /** The temporary override, or null when the axis is fitting the view. */
    private var overrideMin: Double? = null
    private var overrideMax: Double? = null

    /**
     * A level the automatic fit must keep in view, or null to fit the data
     * alone - null unless the "autoscale includes alert level" setting is on
     * (see `AppConfig.autoscaleAlertLevel`, which is what the session feeds
     * this from).
     *
     * It participates only in the *fit*: a manual range and a gesture override
     * are the user's own numbers, and the override's widening rule answers to
     * the readings, not to a line the user can already see is off-screen.
     */
    var alertY: Double? = null

    /** True when the override is pinned against further gestures. */
    var yLocked: Boolean = false
        private set

    /** True when a gesture has taken temporary control of the range. */
    fun yOverridden(): Boolean = yAuto && overrideMin != null && overrideMax != null

    fun yControl(): YAxisControl = when {
        !yAuto -> YAxisControl.MANUAL
        !yOverridden() -> YAxisControl.FITTED
        yLocked -> YAxisControl.LOCKED
        else -> YAxisControl.OVERRIDDEN
    }

    fun setYRange(min: Double, max: Double) {
        require(min < max) { "yMin must be < yMax, got $min >= $max" }
        yAuto = false
        // An explicit range is a fresh statement of intent, so it replaces the
        // override rather than layering on top of it.
        releaseYOverride()
        // A stated range keeps the top it states and has its bottom lifted to
        // zero, rather than being slid upwards the way a gesture is: the user
        // typed both numbers, and the one that cannot be honoured is the only one
        // that should move. See [atOrAboveZero].
        val bottom = maxOf(min, 0.0)
        yMin = bottom
        yMax = maxOf(max, bottom + MIN_Y_SPAN)
    }

    /**
     * The autoscale setting itself.
     *
     * Switching it off adopts whatever range is on screen at that moment - the
     * fit of the current view, or the override if one is in force. The old
     * behaviour only seeded a range the very first time, so turning autoscale
     * off a second time silently restored a range from some earlier session and
     * the plot jumped. Adopting what is displayed makes the switch a freeze
     * rather than a recall, and it is what leaves the range dialog holding the
     * values autoscaling had chosen for this view.
     */
    fun yAutoscale(on: Boolean, dataMin: Double? = null, dataMax: Double? = null) {
        if (on) {
            yAuto = true
            releaseYOverride()
        } else {
            // The range as drawn, so switching the setting off freezes the plot
            // where it stands instead of nudging it to an unsnapped fit.
            val adopted = lastRange ?: displayedYRange(dataMin, dataMax)
            val (lo, hi) = atOrAboveZero(adopted.first, adopted.second)
            yAuto = false
            releaseYOverride()
            yMin = lo
            yMax = hi
        }
    }

    /**
     * Hand the axis back to autoscaling. Works whether or not it was locked -
     * being unable to release a locked axis without first unlocking it is a
     * second step for no gain.
     */
    fun releaseYOverride() {
        overrideMin = null
        overrideMax = null
        yLocked = false
    }

    /** Pin (or unpin) the override so a stray touch cannot move it. */
    fun setYLocked(locked: Boolean) {
        if (yOverridden()) yLocked = locked
    }

    /**
     * Shift the range by [fraction] of the plot height, positive dragging down.
     *
     * Dragging the axis down has to carry the curve down with it, which means
     * the range moves *up*: the value under a given pixel increases.
     */
    fun panY(fraction: Double, dataMin: Double? = null, dataMax: Double? = null) {
        if (fraction == 0.0 || !fraction.isFinite()) return
        adjustY(dataMin, dataMax) { lo, hi ->
            val shift = (hi - lo) * fraction
            (lo + shift) to (hi + shift)
        }
    }

    /**
     * Scale the range about [anchor]. [factor] > 1 widens it (fingers pinched
     * together), matching the sense of the time axis' own zoom.
     *
     * [anchor] is a fraction of the range measured from its **bottom**: 0 holds
     * the bottom still and moves only the top, 1 holds the top, and
     * [Y_ANCHOR_CENTRE] moves both ends by as much, which is what this used to do
     * and always do.
     *
     * ## Why an anchor at all
     *
     * Scaling about the centre moves both bounds together, so there is no way to
     * hold one end still and move the other. An axis autoscaling had settled at
     * 8 to 10, wanted as 8 to 15, was unreachable: every factor that raised the
     * top lowered the bottom by exactly as much. The standard remedy is to scale
     * about the gesture's own focal point - the value under the middle of the
     * fingers stays where it is on the glass while the rest of the axis grows or
     * shrinks away from it - and that is precisely the missing control. Fingers
     * near the bottom of the plot hold the bottom; fingers near the top hold the
     * top; fingers in the middle behave exactly as before.
     *
     * The floor still has the last word. An anchor high in the range can drive
     * the bottom below zero, and [adjustY] lifts the whole span back up rather
     * than squashing it - see [atOrAboveZero].
     */
    fun zoomY(
        factor: Double,
        dataMin: Double? = null,
        dataMax: Double? = null,
        anchor: Double = Y_ANCHOR_CENTRE,
    ) {
        if (factor <= 0 || factor == 1.0 || !factor.isFinite()) return
        // A focal point off the plot, or none at all, is not a reason to refuse
        // the gesture: it is a reason to fall back on the end nearest to it.
        val at = if (anchor.isFinite()) anchor.coerceIn(0.0, 1.0) else Y_ANCHOR_CENTRE
        adjustY(dataMin, dataMax) { lo, hi ->
            val fixed = lo + (hi - lo) * at
            val span = ((hi - lo) * factor).coerceAtLeast(MIN_Y_SPAN)
            (fixed - span * at) to (fixed + span * (1.0 - at))
        }
    }

    /**
     * Apply a gesture to whichever range the axis is currently showing, creating
     * the override if this is the first such gesture.
     *
     * A gesture on the axis while autoscale is *off* simply edits the manual
     * range: it is already the user's range, so there is nothing to override and
     * no controls to offer. Either way the autoscale setting is untouched.
     */
    private fun adjustY(
        dataMin: Double?,
        dataMax: Double?,
        move: (Double, Double) -> Pair<Double, Double>,
    ) {
        if (yLocked) return
        // What the axis is showing, snapping included - the thing under the
        // finger, rather than the range it was derived from.
        val (lo, hi) = lastRange ?: displayedYRange(dataMin, dataMax)
        val moved = move(lo, hi)
        if (!moved.first.isFinite() || !moved.second.isFinite()) return
        if (moved.second - moved.first < MIN_Y_SPAN) return
        val (newLo, newHi) = atOrAboveZero(moved.first, moved.second)
        if (yAuto) {
            overrideMin = newLo
            overrideMax = newHi
        } else {
            yMin = newLo
            yMax = newHi
        }
        // The next gesture starts from what this one left, not from the range
        // last *drawn*. Two gestures can land between one frame and the next -
        // the y axis is now shifted and scaled by the same two fingers, and a
        // flush can carry some of each - and reading the stale drawn range would
        // make the second of them re-derive from before the first and throw it
        // away. Nothing is lost by moving it early: the axis a gesture has just
        // set is answered exactly, never snapped (see [resolveYRange]), so this
        // is precisely what the next frame will draw.
        lastRange = newLo to newHi
    }

    /**
     * Let data that falls outside the override extend it - and only extend it.
     *
     * Re-fitting would be wrong here even though it looks like the same thing:
     * a reading above the top would drag the bottom back up with it and throw
     * away the roomy axis the user chose. So each end moves outwards on its own
     * or not at all.
     *
     * The test is against the readings themselves, not against the range
     * autoscaling would have picked for them. Those differ, and the difference
     * bites: a spike to 80 makes the automatic fit start at zero, because its
     * margin below 2.9 would have gone negative and got clamped - so widening on
     * the fit would have dropped the bottom of a 0.68-to-5.32 axis to zero on
     * account of a reading at the *top*. The end that moves is the end the data
     * actually left.
     *
     * Widening applies while locked too. Lock is about a stray finger, and an
     * axis that hid a reading in order to stay put would be a worse failure than
     * the one lock exists to prevent.
     */
    private fun widenOverride(dataMin: Double?, dataMax: Double?) {
        val lo = overrideMin ?: return
        val hi = overrideMax ?: return
        // Enough room that a new extreme is not drawn on the frame itself, taken
        // from the range the user chose rather than from the data - the whole
        // point of the override is that those two are different.
        val margin = (hi - lo) * Y_MARGIN
        if (dataMax != null && dataMax > hi) overrideMax = dataMax + margin
        if (dataMin != null && dataMin < lo) {
            overrideMin = maxOf(dataMin - margin, 0.0)
        }
    }

    /**
     * The gridline interval that goes with [lastRange]. Carried rather than
     * re-derived by the renderer: snapping and labelling have to use the same
     * step, and deriving it twice let them disagree whenever snapping carried
     * the span across 1.0 - the axis then snapped to whole numbers and labelled
     * itself in fifths, or the reverse.
     */
    private var lastStep: Double = 1.0

    /**
     * The range the axis was last resolved to - what is on the glass right now.
     *
     * A gesture has to start from this and not from the underlying fit, because
     * the two differ by the snapping: seeding from the fit made the first touch
     * on the axis jump the range by up to half again before it had moved at all.
     */
    private var lastRange: Pair<Double, Double>? = null

    /**
     * The range the axis spans for data covering [dataMin]..[dataMax] - widened,
     * resolved and snapped, exactly as it will be drawn.
     *
     * Called once per frame by [compute]. Separate from it so the y rules can be
     * exercised on ranges alone: the rules are about ranges, not about readings,
     * and going through a history to reach them would add nothing but the need
     * to invent data to put in it. Public rather than internal for exactly that
     * reason: the gesture tests that exercise the rules live in :app, on the
     * other side of the module boundary, with the gestures.
     */
    fun resolveYRange(
        dataMin: Double?,
        dataMax: Double?,
        logY: Boolean = false,
    ): Pair<Double, Double> {
        widenOverride(dataMin, dataMax)
        val (lo, hi) = displayedYRange(dataMin, dataMax)
        // Snapping is for an axis the chart chose. A range the user set - from
        // the dialog, or by dragging the axis - is answered exactly, because
        // rounding it outward would quietly move the numbers they picked. A log
        // axis is not snapped either; the renderer floors and decades it instead.
        val snap = yControl() == YAxisControl.FITTED && !logY
        lastStep = if (snap) fittingStep(lo, hi) else niceStep(hi - lo)
        val moved = if (snap) snapToSteps(lo, hi, lastStep) else lo to hi
        // The last word on the floor, whatever decided the range. Every route to
        // a lower bound clamps for itself, and this is here so that a route added
        // later cannot be the one that forgets: a particle concentration below
        // zero is not a reading the sensor can take, so it is not a value the
        // axis may span. See [atOrAboveZero].
        val range = atOrAboveZero(moved.first, moved.second)
        lastRange = range
        return range
    }

    /** The range on screen right now, whatever is deciding it. */
    private fun displayedYRange(dataMin: Double?, dataMax: Double?): Pair<Double, Double> {
        if (!yAuto) {
            val lo = yMin
            val hi = yMax
            if (lo != null && hi != null && lo < hi) return lo to hi
        }
        val lo = overrideMin
        val hi = overrideMax
        if (yAuto && lo != null && hi != null) return lo to hi
        return autoYRange(dataMin, dataMax, alertY)
    }

    // --- Live / Pin buttons -------------------------------------------------

    /**
     * The "Live" button: return to a clean moving window whose size is the
     * currently displayed span, so what you were looking at stays put but starts
     * following now again. (In clean Live the span already equals [windowSeconds].)
     */
    fun goLive(nowEpoch: Double) {
        val (start, end) = visibleRange(nowEpoch)
        windowSeconds = (end - start).coerceAtLeast(MIN_WINDOW)
        allStart = null
        parkedAtLive = false
        mode = ChartMode.MOVING
        xFixed = null
    }

    /**
     * The "all" button: show the whole current acquisition session -
     * [sessionStartEpoch] (the first stable reading after the last Restart) to now -
     * autoscaling x so all the data fills the plot width, right edge growing with now.
     * A dedicated [ViewMode.ALL]; any other navigation returns to normal modes.
     */
    fun applyAll(sessionStartEpoch: Double, nowEpoch: Double) {
        xFixed = null
        parkedAtLive = false
        mode = ChartMode.MOVING
        // The one place a corrupt origin gets to set the *size* of the window as
        // well as its position, so it is refused here rather than only clamped
        // when drawn: taken in, it would leave windowSeconds at half a century
        // and go on poisoning every later view built from it.
        if (sessionStartEpoch < EARLIEST_PLAUSIBLE_EPOCH) {
            allStart = null
            return
        }
        allStart = minOf(sessionStartEpoch, nowEpoch)
        windowSeconds = (nowEpoch - allStart!!).coerceAtLeast(MIN_WINDOW)
    }

    /**
     * A window-size button. In Live/Live-frozen it enters a clean live window of
     * [seconds]. In Pin mode it means "[seconds] of time, beginning at the pin":
     * the window runs from [pinStart] forward by the chosen duration, and the
     * pin itself is untouched, so every further size tap measures from the same
     * mark until the user unpins.
     *
     * It used to re-pin the origin at now - [seconds] instead, which read as the
     * button quietly discarding the mark the user had just placed: pin an
     * instant, tap 30m, and the pin was replaced by "half an hour ago" - a
     * moment nobody chose.
     *
     * ## The record boundary
     *
     * A pin close to the record's end would put most of a forward window past
     * the newest reading. The clamp is [snapWindowStart]'s, for the same reason:
     * the start comes no further right than [dataEnd] minus [seconds], so the
     * window holds a full span of record when the record has that much, and
     * simply starts where the record does when it is shorter. The pin stays
     * set - and drawn - wherever it is; only the framing slides.
     */
    fun applyWindow(
        seconds: Double,
        nowEpoch: Double,
        dataStart: Double? = null,
        dataEnd: Double? = null,
    ) {
        require(seconds > 0) { "window must be positive, got $seconds" }
        allStart = null
        parkedAtLive = false
        windowSeconds = seconds
        if (viewMode() == ViewMode.PIN) {
            val pin = pinStart ?: (nowEpoch - seconds)
            pinStart = pin
            val limit = dataEnd?.let { maxOf(dataStart ?: it, it - seconds) }
            val start = if (limit != null) minOf(pin, limit) else pin
            // A held range under a PINNED mode: the mode is what keeps the view
            // pinned (and the next size tap measuring from the same mark), the
            // range is this tap's framing. setXRange so a lock on some earlier
            // range is released, exactly as it is for every other fresh framing.
            setXRange(start, start + seconds)
        } else {
            mode = ChartMode.MOVING
            xFixed = null
        }
    }

    /**
     * Snap the visible window so [epoch] becomes its **left edge**: "start the
     * view here". Everything to the crosshair's right stays on screen;
     * everything to its left moves out of view.
     *
     * A pan, never a zoom - the span on screen is measured first and the window
     * keeps it exactly, in every mode and at every boundary. The result is a
     * held range, the same [ViewMode.LIVE_FROZEN] a hand-panned view lands in,
     * so every existing way out (Live, Pin, Auto, the presets) applies
     * unchanged. Like those buttons it is a fresh statement of intent: it
     * frames a view rather than gesturing at one, so it goes through
     * [setXRange] and an x lock is released rather than obeyed.
     *
     * ## The data boundary
     *
     * Asked to start at the newest reading, a faithful answer would be a window
     * that is one data point on the left edge and emptiness after it. The snap
     * clamps instead of obliging: the start comes no further right than
     * [dataEnd] minus the span, so the window it frames always holds a span's
     * worth of record when the record has that much ([snapWindowEnd] clamps
     * mirror-image at [dataStart]). When the whole record is shorter than the
     * span, the window simply starts where the record does. The crosshair's
     * point is inside the view in every case - at the requested edge whenever
     * the data allows it.
     */
    fun snapWindowStart(epoch: Double, dataStart: Double?, dataEnd: Double?, nowEpoch: Double) {
        val (start, end) = visibleRange(nowEpoch)
        val span = end - start
        val limit = dataEnd?.let { maxOf(dataStart ?: it, it - span) }
        val newStart = if (limit != null) minOf(epoch, limit) else epoch
        mode = ChartMode.MOVING
        setXRange(newStart, newStart + span)
    }

    /**
     * Snap the visible window so [epoch] becomes its **right edge**: "end the
     * view here". The mirror image of [snapWindowStart], with the same span
     * promise and the same clamp - the end comes no further left than
     * [dataStart] plus the span, so snapping on the oldest reading still frames
     * a full window of record instead of emptiness before one point.
     */
    fun snapWindowEnd(epoch: Double, dataStart: Double?, dataEnd: Double?, nowEpoch: Double) {
        val (start, end) = visibleRange(nowEpoch)
        val span = end - start
        val limit = dataStart?.let { minOf(dataEnd ?: it, it + span) }
        val newEnd = if (limit != null) maxOf(epoch, limit) else epoch
        mode = ChartMode.MOVING
        setXRange(newEnd - span, newEnd)
    }

    /**
     * A short tap on "Pin": step to the next stop on the cycle.
     *
     * The stops are the [pinCycleCount] most recent pin marks, newest first, and
     * then Live. One button therefore does what a Pin button and a Live button
     * used to do together, and the framings the user has been working in are a
     * tap apart instead of lost:
     *
     * - from a view framed by hand, the press enters Pin at the current left
     *   edge, which is the one act that creates a mark;
     * - each further press hands back an earlier framing;
     * - the press after the oldest one returns the view to the present;
     * - and the press after *that* goes back to the newest mark rather than
     *   marking the live view, because stepping round the cycle is navigation
     *   and marks are only ever made deliberately.
     *
     * With a single mark the stops are just that mark and Live, so the button is
     * the plain Pin/Live toggle it has been - the cycle only appears once there
     * is more than one framing to return to. See [pinCycleStops] for what the
     * button shows of all this.
     */
    fun pinTap(nowEpoch: Double) {
        when {
            parkedAtLive -> {
                // Back onto the marks. Nothing is captured: the live view being
                // left is the cycle's own Live stop, not a framing the user chose.
                parkedAtLive = false
                val newest = pinHistory.firstOrNull()
                if (newest == null) enterPinAt(visibleRange(nowEpoch).first)
                else { pinCycleIndex = 0; restorePin(newest) }
            }
            viewMode() == ViewMode.PIN -> stepCycle(nowEpoch)
            else -> enterPinAt(visibleRange(nowEpoch).first)
        }
    }

    /**
     * The "Unpin" button: leave Pin in one press, from anywhere in the cycle.
     *
     * The cycle's own way back to the present is its last stop, which means
     * reaching it costs a press per saved mark - and reaching it is not a rare
     * thing to want, because the long press that sets the reference mark only
     * does so from outside Pin. So getting out of Pin was gated behind stepping
     * through every framing the user had saved, in order to do something that has
     * nothing to do with any of them.
     *
     * It lands in exactly the state the cycle's Live stop lands in, parked flag
     * and all, so the Pin button behaves identically afterwards whichever way the
     * user got here: the next press picks the marks back up rather than
     * manufacturing a new one from a view nobody framed. An escape hatch, not a
     * second Pin button - the cycle itself is untouched.
     */
    fun unpin(nowEpoch: Double) {
        goLive(nowEpoch)
        parkedAtLive = true
    }

    /**
     * How many stops the Pin button's cycle has - the recent marks plus Live -
     * or 0 when the view is not on the cycle at all, or is on a cycle with no
     * more in it than the plain toggle.
     *
     * Zero is what the button uses to decide to say nothing: a control that
     * announces a cycle of one is a control claiming to do more than it does.
     */
    fun pinCycleStops(): Int {
        if (!parkedAtLive && viewMode() != ViewMode.PIN) return 0
        val marks = minOf(pinCycleCount, pinHistory.size)
        return if (marks < 2) 0 else marks + 1
    }

    /** Which stop of [pinCycleStops] the view is on; the last one is Live. */
    fun pinCycleAt(): Int {
        val stops = pinCycleStops()
        if (stops == 0) return 0
        return if (parkedAtLive) stops - 1 else pinCycleIndex.coerceIn(0, stops - 1)
    }

    private fun stepCycle(nowEpoch: Double) {
        val next = pinCycleIndex + 1
        if (next >= minOf(pinCycleCount, pinHistory.size)) {
            // Past the oldest mark: the last stop is the present. goLive clears
            // the flag as any navigation does, so it is set after, not before.
            goLive(nowEpoch)
            parkedAtLive = true
        } else {
            pinCycleIndex = next
            restorePin(pinHistory[next])
        }
    }

    /** Put the view back on an existing mark without recording anything new. */
    private fun restorePin(pin: Double) {
        pinStart = pin
        allStart = null
        xFixed = null
        mode = ChartMode.PINNED
    }

    /**
     * A long press on "Pin". In Pin mode: snap the origin to the reference pin
     * (if one is set). Anywhere else: set the reference pin - the global pin
     * time mark - to the current left edge.
     *
     * What else the press does depends on whether the view is following now:
     *
     *  - In a **held** range (the captain's own workflow: pan the instant that
     *    matters to the left edge, then hold Pin), marking the origin of the
     *    framing *is* the act of pinning there, so the view enters Pin at the
     *    mark - without moving what is on the glass, which stays exactly the
     *    range the hand framed. From here the window-size buttons measure from
     *    the mark (see [applyWindow]), which is what they are for in Pin mode;
     *    setting a mark that the very next button-press abandoned is how the
     *    mark used to just vanish.
     *  - In a **following** view (Live, All), only the mark is set. The left
     *    edge there is sliding with the clock - a bookmark of a moment, not a
     *    framing the user built - and pinning would silently stop the live
     *    scroll the user is watching.
     */
    fun pinLongPress(nowEpoch: Double) {
        if (viewMode() == ViewMode.PIN) {
            referencePinTime?.let { ref ->
                pinStart = ref
                // A framed pin view (a size tap's [pin, pin + w]) snaps its
                // frame to the reference at the same span - just moving
                // pinStart underneath the frame would leave the glass still,
                // and a snap that visibly does nothing reads as a dead button.
                // The lock survives: the range still exists, it moved.
                xFixed?.let { (s, e) -> xFixed = ref to ref + (e - s) }
            }
        } else {
            val mark = visibleRange(nowEpoch).first
            referencePinTime = mark
            if (viewMode() == ViewMode.LIVE_FROZEN) {
                // Enter Pin at the mark, keeping the glass: the held range (and
                // any lock on it) survives, because the press is about the mark,
                // not a request to re-frame the view.
                val held = xFixed
                val lock = xLocked
                enterPinAt(mark)
                xFixed = held
                xLocked = lock
            }
        }
    }

    /**
     * Enter Pin at [pin], recording it as the newest mark.
     *
     * Reached from every deliberate act of pinning - the tap from a hand-framed
     * view, the long press on a held range - and, with [commitManualPin] (a
     * shift of the view while already pinned), these are the only moments that
     * create a mark. Nothing is ever
     * captured from a scrolling view: there the left edge advances with every
     * reading, so marks would be manufactured a second at a time and would flush
     * out the ones the user actually chose.
     */
    private fun enterPinAt(pin: Double) {
        if (pinHistory.firstOrNull() != pin) pinHistory.addFirst(pin)
        while (pinHistory.size > MAX_PIN_HISTORY) pinHistory.removeLast()
        parkedAtLive = false
        pinCycleIndex = 0
        restorePin(pin)
    }

    /**
     * Commit a manually dragged pin so it re-enters the Pin cycle. After the user
     * drags the curve in Pin mode the resulting [pinStart] becomes the newest entry
     * in the cycle set, so tapping Pin brings it back (call on drag end). No-op
     * outside Pin mode. Idempotent when the current pin is already the newest entry.
     */
    fun commitManualPin() {
        if (viewMode() != ViewMode.PIN) return
        val p = pinStart ?: return
        if (pinHistory.firstOrNull() != p) pinHistory.addFirst(p)
        while (pinHistory.size > MAX_PIN_HISTORY) pinHistory.removeLast()
        pinCycleIndex = 0
    }

    // --- plot interval / averaging ------------------------------------------

    /** Explicit averaging bucket in seconds; null or <= 1 means raw samples. */
    var plotIntervalSeconds: Double? = null
        private set

    /** When true the bucket is derived from the visible span on every compute. */
    var autoInterval: Boolean = false
        private set

    /**
     * The averaging auto last settled on, in whole seconds.
     *
     * Auto computes the value that actually suits the data rather than rounding
     * to a preset - constrained to the offered buttons it kept landing on one
     * step too little smoothing and the next step too much. The UI turns this
     * into a button of its own, so the chosen figure is both visible and
     * something the user can toggle back to after looking at raw.
     */
    var autoChosenSeconds: Double? = null
        private set

    fun setPlotInterval(seconds: Double?) {
        // Choosing an interval by hand is an override, so it ends auto mode -
        // but the value auto picked stays available as a button, so raw and
        // "what auto decided" remain one tap apart.
        autoInterval = false
        plotIntervalSeconds = seconds?.takeIf { it > 1.0 }
    }

    /** Let the view pick its own averaging from how much is on screen. */
    fun setAutoInterval() {
        autoInterval = true
        plotIntervalSeconds = null
    }

    /**
     * The bucket actually used for this frame.
     *
     * In auto mode it follows the visible span, which is what makes averaging
     * fall away by itself: zooming in shrinks the span, the target point count
     * stops being the binding constraint, and the ladder drops back to raw
     * samples without the user having to know averaging was ever happening.
     */
    private fun effectiveBucketSeconds(span: Double, maxPoints: Int?): Double? {
        if (!autoInterval) return plotIntervalSeconds
        val target = (maxPoints ?: 600).coerceAtLeast(60)
        val ideal = span / target
        // Averaging only earns its place once raw data would overrun the point
        // budget. Zoomed in far enough that every sample fits, raw is the honest
        // answer - otherwise auto would keep smoothing data that needs no help.
        if (ideal <= RAW_SAMPLE_PERIOD) {
            autoChosenSeconds = null
            return null
        }
        // Whole seconds: sub-second precision in an averaging window is noise in
        // the label and makes no visible difference to the curve.
        val chosen = ceil(ideal).coerceAtLeast(2.0)
        autoChosenSeconds = chosen
        return chosen
    }

    // --- compute ------------------------------------------------------------
    fun compute(
        history: TimeSeriesHistory,
        nowEpoch: Double,
        maxPoints: Int? = null,
        excludeFlagged: Boolean = false,
        visible: Collection<String>? = null,
        logY: Boolean = false,
    ): ChartData {
        val (start, end) = visibleRange(nowEpoch)
        val span = end - start
        val bucket = effectiveBucketSeconds(span, maxPoints)
        // Decimation happens inside the extraction walk, so a multi-day window
        // costs one pass over primitives instead of materialising every point.
        // The y range still reflects the full window, not the emitted subset.
        val w = history.window(start, end, excludeFlagged, maxPoints, bucket)
        // Round times at a round interval, in the zone the axis is plotted in -
        // see [TimeTicks] for the choice and for what daylight saving does to
        // it. The density control steps the interval finer from there, always
        // relative to this window's own choice.
        val tickStep = TimeTicks.finerInterval(span, tickDensity)
        val ticks = TimeTicks.ticks(start, end, TimeSeriesHistory.EASTERN, tickStep)
        val labels = ticks.map { label(it, TimeTicks.labelFormat(tickStep)) }

        // The axis fits what is drawn, over the window that is on screen. Both
        // halves of that matter: the window is what makes zooming in on time
        // re-scale y, and the visible-series filter is what stops pm10 - a curve
        // nobody asked to see - setting the top of the axis.
        val drawn = visible?.takeIf { it.isNotEmpty() }
        val dataMin = extentOf(w.fieldMin, drawn, min = true) ?: w.yMin
        val dataMax = extentOf(w.fieldMax, drawn, min = false) ?: w.yMax
        val (yFloor, yCeil) = resolveYRange(dataMin, dataMax, logY)

        return ChartData(
            x = w.epochs,
            series = w.series,
            start = start,
            end = end,
            xTicks = ticks,
            xLabels = labels,
            // Once a tick is a whole day or more apart from its neighbour the
            // label is already the date, so a second line saying so under it
            // would be the same word twice.
            xDates = if (TimeTicks.labelsAreDates(tickStep)) emptyList() else dateMarks(ticks),
            xLabelsAreDates = TimeTicks.labelsAreDates(tickStep),
            timeZoneLabel = zoneAbbreviation(end),
            dataYMin = dataMin,
            dataYMax = dataMax,
            spanSeconds = span,
            sampleCount = w.totalCount,
            mode = mode,
            viewMode = viewMode(),
            windowSeconds = windowSeconds,
            yAuto = yAuto,
            yMin = yMin,
            yMax = yMax,
            yFloor = yFloor,
            yCeil = yCeil,
            yStep = lastStep,
            yControl = yControl(),
            xFrozen = xFrozen(),
            xControl = xControl(),
            hasReferencePin = referencePinTime != null,
            referencePinEpoch = referencePinTime,
            bucketSeconds = bucket,
            autoInterval = autoInterval,
            autoChosenSeconds = autoChosenSeconds,
            gapSeconds = gapThreshold(w.epochs, bucket),
            pinCycleStops = pinCycleStops(),
            pinCycleAt = pinCycleAt(),
            tickStepSeconds = tickStep,
            tickDensity = tickDensity,
        )
    }

    private fun label(epoch: Double, fmt: DateTimeFormatter): String =
        Instant.ofEpochSecond(epoch.toLong()).atZone(TimeSeriesHistory.EASTERN).format(fmt)

    /**
     * A date for the first tick and for any tick that starts a new day; blank
     * elsewhere.
     *
     * Over a multi-day window the useful question is "where did the day change",
     * and answering it once at the boundary is both less cluttered and easier to
     * scan than repeating the date on every label.
     */
    /**
     * "EDT", "EST" - the zone the axis is actually plotted in.
     *
     * Resolved from a real instant rather than named statically, so it follows
     * daylight saving instead of quietly going an hour wrong for half the year.
     * Note this is the *sensor's* zone, which is what the timestamps carry - not
     * the phone's, which may well differ.
     */
    private fun zoneAbbreviation(epoch: Double): String = runCatching {
        Instant.ofEpochSecond(epoch.toLong())
            .atZone(TimeSeriesHistory.EASTERN)
            .format(FMT_ZONE)
    }.getOrDefault("")

    private fun dateMarks(ticks: DoubleArray): List<String> {
        var previousDay: java.time.LocalDate? = null
        return ticks.map { epoch ->
            val day = Instant.ofEpochSecond(epoch.toLong())
                .atZone(TimeSeriesHistory.EASTERN).toLocalDate()
            val mark = if (day != previousDay) label(epoch, FMT_DATE) else ""
            previousDay = day
            mark
        }
    }

    /** The extent of [fields] (or all of them) taken from a per-field extent map. */
    private fun extentOf(
        source: Map<String, Double>,
        fields: Collection<String>?,
        min: Boolean,
    ): Double? {
        var best: Double? = null
        for ((field, value) in source) {
            if (fields != null && field !in fields) continue
            val b = best
            best = when {
                b == null -> value
                min -> minOf(b, value)
                else -> maxOf(b, value)
            }
        }
        return best
    }

    companion object {
        /**
         * The range autoscaling would choose for data spanning [dataMin]..[dataMax].
         *
         * One definition, used by the axis, by the widening rule and by the
         * range dialog's opening values, so those three cannot drift apart.
         *
         * A margin above the maximum and below the minimum, because a trace that
         * touches the top of the frame reads as clipped - the eye cannot tell
         * whether the peak is the peak or merely where the plot ran out. The
         * exception is a floor at zero: PM readings are non-negative, and an
         * axis that dips below zero to leave room spends the plot's height on a
         * region no reading can ever occupy.
         */
        fun autoYRange(
            dataMin: Double?,
            dataMax: Double?,
            /**
             * A level the range must also reach - the alert line, when the
             * setting asks for it. It can only raise the top: the fit never
             * shrinks below what the data needs, and the bottom stays with the
             * data, because the level is a ceiling being watched, not a
             * reading taken.
             */
            includeLevel: Double? = null,
        ): Pair<Double, Double> {
            val rawLo = dataMin ?: 0.0
            val rawHi = includeLevel
                ?.takeIf { it.isFinite() }
                ?.let { maxOf(dataMax ?: 10.0, it) }
                ?: dataMax ?: 10.0
            var lo = rawLo
            var hi = rawHi
            // A flat trace has no extent to pad, so give it a range to sit in
            // the middle of rather than a zero-height axis.
            if (hi - lo < 1e-6) { lo -= 1.0; hi += 1.0 }
            val pad = (hi - lo) * Y_MARGIN
            lo -= pad
            hi += pad
            // The floor, unconditionally. It used to apply only when the data
            // was itself non-negative, which reads as caution and is not: a
            // negative reading is not a measurement the axis should make room
            // for, it is a number that should never have reached here, and an
            // axis drawn below zero to accommodate it says the sensor measured
            // something it cannot measure.
            if (lo < 0.0) lo = 0.0
            if (hi <= lo) hi = lo + MIN_Y_SPAN
            return lo to hi
        }

        /**
         * [lo]..[hi] with its lower bound at or above zero, keeping the span.
         *
         * A particle concentration cannot be negative, so a y axis reaching below
         * zero spends the plot's height on a region no reading can ever occupy -
         * and reads as though the instrument might report one. Zero is the floor.
         *
         * The span is kept rather than the top, because this is the clamp a
         * *gesture* goes through and a gesture is a statement about how much of
         * the axis to show: fingers asking for a range twenty units tall get one
         * twenty units tall, starting at zero, instead of one cut to fifteen
         * because five of them fell off the bottom. Clamping the bottom alone
         * would also make each successive pinch deliver less than the last, so a
         * user zooming out near zero would find the axis growing more and more
         * reluctantly for no reason they could see.
         *
         * Ranges the chart derives rather than the hand asks for keep their top
         * instead - the top is where the data is, and moving it would hide a
         * reading. See [autoYRange] and [setYRange].
         */
        internal fun atOrAboveZero(lo: Double, hi: Double): Pair<Double, Double> {
            if (lo >= 0.0) return lo to hi
            val span = (hi - lo).coerceAtLeast(MIN_Y_SPAN)
            return 0.0 to span
        }

        /**
         * A round interval - 1, 2, 5 x a power of ten - giving roughly four
         * lines over [range].
         *
         * Whole numbers are preferred, because the axis is for reading levels at
         * a glance and the crosshair is what precision is for. But that is a
         * preference, not a rule: forcing a step of 1 onto a range narrower than
         * 1 either leaves the axis blank or flattens the trace by stretching the
         * range around it. Indoor air sits at 1-3 ug/m3 and moves in hundredths,
         * so that case is the common one, not an edge case.
         */
        internal fun niceStep(range: Double): Double {
            if (range <= 0 || !range.isFinite()) return 1.0
            val target = range / 4.0
            val magnitude = Math.pow(10.0, floor(log10(target.coerceAtLeast(1e-9))))
            val step = listOf(1.0, 2.0, 5.0, 10.0)
                .map { it * magnitude }
                .firstOrNull { it >= target } ?: (10.0 * magnitude)
            return if (range >= 1.0) maxOf(1.0, step) else step
        }

        /**
         * The step to snap a fitted range to, refined until snapping does not
         * cost the trace too much of the plot.
         *
         * Snapping outward to round numbers is what gives the axis labelled
         * lines top and bottom, but it is paid for in height: a range of 1.24
         * rounded to whole numbers becomes 0..3, and the curve then occupies a
         * third of the frame while two thirds show air that was never measured.
         * That is the same complaint as fitting the wrong data - the trace ends
         * up in a band - so the step steps down the round ladder until the
         * snapped axis is within half again of the range actually asked for.
         *
         * Bounded at two rungs: past that the labels crowd, and a step of 1 on a
         * range of 1.24 is only mildly wasteful where 0.02 would be unreadable.
         */
        internal fun fittingStep(lo: Double, hi: Double): Double {
            val span = (hi - lo).coerceAtLeast(1e-9)
            var step = niceStep(span)
            repeat(2) {
                val snapped = snapToSteps(lo, hi, step)
                if (snapped.second - snapped.first <= span * MAX_SNAP_INFLATION) return step
                step = finerStep(step)
            }
            return step
        }

        /** The next rung down the 1-2-5 ladder: 10 -> 5 -> 2 -> 1 -> 0.5 -> ... */
        internal fun finerStep(step: Double): Double {
            val magnitude = Math.pow(10.0, floor(log10(step) + 1e-9))
            val mantissa = step / magnitude
            return when {
                mantissa > 4.5 -> 2.0 * magnitude   // 5 -> 2
                mantissa > 1.5 -> 1.0 * magnitude   // 2 -> 1
                else -> 5.0 * magnitude / 10.0      // 1 -> 0.5
            }
        }

        /**
         * Widen [lo]..[hi] to whole multiples of [step], with at least two
         * intervals so the axis always carries a labelled line at each end.
         *
         * Without this a range that happens to sit between two integers - 1.2 to
         * 1.8, say - contains no labelled value at all, and the axis draws with
         * no gridlines and no numbers.
         */
        internal fun snapToSteps(lo: Double, hi: Double, step: Double): Pair<Double, Double> {
            var bottom = floor(lo / step) * step
            var top = ceil(hi / step) * step
            // A range narrower than one step collapses to a single line; give it two.
            if (top - bottom < step * 1.5) {
                top = bottom + step * 2
            }
            // Readings are non-negative, so do not invent a negative axis. The
            // snap is what pushes the bottom under zero here - the range handed
            // in is already at or above it - so the height it added is given back
            // to the top rather than lost.
            if (bottom < 0) {
                top -= bottom
                bottom = 0.0
            }
            return bottom to top
        }

        /** How much taller than the requested range a snapped axis may become. */
        private const val MAX_SNAP_INFLATION = 1.5

        /** Margin left above the maximum and below the minimum, as a fraction. */
        private const val Y_MARGIN = 0.08

        /** Narrowest the axis may be pinched, in ug/m3. */
        private const val MIN_Y_SPAN = 0.05

        /**
         * The focal point of a scale that moves both bounds by as much - the
         * middle of the range, and what [zoomY] does when nobody says otherwise.
         */
        const val Y_ANCHOR_CENTRE = 0.5

        /**
         * The earliest instant this project could plausibly have recorded.
         *
         * 2026-01-01T00:00:00Z, comfortably before the first commit in this
         * repository (2026-07-18) and therefore before any reading the sensor has
         * ever produced, and fifty-six years after the epoch that a missing or
         * defaulted time collapses to. A start below it is not old data; it is a
         * number standing in for one nobody knew.
         *
         * A date rather than "some large negative offset from now" on purpose: the
         * fault being caught is an absolute one - zero - and a relative bound
         * would have to guess how far back a legitimate archive may reach.
         */
        const val EARLIEST_PLAUSIBLE_EPOCH = 1_767_225_600.0

        /**
         * How far apart two plotted points must be before the line between them
         * is dropped, or null with too few points to establish a normal spacing.
         *
         * Judged against the *median* spacing rather than the mean, because the
         * mean is dragged upwards by exactly the gaps being looked for: one
         * two-day break in an hour of data would raise the mean enough to make
         * itself look ordinary.
         *
         * Both terms matter. Ten times the normal spacing keeps ordinary jitter
         * and the occasional dropped reading connected, which is what a live
         * plot should look like. The absolute floor stops that rule from
         * breaking a 1 Hz trace at every three-second stumble, since ten times
         * one second is still nothing. What survives both is a genuine hole:
         * recording stopped and later started again.
         *
         * Public because it is the one statement of what "a gap" means, and the
         * region statistics (`RegionStats`, fed by :app) must judge gaps by the
         * same rule the plot draws them by - two definitions would let the
         * numbers disagree with the picture.
         */
        fun gapThreshold(epochs: DoubleArray, bucketSeconds: Double?): Double? {
            if (epochs.size < 3) return null
            val deltas = DoubleArray(epochs.size - 1) { epochs[it + 1] - epochs[it] }
            deltas.sort()
            val normal = maxOf(deltas[deltas.size / 2], bucketSeconds ?: 0.0)
            if (normal <= 0.0) return null
            return maxOf(GAP_FACTOR * normal, MIN_GAP_SECONDS)
        }

        private const val GAP_FACTOR = 10.0
        private const val MIN_GAP_SECONDS = 60.0

        /** The sensor's own sampling period; below this there is nothing to average. */
        const val RAW_SAMPLE_PERIOD = 1.0

        const val DEFAULT_PIN_CYCLE = 2
        private const val MAX_PIN_HISTORY = 12
        private const val MIN_WINDOW = 1.0
        private val FMT_DATE = DateTimeFormatter.ofPattern("MMM d")
        private val FMT_ZONE = DateTimeFormatter.ofPattern("zzz")
    }
}

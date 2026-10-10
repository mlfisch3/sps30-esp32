package com.sensirion.sps30.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long a disengaged safety stays open with nothing happening in the sheet. */
private const val SAFETY_IDLE_MS = 20_000L

/**
 * The clear strip at the top of every guarded section that the "SAFETY ON" badge
 * is drawn into.
 *
 * Tall enough for the badge's own top padding plus its `labelLarge` line box and
 * pill padding. It is not tuned by eye: `SafetyBadgeLayoutTest` renders each
 * guarded shape and fails if the badge reaches the first thing below it, so the
 * number is held honest rather than trusted.
 */
private val BADGE_STRIP = 30.dp

/** Where the badge sits inside [BADGE_STRIP], measured from the top of the plate. */
private val BADGE_TOP = 4.dp

/**
 * The inset the guarded content keeps from the plate's own border, left and right.
 *
 * The plate is drawn over the whole section at full width, and the content was
 * laid out at full width too, so the border ran down the first and last column of
 * pixels of every line: the "T" of "These set what the controller records" and the
 * "R" of "Read the sensor every" were sliced lengthwise on the tablet. Nothing was
 * covered up by the badge - that was the earlier fault, and it stayed fixed - and
 * nothing overflowed either. The container and its content simply disagreed about
 * where the container's edge was.
 *
 * A border needs room on the inside, and the guard is the only thing that knows
 * it has a border, so the room is reserved here rather than asked of every
 * section that might be guarded. Sized past the 1.5dp stroke and its 10dp corner
 * radius, so a line of text also clears the curve rather than merely the straight
 * run of the edge.
 */
private val GUARD_INSET = 12.dp

/** How long a "that press did nothing, and here is why" line stays on screen. */
private const val SAFETY_NOTICE_MS = 6_000L

/**
 * The state of one missile-switch-style safety: engaged (guard down, controls
 * covered) or disengaged (ARMED, guard lifted).
 *
 * ## Why the guard talks back
 *
 * The guard re-engages by itself after [SAFETY_IDLE_MS], which is right - an
 * armed destructive control should not stay armed while the phone is in a
 * pocket. What was wrong is what happened next: the plate silently ate the
 * following tap. From the user's side a button they had just armed simply
 * stopped working, with nothing on screen to explain it, and the natural reading
 * of that is a broken app rather than a safety doing its job.
 *
 * So a blocked tap is never dead. It sets [notice], which the section shows, and
 * the wording distinguishes the two cases: a safety that timed out under you is
 * a different experience from one you never opened, and only the first one owes
 * you an apology.
 */
@Stable
class SafetyState internal constructor() {
    /** True while the guard is down and the controls beneath it are covered. */
    var engaged by mutableStateOf(true)
        private set

    /** Set when the *timeout* closed the guard, cleared whenever the user works it. */
    private var closedByTimeout by mutableStateOf(false)

    /** What to tell the user about a press that the guard just absorbed, if any. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Bumped with every [notice] so an identical repeat still restarts its timer. */
    internal var noticeToken by mutableIntStateOf(0)
        private set

    /** The user working the switch: deliberate, so nothing is owed an explanation. */
    fun toggle() {
        engaged = !engaged
        closedByTimeout = false
        notice = null
    }

    /** The idle timeout closing the guard behind the user's back. */
    internal fun reengage() {
        engaged = true
        closedByTimeout = true
    }

    /**
     * Closing the guard because the user navigated away from it.
     *
     * Distinct from [reengage] on purpose: nothing timed out, so the next
     * blocked tap must not claim it did. Leaving the tab is as deliberate an
     * end to the job as flipping the switch back would have been, and coming
     * back to a guard you left open is the one way this control could fail
     * quietly.
     */
    fun engageOnLeaving() {
        engaged = true
        closedByTimeout = false
        notice = null
    }

    /** A tap the guard absorbed. Says so, rather than letting it vanish. */
    fun reportBlockedTap() {
        notice = if (closedByTimeout) {
            "The safety re-engaged after 20 seconds unused, so that press did " +
                "nothing. Flip it back to ARMED to continue."
        } else {
            "The safety is on, so that press did nothing. Flip it to ARMED first."
        }
        noticeToken++
    }

    internal fun clearNotice() {
        notice = null
    }
}

/**
 * A safety that re-engages [SAFETY_IDLE_MS] after it is opened with no further
 * interaction anywhere in the sheet.
 *
 * [interaction] is a counter bumped by any touch in the containing sheet; a
 * change to it restarts the countdown, so a safety is only closed on someone who
 * has genuinely walked away.
 */
@Composable
fun rememberSafetyState(interaction: Int): SafetyState {
    val state = remember { SafetyState() }
    LaunchedEffect(state.engaged, interaction) {
        if (!state.engaged) {
            delay(SAFETY_IDLE_MS)
            state.reengage()
        }
    }
    LaunchedEffect(state.noticeToken) {
        if (state.notice != null) {
            delay(SAFETY_NOTICE_MS)
            state.clearNotice()
        }
    }
    return state
}

/**
 * The explanation for a press the guard absorbed. Nothing at all when there was none.
 *
 * Private, like [SafetyGuard]: [GuardedSection] prints it, and a section that
 * printed its own would show the line twice.
 */
@Composable
private fun SafetyNotice(state: SafetyState) {
    state.notice?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A compact sliding safety switch: red "SAFE" (guard down) <-> green "ARMED" (up). */
@Composable
internal fun SafetyToggle(engaged: Boolean, onToggle: () -> Unit) {
    val red = Color(0xFFE53935)
    val green = Color(0xFF43A047)
    val color = if (engaged) red else green
    val knobBias by animateFloatAsState(
        targetValue = if (engaged) -1f else 1f,
        animationSpec = tween(220),
        label = "safety-knob",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (engaged) "SAFE" else "ARMED",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 22.dp)
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.20f))
                .border(1.5.dp, color, RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .align(BiasAlignment(horizontalBias = knobBias, verticalBias = 0f))
                    .padding(horizontal = 3.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

/**
 * A section of controls under one safety: the content, the guard plate over it, and
 * the "that press did nothing" line beneath.
 *
 * ## Why the badge gets a strip of its own
 *
 * The plate covers a whole section, so wherever its "SAFETY ON" badge is *aligned*
 * there is something under it, and aligning it differently only changes what it
 * buries. Top-centred - which is what the tablet was running - it landed on the
 * Sensor section's button row and hid **Clean sensor** outright: a user saw two
 * buttons, and nothing on screen said a third existed. The same badge covered a
 * line of the Measurement section's paragraph, because that section's content
 * begins with a paragraph. The opaque pill behind the words made the *badge*
 * legible and changed nothing about what was underneath it.
 *
 * So the badge is not aligned against the content at all: the content starts
 * [BADGE_STRIP] down, and the badge is drawn into the empty strip that leaves.
 * It cannot cover a control because there is never a control where it is drawn.
 *
 * The strip is reserved whether the guard is up or down. Giving it back on
 * disengage would slide every button in the section upwards at the exact moment
 * the user is reaching for one, which is a worse fault than the gap it saves - a
 * tap aimed at Clean sensor would land on whatever rose into its place.
 *
 * [GUARD_INSET] is the same argument along the sides: the plate has a border, a
 * border needs room on the inside, and until it got any the border was drawn
 * straight down the first glyph of every line.
 *
 * This is the only way to raise a guard; [SafetyGuard] is private so that a new
 * guarded section cannot reintroduce the overlap by hand.
 */
@Composable
internal fun GuardedSection(
    safety: SafetyState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BADGE_STRIP, start = GUARD_INSET, end = GUARD_INSET),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
            SafetyGuard(engaged = safety.engaged, onBlockedTap = safety::reportBlockedTap)
        }
        SafetyNotice(safety)
    }
}

/**
 * The translucent red guard plate. Covers and blocks the controls beneath it while
 * the safety is engaged; flips up on its top hinge (rotationX) and vanishes when off.
 *
 * [onBlockedTap] is called for every tap the plate absorbs. It exists so that no
 * press is ever simply lost - see [SafetyState] for what that cost.
 *
 * Private on purpose: reach it through [GuardedSection], which is what keeps the
 * badge out of the controls.
 */
@Composable
private fun BoxScope.SafetyGuard(engaged: Boolean, onBlockedTap: () -> Unit = {}) {
    val rot by animateFloatAsState(
        targetValue = if (engaged) 0f else -108f,
        animationSpec = tween(durationMillis = 420),
        label = "safety-guard",
    )
    if (rot <= -104f) return // fully lifted: gone, buttons fully exposed and tappable
    val red = Color(0xFFE53935)
    val guardInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                rotationX = rot
                transformOrigin = TransformOrigin(0.5f, 0f) // hinge along the top edge
                cameraDistance = 18f * density
                alpha = (1f + rot / 108f).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(red.copy(alpha = 0.42f), red.copy(alpha = 0.20f), red.copy(alpha = 0.40f)),
                ),
            )
            .border(1.5.dp, red.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
            .then(
                // Only intercept taps while engaged (protecting the buttons); when
                // lifting, it lets them through. The tap is consumed here and
                // reported, never dropped.
                if (engaged) {
                    Modifier.clickable(
                        interactionSource = guardInteraction,
                        indication = null,
                        onClick = onBlockedTap,
                    )
                } else {
                    Modifier
                },
            ),
        // Top-aligned, into the strip [GuardedSection] holds empty for it. The
        // opaque pill stays: it is what makes the badge legible against the
        // gradient, and it marks the words as belonging to the plate rather than
        // to the section. What it never was is a fix for covering things - see
        // [GuardedSection] for why nothing is under it now.
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            "🔒  SAFETY ON",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .padding(top = BADGE_TOP)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFB71C1C))
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
}

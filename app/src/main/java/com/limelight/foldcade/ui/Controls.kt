/*
 * ToggleSwitch, ChoiceValue and the slider track are adapted from Foldcade's MenuKit.kt and
 * SliderRow.kt, MIT licensed: see assets/licenses/foldcade-MIT.txt.
 */
package com.limelight.foldcade.ui

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A one-ink symbol in [tint]; null keeps the art's own colours, as a controller button's cap. */
@Composable
internal fun Glyph(@DrawableRes id: Int, size: Dp, tint: Color?, modifier: Modifier = Modifier) {
    Image(painterResource(id), contentDescription = null, colorFilter = tint?.let { ColorFilter.tint(it) },
        modifier = modifier.size(size))
}

/** One hint: a controller button and what it does, as Foldcade's hint row shows it. */
@Composable
internal fun Hint(@DrawableRes button: Int, label: String, style: TextStyle = LocalFold.current.hint) = Hint(listOf(button), label, style)

/** Buttons that share one meaning, as L1 and R1 page, drawn 4dp apart before one label. */
@Composable
internal fun Hint(buttons: List<Int>, label: String, style: TextStyle = LocalFold.current.hint) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { index, button ->
            if (index > 0) Spacer(Modifier.width(4.dp))
            Glyph(button, 22.dp, null)
        }
        Spacer(Modifier.width(6.dp))
        BasicText(label, style = style, maxLines = 1)
    }
}

@Composable
internal fun HintRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

/**
 * Hold to confirm. Touch: press and hold. Controller: hold A on the focused control. The fill
 * follows real time with Foldcade's dissolve curve; letting go early refuses with a shake.
 */
internal class HoldState { var progress = Animatable(0f); var touching by mutableStateOf(false) }

@Composable
internal fun rememberHold(nav: NavHost, id: String, enabled: Boolean, onComplete: () -> Unit): HoldState {
    val fold = LocalFold.current
    val view = LocalView.current
    val hold = remember { HoldState() }
    val done by rememberUpdatedState(onComplete)
    val active = enabled && (hold.touching || nav.holding == id)
    LaunchedEffect(active) {
        if (active) {
            hold.progress.animateTo(1f, tween((fold.durationHold * fold.animatorScale.coerceAtLeast(0.5f)).toInt(), easing = fold.dissolve))
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hold.touching = false
            if (nav.holding == id) nav.holding = null
            done()
            hold.progress.snapTo(0f)
        } else if (hold.progress.value > 0f) {
            nav.refuse(id)
            steady { hold.progress.animateTo(0f, fold.arriveSpec(fold.durationFocus)) }
        }
    }
    return hold
}

/** A refused press shakes its control from side to side. */
@Composable
internal fun Modifier.shakeOnRefuse(nav: NavHost, id: String): Modifier {
    val fold = LocalFold.current
    val shake = remember { Animatable(0f) }
    val refused = nav.refused
    LaunchedEffect(refused) {
        if (refused?.first == id) steady {
            shake.animateTo(0f, keyframes {
                durationMillis = fold.duration(fold.durationSelect)
                fold.shakePx at durationMillis / 6
                -fold.shakePx at durationMillis / 2
                fold.shakePx / 2 at durationMillis * 5 / 6
            })
        }
    }
    return graphicsLayer { translationX = shake.value }
}

/** Glass behind a key, as Foldcade's keyboard draws its keys: a lit top rim and a soft fill. */
private fun Modifier.glass(fold: Fold, corner: Dp, lit: () -> Float): Modifier = drawBehind {
    val radius = CornerRadius(corner.toPx())
    val l = lit()
    // Opaque underneath, so the backdrop's ribbons never cut through a key.
    drawRoundRect(fold.background, cornerRadius = radius)
    drawRoundRect(Brush.verticalGradient(listOf(fold.ink.copy(alpha = 0.10f + 0.12f * l), fold.ink.copy(alpha = 0.05f + 0.08f * l))), cornerRadius = radius)
    drawRoundRect(Brush.verticalGradient(listOf(fold.ink.copy(alpha = 0.22f), fold.ink.copy(alpha = 0.04f)), endY = size.height * 0.6f),
        cornerRadius = radius, style = Stroke(1.dp.toPx()))
}

/**
 * A PC key: icon first, a short label, glass like Foldcade's keyboard. A [danger] key asks for a
 * hold and fills in rose while held; a small open ring in its corner marks it as one to hold.
 */
@Composable
internal fun KeyCap(
    nav: NavHost,
    id: String,
    @DrawableRes icon: Int,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    /** For a hold key, what an accessibility service's activation does instead: ask first. */
    onConfirm: (() -> Unit)? = null,
    onSend: () -> Unit,
) {
    val fold = LocalFold.current
    val corner = 14.dp
    val focused = nav.isFocused(id)
    var pressed by remember { mutableStateOf(false) }
    // A brief glow each time the key sends, from a tap, A, or a completed hold.
    val pulse = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sent: () -> Unit = {
        onSend()
        scope.launch { pulse.snapTo(1f); steady { pulse.animateTo(0f, fold.leaveSpec(fold.durationIsland)) } }
    }
    val hold = if (danger) rememberHold(nav, id, enabled, sent) else null
    val send by rememberUpdatedState(sent)
    val confirm by rememberUpdatedState(onConfirm)
    val lit by eased(if (pressed || focused || hold?.touching == true || nav.holding == id) 1f else 0f, fold.durationShort)
    val view = LocalView.current
    Box(
        modifier
            .navTarget(nav, id, 0, enabled, hold = danger) { send() }
            .shakeOnRefuse(nav, id)
            .focusLift(focused, pressed || nav.holding == id)
            .focusRing(focused, corner)
            .glass(fold, corner) { maxOf(lit, pulse.value) }
            .drawWithContent {
                val progress = hold?.progress?.value ?: 0f
                if (progress > 0f) {
                    val r = CornerRadius(corner.toPx())
                    clipRect(top = size.height * (1f - progress)) {
                        drawRoundRect(Brush.verticalGradient(listOf(fold.rose.copy(alpha = 0.75f), fold.rose.copy(alpha = 0.45f))), cornerRadius = r)
                    }
                }
                drawContent()
                if (danger) {
                    // The hold mark: an open ring, three quarters round.
                    val d = 7.dp.toPx()
                    drawArc(fold.rose.copy(alpha = 0.9f), -90f, 270f, false, Offset(size.width - d - 6.dp.toPx(), 6.dp.toPx()),
                        Size(d, d), style = Stroke(1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
                // A hold key never sends from one activation: TalkBack's double-tap asks first.
                onClick(label = if (danger) "Ask to send" else null) {
                    when {
                        !enabled -> false
                        danger -> { confirm?.invoke(); confirm != null }
                        else -> { send(); true }
                    }
                }
            }
            .pointerInput(danger, enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        pressed = true
                        if (danger) hold?.touching = true
                        tryAwaitRelease()
                        pressed = false
                        if (danger) hold?.touching = false
                    },
                    onTap = {
                        if (!enabled) return@detectTapGestures
                        if (!danger) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            send()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Glyph(icon, 22.dp, fold.ink, Modifier.graphicsLayer { alpha = if (enabled) 0.82f + 0.18f * lit else 0.3f })
            BasicText(label, style = fold.small.copy(color = fold.ink.copy(alpha = if (enabled) 0.86f else 0.3f), fontSize = 11.sp,
                textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

/**
 * A quick tile, as in Foldcade's R1 island: a symbol on faint glass and a short label. On, it
 * fills with the focus colours and its symbol goes dark, as a held button cap does.
 */
@Composable
internal fun QuickTile(
    nav: NavHost,
    id: String,
    @DrawableRes icon: Int,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    badge: Boolean = false,
    hold: Boolean = false,
    /** What A does with the controller, when it differs from a tap (Stick mouse hands the controller back). */
    onActivate: (() -> Unit)? = null,
    onPress: () -> Unit,
) {
    val fold = LocalFold.current
    val corner = 18.dp
    val focused = nav.isFocused(id)
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val activate by rememberUpdatedState(onActivate ?: onPress)
    val lit by eased(if (on) 1f else 0f, fold.durationSelect)
    val bright by eased(if (focused || pressed) 1f else 0f, fold.durationShort)
    // On is teal, as Foldcade's symbols say "on": the tile stays a card, its symbol, label and
    // edge turn teal. The focus colours belong to focus alone.
    Box(
        modifier
            .navTarget(nav, id, 0, hold = hold) { activate() }
            .shakeOnRefuse(nav, id)
            .focusLift(focused, pressed)
            .focusRing(focused, corner)
            .drawBehind {
                val r = CornerRadius(corner.toPx())
                // Opaque underneath, as the keys are, so the backdrop's ribbons never show through.
                drawRoundRect(fold.background, cornerRadius = r)
                drawRoundRect(fold.ink.copy(alpha = fold.quickTileAlpha + (fold.quickTileLitAlpha - fold.quickTileAlpha) * bright), cornerRadius = r)
                if (lit > 0f) {
                    val edge = 1.5.dp.toPx()
                    drawRoundRect(fold.on.copy(alpha = 0.4f * lit), Offset(edge / 2f, edge / 2f), Size(size.width - edge, size.height - edge),
                        CornerRadius(corner.toPx() - edge / 2f), style = Stroke(edge))
                }
                if (badge) drawCircle(fold.attention, 4.dp.toPx(), Offset(size.width - 10.dp.toPx(), 10.dp.toPx()))
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick { press(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { press() })
            },
        contentAlignment = Alignment.Center,
    ) {
        val ink = lerp(fold.ink, fold.on, lit)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // A symbol that changes (Sound and Muted) crossfades with a little pop rather than cutting.
            androidx.compose.animation.Crossfade(icon, animationSpec = tween(fold.duration(fold.durationSelect)), label = "tile icon") { shown ->
                Glyph(shown, 26.dp, ink, Modifier.graphicsLayer { alpha = fold.artRestAlpha + (1f - fold.artRestAlpha) * maxOf(bright, lit) })
            }
            RollingText(label, fold.small.copy(color = ink, fontSize = 12.sp))
        }
    }
}

/** An on/off switch. The knob springs across with a little overshoot, and the track lights. */
@Composable
internal fun ToggleSwitch(on: Boolean, focused: Boolean) {
    val fold = LocalFold.current
    val knob by bouncy(if (on) 1f else 0f)
    val lit by eased(if (on) 1f else 0f, fold.durationSelect)
    Box(
        Modifier.size(54.dp, 30.dp).drawBehind {
            val radius = size.height * fold.round
            drawRoundRect(fold.ink.copy(alpha = if (focused) fold.trackFocusAlpha else fold.trackAlpha), cornerRadius = CornerRadius(radius))
            if (lit > 0f) drawRoundRect(Brush.horizontalGradient(listOf(fold.ringStart, fold.ringEnd)), alpha = lit.coerceIn(0f, 1f), cornerRadius = CornerRadius(radius))
            val inset = 4.dp.toPx()
            val knobSize = size.height - 2f * inset
            val x = inset + (size.width - 2f * inset - knobSize) * knob
            drawRoundRect(fold.ink, Offset(x, inset), Size(knobSize, knobSize), CornerRadius(knobSize * fold.round))
        },
    )
}

/**
 * ‹ value ›. The chevrons show while the row has focus, since left and right change it then.
 * Tapping a chevron steps the value too. A new value rolls up into place.
 */
/**
 * ‹ value ›. Both chevrons always keep their room, so values line up and nothing shifts as the
 * focus arrives; the › sits in the row's end gutter ([CHEVRON_GUTTER]), so the value itself
 * ends on the same edge as switches and sliders. A chevron with nowhere further to go fades.
 */
@Composable
internal fun ChoiceValue(value: String, focused: Boolean, controller: Boolean, canLeft: Boolean, canRight: Boolean,
                         onStep: (Int) -> Unit, style: TextStyle) {
    val fold = LocalFold.current
    // With the controller here, only the focused row shows its chevrons, as Foldcade's menus do.
    // By touch they stay faint on every row, since they are what a finger taps.
    val shown by eased(if (focused) 1f else if (controller) 0f else 0.35f)
    val leftEnd by eased(if (canLeft) 1f else 0.25f)
    val rightEnd by eased(if (canRight) 1f else 0.25f)
    val step by rememberUpdatedState(onStep)
    val arrow = style.copy(color = fold.ink, fontSize = 22.sp, textAlign = TextAlign.Center)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("‹", style = arrow, modifier = Modifier
            .width(CHEVRON_GUTTER)
            .pointerInput(Unit) { detectTapGestures { step(-1) } }
            .graphicsLayer { alpha = shown * leftEnd })
        RollingText(value, style.copy(color = if (focused) fold.ink else fold.muted, textAlign = TextAlign.End))
        BasicText("›", style = arrow, modifier = Modifier
            .width(CHEVRON_GUTTER)
            .pointerInput(Unit) { detectTapGestures { step(1) } }
            .graphicsLayer { alpha = shown * rightEnd })
    }
}

/** The room a row keeps at its end for a choice's ›, so every row's value ends on one edge. */
internal val CHEVRON_GUTTER = 24.dp

/** Text that rolls up into place when it changes, overshooting a touch, as Foldcade's choices do. */
@Composable
internal fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    var shown by remember { mutableStateOf(text) }
    var previous by remember { mutableStateOf<String?>(null) }
    val roll = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        previous = shown
        shown = text
        roll.snapTo(0f)
        steady { roll.animateTo(1f, fold.playfulSpec()) }
        previous = null
    }
    Box(modifier) {
        previous?.let {
            BasicText(it, style = style, maxLines = 1, modifier = Modifier.graphicsLayer {
                alpha = (1f - roll.value * 1.5f).coerceIn(0f, 1f); translationY = -roll.value * 18.dp.toPx()
            })
        }
        BasicText(shown, style = style, maxLines = 1, modifier = Modifier.graphicsLayer {
            if (previous != null) { alpha = roll.value.coerceIn(0f, 1f); translationY = (1f - roll.value) * 18.dp.toPx() }
        })
    }
}

/** The slider track, its fill, and the glowing thumb. Touch drags it, in whole stops. */
@Composable
internal fun StepTrack(fraction: Float, stops: Int, onStop: (Int) -> Unit, modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    val shown by motionState(fraction.coerceIn(0f, 1f), fold.arriveSpec(fold.durationFocus))
    val pick by rememberUpdatedState(onStop)
    Box(
        modifier
            .height(36.dp)
            .drawWithContent {
                val trackHeight = 6.dp.toPx()
                val top = (size.height - trackHeight) / 2f
                val corner = CornerRadius(trackHeight * fold.round)
                drawRoundRect(fold.focus.copy(alpha = fold.sliderTrackAlpha), Offset(0f, top), Size(size.width, trackHeight), corner)
                val fill = size.width * shown
                if (fill > 1f) drawRoundRect(fold.focus, Offset(0f, top), Size(fill, trackHeight), CornerRadius(min(trackHeight * fold.round, fill / 2f)))
                val thumb = 7.dp.toPx()
                val center = Offset(fill.coerceIn(thumb, (size.width - thumb).coerceAtLeast(thumb)), size.height / 2f)
                val glow = thumb * 2.2f
                drawCircle(Brush.radialGradient(*arrayOf(0f to fold.focus.copy(alpha = 0.9f), 0.42f to fold.focus.copy(alpha = 0.34f), 1f to Color.Transparent),
                    center = center, radius = glow), glow, center)
                drawCircle(fold.focus, thumb * 0.62f, center)
            }
            .pointerInput(stops) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(x: Float) = pick(((x / size.width.coerceAtLeast(1)) * (stops - 1)).roundToInt().coerceIn(0, stops - 1))
                    apply(down.position.x)
                    drag(down.id) { change ->
                        if (change.positionChange() != Offset.Zero) change.consume()
                        apply(change.position.x)
                    }
                }
            },
    )
}

/** A row's label at rest is a little dim, and brightens as the focus arrives. */
@Composable
internal fun RowLabel(text: String, focused: Boolean, modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    val lit by eased(if (focused) 1f else 0f)
    // Two lines before a label is cut: the reviewer's copy stays whole on the narrow card.
    BasicText(text, style = fold.row.copy(fontSize = 17.sp, lineHeight = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.graphicsLayer { alpha = fold.labelRestAlpha + (1f - fold.labelRestAlpha) * lit })
}

@Composable
internal fun Gap(width: Dp = 0.dp, height: Dp = 0.dp) = Spacer(Modifier.width(width).height(height))

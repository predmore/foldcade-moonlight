/*
 * Parts of this file are adapted from Foldcade (FocusRing.kt, MenuKit.kt, Motion.kt), which is
 * MIT licensed: see assets/licenses/foldcade-MIT.txt. The rest is GPL-3.0 with this app.
 */
package com.limelight.foldcade.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.foldcade.Appearance
import kotlinx.coroutines.withContext

/** Afterglow's tokens, or the player's theme from Foldcade's `appearance` reply. */
@Immutable
internal class Fold(appearance: Appearance, val animatorScale: Float) {
    val background = Color(appearance.background)
    val surface = Color(appearance.surface)
    val ink = Color(appearance.onBackground)
    val muted = Color(appearance.muted)
    val focus = Color(appearance.focus)
    val ringStart = Color(appearance.ringStart)
    val ringEnd = Color(appearance.ringEnd)
    val attention = Color(appearance.attention)
    /** Iconography rule 5: teal means on or held, rose means it needs you. */
    val on = Color(0xFF3EE6C0)
    val rose = Color(0xFFFF5C7A)
    /** A controller button's dark cap, as Foldcade's glyphs draw it. */
    val cap = Color(0xFF30333B)
    val round = appearance.round
    val cardCorner: Dp = appearance.cardCornerDp.dp

    val font = FontFamily(Font(R.font.nunito, FontWeight.Normal))
    val strong = FontWeight.W600

    // style.type, in sp.
    val title = TextStyle(fontFamily = font, fontWeight = strong, fontSize = 20.sp, color = ink)
    val row = TextStyle(fontFamily = font, fontSize = 18.sp, color = ink)
    val hint = TextStyle(fontFamily = font, fontSize = 14.sp, color = muted)
    val small = TextStyle(fontFamily = font, fontSize = 12.sp, color = muted)

    // style.motion. Durations in ms; Android's animator scale applies once, here.
    val durationShort = 100
    val durationFocus = 180
    val durationSelect = 280
    val durationIsland = 320
    val durationPulse = 720
    /** How long a hold takes to confirm something that cannot be undone. */
    val durationHold = 900
    val scaleFocus = 1.05f
    val keySquash = 0.07f
    val riseFrom = 0.96f
    val riseDp = 24.dp
    val sinkScale = 0.06f
    val sinkDp = 16.dp
    val cascadeDp = 14.dp
    val cascadeStep = 0.05f
    val cascadeSpan = 0.35f
    val shakePx = 8f
    val arrive: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val leave: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val pulse: Easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
    val dissolve: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val playful: Easing = CubicBezierEasing(0.34f, 1.36f, 0.64f, 1f)

    val reduced get() = animatorScale <= 0f
    fun duration(base: Int): Int = if (animatorScale <= 0f) durationShort else (base * animatorScale).toInt().coerceAtLeast(1)
    fun arriveSpec(base: Int): FiniteAnimationSpec<Float> = tween(duration(base), easing = arrive)
    fun leaveSpec(base: Int): FiniteAnimationSpec<Float> = tween(duration(base), easing = leave)
    fun dissolveSpec(base: Int): FiniteAnimationSpec<Float> = tween(duration(base), easing = dissolve)
    /** The playful overshoot, or a plain arrival when Android removes animations, so nothing bounces then. */
    fun playfulSpec(base: Int = durationSelect): FiniteAnimationSpec<Float> =
        if (reduced) arriveSpec(base) else tween(duration(base), easing = playful)

    // style.focusRing, in panel px.
    val ringStroke = 6f
    val ringGap = 2f
    val ringGlowSpread = 10f
    val ringGlowAlpha = 0.1f
    val ringGlowSteps = 4
    val pronouncedStroke = 10f
    val pronouncedSpread = 22f
    val pronouncedAlpha = 0.22f

    // style.menu, style.controls, style.dialog.
    val highlightStartAlpha = 0.2f
    val highlightEndAlpha = 0.08f
    val labelRestAlpha = 0.72f
    val artRestAlpha = 0.78f
    val quickTileAlpha = 0.08f
    val quickTileLitAlpha = 0.16f
    val trackAlpha = 0.12f
    val trackFocusAlpha = 0.2f
    val sliderTrackAlpha = 0.28f
    val scrimAlpha = 0.72f
    val borderAlpha = 0.08f
    val cardLift = 0.035f

    fun ringBrush(size: Size, origin: Offset = Offset.Zero): Brush = Brush.linearGradient(
        listOf(ringStart, ringEnd), start = origin, end = Offset(origin.x + size.width, origin.y + size.height))

    /** A filled card's surface, lit a little at the top. */
    fun cardFill(lift: Float = cardLift): Brush = Brush.verticalGradient(listOf(lerp(surface, ink, lift), surface))
}

internal val LocalFold = staticCompositionLocalOf { Fold(Appearance.AFTERGLOW, 1f) }

/** Durations are already scaled, so the toolkit must not scale them again. */
private object Steady : MotionDurationScale { override val scaleFactor: Float = 1f }

/** [target] reached with [spec], as state. Read it in a layer or draw block so frames do not recompose. */
@Composable
internal fun motionState(target: Float, spec: FiniteAnimationSpec<Float>): State<Float> {
    val anim = remember { Animatable(target) }
    LaunchedEffect(target) { withContext(Steady) { anim.animateTo(target, spec) } }
    return anim.asState()
}

@Composable
internal fun eased(target: Float, base: Int = LocalFold.current.durationFocus): State<Float> =
    motionState(target, LocalFold.current.arriveSpec(base))

@Composable
internal fun bouncy(target: Float): State<Float> = motionState(target, LocalFold.current.playfulSpec())

internal suspend fun <T> steady(block: suspend () -> T): T = withContext(Steady) { block() }

/**
 * The focus ring every focusable thing shares: a green-to-blue stroke just outside the shape,
 * with a soft glow built from wider, fainter strokes.
 */
internal fun DrawScope.drawFocusRing(fold: Fold, cornerPx: Float, alpha: Float, bounds: Rect = Rect(Offset.Zero, size), pronounced: Boolean = false) {
    if (alpha <= 0f) return
    val stroke = if (pronounced) fold.pronouncedStroke else fold.ringStroke
    val spread = if (pronounced) fold.pronouncedSpread else fold.ringGlowSpread
    val glowAlpha = if (pronounced) fold.pronouncedAlpha else fold.ringGlowAlpha
    val outset = fold.ringGap + stroke / 2f
    val ringSize = Size(bounds.width + outset * 2f, bounds.height + outset * 2f)
    val topLeft = Offset(bounds.left - outset, bounds.top - outset)
    val radius = CornerRadius(cornerPx + outset, cornerPx + outset)
    val brush = fold.ringBrush(ringSize, topLeft)
    // A wide glow in four strokes shows bands; the pronounced one takes twelve, each fainter, so
    // the glow builds to the same strength without steps.
    val steps = if (pronounced) PRONOUNCED_GLOW_STEPS else fold.ringGlowSteps
    val each = glowAlpha * fold.ringGlowSteps / steps
    for (step in steps downTo 1) {
        val reach = spread * step / steps
        drawRoundRect(brush, topLeft, ringSize, radius, alpha = each * alpha, style = Stroke(stroke + reach * 2f))
    }
    drawRoundRect(brush, topLeft, ringSize, radius, alpha = alpha, style = Stroke(stroke))
}

private const val PRONOUNCED_GLOW_STEPS = 12

/** The ring around this element while it has the controller's focus. It fades in and out. */
@Composable
internal fun Modifier.focusRing(focused: Boolean, corner: Dp, pronounced: Boolean = false): Modifier {
    val fold = LocalFold.current
    val shown by eased(if (focused) 1f else 0f)
    return drawWithContent {
        drawContent()
        drawFocusRing(fold, corner.toPx(), shown, pronounced = pronounced)
    }
}

/** A focused tile or key grows a little and lands with a bounce, as Foldcade's tiles do. */
@Composable
internal fun Modifier.focusLift(focused: Boolean, pressed: Boolean = false): Modifier {
    val fold = LocalFold.current
    val lift by bouncy(if (focused) 1f else 0f)
    val squash by motionState(if (pressed) 1f else 0f, fold.arriveSpec(fold.durationShort))
    return graphicsLayer {
        val grow = 1f + (fold.scaleFocus - 1f) * lift - fold.keySquash * squash
        scaleX = grow
        scaleY = grow
    }
}

/** A card or page arriving: it fades up from just under full size and a little lower. */
internal fun Modifier.rise(fold: Fold, progress: () -> Float): Modifier = graphicsLayer {
    val t = progress()
    alpha = t.coerceIn(0f, 1f)
    val grow = fold.riseFrom + (1f - fold.riseFrom) * t
    scaleX = grow
    scaleY = grow
    translationY = (1f - t) * fold.riseDp.toPx()
}

/** Content giving way as [progress] goes to 1: it fades and settles back. */
internal fun Modifier.sinking(fold: Fold, progress: () -> Float): Modifier = graphicsLayer {
    val t = progress()
    alpha = 1f - t
    val shrink = 1f - fold.sinkScale * t
    scaleX = shrink
    scaleY = shrink
    translationY = -t * fold.sinkDp.toPx()
}

/** Rows arrive one after another as a page opens, each rising a little into place. */
internal fun Modifier.cascade(fold: Fold, index: Int, progress: () -> Float): Modifier = graphicsLayer {
    val span = fold.cascadeSpan.coerceAtMost(0.99f)
    val lag = (index * fold.cascadeStep).coerceAtMost(span)
    val local = ((progress() - lag) / (1f - span)).coerceIn(0f, 1f)
    alpha = local
    translationY = (1f - local) * fold.cascadeDp.toPx()
}

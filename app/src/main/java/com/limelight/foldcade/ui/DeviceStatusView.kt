/*
 * The battery glyph is drawn from the geometry of Foldcade's battery icons, and [place] follows
 * Foldcade's StatusPlace; both are MIT licensed: see assets/licenses/foldcade-MIT.txt. The rest
 * is GPL-3.0 with this app.
 */
package com.limelight.foldcade.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.limelight.foldcade.DeviceStatus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The status's sizes, shared with the header's measurement so the two always agree. */
internal object StatusMetrics {
    val GAP = 8.dp
    private val GLYPH = 18.dp
    /** The glyph grows with the font scale, up to 1.3×, so large text never sits beside a dash. */
    fun glyph(fontScale: Float): Dp = GLYPH * fontScale.coerceIn(1f, 1.3f)
}

/** The clock's style: the hint size in ink, regular weight, quieter than the stats pill. */
internal fun Fold.clockStyle(): TextStyle = hint.copy(color = ink)

/** The low percent's style: semibold, ink, or rose below 15% where rose reads on the theme's background. */
internal fun Fold.percentStyle(battery: DeviceStatus.Battery): TextStyle {
    val readable = DeviceStatus.Rules.contrast(rose.toArgb(), background.toArgb()) >= 3.0
    return hint.copy(color = if (battery.critical && readable) rose else ink, fontWeight = strong)
}

/**
 * The device's battery and clock, read from the edge in: the clock at the edge, the battery glyph
 * before it, and, only when low, the percent before the glyph. Status, not a control: no focus
 * stop, no tap, and one spoken node that is not a live region.
 *
 * [clockShown] eases the clock away when the header runs short; the glyph and a low percent stay.
 * The status keeps the width its clock would have at a two-digit hour and ends at the edge, so
 * the spare room at 1:00–9:59 falls before it, never inside it: a low percent always sits beside
 * its glyph, the glyph beside the clock, and nothing moves at 9:59 → 10:00.
 */
@Composable
internal fun DeviceStatusView(status: DeviceStatus, clockShown: State<Float>, modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    val battery = status.battery
    val fontScale = LocalDensity.current.fontScale
    val measurer = rememberTextMeasurer()
    fun width(text: String) = measurer.measure(text, fold.clockStyle(), maxLines = 1, softWrap = false).size.width
    // What a two-digit hour adds over the clock shown now; nothing once the clock has gone.
    val spare = maxOf(0, width(status.widestClock) - width(status.clock))
    val low by eased(if (battery?.low == true) 1f else 0f, fold.durationSelect)
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = status.spoken }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0))
                val width = (placeable.width + (spare * clockShown.value.coerceIn(0f, 1f)).roundToInt()).coerceAtMost(constraints.maxWidth)
                layout(width, placeable.height) { placeable.place(width - placeable.width, 0) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (battery != null) {
            if (low > 0f) Row(Modifier.place { low }, verticalAlignment = Alignment.CenterVertically) {
                BasicText("${battery.percent}%", style = fold.percentStyle(battery), maxLines = 1)
                Gap(width = StatusMetrics.GAP)
            }
            BatteryGlyph(battery, StatusMetrics.glyph(fontScale))
        }
        Row(Modifier.place { clockShown.value }, verticalAlignment = Alignment.CenterVertically) {
            if (battery != null) Gap(width = StatusMetrics.GAP)
            BasicText(status.clock, style = fold.clockStyle(), maxLines = 1)
        }
    }
}

/**
 * Foldcade's battery, drawn so it wears the player's theme: the frame, nub and an ordinary fill in
 * ink; the fill rose below 15% and teal while charging; the bolt in ink, cut out of the fill.
 * Foldcade's own icons are fixed cream, which a light theme would lose.
 */
@Composable
internal fun BatteryGlyph(battery: DeviceStatus.Battery, size: Dp, modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    val fill = when {
        battery.charging -> fold.on
        battery.critical -> fold.rose
        else -> fold.ink
    }
    val units = DeviceStatus.Rules.fillUnits(battery.step)
    // Offscreen, so the bolt's clear outline cuts the fill and frame to whatever lies behind.
    Canvas(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawRoundRect(fold.ink, Offset(2.2f, 7.4f), Size(17.6f, 9.2f), CornerRadius(2.6f), style = Stroke(2f))
            drawPath(NUB, fold.ink)
            if (units > 0f) drawRoundRect(fill, Offset(4.4f, 9.6f), Size(units, 4.8f), CornerRadius(minOf(1.1f, units / 2f)))
            if (battery.charging) {
                drawPath(BOLT, fold.ink, style = Stroke(2.2f, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
                drawPath(BOLT, fold.ink)
            }
        }
    }
}

/** The nub, rounded on its outer side only. */
private val NUB = Path().apply {
    addRoundRect(RoundRect(20.6f, 10.3f, 22.5f, 13.7f, topRightCornerRadius = CornerRadius(0.9f), bottomRightCornerRadius = CornerRadius(0.9f)))
}

private val BOLT = Path().apply {
    moveTo(12.5f, 7.4f); lineTo(8.9f, 12.5f); lineTo(11.3f, 12.5f); lineTo(10.7f, 16.6f); lineTo(14.5f, 11.3f); lineTo(12f, 11.3f); close()
}

/**
 * Opens or closes a place from nothing to its full width and fades it, as Foldcade's StatusPlace
 * does. The content holds to the place's end, so it stays still while the place beside it moves.
 */
internal fun Modifier.place(shown: () -> Float): Modifier = this
    .graphicsLayer { alpha = shown().coerceIn(0f, 1f); clip = true }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        val width = (placeable.width * shown().coerceIn(0f, 1f)).roundToInt()
        layout(width, placeable.height) { placeable.place(width - placeable.width, 0) }
    }

/**
 * [target] with calm: a step up (giving something up) is taken at once; a step back down waits
 * until [target] has stayed lower for 10 s, or 30 s when it crosses back from [slowFrom] or later
 * to before it, so a value that comes and goes never makes the header blink. The wait runs while
 * the target stays lower, whichever lower step it is, and then settles on the latest. A new [key]
 * (the player's own change, such as opening detail) starts afresh with no hold.
 */
@Composable
internal fun heldStep(target: Int, key: Any, slowFrom: Int = Int.MAX_VALUE): Int {
    var held by remember(key) { mutableIntStateOf(target) }
    val latest by rememberUpdatedState(target)
    LaunchedEffect(target, key) { if (target > held) held = target }
    val lower = target < held
    val slow = lower && held >= slowFrom && target < slowFrom
    LaunchedEffect(lower, slow, held, key) {
        if (!lower) return@LaunchedEffect
        delay(if (slow) 30_000L else 10_000L)
        // A short wait never brings back what only the long one may.
        held = if (!slow && held >= slowFrom) maxOf(latest, slowFrom) else latest
    }
    return maxOf(target, held)
}

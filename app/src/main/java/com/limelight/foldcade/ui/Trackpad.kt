package com.limelight.foldcade.ui

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.limelight.foldcade.TouchpadGesture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the pad sends besides gestures: the click bar's buttons. */
internal interface PadButtons { fun button(right: Boolean, down: Boolean) }

/**
 * The PC trackpad: a glass surface with a fine dot grid, a light under each finger, a scroll
 * strip at the right edge that works like a wheel, and two click buttons along the bottom.
 * It draws only while touched; at rest it is a cached picture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Trackpad(
    gesture: TouchpadGesture,
    buttons: PadButtons,
    /** Gestures already used: their hints are gone. The rest stay until each is used. */
    learned: Set<TouchpadGesture.Gesture>,
    description: String,
    modifier: Modifier = Modifier,
    /** Typing's short pad: no hints and no click buttons, a narrower strip. */
    compact: Boolean = false,
) {
    val fold = LocalFold.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var fingers by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var lastFingers by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier
            .clip(shape)
            .background(fold)
            .border(1.dp, fold.ink.copy(alpha = fold.borderAlpha), shape),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // The pad proper.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = description }
                    .drawWithCache {
                        // The dot grid is built once per size and reused for every frame.
                        val step = 18.dp.toPx()
                        val dot = 1.2.dp.toPx()
                        val dots = Path()
                        var y = step
                        while (y < size.height) {
                            var x = step
                            while (x < size.width) { dots.addOval(androidx.compose.ui.geometry.Rect(Offset(x, y), dot)); x += step }
                            y += step
                        }
                        onDrawBehind {
                            drawPath(dots, fold.ink.copy(alpha = 0.07f))
                            val strength = glow.value
                            if (strength > 0f) {
                                val shown = fingers.ifEmpty { lastFingers }
                                for (finger in shown) {
                                    val p = Offset(finger.x * density, finger.y * density)
                                    val r = 64.dp.toPx()
                                    drawCircle(Brush.radialGradient(listOf(fold.ringStart.copy(alpha = 0.34f * strength), fold.ringEnd.copy(alpha = 0.12f * strength), Color.Transparent),
                                        center = p, radius = r), r, p)
                                    drawCircle(fold.ink.copy(alpha = 0.5f * strength), 4.dp.toPx(), p)
                                }
                            }
                        }
                    }
                    .pointerInteropFilter { event ->
                        var x = 0f
                        var y = 0f
                        val count = event.pointerCount
                        val points = ArrayList<Offset>(count)
                        for (i in 0 until count) {
                            x += event.getX(i); y += event.getY(i)
                            points += Offset(event.getX(i) / density, event.getY(i) / density)
                        }
                        x /= count * density
                        y /= count * density
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                tapJob?.cancel()
                                gesture.down(x, y, event.eventTime)
                                scope.launch { glow.snapTo(1f) }
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> gesture.pointers(count, x, y, false)
                            MotionEvent.ACTION_POINTER_UP -> gesture.pointers(count, x, y, true)
                            MotionEvent.ACTION_MOVE -> gesture.move(x, y, count, event.eventTime)
                            MotionEvent.ACTION_UP -> {
                                gesture.up(event.eventTime)
                                if (gesture.hasPendingTap()) tapJob = scope.launch {
                                    delay(TouchpadGesture.DOUBLE_TAP_MS)
                                    gesture.tapTimeout()
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> gesture.cancel()
                        }
                        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
                        if (ended) {
                            lastFingers = points
                            fingers = emptyList()
                            scope.launch { steady { glow.animateTo(0f, fold.leaveSpec(fold.durationIsland)) } }
                        } else {
                            fingers = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP)
                                points.filterIndexed { i, _ -> i != event.actionIndex } else points
                        }
                        true
                    },
            ) {
                if (!compact) {
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A retired hint fades and folds away; the rest slide together, leaving no hole.
                        val leave = fadeOut(tween(fold.duration(fold.durationIsland))) + shrinkHorizontally(tween(fold.duration(fold.durationIsland)))
                        AnimatedVisibility(TouchpadGesture.Gesture.CLICK !in learned, exit = leave, enter = EnterTransition.None) { PadHint(Fingers.One, "Click") }
                        AnimatedVisibility(TouchpadGesture.Gesture.RIGHT_CLICK !in learned, exit = leave, enter = EnterTransition.None) { PadHint(Fingers.Two, "Right-click") }
                        AnimatedVisibility(TouchpadGesture.Gesture.SCROLL !in learned, exit = leave, enter = EnterTransition.None) { PadHint(Fingers.TwoMoving, "Scroll") }
                        AnimatedVisibility(TouchpadGesture.Gesture.DRAG !in learned, exit = leave, enter = EnterTransition.None) { PadHint(Fingers.DoubleTap, "Drag") }
                    }
                }
            }
            ScrollStrip(gesture, Modifier.width(if (compact) 40.dp else 46.dp).fillMaxHeight(), compact)
        }
        if (!compact) ClickBar(buttons, Modifier.fillMaxWidth().height(44.dp))
    }
}

private fun Modifier.background(fold: Fold): Modifier = drawBehind {
    // Glass over the surface, lit a little at the top as Foldcade's cards are.
    drawRect(fold.cardFill(0.05f))
    drawRect(Brush.verticalGradient(listOf(fold.ringStart.copy(alpha = 0.05f), Color.Transparent), endY = size.height * 0.5f))
}

internal enum class Fingers { One, Two, TwoMoving, DoubleTap }

/**
 * A gesture as fingertips, and what it does: one dot clicks, two right-click, two with an
 * up-and-down arrow scroll, and a dot in two rings is a double tap, which drags. Shown until
 * the pad's first use.
 */
@Composable
private fun PadHint(fingers: Fingers, label: String) {
    val fold = LocalFold.current
    // The gap after each hint belongs to it, so it folds away with the hint.
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(if (fingers == Fingers.One || fingers == Fingers.DoubleTap) 20.dp else 32.dp).height(22.dp).drawBehind {
            val ink = fold.ink.copy(alpha = 0.7f)
            val r = 3.2.dp.toPx()
            val cy = size.height / 2f
            when (fingers) {
                Fingers.One -> drawCircle(ink, r, Offset(size.width / 2f, cy))
                Fingers.Two -> {
                    drawCircle(ink, r, Offset(size.width / 2f - 5.dp.toPx(), cy))
                    drawCircle(ink, r, Offset(size.width / 2f + 5.dp.toPx(), cy))
                }
                Fingers.TwoMoving -> {
                    drawCircle(ink, r, Offset(6.dp.toPx(), cy))
                    drawCircle(ink, r, Offset(15.dp.toPx(), cy))
                    val x = size.width - 5.dp.toPx()
                    val stroke = 1.6.dp.toPx()
                    val a = 3.dp.toPx()
                    drawLine(ink, Offset(x, cy - 8.dp.toPx()), Offset(x, cy + 8.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(x - a, cy - 8.dp.toPx() + a), Offset(x, cy - 8.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(x + a, cy - 8.dp.toPx() + a), Offset(x, cy - 8.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(x - a, cy + 8.dp.toPx() - a), Offset(x, cy + 8.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(x + a, cy + 8.dp.toPx() - a), Offset(x, cy + 8.dp.toPx()), stroke, StrokeCap.Round)
                }
                Fingers.DoubleTap -> {
                    val c = Offset(size.width / 2f, cy)
                    drawCircle(ink, r * 0.85f, c)
                    drawCircle(ink, r * 1.9f, c, style = Stroke(1.2.dp.toPx()))
                    drawCircle(ink.copy(alpha = 0.4f), r * 2.9f, c, style = Stroke(1.2.dp.toPx()))
                }
            }
        })
        Gap(width = 6.dp)
        BasicText(label, style = fold.small.copy(color = fold.muted), maxLines = 1)
    }
}

/**
 * The scroll strip: ridges like a wheel's edge between an up and a down chevron. Sliding down
 * scrolls down, and the ridges roll with the finger; a tap on either half scrolls one notch.
 */
@Composable
private fun ScrollStrip(gesture: TouchpadGesture, modifier: Modifier, compact: Boolean = false) {
    val fold = LocalFold.current
    val density = LocalDensity.current.density
    var roll by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val lit by eased(if (held) 1f else 0f, fold.durationShort)
    Box(
        modifier
            .semantics { contentDescription = "Scroll" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    held = true
                    var lastY = down.position.y
                    gesture.stripDown(down.position.y / density, size.height / density, down.uptimeMillis)
                    var up = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { up = change.uptimeMillis; break }
                        change.consume()
                        roll += change.position.y - lastY
                        lastY = change.position.y
                        gesture.stripMove(change.position.y / density)
                    }
                    gesture.stripUp(up)
                    held = false
                }
            }
            .drawBehind {
                val line = 1.dp.toPx()
                drawLine(fold.ink.copy(alpha = fold.borderAlpha), Offset(0f, 0f), Offset(0f, size.height), line)
                drawRect(fold.ink.copy(alpha = 0.02f + 0.06f * lit))
                val cx = size.width / 2f
                val chevron = 6.dp.toPx()
                val ink = fold.ink.copy(alpha = 0.55f + 0.35f * lit)
                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                val top = (if (compact) 12 else 18).dp.toPx()
                drawPath(Path().apply { moveTo(cx - chevron, top + chevron / 2); lineTo(cx, top - chevron / 2); lineTo(cx + chevron, top + chevron / 2) }, ink, style = stroke)
                val bottom = size.height - (if (compact) 12 else 18).dp.toPx()
                drawPath(Path().apply { moveTo(cx - chevron, bottom - chevron / 2); lineTo(cx, bottom + chevron / 2); lineTo(cx + chevron, bottom - chevron / 2) }, ink, style = stroke)
                // Ridges between the chevrons, rolling with the finger.
                val gap = 7.dp.toPx()
                val from = top + (if (compact) 12 else 18).dp.toPx()
                val to = bottom - (if (compact) 12 else 18).dp.toPx()
                val offset = ((roll % gap) + gap) % gap
                var y = from + offset
                val half = 9.dp.toPx()
                while (y < to) {
                    val edge = minOf(y - from, to - y) / (3 * gap)
                    drawLine(fold.ink.copy(alpha = (0.1f + 0.2f * lit) * edge.coerceIn(0f, 1f)), Offset(cx - half, y), Offset(cx + half, y), 1.5.dp.toPx(), StrokeCap.Round)
                    y += gap
                }
            },
    )
}

/** Two click buttons, as a laptop's pad has: hold one and move a finger on the pad to drag. */
@Composable
private fun ClickBar(buttons: PadButtons, modifier: Modifier) {
    val fold = LocalFold.current
    Row(modifier.drawBehind {
        drawLine(fold.ink.copy(alpha = fold.borderAlpha), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
    }) {
        ClickButton(false, buttons, "Click", Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).drawBehind { drawRect(fold.ink.copy(alpha = 0.12f)) })
        ClickButton(true, buttons, "Right-click", Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ClickButton(right: Boolean, buttons: PadButtons, label: String, modifier: Modifier) {
    val fold = LocalFold.current
    var down by remember { mutableStateOf(false) }
    val lit by eased(if (down) 1f else 0f, fold.durationShort)
    Box(
        modifier
            .semantics { contentDescription = if (right) "Right click" else "Left click" }
            .pointerInput(right) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    down = true
                    buttons.button(right, true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        down = false
                        buttons.button(right, false)
                    }
                }
            }
            .drawBehind {
                if (lit > 0f) drawRoundRect(fold.ringBrush(size), alpha = 0.28f * lit, cornerRadius = CornerRadius(0f))
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MouseGlyph(right, lit)
            Gap(width = 7.dp)
            BasicText(label, style = fold.small.copy(color = fold.ink.copy(alpha = 0.7f + 0.3f * lit)))
        }
    }
}

/** A mouse with the button this pad presses lit, so the two halves say which is which. */
@Composable
private fun MouseGlyph(right: Boolean, lit: Float) {
    val fold = LocalFold.current
    Box(Modifier.width(14.dp).height(20.dp).drawBehind {
        val stroke = 1.5.dp.toPx()
        val ink = fold.ink.copy(alpha = 0.7f + 0.3f * lit)
        val w = size.width
        val h = size.height
        val r = w / 2f
        // The lit half: the top quarter on its side, cut at the split.
        val half = Path().apply {
            if (right) {
                moveTo(w / 2f, 0f); lineTo(w / 2f, h * 0.42f); lineTo(w, h * 0.42f); lineTo(w, r)
                arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, w, w), 0f, -90f, false); close()
            } else {
                moveTo(w / 2f, 0f); lineTo(w / 2f, h * 0.42f); lineTo(0f, h * 0.42f); lineTo(0f, r)
                arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, w, w), 180f, 90f, false); close()
            }
        }
        drawPath(half, fold.ink.copy(alpha = 0.55f + 0.45f * lit))
        drawRoundRect(ink, Offset(stroke / 2f, stroke / 2f), androidx.compose.ui.geometry.Size(w - stroke, h - stroke),
            CornerRadius(r), style = Stroke(stroke))
        drawLine(ink, Offset(w / 2f, stroke), Offset(w / 2f, h * 0.42f), stroke)
        drawLine(ink, Offset(stroke, h * 0.42f), Offset(w - stroke, h * 0.42f), stroke)
    })
}

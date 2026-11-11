/*
 * Adapted from Foldcade's ControllerKeyboard.kt (the glossy keys with their neon rim, the
 * gliding highlight, the chips, and the flash on a key that acts), which is MIT licensed:
 * see assets/licenses/foldcade-MIT.txt.
 */
package com.limelight.foldcade.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.foldcade.PcKeyboard
import com.limelight.foldcade.PcKeyboard.Cap
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foldcade's controller keyboard, typing to the PC: glossy keys with a lit rim, three PC-key
 * chips above them, and one highlight that glides from key to key and lands with the menus'
 * bounce. A key flashes when it acts, even when a button did the work, and the keys a button
 * also works wear its glyph: X on Erase, Y on Space, L2 on Shift, Start on Done.
 *
 * It is played with the controller first. A tap still types its key, and the highlight moves
 * there, so a finger on it is never ignored; at 12 keys across a key is about 40dp, so steady
 * typing by touch is Android's keyboard, one tap away on "Touch keys".
 */
@Composable
internal fun PcKeyboardView(state: PcKeyboard.State, onTap: (PcKeyboard.Focus) -> Unit, modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    val density = LocalDensity.current
    val tap by rememberUpdatedState(onTap)
    val caps by eased(if (state.shift == PcKeyboard.Shift.Off) 0f else 1f)
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.pressed) {
        if (state.pressed == 0) return@LaunchedEffect
        flash.snapTo(1f)
        steady { flash.animateTo(0f, fold.leaveSpec(fold.durationSelect)) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val reach = 14.dp
        val tall = CHIP_HEIGHT + CHIP_GAP + PcKeyboard.rows.size * ROW_PITCH - KEY_GAP
        val unit = minOf((maxWidth - reach * 2) / PcKeyboard.WIDTH, (maxHeight - reach * 2) / tall).coerceAtLeast(0.dp)
        // Height to spare makes the key rows taller, by up to a third, rather than leaving it empty
        // above and below them. The chips, widths, gaps across and lettering keep [unit].
        val rowsTall = tall - CHIP_HEIGHT
        val unitY = minOf(unit * 1.35f, (maxHeight - reach * 2 - unit * CHIP_HEIGHT) / rowsTall).coerceAtLeast(unit)
        val blockWidth = unit * PcKeyboard.WIDTH
        val left = (maxWidth - blockWidth) / 2
        val top = ((maxHeight - unit * CHIP_HEIGHT - unitY * rowsTall) / 2).coerceAtLeast(0.dp)
        val keysTop = top + unit * CHIP_HEIGHT + unitY * CHIP_GAP
        val unitPx = with(density) { unit.toPx() }
        val unitYPx = with(density) { unitY.toPx() }
        val leftPx = with(density) { left.toPx() }
        val topPx = with(density) { top.toPx() }
        val keysTopPx = with(density) { keysTop.toPx() }
        val slot = PcKeyboard.WIDTH / PcKeyboard.chips.size

        fun chipRect(index: Int) = Rect(Offset(leftPx + (index * slot + CHIP_SPACING / 2f) * unitPx, topPx),
            Size((slot - CHIP_SPACING) * unitPx, CHIP_HEIGHT * unitPx))

        val rows = PcKeyboard.rows(state.fKeys)
        val target = if (state.focus.onChips) chipRect(state.focus.index).grown(fold.scaleFocus)
        else PcKeyboard.key(state.focus, state.fKeys)?.let { keyRect(it, state.focus.row, unitPx, unitYPx, leftPx, keysTopPx).grown(fold.scaleFocus) }
        val highlight = rememberGliding(target)
        Box(
            Modifier.fillMaxSize().drawWithContent {
                drawContent()
                val rect = highlight() ?: return@drawWithContent
                val corner = unitPx * KEY_CORNER * fold.scaleFocus
                drawRoundRect(Brush.verticalGradient(listOf(fold.ringStart.copy(alpha = 0.26f), fold.ringEnd.copy(alpha = 0.1f)), rect.top, rect.bottom),
                    rect.topLeft, rect.size, CornerRadius(corner))
                drawFocusRing(fold, corner, 1f, rect)
            },
        ) {
            PcKeyboard.chips.forEachIndexed { index, chip ->
                val rect = chipRect(index)
                val flashed = state.pressedKey?.let { it.onChips && it.index == index } == true
                // Ctrl, Alt and the F-key layer light while latched, as Shift does.
                val latched = (chip == PcKeyboard.Chip.Ctrl && state.ctrl) || (chip == PcKeyboard.Chip.Alt && state.alt) ||
                    (chip == PcKeyboard.Chip.FKeys && state.fKeys)
                val lit by eased(if (latched) 1f else 0f)
                Box(
                    Modifier
                        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .drawBehind {
                            val r = CornerRadius(size.height * fold.round)
                            drawRoundRect(lerp(fold.background, fold.surface, 0.9f), cornerRadius = r)
                            drawRoundRect(fold.ink.copy(alpha = 0.08f + 0.2f * if (flashed) flash.value else 0f), cornerRadius = r)
                            if (lit > 0f) drawRoundRect(Brush.linearGradient(listOf(fold.ringStart, fold.ringEnd), Offset.Zero, Offset(size.width, size.height)),
                                cornerRadius = r, alpha = 0.5f * lit)
                            drawRoundRect(fold.ink.copy(alpha = 0.12f), cornerRadius = r, style = Stroke(1.dp.toPx()))
                        }
                        .semantics { contentDescription = chip.label }
                        .pointerInput(index) { detectTapGestures { tap(PcKeyboard.Focus(PcKeyboard.CHIP_ROW, index, PcKeyboard.chipCenter(index))) } },
                    contentAlignment = Alignment.Center,
                ) {
                    val labelSize = with(density) { (unit * 0.34f).toSp() }
                    when (chip) {
                        PcKeyboard.Chip.Esc -> Glyph(R.drawable.fc_pc_escape, unit * 0.46f, fold.ink)
                        PcKeyboard.Chip.Tab -> Glyph(R.drawable.fc_pc_tab, unit * 0.46f, fold.ink)
                        else -> BasicText(chip.label, maxLines = 1, style = fold.hint.copy(color = fold.ink, fontWeight = if (latched) fold.strong else null,
                            fontSize = with(density) { (unit * when { chip.label.length == 1 -> 0.44f; chip.label.length > 4 -> 0.27f; else -> 0.34f }).toSp() }))
                    }
                }
            }
            rows.forEachIndexed { row, keys ->
                keys.forEachIndexed { index, placed ->
                    val at = PcKeyboard.Focus(row, index, placed.center)
                    val rect = keyRect(placed, row, unitPx, unitYPx, leftPx, keysTopPx)
                    val focused = !state.focus.onChips && state.focus.row == row && state.focus.index == index
                    Key(placed, state, focused, state.pressedKey?.let { it.row == at.row && it.index == at.index } == true, { flash.value }, { caps }, unit,
                        Modifier
                            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                            .pointerInput(row, index) { detectTapGestures { tap(at) } })
                }
            }
        }
    }
}

@Composable
private fun Key(placed: PcKeyboard.Placed, state: PcKeyboard.State, focused: Boolean, flashed: Boolean, flash: () -> Float,
                caps: () -> Float, unit: Dp, modifier: Modifier) {
    val fold = LocalFold.current
    val density = LocalDensity.current
    val lift by bouncy(if (focused) 1f else 0f)
    val lit by eased(if (focused) 1f else 0f)
    val cap = placed.cap
    val shiftLit by eased(if (cap == Cap.ShiftKey && state.shift != PcKeyboard.Shift.Off) 1f else 0f)
    val lamp by eased(if (cap == Cap.ShiftKey && state.shift == PcKeyboard.Shift.Locked) 1f else 0f)
    Box(
        modifier
            .graphicsLayer {
                val pressed = if (flashed) flash() else 0f
                val grown = 1f + (fold.scaleFocus - 1f) * lift - fold.keySquash * pressed
                scaleX = grown
                scaleY = grown
            }
            .drawBehind {
                val radius = CornerRadius(unit.toPx() * KEY_CORNER)
                val body = if (cap is Cap.Glyph || cap is Cap.Fn) 0.12f else 0.05f
                // Glass: lit at the top, the surface below, opaque so the backdrop never cuts a key.
                drawRoundRect(Brush.verticalGradient(listOf(lerp(fold.surface, fold.ink, body + 0.06f * lit), fold.surface)), cornerRadius = radius)
                if (cap == Cap.Done || shiftLit > 0f) {
                    drawRoundRect(Brush.linearGradient(listOf(fold.ringStart, fold.ringEnd), Offset.Zero, Offset(size.width, size.height)),
                        cornerRadius = radius, alpha = if (cap == Cap.Done) 0.55f else 0.38f * shiftLit)
                }
                val pressed = if (flashed) flash() else 0f
                if (pressed > 0f) drawRoundRect(fold.ink, cornerRadius = radius, alpha = 0.22f * pressed)
                drawRoundRect(Brush.verticalGradient(listOf(fold.ink.copy(alpha = 0.1f), Color.Transparent), 0f, size.height * 0.5f), cornerRadius = radius)
                drawRoundRect(Brush.verticalGradient(listOf(fold.ink.copy(alpha = 0.22f), fold.ink.copy(alpha = 0.04f))), cornerRadius = radius,
                    style = Stroke(1.dp.toPx()))
                if (lamp > 0f) {
                    val w = size.width
                    drawRoundRect(Brush.horizontalGradient(listOf(fold.ringStart, fold.ringEnd)), Offset(w * 0.3f, size.height * 0.8f),
                        Size(w * 0.4f, size.height * 0.07f), CornerRadius(size.height * 0.035f), alpha = lamp)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val label = with(density) { (unit * 0.44f).toSp() }
        val word = with(density) { (unit * 0.34f).toSp() }
        val glyph = unit * 0.44f
        when (cap) {
            is Cap.Glyph -> {
                val style = TextStyle(color = fold.ink, fontSize = label, fontFamily = fold.font)
                if (cap.lower == cap.upper) BasicText(cap.lower.toString(), style = style)
                else {
                    val roll = with(density) { (unit * 0.18f).toPx() }
                    BasicText(cap.lower.toString(), style = style, modifier = Modifier.graphicsLayer { alpha = 1f - caps(); translationY = caps() * roll })
                    BasicText(cap.upper.toString(), style = style, modifier = Modifier.graphicsLayer { alpha = caps(); translationY = (caps() - 1f) * roll })
                }
            }
            is Cap.Fn -> BasicText("F${cap.number}", style = TextStyle(color = fold.ink, fontSize = word, fontFamily = fold.font, fontWeight = fold.strong))
            Cap.ShiftKey -> KeyArt(if (shiftLit > 0.5f) R.drawable.fc_ic_control_shift_on else R.drawable.fc_ic_control_shift, R.drawable.fc_ic_btn_l2, glyph)
            Cap.Erase -> KeyArt(R.drawable.fc_ic_control_erase, R.drawable.fc_ic_btn_x, glyph)
            Cap.Space -> KeyArt(R.drawable.fc_ic_control_space, R.drawable.fc_ic_btn_y, glyph, artWidth = 2.4f)
            Cap.Done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(unit * 0.16f)) {
                BasicText("Done", style = TextStyle(color = fold.ink, fontSize = word, fontFamily = fold.font, fontWeight = fold.strong))
                Glyph(R.drawable.fc_ic_btn_triangle, glyph, null, Modifier.graphicsLayer { alpha = 0.62f })
            }
        }
    }
}

@Composable
private fun KeyArt(art: Int, button: Int, size: Dp, artWidth: Float = 1f) {
    val fold = LocalFold.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size * 0.3f)) {
        Glyph(art, size, fold.ink, Modifier.size(size * artWidth, size))
        Glyph(button, size, null, Modifier.graphicsLayer { alpha = 0.62f })
    }
}

private fun keyRect(key: PcKeyboard.Placed, row: Int, unit: Float, unitY: Float, left: Float, top: Float): Rect {
    val gap = unit * KEY_GAP
    return Rect(Offset(left + key.x * unit + gap / 2f, top + row * unitY * ROW_PITCH), Size(key.width * unit - gap, unitY * (ROW_PITCH - KEY_GAP)))
}

private fun Rect.grown(by: Float): Rect {
    val dx = width * (by - 1f) / 2f
    val dy = height * (by - 1f) / 2f
    return Rect(left - dx, top - dy, right + dx, bottom + dy)
}

/** A highlight that glides to each new place and lands with a bounce. Null until first placed. */
@Composable
private fun rememberGliding(target: Rect?): () -> Rect? {
    val fold = LocalFold.current
    val left = remember { Animatable(0f) }
    val top = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        val to = target ?: return@LaunchedEffect
        if (!placed) {
            left.snapTo(to.left); top.snapTo(to.top); width.snapTo(to.width); height.snapTo(to.height)
            placed = true
            return@LaunchedEffect
        }
        steady {
            kotlinx.coroutines.coroutineScope {
                launch { left.animateTo(to.left, fold.playfulSpec()) }
                launch { top.animateTo(to.top, fold.playfulSpec()) }
                launch { width.animateTo(to.width, fold.arriveSpec(fold.durationSelect)) }
                launch { height.animateTo(to.height, fold.arriveSpec(fold.durationSelect)) }
            }
        }
    }
    return { if (!placed) null else Rect(Offset(left.value, top.value), Size(width.value, height.value)) }
}

// The keyboard's proportions, in key widths, as Foldcade's.
private const val ROW_PITCH = 1.12f
private const val KEY_GAP = 0.14f
private const val KEY_CORNER = 0.2f
private const val CHIP_HEIGHT = 0.84f
private const val CHIP_GAP = 0.42f
private const val CHIP_SPACING = 0.2f

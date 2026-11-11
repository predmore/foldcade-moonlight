package com.limelight.foldcade.ui

import android.app.Activity
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.limelight.R
import com.limelight.foldcade.Appearance

/**
 * Ending a session played on one screen, where there is no companion: the End choice drawn in the
 * companion's style over the stream, in place of a stock dialog. A is Disconnect, the safe choice
 * and first in focus; Quit game takes a hold, of A or a finger. B or Back closes it. An
 * accessibility service's activation of Quit, which cannot hold, asks "Quit game?" first, as the
 * companion does. The stream window hands it every key while it is up ([key]).
 */
class StreamEndPanel private constructor(
    private val activity: Activity,
    private val onDisconnect: Runnable,
    private val onQuit: Runnable,
    private val onClosed: Runnable,
) {
    private var focus by mutableIntStateOf(0)
    private var holdingA by mutableStateOf(false)
    /** "Quit game?" is up, in place of the two choices. */
    private var confirmingQuit by mutableStateOf(false)
    private val owner = PanelOwner()
    private val view = ComposeView(activity)

    companion object {
        /** Shows the panel over [activity]'s content. */
        @JvmStatic
        fun show(activity: Activity, onDisconnect: Runnable, onQuit: Runnable, onClosed: Runnable): StreamEndPanel =
            StreamEndPanel(activity, onDisconnect, onQuit, onClosed).also { it.attach() }
    }

    private fun attach() {
        val scale = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val fold = Fold(Appearance.AFTERGLOW, scale)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent { CompositionLocalProvider(LocalFold provides fold) { Panel() } }
        activity.addContentView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Closes the panel without choosing. */
    fun dismiss() {
        (view.parent as? ViewGroup)?.removeView(view)
        owner.destroy()
        onClosed.run()
    }

    /** Every key while the panel is up. True: the panel took it (all but volume and power). */
    fun key(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER -> return false
        }
        if (confirmingQuit) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                    if (down && event.repeatCount == 0) choose(onQuit)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) confirmingQuit = false
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) focus = 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) focus = 1
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount > 0) return true
                if (focus == 0) { if (down) choose(onDisconnect) } else holdingA = down
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) dismiss()
        }
        return true
    }

    private fun choose(action: Runnable) {
        (view.parent as? ViewGroup)?.removeView(view)
        owner.destroy()
        action.run()
    }

    @Composable
    private fun Panel() {
        val fold = LocalFold.current
        var target by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) { target = 1f }
        val shown by eased(target, fold.durationIsland)
        Box(
            Modifier.fillMaxSize()
                .drawBehind { drawRect(fold.background.copy(alpha = 0.78f * shown)) }
                .pointerInput(Unit) { detectTapGestures { if (confirmingQuit) confirmingQuit = false else dismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            if (confirmingQuit) {
                MessagePanel(
                    title = "Quit game?", body = "It closes the game on your PC.", primary = "Quit", secondary = "Cancel",
                    primaryFocused = true, secondaryFocused = false,
                    onPrimary = { choose(onQuit) }, onSecondary = { confirmingQuit = false },
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
                )
                return@Box
            }
            val shape = RoundedCornerShape(fold.cardCorner)
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .padding(24.dp)
                    .rise(fold) { shown }
                    .clip(shape)
                    .drawBehind { drawRect(fold.cardFill()) }
                    .border(2.dp, fold.ink.copy(alpha = fold.borderAlpha), shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(24.dp),
            ) {
                BasicText("End session", style = fold.title)
                Gap(height = 20.dp)
                Row(Modifier.width(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Choice("Disconnect", R.drawable.fc_pc_exit, fold.on, focus == 0, hold = false, Modifier.weight(1f)) { choose(onDisconnect) }
                    Choice("Quit game", R.drawable.fc_symbol_close, fold.rose, focus == 1, hold = true, Modifier.weight(1f)) { choose(onQuit) }
                }
                Gap(height = 16.dp)
                HintRow {
                    Hint(R.drawable.fc_ic_btn_a, if (focus == 1) "Hold to quit" else "Disconnect")
                    Hint(R.drawable.fc_ic_btn_b, "Cancel")
                }
            }
        }
    }

    @Composable
    private fun Choice(label: String, icon: Int, accent: androidx.compose.ui.graphics.Color, focused: Boolean, hold: Boolean, modifier: Modifier, onDone: () -> Unit) {
        val fold = LocalFold.current
        var touching by remember { mutableStateOf(false) }
        val progress = remember { Animatable(0f) }
        val active = hold && (touching || (focused && holdingA))
        LaunchedEffect(active) {
            if (active) {
                progress.animateTo(1f, tween((fold.durationHold * fold.animatorScale.coerceAtLeast(0.5f)).toInt(), easing = fold.dissolve))
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onDone()
            } else if (progress.value > 0f) {
                steady { progress.animateTo(0f, fold.arriveSpec(fold.durationFocus)) }
            }
        }
        val corner = 22.dp
        Box(
            modifier
                .heightIn(min = 64.dp)
                .widthIn(min = 180.dp)
                .focusRing(focused, corner, pronounced = true)
                .drawWithContent {
                    val r = CornerRadius(corner.toPx())
                    drawRoundRect(fold.ink.copy(alpha = 0.08f), cornerRadius = r)
                    if (progress.value > 0f) drawRoundRect(accent.copy(alpha = 0.4f), Offset(0f, size.height * (1f - progress.value)),
                        Size(size.width, size.height * progress.value), r)
                    drawContent()
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    // An accessibility activation cannot hold, so for Quit it asks first instead.
                    onClick(label = if (hold) "Ask to quit" else null) {
                        if (hold) { focus = 1; confirmingQuit = true } else onDone()
                        true
                    }
                }
                .pointerInput(hold) {
                    detectTapGestures(
                        onPress = { if (hold) { touching = true; tryAwaitRelease(); touching = false } },
                        onTap = { if (!hold) onDone() },
                    )
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(icon, 30.dp, accent)
                Gap(width = 12.dp)
                Column {
                    BasicText(label, style = fold.row.copy(fontWeight = fold.strong))
                    if (hold) BasicText("Hold", style = fold.small.copy(color = accent, fontSize = 12.sp))
                }
            }
        }
    }
}

/** The lifecycle a ComposeView needs, for one placed in a plain Activity's window. */
private class PanelOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

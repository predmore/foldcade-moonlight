package com.limelight.foldcade.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.limelight.foldcade.CompanionNav

/**
 * The companion's own controller focus. Compose's focus system is never involved: keys reach
 * the companion only while it owns the controller, and then only through [NavHost].
 *
 * Each page is a layer; only the top layer's targets take focus. A target can take left and
 * right for itself ([NavTarget.adjust]), returning false at an end so the D-pad moves on; it
 * can name where a direction leads ([NavTarget.moves]); and it can ask for a hold on A.
 */
@Stable
internal class NavHost {
    /** The companion owns the controller: focus shows and keys arrive. */
    var owned by mutableStateOf(false)
    var layer by mutableStateOf(0)
    var focused by mutableStateOf<String?>(null)
    /** The target whose A is held down for a hold. */
    var holding by mutableStateOf<String?>(null)
    /** Bumped when a press is refused, so the refused target can shake. */
    var refused by mutableStateOf<Pair<String, Int>?>(null)
    /** The last refusal whose tip was shown, so a screen coming back does not show it again. */
    var tipShown = 0

    /**
     * Focus asked for before its target exists, as when a page changes and its rows have not
     * composed yet. It lands when the target registers, and nothing shows focus until then.
     */
    var pending by mutableStateOf<String?>(null)
        private set

    internal val targets = HashMap<String, NavTarget>()
    private val lastFocus = HashMap<Int, String>()
    /** What B does on each layer. */
    val backs = HashMap<Int, () -> Unit>()

    /** Focus shows only on a target that is really there on the top layer. */
    fun isFocused(id: String) = owned && focused == id && pending == null && targets[id]?.layer == layer

    fun openLayer(layer: Int, first: String?) {
        focused?.let { lastFocus[this.layer] = it }
        holding = null
        this.layer = layer
        pending = null
        if (first != null) focus(first) else focused = null
    }

    fun closeLayer(to: Int) {
        holding = null
        layer = to
        pending = null
        focused = lastFocus[to] ?: focused
    }

    /** Focuses [id] now if it is on this layer, or as soon as it registers. */
    fun focus(id: String) {
        val target = targets[id]
        if (target != null && target.layer == layer) {
            focused = id
            pending = null
        } else {
            pending = id
        }
    }

    internal fun register(target: NavTarget) {
        targets[target.id] = target
        if (pending == target.id && target.layer == layer) {
            focused = target.id
            pending = null
        }
    }

    internal fun unregister(target: NavTarget) {
        if (targets[target.id] === target) targets.remove(target.id)
    }

    /** Moves focus, or lets the focused target take left and right. True if anything happened. */
    fun move(direction: CompanionNav.Direction): Boolean {
        // A target that never arrived (its page closed first) gives up; the move starts from what has focus.
        if (pending != null) pending = null
        val current = focused?.let(targets::get)?.takeIf { it.layer == layer }
        if (current == null) {
            focused = targets.entries.filter { it.value.layer == layer }.minByOrNull { it.value.box.top * 10000 + it.value.box.left }?.key
            return focused != null
        }
        val adjust = current.adjust
        if (adjust != null && (direction == CompanionNav.Direction.Left || direction == CompanionNav.Direction.Right)) {
            if (adjust(if (direction == CompanionNav.Direction.Right) 1 else -1)) return true
        }
        current.moves[direction]?.invoke()?.let { next ->
            focus(next)
            return true
        }
        val candidates = targets.filter { it.value.layer == layer && it.value.enabled }.mapValues { it.value.box }
        val next = CompanionNav.next(current.box, candidates, direction) ?: return false
        focused = next
        return true
    }

    fun press(down: Boolean) {
        val target = focused?.let(targets::get)?.takeIf { it.layer == layer && pending == null } ?: return
        if (target.hold) {
            holding = if (down) target.id else null
        } else if (down && target.enabled) {
            target.activate()
        }
    }

    fun back() { backs[layer]?.invoke() }

    fun refuse(id: String) { refused = id to ((refused?.second ?: 0) + 1) }

    /** The focused target, when it is on this layer. */
    fun current(): NavTarget? = focused?.let(targets::get)?.takeIf { it.layer == layer && pending == null }
}

internal class NavTarget(val id: String) {
    var box = CompanionNav.Box(0f, 0f, 0f, 0f)
    var layer = 0
    var enabled = true
    var hold = false
    var activate: () -> Unit = {}
    var adjust: ((Int) -> Boolean)? = null
    /** Where a direction leads from here, when geometry would pick the wrong place. */
    var moves: Map<CompanionNav.Direction, () -> String?> = emptyMap()
}

/** Makes this element a place the D-pad can reach on [layer]. */
@Composable
internal fun Modifier.navTarget(
    nav: NavHost,
    id: String,
    layer: Int,
    enabled: Boolean = true,
    hold: Boolean = false,
    adjust: ((Int) -> Boolean)? = null,
    moves: Map<CompanionNav.Direction, () -> String?> = emptyMap(),
    activate: () -> Unit,
): Modifier {
    val onActivate by rememberUpdatedState(activate)
    val onAdjust by rememberUpdatedState(adjust)
    val target = remember(id) { NavTarget(id) }
    target.layer = layer
    target.enabled = enabled
    target.hold = hold
    target.activate = { onActivate() }
    target.adjust = if (adjust == null) null else { by -> onAdjust?.invoke(by) ?: false }
    target.moves = moves
    DisposableEffect(id) {
        nav.register(target)
        onDispose { nav.unregister(target) }
    }
    return onGloballyPositioned {
        // Unclipped, so a row scrolled out of view still has its place below or above the others.
        val p = it.positionInRoot()
        target.box = CompanionNav.Box(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
    }
}

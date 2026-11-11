/*
 * Adapted from Foldcade's language/Keyboard.kt (the layout, how the D-pad walks it, and
 * Shift), which is MIT licensed: see assets/licenses/foldcade-MIT.txt. The PC typing is new
 * to this app and GPL-3.0.
 */
package com.limelight.foldcade

import android.view.KeyEvent
import kotlin.math.abs

/**
 * Android-independent apart from key codes: the controller keyboard that types to the PC.
 * Foldcade's keyboard edits a name; this one sends every key to the PC as it is pressed, so
 * the PC's own field holds the text. Foldcade's buttons keep their meanings: A presses the
 * focused key, X erases, Y types a space, L1 and R1 move the caret, L2 steps Shift, Start
 * is Done and B closes. R2 is Enter.
 */
object PcKeyboard {
    sealed interface Cap {
        /** Types [lower], or [upper] while Shift is on. */
        data class Glyph(val lower: Char, val upper: Char = lower.uppercaseChar()) : Cap {
            fun shown(shift: Shift): Char = if (shift == Shift.Off) lower else upper
        }

        data object ShiftKey : Cap
        data object Erase : Cap
        data object Space : Cap
        data object Done : Cap
    }

    /** A key and where it sits in its row, in key widths from the keyboard's left edge. */
    data class Placed(val cap: Cap, val x: Float, val width: Float = 1f) {
        val center: Float get() = x + width / 2f
        val end: Float get() = x + width
    }

    const val WIDTH = 12f

    /**
     * Foldcade's QWERTY under a number row, staggered like a real keyboard. Its marks are the
     * ones a PC field asks for; Shift gives each its partner, as a PC keyboard's Shift does.
     */
    val rows: List<List<Placed>> = listOf(
        glyphs(0f, "1234567890", "!@#$%^&*()") + listOf(Placed(Cap.Glyph('-', '_'), 10f), Placed(Cap.Glyph('/', '\\'), 11f)),
        glyphs(0f, "qwertyuiop") + listOf(Placed(Cap.Glyph('\'', '"'), 10f), Placed(Cap.Glyph('=', '+'), 11f)),
        glyphs(0.5f, "asdfghjkl") + Placed(Cap.Glyph(';', ':'), 9.5f) + Placed(Cap.Glyph('?', '!'), 10.5f),
        listOf(Placed(Cap.ShiftKey, 0f, 1.5f)) + glyphs(1.5f, "zxcvbnm") + Placed(Cap.Glyph(',', '<'), 8.5f) +
            Placed(Cap.Glyph('.', '>'), 9.5f) + Placed(Cap.Erase, 10.5f, 1.5f),
        listOf(Placed(Cap.Space, 0f, 9f), Placed(Cap.Done, 9f, 3f)),
    )

    /** The chips above the keys: PC keys a field needs that the letters do not give. */
    val chips: List<Pair<String, PcShortcut>> = listOf("Esc" to PcShortcut.ESCAPE, "Tab" to PcShortcut.TAB, "Enter" to PcShortcut.ENTER)

    const val CHIP_ROW = -1

    /** The cursor: a key, or a chip when [row] is [CHIP_ROW]. [x] is kept while it goes up and down. */
    data class Focus(val row: Int, val index: Int, val x: Float) {
        val onChips: Boolean get() = row == CHIP_ROW
    }

    fun key(focus: Focus): Placed? = if (focus.onChips) null else rows.getOrNull(focus.row)?.getOrNull(focus.index)

    fun find(cap: Cap): Focus {
        rows.forEachIndexed { row, keys ->
            val index = keys.indexOfFirst { it.cap == cap }
            if (index >= 0) return Focus(row, index, keys[index].center)
        }
        error("$cap is not on the keyboard")
    }

    /** G, in the middle of the home row: the shortest reach to anything. */
    val start: Focus get() = find(Cap.Glyph('g'))

    enum class Direction { Up, Down, Left, Right }

    /** Where the D-pad takes [focus]; null at an edge, since nothing wraps. As Foldcade's `moveKey`. */
    fun move(focus: Focus, direction: Direction, lastChip: Int = 0): Focus? = when (direction) {
        Direction.Left, Direction.Right -> {
            val next = focus.index + if (direction == Direction.Left) -1 else 1
            if (focus.onChips) {
                if (next in chips.indices) focus.copy(index = next) else null
            } else {
                rows[focus.row].getOrNull(next)?.let { Focus(focus.row, next, it.center) }
            }
        }
        Direction.Up -> when {
            focus.onChips -> null
            focus.row == 0 -> Focus(CHIP_ROW, lastChip.coerceIn(0, chips.size - 1), focus.x)
            else -> keyUnder(focus.row - 1, focus.x)
        }
        Direction.Down -> when {
            focus.onChips -> keyUnder(0, focus.x)
            focus.row == rows.lastIndex -> null
            else -> keyUnder(focus.row + 1, focus.x)
        }
    }

    private fun keyUnder(row: Int, x: Float): Focus {
        val keys = rows[row]
        val index = keys.indexOfFirst { x >= it.x && x < it.end }.takeIf { it >= 0 } ?: keys.indices.minBy { abs(keys[it].center - x) }
        return Focus(row, index, x)
    }

    /** One capital, then caps lock, then off, as Foldcade's L2. */
    enum class Shift { Off, Once, Locked; fun next(): Shift = when (this) { Off -> Once; Once -> Locked; Locked -> Off } }

    /** What a press means on the keyboard. */
    enum class Action { Up, Down, Left, Right, Press, Erase, Space, ShiftStep, CaretBack, CaretForward, Done, Close, Enter }

    /** The controller's buttons and a keyboard's own keys, as the keyboard reads them. */
    fun action(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Action.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> Action.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> Action.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> Action.Right
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> Action.Press
        KeyEvent.KEYCODE_BUTTON_X -> Action.Erase
        KeyEvent.KEYCODE_BUTTON_Y -> Action.Space
        KeyEvent.KEYCODE_BUTTON_L2 -> Action.ShiftStep
        KeyEvent.KEYCODE_BUTTON_L1 -> Action.CaretBack
        KeyEvent.KEYCODE_BUTTON_R1 -> Action.CaretForward
        KeyEvent.KEYCODE_BUTTON_START -> Action.Done
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> Action.Close
        KeyEvent.KEYCODE_BUTTON_R2 -> Action.Enter
        else -> null
    }

    /** What a press sends to the PC, if anything. */
    sealed interface Output {
        data class Text(val text: String) : Output
        data class Key(val shortcut: PcShortcut) : Output
        data object Close : Output
        data object None : Output
    }

    /**
     * The open keyboard. [pressed] counts presses and [pressedKey] is the key that acted last, so
     * the board can flash it even when a button did the work, as Foldcade's does.
     */
    data class State(
        val focus: Focus = start,
        val shift: Shift = Shift.Off,
        val chip: Int = 0,
        val pressed: Int = 0,
        val pressedKey: Focus? = null,
    ) {
        fun on(action: Action): Pair<State, Output> = when (action) {
            Action.Up, Action.Down, Action.Left, Action.Right -> {
                val next = move(focus, Direction.valueOf(action.name), chip)
                (if (next == null) this else copy(focus = next, chip = if (next.onChips) next.index else chip)) to Output.None
            }
            Action.Press -> pressFocused()
            Action.Erase -> pressing(find(Cap.Erase)) to Output.Key(PcShortcut.BACKSPACE)
            Action.Space -> pressing(find(Cap.Space)) to Output.Text(" ")
            Action.ShiftStep -> copy(shift = shift.next()).pressing(find(Cap.ShiftKey)) to Output.None
            Action.CaretBack -> this to Output.Key(PcShortcut.LEFT)
            Action.CaretForward -> this to Output.Key(PcShortcut.RIGHT)
            Action.Done -> pressing(find(Cap.Done)) to Output.Close
            Action.Close -> this to Output.Close
            Action.Enter -> pressing(Focus(CHIP_ROW, 2, focus.x)) to Output.Key(PcShortcut.ENTER)
        }

        /** A tap on a key or chip: the cursor goes there and presses it, as A would. */
        fun press(at: Focus): Pair<State, Output> {
            val valid = if (at.onChips) at.index in chips.indices else key(at) != null
            if (!valid) return this to Output.None
            return copy(focus = at, chip = if (at.onChips) at.index else chip).pressFocused()
        }

        private fun pressFocused(): Pair<State, Output> {
            if (focus.onChips) {
                val chip = chips.getOrNull(focus.index) ?: return this to Output.None
                return pressing(focus) to Output.Key(chip.second)
            }
            return when (val cap = key(focus)?.cap) {
                is Cap.Glyph -> {
                    val char = cap.shown(shift)
                    // A single capital is spent by the character it was for; caps lock stays.
                    val after = if (shift == Shift.Once) Shift.Off else shift
                    copy(shift = after).pressing(focus) to Output.Text(char.toString())
                }
                Cap.ShiftKey -> copy(shift = shift.next()).pressing(focus) to Output.None
                Cap.Erase -> pressing(focus) to Output.Key(PcShortcut.BACKSPACE)
                Cap.Space -> pressing(focus) to Output.Text(" ")
                Cap.Done -> pressing(focus) to Output.Close
                null -> this to Output.None
            }
        }

        private fun pressing(key: Focus?) = copy(pressed = pressed + 1, pressedKey = key)
    }

    private fun glyphs(from: Float, chars: String, shifted: String? = null): List<Placed> =
        chars.mapIndexed { index, char -> Placed(Cap.Glyph(char, shifted?.get(index) ?: char.uppercaseChar()), from + index) }
}

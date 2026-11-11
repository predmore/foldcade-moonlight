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
 *
 * The chips above the keys are the PC keys letters do not give: Esc, Tab, Ctrl and Alt (sticky:
 * they latch for the next key), the arrows, and F1–F12, which swaps the number row for F-keys.
 */
object PcKeyboard {
    sealed interface Cap {
        /** Types [lower], or [upper] while Shift is on. */
        data class Glyph(val lower: Char, val upper: Char = lower.uppercaseChar()) : Cap {
            fun shown(shift: Shift): Char = if (shift == Shift.Off) lower else upper
        }

        /** F1 to F12, in the number row's place while the F-key layer is on. */
        data class Fn(val number: Int) : Cap

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

    private val numberRow = glyphs(0f, "1234567890", "!@#$%^&*()") +
        listOf(Placed(Cap.Glyph('-', '_'), 10f), Placed(Cap.Glyph('/', '?'), 11f))
    private val fRow = (1..12).map { Placed(Cap.Fn(it), it - 1f) }

    /**
     * Foldcade's QWERTY under a number row, staggered like a real keyboard. Each mark appears
     * once, and Shift gives it its partner, as a PC keyboard's Shift does: ` and ~ are there for
     * the console many PC games open with them, and \ and | sit beside Space.
     */
    private val letterRows: List<List<Placed>> = listOf(
        glyphs(0f, "qwertyuiop") + listOf(Placed(Cap.Glyph('\'', '"'), 10f), Placed(Cap.Glyph('=', '+'), 11f)),
        glyphs(0.5f, "asdfghjkl") + Placed(Cap.Glyph(';', ':'), 9.5f) + Placed(Cap.Glyph('`', '~'), 10.5f),
        listOf(Placed(Cap.ShiftKey, 0f, 1.5f)) + glyphs(1.5f, "zxcvbnm") + Placed(Cap.Glyph(',', '<'), 8.5f) +
            Placed(Cap.Glyph('.', '>'), 9.5f) + Placed(Cap.Erase, 10.5f, 1.5f),
        listOf(Placed(Cap.Space, 0f, 8f), Placed(Cap.Glyph('\\', '|'), 8f), Placed(Cap.Done, 9f, 3f)),
    )

    /** The keys, with F1–F12 in the number row's place when [fKeys]. */
    fun rows(fKeys: Boolean): List<List<Placed>> = listOf(if (fKeys) fRow else numberRow) + letterRows

    val rows: List<List<Placed>> = rows(false)

    /** The chips above the keys, left to right. */
    enum class Chip(val label: String) {
        Esc("Esc"), Tab("Tab"), Ctrl("Ctrl"), Alt("Alt"), Left("←"), Up("↑"), Down("↓"), Right("→"), FKeys("F1–F12")
    }

    val chips: List<Chip> = Chip.entries

    const val CHIP_ROW = -1

    /** The cursor: a key, or a chip when [row] is [CHIP_ROW]. [x] is kept while it goes up and down. */
    data class Focus(val row: Int, val index: Int, val x: Float) {
        val onChips: Boolean get() = row == CHIP_ROW
    }

    fun key(focus: Focus, fKeys: Boolean = false): Placed? = if (focus.onChips) null else rows(fKeys).getOrNull(focus.row)?.getOrNull(focus.index)

    fun find(cap: Cap): Focus {
        rows.forEachIndexed { row, keys ->
            val index = keys.indexOfFirst { it.cap == cap }
            if (index >= 0) return Focus(row, index, keys[index].center)
        }
        error("$cap is not on the keyboard")
    }

    /** G, in the middle of the home row: the shortest reach to anything. */
    val start: Focus get() = find(Cap.Glyph('g'))

    /** Each chip's share of the width; a chip's column is where the cursor lands under or over it. */
    private val chipWidth = WIDTH / Chip.entries.size

    fun chipCenter(index: Int): Float = chipWidth * (index + 0.5f)

    enum class Direction { Up, Down, Left, Right }

    /** Where the D-pad takes [focus]; null at an edge, since nothing wraps. As Foldcade's `moveKey`. */
    fun move(focus: Focus, direction: Direction, fKeys: Boolean = false): Focus? {
        val rows = rows(fKeys)
        return when (direction) {
            Direction.Left, Direction.Right -> {
                val next = focus.index + if (direction == Direction.Left) -1 else 1
                if (focus.onChips) {
                    if (next in chips.indices) Focus(CHIP_ROW, next, chipCenter(next)) else null
                } else {
                    rows[focus.row].getOrNull(next)?.let { Focus(focus.row, next, it.center) }
                }
            }
            Direction.Up -> when {
                focus.onChips -> null
                focus.row == 0 -> {
                    val index = (focus.x / chipWidth).toInt().coerceIn(0, chips.size - 1)
                    Focus(CHIP_ROW, index, focus.x)
                }
                else -> keyUnder(rows, focus.row - 1, focus.x)
            }
            Direction.Down -> when {
                focus.onChips -> keyUnder(rows, 0, focus.x)
                focus.row == rows.lastIndex -> null
                else -> keyUnder(rows, focus.row + 1, focus.x)
            }
        }
    }

    private fun keyUnder(rows: List<List<Placed>>, row: Int, x: Float): Focus {
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
        /** A Windows virtual key with modifiers held around it ([PcShortcut.CTRL] and friends, or'd). */
        data class Stroke(val vk: Int, val modifiers: Int = 0) : Output
        data object Close : Output
        data object None : Output
    }

    /** How many typed characters the echo above the keys keeps. */
    const val ECHO = 24

    /**
     * The open keyboard. [ctrl] and [alt] are latched for the next key; [fKeys] has F1–F12 in the
     * number row. [echo] is what was typed while it was open, shown faintly above the keys and
     * never sent anywhere. [pressed] counts presses and [pressedKey] is the key that acted last,
     * so the board can flash it even when a button did the work, as Foldcade's does. [sent] names
     * the last shortcut, as "Ctrl+A", which the echo shows for a moment in place of what it
     * cleared; [sentCount] tells one sending from the next. Display only.
     */
    data class State(
        val focus: Focus = start,
        val shift: Shift = Shift.Off,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val fKeys: Boolean = false,
        val echo: String = "",
        val pressed: Int = 0,
        val pressedKey: Focus? = null,
        val sent: String = "",
        val sentCount: Int = 0,
    ) {
        /** The modifiers a stroke goes out with now: latched Ctrl and Alt, and Shift while on. */
        val modifiers: Int get() = (if (ctrl) PcShortcut.CTRL.toInt() else 0) or (if (alt) PcShortcut.ALT.toInt() else 0) or
            (if (shift != Shift.Off) PcShortcut.SHIFT.toInt() else 0)

        fun on(action: Action): Pair<State, Output> = when (action) {
            Action.Up, Action.Down, Action.Left, Action.Right -> {
                val next = move(focus, Direction.valueOf(action.name), fKeys)
                (if (next == null) this else copy(focus = next)) to Output.None
            }
            Action.Press -> pressFocused()
            Action.Erase -> stroke(find(Cap.Erase), VK_BACK, echoErase = true)
            Action.Space -> if (ctrl || alt) stroke(find(Cap.Space), VK_SPACE) else typed(" ", find(Cap.Space))
            Action.ShiftStep -> copy(shift = shift.next()).pressing(find(Cap.ShiftKey)) to Output.None
            Action.CaretBack -> stroke(null, VK_LEFT)
            Action.CaretForward -> stroke(null, VK_RIGHT)
            Action.Done -> pressing(find(Cap.Done)) to Output.Close
            Action.Close -> this to Output.Close
            Action.Enter -> stroke(null, VK_RETURN, clearEcho = true)
        }

        /** A tap on a key or chip: the cursor goes there and presses it, as A would. */
        fun press(at: Focus): Pair<State, Output> {
            val valid = if (at.onChips) at.index in chips.indices else key(at, fKeys) != null
            if (!valid) return this to Output.None
            return copy(focus = at).pressFocused()
        }

        private fun pressFocused(): Pair<State, Output> {
            if (focus.onChips) {
                return when (chips.getOrNull(focus.index)) {
                    Chip.Esc -> stroke(focus, VK_ESCAPE)
                    Chip.Tab -> stroke(focus, VK_TAB)
                    Chip.Ctrl -> copy(ctrl = !ctrl).pressing(focus) to Output.None
                    Chip.Alt -> copy(alt = !alt).pressing(focus) to Output.None
                    Chip.Left -> stroke(focus, VK_LEFT)
                    Chip.Up -> stroke(focus, VK_UP)
                    Chip.Down -> stroke(focus, VK_DOWN)
                    Chip.Right -> stroke(focus, VK_RIGHT)
                    Chip.FKeys -> copy(fKeys = !fKeys).pressing(focus) to Output.None
                    null -> this to Output.None
                }
            }
            return when (val cap = key(focus, fKeys)?.cap) {
                is Cap.Glyph -> {
                    val char = cap.shown(shift)
                    val vk = virtualKey(cap.lower)
                    // With Ctrl or Alt latched a character is a shortcut, sent as its key, not as text.
                    if ((ctrl || alt) && vk != null) stroke(focus, vk) else typed(char.toString(), focus)
                }
                is Cap.Fn -> stroke(focus, VK_F1 + cap.number - 1)
                Cap.ShiftKey -> copy(shift = shift.next()).pressing(focus) to Output.None
                Cap.Erase -> stroke(focus, VK_BACK, echoErase = true)
                Cap.Space -> if (ctrl || alt) stroke(focus, VK_SPACE) else typed(" ", focus)
                Cap.Done -> pressing(focus) to Output.Close
                null -> this to Output.None
            }
        }

        /** Text, echoed. A single capital is spent by the character it was for; caps lock stays. */
        private fun typed(text: String, key: Focus?): Pair<State, Output> {
            val after = if (shift == Shift.Once) Shift.Off else shift
            return copy(shift = after, echo = (echo + text).takeLast(ECHO)).pressing(key) to Output.Text(text)
        }

        /** A key with the modifiers on now. Ctrl, Alt and a single capital are spent by it. */
        private fun stroke(key: Focus?, vk: Int, echoErase: Boolean = false, clearEcho: Boolean = false): Pair<State, Output> {
            val output = Output.Stroke(vk, modifiers)
            val plain = !ctrl && !alt
            // A shortcut can change the PC's field in ways the echo cannot follow (Ctrl+A, Ctrl+V), so
            // it starts over; plain Backspace takes off the last character.
            val echoed = when {
                clearEcho || !plain -> ""
                echoErase -> echo.dropLast(1)
                else -> echo
            }
            val shortcut = if (plain) this else copy(sent = strokeLabel(vk, output.modifiers), sentCount = sentCount + 1)
            return shortcut.copy(ctrl = false, alt = false, shift = if (shift == Shift.Once) Shift.Off else shift, echo = echoed)
                .pressing(key) to output
        }

        private fun pressing(key: Focus?) = copy(pressed = pressed + 1, pressedKey = key)
    }

    /** The Windows virtual key for a character's key, for shortcuts such as Ctrl+C. */
    fun virtualKey(char: Char): Int? = when (char) {
        in 'a'..'z' -> char.uppercaseChar().code
        in '0'..'9' -> char.code
        ' ' -> VK_SPACE
        '-' -> 0xBD; '=' -> 0xBB; '\\' -> 0xDC; ';' -> 0xBA; '\'' -> 0xDE
        ',' -> 0xBC; '.' -> 0xBE; '/' -> 0xBF; '`' -> 0xC0
        else -> null
    }

    /** A stroke as a PC shortcut reads: "Ctrl+Shift+T", "Alt+F4", "Ctrl+←". */
    fun strokeLabel(vk: Int, modifiers: Int): String {
        val names = listOfNotNull(
            "Ctrl".takeIf { modifiers and PcShortcut.CTRL.toInt() != 0 },
            "Alt".takeIf { modifiers and PcShortcut.ALT.toInt() != 0 },
            "Shift".takeIf { modifiers and PcShortcut.SHIFT.toInt() != 0 },
        )
        return (names + keyName(vk)).joinToString("+")
    }

    private fun keyName(vk: Int): String = when (vk) {
        in 0x30..0x39, in 0x41..0x5A -> vk.toChar().toString()
        in VK_F1 until VK_F1 + 12 -> "F${vk - VK_F1 + 1}"
        VK_SPACE -> "Space"; VK_TAB -> "Tab"; VK_ESCAPE -> "Esc"; VK_BACK -> "Backspace"; VK_RETURN -> "Enter"
        VK_LEFT -> "←"; VK_UP -> "↑"; VK_RIGHT -> "→"; VK_DOWN -> "↓"
        0xBD -> "-"; 0xBB -> "="; 0xDC -> "\\"; 0xBA -> ";"; 0xDE -> "'"; 0xBC -> ","; 0xBE -> "."; 0xBF -> "/"; 0xC0 -> "`"
        else -> "Key"
    }

    const val VK_BACK = 0x08
    const val VK_TAB = 0x09
    const val VK_RETURN = 0x0D
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20
    const val VK_LEFT = 0x25
    const val VK_UP = 0x26
    const val VK_RIGHT = 0x27
    const val VK_DOWN = 0x28
    const val VK_F1 = 0x70

    private fun glyphs(from: Float, chars: String, shifted: String? = null): List<Placed> =
        chars.mapIndexed { index, char -> Placed(Cap.Glyph(char, shifted?.get(index) ?: char.uppercaseChar()), from + index) }
}

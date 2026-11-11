package com.limelight.foldcade

import android.view.KeyEvent
import com.limelight.foldcade.PcKeyboard.Action
import com.limelight.foldcade.PcKeyboard.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcKeyboardTest {
    private fun typed(state: PcKeyboard.State, vararg actions: Action): Pair<PcKeyboard.State, List<Output>> {
        var s = state
        val out = ArrayList<Output>()
        for (action in actions) { val (next, o) = s.on(action); s = next; out += o }
        return s to out.filter { it != Output.None }
    }

    @Test fun foldcadesButtonsKeepTheirMeanings() {
        assertEquals(Action.Press, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(Action.Erase, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(Action.Space, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(Action.CaretBack, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(Action.CaretForward, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(Action.ShiftStep, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(Action.Done, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(Action.Close, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(Action.Enter, PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_R2))
        assertNull(PcKeyboard.action(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test fun keysTypeStraightToThePc() {
        // From G: right is H, A types it; X erases with Backspace; Y types a space.
        val (_, out) = typed(PcKeyboard.State(), Action.Right, Action.Press, Action.Erase, Action.Space)
        assertEquals(listOf(Output.Text("h"), Output.Stroke(PcKeyboard.VK_BACK), Output.Text(" ")), out)
    }

    @Test fun shiftIsOneCapitalThenCapsLockThenOff() {
        var (s, out) = typed(PcKeyboard.State(), Action.ShiftStep, Action.Press, Action.Press)
        assertEquals(listOf(Output.Text("G"), Output.Text("g")), out)
        val locked = typed(s, Action.ShiftStep, Action.ShiftStep, Action.Press, Action.Press)
        assertEquals(listOf(Output.Text("G"), Output.Text("G")), locked.second)
        assertEquals(PcKeyboard.Shift.Locked, locked.first.shift)
        s = typed(locked.first, Action.ShiftStep).first
        assertEquals(PcKeyboard.Shift.Off, s.shift)
    }

    @Test fun marksShiftToTheirPartners() {
        val minus = PcKeyboard.Focus(0, 10, 10.5f)
        assertEquals(Output.Text("-"), PcKeyboard.State().press(minus).second)
        assertEquals(Output.Text("_"), PcKeyboard.State(shift = PcKeyboard.Shift.Once).press(minus).second)
        assertEquals(Output.Text("@"), PcKeyboard.State(shift = PcKeyboard.Shift.Once).press(PcKeyboard.Focus(0, 1, 1.5f)).second)
    }

    @Test fun theDpadWalksLikeFoldcadesAndReachesTheChips() {
        var s = PcKeyboard.State()
        // Up three rows from G's column reaches the top row, then the chips, and stops there.
        s = typed(s, Action.Up, Action.Up, Action.Up).first
        assertTrue(s.focus.onChips)
        assertEquals(s, typed(s, Action.Up).first)
        // The chip above G's column is Alt: A latches it and sends nothing yet.
        assertEquals(PcKeyboard.Chip.Alt, PcKeyboard.chips[s.focus.index])
        val (latched, out) = typed(s, Action.Press)
        assertTrue(out.isEmpty())
        assertTrue(latched.alt)
        // Left and right stop at the row's ends rather than wrap.
        val edge = PcKeyboard.State(focus = PcKeyboard.Focus(1, 0, 0.5f))
        assertEquals(edge.focus, typed(edge, Action.Left).first.focus)
        // Down to the bottom row and onto Done, which closes.
        val done = PcKeyboard.find(PcKeyboard.Cap.Done)
        assertEquals(Output.Close, PcKeyboard.State().press(done).second)
    }

    @Test fun caretAndEnterAreKeysAndBCloses() {
        val (_, out) = typed(PcKeyboard.State(), Action.CaretBack, Action.CaretForward, Action.Enter, Action.Close)
        assertEquals(listOf(Output.Stroke(PcKeyboard.VK_LEFT), Output.Stroke(PcKeyboard.VK_RIGHT), Output.Stroke(PcKeyboard.VK_RETURN), Output.Close), out)
    }

    private fun chip(c: PcKeyboard.Chip) = PcKeyboard.Focus(PcKeyboard.CHIP_ROW, c.ordinal, PcKeyboard.chipCenter(c.ordinal))

    @Test fun ctrlAndAltLatchForTheNextKeyAndThenLetGo() {
        var s = PcKeyboard.State().press(chip(PcKeyboard.Chip.Ctrl)).first
        assertTrue(s.ctrl)
        // Ctrl then C is a shortcut: the C key with Ctrl held, not the letter as text.
        val c = PcKeyboard.find(PcKeyboard.Cap.Glyph('c'))
        val (after, out) = s.press(c)
        assertEquals(Output.Stroke('C'.code, PcShortcut.CTRL.toInt()), out)
        assertFalse(after.ctrl)
        // Both latch together, and a chip key takes them too: Ctrl+Alt+Left.
        s = after.press(chip(PcKeyboard.Chip.Ctrl)).first.press(chip(PcKeyboard.Chip.Alt)).first
        assertEquals(Output.Stroke(PcKeyboard.VK_LEFT, (PcShortcut.CTRL.toInt() or PcShortcut.ALT.toInt())), s.press(chip(PcKeyboard.Chip.Left)).second)
        // Pressing a latched modifier again lets it go without sending anything.
        val off = PcKeyboard.State().press(chip(PcKeyboard.Chip.Alt)).first.press(chip(PcKeyboard.Chip.Alt))
        assertFalse(off.first.alt)
        assertEquals(Output.None, off.second)
    }

    @Test fun shortcutsAreNotEchoedButTextIs() {
        val g = PcKeyboard.find(PcKeyboard.Cap.Glyph('g'))
        var s = PcKeyboard.State().press(g).first.press(g).first
        assertEquals("gg", s.echo)
        s = s.on(Action.Erase).first
        assertEquals("g", s.echo)
        // A shortcut may have changed the field, so the echo starts over.
        s = s.press(chip(PcKeyboard.Chip.Ctrl)).first.press(g).first
        assertEquals("", s.echo)
        s = s.press(g).first
        assertEquals("", s.on(Action.Enter).first.echo)
        val long = (1..40).fold(PcKeyboard.State()) { st, _ -> st.press(g).first }
        assertEquals(PcKeyboard.ECHO, long.echo.length)
    }

    @Test fun theFKeyChipSwapsTheNumberRowForF1ToF12() {
        val s = PcKeyboard.State().press(chip(PcKeyboard.Chip.FKeys)).first
        assertTrue(s.fKeys)
        val f4 = PcKeyboard.Focus(0, 3, 3.5f)
        assertEquals(PcKeyboard.Cap.Fn(4), PcKeyboard.key(f4, s.fKeys)?.cap)
        assertEquals(Output.Stroke(PcKeyboard.VK_F1 + 3), s.press(f4).second)
        // The layer stays until the chip is pressed again; off, the same key is 4.
        assertTrue(s.press(f4).first.fKeys)
        assertEquals(Output.Text("4"), PcKeyboard.State().press(f4).second)
        assertEquals(12, PcKeyboard.rows(true)[0].size)
    }

    @Test fun everyMarkAppearsOnceAndTheConsoleKeyIsThere() {
        val chars = PcKeyboard.rows.flatten().mapNotNull { it.cap as? PcKeyboard.Cap.Glyph }.flatMap { listOf(it.lower, it.upper) }
        assertEquals(chars.size, chars.toSet().size)
        for (c in "`~/?\\|") assertTrue("$c", c in chars)
    }

    @Test fun upFromTheTopRowLandsOnTheChipAboveIt() {
        // From the 0 key (x 9.5) the chip above is →; from 1 it is Esc, and Down comes back under it.
        val zero = PcKeyboard.Focus(0, 9, 9.5f)
        val up = PcKeyboard.move(zero, PcKeyboard.Direction.Up)!!
        assertTrue(up.onChips)
        assertEquals(PcKeyboard.Chip.Right, PcKeyboard.chips[up.index])
        val esc = PcKeyboard.move(PcKeyboard.Focus(0, 0, 0.5f), PcKeyboard.Direction.Up)!!
        assertEquals(PcKeyboard.Chip.Esc, PcKeyboard.chips[esc.index])
        assertEquals(0, PcKeyboard.move(esc, PcKeyboard.Direction.Down)!!.index)
    }
}

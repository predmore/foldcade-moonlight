package com.limelight.foldcade

import android.view.KeyEvent
import com.limelight.foldcade.PcKeyboard.Action
import com.limelight.foldcade.PcKeyboard.Output
import org.junit.Assert.assertEquals
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
        assertEquals(listOf(Output.Text("h"), Output.Key(PcShortcut.BACKSPACE), Output.Text(" ")), out)
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
        val (_, out) = typed(s, Action.Press)
        assertEquals(1, out.size)
        assertTrue(out[0] is Output.Key)
        // Left and right stop at the row's ends rather than wrap.
        val edge = PcKeyboard.State(focus = PcKeyboard.Focus(1, 0, 0.5f))
        assertEquals(edge.focus, typed(edge, Action.Left).first.focus)
        // Down to the bottom row and onto Done, which closes.
        val done = PcKeyboard.find(PcKeyboard.Cap.Done)
        assertEquals(Output.Close, PcKeyboard.State().press(done).second)
    }

    @Test fun caretAndEnterAreKeysAndBCloses() {
        val (_, out) = typed(PcKeyboard.State(), Action.CaretBack, Action.CaretForward, Action.Enter, Action.Close)
        assertEquals(listOf(Output.Key(PcShortcut.LEFT), Output.Key(PcShortcut.RIGHT), Output.Key(PcShortcut.ENTER), Output.Close), out)
    }
}

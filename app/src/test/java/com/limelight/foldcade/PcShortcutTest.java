package com.limelight.foldcade;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcShortcutTest {
    private static String strokes(PcShortcut shortcut) { return shortcut.steps().toString(); }

    @Test public void modifiersGoDownInOrderAndComeUpInReverse() {
        // Ctrl (A2), Alt (A4), Delete (2E): each event carries the modifiers held at that moment.
        assertEquals("[A2v2, A4v6, 2Ev6, 2E^6, A4^2, A2^0]", strokes(PcShortcut.CTRL_ALT_DEL));
        assertEquals("[A4v4, 73v4, 73^4, A4^0]", strokes(PcShortcut.ALT_F4));
        assertEquals("[A4v4, 09v4, 09^4, A4^0]", strokes(PcShortcut.ALT_TAB));
    }

    @Test public void aLoneKeyIsATap() {
        assertEquals("[1Bv0, 1B^0]", strokes(PcShortcut.ESCAPE));
        // The Windows key carries its own flag while it is down, as a real press does.
        assertEquals("[5Bv8, 5B^0]", strokes(PcShortcut.START_MENU));
    }

    @Test public void onlyStrokesThatCloseOrLockSomethingNeedAHold() {
        for (PcShortcut shortcut : PcShortcut.values()) {
            assertEquals(shortcut.name(), shortcut == PcShortcut.ALT_F4 || shortcut == PcShortcut.CTRL_ALT_DEL, shortcut.needsHold);
        }
    }

    @Test public void keyCodesCarryMoonlightsPrefix() {
        for (PcShortcut.Step step : PcShortcut.ALT_F4.steps()) assertEquals(0x80, (step.key >> 8) & 0xff);
    }
}

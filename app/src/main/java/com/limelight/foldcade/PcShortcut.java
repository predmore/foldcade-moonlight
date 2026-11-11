package com.limelight.foldcade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android-independent: the PC keys the companion's keycaps send, as Windows virtual-key strokes.
 * Modifiers go down in order, the last key is tapped, then the modifiers come up in reverse,
 * each event carrying the modifier state the PC should see at that moment.
 */
public enum PcShortcut {
    ESCAPE(false, 0x1B),
    /** The Windows key on its own: opens Start. */
    START_MENU(false, 0x5B),
    ALT_TAB(false, 0xA4, 0x09),
    /** Closes the focused window on the PC, so the companion asks for a hold. */
    ALT_F4(true, 0xA4, 0x73),
    /** Opens the PC's security screen, so the companion asks for a hold. */
    CTRL_ALT_DEL(true, 0xA2, 0xA4, 0x2E),
    TAB(false, 0x09),
    ENTER(false, 0x0D),
    BACKSPACE(false, 0x08),
    LEFT(false, 0x25),
    RIGHT(false, 0x27);

    public static final byte KEY_DOWN = 0x03, KEY_UP = 0x04;
    public static final byte SHIFT = 0x01, CTRL = 0x02, ALT = 0x04, META = 0x08;

    /** A stroke the PC must not get by accident takes a deliberate hold. */
    public final boolean needsHold;
    private final int[] keys;

    PcShortcut(boolean needsHold, int... keys) {
        this.needsHold = needsHold;
        this.keys = keys;
    }

    public static final class Step {
        /** Moonlight's key code: the Windows virtual key with its 0x80 prefix. */
        public final short key;
        public final byte direction, modifiers;

        Step(int vk, boolean down, byte modifiers) {
            this.key = (short) (0x8000 | vk);
            this.direction = down ? KEY_DOWN : KEY_UP;
            this.modifiers = modifiers;
        }

        @Override public String toString() {
            return String.format("%02X%s%d", key & 0xff, direction == KEY_DOWN ? "v" : "^", modifiers);
        }
    }

    public List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        byte held = 0;
        for (int i = 0; i < keys.length - 1; i++) {
            held |= modifierOf(keys[i]);
            steps.add(new Step(keys[i], true, held));
        }
        int last = keys[keys.length - 1];
        // A lone modifier (the Windows key) carries its own flag while it is down, as a real press does.
        byte own = (byte) (held | modifierOf(last));
        steps.add(new Step(last, true, own));
        steps.add(new Step(last, false, held));
        for (int i = keys.length - 2; i >= 0; i--) {
            held &= (byte) ~modifierOf(keys[i]);
            steps.add(new Step(keys[i], false, held));
        }
        return Collections.unmodifiableList(steps);
    }

    static byte modifierOf(int vk) {
        switch (vk) {
            case 0xA0: case 0xA1: return SHIFT;
            case 0xA2: case 0xA3: return CTRL;
            case 0xA4: case 0xA5: return ALT;
            case 0x5B: case 0x5C: return META;
            default: return 0;
        }
    }
}

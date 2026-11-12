package com.limelight.foldcade;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class AppearanceTest {
    @Test public void missingReplyIsAfterglow() {
        assertSame(Appearance.AFTERGLOW, Appearance.fromReply(null));
        Appearance a = Appearance.fromReply(new HashMap<>());
        assertEquals(Appearance.AFTERGLOW.focus, a.focus);
        assertEquals(Appearance.AFTERGLOW.ringStart, a.ringStart);
        assertEquals(0.5f, a.round, 0f);
    }

    @Test public void themeValuesReplaceAfterglowAndWrongOnesDoNot() {
        Map<String, Object> reply = new HashMap<>();
        reply.put("background", 0x00101010);  // translucent: made opaque
        reply.put("surface", "#222222");      // wrong type: ignored
        reply.put("round", 0.9f);             // out of range: ignored
        reply.put("cardCornerDp", 12f);
        Appearance a = Appearance.fromReply(reply);
        assertEquals(0xFF101010, a.background);
        assertEquals(Appearance.AFTERGLOW.surface, a.surface);
        assertEquals(0.5f, a.round, 0f);
        assertEquals(12f, a.cardCornerDp, 0f);
    }

    @Test public void aFocusColourAloneColoursTheRing() {
        Map<String, Object> reply = new HashMap<>();
        reply.put("focus", 0xFFFF0000);
        Appearance a = Appearance.fromReply(reply);
        assertEquals(0xFFFF0000, a.ringStart);
        assertEquals(0xFFFF0000, a.ringEnd);
        reply.put("focusRingStart", 0xFF00FF00);
        a = Appearance.fromReply(reply);
        assertEquals(0xFF00FF00, a.ringStart);
        assertEquals(Appearance.AFTERGLOW.ringEnd, a.ringEnd);
    }
}

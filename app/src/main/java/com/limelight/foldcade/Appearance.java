package com.limelight.foldcade;

import java.util.Map;

/**
 * Android-independent: the colours and corners the companion wears. Foldcade's optional
 * {@code appearance} broker method can send the player's current theme; every value it leaves
 * out, or sends wrong, is Afterglow's, Foldcade's reference theme.
 */
public final class Appearance {
    public final int background, surface, onBackground, muted, focus, ringStart, ringEnd, attention;
    /** A small control's corner as a share of its shorter side: 0.5 is a pill, 0 is square. */
    public final float round;
    public final float cardCornerDp;

    public static final Appearance AFTERGLOW = new Appearance(0xFF000000, 0xFF1C1C20, 0xFFF4F1EA, 0xFF9A958C,
            0xFFC8FFE4, 0xFF52B788, 0xFF3E8EC0, 0xFFFFC48A, 0.5f, 28f);

    public Appearance(int background, int surface, int onBackground, int muted, int focus,
                      int ringStart, int ringEnd, int attention, float round, float cardCornerDp) {
        this.background = background;
        this.surface = surface;
        this.onBackground = onBackground;
        this.muted = muted;
        this.focus = focus;
        this.ringStart = ringStart;
        this.ringEnd = ringEnd;
        this.attention = attention;
        this.round = round;
        this.cardCornerDp = cardCornerDp;
    }

    /** Reads an {@code appearance} reply. Colours are opaque ARGB ints; a translucent one is made opaque. */
    public static Appearance fromReply(Map<String, Object> reply) {
        Appearance a = AFTERGLOW;
        if (reply == null) return a;
        int focus = color(reply, "focus", a.focus);
        // A theme that sets only its focus colour gets a ring in that colour, as Foldcade draws it.
        boolean ownRing = reply.get("focusRingStart") instanceof Integer || reply.get("focusRingEnd") instanceof Integer;
        boolean ownFocus = reply.get("focus") instanceof Integer;
        int start = color(reply, "focusRingStart", ownFocus && !ownRing ? focus : a.ringStart);
        int end = color(reply, "focusRingEnd", ownFocus && !ownRing ? focus : a.ringEnd);
        return new Appearance(color(reply, "background", a.background), color(reply, "surface", a.surface),
                color(reply, "onBackground", a.onBackground), color(reply, "muted", a.muted), focus, start, end,
                color(reply, "attention", a.attention), fraction(reply, "round", 0f, 0.5f, a.round),
                fraction(reply, "cardCornerDp", 0f, 64f, a.cardCornerDp));
    }

    private static int color(Map<String, Object> reply, String key, int fallback) {
        Object value = reply.get(key);
        return value instanceof Integer ? (Integer) value | 0xFF000000 : fallback;
    }

    private static float fraction(Map<String, Object> reply, String key, float min, float max, float fallback) {
        Object value = reply.get(key);
        if (!(value instanceof Float) && !(value instanceof Double)) return fallback;
        float f = ((Number) value).floatValue();
        return f >= min && f <= max && !Float.isNaN(f) ? f : fallback;
    }
}

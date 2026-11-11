package com.limelight.foldcade;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.Game;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;

/** Reuses upstream Game without changing its standalone task or input behavior. */
public final class FoldcadeStreamActivity extends Game {
    private FoldcadeSession session;

    @Override
    protected void onCreate(Bundle state) {
        session = FoldcadeSession.find(getIntent());
        super.onCreate(state);
    }

    @Override protected boolean prepareStream() { return session != null; }
    @Override protected NvHTTP.RequestTransport getRequestTransport() { return session; }
    @Override protected void onConnectionCreated(NvConnection connection) {
        session.setStreamIntent(getIntent());
        session.attach(this, connection);
    }

    @Override protected void configureStreamPreferences(PreferenceConfiguration preferences) {
        // The companion shows stats on the bottom screen; Settings › Video › Stats on game screen
        // adds Moonlight's own overlay on top. Picture-in-picture has no place in a two-screen session.
        preferences.enablePip = false;
        matchStreamDisplay(preferences);
    }

    @Override protected String stageFailureMessage() { return session != null ? session.brokerFailure : null; }

    /**
     * Unless the player chose a resolution, stream at the top screen's native size, with the
     * matching default bitrate. See {@link CompanionSettings#resolutionChoice}.
     */
    private void matchStreamDisplay(PreferenceConfiguration preferences) {
        PreferenceStore prefs = PreferenceStore.of(this);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                !CompanionSettings.NATIVE.equals(CompanionSettings.INSTANCE.resolutionChoice(prefs))) return;
        Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
        int width = Math.min(3840, Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
        int height = Math.min(2160, Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
        if (width <= 0 || height <= 0) return;
        preferences.width = width;
        preferences.height = height;
        if (!prefs.has(CompanionSettings.BITRATE) && !prefs.has("seekbar_bitrate")) {
            preferences.bitrate = PreferenceConfiguration.getDefaultBitrate(width + "x" + height, String.valueOf(preferences.fps));
        }
    }

    /** True while delivering a key the session already routed to the game, so it is not routed twice. */
    private boolean routed;

    // The ownership key and a companion that owns the controller see input before Game does. Keys
    // reach Game two ways: StreamView hands them over in onKeyPreIme, before the activity sees
    // them, and anything it leaves comes through dispatchKeyEvent. Both are routed.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return !routed && session != null && session.interceptKey(event) || super.dispatchKeyEvent(event);
    }

    @Override public boolean handleKeyDown(KeyEvent event) {
        return !routed && session != null && session.interceptKey(event) || super.handleKeyDown(event);
    }

    @Override public boolean handleKeyUp(KeyEvent event) {
        return !routed && session != null && session.interceptKey(event) || super.handleKeyUp(event);
    }

    /** A key the companion received and the session routed to the game. */
    boolean deliverKey(KeyEvent event) {
        routed = true;
        try {
            return onKey(null, event.getKeyCode(), event);
        } finally {
            routed = false;
        }
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return session != null && session.interceptMotion(event) || super.dispatchGenericMotionEvent(event);
    }

    void releaseGameInput() { releaseControllerInput(); }

    /** The game screen's size in pixels: the reference Desktop mouse mode moves the PC's cursor against. */
    int[] screenSize() {
        Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
        return new int[] { Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()), Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()) };
    }
    void setPadMouse(boolean on) { setControllerMouse(on); }
    boolean padMouse() { return isControllerMouseActive(); }

    /** What the pill on the game's screen says. */
    enum Pill { CONTROLLER_AWAY, STICK_MOUSE }

    private TextView pill, notice;
    private Pill shownPill;

    /**
     * A small pill at the top of the game's screen: the bottom screen has the controller, or the
     * stick is the mouse. Null hides it. Only a change touches the view.
     */
    void showPill(Pill which) {
        if (which == shownPill) return;
        shownPill = which;
        if (which == null) {
            if (pill != null) pill.setVisibility(View.GONE);
            return;
        }
        if (pill == null) pill = pillView(Color.rgb(0xFF, 0xC4, 0x8A));
        if (which == Pill.CONTROLLER_AWAY) {
            pill.setText(withBackGlyph(getString(com.limelight.R.string.foldcade_controller_away), "Return"));
        } else {
            Boolean hold = PreferenceStore.of(this).bool(CompanionSettings.INSTANCE.getPadMouse().getKey());
            pill.setText(hold == null || hold ? com.limelight.R.string.foldcade_stick_mouse_on : com.limelight.R.string.foldcade_stick_mouse_on_plain);
        }
        pill.setVisibility(View.VISIBLE);
    }

    /** A notice that shows for a few seconds and fades, in place of a toast. */
    void showNotice(String text) {
        if (notice == null) notice = pillView(Color.argb(0x40, 0xF4, 0xF1, 0xEA));
        notice.setText(text);
        notice.setAlpha(1f);
        notice.setVisibility(View.VISIBLE);
        notice.animate().cancel();
        notice.animate().alpha(0f).setStartDelay(4000).setDuration(320).withEndAction(() -> notice.setVisibility(View.GONE)).start();
    }

    private TextView pillView(int edge) {
        TextView view = new TextView(this);
        view.setTextColor(Color.rgb(0xF4, 0xF1, 0xEA));
        view.setTextSize(15);
        view.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, com.limelight.R.font.nunito));
        int pad = Math.round(14 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.argb(0xD9, 0x1C, 0x1C, 0x20));
        shape.setCornerRadius(999);
        shape.setStroke(Math.max(1, pad / 7), edge);
        view.setBackground(shape);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = pad * 2;
        addContentView(view, params);
        return view;
    }

    /** [words] with the Thor's Back glyph set before [before]. */
    private CharSequence withBackGlyph(String words, String before) {
        int at = words.lastIndexOf(before);
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(words);
        android.graphics.drawable.Drawable glyph = androidx.core.content.ContextCompat.getDrawable(this, com.limelight.R.drawable.fc_ic_btn_back);
        if (glyph == null || at < 0) return text;
        int size = Math.round(20 * getResources().getDisplayMetrics().density);
        glyph.setBounds(0, 0, size, size);
        text.insert(at, "\u00A0 ");
        text.setSpan(new android.text.style.ImageSpan(glyph, android.os.Build.VERSION.SDK_INT >= 29 ? 2 /* ALIGN_CENTER */ : android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    @Override public boolean wantsPerformanceStats() { return true; }
    @Override public void onPerformanceStats(float fps, int rtt, float decode, float loss, int width, int height, int format) {
        if (session != null && !session.ended && session.isStream(this)) session.stats = new StreamStats(fps, rtt, decode, loss, width, height, format);
    }

    @Override public void toggleKeyboard() {
        if (session == null || !session.toggleKeyboard()) super.toggleKeyboard();
    }

    @Override public void onBackPressed() {
        if (session != null) session.confirmDisconnect();
    }

    @Override public void finish() {
        if (session != null) session.streamFinished(this);
        super.finish();
    }

    @Override protected void onDestroy() {
        if (session != null) session.streamFinished(this);
        super.onDestroy();
    }
}

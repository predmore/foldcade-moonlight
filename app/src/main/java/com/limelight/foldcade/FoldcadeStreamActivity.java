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
    private TextView controllerAway;

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
        preferences.enablePerfOverlay = false;
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

    // The ownership key and a companion that owns the controller see input before Game does.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return session != null && session.interceptKey(event) || super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return session != null && session.interceptMotion(event) || super.dispatchGenericMotionEvent(event);
    }

    void releaseGameInput() { releaseControllerInput(); }
    void setPadMouse(boolean on) { setControllerMouse(on); }
    boolean padMouse() { return isControllerMouseActive(); }

    /** A small pill on the game's screen while the bottom screen has the controller, so a stray press is obvious. */
    void showControllerAway(boolean away) {
        if (away && controllerAway == null) {
            controllerAway = new TextView(this);
            controllerAway.setText(com.limelight.R.string.foldcade_controller_away);
            controllerAway.setTextColor(Color.rgb(0xF4, 0xF1, 0xEA));
            controllerAway.setTextSize(15);
            controllerAway.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, com.limelight.R.font.nunito));
            int pad = Math.round(14 * getResources().getDisplayMetrics().density);
            controllerAway.setPadding(pad, pad / 2, pad, pad / 2);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(Color.argb(0xD9, 0x1C, 0x1C, 0x20));
            pill.setCornerRadius(999);
            pill.setStroke(Math.max(1, pad / 7), Color.rgb(0xFF, 0xC4, 0x8A));
            controllerAway.setBackground(pill);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.topMargin = pad * 2;
            addContentView(controllerAway, params);
        }
        if (controllerAway != null) controllerAway.setVisibility(away ? View.VISIBLE : View.GONE);
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

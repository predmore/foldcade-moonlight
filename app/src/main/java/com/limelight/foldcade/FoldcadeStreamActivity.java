package com.limelight.foldcade;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Display;

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
    @Override protected void onConnectionCreated(NvConnection connection) { session.attach(this, connection); }

    @Override protected void configureStreamPreferences(PreferenceConfiguration preferences) {
        preferences.enablePerfOverlay = false;
        preferences.enablePip = false;
        matchStreamDisplay(preferences);
    }

    @Override protected String stageFailureMessage() { return session != null ? session.brokerFailure : null; }

    /**
     * Upstream defaults to 1280x720. Unless the user chose another resolution in this app's own
     * settings, stream at the top screen's native size, with the matching default bitrate.
     */
    private void matchStreamDisplay(PreferenceConfiguration preferences) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        // Moonlight writes its XML default (1280x720) the first time its own UI opens, so that value
        // means no choice was made; any other stored resolution is the user's and is kept.
        String chosen = prefs.getString("list_resolution", "1280x720");
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || !"1280x720".equals(chosen)) return;
        Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
        int width = Math.min(3840, Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
        int height = Math.min(2160, Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
        if (width <= 0 || height <= 0) return;
        preferences.width = width;
        preferences.height = height;
        if (!prefs.contains("seekbar_bitrate_kbps") && !prefs.contains("seekbar_bitrate")) {
            preferences.bitrate = PreferenceConfiguration.getDefaultBitrate(width + "x" + height, String.valueOf(preferences.fps));
        }
    }

    @Override public boolean wantsPerformanceStats() { return true; }
    @Override public void onPerformanceStats(float fps, int rtt, float decode, float loss, int width, int height, int format) {
        if (session != null && !session.ended) session.stats = new StreamStats(fps, rtt, decode, loss, width, height, format);
    }

    @Override public void toggleKeyboard() {
        if (session == null || !session.toggleKeyboard()) super.toggleKeyboard();
    }

    @Override public void onBackPressed() {
        if (session != null) session.confirmDisconnect();
    }

    @Override public void finish() {
        if (session != null) session.end();
        super.finish();
    }

    @Override protected void onDestroy() {
        if (session != null) session.end();
        super.onDestroy();
    }
}

package com.limelight.foldcade;

import android.os.Bundle;

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

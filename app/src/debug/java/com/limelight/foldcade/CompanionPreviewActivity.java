package com.limelight.foldcade;

/**
 * Debug APK only. Launch on the desired display with adb's --display option. An optional
 * {@code --es fc_state} opens a screen or state for QA: owned, end, end-owned, settings,
 * settings-owned (with {@code --es fc_category video|audio|controls|trackpad}), typing, detail,
 * message, restart, or fresh.
 */
public final class CompanionPreviewActivity extends CompanionActivity {
    @Override protected FoldcadeSession obtainSession() {
        if (FoldcadeSession.current() != null) return null;
        return FoldcadeSession.simulated(getWindowManager().getDefaultDisplay().getDisplayId());
    }

    @Override protected String previewState() { return getIntent().getStringExtra("fc_state"); }
}

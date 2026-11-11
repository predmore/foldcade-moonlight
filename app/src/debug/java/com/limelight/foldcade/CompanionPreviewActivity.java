package com.limelight.foldcade;

/** Debug APK only. Launch on the desired display with adb's --display option. */
public final class CompanionPreviewActivity extends CompanionActivity {
    @Override protected FoldcadeSession obtainSession() {
        if (FoldcadeSession.current() != null) return null;
        return FoldcadeSession.simulated(getWindowManager().getDefaultDisplay().getDisplayId());
    }
}

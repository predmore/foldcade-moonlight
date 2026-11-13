package com.limelight.foldcade;

/**
 * Debug APK only. Launch on the desired display with adb's --display option. An optional
 * {@code --es fc_state} opens a screen or state for QA: owned, end, end-owned, settings,
 * settings-owned (with {@code --es fc_category video|audio|controls|trackpad}), typing, detail,
 * message, restart, or fresh. {@code --ei fc_battery 7} (with {@code --ez fc_charging true}) stands
 * in for the battery, and {@code --es fc_time 9:59} for the clock, so QA can show the header's
 * low, charging and one-digit-hour states without draining the device or changing its time.
 */
public final class CompanionPreviewActivity extends CompanionActivity {
    @Override protected FoldcadeSession obtainSession() {
        if (FoldcadeSession.current() != null) return null;
        return FoldcadeSession.simulated(getWindowManager().getDefaultDisplay().getDisplayId());
    }

    @Override protected String previewState() { return getIntent().getStringExtra("fc_state"); }

    @Override protected DeviceStatus.Battery previewBattery() {
        int percent = getIntent().getIntExtra("fc_battery", -1);
        return percent < 0 ? null : new DeviceStatus.Battery(percent, getIntent().getBooleanExtra("fc_charging", false));
    }

    @Override protected Long previewTime() {
        String time = getIntent().getStringExtra("fc_time");
        if (time == null) return null;
        String[] parts = time.split(":");
        java.util.Calendar at = java.util.Calendar.getInstance();
        at.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
        at.set(java.util.Calendar.MINUTE, Integer.parseInt(parts[1]));
        return at.getTimeInMillis();
    }
}

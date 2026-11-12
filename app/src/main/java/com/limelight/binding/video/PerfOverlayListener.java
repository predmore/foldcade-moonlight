package com.limelight.binding.video;

public interface PerfOverlayListener {
    void onPerfUpdate(final String text);

    default boolean wantsPerformanceStats() { return false; }

    default void onPerformanceStats(float renderedFps, int rttMs, float decodeMs,
                                    float lossPercent, int width, int height, int videoFormat) { }
}

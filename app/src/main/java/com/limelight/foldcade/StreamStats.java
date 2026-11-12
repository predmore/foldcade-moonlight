package com.limelight.foldcade;

import com.limelight.nvstream.jni.MoonBridge;

import java.util.Locale;

/** Immutable snapshot from the decoder's two-window performance counters. */
public final class StreamStats {
    public final float fps, decodeMs, lossPercent;
    public final int rttMs, width, height;
    public final String codec;

    public StreamStats(float fps, int rttMs, float decodeMs, float lossPercent,
                       int width, int height, int format) {
        this.fps = fps;
        this.rttMs = rttMs;
        this.decodeMs = decodeMs;
        this.lossPercent = lossPercent;
        this.width = width;
        this.height = height;
        codec = (format & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0 ? "H.264" :
                (format & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 ? "HEVC" :
                (format & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 ? "AV1" : "—";
    }

    public String display() {
        return String.format(Locale.getDefault(), "%.0f fps · RTT %d ms · Decode %.1f ms · Loss %.1f%% · %d×%d %s",
                fps, rttMs, decodeMs, lossPercent, width, height, codec);
    }
}

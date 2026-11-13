package com.limelight.foldcade

import java.util.Locale

/**
 * Android-independent: how the stream is doing, for the stats pill's dot and words.
 * Frame rate is shown but never judged: a host sends only changed frames, so a still desktop
 * runs slow without anything being wrong.
 */
object StreamHealth {
    enum class Cause { Loss, Ping, Decode }

    /** The worst problem and how bad it is: 0 none, 1 slipping, 2 struggling. */
    data class Verdict(val level: Int, val cause: Cause?)

    fun verdict(stats: StreamStats, targetFps: Int): Verdict {
        val budget = 1000f / targetFps.coerceAtLeast(1)
        val loss = when { stats.lossPercent >= 5f -> 2; stats.lossPercent >= 1f -> 1; else -> 0 }
        val ping = when { stats.rttMs >= 80 -> 2; stats.rttMs >= 35 -> 1; else -> 0 }
        val decode = when { stats.decodeMs >= budget -> 2; stats.decodeMs >= budget * 0.6f -> 1; else -> 0 }
        val level = maxOf(loss, ping, decode)
        // Loss is the cause a player can most often fix (Wi-Fi), so it names first at equal severity.
        val cause = when {
            level == 0 -> null
            loss == level -> Cause.Loss
            ping == level -> Cause.Ping
            else -> Cause.Decode
        }
        return Verdict(level, cause)
    }

    /**
     * The pill's words: fps and ping while all is well, and fps and the problem once there is
     * one, so the dot's colour explains itself: "21 fps · 6% loss", "60 fps · 19 ms decode".
     */
    fun pillText(stats: StreamStats, targetFps: Int, locale: Locale): String {
        val fps = String.format(locale, "%.0f fps", stats.fps)
        return when (verdict(stats, targetFps).cause) {
            Cause.Loss -> fps + " · " + String.format(locale, "%.0f%% loss", stats.lossPercent)
            Cause.Decode -> fps + " · " + String.format(locale, "%.0f ms decode", stats.decodeMs)
            Cause.Ping -> fps + " · " + String.format(locale, "%d ms ping", stats.rttMs)
            null -> fps + " · " + String.format(locale, "%d ms", stats.rttMs)
        }
    }

    /**
     * The pill while the detail metrics are open: fps and ping, whatever the verdict. The metrics
     * beside it show decode and loss, so the pill no longer repeats them; ping is not among the
     * metrics, so it stays here. The second value is where the ping begins, for colouring it as
     * the cause.
     */
    fun detailPillText(stats: StreamStats, locale: Locale): Pair<String, Int> {
        val fps = String.format(locale, "%.0f fps", stats.fps) + " · "
        return (fps + String.format(locale, "%d ms", stats.rttMs)) to fps.length
    }
}

/*
 * The battery reading is adapted from Foldcade's Status.kt, which is MIT licensed: see
 * assets/licenses/foldcade-MIT.txt. The rest is GPL-3.0 with this app.
 */
package com.limelight.foldcade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Thor's own clock and battery, for the companion's header: the stream fills the top screen
 * and the companion hides Android's status bar, so this is the only place the player sees them.
 */
data class DeviceStatus(
    /** "12:41" or "21:05": never a day period, as Android's own status bar. */
    val clock: String,
    /** The clock at a two-digit hour, which the header reserves so nothing moves at 9:59 → 10:00. */
    val widestClock: String,
    /** What TalkBack reads: the full time, day period included, and the battery. */
    val spoken: String,
    /** Null when the device reports no battery. */
    val battery: Battery?,
) {
    data class Battery(val percent: Int, val charging: Boolean) {
        /** Foldcade's ten steps, as its battery icons draw them. */
        val step: Int get() = Rules.step(percent)
        /** At 20% and below, unplugged, the percent joins the glyph. */
        val low: Boolean get() = !charging && percent <= Rules.LOW
        /** Below 15%, unplugged, the glyph's fill turns rose (Foldcade's 10% step and below). */
        val critical: Boolean get() = !charging && percent < Rules.CRITICAL
    }

    /** Pure rules, kept apart from Android so they can be tested. */
    object Rules {
        const val LOW = 20
        const val CRITICAL = 15

        /** Rounded to the nearest ten, as Foldcade buckets its icons. */
        fun step(percent: Int): Int = ((percent.coerceIn(0, 100) + 5) / 10 * 10).coerceAtMost(100)

        /**
         * The width of the glyph's fill, in its 24-unit box: Foldcade's art runs 13.2 units at
         * 100%, with a 1.6-unit sliver at 10% so the last step still shows. Empty at 0.
         */
        fun fillUnits(step: Int): Float = if (step <= 0) 0f else maxOf(1.6f, 13.2f * step / 100f)

        /**
         * [pattern] without its day-period field ("a", "b" or "B") and the space beside it, as
         * SystemUI's clock trims it: "h:mm a" → "h:mm", "a h:mm" → "h:mm", "ah:mm" → "h:mm".
         * Quoted literals are kept.
         */
        fun withoutDayPeriod(pattern: String): String {
            val out = StringBuilder()
            var quoted = false
            for (c in pattern) {
                if (c == '\'') quoted = !quoted
                if (!quoted && (c == 'a' || c == 'b' || c == 'B')) continue
                out.append(c)
            }
            // The separators the day period stood between: plain, no-break and narrow no-break spaces.
            return out.toString().trim(' ', '\u00A0', '\u202F').replace(Regex("[ \u00A0\u202F]{2,}"), " ")
        }

        /** WCAG contrast between two opaque ARGB colours, 1 to 21. */
        fun contrast(a: Int, b: Int): Double {
            val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }

        private fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val v = ((argb shr shift) and 0xFF) / 255.0
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }

        /** "12:41 PM, battery 82 percent" / "…, charging" / "…, battery low, 12 percent". */
        fun spoken(time: String, battery: Battery?): String = when {
            battery == null -> time
            battery.charging -> "$time, battery ${battery.percent} percent, charging"
            battery.low -> "$time, battery low, ${battery.percent} percent"
            else -> "$time, battery ${battery.percent} percent"
        }
    }

    companion object {
        /** Reads the clock and the battery now. [override] stands in for the battery in the debug preview. */
        fun read(context: Context, override: Battery? = null, now: Long = System.currentTimeMillis()): DeviceStatus {
            val locale = Locale.getDefault()
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val skeleton = if (is24) "Hm" else "hm"
            val format = SimpleDateFormat(Rules.withoutDayPeriod(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)), locale)
            // 10:00 or 20:00: a two-digit hour in either clock. Nunito's digits are all one width.
            val widest = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, if (is24) 20 else 10); set(java.util.Calendar.MINUTE, 0) }
            val battery = override ?: battery(context)
            return DeviceStatus(
                clock = format.format(Date(now)),
                widestClock = format.format(widest.time),
                spoken = Rules.spoken(android.text.format.DateFormat.getTimeFormat(context).format(Date(now)), battery),
                battery = battery,
            )
        }

        private fun battery(context: Context): Battery? {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            if (!sticky.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return null
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return null
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            // Full on the charger counts as charging, as Foldcade reads it.
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return Battery(level * 100 / scale, charging)
        }
    }

    /**
     * Calls [changed] whenever the clock or the battery may read differently: each minute, a set
     * clock, a new time zone, the 12/24-hour setting, the locale, and every battery change.
     * Registered only between [start] and [stop], while the companion is visible.
     */
    class Watcher(private val context: Context, private val changed: () -> Unit) {
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changed()
        }
        private var registered = false

        fun start() {
            if (registered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                // A set clock or the 12/24-hour setting.
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            changed()
        }

        fun stop() {
            if (!registered) return
            context.unregisterReceiver(receiver)
            registered = false
        }
    }
}

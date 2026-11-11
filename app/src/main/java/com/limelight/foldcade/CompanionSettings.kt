package com.limelight.foldcade

/**
 * The companion's settings. Every stream setting is one of Moonlight's own preferences, under
 * Moonlight's key, so standalone Moonlight and Foldcade mode share them. Android-independent:
 * [Store] wraps SharedPreferences on the device and a map in tests.
 */
object CompanionSettings {
    interface Store {
        fun has(key: String): Boolean
        fun string(key: String): String?
        fun integer(key: String): Int?
        fun bool(key: String): Boolean?
        fun put(key: String, value: Any?)
    }

    /** Foldcade mode's own key: `native` streams at the top screen's size, `chosen` uses Moonlight's resolution. */
    const val RESOLUTION_MODE = "foldcade_stream_resolution"
    const val RESOLUTION = "list_resolution"
    const val BITRATE = "seekbar_bitrate_kbps"
    const val FPS = "list_fps"
    const val PAD_SPEED = "foldcade_pad_speed"
    const val NATURAL_SCROLL = "foldcade_natural_scroll"
    const val NATIVE = "native"

    data class Option(val value: String, val label: String)

    sealed class Setting(val key: String, val label: String, val restarts: Boolean) {
        /** A value from a list. Left and right step through it; A steps forward. */
        class Choice(key: String, label: String, val options: List<Option>, val default: String, restarts: Boolean = true) :
            Setting(key, label, restarts)

        class Toggle(key: String, label: String, val default: Boolean, restarts: Boolean = true) : Setting(key, label, restarts)

        /**
         * Whole-number stops. With [auto], a missing key is Auto: Moonlight picks the value, and the
         * row steps from the value Moonlight would pick.
         */
        class Steps(key: String, label: String, val stops: List<Int>, val default: Int?, val unit: String,
                    restarts: Boolean = true, val auto: Boolean = false) : Setting(key, label, restarts)
    }

    data class Category(val id: String, val label: String, val settings: List<Setting>)

    private val resolutions = listOf(
        Option(NATIVE, "Top screen"),
        Option("1280x720", "720p"),
        Option("1920x1080", "1080p"),
        Option("2560x1440", "1440p"),
        Option("3840x2160", "4K"),
    )

    val resolution = Setting.Choice(RESOLUTION_MODE, "Resolution", resolutions, NATIVE)
    val fps = Setting.Choice(FPS, "Frame rate", listOf("30", "60", "90", "120").map { Option(it, "$it fps") }, "60")
    val bitrate = Setting.Steps(BITRATE, "Bitrate", (1..30).map { it * 5000 }, null, "Mbps", auto = true)
    val codec = Setting.Choice("video_format", "Codec", listOf(
        Option("auto", "Auto"), Option("neverh265", "H.264"), Option("forceh265", "HEVC"), Option("forceav1", "AV1")), "auto")
    val hdr = Setting.Toggle("checkbox_enable_hdr", "HDR", false)
    val pacing = Setting.Choice("frame_pacing", "Frame pacing", listOf(
        Option("latency", "Fastest"), Option("balanced", "Balanced"),
        Option("cap-fps", "Capped"), Option("smoothness", "Smoothest")), "latency")
    val sops = Setting.Toggle("checkbox_enable_sops", "Optimize game settings", true)
    val stretch = Setting.Toggle("checkbox_stretch_video", "Fill screen", false)
    val fullRange = Setting.Toggle("checkbox_full_range", "Full color range", false)
    val overlay = Setting.Toggle("checkbox_enable_perf_overlay", "Stats on game screen", false)
    val speakers = Setting.Choice("list_audio_config", "Speakers", listOf(
        Option("2", "Stereo"), Option("51", "5.1"), Option("71", "7.1")), "2")
    val hostAudio = Setting.Toggle("checkbox_host_audio", "Play on PC too", false)
    val swapButtons = Setting.Toggle("checkbox_flip_face_buttons", "Swap A/B and X/Y", false)
    val deadzone = Setting.Steps("seekbar_deadzone", "Stick deadzone", (0..20).toList(), 7, "%")
    val padMouse = Setting.Toggle("checkbox_mouse_emulation", "Hold Start for stick mouse", true)
    /** The Thor's controller has no motion sensors; Moonlight then uses the Thor's own. */
    val motion = Setting.Toggle("checkbox_gamepad_motion_fallback", "Motion controls", false)
    /** The Thor's controller has no rumble motor; Moonlight then rumbles the Thor itself. */
    val rumble = Setting.Toggle("checkbox_vibrate_fallback", "Rumble", false)
    val desktopMouse = Setting.Toggle("checkbox_absolute_mouse_mode", "Desktop mouse mode", false)
    val padSpeed = Setting.Steps(PAD_SPEED, "Pointer speed", (5..20).map { it * 10 }, 100, "%", restarts = false)
    val naturalScroll = Setting.Toggle(NATURAL_SCROLL, "Natural scrolling", true, restarts = false)

    val categories = listOf(
        Category("video", "Video", listOf(resolution, fps, bitrate, codec, hdr, pacing, stretch, fullRange, overlay, sops)),
        Category("audio", "Audio", listOf(speakers, hostAudio)),
        Category("controls", "Controls", listOf(swapButtons, deadzone, padMouse, motion, rumble)),
        Category("trackpad", "Trackpad", listOf(padSpeed, naturalScroll, desktopMouse)),
    )

    private val restartSettings = categories.flatMap { it.settings }.filter { it.restarts }

    fun choice(store: Store, setting: Setting.Choice): String =
        if (setting === resolution) resolutionChoice(store) else store.string(setting.key)?.takeIf { v -> setting.options.any { it.value == v } } ?: setting.default

    fun toggle(store: Store, setting: Setting.Toggle): Boolean = store.bool(setting.key) ?: setting.default

    /** The stop the stored value sits on, rounded to the nearest; null is Auto. */
    fun steps(store: Store, setting: Setting.Steps): Int? {
        val stored = store.integer(setting.key) ?: return setting.default
        return setting.stops.minByOrNull { kotlin.math.abs(it - stored) }
    }

    /**
     * Foldcade mode streams at the top screen's size unless the player chose a resolution: here,
     * or in Moonlight's own settings before this key existed. Moonlight writes its 720p default
     * the first time its UI opens, so that value alone is not a choice.
     */
    fun resolutionChoice(store: Store): String {
        val listed = store.string(RESOLUTION)
        return when (store.string(RESOLUTION_MODE)) {
            NATIVE -> NATIVE
            "chosen" -> listed?.takeIf { v -> resolutions.any { it.value == v } } ?: listed ?: NATIVE
            else -> if (listed == null || listed == "1280x720") NATIVE else listed
        }
    }

    fun setChoice(store: Store, setting: Setting.Choice, value: String) {
        if (setting === resolution) {
            if (value == NATIVE) {
                store.put(RESOLUTION_MODE, NATIVE)
            } else {
                store.put(RESOLUTION, value)
                store.put(RESOLUTION_MODE, "chosen")
            }
        } else {
            store.put(setting.key, value)
        }
    }

    fun setSteps(store: Store, setting: Setting.Steps, value: Int?) = store.put(setting.key, value)

    fun setToggle(store: Store, setting: Setting.Toggle, on: Boolean) = store.put(setting.key, on)

    /** One step through a choice's options, wrapping, as A steps it. [by] is -1 or 1. */
    fun stepChoice(setting: Setting.Choice, current: String, by: Int): String {
        val index = setting.options.indexOfFirst { it.value == current }.coerceAtLeast(0)
        return setting.options[Math.floorMod(index + by, setting.options.size)].value
    }

    /** One step left or right, stopping at either end: null there, so the D-pad can move on instead. */
    fun stepChoiceBounded(setting: Setting.Choice, current: String, by: Int): String? {
        val index = setting.options.indexOfFirst { it.value == current }.coerceAtLeast(0)
        return setting.options.getOrNull(index + by)?.value
    }

    /** Whether a choice can step further toward [by], for the chevron on that side. */
    fun canStep(setting: Setting.Choice, current: String, by: Int): Boolean = stepChoiceBounded(setting, current, by) != null

    /**
     * One stop along from where the row reads: the stored stop, or on Auto the value Moonlight
     * would pick ([effective]), so Auto at 20 Mbps steps to 25 or 15. False at either end.
     */
    fun stepSteps(setting: Setting.Steps, current: Int?, by: Int, effective: Int? = null): Pair<Boolean, Int?> {
        val base = current ?: effective ?: setting.default ?: setting.stops.first()
        val next = if (by > 0) setting.stops.firstOrNull { it > base } else setting.stops.lastOrNull { it < base }
        return if (next == null) false to current else true to next
    }

    /** Where the slider's thumb sits, 0 to 1: the stored stop, or the value Auto stands for. */
    fun fraction(setting: Setting.Steps, current: Int?, effective: Int? = null): Float {
        val value = (current ?: effective ?: setting.default ?: setting.stops.first()).toFloat()
        val first = setting.stops.first().toFloat()
        val last = setting.stops.last().toFloat()
        if (last <= first) return 0f
        // Stops are evenly spaced, so the value's place between the ends is its place on the track.
        return ((value - first) / (last - first)).coerceIn(0f, 1f)
    }

    /** Everything a new stream would read, as text, to tell when the running one is out of date. */
    fun restartSnapshot(store: Store): Map<String, String> = restartSettings.associate { setting ->
        setting.key to when (setting) {
            is Setting.Choice -> choice(store, setting)
            is Setting.Toggle -> toggle(store, setting).toString()
            is Setting.Steps -> steps(store, setting).toString()
        }
    }

    /** The settings changed since [applied], by label, in the order the page lists them. */
    fun pending(applied: Map<String, String>?, now: Map<String, String>): List<String> {
        if (applied == null) return emptyList()
        return restartSettings.filter { applied[it.key] != now[it.key] }.map { it.label }
    }

    /** What a Steps value reads as. Auto leads with the value Moonlight would pick: "20 Mbps · Auto". */
    fun stepsLabel(setting: Setting.Steps, value: Int?, auto: Int? = null): String = when {
        value == null && auto != null -> "${formatSteps(setting, auto)} · Auto"
        value == null -> "Auto"
        else -> formatSteps(setting, value)
    }

    private fun formatSteps(setting: Setting.Steps, value: Int): String =
        if (setting.unit == "Mbps") "${(value + 500) / 1000} Mbps" else "$value${setting.unit}"

    fun optionLabel(setting: Setting.Choice, value: String): String =
        setting.options.firstOrNull { it.value == value }?.label ?: value.replace('x', '×')
}

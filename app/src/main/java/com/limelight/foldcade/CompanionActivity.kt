package com.limelight.foldcade

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import com.limelight.foldcade.ui.CompanionActions
import com.limelight.foldcade.ui.CompanionRoot
import com.limelight.foldcade.ui.CompanionUi
import com.limelight.foldcade.ui.Fold
import com.limelight.foldcade.ui.Layer
import com.limelight.foldcade.ui.LocalFold
import com.limelight.foldcade.ui.NavHost
import com.limelight.foldcade.ui.Sheet
import com.limelight.foldcade.ui.firstRow
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.abs
import kotlin.math.sin

/**
 * The bottom screen. While the game owns the controller, every key and stick motion this
 * window receives goes to the stream, as before. The player hands the controller here with the
 * Thor's Back button (see [FoldcadeSession.isOwnershipKey]); then the D-pad, the left stick, A, B,
 * L1, R1 and Start drive these controls, and Back returns it. Touch works in either state and
 * never moves the controller.
 */
open class CompanionActivity : ComponentActivity(), FoldcadeSession.CompanionView, CompanionActions {
    private val handler = Handler(Looper.getMainLooper())
    private var session: FoldcadeSession? = null
    private lateinit var ui: CompanionUi
    private val nav = NavHost()
    private lateinit var editor: RemoteKeyboardView
    private var showImeOnFocus = false
    private var imeWasVisible = false
    override lateinit var gesture: TouchpadGesture
    override val store: CompanionSettings.Store by lazy { PreferenceStore.of(this) }
    override var topScreen: String = "1920×1080"

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (s.ended || isFinishing) return
            if (s.simulated && !s.restarting) {
                val offset = sin(SystemClock.uptimeMillis() / 2000.0).toFloat()
                s.stats = StreamStats(60 + offset / 4, 4, 2.1f + offset / 10, 0f, 1920, 1080, MoonBridge.VIDEO_FORMAT_H265)
            }
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    protected open fun obtainSession(): FoldcadeSession? = FoldcadeSession.find(intent)

    /** The debug preview opens a given screen or state; release builds never do. */
    protected open fun previewState(): String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = obtainSession()
        if (s == null) { finish(); return }
        session = s
        s.attach(this)
        ui = CompanionUi(s.title, s.pcName)
        ui.hintsSeen = getPreferences(MODE_PRIVATE).getBoolean("touchpadHintSeen", false)
        // The window stays focusable. Launching it makes the bottom display the focused one, and a
        // non-focusable window there would leave controller input with no window at all. Every key
        // and controller motion it receives is forwarded to the stream unless the companion owns it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = s.appearance.background
        window.navigationBarColor = s.appearance.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        topScreen = topScreenSize(s)

        gesture = TouchpadGesture(object : TouchpadGesture.Output {
            override fun move(x: Short, y: Short) = s.move(x, y)
            override fun button(right: Boolean, down: Boolean) = s.button(if (right) MouseButtonPacket.BUTTON_RIGHT else MouseButtonPacket.BUTTON_LEFT, down)
            override fun scroll(vertical: Short, horizontal: Short) = s.scroll(vertical, horizontal)
        }, ViewConfiguration.get(this).scaledTouchSlop / resources.displayMetrics.density)
        applyTrackpadSettings()

        val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val fold = Fold(s.appearance, scale)
        nav.backs[Layer.DECK] = { takeController(false) }
        nav.backs[Layer.END] = { closeSheet() }
        nav.backs[Layer.SETTINGS] = {
            if (nav.focused?.startsWith("set:") == true || nav.focused == "apply") nav.focus("cat:${ui.category}") else closeSheet()
        }
        nav.backs[Layer.MESSAGE] = { dismissMessage() }

        val root = FrameLayout(this)
        root.addView(ComposeView(this).apply {
            setContent { CompositionLocalProvider(LocalFold provides fold) { CompanionRoot(ui, nav, this@CompanionActivity) } }
        }, FrameLayout.LayoutParams(-1, -1))
        // The Thor's Back hands the controller over even while Android's keyboard is up, which
        // would otherwise take it as "close the keyboard".
        editor = RemoteKeyboardView(this, s, { setTyping(false) }) { event ->
            FoldcadeSession.isOwnershipKey(event) && handleKey(event)
        }
        root.addView(editor, FrameLayout.LayoutParams(1, 1))
        keySink = KeySink(this)
        root.addView(keySink, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        keySink.requestFocus()

        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                if (ui.typing && ui.keyboard == null) {
                    if (insets.isVisible(WindowInsets.Type.ime())) imeWasVisible = true
                    else if (imeWasVisible) handler.post { setTyping(false) }
                }
                view.onApplyWindowInsets(insets)
            }
        }
        refresh()
        handler.post(tick)
        previewState()?.let { handler.postDelayed({ applyPreview(it) }, 300) }
    }

    private fun topScreenSize(s: FoldcadeSession): String {
        val manager = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = manager.getDisplay(if (s.topDisplay >= 0) s.topDisplay else android.view.Display.DEFAULT_DISPLAY) ?: return topScreen
        val mode = display.mode
        return "${maxOf(mode.physicalWidth, mode.physicalHeight).coerceAtMost(3840)}×${minOf(mode.physicalWidth, mode.physicalHeight).coerceAtMost(2160)}"
    }

    /** Copies the session's state into the UI. Unchanged values do not recompose anything. */
    private fun refresh() {
        val s = session ?: return
        ui.stats = s.stats
        ui.restarting = s.restarting
        ui.quitPending = s.quitPending()
        ui.padMouse = s.padMouse()
        ui.muted = s.isMuted(this)
        val pending = s.pendingSettings(this)
        if (pending != ui.pending) ui.pending = pending
        ui.targetFps = CompanionSettings.choice(store, CompanionSettings.fps).toIntOrNull() ?: 60
        val owned = s.companionOwnsInput()
        if (owned != ui.owned) {
            ui.owned = owned
            nav.owned = owned
            nav.holding = null
            if (owned && nav.focused == null) nav.focused = if (ui.sheet == Sheet.None) "key:esc" else nav.focused
            // Typing follows the controller: taken here, Android's keyboard gives way to the
            // controller keyboard; handed back to the game, the controller keyboard closes.
            if (ui.typing) {
                if (owned && ui.keyboard == null) useControllerKeyboard()
                else if (!owned && ui.keyboard != null) setTyping(false)
            }
            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM.takeIf { Build.VERSION.SDK_INT >= 30 } ?: HapticFeedbackConstants.VIRTUAL_KEY)
            if (!owned) stickDirection = null
        }
    }

    override fun onSessionChanged() = refresh()

    // ---- Keys ----

    // AndroidX inherits this public Activity callback through its restricted core shim, as
    // Foldcade's PanelKeyActivity notes; keys must be routed before any view sees them.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleKey(event) || super.dispatchKeyEvent(event)

    /** Routes a key: to the companion, to the game, or (false) on to Android for system keys. */
    private fun handleKey(event: KeyEvent): Boolean {
        val s = session ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !isController(event)) {
            if (event.action == KeyEvent.ACTION_UP) systemBack()
            return true
        }
        if (s.interceptKey(event)) return true
        if (s.key(event)) return true
        // Only system keys fall through; nothing reaches Compose's own focus handling.
        return !isSystemKey(event.keyCode)
    }

    /**
     * Holds the window's focus and takes keys before the IME stage. Android otherwise eats the
     * first D-pad press after a touch to leave touch mode, before any activity code sees it, so
     * that press would reach neither the game nor these controls.
     */
    private inner class KeySink(context: android.content.Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean = handleKey(event) || super.dispatchKeyEventPreIme(event)
    }

    private lateinit var keySink: View

    private fun isSystemKey(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
        code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER

    private fun isController(event: KeyEvent) =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)

    /** Android's own Back, from a touch gesture: close what is open, else offer to end the session. */
    private fun systemBack() {
        when {
            ui.typing -> setTyping(false)
            ui.message != null -> dismissMessage()
            ui.sheet != Sheet.None -> closeSheet()
            else -> confirmDisconnect()
        }
    }

    private var lastMove: CompanionNav.Direction? = null
    private var lastMoveAt = 0L

    /** A key while the companion owns the controller. */
    override fun onNavKey(event: KeyEvent) {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (ui.typing && ui.keyboard != null) {
            val action = PcKeyboard.action(event.keyCode) ?: return
            val moves = action == PcKeyboard.Action.Up || action == PcKeyboard.Action.Down ||
                action == PcKeyboard.Action.Left || action == PcKeyboard.Action.Right
            if (down && (event.repeatCount == 0 || moves || action == PcKeyboard.Action.Erase)) keyboardAction(action)
            return
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(CompanionNav.Direction.Up, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(CompanionNav.Direction.Down, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) move(CompanionNav.Direction.Left, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) move(CompanionNav.Direction.Right, event.repeatCount > 0)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> if (event.repeatCount == 0) nav.press(down)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> if (down && event.repeatCount == 0) nav.back()
            KeyEvent.KEYCODE_BUTTON_L1 -> if (down && event.repeatCount == 0) page(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (down && event.repeatCount == 0) page(1)
            KeyEvent.KEYCODE_BUTTON_START -> if (down && event.repeatCount == 0) {
                if (ui.sheet == Sheet.Settings) closeSheet() else openSheet(Sheet.Settings)
            }
            else -> Unit
        }
    }

    /**
     * One step of focus. The Thor's D-pad can report as both keys and a hat; the second report of
     * the same step within a moment is the same press, not another.
     */
    private fun move(direction: CompanionNav.Direction, repeat: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!repeat && direction == lastMove && now - lastMoveAt < 80) return
        lastMove = direction
        lastMoveAt = now
        if (ui.typing && ui.keyboard != null) keyboardAction(PcKeyboard.Action.valueOf(direction.name)) else nav.move(direction)
    }

    /** One press on the controller keyboard: the board moves, and what it types goes to the PC. */
    private fun keyboardAction(action: PcKeyboard.Action) {
        val state = ui.keyboard ?: return
        val (next, output) = state.on(action)
        ui.keyboard = next
        deliver(output)
    }

    override fun keyboardTap(at: PcKeyboard.Focus) {
        val state = ui.keyboard ?: return
        val (next, output) = state.press(at)
        ui.keyboard = next
        deliver(output)
    }

    private fun deliver(output: PcKeyboard.Output) {
        val s = session ?: return
        when (output) {
            is PcKeyboard.Output.Text -> s.text(output.text)
            is PcKeyboard.Output.Key -> s.sendShortcut(output.shortcut)
            PcKeyboard.Output.Close -> setTyping(false)
            PcKeyboard.Output.None -> Unit
        }
    }

    /** L1 and R1 page through Settings' categories, as Foldcade's shoulders page its screens. */
    private fun page(by: Int) {
        if (ui.sheet != Sheet.Settings) return
        val categories = CompanionSettings.categories
        val index = categories.indexOfFirst { it.id == ui.category }
        val next = categories[Math.floorMod(index + by, categories.size)].id
        val onCategory = nav.focused?.startsWith("cat:") == true
        ui.category = next
        // The new page's rows compose next frame; the focus waits for its row to arrive.
        if (onCategory) nav.focus("cat:$next") else (ui.lastRow[next] ?: firstRow(next))?.let { nav.focus(it) }
    }

    // ---- Sticks and the hat ----

    private var stickDirection: CompanionNav.Direction? = null
    private val stickRepeat = object : Runnable {
        override fun run() {
            val direction = stickDirection ?: return
            move(direction, true)
            handler.postDelayed(this, 110)
        }
    }

    override fun onNavMotion(event: MotionEvent) {
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val held = stickDirection
        // Hysteresis: a direction starts past 0.6 and lets go under 0.35.
        val enter = 0.6f
        val keep = 0.35f
        val direction = when {
            hatX <= -0.5f -> CompanionNav.Direction.Left
            hatX >= 0.5f -> CompanionNav.Direction.Right
            hatY <= -0.5f -> CompanionNav.Direction.Up
            hatY >= 0.5f -> CompanionNav.Direction.Down
            held != null && axisFor(held, x, y) > keep -> held
            abs(x) >= enter && abs(x) >= abs(y) -> if (x < 0) CompanionNav.Direction.Left else CompanionNav.Direction.Right
            abs(y) >= enter -> if (y < 0) CompanionNav.Direction.Up else CompanionNav.Direction.Down
            else -> null
        }
        if (direction == held) return
        handler.removeCallbacks(stickRepeat)
        stickDirection = direction
        if (direction != null) {
            move(direction, false)
            handler.postDelayed(stickRepeat, 380)
        }
    }

    private fun axisFor(direction: CompanionNav.Direction, x: Float, y: Float) = when (direction) {
        CompanionNav.Direction.Left -> -x
        CompanionNav.Direction.Right -> x
        CompanionNav.Direction.Up -> -y
        CompanionNav.Direction.Down -> y
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val s = session ?: return super.dispatchGenericMotionEvent(event)
        // Controller sticks and triggers go to the stream, or to these controls, before any view can take them.
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            if (s.interceptMotion(event)) return true
            if (s.motion(event)) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        session?.motion(event) == true || super.onGenericMotionEvent(event)

    // ---- Actions ----

    override fun send(shortcut: PcShortcut) { session?.sendShortcut(shortcut) }

    override fun button(right: Boolean, down: Boolean) {
        session?.button(if (right) MouseButtonPacket.BUTTON_RIGHT else MouseButtonPacket.BUTTON_LEFT, down)
    }

    override fun setTyping(open: Boolean) {
        if (ui.typing == open) return
        ui.typing = open
        imeWasVisible = false
        val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (open && nav.owned) {
            // The controller is here, so the controller keyboard types; Android's stays down.
            useControllerKeyboard()
        } else if (open) {
            ui.keyboard = null
            showImeOnFocus = true
            editor.requestFocus()
            editor.post { if (ui.typing && ui.keyboard == null && hasWindowFocus()) showIme() }
        } else {
            ui.keyboard = null
            showImeOnFocus = false
            ime.hideSoftInputFromWindow(editor.windowToken, 0)
            editor.clearFocus()
            keySink.requestFocus()
            if (nav.owned) nav.focus("key:type")
        }
    }

    /** Swaps Android's keyboard for the controller keyboard, keeping typing open. */
    private fun useControllerKeyboard() {
        showImeOnFocus = false
        imeWasVisible = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
        keySink.requestFocus()
        ui.keyboard = PcKeyboard.State()
    }

    private fun showIme() {
        showImeOnFocus = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && (!ui.typing || ui.keyboard != null) && !keySink.isFocused) keySink.requestFocus()
        if (hasFocus && ui.typing && ui.keyboard == null && showImeOnFocus) editor.post { showIme() }
    }

    override fun toggleKeyboard() = setTyping(!ui.typing)

    override fun setMuted(muted: Boolean) {
        session?.setMuted(this, muted)
        refresh()
    }

    override fun setPadMouse(on: Boolean) { session?.setPadMouse(on) }

    override fun takeController(take: Boolean) { session?.setCompanionOwnsInput(take) }

    override fun openSheet(sheet: Sheet) {
        if (ui.sheet == sheet) return
        if (ui.typing) setTyping(false)
        ui.sheet = sheet
        when (sheet) {
            Sheet.End -> nav.openLayer(Layer.END, "end:disconnect")
            Sheet.Settings -> nav.openLayer(Layer.SETTINGS, "cat:${ui.category}")
            Sheet.None -> Unit
        }
    }

    override fun closeSheet() {
        if (ui.sheet == Sheet.None) return
        ui.sheet = Sheet.None
        nav.closeLayer(Layer.DECK)
    }

    override fun disconnect() { session?.end() }

    override fun quit() { session?.quitGame() }

    override fun restart() {
        val s = session ?: return
        closeSheet()
        s.restartStream()
        refresh()
    }

    override fun settingChanged() {
        ui.settingsVersion++
        applyTrackpadSettings()
        refresh()
    }

    private fun applyTrackpadSettings() {
        gesture.setSpeed((CompanionSettings.steps(store, CompanionSettings.padSpeed) ?: 100) / 100f)
        gesture.setNaturalScroll(CompanionSettings.toggle(store, CompanionSettings.naturalScroll))
    }

    override fun hintsSeen() {
        if (ui.hintsSeen) return
        ui.hintsSeen = true
        getPreferences(MODE_PRIVATE).edit().putBoolean("touchpadHintSeen", true).apply()
    }

    override fun confirmDisconnect() = openSheet(Sheet.End)

    override fun showMessage(title: String, message: String) {
        if (isFinishing) return
        ui.message = title to message
        nav.openLayer(Layer.MESSAGE, "message:ok")
    }

    override fun dismissMessage() {
        if (ui.message == null) return
        ui.message = null
        nav.closeLayer(if (ui.sheet == Sheet.End) Layer.END else if (ui.sheet == Sheet.Settings) Layer.SETTINGS else Layer.DECK)
    }

    /** Debug preview only: open a screen or state so QA can capture it. */
    private fun applyPreview(state: String) {
        val s = session ?: return
        when (state) {
            "owned" -> s.setCompanionOwnsInput(true)
            "end" -> openSheet(Sheet.End)
            "end-owned" -> { s.setCompanionOwnsInput(true); openSheet(Sheet.End) }
            "settings", "settings-owned" -> {
                intent.getStringExtra("fc_category")?.let { ui.category = it }
                if (state == "settings-owned") s.setCompanionOwnsInput(true)
                openSheet(Sheet.Settings)
                if (state == "settings-owned") handler.postDelayed({ firstRow(ui)?.let { nav.focus(it) } }, 200)
            }
            "typing" -> setTyping(true)
            "typing-owned" -> { s.setCompanionOwnsInput(true); setTyping(true) }
            "detail" -> ui.statsDetail = true
            "message" -> showMessage("Unable to quit game", "The computer did not close the game. Try again, or disconnect and leave it running.")
            "restart" -> s.restartStream()
            "fresh" -> { ui.hintsSeen = false }
        }
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::gesture.isInitialized) gesture.cancel()
        session?.end()
        super.onDestroy()
    }
}

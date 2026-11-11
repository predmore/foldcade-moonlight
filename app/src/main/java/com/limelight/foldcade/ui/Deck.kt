package com.limelight.foldcade.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.foldcade.CompanionSettings
import com.limelight.foldcade.PcShortcut
import com.limelight.foldcade.StreamStats
import com.limelight.foldcade.TouchpadGesture
import kotlinx.coroutines.delay
import com.limelight.foldcade.PcKeyboard
import com.limelight.foldcade.StreamHealth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn

enum class Sheet { None, End, Settings }

/** Layers of [NavHost]: the deck, then whatever sits over it. */
internal object Layer { const val DECK = 0; const val END = 1; const val SETTINGS = 2; const val MESSAGE = 3 }

/** Everything the companion shows, as state. The activity writes it; the screens read it. */
@Stable
internal class CompanionUi(val title: String, val pcName: String) {
    var stats by mutableStateOf<StreamStats?>(null)
    var restarting by mutableStateOf(false)
    var owned by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var padMouse by mutableStateOf(false)
    var quitPending by mutableStateOf(false)
    var pending by mutableStateOf<List<String>>(emptyList())
    var sheet by mutableStateOf(Sheet.None)
    var typing by mutableStateOf(false)
    /** The controller keyboard while the companion has the controller; null types with Android's keyboard. */
    var keyboard by mutableStateOf<PcKeyboard.State?>(null)
    var statsDetail by mutableStateOf(false)
    /** The new stream's settings, briefly after a restart: "1080p60 · 25 Mbps". */
    var applied by mutableStateOf<String?>(null)
    /** The player has used Back to move the controller, so the deck stops offering the hint. */
    var switchLearned by mutableStateOf(true)
    var message by mutableStateOf<Message?>(null)
    /** The trackpad gestures the player has used; each retires its own hint. */
    var learned by mutableStateOf<Set<TouchpadGesture.Gesture>>(emptySet())
    var category by mutableStateOf(CompanionSettings.categories.first().id)
    /** The row last used in each category, where Right from that category lands. */
    val lastRow = HashMap<String, String>()
    var targetFps by mutableIntStateOf(60)
    /** Bumped on every settings write, so rows read the stored values again. */
    var settingsVersion by mutableIntStateOf(0)
}

/** What a message is about, which decides its buttons and where focus goes after it. */
internal enum class MessageKind { QuitFailed, RestartFailed, Info, Confirm }

/** A message in Foldcade's dialog style. [primary] is A's button; [secondary], when there is one, is B's. */
internal data class Message(val kind: MessageKind, val title: String, val body: String, val primary: String, val secondary: String? = null,
                             /** Bumped when the same message comes back, as a Try again that failed: the card shakes. */
                             val attempt: Int = 0)

/** What the screens can do. The activity carries them out against the session. */
internal interface CompanionActions : PadButtons {
    val gesture: TouchpadGesture
    val store: CompanionSettings.Store
    val topScreen: String
    fun send(shortcut: PcShortcut)
    fun setTyping(open: Boolean)
    fun setMuted(muted: Boolean)
    /** [fromController]: activated with A, which also hands the controller back; a tap only toggles. */
    fun setPadMouse(on: Boolean, fromController: Boolean)
    /** Asks before something an accessibility activation would otherwise do at once. */
    fun confirm(title: String, body: String, primary: String, action: () -> Unit)
    fun keyboardTap(at: PcKeyboard.Focus)
    fun takeController(take: Boolean)
    fun openSheet(sheet: Sheet)
    fun closeSheet()
    fun disconnect()
    fun quit()
    fun restart()
    fun settingChanged()
    fun dismissMessage()
    /** A on a message, or its first button. */
    fun messagePrimary()
    /** B on a message, or its second button. */
    fun messageSecondary()
    /** Leaves the controller keyboard for Android's, handing the controller back to the game. */
    fun touchKeys()
}

@Composable
internal fun CompanionRoot(ui: CompanionUi, nav: NavHost, actions: CompanionActions) {
    val fold = LocalFold.current
    val sheetShown by eased(if (ui.sheet != Sheet.None || ui.message != null) 1f else 0f, fold.durationIsland)
    val ownedGlow by eased(if (ui.owned) 1f else 0f, fold.durationIsland)
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                // Afterglow's ribbons, drawn once as two still strands. Nothing here animates.
                val w = size.width
                val h = size.height
                val cool = Path().apply { moveTo(-w * 0.05f, h * 0.78f); cubicTo(w * 0.3f, h * 0.35f, w * 0.55f, h * 1.05f, w * 1.05f, h * 0.42f) }
                val warm = Path().apply { moveTo(-w * 0.05f, h * 0.92f); cubicTo(w * 0.35f, h * 0.62f, w * 0.7f, h * 0.98f, w * 1.05f, h * 0.7f) }
                val coolBrush = Brush.horizontalGradient(listOf(fold.ringStart, fold.ringEnd))
                val warmBrush = Brush.horizontalGradient(listOf(fold.attention, fold.rose))
                onDrawBehind {
                    drawRect(fold.background)
                    drawPath(cool, coolBrush, alpha = 0.05f, style = Stroke(14.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(cool, coolBrush, alpha = 0.22f, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(warm, warmBrush, alpha = 0.04f, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(warm, warmBrush, alpha = 0.16f, style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
                }
            },
    ) {
        Box(Modifier.fillMaxSize().sinking(fold) { sheetShown }) {
            val keyboard = ui.keyboard
            when {
                ui.typing && keyboard != null -> ControllerTyping(ui, keyboard, actions)
                ui.typing -> TouchTyping(ui, nav, actions)
                else -> Deck(ui, nav, actions)
            }
        }
        Sheets(ui, nav, actions)
        // The companion has the controller: the whole screen wears the focus ring's light.
        Box(Modifier.fillMaxSize().drawBehind {
            if (ownedGlow <= 0f) return@drawBehind
            val inset = fold.ringGap + fold.ringStroke + 2f
            drawFocusRing(fold, 26.dp.toPx(), ownedGlow * 0.7f, Rect(inset, inset, size.width - inset, size.height - inset))
        })
    }
}

/**
 * The deck. Landscape puts the rail of quick tiles beside the keys and pad; portrait, on the
 * bottom screen held upright, lays the tiles in a row under the pad so the keys keep their width.
 */
@Composable
private fun Deck(ui: CompanionUi, nav: NavHost, actions: CompanionActions) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        val portrait = maxHeight > maxWidth
        Column(Modifier.fillMaxSize()) {
            Header(ui, nav)
            Gap(height = 10.dp)
            if (portrait) {
                KeysRow(nav, actions, Modifier.fillMaxWidth().height(58.dp))
                Gap(height = 10.dp)
                Pad(ui, nav, actions, Modifier.weight(1f).fillMaxWidth())
                Gap(height = 10.dp)
                Rail(ui, nav, actions, Modifier.fillMaxWidth().height(78.dp), horizontal = true)
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        KeysRow(nav, actions, Modifier.fillMaxWidth().height(58.dp))
                        Gap(height = 10.dp)
                        Pad(ui, nav, actions, Modifier.weight(1f).fillMaxWidth())
                    }
                    Gap(width = 12.dp)
                    Rail(ui, nav, actions, Modifier.width(84.dp).fillMaxHeight(), horizontal = false)
                }
            }
            Gap(height = 2.dp)
            Footer(ui, nav, actions, Modifier.fillMaxWidth().height(48.dp))
        }
    }
}

/** The trackpad, as a place the D-pad can reach too: A there turns the stick mouse on and off. */
@Composable
private fun Pad(ui: CompanionUi, nav: NavHost, actions: CompanionActions, modifier: Modifier, compact: Boolean = false) {
    val focused = nav.isFocused("pad")
    Trackpad(
        actions.gesture, actions, if (compact) TouchpadGesture.Gesture.entries.toSet() else ui.learned, "PC trackpad",
        modifier
            .navTarget(nav, if (compact) "pad:compact" else "pad", Layer.DECK, enabled = !compact) { actions.setPadMouse(!ui.padMouse, fromController = true) }
            .focusRing(focused && !compact, 22.dp),
        compact = compact,
    )
}

@Composable
private fun Header(ui: CompanionUi, nav: NavHost) {
    val fold = LocalFold.current
    val detail by eased(if (ui.statsDetail) 1f else 0f, fold.durationSelect)
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(R.drawable.fc_symbol_moonlight, 26.dp, fold.ink, Modifier.graphicsLayer { alpha = 0.9f })
        Gap(width = 12.dp)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            if (detail < 1f) Column(Modifier.graphicsLayer { alpha = 1f - detail; translationY = -detail * 10.dp.toPx() }) {
                BasicText(ui.title, style = fold.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(ui.pcName, style = fold.small.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detail > 0f) Metrics(ui.stats, Modifier.graphicsLayer { alpha = detail; translationY = (1f - detail) * 10.dp.toPx() })
        }
        Gap(width = 10.dp)
        StatsPill(ui, nav)
    }
}

/**
 * fps and ping on a pill, with a dot that says at a glance how the stream is doing. When the
 * dot is amber or rose, ping gives way to what is wrong: "21 fps · 6% loss".
 */
@Composable
private fun StatsPill(ui: CompanionUi, nav: NavHost) {
    val fold = LocalFold.current
    val id = "stats"
    val focused = nav.isFocused(id)
    val stats = ui.stats
    val toggle = { ui.statsDetail = !ui.statsDetail }
    val dot = when {
        stats == null -> fold.muted
        else -> when (StreamHealth.verdict(stats, ui.targetFps).level) { 2 -> fold.rose; 1 -> fold.attention; else -> fold.on }
    }
    val locale = androidx.core.os.ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: java.util.Locale.ROOT
    val waiting = stats == null
    val breath = if (waiting) {
        val t = rememberInfiniteTransition(label = "connecting")
        t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(fold.duration(fold.durationPulse), easing = fold.pulse), RepeatMode.Reverse), label = "dot")
    } else null
    Row(
        Modifier
            .navTarget(nav, id, Layer.DECK, activate = toggle)
            .focusLift(focused)
            .focusRing(focused, 18.dp)
            .height(36.dp)
            .drawBehind { drawRoundRect(fold.ink.copy(alpha = 0.08f), cornerRadius = CornerRadius(size.height / 2f)) }
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "Stream stats"; onClick { toggle(); true } }
            .pointerInput(Unit) { detectTapGestures { toggle() } }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).graphicsLayer { alpha = breath?.value ?: 1f }.drawBehind { drawCircle(dot) })
        Gap(width = 8.dp)
        val text = when {
            ui.restarting -> "Restarting…"
            ui.applied != null -> ui.applied!!
            stats == null -> "Connecting…"
            else -> StreamHealth.pillText(stats, ui.targetFps, locale)
        }
        BasicText(text, style = fold.hint.copy(color = fold.ink, fontWeight = fold.strong), maxLines = 1)
    }
}

@Composable
private fun Metrics(stats: StreamStats?, modifier: Modifier) {
    val fold = LocalFold.current
    val locale = androidx.core.os.ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: java.util.Locale.ROOT
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        if (stats == null) {
            BasicText("Waiting for video", style = fold.hint)
            return@Row
        }
        Metric(String.format(locale, "%.1f", stats.decodeMs), "ms decode")
        Metric(String.format(locale, "%.1f%%", stats.lossPercent), "loss")
        Metric("${stats.width}×${stats.height}", stats.codec)
    }
}

@Composable
private fun Metric(value: String, label: String) {
    val fold = LocalFold.current
    Column {
        BasicText(value, style = fold.hint.copy(color = fold.ink, fontWeight = fold.strong, fontSize = 15.sp), maxLines = 1)
        BasicText(label, style = fold.small.copy(fontSize = 11.sp), maxLines = 1)
    }
}

@Composable
private fun KeysRow(nav: NavHost, actions: CompanionActions, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val key = Modifier.weight(1f).fillMaxHeight()
        KeyCap(nav, "key:esc", R.drawable.fc_pc_escape, "Esc", "Escape", key) { actions.send(PcShortcut.ESCAPE) }
        KeyCap(nav, "key:start", R.drawable.fc_pc_start, "Win", "Windows key", key) { actions.send(PcShortcut.START_MENU) }
        KeyCap(nav, "key:alttab", R.drawable.fc_pc_switch, "Alt+Tab", "Alt Tab", key) { actions.send(PcShortcut.ALT_TAB) }
        KeyCap(nav, "key:overlay", R.drawable.fc_pc_gamebar, "Overlay", "Game overlay, Windows G", key) { actions.send(PcShortcut.GAME_OVERLAY) }
        KeyCap(nav, "key:altf4", R.drawable.fc_pc_close_window, "Alt+F4", "Alt F4, hold to send", key, danger = true,
            onConfirm = { actions.confirm("Send Alt+F4?", "It closes the window in front on your PC.", "Send") { actions.send(PcShortcut.ALT_F4) } }) {
            actions.send(PcShortcut.ALT_F4)
        }
        KeyCap(nav, "key:cad", R.drawable.fc_pc_security, "Ctrl+Alt+Del", "Control Alt Delete, hold to send",
            Modifier.weight(1.45f).fillMaxHeight(), danger = true,
            onConfirm = { actions.confirm("Send Ctrl+Alt+Del?", "It opens your PC's security screen.", "Send") { actions.send(PcShortcut.CTRL_ALT_DEL) } }) {
            actions.send(PcShortcut.CTRL_ALT_DEL)
        }
        KeyCap(nav, "key:type", R.drawable.fc_pc_keyboard, "Type", "Keyboard", key) { actions.setTyping(true) }
    }
}

@Composable
private fun Rail(ui: CompanionUi, nav: NavHost, actions: CompanionActions, modifier: Modifier, horizontal: Boolean) {
    val tiles: @Composable (Modifier) -> Unit = { tile ->
        QuickTile(nav, "tile:mute", if (ui.muted) R.drawable.fc_ic_status_volume_mute else R.drawable.fc_ic_status_volume_3,
            if (ui.muted) "Muted" else "Sound", if (ui.muted) "Unmute" else "Mute", tile, on = ui.muted) { actions.setMuted(!ui.muted) }
        QuickTile(nav, "tile:mouse", R.drawable.fc_pc_mouse, "Stick mouse", "Controller stick as mouse", tile, on = ui.padMouse,
            onActivate = { actions.setPadMouse(!ui.padMouse, fromController = true) }) { actions.setPadMouse(!ui.padMouse, fromController = false) }
        QuickTile(nav, "tile:settings", R.drawable.fc_ic_status_settings, "Settings", "Settings", tile, badge = ui.pending.isNotEmpty()) { actions.openSheet(Sheet.Settings) }
        QuickTile(nav, "tile:end", R.drawable.fc_pc_exit, "End", "End session", tile) { actions.openSheet(Sheet.End) }
    }
    if (horizontal) Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) { tiles(Modifier.weight(1f).fillMaxHeight()) }
    else Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) { tiles(Modifier.weight(1f).fillMaxWidth()) }
}

/** What A does on the focused deck control, named, for the hint row. */
private fun deckAction(nav: NavHost, ui: CompanionUi): String = when (nav.focused) {
    "tile:mute" -> if (ui.muted) "Unmute" else "Mute"
    "tile:settings" -> "Open"
    "tile:end" -> "End"
    "stats" -> "Details"
    "tile:mouse", "pad" -> if (ui.padMouse) "Stick mouse off" else "Stick mouse"
    "key:type" -> "Type"
    else -> if (nav.current()?.hold == true) "Hold" else "Send"
}

@Composable
private fun Footer(ui: CompanionUi, nav: NavHost, actions: CompanionActions, modifier: Modifier) {
    val fold = LocalFold.current
    val owned by eased(if (ui.owned) 1f else 0f, fold.durationSelect)
    val tip = refusalTip(nav)
    val tipShown by eased(if (tip) 1f else 0f)
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        // The game has the controller: offer it here. Tapping is a deliberate choice too.
        if (owned < 1f) Row(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - owned }, verticalAlignment = Alignment.CenterVertically) {
            TakeController(actions)
            // The stick is the mouse: say so, and how it stops, where the player looks for state.
            if (ui.padMouse) {
                Gap(width = 12.dp)
                StickMouseChip()
            }
            // Until Back has been used once: the controller moves between the screens with it.
            else if (!ui.switchLearned) {
                Spacer(Modifier.weight(1f))
                SwitchScreensChip()
            }
        }
        if (owned > 0f) Row(
            Modifier.fillMaxWidth().graphicsLayer { alpha = owned },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PausedChip()
            Spacer(Modifier.weight(1f))
            HintRow {
                Hint(R.drawable.fc_ic_btn_a, deckAction(nav, ui))
                Hint(R.drawable.fc_ic_btn_back, "Game")
            }
        }
        if (tipShown > 0f) Box(Modifier.fillMaxWidth().graphicsLayer { alpha = tipShown }, contentAlignment = Alignment.Center) {
            BasicText("Hold to send", modifier = Modifier
                .drawBehind { drawRoundRect(fold.rose.copy(alpha = 0.22f), cornerRadius = CornerRadius(size.height / 2f)) }
                .padding(horizontal = 14.dp, vertical = 5.dp),
                style = fold.hint.copy(color = fold.ink, fontWeight = fold.strong))
        }
    }
}

/**
 * True for a moment after a hold key refuses a short press. Each refusal shows once: a screen
 * that comes back (the deck after typing) does not replay the last one.
 */
@Composable
private fun refusalTip(nav: NavHost): Boolean {
    var tip by remember { mutableStateOf(false) }
    val refused = nav.refused
    LaunchedEffect(refused) {
        val (id, count) = refused ?: return@LaunchedEffect
        if (count <= nav.tipShown) return@LaunchedEffect
        nav.tipShown = count
        if (nav.targets[id]?.hold != true) return@LaunchedEffect
        tip = true
        delay(1800)
        tip = false
    }
    return tip
}

/**
 * Back, and "Take controller": what hands the controller to these controls. A tap does too, on a
 * touch area at least 48dp tall however small the words.
 */
@Composable
private fun TakeController(actions: CompanionActions, modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    TapTarget({ actions.takeController(true) }, "Take controller", modifier) {
        Hint(R.drawable.fc_ic_btn_back, "Take controller", fold.hint.copy(color = fold.ink))
    }
}

/** A touch area of at least 48dp around [content], which keeps its own, smaller look. */
@Composable
internal fun TapTarget(onTap: () -> Unit, description: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = description; onClick { tap(); true } }
            .pointerInput(Unit) { detectTapGestures { tap() } },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Shown until the player first uses Back: the button that moves the controller between screens. */
@Composable
private fun SwitchScreensChip() {
    val fold = LocalFold.current
    Row(
        Modifier
            .drawBehind { drawRoundRect(fold.ink.copy(alpha = 0.08f), cornerRadius = CornerRadius(size.height / 2f)) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hint(R.drawable.fc_ic_btn_back, "Switch screens", fold.hint.copy(color = fold.ink))
    }
}

/** Teal, as on is: the stick is the PC's mouse until Start is held. */
@Composable
private fun StickMouseChip() {
    val fold = LocalFold.current
    Row(
        Modifier
            .drawBehind { drawRoundRect(fold.on.copy(alpha = 0.14f), cornerRadius = CornerRadius(size.height / 2f)) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.fc_pc_mouse, 16.dp, fold.on)
        Gap(width = 6.dp)
        BasicText("Stick mouse · Hold Start to stop", style = fold.hint.copy(color = fold.on, fontWeight = fold.strong), maxLines = 1)
    }
}

/** The amber chip that says the game is not getting the controller. */
@Composable
private fun PausedChip(modifier: Modifier = Modifier) {
    val fold = LocalFold.current
    Row(
        modifier
            .drawBehind { drawRoundRect(fold.attention.copy(alpha = 0.14f), cornerRadius = CornerRadius(size.height / 2f)) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.fc_ic_badge_paused, 18.dp, fold.attention)
        Gap(width = 7.dp)
        BasicText("Game input paused", style = fold.hint.copy(color = fold.attention, fontWeight = fold.strong), maxLines = 1)
    }
}

/**
 * Typing by touch, with Android's keyboard below: a short trackpad, so clicking and scrolling
 * need no closing, then the keys a PC field needs, just above the keyboard.
 */
@Composable
private fun TouchTyping(ui: CompanionUi, nav: NavHost, actions: CompanionActions) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        // The pad takes all the room Android's keyboard leaves, so none of it sits empty above.
        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 64.dp, max = 140.dp)) {
            Pad(ui, nav, actions, Modifier.fillMaxSize(), compact = true)
            val fold = LocalFold.current
            TapTarget({ actions.takeController(true) }, "Take controller", Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                Box(Modifier
                    .drawBehind { drawRoundRect(Color.Black.copy(alpha = 0.55f), cornerRadius = CornerRadius(size.height / 2f)) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Hint(R.drawable.fc_ic_btn_back, "Take controller", fold.hint.copy(color = fold.ink))
                }
            }
        }
        Gap(height = 8.dp)
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val key = Modifier.weight(1f).fillMaxHeight()
            KeyCap(nav, "type:esc", R.drawable.fc_pc_escape, "Esc", "Escape", key) { actions.send(PcShortcut.ESCAPE) }
            KeyCap(nav, "type:tab", R.drawable.fc_pc_tab, "Tab", "Tab", key) { actions.send(PcShortcut.TAB) }
            KeyCap(nav, "type:back", R.drawable.fc_ic_control_erase, "Erase", "Backspace", key) { actions.send(PcShortcut.BACKSPACE) }
            KeyCap(nav, "type:enter", R.drawable.fc_pc_enter, "Enter", "Enter", key) { actions.send(PcShortcut.ENTER) }
            KeyCap(nav, "type:done", R.drawable.fc_pc_keyboard, "Done", "Hide keyboard", key) { actions.setTyping(false) }
        }
    }
}

/**
 * Typing with the controller: Foldcade's controller keyboard in place of Android's, a short
 * trackpad above it when there is room, and the paused chip so it is clear where input goes.
 */
@Composable
private fun ControllerTyping(ui: CompanionUi, keyboard: PcKeyboard.State, actions: CompanionActions) {
    val fold = LocalFold.current
    val nav = remember { NavHost() }
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        // The keys need about 6.7 of their own widths in height; the pad takes what is left, if enough.
        val keyUnit = (maxWidth - 28.dp) / PcKeyboard.WIDTH
        val keysTall = keyUnit * 6.72f + 28.dp
        val padRoom = maxHeight - 40.dp - 48.dp - keysTall
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                PausedChip()
                Spacer(Modifier.weight(1f))
                HintRow {
                    Hint(R.drawable.fc_ic_btn_l2, "Shift")
                    Hint(R.drawable.fc_ic_btn_r2, "Enter")
                    Hint(R.drawable.fc_ic_btn_b, "Close")
                }
            }
            Echo(keyboard, actions, Modifier.fillMaxWidth().height(48.dp))
            // The keys keep their own height; the pad takes the rest, so nothing floats in a gap.
            if (padRoom >= 72.dp) {
                Pad(ui, nav, actions, Modifier.fillMaxWidth().weight(1f), compact = true)
                PcKeyboardView(keyboard, actions::keyboardTap, Modifier.height(keysTall).fillMaxWidth())
            } else {
                PcKeyboardView(keyboard, actions::keyboardTap, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * What was typed while the keyboard has been open, faint, ending in a caret: the PC's field is
 * on the other screen, so this is the bottom screen's own record. It stays here and is cleared
 * when the keyboard closes.
 */
@Composable
private fun Echo(keyboard: PcKeyboard.State, actions: CompanionActions, modifier: Modifier) {
    val fold = LocalFold.current
    // The modifiers waiting for the next key, as a PC shortcut reads: "Ctrl + Alt +".
    val latched = listOfNotNull("Ctrl".takeIf { keyboard.ctrl }, "Alt".takeIf { keyboard.alt })
    // A shortcut clears the echo, since the PC's field may have changed in ways it cannot follow;
    // what was sent stands there for a moment instead, so the press visibly went somewhere.
    var sentShown by remember { mutableStateOf(false) }
    LaunchedEffect(keyboard.sentCount) {
        if (keyboard.sentCount == 0) return@LaunchedEffect
        sentShown = true
        kotlinx.coroutines.delay(1200)
        sentShown = false
    }
    val sent by eased(if (sentShown && keyboard.echo.isEmpty()) 1f else 0f)
    Row(modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (sent > 0f) {
                BasicText(keyboard.sent, style = fold.row.copy(color = fold.focus, fontWeight = fold.strong, fontSize = 17.sp), maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = sent }.semantics { contentDescription = "Sent ${keyboard.sent}" })
                Gap(width = 4.dp)
            }
            BasicText(keyboard.echo, style = fold.row.copy(color = fold.ink.copy(alpha = 0.55f), fontSize = 17.sp), maxLines = 1,
                overflow = TextOverflow.Clip)
            Box(Modifier.width(2.dp).height(18.dp).drawBehind { drawRect(fold.ringBrush(size), alpha = 0.8f) })
            if (latched.isNotEmpty()) {
                Gap(width = 10.dp)
                BasicText(latched.joinToString(" + ") + " +", style = fold.hint.copy(color = fold.focus, fontWeight = fold.strong), maxLines = 1)
            }
        }
        TouchKeysChip(actions)
    }
}

/** Typing by touch instead: hands the controller back and brings up Android's keyboard. */
@Composable
private fun TouchKeysChip(actions: CompanionActions) {
    val fold = LocalFold.current
    TapTarget(actions::touchKeys, "Touch keyboard") {
        Row(
            Modifier
                .drawBehind { drawRoundRect(fold.ink.copy(alpha = 0.08f), cornerRadius = CornerRadius(size.height / 2f)) }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.fc_pc_keyboard, 18.dp, fold.ink)
            Gap(width = 6.dp)
            BasicText("Touch keys", style = fold.hint.copy(color = fold.ink), maxLines = 1)
        }
    }
}

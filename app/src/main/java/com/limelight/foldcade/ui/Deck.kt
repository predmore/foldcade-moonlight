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
    var statsDetail by mutableStateOf(false)
    var hintsSeen by mutableStateOf(false)
    var message by mutableStateOf<Pair<String, String>?>(null)
    var category by mutableStateOf(CompanionSettings.categories.first().id)
    var targetFps by mutableIntStateOf(60)
    /** Bumped on every settings write, so rows read the stored values again. */
    var settingsVersion by mutableIntStateOf(0)
}

/** What the screens can do. The activity carries them out against the session. */
internal interface CompanionActions : PadButtons {
    val gesture: TouchpadGesture
    val store: CompanionSettings.Store
    val topScreen: String
    fun send(shortcut: PcShortcut)
    fun setTyping(open: Boolean)
    fun setMuted(muted: Boolean)
    fun setPadMouse(on: Boolean)
    fun takeController(take: Boolean)
    fun openSheet(sheet: Sheet)
    fun closeSheet()
    fun disconnect()
    fun quit()
    fun restart()
    fun settingChanged()
    fun hintsSeen()
    fun dismissMessage()
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
            if (ui.typing) TypingDeck(ui, nav, actions) else Deck(ui, nav, actions)
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

@Composable
private fun Deck(ui: CompanionUi, nav: NavHost, actions: CompanionActions) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        Header(ui, nav)
        Gap(height = 10.dp)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                KeysRow(nav, actions, Modifier.fillMaxWidth().height(58.dp))
                Gap(height = 10.dp)
                Trackpad(actions.gesture, actions, !ui.hintsSeen, actions::hintsSeen, "PC trackpad", Modifier.weight(1f).fillMaxWidth())
            }
            Gap(width = 12.dp)
            Rail(ui, nav, actions, Modifier.width(84.dp).fillMaxHeight())
        }
        Gap(height = 6.dp)
        Footer(ui, nav, actions, Modifier.fillMaxWidth().height(34.dp))
    }
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

/** fps and ping on a pill, with a dot that says at a glance how the stream is doing. */
@Composable
private fun StatsPill(ui: CompanionUi, nav: NavHost) {
    val fold = LocalFold.current
    val id = "stats"
    val focused = nav.isFocused(id)
    val stats = ui.stats
    val toggle = { ui.statsDetail = !ui.statsDetail }
    val dot = when {
        stats == null -> fold.muted
        else -> when (health(stats, ui.targetFps)) { 2 -> fold.rose; 1 -> fold.attention; else -> fold.on }
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
            stats == null -> "Connecting…"
            else -> String.format(locale, "%.0f fps · %d ms", stats.fps, stats.rttMs)
        }
        BasicText(text, style = fold.hint.copy(color = fold.ink, fontWeight = fold.strong), maxLines = 1)
    }
}

/**
 * 0 healthy, 1 slipping, 2 struggling: ping, loss, and decode time against the frame budget.
 * Frame rate is not part of it: a host sends only changed frames, so a still desktop runs slow.
 */
internal fun health(stats: StreamStats, targetFps: Int): Int {
    val budget = 1000f / targetFps.coerceAtLeast(1)
    return when {
        stats.lossPercent >= 5f || stats.rttMs >= 80 || stats.decodeMs >= budget -> 2
        stats.lossPercent >= 1f || stats.rttMs >= 35 || stats.decodeMs >= budget * 0.6f -> 1
        else -> 0
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
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val key = Modifier.weight(1f).fillMaxHeight()
        KeyCap(nav, "key:esc", R.drawable.fc_pc_escape, "Esc", "Escape", key) { actions.send(PcShortcut.ESCAPE) }
        KeyCap(nav, "key:start", R.drawable.fc_pc_start, "Start", "Windows key", key) { actions.send(PcShortcut.START_MENU) }
        KeyCap(nav, "key:alttab", R.drawable.fc_pc_switch, "Alt+Tab", "Alt Tab", key) { actions.send(PcShortcut.ALT_TAB) }
        KeyCap(nav, "key:altf4", R.drawable.fc_pc_close_window, "Alt+F4", "Alt F4, hold to send", key, danger = true) { actions.send(PcShortcut.ALT_F4) }
        KeyCap(nav, "key:cad", R.drawable.fc_pc_security, "Ctrl+Alt+Del", "Control Alt Delete, hold to send",
            Modifier.weight(1.4f).fillMaxHeight(), danger = true) { actions.send(PcShortcut.CTRL_ALT_DEL) }
        KeyCap(nav, "key:type", R.drawable.fc_pc_keyboard, "Type", "Keyboard", key) { actions.setTyping(true) }
    }
}

@Composable
private fun Rail(ui: CompanionUi, nav: NavHost, actions: CompanionActions, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val tile = Modifier.weight(1f).fillMaxWidth()
        QuickTile(nav, "tile:mute", if (ui.muted) R.drawable.fc_ic_status_volume_mute else R.drawable.fc_ic_status_volume_3,
            if (ui.muted) "Muted" else "Sound", if (ui.muted) "Unmute" else "Mute", tile, on = ui.muted) { actions.setMuted(!ui.muted) }
        QuickTile(nav, "tile:mouse", R.drawable.fc_pc_mouse, "Pad mouse", "Controller as mouse", tile, on = ui.padMouse) { actions.setPadMouse(!ui.padMouse) }
        QuickTile(nav, "tile:settings", R.drawable.fc_ic_status_settings, "Settings", "Settings", tile, badge = ui.pending.isNotEmpty()) { actions.openSheet(Sheet.Settings) }
        QuickTile(nav, "tile:end", R.drawable.fc_symbol_close, "End", "End session", tile) { actions.openSheet(Sheet.End) }
    }
}

@Composable
private fun Footer(ui: CompanionUi, nav: NavHost, actions: CompanionActions, modifier: Modifier) {
    val fold = LocalFold.current
    val owned by eased(if (ui.owned) 1f else 0f, fold.durationSelect)
    var tip by remember { mutableStateOf(false) }
    LaunchedEffect(nav.refused) {
        if (nav.refused == null) return@LaunchedEffect
        tip = true
        delay(1800)
        tip = false
    }
    val tipShown by eased(if (tip) 1f else 0f)
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        // The game has the controller: offer it here. Tapping is a deliberate choice too.
        if (owned < 1f) Row(
            Modifier
                .graphicsLayer { alpha = 1f - owned }
                .semantics(mergeDescendants = true) { role = Role.Button; onClick { actions.takeController(true); true } }
                .pointerInput(Unit) { detectTapGestures { actions.takeController(true) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Hint(R.drawable.fc_ic_btn_back, "Controls here")
        }
        if (owned > 0f) Row(
            Modifier.fillMaxWidth().graphicsLayer { alpha = owned },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .drawBehind { drawRoundRect(fold.attention.copy(alpha = 0.14f), cornerRadius = CornerRadius(size.height / 2f)) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(R.drawable.fc_ic_badge_paused, 18.dp, fold.attention)
                Gap(width = 7.dp)
                BasicText("Game input paused", style = fold.hint.copy(color = fold.attention, fontWeight = fold.strong), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            HintRow {
                val holds = nav.focused?.let { nav.targets[it]?.hold } == true
                Hint(R.drawable.fc_ic_btn_a, if (holds) "Hold" else "Select")
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

/** Typing: the system keyboard takes the lower screen; the keys a PC field needs sit just above it. */
@Composable
private fun TypingDeck(ui: CompanionUi, nav: NavHost, actions: CompanionActions) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        Header(ui, nav)
        Spacer(Modifier.weight(1f))
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

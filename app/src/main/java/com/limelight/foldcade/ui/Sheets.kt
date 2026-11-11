package com.limelight.foldcade.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.limelight.R
import com.limelight.foldcade.CompanionSettings
import com.limelight.foldcade.CompanionSettings.Setting
import kotlinx.coroutines.launch

/** The sheets over the deck. Each keeps showing as it was while it fades away. */
@Composable
internal fun Sheets(ui: CompanionUi, nav: NavHost, actions: CompanionActions) {
    val fold = LocalFold.current
    var lastSheet by remember { mutableStateOf(Sheet.None) }
    if (ui.sheet != Sheet.None) lastSheet = ui.sheet
    val open by eased(if (ui.sheet != Sheet.None) 1f else 0f, fold.durationIsland)
    val shown = if (ui.sheet != Sheet.None) ui.sheet else lastSheet.takeIf { open > 0f } ?: Sheet.None
    if (shown != Sheet.None) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(fold.background.copy(alpha = fold.scrimAlpha * open)) }
            .pointerInput(Unit) { detectTapGestures { if (shown == Sheet.End) actions.closeSheet() } })
        when (shown) {
            Sheet.End -> EndSheet(ui, nav, actions) { open }
            Sheet.Settings -> SettingsSheet(ui, nav, actions) { open }
            Sheet.None -> Unit
        }
    }
    var lastMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    if (ui.message != null) lastMessage = ui.message
    val messageOpen by eased(if (ui.message != null) 1f else 0f, fold.durationIsland)
    val message = ui.message ?: lastMessage?.takeIf { messageOpen > 0f }
    if (message != null) MessageCard(message, nav, actions) { messageOpen }
}

/**
 * Ending a session is a choice between two cards. Disconnect leaves the game running and is the
 * safe default; Quit closes it on the PC, so it is rose and takes a hold.
 */
@Composable
private fun EndSheet(ui: CompanionUi, nav: NavHost, actions: CompanionActions, progress: () -> Float) {
    val fold = LocalFold.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp).rise(fold, progress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("End session", style = fold.title)
        BasicText(ui.title + " · " + ui.pcName, style = fold.hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Gap(height = 16.dp)
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            EndCard(nav, "end:disconnect", R.drawable.fc_ic_badge_paused, fold.focus, "Disconnect", "Game keeps running",
                Modifier.weight(1f).fillMaxHeight().cascade(fold, 0, progress), hold = false, enabled = true) { actions.disconnect() }
            EndCard(nav, "end:quit", R.drawable.fc_symbol_close, fold.rose, "Quit game",
                if (ui.quitPending) "Quitting…" else "Closes it on the PC",
                Modifier.weight(1f).fillMaxHeight().cascade(fold, 1, progress), hold = true, enabled = !ui.quitPending) { actions.quit() }
        }
        Gap(height = 12.dp)
        HintRow {
            val quit = nav.focused == "end:quit"
            Hint(R.drawable.fc_ic_btn_a, if (quit) "Hold to quit" else "Disconnect")
            Hint(R.drawable.fc_ic_btn_b, "Cancel")
        }
    }
}

@Composable
private fun EndCard(
    nav: NavHost, id: String, icon: Int, accent: androidx.compose.ui.graphics.Color, title: String, detail: String,
    modifier: Modifier, hold: Boolean, enabled: Boolean, onChoose: () -> Unit,
) {
    val fold = LocalFold.current
    val corner = 26.dp
    val focused = nav.isFocused(id)
    var pressed by remember { mutableStateOf(false) }
    val holdState = if (hold) rememberHold(nav, id, enabled, onChoose) else null
    val choose by androidx.compose.runtime.rememberUpdatedState(onChoose)
    val lit by eased(if (focused || pressed) 1f else 0f)
    Box(
        modifier
            .navTarget(nav, id, Layer.END, enabled, hold = hold, activate = onChoose)
            .shakeOnRefuse(nav, id)
            .focusLift(false, pressed)
            .focusRing(focused, corner, pronounced = true)
            .drawWithContent {
                val r = CornerRadius(corner.toPx())
                drawRoundRect(fold.cardFill(0.035f + 0.04f * lit), cornerRadius = r)
                // The card's own light: its accent, soft, near the top.
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f + 0.1f * lit), androidx.compose.ui.graphics.Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.3f), radius = size.width * 0.6f), size.width * 0.6f, Offset(size.width / 2f, size.height * 0.3f))
                val p = holdState?.progress?.value ?: 0f
                if (p > 0f) drawRoundRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.5f), accent.copy(alpha = 0.25f))),
                    topLeft = Offset(0f, size.height * (1f - p)), size = Size(size.width, size.height * p), cornerRadius = r)
                drawRoundRect(fold.ink.copy(alpha = fold.borderAlpha), cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                drawContent()
            }
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "$title. $detail"; onClick { onChoose(); true } }
            .pointerInput(hold, enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        pressed = true
                        nav.focus(id)
                        if (hold) holdState?.touching = true
                        tryAwaitRelease()
                        pressed = false
                        if (hold) holdState?.touching = false
                    },
                    onTap = { if (!hold && enabled) choose() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(icon, 56.dp, accent, Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f })
            Gap(height = 14.dp)
            BasicText(title, style = fold.title.copy(fontSize = 21.sp))
            Gap(height = 4.dp)
            BasicText(detail, style = fold.hint.copy(textAlign = TextAlign.Center), maxLines = 1)
            if (hold) {
                Gap(height = 12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(R.drawable.fc_ic_btn_a, 20.dp, null)
                    Gap(width = 6.dp)
                    BasicText("Hold", style = fold.small.copy(color = accent, fontWeight = fold.strong))
                }
            }
        }
    }
}

/**
 * Settings, as Foldcade lays its own out: categories on the left, the chosen one's rows in a
 * card on the right. Everything a new stream reads is Moonlight's own preference; when one
 * changes, an Apply pill offers to restart the stream with it.
 */
@Composable
private fun SettingsSheet(ui: CompanionUi, nav: NavHost, actions: CompanionActions, progress: () -> Float) {
    val fold = LocalFold.current
    val category = CompanionSettings.categories.first { it.id == ui.category }
    Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp).rise(fold, progress)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(158.dp).fillMaxHeight()) {
                // Foldcade's page title pill: B and the page's name.
                Row(
                    Modifier
                        .drawBehind { drawRoundRect(fold.ink.copy(alpha = 0.08f), cornerRadius = CornerRadius(size.height / 2f)) }
                        .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "Close settings"; onClick { actions.closeSheet(); true } }
                        .pointerInput(Unit) { detectTapGestures { actions.closeSheet() } }
                        .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(R.drawable.fc_ic_btn_b, 26.dp, null)
                    Gap(width = 10.dp)
                    BasicText("Settings", style = fold.title.copy(fontSize = 22.sp))
                }
                Gap(height = 14.dp)
                CompanionSettings.categories.forEachIndexed { index, item ->
                    CategoryRow(ui, nav, item.id, item.label, categoryIcon(item.id), Modifier.cascade(fold, index, progress))
                }
            }
            Gap(width = 12.dp)
            val corner = 24.dp
            val shape = RoundedCornerShape(corner)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .drawBehind { drawRect(fold.cardFill()) }
                    .border(1.dp, fold.ink.copy(alpha = 0.06f), shape),
            ) {
                val scroll = rememberScrollState()
                val slots = remember { mutableStateMapOf<String, Rect>() }
                LaunchedEffect(ui.category) { scroll.scrollTo(0) }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .selectionHighlight(nav, slots),
                ) {
                    category.settings.forEachIndexed { index, setting ->
                        SettingRow(ui, nav, actions, setting, slots, Modifier.cascade(fold, index, progress))
                    }
                }
                ApplyBar(ui, nav, actions)
            }
        }
        Gap(height = 6.dp)
        HintRow(Modifier.height(30.dp)) {
            Hint(R.drawable.fc_ic_btn_l1, "")
            Hint(R.drawable.fc_ic_btn_r1, "Page")
            Hint(R.drawable.fc_ic_btn_a, "Change")
            Hint(R.drawable.fc_ic_btn_b, "Back")
        }
    }
}

private fun categoryIcon(id: String): Int = when (id) {
    "video" -> R.drawable.fc_symbol_pc
    "audio" -> R.drawable.fc_ic_status_volume_3
    "controls" -> R.drawable.fc_symbol_console
    else -> R.drawable.fc_pc_trackpad
}

@Composable
private fun CategoryRow(ui: CompanionUi, nav: NavHost, id: String, label: String, icon: Int, modifier: Modifier) {
    val fold = LocalFold.current
    val navId = "cat:$id"
    val focused = nav.isFocused(navId)
    val chosen by eased(if (ui.category == id) 1f else 0f, fold.durationSelect)
    val select = {
        ui.category = id
        firstRow(ui)?.let { nav.focus(it) }
    }
    LaunchedEffect(focused) { if (focused) ui.category = id }
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .navTarget(nav, navId, Layer.SETTINGS) { select() }
            .focusRing(focused, 22.dp)
            .drawBehind {
                if (chosen > 0f) drawRoundRect(Brush.horizontalGradient(listOf(fold.ringStart.copy(alpha = fold.highlightStartAlpha * chosen),
                    fold.ringEnd.copy(alpha = fold.highlightEndAlpha * chosen))), cornerRadius = CornerRadius(22.dp.toPx()))
            }
            .semantics(mergeDescendants = true) { role = Role.Tab; contentDescription = label; onClick { ui.category = id; true } }
            .pointerInput(id) { detectTapGestures { ui.category = id; nav.focus(navId) } }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 28.dp, fold.ink, Modifier.graphicsLayer { alpha = fold.artRestAlpha + (1f - fold.artRestAlpha) * chosen })
        Gap(width = 12.dp)
        BasicText(label, style = fold.row.copy(fontSize = 17.sp), maxLines = 1,
            modifier = Modifier.graphicsLayer { alpha = 0.62f + 0.38f * chosen })
    }
}

internal fun firstRow(ui: CompanionUi): String? =
    CompanionSettings.categories.firstOrNull { it.id == ui.category }?.settings?.firstOrNull()?.let { "set:${it.key}" }

/**
 * One highlight behind the focused row. It travels to the next row and lands with a little
 * bounce, as Foldcade's menus do; the focus ring rides on it while the controller is here.
 */
@Composable
private fun Modifier.selectionHighlight(nav: NavHost, slots: Map<String, Rect>): Modifier {
    val fold = LocalFold.current
    val top = remember { Animatable(0f) }
    val left = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(slots) {
        snapshotFlow { nav.focused?.let { slots[it] } }.collect { next ->
            if (next == null) return@collect
            if (!placed) {
                top.snapTo(next.top); left.snapTo(next.left); width.snapTo(next.width); height.snapTo(next.height)
                placed = true
                return@collect
            }
            steady {
                kotlinx.coroutines.coroutineScope {
                    launch { top.animateTo(next.top, fold.playfulSpec()) }
                    launch { left.animateTo(next.left, fold.arriveSpec(fold.durationSelect)) }
                    launch { width.animateTo(next.width, fold.arriveSpec(fold.durationSelect)) }
                    launch { height.animateTo(next.height, fold.arriveSpec(fold.durationSelect)) }
                }
            }
        }
    }
    val present = nav.owned && nav.focused?.let { slots[it] } != null
    val shown by eased(if (present) 1f else 0f)
    return drawBehind {
        if (!placed || shown <= 0f) return@drawBehind
        val rect = Rect(Offset(left.value, top.value), Size(width.value, height.value))
        val corner = 22.dp.toPx()
        drawRoundRect(Brush.horizontalGradient(listOf(fold.ringStart.copy(alpha = fold.highlightStartAlpha * shown), fold.ringEnd.copy(alpha = fold.highlightEndAlpha * shown)),
            startX = rect.left, endX = rect.right), rect.topLeft, rect.size, CornerRadius(corner))
        drawFocusRing(fold, corner, shown, rect)
    }
}

@Composable
private fun SettingRow(ui: CompanionUi, nav: NavHost, actions: CompanionActions, setting: Setting, slots: MutableMap<String, Rect>, modifier: Modifier) {
    val fold = LocalFold.current
    val id = "set:${setting.key}"
    val focused = nav.isFocused(id)
    @Suppress("UNUSED_VARIABLE") val version = ui.settingsVersion
    val store = actions.store
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    val changed = { actions.settingChanged() }
    val rowModifier = modifier
        .fillMaxWidth()
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { slots[id] = Rect(it.positionInParent(), it.size.toSize()) }
    when (setting) {
        is Setting.Toggle -> {
            val on = CompanionSettings.toggle(store, setting)
            val flip = { CompanionSettings.setToggle(store, setting, !on); changed() }
            RowShell(rowModifier.navTarget(nav, id, Layer.SETTINGS) { flip() }, nav, id, setting.label, 52.dp, { flip() }) {
                ToggleSwitch(on, focused)
            }
        }
        is Setting.Choice -> {
            val value = CompanionSettings.choice(store, setting)
            val step = { by: Int -> CompanionSettings.setChoice(store, setting, CompanionSettings.stepChoice(setting, value, by)); changed() }
            val shown = CompanionSettings.optionLabel(setting, value)
            RowShell(rowModifier.navTarget(nav, id, Layer.SETTINGS, adjust = { step(it); true }) { step(1) }, nav, id, setting.label, 52.dp, { step(1) }) {
                ChoiceValue(shown, focused, nav.owned, step, fold.row.copy(fontSize = 16.sp))
            }
        }
        is Setting.Steps -> {
            val value = CompanionSettings.steps(store, setting)
            val auto = if (setting === CompanionSettings.bitrate) autoBitrate(store, actions.topScreen) else null
            val adjust = { by: Int ->
                val (moved, next) = CompanionSettings.stepSteps(setting, value, by)
                if (moved) { CompanionSettings.setSteps(store, setting, next); changed() } else nav.refuse(id)
                true
            }
            val index = setting.stops.indexOf(value).coerceAtLeast(0)
            Column(rowModifier.navTarget(nav, id, Layer.SETTINGS, adjust = adjust) { adjust(1) }.shakeOnRefuse(nav, id)
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowLabel(setting.label, focused, Modifier.weight(1f))
                    RollingText(CompanionSettings.stepsLabel(setting, value, auto), fold.row.copy(fontSize = 16.sp, color = if (focused) fold.focus else fold.ink))
                }
                StepTrack(index / (setting.stops.size - 1f), setting.stops.size, { stop ->
                    nav.focus(id)
                    val next = setting.stops[stop]
                    if (next != value) { CompanionSettings.setSteps(store, setting, next); changed() }
                }, Modifier.fillMaxWidth().semantics { contentDescription = "${setting.label} ${CompanionSettings.stepsLabel(setting, value, auto)}" })
            }
        }
    }
}

/** What Moonlight would pick for Auto: its default for the resolution and frame rate the stream will use. */
private fun autoBitrate(store: CompanionSettings.Store, topScreen: String): Int? {
    val res = CompanionSettings.resolutionChoice(store).let { if (it == CompanionSettings.NATIVE) topScreen.replace('×', 'x') else it }
    val fps = CompanionSettings.choice(store, CompanionSettings.fps)
    return runCatching { com.limelight.preferences.PreferenceConfiguration.getDefaultBitrate(res, fps) }.getOrNull()
}

@Composable
private fun RowShell(modifier: Modifier, nav: NavHost, id: String, label: String, height: Dp, onTap: () -> Unit, control: @Composable () -> Unit) {
    val focused = nav.isFocused(id)
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    Row(
        modifier
            .height(height)
            .shakeOnRefuse(nav, id)
            .semantics(mergeDescendants = true) { contentDescription = label; onClick { tap(); true } }
            .pointerInput(id) { detectTapGestures { nav.focus(id); tap() } }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label, focused, Modifier.weight(1f))
        Gap(width = 12.dp)
        control()
    }
}

/** Shown once a stream setting changed: restart the stream to wear it. The game stays running. */
@Composable
private fun ApplyBar(ui: CompanionUi, nav: NavHost, actions: CompanionActions) {
    val fold = LocalFold.current
    val pending = ui.pending.isNotEmpty() && !ui.restarting
    val shown by eased(if (pending) 1f else 0f, fold.durationIsland)
    if (shown <= 0f && !pending) return
    val id = "apply"
    val focused = nav.isFocused(id)
    Box(Modifier.fillMaxWidth().padding(10.dp).rise(fold) { shown }) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .navTarget(nav, id, Layer.SETTINGS, enabled = pending) { actions.restart() }
                .focusLift(focused)
                .focusRing(focused, 25.dp)
                .drawBehind { drawRoundRect(fold.ringBrush(size), cornerRadius = CornerRadius(size.height / 2f), alpha = 0.9f) }
                .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "Apply and restart stream"; onClick { actions.restart(); true } }
                .pointerInput(Unit) { detectTapGestures { actions.restart() } }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.fc_symbol_restart, 24.dp, fold.background)
            Gap(width = 10.dp)
            BasicText("Apply · restart stream", style = fold.row.copy(color = fold.background, fontWeight = fold.strong, fontSize = 17.sp), maxLines = 1)
            Spacer(Modifier.weight(1f))
            BasicText(ui.pending.size.let { if (it == 1) ui.pending.first() else "$it changes" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = fold.small.copy(color = fold.background.copy(alpha = 0.7f), fontWeight = fold.strong), modifier = Modifier.widthIn(max = 110.dp))
        }
    }
}

/** A message in Foldcade's dialog style, in place of a stock Android dialog. */
@Composable
private fun MessageCard(message: Pair<String, String>, nav: NavHost, actions: CompanionActions, progress: () -> Float) {
    val fold = LocalFold.current
    Box(Modifier.fillMaxSize().drawBehind { drawRect(fold.background.copy(alpha = fold.scrimAlpha * progress())) }
        .pointerInput(Unit) { detectTapGestures { actions.dismissMessage() } }, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(fold.cardCorner)
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp)
                .rise(fold, progress)
                .clip(shape)
                .drawBehind { drawRect(fold.cardFill()) }
                .border(2.dp, fold.ink.copy(alpha = fold.borderAlpha), shape)
                .padding(24.dp),
        ) {
            BasicText(message.first, style = fold.title)
            Gap(height = 8.dp)
            BasicText(message.second, style = fold.row.copy(fontSize = 16.sp, color = fold.ink.copy(alpha = 0.8f)))
            Gap(height = 18.dp)
            val focused = nav.isFocused("message:ok")
            Box(
                Modifier
                    .align(Alignment.End)
                    .navTarget(nav, "message:ok", Layer.MESSAGE) { actions.dismissMessage() }
                    .focusRing(focused, 22.dp)
                    .drawBehind { drawRoundRect(fold.ink.copy(alpha = 0.1f), cornerRadius = CornerRadius(size.height / 2f)) }
                    .pointerInput(Unit) { detectTapGestures { actions.dismissMessage() } }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(R.drawable.fc_ic_btn_a, 22.dp, null)
                    Gap(width = 8.dp)
                    BasicText("OK", style = fold.row.copy(fontWeight = fold.strong))
                }
            }
        }
    }
    Spacer(Modifier.size(0.dp))
}

package dev.nitrate.tv.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.effects.Day
import dev.nitrate.core.effects.EffectsPrefs
import dev.nitrate.core.effects.Occasion
import dev.nitrate.core.effects.Occasions
import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import kotlinx.coroutines.launch

private val Padding = 58.dp
private val Months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

internal fun Day.short() = "$day ${Months[month - 1]}"

/** When [o] is next on, from [today]: "On now" or its first day. */
internal fun whenNext(o: Occasion, today: Day): String {
    val next = Occasions.next(o, today)
    return if (next == today) "On now" else "Next: ${next.short()}"
}

/**
 * Settings → Display → Effects: every effect on or off, Reduce motion, how much, and the calendar's
 * occasions (each with its next date and a preview). Page themes are chosen per page (Pages), card
 * effects per row (the row's settings).
 */
@Composable
fun EffectsSettingsScreen(container: AppContainer) {
    val prefs = LocalDisplayPrefs.current
    val fx = prefs.effects
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val user = container.server.userId
    val profileName by remember(user) { user?.let { container.displaySettings.activeProfileName(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    fun update(change: (EffectsPrefs) -> EffectsPrefs) {
        val u = container.server.userId ?: return
        scope.launch { container.displaySettings.savePrefs(u, prefs.copy(effects = change(prefs.effects))) }
    }
    val today = remember { todayDay() }
    var previewing by remember { mutableStateOf<Pair<PageTheme?, Occasion?>?>(null) }

    @Composable
    fun Toggle(label: String, detail: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, flip: () -> Unit) = ListItem(
        selected = false, onClick = flip, enabled = enabled,
        headlineContent = { Text(label) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = modifier,
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Padding, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Effects", style = MaterialTheme.typography.displaySmall)
                dev.nitrate.tv.ui.settings.ScopeTag(dev.nitrate.core.settings.SettingScope.PROFILE, profileName)
                Text("Page themes are chosen in Pages, card effects in a row's settings (hold OK on a row). Never in the player, and not in easy mode.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Toggle("Effects", "Page themes, card effects and the calendar's occasions", fx.on, Modifier.focusRequester(first)) { update { it.copy(on = !it.on) } } }
        item { Toggle("Reduce motion", "Themes hold still; cards don't spin or spark", fx.reduceMotion, enabled = fx.on) { update { it.copy(reduceMotion = !it.reduceMotion) } } }
        item {
            ListItem(
                selected = false, enabled = fx.on,
                onClick = { update { it.copy(intensity = it.intensity.cycle()) } },
                headlineContent = { Text("Amount") },
                supportingContent = { Text("How much falls, flies and sparks at once") },
                trailingContent = { Text("${fx.intensity.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        item {
            Text("Occasions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        }
        item { Toggle("Seasonal effects", "On the days below, over every page", fx.seasonal, enabled = fx.on) { update { it.copy(seasonal = !it.seasonal) } } }
        items(Occasion.entries, key = { it.name }) { o ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    selected = false, enabled = fx.on && fx.seasonal,
                    onClick = { update { it.with(o, !it.isOn(o)) } },
                    headlineContent = { Text(o.label) },
                    supportingContent = { Text("${o.detail} · ${whenNext(o, today)}") },
                    trailingContent = { Switch(checked = fx.isOn(o), onCheckedChange = null) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { previewing = null to o }) { Text("Preview") }
            }
        }
        item {
            Text("Page themes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        }
        items(PageTheme.entries.filter { it != PageTheme.NONE }, key = { "theme-" + it.name }) { t ->
            ListItem(
                selected = false,
                onClick = { previewing = t to null },
                headlineContent = { Text(t.label) },
                supportingContent = { Text(t.detail) },
                trailingContent = { Text("Preview  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    previewing?.let { (theme, occasion) ->
        EffectPreview(theme, occasion, fx, onDismiss = { previewing = null })
    }
}

/** A full-screen look at one theme or occasion over a stand-in page; any key closes it. */
@Composable
fun EffectPreview(theme: PageTheme?, occasion: Occasion?, prefs: EffectsPrefs, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EffectStage(theme, occasion, prefs, onDismiss)
    }
}

/** A theme or occasion over a stand-in page, titled, with Close; [at] holds it at one moment (screenshots). */
@Composable
fun EffectStage(theme: PageTheme?, occasion: Occasion?, prefs: EffectsPrefs, onDismiss: () -> Unit, at: Float? = null) {
    val focus = remember { FocusRequester() }
    run {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1C2230), Color(0xFF0B0D12))))) {
            StandInPage()
            // Shown even when effects are off: that's what a preview is for.
            EffectsLayer(theme, occasion, prefs.copy(on = true, reduceMotion = false), at = at)
            Column(Modifier.align(Alignment.TopStart).padding(Padding)) {
                Text(occasion?.label ?: theme?.label.orEmpty(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("Back to close", style = MaterialTheme.typography.bodyMedium, color = Color(0xCCFFFFFF))
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.align(Alignment.BottomEnd).padding(Padding).focusRequester(focus)) { Text("Close") }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Grey cards where a page's rows would be, so a preview shows how a theme sits over them. */
@Composable
private fun StandInPage() {
    Column(Modifier.fillMaxSize().padding(start = Padding, top = 200.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(7) {
                    Box(Modifier.width(100.dp).fillMaxHeight(0.36f).background(Color(0xFF2A3142), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

/**
 * The page themes to pick from for one page, the focused one playing behind the list, so each can
 * be seen before it's chosen.
 */
@Composable
fun PageThemePicker(pageName: String, current: PageTheme, onPick: (PageTheme) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PageThemeChoices(pageName, current, onPick)
    }
}

/** The picker's contents: the stand-in page with the focused theme playing, and the list of themes. */
@Composable
internal fun PageThemeChoices(pageName: String, current: PageTheme, onPick: (PageTheme) -> Unit) {
    val prefs = LocalDisplayPrefs.current
    var focused by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    var focusTaken by remember { mutableStateOf(false) }
    // Open scrolled to the theme in use: the list only builds the rows on screen, and focus can only
    // go to a row that's built (themes far down the list used to leave the picker with no focus, so
    // nothing could be chosen until the page was set back to None).
    val currentIndex = PageTheme.entries.indexOf(current) + 1
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1C2230), Color(0xFF0B0D12))))) {
        StandInPage()
        EffectsLayer(focused, null, prefs.effects.copy(on = true))
        LazyColumn(
            Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight().background(Color(0xE6101218)),
            state = list,
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text("$pageName: theme", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp)) }
            itemsIndexed(PageTheme.entries) { _, t ->
                ListItem(
                    selected = t == current,
                    onClick = { onPick(t) },
                    headlineContent = { Text(t.label) },
                    supportingContent = { Text(t.detail) },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) focused = t }
                        .then(if (t == current) Modifier.focusRequester(focus) else Modifier),
                )
                // The row in use takes focus itself once it's built and placed (asking from outside
                // the list could come before it exists, and that leaves the picker with no focus).
                if (t == current) LaunchedEffect(Unit) {
                    if (!focusTaken) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() }; focusTaken = true }
                }
            }
        }
    }
}

private inline fun <reified E : Enum<E>> E.cycle(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

/** [prefs] with [page]'s theme set ([PageTheme.NONE] clears it). */
fun DisplayPrefs.withTheme(page: String, theme: PageTheme): DisplayPrefs =
    copy(pageThemes = if (theme == PageTheme.NONE) pageThemes - page else pageThemes + (page to theme))

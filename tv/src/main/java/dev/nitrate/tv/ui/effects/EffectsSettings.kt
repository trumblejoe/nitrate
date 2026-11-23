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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.nitrate.core.effects.Occasions
import dev.nitrate.core.effects.OccasionDates
import dev.nitrate.core.effects.MonthDay
import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import kotlinx.coroutines.launch

private val Padding = 58.dp
private val Months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

internal fun Day.short() = "$day ${Months[month - 1]}"

/** When [o] is next on, from [today]: "On now" or its first day. */
internal fun whenNext(o: Occasion, today: Day, dates: OccasionDates? = null): String {
    val next = Occasions.next(o, today, dates)
    return if (next == today) "On now" else "Next: ${next.short()}"
}

/** "18 Dec – 26 Dec" (or one day). */
internal fun spanText(span: Pair<Day, Day>) = if (span.first == span.second) span.first.short() else "${span.first.short()} – ${span.second.short()}"

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
    var editingDates by remember { mutableStateOf<Occasion?>(null) }

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
            val dates = fx.datesOf(o)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    selected = false, enabled = fx.on && fx.seasonal,
                    onClick = { update { it.with(o, !it.isOn(o)) } },
                    headlineContent = { Text(o.label) },
                    // The dates it falls on (the user's own, when set) and what it shows.
                    supportingContent = {
                        Text(
                            (if (dates == null) o.usual else "Your dates: ${spanText(Occasions.current(o, today, dates))}") +
                                ": ${o.look} · ${whenNext(o, today, dates)}",
                        )
                    },
                    trailingContent = { Switch(checked = fx.isOn(o), onCheckedChange = null) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { editingDates = o }) { Text("Dates") }
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
    editingDates?.let { o ->
        OccasionDatesDialog(o, fx.datesOf(o), today, onSave = { d -> update { it.withDates(o, d) }; editingDates = null }, onDismiss = { editingDates = null })
    }
}

/**
 * When an occasion shows: its usual dates (moving ones included: Easter, Thanksgiving, Labor Day),
 * started earlier or ended later by some days, or the user's own dates every year (which may run on
 * past New Year). Shows the dates it'll actually cover next as the choices change.
 */
@Composable
internal fun OccasionDatesDialog(o: Occasion, current: OccasionDates?, today: Day, onSave: (OccasionDates?) -> Unit, onDismiss: () -> Unit) {
    val usualSpan = remember(o) { Occasions.current(o, today) }
    var own by remember { mutableStateOf(current?.own == true) }
    var earlier by remember { mutableStateOf(current?.takeIf { !it.own }?.earlier ?: 0) }
    var later by remember { mutableStateOf(current?.takeIf { !it.own }?.later ?: 0) }
    var from by remember { mutableStateOf(current?.from ?: usualSpan.first.monthDay) }
    var to by remember { mutableStateOf(current?.to ?: usualSpan.second.monthDay) }
    val chosen = if (own) OccasionDates(from, to) else OccasionDates(earlier = earlier, later = later)
    val first = remember { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss, width = 720) {
        Text("${o.label}: dates", style = MaterialTheme.typography.headlineSmall)
        Text("Usually ${o.usual}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.tv.material3.FilterChip(selected = !own, onClick = { own = false }, modifier = Modifier.focusRequester(first)) { Text("The usual dates") }
            androidx.tv.material3.FilterChip(selected = own, onClick = { own = true }) { Text("Dates of my own") }
        }
        if (own) {
            DayStepper("From", from) { from = it }
            DayStepper("To", to) { to = it }
            if (to.let { t -> Day(2001, t.month, t.day.coerceAtMost(28)) < Day(2001, from.month, from.day.coerceAtMost(28)) }) {
                Text("Runs on past New Year into the next year.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            CountStepper("Start sooner", earlier, { "$it ${if (it == 1) "day" else "days"} before" }) { earlier = it }
            CountStepper("End later", later, { "$it ${if (it == 1) "day" else "days"} after" }) { later = it }
        }
        Text("Covers: ${spanText(Occasions.current(o, today, chosen))}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.tv.material3.Button(onClick = { onSave(chosen) }) { Text("Save") }
            if (current != null) OutlinedButton(onClick = { onSave(null) }) { Text("Back to the usual dates") }
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** "From  « ‹ 18 Dec › »": a day of the year stepped by days (‹ ›) or months (« »). */
@Composable
private fun DayStepper(label: String, value: MonthDay, onChange: (MonthDay) -> Unit) {
    val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(110.dp))
        OutlinedButton(onClick = { onChange(value.plusMonths(-1)) }) { Text("«", modifier = Modifier.semantics { contentDescription = "$label: a month earlier" }) }
        OutlinedButton(onClick = { onChange(value.plus(-1)) }) { Text("‹", modifier = Modifier.semantics { contentDescription = "$label: a day earlier" }) }
        Text("${value.day} ${months[value.month - 1]}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(170.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange(value.plus(1)) }) { Text("›", modifier = Modifier.semantics { contentDescription = "$label: a day later" }) }
        OutlinedButton(onClick = { onChange(value.plusMonths(1)) }) { Text("»", modifier = Modifier.semantics { contentDescription = "$label: a month later" }) }
    }
}

/** "Start sooner  − 3 days before +": a count of days, 0 up to [OccasionDates.MAX_SHIFT]. */
@Composable
private fun CountStepper(label: String, value: Int, text: (Int) -> String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(160.dp))
        OutlinedButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }, enabled = value > 0) { Text("−", modifier = Modifier.semantics { contentDescription = "$label: one day fewer" }) }
        Text(text(value), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(170.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange((value + 1).coerceAtMost(OccasionDates.MAX_SHIFT)) }, enabled = value < OccasionDates.MAX_SHIFT) { Text("+", modifier = Modifier.semantics { contentDescription = "$label: one day more" }) }
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

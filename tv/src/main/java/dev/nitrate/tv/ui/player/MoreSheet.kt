package dev.nitrate.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.nitrate.core.playback.withSoundMode
import dev.nitrate.core.playback.soundMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.playback.SoundMode
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/** One tab of the More panel: [key] is remembered between openings, [label] is shown. */
internal class MoreTab(val key: String, val label: String, val content: @Composable () -> Unit)

/**
 * "More": a panel across the bottom of the player, the video still showing above. Tabs along the
 * top (◀▶ moves between them, a tab shows as soon as it's focused), one tab's content below (▼ goes
 * into it, ▲ back to the tabs). Opens on [initial] (the tab used last), else the first.
 */
@Composable
internal fun MoreSheet(tabs: List<MoreTab>, initial: String?, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
    if (tabs.isEmpty()) return
    var current by remember { mutableStateOf(tabs.firstOrNull { it.key == initial }?.key ?: tabs.first().key) }
    val tabFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun focusOf(key: String) = tabFocus.getOrPut(key) { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusOf(current).requestFocus() } }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2101317), Color(0xFA101317))))
            .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.focusRestorer(focusOf(current)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tabs.forEach { tab ->
                TabChip(tab.label, selected = tab.key == current, modifier = Modifier.focusRequester(focusOf(tab.key))) {
                    current = tab.key
                    onTab(tab.key)
                }
            }
            Box(Modifier.weight(1f))
            Text("◀ ▶ tabs   ·   Back closes", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
        }
        // ▲ from the content's top line lands on the tab row, which puts focus on the tab showing.
        Box(Modifier.fillMaxWidth().height(MORE_CONTENT_HEIGHT)) {
            tabs.first { it.key == current }.content()
        }
    }
}

private val MORE_CONTENT_HEIGHT = 228.dp

/** A tab: white when it's the one showing, a bright outline when focused. */
@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    androidx.tv.material3.Surface(
        onClick = onFocus,
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White else Color.White.copy(alpha = 0.10f),
            contentColor = if (selected) Color.Black else Color.White.copy(alpha = 0.8f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary), inset = (-4).dp),
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/**
 * A row of cards that opens on item [start]. With [wrap], ▶ on the last goes round to the first
 * and ◀ on the first to the last (the season's episodes).
 */
@Composable
internal fun CardRow(count: Int, start: Int, wrap: Boolean = false, item: @Composable (index: Int, modifier: Modifier) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start.coerceIn(0, (count - 1).coerceAtLeast(0)))
    val requesters = remember(count) { List(count) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var focusedIndex by remember { mutableStateOf(start) }
    fun goTo(index: Int) {
        scope.launch {
            list.scrollToItem(index)
            awaitFrame()
            runCatching { requesters[index].requestFocus() }
        }
    }
    LazyRow(
        state = list,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
        modifier = Modifier
            .focusRestorer(requesters.getOrElse(start) { FocusRequester.Default })
            .onPreviewKeyEvent { e ->
                if (!wrap || count < 2 || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionRight && focusedIndex == count - 1 -> { goTo(0); true }
                    e.key == Key.DirectionLeft && focusedIndex == 0 -> { goTo(count - 1); true }
                    else -> false
                }
            },
    ) {
        items(count) { i ->
            item(i, Modifier.focusRequester(requesters[i]).onFocusChanged { if (it.isFocused) focusedIndex = i })
        }
    }
}

/**
 * What the Settings tab shows and changes, for this playback. Each default comes from the device's
 * own settings (Settings → Picture & sound) and is marked as such; a change here is for this
 * playback only unless "Make default" saves it there.
 */
internal class PlayerChoices(
    val speed: Float,
    val onSpeed: (Float) -> Unit,
    val sound: SoundMode,
    val defaultSound: SoundMode,
    val onSound: (SoundMode) -> Unit,
    val onSoundAsDefault: () -> Unit,
    val sleepAt: Long?,
    val sleepAtEnd: Boolean,
    val onSleep: (Long?, Boolean) -> Unit,
    val subtitleOffsetMs: Long,
    val onSubtitleOffset: (Long) -> Unit,
    val audioDelayMs: Int,
    val defaultAudioDelayMs: Int,
    val onAudioDelay: (Int) -> Unit,
    val onAudioDelayAsDefault: () -> Unit,
    /** Matching the TV's refresh rate for this playback (null: not on this device). */
    val frameRate: dev.nitrate.core.playback.FrameRateMatch? = null,
    val defaultFrameRate: dev.nitrate.core.playback.FrameRateMatch? = null,
    val onFrameRate: (dev.nitrate.core.playback.FrameRateMatch) -> Unit = {},
    val onFrameRateAsDefault: () -> Unit = {},
    /** What the video's frame rate looks like at the TV's rate now, e.g. "23.976 fps at 59.94 Hz: slight judder". */
    val refreshNote: String? = null,
    /** Pages of their own (Speakers, Second subtitles, Find subtitles, Watch together): label → content. */
    val pages: List<Pair<String, @Composable (onBack: () -> Unit) -> Unit>> = emptyList(),
    /** Saves where the film or episode is now as a moment (Companion `moments`; null without it). */
    val onMarkMoment: (() -> Unit)? = null,
    /** What happened to the last moment marked ("Marked at 1:02:03"). */
    val momentNote: String? = null,
)

internal val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
internal val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

/** The Settings tab: one line per setting, its default marked; bigger things open a page of their own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerChoicesTab(choices: PlayerChoices) {
    var page by remember { mutableStateOf<String?>(null) }
    // The page last opened, so closing it puts focus back on its chip.
    var opened by remember { mutableStateOf<String?>(null) }
    val chipFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun chip(label: String) = chipFocus.getOrPut(label) { FocusRequester() }
    BackHandler(enabled = page != null) { page = null }
    val open = choices.pages.firstOrNull { it.first == page }
    if (open != null) {
        // Into the page as it opens (the chip that had focus is gone with the list); Back (as a key)
        // goes back one step to the list, not out of More.
        val pageFocus = remember { FocusRequester() }
        var inside by remember { mutableStateOf(false) }
        LaunchedEffect(open.first) {
            repeat(30) {
                kotlinx.coroutines.android.awaitFrame()
                if (inside) return@LaunchedEffect
                runCatching { pageFocus.requestFocus() }
                kotlinx.coroutines.delay(30)
            }
        }
        Column(
            Modifier.focusRequester(pageFocus).onFocusChanged { inside = it.hasFocus }.focusGroup()
                .onKeyEvent { e ->
                    if (e.key != Key.Back && e.key != Key.Escape) return@onKeyEvent false
                    if (e.type == KeyEventType.KeyUp) page = null
                    true
                }
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            open.second { page = null }
        }
        return
    }
    // Back from a page: focus to the chip that opened it.
    LaunchedEffect(opened, page) {
        val label = opened?.takeIf { page == null } ?: return@LaunchedEffect
        repeat(20) {
            kotlinx.coroutines.android.awaitFrame()
            if (runCatching { chip(label).requestFocus() }.isSuccess) { opened = null; return@LaunchedEffect }
            kotlinx.coroutines.delay(30)
        }
    }
    val scroll = rememberScrollState()
    val first = remember { FocusRequester() }
    Column(
        // ▼ from the tabs lands on the speed that's on (not whichever chip is nearest).
        // (Not while coming back from a page: focus goes to that page's chip, not the speed.)
        Modifier.then(if (opened == null) Modifier.focusRestorer(first) else Modifier).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Line("Speed", if (choices.speed == 1f) "Normal" else "${choices.speed}×") {
            SPEEDS.forEach { s ->
                DefaultChip(if (s == 1f) "Normal" else "$s×", selected = choices.speed == s, isDefault = s == 1f,
                    modifier = if (s == choices.speed || (s == 1f && choices.speed !in SPEEDS)) Modifier.focusRequester(first) else Modifier) { choices.onSpeed(s) }
            }
        }
        Line("Sound", choices.sound.label) {
            SoundMode.entries.forEach { m ->
                DefaultChip(m.label, selected = choices.sound == m, isDefault = m == choices.defaultSound) { choices.onSound(m) }
            }
            if (choices.sound != choices.defaultSound) MakeDefault(choices.onSoundAsDefault)
        }
        choices.onMarkMoment?.let { mark ->
            Line("This moment") {
                DefaultChip("Mark this moment", selected = false, isDefault = false) { mark() }
                choices.momentNote?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        Line("Sleep timer", when { choices.sleepAtEnd -> "End of this"; choices.sleepAt != null -> "On"; else -> "Off" }) {
            DefaultChip("Off", selected = choices.sleepAt == null && !choices.sleepAtEnd, isDefault = true) { choices.onSleep(null, false) }
            SLEEP_MINUTES.forEach { m -> DefaultChip("$m min", selected = false, isDefault = false) { choices.onSleep(System.currentTimeMillis() + m * 60_000L, false) } }
            DefaultChip("End of this", selected = choices.sleepAtEnd, isDefault = false) { choices.onSleep(null, true) }
            choices.sleepAt?.let {
                Text("stops at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)),
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        Line("Subtitle timing", if (choices.subtitleOffsetMs == 0L) "In sync" else "${if (choices.subtitleOffsetMs > 0) "+" else "−"}${kotlin.math.abs(choices.subtitleOffsetMs) / 1000.0}s") {
            listOf(-1000L, -250L, 250L, 1000L).forEach { d ->
                DefaultChip((if (d > 0) "+" else "−") + "${kotlin.math.abs(d) / 1000.0}s", selected = false, isDefault = false) { choices.onSubtitleOffset(choices.subtitleOffsetMs + d) }
            }
            DefaultChip(if (choices.subtitleOffsetMs == 0L) "In sync" else "${if (choices.subtitleOffsetMs > 0) "+" else "−"}${kotlin.math.abs(choices.subtitleOffsetMs) / 1000.0}s · reset",
                selected = choices.subtitleOffsetMs == 0L, isDefault = true) { choices.onSubtitleOffset(0) }
        }
        Line("Audio sync", dev.nitrate.core.playback.AudioSync.label(choices.audioDelayMs)) {
            dev.nitrate.core.playback.AudioSync.STEPS.forEach { d ->
                DefaultChip((if (d > 0) "+" else "−") + "${kotlin.math.abs(d)} ms", selected = false, isDefault = false) { choices.onAudioDelay(choices.audioDelayMs + d) }
            }
            DefaultChip(dev.nitrate.core.playback.AudioSync.label(choices.defaultAudioDelayMs), selected = choices.audioDelayMs == choices.defaultAudioDelayMs, isDefault = true) {
                choices.onAudioDelay(choices.defaultAudioDelayMs)
            }
            if (choices.audioDelayMs != choices.defaultAudioDelayMs) {
                Text("now " + dev.nitrate.core.playback.AudioSync.label(choices.audioDelayMs), style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterVertically))
                MakeDefault(choices.onAudioDelayAsDefault)
            }
        }
        val frameRate = choices.frameRate
        if (frameRate != null) {
            // Named as in Settings → Picture & sound.
            Line("Match frame rate", frameRate.shortLabel) {
                dev.nitrate.core.playback.FrameRateMatch.entries.forEach { m ->
                    DefaultChip(m.shortLabel, selected = frameRate == m, isDefault = m == choices.defaultFrameRate) { choices.onFrameRate(m) }
                }
                if (frameRate != choices.defaultFrameRate) MakeDefault(choices.onFrameRateAsDefault)
                choices.refreshNote?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        if (choices.pages.isNotEmpty()) {
            Line("More") {
                choices.pages.forEach { (label, _) -> DefaultChip("$label  ›", selected = false, isDefault = false, modifier = Modifier.focusRequester(chip(label))) { opened = label; page = label } }
            }
        }
    }
}

/**
 * A setting's line, read like a Settings row: its name with what's on now under it (as Settings
 * shows a value beside each setting), then its choices.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Line(title: String, value: String? = null, content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.width(170.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.85f))
            if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/**
 * A choice. [selected] is what's on now; [isDefault] marks the device's own setting with a small
 * "DEFAULT" tag, so it's clear what this playback started from and what "Make default" would change.
 */
@Composable
private fun DefaultChip(label: String, selected: Boolean, isDefault: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label)
            if (isDefault) {
                Text(
                    "DEFAULT", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Saves what's on now as the device's default (Settings → Picture & sound). */
@Composable
private fun MakeDefault(onClick: () -> Unit) {
    androidx.tv.material3.OutlinedButton(onClick = onClick, modifier = Modifier.padding(start = 8.dp)) { Text("Make default") }
}

/**
 * The More panel's tabs for what's playing: its episodes (or the rest of its collection), chapters,
 * cast, extras, then Settings. Tabs with nothing in them are left out.
 */
@Composable
internal fun moreTabs(
    current: dev.nitrate.core.model.MediaItem?,
    titleStrips: List<TitleStrip>,
    cast: List<dev.nitrate.core.model.Person>,
    chapterImage: (Int) -> String?,
    personImage: (dev.nitrate.core.model.Person) -> String?,
    position: Long,
    onChapter: (Long) -> Unit,
    onPerson: (dev.nitrate.core.model.Person) -> Unit,
    choices: PlayerChoices,
): List<MoreTab> {
    val chapters = current?.chapters.orEmpty()
    val extras = titleStrips.firstOrNull { it.label == EXTRAS }
    val siblings = titleStrips.firstOrNull { it.label != EXTRAS }
    return listOfNotNull(
        siblings?.let { strip ->
            // The season goes round: ▶ on its last episode comes back to the first.
            MoreTab("siblings", if (current?.isEpisode == true) "Episodes" else "Collection") {
                CardRow(strip.cards.size, strip.start, wrap = true) { i, modifier -> strip.cards[i].let { c -> TitleCardView(c, onClick = { strip.onClick(c.item) }, modifier = modifier) } }
            }
        },
        chapters.takeIf { it.size > 1 }?.let { list ->
            MoreTab("chapters", "Chapters") {
                val now = dev.nitrate.core.playback.Chapters.current(list, position).coerceAtLeast(0)
                CardRow(list.size, now) { i, modifier ->
                    ChapterCard(i, list[i], dev.nitrate.core.playback.Chapters.title(list, i), chapterImage(i), playing = i == now, onClick = { onChapter(list[i].startMs) }, modifier = modifier)
                }
            }
        },
        cast.takeIf { it.isNotEmpty() }?.let { people ->
            MoreTab("cast", "Cast") {
                CardRow(people.size, 0) { i, modifier -> CastCard(people[i], personImage(people[i]), onClick = { onPerson(people[i]) }, modifier = modifier) }
            }
        },
        extras?.let { strip ->
            MoreTab("extras", "Extras") {
                CardRow(strip.cards.size, 0) { i, modifier -> strip.cards[i].let { c -> TitleCardView(c, onClick = { strip.onClick(c.item) }, modifier = modifier) } }
            }
        },
        MoreTab("settings", "Settings") { PlayerChoicesTab(choices) },
    )
}

/** The [TitleStrip] label of a title's extras. */
internal const val EXTRAS = "Extras"

/**
 * The Settings tab's state for this playback: what's on in [session], the defaults from the device's
 * own settings, and the pages of their own (speakers, second subtitles, finding subtitles, watching
 * together). Changes are for this playback; "Make default" writes them to the device's settings.
 */
@Composable
internal fun rememberPlayerChoices(
    session: dev.nitrate.android.playback.PlaybackSession,
    settings: dev.nitrate.core.playback.PlaybackSettingsRepository,
    repository: dev.nitrate.core.MediaServer,
    sleepAt: Long?,
    sleepAtEnd: Boolean,
    syncPlay: dev.nitrate.core.syncplay.SyncPlayController?,
    onSleep: (Long?, Boolean) -> Unit,
    onDone: () -> Unit,
    /** This playback's refresh-rate choice (null: the device's own) and how to change it. */
    frameRate: dev.nitrate.core.playback.FrameRateMatch? = null,
    onFrameRate: (dev.nitrate.core.playback.FrameRateMatch) -> Unit = {},
    /** The TV's refresh rate now (null: unknown). */
    displayHz: Float? = null,
): PlayerChoices {
    val scope = rememberCoroutineScope()
    val saved by settings.settings.collectAsState(dev.nitrate.core.playback.PlaybackSettings())
    val speed by session.speed.collectAsState()
    val sound by session.soundMode.collectAsState()
    val offset by session.subtitleOffsetMs.collectAsState()
    val audioDelay by session.audioDelayMs.collectAsState()
    val output by session.soundOutput.collectAsState()
    val plan by session.plan.collectAsState()
    val second by session.secondSubtitle.collectAsState()
    val seconds = plan?.let { dev.nitrate.core.playback.SecondSubtitles.choices(it.source, it.subtitleIndex) }.orEmpty()
    val pages = buildList<Pair<String, @Composable (() -> Unit) -> Unit>> {
        if (output?.stereoOnly == false) add("Speakers" to { _ -> SpeakersSettings(saved, output) { f -> scope.launch { settings.update(f) } } })
        if (seconds.isNotEmpty()) add("Second subtitles" to { _ ->
            Text("Second subtitles (at the top)", style = MaterialTheme.typography.titleSmall)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = second == null, onClick = { session.setSecondSubtitle(null) }) { Text("Off") }
                seconds.forEach { c -> FilterChip(selected = second?.index == c.index, onClick = { session.setSecondSubtitle(c.index) }) { Text(c.title + (c.detail?.let { " · $it" } ?: "")) } }
            }
        })
        add("Find subtitles" to { back -> SubtitleSearch(repository, session, onDone = { lang -> session.reloadWithNewSubtitle(lang); onDone() }, onBack = back) })
        if (syncPlay != null) add("Watch together" to { _ -> dev.nitrate.tv.ui.together.WatchTogetherPanel(syncPlay, "Movie night") })
    }
    // Moments need the Companion plugin: asked once as More opens.
    val canMark by androidx.compose.runtime.produceState(false, repository) {
        value = runCatching { repository.companion()?.features?.contains("moments") == true }.getOrDefault(false)
    }
    var momentNote by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    return PlayerChoices(
        onMarkMoment = if (!canMark) null else {
            {
                val itemId = plan?.itemId
                val at = session.player.currentPosition
                if (itemId != null) scope.launch {
                    momentNote = runCatching { repository.addMoment(itemId, at, null) }.getOrNull()
                        ?.let { "Marked at " + dev.nitrate.core.companion.Moments.clock(it.positionMs) } ?: "Couldn't mark it"
                }
            }
        },
        momentNote = momentNote,
        speed = speed,
        onSpeed = session::setSpeed,
        sound = sound,
        defaultSound = saved.soundMode,
        onSound = session::setSoundMode,
        onSoundAsDefault = { scope.launch { settings.update { it.withSoundMode(sound) } } },
        sleepAt = sleepAt,
        sleepAtEnd = sleepAtEnd,
        onSleep = onSleep,
        subtitleOffsetMs = offset,
        onSubtitleOffset = session::setSubtitleOffset,
        audioDelayMs = audioDelay,
        defaultAudioDelayMs = saved.audioDelayMs,
        onAudioDelay = { session.setAudioDelay(it) },
        onAudioDelayAsDefault = { scope.launch { settings.update { it.copy(audioDelayMs = audioDelay) } } },
        frameRate = frameRate ?: saved.frameRateMatch,
        defaultFrameRate = saved.frameRateMatch,
        onFrameRate = onFrameRate,
        onFrameRateAsDefault = { val m = frameRate ?: saved.frameRateMatch; scope.launch { settings.update { it.copy(frameRateMatch = m) } } },
        refreshNote = refreshNote(session.videoFrameRate.collectAsState().value, displayHz),
        pages = pages,
    )
}

/** "23.976 fps at 59.94 Hz: slight judder" and the like, for the Refresh rate line. */
internal fun refreshNote(fps: Float?, hz: Float?): String? {
    if (fps == null || hz == null) return null
    fun f(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')
    val what = when (dev.nitrate.core.playback.DisplayModes.cadence(fps, hz)) {
        dev.nitrate.core.playback.DisplayModes.Cadence.EXACT -> "smooth"
        dev.nitrate.core.playback.DisplayModes.Cadence.NEAR -> "nearly smooth"
        dev.nitrate.core.playback.DisplayModes.Cadence.JUDDER -> "slight judder"
        dev.nitrate.core.playback.DisplayModes.Cadence.UNKNOWN -> return null
    }
    return "${f(fps)} fps at ${f(hz)} Hz: $what"
}


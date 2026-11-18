package dev.nitrate.tv.ui.preroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.poetry.Poems
import dev.nitrate.core.preroll.PrerollPrefs
import dev.nitrate.core.preroll.PrerollScene
import dev.nitrate.core.preroll.PrerollSource
import dev.nitrate.core.preroll.PrerollStep
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.launch

/**
 * Settings → While watching → Before it plays: whether anything plays before titles, for films and/or
 * episodes, and what: a verse (or a line of your own) on black, a scene, the server's intros, your own
 * pre-roll videos. Each verse and scene can be previewed.
 */
@Composable
fun PrerollSettingsScreen(container: AppContainer) {
    val prefs = LocalDisplayPrefs.current
    val pre = prefs.prerolls
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val user = container.server.userId
    val profileName by remember(user) { user?.let { container.displaySettings.activeProfileName(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val shipped = remember { shippedClips(context) }
    fun update(change: (PrerollPrefs) -> PrerollPrefs) {
        val u = container.server.userId ?: return
        scope.launch { container.displaySettings.savePrefs(u, prefs.copy(prerolls = change(prefs.prerolls))) }
    }
    var preview by remember { mutableStateOf<PrerollStep?>(null) }
    var choosingVideos by remember { mutableStateOf(false) }
    var addingLine by remember { mutableStateOf(false) }

    @Composable
    fun Toggle(label: String, detail: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, flip: () -> Unit) = ListItem(
        selected = false, enabled = enabled, onClick = flip,
        headlineContent = { Text(label) }, supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) }, modifier = modifier,
    )
    @Composable
    fun Choice(label: String, detail: String, value: String, enabled: Boolean = true, next: () -> Unit) = ListItem(
        selected = false, enabled = enabled, onClick = next,
        headlineContent = { Text(label) }, supportingContent = { Text(detail) },
        trailingContent = { Text("$value  ›", color = MaterialTheme.colorScheme.primary) },
    )
    @Composable
    fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

    val live = pre.on
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before it plays", style = MaterialTheme.typography.displaySmall)
                dev.nitrate.tv.ui.settings.ScopeTag(dev.nitrate.core.settings.SettingScope.PROFILE, profileName)
                Text("Only when a title starts from the beginning, never when resuming. OK skips what's showing; Back goes straight to the title.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Toggle("Play something first", "A verse, a scene, intros: whatever is switched on below", pre.on, Modifier.focusRequester(first)) { update { it.copy(on = !it.on) } } }
        item { Toggle("Before films", "Every film started from the top", pre.films, enabled = live) { update { it.copy(films = !it.films) } } }
        item { Toggle("Before episodes", "The first episode of a sitting only (not again for 3 hours)", pre.episodes, enabled = live) { update { it.copy(episodes = !it.episodes) } } }

        item { Heading("On black") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    selected = false, enabled = live, onClick = { update { it.copy(verses = !it.verses) } },
                    headlineContent = { Text("A verse") },
                    supportingContent = { Text("${Poems.all.size} classical Japanese tanka from the Hyakunin Isshu, in William N. Porter's 1909 translation, fading in and out") },
                    trailingContent = { Switch(checked = pre.verses, onCheckedChange = null) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { preview = PrerollStep.Verse(Poems.pick(kotlin.random.Random.Default)) }) { Text("Preview") }
            }
        }
        item { Choice("How long", "The verse or line, fades included", "${pre.verseSeconds} s", enabled = live) { update { it.copy(verseSeconds = listOf(8, 12, 16, 20).let { l -> l[(l.indexOf(it.verseSeconds) + 1).mod(l.size)] }) } } }
        itemsIndexed(pre.ownLines) { i, line ->
            ListItem(
                selected = false, enabled = live, onClick = { update { it.copy(ownLines = it.ownLines.filterIndexed { j, _ -> j != i }) } },
                headlineContent = { Text(line, maxLines = 2) }, supportingContent = { Text("Your line · OK removes it") },
                trailingContent = { Text("✕", color = MaterialTheme.colorScheme.primary) },
            )
        }
        item {
            ListItem(
                selected = false, enabled = live, onClick = { addingLine = true },
                headlineContent = { Text("Add a line of your own") },
                supportingContent = { Text("A quote or a few words, shown the same way and mixed in with the verses") },
                trailingContent = { Text("+", color = MaterialTheme.colorScheme.primary) },
            )
        }

        item { Heading("A scene") }
        item { Choice("How long", "Each scene, fades included", "${pre.sceneSeconds} s", enabled = live) { update { it.copy(sceneSeconds = listOf(8, 12, 20, 30).let { l -> l[(l.indexOf(it.sceneSeconds) + 1).mod(l.size)] }) } } }
        items(PrerollScene.entries, key = { it.name }) { scene ->
            val hasClip = scene.clip in shipped
            val usable = hasClip || scene.drawn
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    selected = false, enabled = live && usable,
                    onClick = { update { it.copy(scenes = if (scene in it.scenes) it.scenes - scene else it.scenes + scene) } },
                    headlineContent = { Text(scene.label) },
                    supportingContent = { Text("${scene.detail} · " + when { hasClip -> "film"; scene.drawn -> "drawn"; else -> "needs its film clip, not in this build" }) },
                    trailingContent = { Switch(checked = scene in pre.scenes, onCheckedChange = null) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { preview = PrerollStep.Scene(scene) }, enabled = usable) { Text("Preview") }
            }
        }

        item { Heading("Videos") }
        item { Toggle("The server's intros", "Trailers and pre-rolls your server offers before a title (a cinema-mode plugin on it)", pre.serverIntros, enabled = live) { update { it.copy(serverIntros = !it.serverIntros) } } }
        item { Choice("At most", "How many of the server's intros", "${pre.maxIntros}", enabled = live && pre.serverIntros) { update { it.copy(maxIntros = it.maxIntros % 3 + 1) } } }
        item { Choice("Your own pre-roll videos", "One at random from a playlist or collection on your server", pre.ownVideos?.name ?: "None", enabled = live) { choosingVideos = true } }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    preview?.let { step ->
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            PrerollScreen(listOf(step), pre) { preview = null }
        }
    }
    if (addingLine) {
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        TvDialog(onDismiss = { addingLine = false }, width = 620) {
            Text("A line of your own", style = MaterialTheme.typography.headlineSmall)
            Text("Shown on black before titles. A new line starts with \" / \".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvTextField(value = text, onValueChange = { text = it }, placeholder = "Words to begin with", modifier = Modifier.focusRequester(focus))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val line = text.split(" / ").joinToString("\n") { it.trim() }.trim()
                    if (line.isNotEmpty()) update { it.copy(ownLines = it.ownLines + line) }
                    addingLine = false
                }) { Text("Add") }
                OutlinedButton(onClick = { addingLine = false }) { Text("Cancel") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
    if (choosingVideos) {
        val sources by produceState<List<PrerollSource>?>(null) {
            val server = container.server
            val playlists = runCatching { server.playlists() }.getOrDefault(emptyList()).map { PrerollSource(it.id, it.name.orEmpty(), playlist = true) }
            val collections = runCatching { server.collections() }.getOrDefault(emptyList()).map { PrerollSource(it.id, it.name.orEmpty(), playlist = false) }
            value = playlists + collections
        }
        val focus = remember { FocusRequester() }
        TvDialog(onDismiss = { choosingVideos = false }, width = 620) {
            Text("Your own pre-roll videos", style = MaterialTheme.typography.headlineSmall)
            ListItem(selected = pre.ownVideos == null, onClick = { update { it.copy(ownVideos = null) }; choosingVideos = false }, headlineContent = { Text("None") }, modifier = Modifier.focusRequester(focus))
            val list = sources
            if (list == null) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else list.forEach { src ->
                ListItem(
                    selected = pre.ownVideos?.id == src.id,
                    onClick = { update { it.copy(ownVideos = src) }; choosingVideos = false },
                    headlineContent = { Text(src.name) },
                    supportingContent = { Text(if (src.playlist) "Playlist" else "Collection") },
                )
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

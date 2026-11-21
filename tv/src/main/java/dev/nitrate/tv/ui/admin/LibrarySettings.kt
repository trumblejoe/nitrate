package dev.nitrate.tv.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.admin.LibraryFetcher
import dev.nitrate.core.admin.LibraryMetadata
import dev.nitrate.core.admin.LibraryMetadataSettings
import dev.nitrate.core.admin.MetadataPlace
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun typeLabel(type: String) = when (type) {
    "Movie" -> "Films"; "Series" -> "Shows"; "Season" -> "Seasons"; "Episode" -> "Episodes"
    "MusicAlbum" -> "Albums"; "MusicArtist" -> "Artists"; "Audio" -> "Songs"; "MusicVideo" -> "Music videos"
    "BoxSet" -> "Collections"; "Book" -> "Books"; "Video" -> "Videos"; "Person" -> "People"
    else -> type
}

/**
 * Manage server → Libraries (admins): each library's metadata settings, as the dashboard's
 * Manage library has them: language and country, artwork and NFO files beside the media, how
 * often it refreshes, and which providers each kind of item asks, in order.
 */
@Composable
internal fun LibrariesTab(repository: MediaServer, notify: (String) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val libraries by produceState<Result<List<LibraryMetadataSettings>>?>(null, reload) { value = runCatching { repository.libraryMetadataSettings() } }
    var editing by remember { mutableStateOf<LibraryMetadataSettings?>(null) }
    editing?.let { e ->
        return LibraryEditor(repository, e, onDone = { saved -> editing = null; if (saved) { notify("Saved. New settings apply from the next refresh."); reload++ } }, notify = notify)
    }
    val list = libraries?.getOrNull()
    when {
        libraries == null -> Text("Loading…")
        list == null -> Text("Couldn't read the libraries: ${libraries?.exceptionOrNull()?.message}")
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Text("Where each library's details and artwork come from: the same settings as the dashboard's Manage library.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.libraryId }) { lib ->
                ListItem(
                    selected = false, onClick = { editing = lib },
                    headlineContent = { Text(lib.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                lib.language?.let { "Language $it" } ?: "Server's language", lib.country,
                                if (lib.refreshDays > 0) "refreshes every ${lib.refreshDays} days" else "no automatic refresh",
                                "NFO files".takeIf { lib.nfo == true }, "artwork in media folders".takeIf { lib.saveLocal },
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = { Text("›") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryEditor(repository: MediaServer, start: LibraryMetadataSettings, onDone: (Boolean) -> Unit, notify: (String) -> Unit) {
    var s by remember { mutableStateOf(start) }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    val languages by produceState(emptyList<MetadataPlace>()) { value = runCatching { repository.metadataLanguages() }.getOrDefault(emptyList()) }
    val countries by produceState(emptyList<MetadataPlace>()) { value = runCatching { repository.metadataCountries() }.getOrDefault(emptyList()) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onDone(false) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(enabled = !saving, modifier = Modifier.focusRequester(first), onClick = {
                    saving = true
                    scope.launch {
                        runCatching { repository.saveLibraryMetadataSettings(s) }
                            .onSuccess { onDone(true) }
                            .onFailure { notify("Couldn't save: ${it.message}"); saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
                OutlinedButton(onClick = { onDone(false) }) { Text("Cancel") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Language and country:")
                OutlinedButton(onClick = { picking = "language" }) { Text(languages.firstOrNull { it.code == s.language }?.name ?: s.language ?: "Server's language") }
                OutlinedButton(onClick = { picking = "country" }) { Text(countries.firstOrNull { it.code == s.country }?.name ?: s.country ?: "Server's country") }
            }
        }
        item {
            ListItem(selected = false, onClick = { s = s.copy(saveLocal = !s.saveLocal) }, headlineContent = { Text("Save artwork into media folders") },
                supportingContent = { Text("Images kept beside the films and shows, so they survive a reinstall and other apps see them.") },
                trailingContent = { Switch(checked = s.saveLocal, onCheckedChange = null) })
        }
        s.nfo?.let { nfo ->
            item {
                ListItem(selected = false, onClick = { s = s.copy(nfo = !nfo) }, headlineContent = { Text("Write NFO files") },
                    supportingContent = { Text("Details in .nfo files beside the media, for Kodi and other media managers.") },
                    trailingContent = { Switch(checked = nfo, onCheckedChange = null) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Refresh automatically:")
                LibraryMetadataSettings.REFRESH_DAYS.forEach { d -> FilterChip(selected = s.refreshDays == d, onClick = { s = s.copy(refreshDays = d) }) { Text(if (d == 0) "Never" else "Every $d days") } }
            }
        }
        s.types.forEachIndexed { ti, t ->
            item(key = "type-${t.type}") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(typeLabel(t.type), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        if (t.metadata.isNotEmpty()) FetcherList("Details from (first answer wins)", t.metadata) { list -> s = s.copy(types = s.types.mapIndexed { i, x -> if (i == ti) x.copy(metadata = list) else x }) }
                        if (t.images.isNotEmpty()) FetcherList("Images from", t.images) { list -> s = s.copy(types = s.types.mapIndexed { i, x -> if (i == ti) x.copy(images = list) else x }) }
                    }
                }
            }
        }
    }
    picking?.let { what ->
        val options = if (what == "language") languages else countries
        val top = remember { FocusRequester() }
        TvDialog({ picking = null }, width = 560) {
            Text(if (what == "language") "Metadata language" else "Country", style = MaterialTheme.typography.headlineSmall)
            LazyColumn(Modifier.height(420.dp)) {
                item {
                    ListItem(selected = false, onClick = { s = if (what == "language") s.copy(language = null) else s.copy(country = null); picking = null },
                        headlineContent = { Text("The server's own") }, modifier = Modifier.focusRequester(top))
                }
                items(options, key = { it.code }) { o ->
                    ListItem(selected = o.code == (if (what == "language") s.language else s.country), onClick = {
                        s = if (what == "language") s.copy(language = o.code) else s.copy(country = o.code); picking = null
                    }, headlineContent = { Text("${o.name} (${o.code})") })
                }
            }
            LaunchedEffect(Unit) { delay(80); runCatching { top.requestFocus() } }
        }
    }
}

/** Providers in order: OK switches one on or off, ▲/▼ buttons move it. */
@Composable
private fun FetcherList(title: String, list: List<LibraryFetcher>, onChange: (List<LibraryFetcher>) -> Unit) {
    Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEachIndexed { i, f ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = f.enabled, onClick = { onChange(LibraryMetadata.toggle(list, i)) }, modifier = Modifier.weight(1f)) { Text((if (f.enabled) "✓ " else "") + f.name) }
                OutlinedButton(enabled = i > 0, onClick = { onChange(LibraryMetadata.move(list, i, -1)) }) { Text("▲") }
                OutlinedButton(enabled = i < list.lastIndex, onClick = { onChange(LibraryMetadata.move(list, i, 1)) }) { Text("▼") }
            }
        }
    }
}

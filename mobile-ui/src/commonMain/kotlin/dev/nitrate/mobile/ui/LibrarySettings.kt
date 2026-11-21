package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.admin.LibraryFetcher
import dev.nitrate.core.admin.LibraryMetadata
import dev.nitrate.core.admin.LibraryMetadataSettings
import dev.nitrate.core.admin.MetadataPlace
import kotlinx.coroutines.launch

/** The words Jellyfin's item kinds go by in a library's settings. */
internal fun typeLabel(type: String) = when (type) {
    "Movie" -> "Films"; "Series" -> "Shows"; "Season" -> "Seasons"; "Episode" -> "Episodes"
    "MusicAlbum" -> "Albums"; "MusicArtist" -> "Artists"; "Audio" -> "Songs"; "MusicVideo" -> "Music videos"
    "BoxSet" -> "Collections"; "Book" -> "Books"; "Video" -> "Videos"; "Person" -> "People"
    else -> type
}

/**
 * Manage server → Libraries (admins): each library's metadata settings, as Jellyfin's dashboard
 * has them under Manage library: language and country, where artwork and NFO files go, how often
 * it refreshes, and which providers each kind of item asks, in order.
 */
@Composable
internal fun LibrariesPane(actions: AppActions, notify: (String) -> Unit) {
    var reload by remember { mutableStateOf(0) }
    val libraries by produceState<Result<List<LibraryMetadataSettings>>?>(null, reload) { value = runCatching { actions.server.libraryMetadataSettings() } }
    var editing by remember { mutableStateOf<LibraryMetadataSettings?>(null) }
    val e = editing
    if (e != null) return LibraryEditor(actions, e, onDone = { saved -> editing = null; if (saved) { notify("Saved. New settings apply from the next refresh."); reload++ } }, notify = notify)
    val list = libraries?.getOrNull()
    when {
        libraries == null -> Loading()
        list == null -> ErrorMessage("Couldn't read the libraries: ${libraries?.exceptionOrNull()?.message}")
        else -> LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Where each library's details and artwork come from. Rarely changed; the same settings as the dashboard's Manage library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.libraryId }) { lib ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { editing = lib }.padding(14.dp),
                ) {
                    Text(lib.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            lib.language?.let { "Language $it" } ?: "Server's language", lib.country,
                            if (lib.refreshDays > 0) "refreshes every ${lib.refreshDays} days" else "no automatic refresh",
                            "NFO files".takeIf { lib.nfo == true }, "artwork in media folders".takeIf { lib.saveLocal },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryEditor(actions: AppActions, start: LibraryMetadataSettings, onDone: (Boolean) -> Unit, notify: (String) -> Unit) {
    var s by remember { mutableStateOf(start) }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    val languages by produceState(emptyList<MetadataPlace>()) { value = runCatching { actions.server.metadataLanguages() }.getOrDefault(emptyList()) }
    val countries by produceState(emptyList<MetadataPlace>()) { value = runCatching { actions.server.metadataCountries() }.getOrDefault(emptyList()) }
    val scope = rememberCoroutineScope()
    dev.nitrate.mobile.platform.PlatformBackHandler { onDone(false) }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDone(false) }) { Text("Cancel") }
                Button(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        runCatching { actions.server.saveLibraryMetadataSettings(s) }
                            .onSuccess { onDone(true) }
                            .onFailure { notify("Couldn't save: ${it.message}"); saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Metadata language and country", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = "language" }) { Text(languages.firstOrNull { it.code == s.language }?.name ?: s.language ?: "Server's language") }
                    OutlinedButton(onClick = { picking = "country" }) { Text(countries.firstOrNull { it.code == s.country }?.name ?: s.country ?: "Server's country") }
                }
                Text("Titles, descriptions and the images Nitrate looks for first come in this language; the country decides age ratings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SwitchLine("Save artwork into media folders", "Images (and metadata) are kept beside the films and shows, so they survive a reinstall and other apps see them.", s.saveLocal) { s = s.copy(saveLocal = it) }
        }
        s.nfo?.let { nfo -> item { SwitchLine("Write NFO files", "Details in .nfo files beside the media, for Kodi and other media managers.", nfo) { s = s.copy(nfo = it) } } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Refresh metadata automatically", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryMetadataSettings.REFRESH_DAYS.forEach { d -> FilterChip(s.refreshDays == d, { s = s.copy(refreshDays = d) }, label = { Text(if (d == 0) "Never" else "Every $d days") }) }
                }
            }
        }
        s.types.forEachIndexed { ti, t ->
            item(key = "type-${t.type}") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(typeLabel(t.type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (t.metadata.isNotEmpty()) FetcherList("Details from (first answer wins)", t.metadata) { list -> s = s.copy(types = s.types.mapIndexed { i, x -> if (i == ti) x.copy(metadata = list) else x }) }
                    if (t.images.isNotEmpty()) FetcherList("Images from", t.images) { list -> s = s.copy(types = s.types.mapIndexed { i, x -> if (i == ti) x.copy(images = list) else x }) }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
    picking?.let { what ->
        val options = if (what == "language") languages else countries
        var filter by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (what == "language") "Metadata language" else "Country") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("Find") }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            Text("The server's own", Modifier.fillMaxWidth().clickable {
                                s = if (what == "language") s.copy(language = null) else s.copy(country = null); picking = null
                            }.padding(vertical = 10.dp), color = accentColor())
                        }
                        items(options.filter { filter.isBlank() || it.name.contains(filter, true) || it.code.equals(filter, true) }, key = { it.code }) { o ->
                            Text("${o.name} (${o.code})", Modifier.fillMaxWidth().clickable {
                                s = if (what == "language") s.copy(language = o.code) else s.copy(country = o.code); picking = null
                            }.padding(vertical = 10.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchLine(title: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, onChange)
    }
}

/** Providers in order, each on or off, moved with the arrows. */
@Composable
private fun FetcherList(title: String, list: List<LibraryFetcher>, onChange: (List<LibraryFetcher>) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEachIndexed { i, f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(f.enabled, { onChange(LibraryMetadata.toggle(list, i)) })
                Text(f.name, Modifier.weight(1f).padding(start = 10.dp))
                TextButton(enabled = i > 0, onClick = { onChange(LibraryMetadata.move(list, i, -1)) }) { Text("▲") }
                TextButton(enabled = i < list.lastIndex, onClick = { onChange(LibraryMetadata.move(list, i, 1)) }) { Text("▼") }
            }
        }
    }
}

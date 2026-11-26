package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.admin.ImageFlawKind
import dev.nitrate.core.admin.ImageGuide
import dev.nitrate.core.admin.ImageOverviewAsk
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.bytesLabel
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

private val IMAGE_LIBRARIES = setOf(CollectionKind.MOVIES, CollectionKind.TV_SHOWS, CollectionKind.MIXED, CollectionKind.HOME_VIDEOS, CollectionKind.MUSIC_VIDEOS, CollectionKind.BOX_SETS)
private val Amber = Color(0xFFFFC266)

/**
 * Settings → Tools → Library images overview (admins, Companion feature `imagesurvey`): every
 * title's images, the space they take, and which are missing, small, the wrong shape or gone.
 * A kind of picture narrows the list to it, a kind of title to what it's missing; each title opens
 * Manage on Images.
 */
@Composable
fun ImagesOverviewScreen(container: AppContainer, onManage: (String) -> Unit, onFixRun: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val companion by container.companion.collectAsState()
    val available = companion?.features?.contains(ImageGuide.FEATURE) == true
    val libraries by produceState(emptyList<MediaItem>()) {
        value = runCatching { container.server.views() }.getOrDefault(emptyList()).filter { it.collection in IMAGE_LIBRARIES }
    }
    val kept = container.lastImageOverview
    var libraryId by remember { mutableStateOf(kept?.first?.libraryId) }
    var only by remember { mutableStateOf(kept?.first?.only) }
    var onlyType by remember { mutableStateOf(kept?.first?.onlyType) }
    var overview by remember { mutableStateOf(kept?.second) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf(setOf<String>()) }
    // "Find missing for these": how far the asking has got.
    var finding by remember { mutableStateOf<String?>(null) }
    fun findMissing(ids: List<String>) {
        scope.launch {
            ids.forEachIndexed { n, id ->
                finding = "Asking the server: ${n + 1} of ${ids.size}…"
                runCatching { container.server.refreshItem(id, RefreshMode.MISSING, replaceImages = false, regenerateTrickplay = false) }.onSuccess { asked = asked + id }
            }
            finding = "Asked for ${ids.size}. Missing pictures appear as the server finds them; Read again later."
        }
    }
    val first = remember { FocusRequester() }
    fun load() {
        val ask = ImageOverviewAsk(libraryId, only, onlyType)
        reading = true
        error = null
        scope.launch {
            runCatching { container.imageOverview(ask) }
                .onSuccess { overview = it }
                .onFailure { error = "Couldn't read the images: ${it.message ?: it::class.simpleName}" }
            reading = false
        }
    }
    LaunchedEffect(available, libraryId, only, onlyType) {
        if (available && container.lastImageOverview?.first != ImageOverviewAsk(libraryId, only, onlyType)) load()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Library images overview", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Every film, show, season, episode and collection's pictures: how many of each kind, the space they take, and which are missing, " +
                        "small (narrower than the size guide: posters 1000×1500, backdrops 1920×1080, episode stills 1280×720…), the wrong shape (over 15% off) or gone. " +
                        "The server's resized copies and people's photos aren't counted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!available) {
                    // Focus has somewhere to be.
                    Text(
                        "This needs the Nitrate Companion plugin on your server (a version with the images overview): only the server can read the pictures' files and sizes.",
                        modifier = Modifier.focusRequester(first).focusable(),
                    )
                    return@Column
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(libraryId == null, { libraryId = null }, modifier = Modifier.focusRequester(first)) { Text("All libraries") } }
                    items(libraries, key = { it.id }) { l -> FilterChip(libraryId == l.id, { libraryId = l.id }) { Text(l.name) } }
                }
                if (reading) Text("Reading the library's pictures…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        val o = overview?.takeIf { available } ?: return@LazyColumn
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Text(bytesLabel(o.bytes), style = MaterialTheme.typography.displaySmall)
                Column(Modifier.weight(1f)) {
                    Text(o.summary)
                    Text(
                        "${o.complete} of ${o.items} complete · ${o.missing} missing · ${o.small} small · ${o.misshapen} wrong shape · ${o.gone} gone" + (if (o.opaque > 0) " · ${o.opaque} with no transparency" else ""),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = ::load, enabled = !reading) { Text("Read again") }
            }
        }
        item { Heading("By kind of picture: select one to list only its problems") }
        items(o.types, key = { "t-${it.typeName}" }) { t ->
            ListItem(
                selected = onlyType != null && onlyType == t.type,
                onClick = { t.type?.let { onlyType = if (onlyType == it) null else it } },
                headlineContent = { Text(t.label) },
                supportingContent = { Text(t.detail, color = if (t.small + t.misshapen + t.gone + t.opaque > 0) Amber else Color.Unspecified) },
            )
        }
        item { Heading("By kind of title: select one to list what's missing") }
        items(o.kinds, key = { "k-${it.kindName}" }) { k ->
            ListItem(
                selected = false,
                onClick = { only = ImageFlawKind.MISSING; onlyType = null },
                headlineContent = { Text(k.label) },
                supportingContent = {
                    Column {
                        Text(k.detail)
                        k.missingLine?.let { Text(it, color = Amber) }
                    }
                },
            )
        }
        item {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("To look at", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(only == null, { only = null }) { Text("Anything") } }
                    items(ImageFlawKind.entries) { f -> FilterChip(only == f, { only = f }) { Text(f.label) } }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(onlyType == null, { onlyType = null }) { Text("Every kind") } }
                    items(ImageType.entries) { t -> FilterChip(onlyType == t, { onlyType = t }) { Text(t.label) } }
                }
                if (o.problems.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // One title after another on Manage → Images, the found pictures it needs open; picking one moves on.
                    androidx.tv.material3.Button(onClick = {
                        val steps = dev.nitrate.core.admin.ImageFixRun.steps(o.problems, onlyType)
                        if (steps.isNotEmpty()) { container.imageFixRun = steps; onFixRun(steps.first().itemId) }
                    }) { Text("Fix these") }
                    val missing = dev.nitrate.core.admin.ImageFixRun.missingIds(o.problems).filter { it !in asked }
                    if (missing.isNotEmpty()) OutlinedButton(onClick = { findMissing(missing) }, enabled = finding?.startsWith("Asking") != true) { Text("Find missing · ${missing.size}") }
                }
                finding?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                Text(
                    when {
                        o.problemsTotal == 0 -> "Nothing to look at here."
                        o.problems.size < o.problemsTotal -> "The first ${o.problems.size} of ${o.problemsTotal}"
                        else -> "${o.problemsTotal} to look at"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(o.problems, key = { "p-${it.itemId}" }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    selected = false,
                    onClick = { onManage(p.itemId) },
                    headlineContent = { Text(p.title) },
                    supportingContent = { Text(p.line) },
                    trailingContent = { Text("Images ›", color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.weight(1f),
                )
                if (p.missing.isNotEmpty()) {
                    val done = p.itemId in asked
                    OutlinedButton(enabled = !done, onClick = {
                        scope.launch {
                            runCatching { container.server.refreshItem(p.itemId, RefreshMode.MISSING, replaceImages = false, regenerateTrickplay = false) }
                                .onSuccess { asked = asked + p.itemId }
                                .onFailure { error = "Couldn't ask the server: ${it.message}" }
                        }
                    }) { Text(if (done) "Asked the server" else "Find missing") }
                }
            }
        }
        if (o.largest.isNotEmpty()) {
            item { Heading("Biggest files") }
            items(o.largest, key = { "b-${it.itemId}-${it.typeName}-${it.index}" }) { b ->
                ListItem(
                    selected = false,
                    onClick = { onManage(b.itemId) },
                    headlineContent = { Text(b.title) },
                    supportingContent = { Text(b.detail) },
                )
            }
        }
    }
    LaunchedEffect(available) { runCatching { first.requestFocus() } }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

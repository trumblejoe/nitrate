package dev.nitrate.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.admin.ImageFlawKind
import dev.nitrate.core.admin.ImageGuide
import dev.nitrate.core.admin.ImageOverview
import dev.nitrate.core.admin.ImageOverviewAsk
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

/** The libraries the overview offers: those holding films, shows or other videos. */
internal val IMAGE_LIBRARIES = setOf(CollectionKind.MOVIES, CollectionKind.TV_SHOWS, CollectionKind.MIXED, CollectionKind.HOME_VIDEOS, CollectionKind.MUSIC_VIDEOS, CollectionKind.BOX_SETS)

/**
 * Settings → Tools → Library images overview (admins, Companion feature `imagesurvey`): every
 * film, show, season, episode and collection's images, how much space they take, and which are
 * missing, small, the wrong shape or gone, by the size guide (core `admin/ImageGuide`).
 */
@Composable
fun ImagesOverviewScreen(actions: AppActions) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val companion by container.companion.collectAsState()
    val available = companion?.features?.contains(ImageGuide.FEATURE) == true
    val libraries by produceState(emptyList<MediaItem>()) {
        value = runCatching { actions.server.views() }.getOrDefault(emptyList()).filter { it.collection in IMAGE_LIBRARIES }
    }
    val kept = container.lastImageOverview
    var libraryId by rememberSaveable { mutableStateOf(kept?.first?.libraryId) }
    var only by rememberSaveable { mutableStateOf(kept?.first?.only) }
    var onlyType by rememberSaveable { mutableStateOf(kept?.first?.onlyType) }
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
                runCatching { actions.server.refreshItem(id, RefreshMode.MISSING, replaceImages = false, regenerateTrickplay = false) }.onSuccess { asked = asked + id }
            }
            finding = "Asked for ${ids.size}. Missing pictures appear as the server finds them; Read again later."
        }
    }
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

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Library images overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Every film, show, season, episode and collection's pictures: how many of each kind, the space they take, and which are missing, small, the wrong shape or gone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!available) {
                    Text(
                        "This needs the Nitrate Companion plugin on your server (a version with the images overview): only the server can read the pictures' files and sizes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(libraryId == null, { libraryId = null }, label = { Text("All") })
                    libraries.forEach { l -> FilterChip(libraryId == l.id, { libraryId = l.id }, label = { Text(l.name) }) }
                }
                if (reading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Reading the library's pictures…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        val o = overview?.takeIf { available } ?: return@LazyColumn
        item { Summary(o) { load() } }
        item { Heading("By kind of picture") }
        items(o.types, key = { "t-${it.typeName}" }) { t ->
            Line(t.label, t.detail, warn = t.small + t.misshapen + t.gone + t.opaque > 0)
        }
        item { Heading("By kind of title") }
        items(o.kinds, key = { "k-${it.kindName}" }) { k ->
            Line(k.label, listOfNotNull(k.detail, k.missingLine).joinToString("\n"), warn = k.missingLine != null)
        }
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("To look at", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(only == null, { only = null }, label = { Text("Anything") })
                    ImageFlawKind.entries.forEach { f -> FilterChip(only == f, { only = f }, label = { Text(f.label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(onlyType == null, { onlyType = null }, label = { Text("Every kind") })
                    ImageType.entries.forEach { t -> FilterChip(onlyType == t, { onlyType = t }, label = { Text(t.label) }) }
                }
                if (o.problems.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // One title after another on Manage → Images, the found pictures it needs open; picking one moves on.
                    androidx.compose.material3.Button(onClick = {
                        val steps = dev.nitrate.core.admin.ImageFixRun.steps(o.problems, onlyType)
                        if (steps.isNotEmpty()) {
                            container.imageFixRun = steps
                            actions.nav.navigate(ManageItemRoute(steps.first().itemId, images = true, run = true))
                        }
                    }) { Text("Fix these") }
                    val missing = dev.nitrate.core.admin.ImageFixRun.missingIds(o.problems).filter { it !in asked }
                    if (missing.isNotEmpty()) OutlinedButton(onClick = { findMissing(missing) }, enabled = finding?.startsWith("Asking") != true) { Text("Find missing · ${missing.size}") }
                }
                finding?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = accentColor()) }
                Text(
                    when {
                        o.problemsTotal == 0 -> "Nothing to look at here."
                        o.problems.size < o.problemsTotal -> "The first ${o.problems.size} of ${o.problemsTotal}"
                        else -> "${o.problemsTotal} to look at"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(o.problems, key = { "p-${it.itemId}" }) { p ->
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                Text(p.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { actions.nav.navigate(ManageItemRoute(p.itemId, images = true)) }) { Text("Images") }
                    if (p.missing.isNotEmpty()) {
                        val done = p.itemId in asked
                        TextButton(enabled = !done, onClick = {
                            scope.launch {
                                runCatching { actions.server.refreshItem(p.itemId, RefreshMode.MISSING, replaceImages = false, regenerateTrickplay = false) }
                                    .onSuccess { asked = asked + p.itemId }
                                    .onFailure { error = "Couldn't ask the server: ${it.message}" }
                            }
                        }) { Text(if (done) "Asked the server" else "Find missing") }
                    }
                }
            }
        }
        if (o.largest.isNotEmpty()) {
            item { Heading("Biggest files") }
            items(o.largest, key = { "b-${it.itemId}-${it.typeName}-${it.index}" }) { b ->
                Column(Modifier.pressable({ actions.nav.navigate(ManageItemRoute(b.itemId, images = true)) }).padding(horizontal = ScreenPadding, vertical = 6.dp)) {
                    Text(b.title, style = MaterialTheme.typography.titleSmall)
                    Text(b.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(
                "Small means narrower than the size guide's good size for its kind (posters 1000×1500, backdrops 1920×1080, episode stills 1280×720…); wrong shape, more than 15% away from it. " +
                    "The server's resized copies (its image cache) and people's photos aren't counted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(ScreenPadding),
            )
        }
    }
}

@Composable
private fun Summary(o: ImageOverview, onReadAgain: () -> Unit) {
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(dev.nitrate.core.admin.bytesLabel(o.bytes), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(o.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${o.complete} of ${o.items} complete · ${o.missing} missing · ${o.small} small · ${o.misshapen} wrong shape · ${o.gone} gone" + (if (o.opaque > 0) " · ${o.opaque} with no transparency" else ""),
            style = MaterialTheme.typography.bodySmall, color = accentColor(),
        )
        OutlinedButton(onClick = onReadAgain) { Text("Read again") }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = ScreenPadding, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Line(title: String, detail: String, warn: Boolean) {
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = if (warn) Color(0xFFFFC266) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

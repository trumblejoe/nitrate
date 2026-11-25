package dev.nitrate.tv.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.ItemImage
import dev.nitrate.core.admin.ItemMetadata
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.RemoteImage
import dev.nitrate.core.admin.RemoteMatch
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.admin.TaskState
import dev.nitrate.core.admin.UserAccount
import dev.nitrate.core.admin.UserPermissions
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatClock
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PagePadding = 58.dp

@Composable
private fun <T> polled(everyMs: Long, key: Any? = null, load: suspend () -> T): Result<T>? =
    produceState<Result<T>?>(null, key) {
        while (true) {
            value = runCatching { load() }
            delay(everyMs)
        }
    }.value

@Composable
private fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, first: FocusRequester) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { i, l -> FilterChip(selected = i == selected, onClick = { onSelect(i) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(l) } }
    }
}

@Composable
private fun Status(message: String?) {
    if (message != null) Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
}

// ---- One item ----

/**
 * Metadata tools for one item, with the remote: details, identify, images and refresh. Admins and
 * metadata editors ([asEditor]: the Companion checks and logs each change, and its refusals show
 * in the status line) get the same tabs; nothing here deletes the title or touches library settings.
 */
@Composable
fun ManageItemScreen(
    repository: MediaServer,
    itemId: String,
    /** Plays (true) or stops (false) an item's theme music, for the preview on Details. */
    themePreview: ((String, Boolean) -> Unit)? = null,
    asEditor: Boolean = false,
    /** Admins with the Companion's `creditsreport`: Refresh has a "Credits check" line. */
    creditsCheck: Boolean = false,
) {
    val item by produceState<MediaItem?>(null, itemId) { value = runCatching { repository.getItem(itemId) }.getOrNull() }
    var tab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(start = PagePadding, end = PagePadding, top = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Manage", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(item?.let { it.episodeLabel ?: it.name }.orEmpty(), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (asEditor) Text(
                    "As a metadata editor: the server's admins can see and undo your changes",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
            Tabs(listOf("Details", "Identify", "Images", "Refresh"), tab, { tab = it; status = null }, first)
        }
        Status(status)
        val i = item ?: run { Text("Loading…"); return@Column }
        when (tab) {
            0 -> MetadataTab(repository, i, themePreview) { status = it }
            1 -> IdentifyTab(repository, i) { status = it }
            2 -> ImagesTab(repository, i) { status = it }
            else -> RefreshTab(repository, i, creditsCheck && (i.kind == ItemKind.MOVIE || i.kind == ItemKind.EPISODE)) { status = it }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun LabeledField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TvTextField(value, onChange, placeholder = label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataTab(repository: MediaServer, item: MediaItem, themePreview: ((String, Boolean) -> Unit)?, notify: (String) -> Unit) {
    var m by remember { mutableStateOf<ItemMetadata?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) { runCatching { repository.metadata(item.id) }.onSuccess { m = it }.onFailure { error = it.message } }
    val meta = m ?: run { Text(error?.let { "Couldn't load: $it" } ?: "Loading…"); return }
    var newGenre by remember { mutableStateOf("") }
    val theme by produceState<Result<dev.nitrate.core.model.ThemeMusicInfo>?>(null, item.id) { value = runCatching { repository.themeMusicInfo(item.id) } }
    var previewing by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(item.id) { onDispose { if (previewing) themePreview?.invoke(item.id, false) } }
    val locked = meta.lockedFields.mapNotNull(dev.nitrate.core.admin.LockableField::of).toSet()
    fun toggleLock(f: dev.nitrate.core.admin.LockableField) {
        m = meta.copy(lockedFields = (if (f in locked) locked - f else locked + f).sortedBy { it.ordinal }.map { it.wire })
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        // Where the file is on the server: folder and file names often say which film or edition it is.
        meta.path?.let { path ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("File", style = MaterialTheme.typography.titleSmall)
                    Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            // Whether the title has theme music: its own, or its series'.
            val t = theme?.getOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Theme music: " + when {
                        theme == null -> "checking…"
                        t == null -> "couldn't check"
                        t.songs.isEmpty() -> "none. Put a theme.mp3 in the title's folder (or use Jellyfin's theme songs plugin), then refresh it."
                        else -> (if (t.songs.size == 1) "“${t.songs.first().name}”" else "${t.songs.size} songs") + if (t.inheritedFrom(item.id) != null) " (the series')" else " (its own)"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (t != null && t.songs.isNotEmpty() && themePreview != null) OutlinedButton(onClick = {
                    themePreview(item.id, !previewing)
                    previewing = !previewing
                }) { Text(if (previewing) "Stop" else "Play") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledField("Title", meta.name, Modifier.weight(2f)) { m = meta.copy(name = it) }
                LabeledField("Sort title", meta.sortName.orEmpty(), Modifier.weight(1f)) { m = meta.copy(sortName = it) }
                LabeledField("Original title", meta.originalTitle.orEmpty(), Modifier.weight(1f)) { m = meta.copy(originalTitle = it) }
            }
        }
        item { LabeledField("Tagline", meta.tagline.orEmpty()) { m = meta.copy(tagline = it) } }
        item { LabeledField("Overview", meta.overview.orEmpty()) { m = meta.copy(overview = it) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledField("Year", meta.productionYear?.toString().orEmpty(), Modifier.weight(1f)) { m = meta.copy(productionYear = it.toIntOrNull()) }
                LabeledField("Released (YYYY-MM-DD)", meta.premiereDate.orEmpty(), Modifier.weight(1f)) { m = meta.copy(premiereDate = it) }
                LabeledField("Age rating", meta.officialRating.orEmpty(), Modifier.weight(1f)) { m = meta.copy(officialRating = it) }
                LabeledField("Rating", meta.communityRating?.toString().orEmpty(), Modifier.weight(1f)) { m = meta.copy(communityRating = it.toFloatOrNull()) }
            }
        }
        if (item.kind == ItemKind.EPISODE) item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledField("Season", meta.parentIndexNumber?.toString().orEmpty(), Modifier.weight(1f)) { m = meta.copy(parentIndexNumber = it.toIntOrNull()) }
                LabeledField("Episode", meta.indexNumber?.toString().orEmpty(), Modifier.weight(1f)) { m = meta.copy(indexNumber = it.toIntOrNull()) }
                LabeledField("Airs before season", meta.airsBeforeSeason?.toString().orEmpty(), Modifier.weight(1f)) { m = meta.copy(airsBeforeSeason = it.toIntOrNull()) }
                LabeledField("…before episode", meta.airsBeforeEpisode?.toString().orEmpty(), Modifier.weight(1f)) { m = meta.copy(airsBeforeEpisode = it.toIntOrNull()) }
                LabeledField("Airs after season", meta.airsAfterSeason?.toString().orEmpty(), Modifier.weight(1f)) { m = meta.copy(airsAfterSeason = it.toIntOrNull()) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Genres (select to remove)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    meta.genres.forEach { g -> FilterChip(selected = true, onClick = { m = meta.copy(genres = meta.genres - g) }) { Text("$g  ✕") } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(320.dp)) { TvTextField(newGenre, { newGenre = it }, placeholder = "Add genre") }
                    OutlinedButton(onClick = { if (newGenre.isNotBlank()) m = meta.copy(genres = meta.genres + newGenre.trim()); newGenre = "" }) { Text("Add") }
                }
            }
        }
        item {
            // The title's IDs on the providers: the IDs the server's providers accept for this kind of
            // title (Jellyfin's ExternalIdInfos), then any other the title has.
            val accepted by produceState(emptyList<dev.nitrate.core.admin.ExternalIdField>(), item.id) { value = runCatching { repository.externalIdFields(item.id) }.getOrDefault(emptyList()) }
            val fields = dev.nitrate.core.admin.ExternalIds.fields(accepted.ifEmpty { listOf("Tmdb", "Imdb", "Tvdb").map { dev.nitrate.core.admin.ExternalIdField(it, it, null) } }, meta.providerIds)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("External IDs: change one, then Refresh with \"Replace all metadata\" to read the title again from it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fields.forEach { f ->
                        val ids = meta.providerIds
                        val value = ids.entries.firstOrNull { it.key.equals(f.key, ignoreCase = true) }?.value.orEmpty()
                        LabeledField(f.label, value, Modifier.width(260.dp)) { v ->
                            m = meta.copy(providerIds = ids.filterKeys { !it.equals(f.key, ignoreCase = true) } + (if (v.isBlank()) emptyMap() else mapOf(f.key to v.trim())))
                        }
                    }
                }
                if (meta.links.isNotEmpty()) Text(
                    meta.links.joinToString("   ·   ") { "${it.first}: ${it.second}" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ListItem(selected = false, onClick = { m = meta.copy(lockData = !meta.lockData) }, headlineContent = { Text("Lock this item against refreshes") },
                trailingContent = { Switch(checked = meta.lockData, onCheckedChange = null) })
        }
        if (!meta.lockData) item {
            // Fields Jellyfin can lock one by one: refreshes and identifying leave them as they are.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Locked against refreshes (select to lock or unlock)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.nitrate.core.admin.LockableField.entries.forEach { f ->
                        FilterChip(selected = f in locked, onClick = { toggleLock(f) }) { Text((if (f in locked) "🔒 " else "") + f.label) }
                    }
                }
            }
        }
        item {
            Button(onClick = {
                scope.launch { runCatching { repository.saveMetadata(meta) }.onSuccess { notify("Saved") }.onFailure { notify("Couldn't save: ${it.message}") } }
            }) { Text("Save changes") }
        }
    }
}

@Composable
private fun IdentifyTab(repository: MediaServer, item: MediaItem, notify: (String) -> Unit) {
    var name by remember { mutableStateOf(item.displayTitle) }
    var year by remember { mutableStateOf(item.productionYear?.toString().orEmpty()) }
    // Identify by ID: an IMDb, TMDb or TVDb ID (or a link) finds exactly that title.
    var byId by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Result<List<RemoteMatch>>?>(null) }
    var replaceImages by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val supported = item.kind in setOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.BOX_SET, ItemKind.MUSIC_VIDEO)
    fun search() = scope.launch {
        results = null
        val id = dev.nitrate.core.admin.ExternalIds.guess(byId, digitsAre = if (item.kind == ItemKind.SERIES) "Tvdb" else "Tmdb")
        results = runCatching { if (id != null) repository.remoteSearch(item, name, year.toIntOrNull(), mapOf(id)) else repository.remoteSearch(item, name, year.toIntOrNull()) }
    }
    LaunchedEffect(Unit) { if (supported) search() }
    if (!supported) {
        Text("Identify works on movies, shows and collections. For an episode, identify its show.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            LabeledField("Title", name, Modifier.weight(2f)) { name = it }
            LabeledField("Year", year, Modifier.weight(1f)) { year = it }
            LabeledField(if (item.kind == ItemKind.SERIES) "Or an IMDb/TVDb ID" else "Or an IMDb/TMDb ID", byId, Modifier.weight(1.5f)) { byId = it }
            Button(onClick = { search() }) { Text("Search") }
            FilterChip(selected = replaceImages, onClick = { replaceImages = !replaceImages }) { Text("Replace images") }
        }
        val list = results?.getOrNull()
        when {
            results == null -> Text("Searching…")
            list == null -> Text("Search failed: ${results?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
            list.isEmpty() -> Text("No matches. The server may not have internet access, or try another title or year.")
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(list) { r ->
                    Card(onClick = {
                        scope.launch { runCatching { repository.applyMatch(item.id, r, replaceImages) }.onSuccess { notify("Identified as ${r.name}") }.onFailure { notify("Couldn't apply: ${it.message}") } }
                    }, modifier = Modifier.width(200.dp)) {
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                r.imageUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                            Text(r.name + (r.year?.let { " ($it)" } ?: ""), Modifier.padding(8.dp), maxLines = 2)
                            Text(r.providerName.orEmpty(), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The title's images by kind; OK on one asks what to do (make it the main backdrop, delete). Kinds
 * with one image are replaced from the providers; backdrops are a set Nitrate rotates through, so
 * found ones are added. Sizes show on each, with small ones (blocky on a TV) marked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImagesTab(repository: MediaServer, item: MediaItem, notify: (String) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val images by produceState<List<ItemImage>?>(null, reload) { value = runCatching { repository.itemImages(item.id) }.getOrDefault(emptyList()) }
    val providers by produceState(emptyList<dev.nitrate.core.admin.ImageProvider>(), item.id) { value = runCatching { repository.imageProviders(item.id) }.getOrDefault(emptyList()) }
    var browsing by remember { mutableStateOf<ImageType?>(null) }
    var choosing by remember { mutableStateOf<ItemImage?>(null) }
    // A found backdrop waiting to be placed: replace the main one, add as main, or just add.
    var placing by remember { mutableStateOf<Pair<ImageType, String>?>(null) }
    val scope = rememberCoroutineScope()
    fun place(type: ImageType, url: String, placement: dev.nitrate.core.admin.ImagePlacement) {
        scope.launch {
            runCatching { dev.nitrate.core.admin.ImagePlacing.place(repository, item.id, type, placement) { repository.downloadRemoteImage(item.id, type, url) } }
                .onSuccess { notify(if (dev.nitrate.core.admin.ImagePlacing.holdsSeveral(type)) "${placement.label}: done" else "Image set"); reload++ }
                .onFailure { notify("${it.message}") }
        }
    }
    val offered = providers.flatMap { it.types }.toSet()
    val kinds = ImageType.entries.filter { if (providers.isEmpty()) it in setOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.LOGO, ImageType.THUMB, ImageType.BANNER) else it in offered || images.orEmpty().any { i -> i.type == it } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        items(kinds, key = { it.name }) { type ->
            val ofType = images.orEmpty().filter { it.type == type }.sortedBy { it.index ?: 0 }
            val several = type == ImageType.BACKDROP
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(type.label, style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { browsing = type }) { Text(if (several) "Add backdrops" else "Find ${type.label.lowercase()}") }
                    if (several && ofType.size > 1) Text("Nitrate rotates through these; the main one comes first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (ofType.isEmpty()) item { Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(ofType, key = { "${it.type}-${it.index}" }) { img ->
                        Card(onClick = { choosing = img }, modifier = Modifier.height(140.dp).aspectRatio(img.width?.let { w -> img.height?.let { h -> w.toFloat() / h } } ?: 1f)) {
                            Box {
                                AsyncImage(img.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                val small = dev.nitrate.core.admin.RemoteImages.isSmall(type, img.width, img.height)
                                Text(
                                    listOfNotNull(listOfNotNull(img.width, img.height).joinToString("×").ifBlank { null }, "small".takeIf { small }, "main".takeIf { several && (img.index ?: 0) == 0 }).joinToString(" · "),
                                    Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(4.dp),
                                    style = MaterialTheme.typography.labelSmall, color = if (small) Color(0xFFFFC266) else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    choosing?.let { img ->
        val keep = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog({ choosing = null }, width = 520) {
            Text("${img.type.label.removeSuffix("s")}${listOfNotNull(img.width, img.height).joinToString("×").ifBlank { null }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { choosing = null }, modifier = Modifier.focusRequester(keep)) { Text("Keep") }
                val index = img.index
                if (img.type == ImageType.BACKDROP && index != null && index > 0) Button(onClick = {
                    choosing = null
                    scope.launch { runCatching { repository.moveImage(item.id, img.type, index, 0) }.onSuccess { notify("Main backdrop set"); reload++ }.onFailure { notify("${it.message}") } }
                }) { Text("Make main") }
                OutlinedButton(onClick = {
                    choosing = null
                    scope.launch { runCatching { repository.deleteImage(item.id, img.type, img.index) }.onSuccess { notify("Deleted"); reload++ }.onFailure { notify("${it.message}") } }
                }) { Text("Delete") }
            }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { keep.requestFocus() } }
        }
    }
    browsing?.let { type ->
        var provider by remember(type) { mutableStateOf<String?>(null) }
        var allLanguages by remember(type) { mutableStateOf(false) }
        val added = remember(type) { androidx.compose.runtime.mutableStateListOf<String>() }
        val found by produceState<Result<List<RemoteImage>>?>(null, type, provider, allLanguages) {
            value = null
            value = runCatching { dev.nitrate.core.admin.RemoteImages.best(repository.remoteImages(item.id, type, provider, allLanguages)) }
        }
        val first = remember { FocusRequester() }
        Dialog(onDismissRequest = { browsing = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(1000.dp).height(600.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${type.label} from providers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { browsing = null }, modifier = Modifier.focusRequester(first)) { Text(if (type == ImageType.BACKDROP) "Done" else "Close") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = provider == null, onClick = { provider = null }) { Text("All providers") }
                    providers.filter { type in it.types }.forEach { p -> FilterChip(selected = provider == p.name, onClick = { provider = p.name }) { Text(p.name) } }
                    FilterChip(selected = allLanguages, onClick = { allLanguages = !allLanguages }) { Text("All languages") }
                }
                Text(
                    if (type == ImageType.BACKDROP) "Pick one to replace the main backdrop, add it as the main one, or just add it to the set Nitrate rotates through."
                    else "A title has one ${type.label.lowercase()}: the one you pick replaces it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val list = found?.getOrNull()
                when {
                    found == null -> Text("Searching…")
                    list.isNullOrEmpty() -> Text(
                        if (allLanguages) "Nothing found. The server may not have internet access or a provider ID for this item."
                        else "None in the server's metadata language. Try \"All languages\".",
                    )
                    else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(list, key = { it.url }) { img ->
                            val small = dev.nitrate.core.admin.RemoteImages.isSmall(type, img.width, img.height)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Card(onClick = {
                                    if (img.url in added) return@Card
                                    if (dev.nitrate.core.admin.ImagePlacing.holdsSeveral(type)) placing = type to img.url
                                    else { added += img.url; browsing = null; place(type, img.url, dev.nitrate.core.admin.ImagePlacement.REPLACE_MAIN) }
                                }, modifier = Modifier.height(300.dp).aspectRatio(img.width?.let { w -> img.height?.let { h -> w.toFloat() / h } } ?: 0.67f)) {
                                    Box {
                                        AsyncImage(img.thumbnailUrl ?: img.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        if (img.url in added) Text("Picked", Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White)
                                    }
                                }
                                Text(
                                    listOfNotNull(img.providerName, img.language?.uppercase(), listOfNotNull(img.width, img.height).joinToString("×").ifBlank { null }, "small".takeIf { small }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = if (small) Color(0xFFFFC266) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(type) { runCatching { first.requestFocus() } }
        placing?.let { (t, url) ->
            val cancel = remember { FocusRequester() }
            dev.nitrate.tv.ui.lists.TvDialog({ placing = null }, width = 640) {
                Text("New backdrop", style = MaterialTheme.typography.headlineSmall)
                dev.nitrate.core.admin.ImagePlacement.entries.forEach { p ->
                    ListItem(selected = false, onClick = { placing = null; added += url; place(t, url, p) },
                        headlineContent = { Text(p.label) }, supportingContent = { Text(p.note) })
                }
                OutlinedButton(onClick = { placing = null }, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { cancel.requestFocus() } }
            }
        }
    }
}

@Composable
private fun RefreshTab(repository: MediaServer, item: MediaItem, creditsCheck: Boolean, notify: (String) -> Unit) {
    var mode by remember { mutableStateOf(RefreshMode.MISSING) }
    var images by remember { mutableStateOf(false) }
    var trickplay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.width(640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RefreshMode.entries.forEach { r ->
            ListItem(selected = mode == r, onClick = { mode = r }, headlineContent = { Text(r.label) }, trailingContent = { if (mode == r) Text("✓", color = MaterialTheme.colorScheme.primary) })
        }
        ListItem(selected = false, onClick = { images = !images }, headlineContent = { Text("Replace existing images") }, trailingContent = { Switch(checked = images, onCheckedChange = null) })
        ListItem(selected = false, onClick = { trickplay = !trickplay }, headlineContent = { Text("Regenerate scrubbing thumbnails") }, trailingContent = { Switch(checked = trickplay, onCheckedChange = null) })
        Button(onClick = {
            scope.launch { runCatching { repository.refreshItem(item.id, mode, images, trickplay) }.onSuccess { notify("Refresh started on the server") }.onFailure { notify("Couldn't refresh: ${it.message}") } }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Refresh") }
        if (creditsCheck) CreditsCheck(repository, item)
    }
}

/**
 * Admins: the Companion's credits reader on this title, in a few words (where the credits start,
 * any scene during or after them, and why). The TV has no clipboard to hand the frame-by-frame
 * report on, and it's too long for a QR code, so the phone app's Manage → Refresh copies it.
 */
@Composable
private fun CreditsCheck(repository: MediaServer, item: MediaItem) {
    val scope = rememberCoroutineScope()
    var report by remember(item.id) { mutableStateOf<dev.nitrate.core.playback.CreditsReport?>(null) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var asking by remember(item.id) { mutableStateOf(false) }
    fun ask() {
        asking = true
        scope.launch {
            runCatching { repository.creditsReport(item.id) }.onSuccess { report = it; error = null }.onFailure { error = "Couldn't check: ${it.message}" }
            asking = false
        }
    }
    LaunchedEffect(report) {
        if (report?.reading == true) { delay(dev.nitrate.core.playback.CreditsReport.RECHECK_MS); ask() }
    }
    ListItem(
        selected = false,
        onClick = { if (!asking) ask() },
        modifier = Modifier.padding(top = 12.dp),
        headlineContent = { Text(if (asking) "Checking…" else "Credits check") },
        supportingContent = { Text("How the server's credits reader sees the end of this title") },
    )
    report?.let { r ->
        Text(r.summary(), style = MaterialTheme.typography.bodyMedium)
        if (r.rows > 0) Text(
            "For the full report, frame by frame, open Manage → Refresh on this title in Nitrate on your phone and choose Copy report.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

// ---- Whole server ----

/**
 * Admin tools for the server: scan, tasks, activity, devices, users. With [metadataEditors] (the
 * Companion's feature `metadata`), Users gains the "Metadata editor" switch and a "Metadata edits"
 * tab reads and undoes the editors' changes.
 */
@Composable
fun ServerAdminScreen(repository: MediaServer, metadataEditors: Boolean = false, startTab: Int = 0) {
    var tab by remember { mutableIntStateOf(startTab) }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val tabs = listOf("Tasks", "Activity", "Devices", "Users", "Libraries") + listOfNotNull("Metadata edits".takeIf { metadataEditors })
    Column(Modifier.fillMaxSize().padding(start = PagePadding, end = PagePadding, top = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Server", style = MaterialTheme.typography.headlineMedium)
                Text(repository.serverUrl.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Tabs(tabs, tab, { tab = it; status = null }, first)
            Button(onClick = { scope.launch { runCatching { repository.scanAllLibraries() }.onSuccess { status = "Library scan started" }.onFailure { status = "${it.message}" } } }) { Text("Scan libraries") }
        }
        Status(status)
        when (tab) {
            0 -> TasksTab(repository) { status = it }
            1 -> ActivityTab(repository)
            2 -> DevicesTab(repository) { status = it }
            3 -> UsersTab(repository, metadataEditors) { status = it }
            4 -> LibrariesTab(repository) { status = it }
            else -> MetadataEditsTab(repository) { status = it }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun TasksTab(repository: MediaServer, notify: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val tasks = polled(2_000, tick) { repository.tasks() }?.getOrNull() ?: run { Text("Loading…"); return }
    val scope = rememberCoroutineScope()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        items(tasks, key = { it.id }) { t ->
            ListItem(
                selected = false,
                onClick = {
                    scope.launch {
                        runCatching { if (t.state == TaskState.IDLE) repository.startTask(t.id) else repository.stopTask(t.id) }.onFailure { notify("${it.message}") }
                        tick++
                    }
                },
                headlineContent = { Text(t.name) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            t.category,
                            when (t.state) {
                                TaskState.RUNNING -> "Running" + (t.progress?.let { " ${it.toInt()}%" } ?: "")
                                TaskState.CANCELLING -> "Stopping"
                                TaskState.IDLE -> t.lastResult?.let { "Last run: $it" } ?: "Never run"
                            },
                            t.lastError?.takeIf { t.lastResult == "Failed" },
                        ).joinToString(" · "),
                        color = if (t.lastResult == "Failed" && t.state == TaskState.IDLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = { Text(if (t.state == TaskState.IDLE) "Run" else "Stop", color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
}

@Composable
private fun ActivityTab(repository: MediaServer) {
    val list = polled(10_000) { repository.activity(100) }?.getOrNull() ?: run { Text("Loading…"); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        items(list, key = { it.id }) { e ->
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = { Text(e.name) },
                supportingContent = { Text(listOfNotNull(e.date.take(16).replace('T', ' '), e.overview).joinToString(" · "), maxLines = 2) },
                leadingContent = { Text(if (e.severity == "Error") "!" else "•", color = if (e.severity == "Error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
            )
        }
    }
}

@Composable
private fun DevicesTab(repository: MediaServer, notify: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val list = polled(4_000, tick) { repository.sessions() }?.getOrNull() ?: run { Text("Loading…"); return }
    var messaging by remember { mutableStateOf<ServerSession?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        items(list, key = { it.id }) { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${s.userName ?: "Someone"} · ${s.deviceName ?: s.client}", style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(s.client, s.remoteAddress).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    s.nowPlaying?.let { np ->
                        Text((if (s.isPaused) "Paused: " else "Playing: ") + (np.episodeLabel?.let { "${np.displayTitle} $it" } ?: np.name) + (s.positionMs?.let { " · ${formatClock(it)}" } ?: ""),
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (s.supportsRemoteControl) OutlinedButton(onClick = { messaging = s }) { Text("Message") }
                if (s.nowPlaying != null && s.supportsMediaControl) {
                    val cmd = if (s.isPaused) SessionCommand.UNPAUSE else SessionCommand.PAUSE
                    OutlinedButton(onClick = { scope.launch { runCatching { repository.sessionCommand(s.id, cmd) }.onFailure { notify("${it.message}") }; tick++ } }) { Text(cmd.label) }
                    OutlinedButton(onClick = { scope.launch { runCatching { repository.sessionCommand(s.id, SessionCommand.STOP) }.onFailure { notify("${it.message}") }; tick++ } }) { Text("Stop") }
                }
            }
        }
    }
    messaging?.let { s ->
        var text by remember { mutableStateOf("") }
        val first = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog({ messaging = null }, width = 520) {
            Column(
                Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Message ${s.deviceName ?: s.userName}", style = MaterialTheme.typography.headlineSmall)
                TvTextField(text, { text = it }, placeholder = "Dinner's ready", modifier = Modifier.focusRequester(first))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        messaging = null
                        scope.launch { runCatching { repository.sendMessage(s.id, "Message", text) }.onSuccess { notify("Sent") }.onFailure { notify("${it.message}") } }
                    }) { Text("Send") }
                    OutlinedButton(onClick = { messaging = null }) { Text("Cancel") }
                }
            }
        }
        LaunchedEffect(s.id) { runCatching { first.requestFocus() } }
    }
}

private val PERMISSIONS: List<Triple<String, (UserPermissions) -> Boolean, (UserPermissions, Boolean) -> UserPermissions>> = listOf(
    Triple("Administrator", { p -> p.isAdministrator }, { p, v -> p.copy(isAdministrator = v) }),
    Triple("Disabled", { p -> p.isDisabled }, { p, v -> p.copy(isDisabled = v) }),
    Triple("Access from outside home", { p -> p.enableRemoteAccess }, { p, v -> p.copy(enableRemoteAccess = v) }),
    Triple("Video transcoding", { p -> p.enableVideoTranscoding }, { p, v -> p.copy(enableVideoTranscoding = v) }),
    Triple("Downloads", { p -> p.enableContentDownloading }, { p, v -> p.copy(enableContentDownloading = v) }),
    Triple("Delete media", { p -> p.enableContentDeletion }, { p, v -> p.copy(enableContentDeletion = v) }),
    Triple("Live TV", { p -> p.enableLiveTvAccess }, { p, v -> p.copy(enableLiveTvAccess = v) }),
    Triple("Manage collections", { p -> p.enableCollectionManagement }, { p, v -> p.copy(enableCollectionManagement = v) }),
    Triple("All libraries", { p -> p.enableAllFolders }, { p, v -> p.copy(enableAllFolders = v) }),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsersTab(repository: MediaServer, metadataEditors: Boolean, notify: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val users by produceState<List<UserAccount>?>(null, tick) { value = runCatching { repository.users() }.getOrNull() }
    // Metadata editors (Companion): who has the role, in which libraries.
    val editors by produceState<List<dev.nitrate.core.admin.MetadataEditor>>(emptyList(), tick, metadataEditors) {
        value = if (metadataEditors) runCatching { repository.metadataEditors() }.getOrDefault(emptyList()) else emptyList()
    }
    val libraries by produceState<List<MediaItem>>(emptyList(), metadataEditors) {
        value = if (metadataEditors) runCatching { repository.views() }.getOrDefault(emptyList()) else emptyList()
    }
    var selected by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = users ?: run { Text("Loading…"); return }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.id }) { u ->
                ListItem(selected = selected == u.id, onClick = { selected = u.id }, headlineContent = { Text(u.name) },
                    supportingContent = {
                        Text(
                            when {
                                u.permissions.isAdministrator -> "Administrator"
                                u.permissions.isDisabled -> "Disabled"
                                editors.any { it.userId == u.id } -> "Metadata editor"
                                else -> "User"
                            },
                        )
                    })
            }
        }
        val u = list.firstOrNull { it.id == selected }
        if (u == null) {
            Text("Select a user to change their permissions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val self = u.id == repository.userId
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 48.dp)) {
                items(PERMISSIONS, key = { it.first }) { (label, get, set) ->
                    val locked = self && (label == "Administrator" || label == "Disabled")
                    ListItem(
                        selected = false,
                        enabled = !locked,
                        onClick = { scope.launch { runCatching { repository.setPermissions(u.id, set(u.permissions, !get(u.permissions))) }.onFailure { notify("${it.message}") }; tick++ } },
                        headlineContent = { Text(label) },
                        trailingContent = { Switch(checked = get(u.permissions), onCheckedChange = null) },
                    )
                }
                // Administrators edit everything anyway: the role is for everyone else.
                if (metadataEditors && !u.permissions.isAdministrator) {
                    val scope0 = editors.firstOrNull { it.userId == u.id }?.scope ?: dev.nitrate.core.admin.MetadataEditorScope.NONE
                    fun set(next: dev.nitrate.core.admin.MetadataEditorScope) {
                        scope.launch { runCatching { repository.setMetadataEditor(u.id, next) }.onFailure { notify("${it.message}") }; tick++ }
                    }
                    item(key = "editor") {
                        ListItem(
                            selected = false,
                            onClick = { set(if (scope0.isEditor) dev.nitrate.core.admin.MetadataEditorScope.NONE else dev.nitrate.core.admin.MetadataEditorScope(true, allLibraries = true)) },
                            headlineContent = { Text("Metadata editor") },
                            supportingContent = { Text("May change titles' details, images and matches; every change is logged", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = { Switch(checked = scope0.isEditor, onCheckedChange = null) },
                        )
                    }
                    if (scope0.isEditor) item(key = "editor-libraries") {
                        Column(Modifier.padding(start = 16.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("In these libraries", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = scope0.allLibraries, onClick = { set(scope0.copy(allLibraries = true, libraryIds = emptyList())) }) { Text("All") }
                                libraries.forEach { lib ->
                                    FilterChip(
                                        selected = !scope0.allLibraries && lib.id in scope0.libraryIds,
                                        onClick = { set(dev.nitrate.core.admin.MetadataEditors.toggle(scope0, lib.id)) },
                                    ) { Text(lib.name) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The metadata editors' changes, newest first (Companion feature `metadata`): when, who, the title
 * and what changed; OK on one that can be undone asks first, then puts it back (or says why not).
 */
@Composable
private fun MetadataEditsTab(repository: MediaServer, notify: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<dev.nitrate.core.admin.MetadataEdit>>?>(null, tick) { value = runCatching { repository.metadataEdits(100) } }
    var undoing by remember { mutableStateOf<dev.nitrate.core.admin.MetadataEdit?>(null) }
    val scope = rememberCoroutineScope()
    val list = result?.getOrNull() ?: run {
        Text(result?.exceptionOrNull()?.let { "Couldn't load the edits: ${it.message}" } ?: "Loading…")
        return
    }
    if (list.isEmpty()) {
        Text("No changes yet. Give someone the Metadata editor switch in Users; what they change shows here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val now = System.currentTimeMillis()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        items(list, key = { it.id }) { e ->
            ListItem(
                selected = false,
                onClick = { if (e.canUndo) undoing = e },
                headlineContent = { Text("${e.itemName.ifBlank { "A title" }} · ${e.kind.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(listOf(dev.nitrate.core.playback.agoLabel(e.atMs, now), e.userName, e.summary).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { if (e.canUndo) Text("Undo", color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
    undoing?.let { e ->
        val confirm = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog({ undoing = null }, width = 560) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Undo this change?", style = MaterialTheme.typography.headlineSmall)
                Text("${e.userName}'s change to ${e.itemName} (${e.summary}) is put back as it was.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        undoing = null
                        scope.launch {
                            runCatching { repository.undoMetadataEdit(e.id) }
                                .onSuccess { notify("Undone: ${e.itemName}"); tick++ }
                                .onFailure { notify("Couldn't undo: ${it.message}") }
                        }
                    }, modifier = Modifier.focusRequester(confirm)) { Text("Undo") }
                    OutlinedButton(onClick = { undoing = null }) { Text("Cancel") }
                }
            }
        }
        LaunchedEffect(e.id) { runCatching { confirm.requestFocus() } }
    }
}

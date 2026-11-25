package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.ItemImage
import dev.nitrate.core.admin.ItemMetadata
import dev.nitrate.core.admin.PersonCredit
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.RemoteImage
import dev.nitrate.core.admin.RemoteMatch
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

private enum class ManageTab(val label: String) { DETAILS("Details"), IDENTIFY("Identify"), IMAGES("Images"), REFRESH("Refresh") }

/** Fields that can be locked against metadata refreshes, by Jellyfin's names. */
/** Locks one field against refreshes (or shows it's locked with the whole item). */
@Composable
private fun LockButton(meta: ItemMetadata, field: dev.nitrate.core.admin.LockableField, onChange: (ItemMetadata) -> Unit) {
    val locked = meta.lockData || meta.lockedFields.any { dev.nitrate.core.admin.LockableField.of(it) == field }
    IconButton(enabled = !meta.lockData, onClick = {
        val now = meta.lockedFields.mapNotNull(dev.nitrate.core.admin.LockableField::of).toSet()
        onChange(meta.copy(lockedFields = (if (field in now) now - field else now + field).sortedBy { it.ordinal }.map { it.wire }))
    }) {
        Icon(
            androidx.compose.material.icons.Icons.Default.Lock,
            if (locked) "${field.label}: locked (select to unlock)" else "${field.label}: lock against refreshes",
            tint = if (locked) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
    }
}

/**
 * Where the title's file is on the server, read only: folder and file names often say which film,
 * year or edition it is, which helps when correcting a match. Copy puts it on the clipboard.
 */
@Composable
private fun FilePathRow(path: String) {
    val platform = dev.nitrate.mobile.platform.rememberPlatformActions()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("File", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.TextButton(onClick = { platform.copy(path, "File path") }) { Text("Copy") }
        }
    }
}

/** A title's theme music (its own, or its series'), with a preview; how to add some when there's none. */
@Composable
private fun ThemeMusicInfoRow(actions: AppActions, item: MediaItem) {
    val info by androidx.compose.runtime.produceState<Result<dev.nitrate.core.model.ThemeMusicInfo>?>(null, item.id) {
        value = runCatching { actions.server.themeMusicInfo(item.id) }
    }
    var playing by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(item.id) { onDispose { if (playing) actions.container.themeMusic.stop() } }
    val i = info?.getOrNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Theme music", style = MaterialTheme.typography.titleSmall)
        when {
            info == null -> Text("Checking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            i == null -> Text("Couldn't check", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            i.songs.isEmpty() -> Text(
                "None. Put a theme.mp3 in the title's folder (or use Jellyfin's theme songs plugin), then refresh it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (i.songs.size == 1) "“${i.songs.first().name}”" else "${i.songs.size} songs") + if (i.inheritedFrom(item.id) != null) " · from the series" else " · its own",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    if (playing) actions.container.themeMusic.stop() else actions.container.themeMusic.show(item.id)
                    playing = !playing
                }) { Text(if (playing) "Stop" else "Play") }
            }
        }
    }
}

/**
 * Admins and metadata editors (Companion feature `metadata`, which checks and logs each change and
 * says why when it refuses one): edit an item's metadata, identify it, manage its images, refresh
 * it. Nothing here deletes the title or touches library settings.
 */
@Composable
fun ManageItemScreen(actions: AppActions, itemId: String) {
    val server = actions.server
    val account by actions.container.account.collectAsState()
    val item by produceState<MediaItem?>(null, itemId) { value = runCatching { server.getItem(itemId) }.getOrNull() }
    var tab by rememberSaveable { mutableStateOf(ManageTab.DETAILS) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) = scope.launch { snack.showSnackbar(message) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Manage", style = MaterialTheme.typography.labelLarge, color = accentColor())
                    Text(item?.let { it.episodeLabel ?: it.name }.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account?.let { !it.isAdmin && it.metadataEditor.isEditor } == true) Text(
                        "As a metadata editor: the server's admins can see and undo your changes",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = ScreenPadding) {
                ManageTab.entries.forEach { t -> Tab(tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
            }
            val i = item
            if (i == null) Loading() else when (tab) {
                ManageTab.DETAILS -> MetadataEditor(actions, i, ::notify)
                ManageTab.IDENTIFY -> IdentifyPane(actions, i, ::notify)
                ManageTab.IMAGES -> ImagesPane(actions, i, ::notify)
                ManageTab.REFRESH -> RefreshPane(actions, i, ::notify)
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun MetadataEditor(actions: AppActions, item: MediaItem, notify: (String) -> Unit) {
    val server = actions.server
    var loaded by remember { mutableStateOf<Result<ItemMetadata>?>(null) }
    var m by remember { mutableStateOf<ItemMetadata?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) {
        loaded = runCatching { server.metadata(item.id) }
        m = loaded?.getOrNull()
    }
    val meta = m
    when {
        loaded == null -> { Loading(); return }
        meta == null -> { ErrorMessage("Couldn't load metadata: ${loaded?.exceptionOrNull()?.message}"); return }
    }
    meta!!
    val episodic = item.kind == ItemKind.EPISODE || item.kind == ItemKind.SEASON
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            meta.path?.let { FilePathRow(it) }
            ThemeMusicInfoRow(actions, item)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field("Title", meta.name, Modifier.weight(1f)) { m = meta.copy(name = it) }
                LockButton(meta, dev.nitrate.core.admin.LockableField.NAME) { m = it }
            }
            Field("Original title", meta.originalTitle.orEmpty()) { m = meta.copy(originalTitle = it) }
            Field("Sort title", meta.sortName.orEmpty()) { m = meta.copy(sortName = it) }
            Field("Tagline", meta.tagline.orEmpty()) { m = meta.copy(tagline = it) }
            Row(verticalAlignment = Alignment.Top) {
                Field("Overview", meta.overview.orEmpty(), Modifier.weight(1f), lines = 5) { m = meta.copy(overview = it) }
                LockButton(meta, dev.nitrate.core.admin.LockableField.OVERVIEW) { m = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Year", meta.productionYear?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(productionYear = it.toIntOrNull()) }
                Field("Released (YYYY-MM-DD)", meta.premiereDate.orEmpty(), Modifier.weight(2f)) { m = meta.copy(premiereDate = it) }
            }
            if (item.kind == ItemKind.SERIES) Field("Ended (YYYY-MM-DD)", meta.endDate.orEmpty()) { m = meta.copy(endDate = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Age rating", meta.officialRating.orEmpty(), Modifier.weight(1f)) { m = meta.copy(officialRating = it) }
                LockButton(meta, dev.nitrate.core.admin.LockableField.OFFICIAL_RATING) { m = it }
                Field("Rating", meta.communityRating?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(communityRating = it.toFloatOrNull()) }
                Field("Critics %", meta.criticRating?.toInt()?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(criticRating = it.toFloatOrNull()) }
            }
            if (episodic) {
                Text("Numbering", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.kind == ItemKind.EPISODE) Field("Season", meta.parentIndexNumber?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(parentIndexNumber = it.toIntOrNull()) }
                    Field(if (item.kind == ItemKind.EPISODE) "Episode" else "Season", meta.indexNumber?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(indexNumber = it.toIntOrNull()) }
                }
                if (item.kind == ItemKind.EPISODE) {
                    Text("For specials: where they fall in viewing order", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Airs before season", meta.airsBeforeSeason?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(airsBeforeSeason = it.toIntOrNull()) }
                        Field("…episode", meta.airsBeforeEpisode?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(airsBeforeEpisode = it.toIntOrNull()) }
                        Field("Airs after season", meta.airsAfterSeason?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(airsAfterSeason = it.toIntOrNull()) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { ChipList("Genres", meta.genres) { m = meta.copy(genres = it) } }
                LockButton(meta, dev.nitrate.core.admin.LockableField.GENRES) { m = it }
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { ChipList("Tags", meta.tags) { m = meta.copy(tags = it) } }
                LockButton(meta, dev.nitrate.core.admin.LockableField.TAGS) { m = it }
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { ChipList("Studios", meta.studios) { m = meta.copy(studios = it) } }
                LockButton(meta, dev.nitrate.core.admin.LockableField.STUDIOS) { m = it }
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { PeopleEditor(meta.people) { m = meta.copy(people = it) } }
                LockButton(meta, dev.nitrate.core.admin.LockableField.CAST) { m = it }
            }
            ProviderIdsEditor(actions, item, meta.providerIds, meta.links) { m = meta.copy(providerIds = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lock this item", style = MaterialTheme.typography.bodyLarge)
                    Text("Refreshes won't change anything", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = meta.lockData, onCheckedChange = { m = meta.copy(lockData = it) }, modifier = Modifier.labelled("Lock this item"))
            }
            if (!meta.lockData) {
                // Every field Jellyfin can lock on its own (the lock icons above, plus two not edited here).
                Text("Locked against refreshes", style = MaterialTheme.typography.titleSmall)
                Text("Refreshes and identifying keep these as they are.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val locked = meta.lockedFields.mapNotNull(dev.nitrate.core.admin.LockableField::of).toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.nitrate.core.admin.LockableField.entries.forEach { f ->
                        FilterChip(selected = f in locked, onClick = {
                            m = meta.copy(lockedFields = (if (f in locked) locked - f else locked + f).sortedBy { it.ordinal }.map { it.wire })
                        }, label = { Text(f.label) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().padding(ScreenPadding)) {
            PillButton(if (saving) "Saving…" else "Save changes", null, primary = true, modifier = Modifier.fillMaxWidth(), onClick = {
                if (saving) return@PillButton
                saving = true
                scope.launch {
                    runCatching { server.saveMetadata(meta) }
                        .onSuccess { notify("Saved") }
                        .onFailure { notify("Couldn't save: ${it.message}") }
                    saving = false
                }
            })
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), lines: Int = 1, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier,
    )
}

/** Editable list of words as chips, with a field to add more. */
@Composable
private fun ChipList(title: String, values: List<String>, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { v -> InputChip(selected = false, onClick = { onChange(values - v) }, label = { Text(v) }, trailingIcon = { Icon(Icons.Default.Close, "Remove $v", Modifier.size(16.dp)) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(adding, { adding = it }, placeholder = { Text("Add ${title.lowercase().removeSuffix("s")}") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val v = adding.trim()
                if (v.isNotEmpty() && v !in values) onChange(values + v)
                adding = ""
            }) { Icon(Icons.Default.Add, "Add") }
        }
    }
}

@Composable
private fun PeopleEditor(people: List<PersonCredit>, onChange: (List<PersonCredit>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Actor") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Cast & crew", style = MaterialTheme.typography.titleSmall)
        people.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    Text(listOfNotNull(p.type, p.role).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onChange(people.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Delete, "Remove ${p.name}") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, placeholder = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(role, { role = it }, placeholder = { Text("Role") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Actor", "Director", "Writer", "Producer", "GuestStar").forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
            }
            TextButton(enabled = name.isNotBlank(), onClick = {
                onChange(people + PersonCredit(name.trim(), role.trim().ifBlank { null }, type))
                name = ""; role = ""
            }) { Text("Add") }
        }
    }
}

/**
 * The title's IDs on the providers: the fields are the IDs the server's providers accept for this
 * kind of title (Jellyfin's ExternalIdInfos), then any other the title has; each title page the
 * server knows opens in the browser.
 */
@Composable
private fun ProviderIdsEditor(actions: AppActions, item: MediaItem, ids: Map<String, String>, links: List<Pair<String, String>>, onChange: (Map<String, String>) -> Unit) {
    val accepted by produceState(emptyList<dev.nitrate.core.admin.ExternalIdField>(), item.id) { value = runCatching { actions.server.externalIdFields(item.id) }.getOrDefault(emptyList()) }
    val fields = dev.nitrate.core.admin.ExternalIds.fields(accepted.ifEmpty { listOf("Tmdb", "Imdb", "Tvdb").map { dev.nitrate.core.admin.ExternalIdField(it, it, null) } }, ids)
    val platform = dev.nitrate.mobile.platform.rememberPlatformActions()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("External IDs", style = MaterialTheme.typography.titleSmall)
        Text("The title's IDs on the providers' sites. Change one, then Refresh with \"Replace all metadata\" to read the title again from it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fields.forEach { f ->
            val value = ids.entries.firstOrNull { it.key.equals(f.key, ignoreCase = true) }?.value.orEmpty()
            Field(f.label, value) { v -> onChange(ids.filterKeys { !it.equals(f.key, ignoreCase = true) } + (if (v.isBlank()) emptyMap() else mapOf(f.key to v.trim()))) }
        }
        if (links.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (name, url) -> androidx.compose.material3.AssistChip(onClick = { platform.openUrl(url) }, label = { Text("Open on $name") }) }
        }
    }
}

@Composable
private fun IdentifyPane(actions: AppActions, item: MediaItem, notify: (String) -> Unit) {
    val server = actions.server
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(item.displayTitle) }
    var year by remember { mutableStateOf(item.productionYear?.toString().orEmpty()) }
    // Identify by ID: an IMDb, TMDb or TVDb ID (or a link to the title's page) finds exactly that title.
    var byId by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Result<List<RemoteMatch>>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<RemoteMatch?>(null) }
    val supported = item.kind in setOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.BOX_SET, ItemKind.MUSIC_VIDEO)
    fun search() {
        searching = true
        scope.launch {
            val id = dev.nitrate.core.admin.ExternalIds.guess(byId, digitsAre = if (item.kind == ItemKind.SERIES) "Tvdb" else "Tmdb")
            results = runCatching {
                if (id != null) server.remoteSearch(item, name, year.toIntOrNull(), mapOf(id)) else server.remoteSearch(item, name, year.toIntOrNull())
            }
            searching = false
        }
    }
    LaunchedEffect(Unit) { if (supported) search() }
    if (!supported) {
        ErrorMessage("Identify works on movies, shows and collections. For an episode, identify its show.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            IconButton(onClick = ::search) { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, "Search") }
        }
        OutlinedTextField(
            byId, { byId = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            label = { Text("Or an ID or link") },
            placeholder = { Text(if (item.kind == ItemKind.SERIES) "tt0944947, a TVDb number, or a themoviedb.org link" else "tt9652782, a TMDb number, or a themoviedb.org link") },
            supportingText = {
                val id = dev.nitrate.core.admin.ExternalIds.guess(byId, digitsAre = if (item.kind == ItemKind.SERIES) "Tvdb" else "Tmdb")
                Text(if (byId.isBlank()) "Finds exactly that title, when the name finds the wrong one." else id?.let { "Searching by ${it.first} ${it.second}" } ?: "Not an ID or link Nitrate recognises")
            },
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
        )
        val list = results?.getOrNull()
        when {
            results?.isFailure == true -> ErrorMessage("Search failed: ${results?.exceptionOrNull()?.message}")
            list != null && list.isEmpty() -> Text("No matches. Try another title or year.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(ScreenPadding))
            list != null -> LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list) { r ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { applying = r }.padding(10.dp)) {
                        Box(Modifier.width(70.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            r.imageUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(r.name + (r.year?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(r.providerName, r.providerIds.entries.joinToString { "${it.key} ${it.value}" }.ifBlank { null }).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
            else -> Loading()
        }
    }
    applying?.let { match ->
        var replaceImages by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { applying = null },
            title = { Text("Use “${match.name}”?") },
            text = {
                Column {
                    Text("The server will fetch this title's metadata from ${match.providerName ?: "the provider"}.")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Replace existing images", modifier = Modifier.weight(1f))
                        Switch(checked = replaceImages, onCheckedChange = { replaceImages = it }, modifier = Modifier.labelled("Replace existing images"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applying = null
                    scope.launch {
                        runCatching { server.applyMatch(item.id, match, replaceImages) }
                            .onSuccess { notify("Identified as ${match.name}. The server is updating it.") }
                            .onFailure { notify("Couldn't apply: ${it.message}") }
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { applying = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The title's images by kind. Kinds with one image (poster, logo…) are replaced by finding or
 * uploading another; backdrops are a set Nitrate rotates through, so found and uploaded ones are
 * added, and any can be made the main one (the first, which every app shows when only one is).
 * Sizes show on each, with small ones (blocky on a TV) marked.
 */
@Composable
private fun ImagesPane(actions: AppActions, item: MediaItem, notify: (String) -> Unit) {
    val server = actions.server
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val images by produceState<Result<List<ItemImage>>?>(null, reload) { value = runCatching { server.itemImages(item.id) } }
    val providers by produceState(emptyList<dev.nitrate.core.admin.ImageProvider>(), item.id) { value = runCatching { server.imageProviders(item.id) }.getOrDefault(emptyList()) }
    var browsing by remember { mutableStateOf<ImageType?>(null) }
    var uploadType by remember { mutableStateOf(ImageType.PRIMARY) }
    // A backdrop waiting to be placed: replace the main one, add as main, or just add.
    var placing by remember { mutableStateOf<Pair<ImageType, suspend () -> Unit>?>(null) }
    fun place(type: ImageType, placement: dev.nitrate.core.admin.ImagePlacement, add: suspend () -> Unit) {
        scope.launch {
            runCatching { dev.nitrate.core.admin.ImagePlacing.place(server, item.id, type, placement, add) }
                .onSuccess { notify(if (dev.nitrate.core.admin.ImagePlacing.holdsSeveral(type)) "${placement.label}: done" else "Image set"); reload++ }
                .onFailure { notify("Couldn't set the image: ${it.message}") }
        }
    }
    val picker = dev.nitrate.mobile.platform.rememberImagePicker { bytes, mime ->
        val type = uploadType
        val upload: suspend () -> Unit = { server.uploadImage(item.id, type, bytes, mime) }
        if (dev.nitrate.core.admin.ImagePlacing.holdsSeveral(type)) placing = type to upload
        else place(type, dev.nitrate.core.admin.ImagePlacement.REPLACE_MAIN, upload)
    }
    val list = images?.getOrNull()
    if (list == null) {
        if (images?.isFailure == true) ErrorMessage("Couldn't load images: ${images?.exceptionOrNull()?.message}") else Loading()
        return
    }
    // The kinds the providers have for this title, and any the title already has.
    val offered = providers.flatMap { it.types }.toSet()
    val kinds = ImageType.entries.filter { providers.isEmpty() || it in offered || list.any { i -> i.type == it } }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(kinds, key = { it.name }) { type ->
            val ofType = list.filter { it.type == type }.sortedBy { it.index ?: 0 }
            val several = type == ImageType.BACKDROP
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { browsing = type }) { Text(if (several) "Add found" else "Find") }
                    TextButton(onClick = {
                        uploadType = type
                        picker()
                    }) { Text(if (several) "Add yours" else "Upload") }
                }
                if (several && ofType.size > 1) Text("Nitrate rotates through these; the main one comes first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ofType.isEmpty()) {
                    Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ofType, key = { "${it.type}-${it.index}" }) { img ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.height(140.dp).aspectRatio(img.width?.let { w -> img.height?.let { h -> w.toFloat() / h } } ?: 1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    AsyncImage(img.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    val small = dev.nitrate.core.admin.RemoteImages.isSmall(type, img.width, img.height)
                                    Text(
                                        listOfNotNull(listOfNotNull(img.width, img.height).joinToString("×").ifBlank { null }, "small".takeIf { small }, "main".takeIf { several && (img.index ?: 0) == 0 }).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall, color = if (small) Color(0xFFFFC266) else Color.White,
                                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching { server.deleteImage(item.id, img.type, img.index) }.onSuccess { reload++ }.onFailure { notify("Couldn't delete: ${it.message}") }
                                        }
                                    }, modifier = Modifier.align(Alignment.TopEnd)) {
                                        Icon(Icons.Default.Delete, "Delete", tint = Color.White, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(4.dp))
                                    }
                                }
                                val index = img.index
                                if (several && index != null && index > 0) TextButton(onClick = {
                                    scope.launch {
                                        runCatching { server.moveImage(item.id, type, index, 0) }.onSuccess { notify("Main backdrop set"); reload++ }.onFailure { notify("Couldn't move it: ${it.message}") }
                                    }
                                }) { Text("Make main") }
                            }
                        }
                    }
                }
            }
        }
    }
    browsing?.let { type ->
        RemoteImagesDialog(actions, item, type, providers.filter { type in it.types }.map { it.name }, onPick = { url ->
            val download: suspend () -> Unit = { server.downloadRemoteImage(item.id, type, url) }
            // Backdrops ask where the new one goes, and the list stays open to add more; other kinds replace and close.
            if (dev.nitrate.core.admin.ImagePlacing.holdsSeveral(type)) placing = type to download
            else { browsing = null; place(type, dev.nitrate.core.admin.ImagePlacement.REPLACE_MAIN, download) }
        }, onDismiss = { browsing = null })
    }
    placing?.let { (type, add) ->
        AlertDialog(
            onDismissRequest = { placing = null },
            title = { Text("New ${type.label.lowercase().removeSuffix("s")}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dev.nitrate.core.admin.ImagePlacement.entries.forEach { p ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { placing = null; place(type, p, add) }.padding(vertical = 10.dp, horizontal = 8.dp)) {
                            Text(p.label, style = MaterialTheme.typography.titleSmall)
                            Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { placing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * What the providers have: in the server's metadata language (and images with none, like most
 * backdrops) unless "All languages", from one provider or all, biggest first, small ones marked.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RemoteImagesDialog(actions: AppActions, item: MediaItem, type: ImageType, providers: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf<String?>(null) }
    var allLanguages by remember { mutableStateOf(false) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val found by produceState<Result<List<RemoteImage>>?>(null, type, provider, allLanguages) {
        value = null
        value = runCatching { dev.nitrate.core.admin.RemoteImages.best(actions.server.remoteImages(item.id, type, provider, allLanguages)) }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = dev.nitrate.mobile.platform.fullScreenDialog()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                Text("${type.label} from providers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (type == ImageType.BACKDROP) TextButton(onClick = onDismiss) { Text("Done") }
            else Text("Replaces the current one", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(provider == null, { provider = null }, label = { Text("All providers") })
                providers.forEach { p -> androidx.compose.material3.FilterChip(provider == p, { provider = p }, label = { Text(p) }) }
                androidx.compose.material3.FilterChip(allLanguages, { allLanguages = !allLanguages }, label = { Text("All languages") })
            }
            if (type == ImageType.BACKDROP) Text("Pick one to replace the main backdrop, add it as the main one, or just add it to the set Nitrate rotates through.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
            val list = found?.getOrNull()
            when {
                found == null -> Loading()
                list == null -> ErrorMessage("Couldn't search: ${found?.exceptionOrNull()?.message}")
                list.isEmpty() -> Text(
                    if (allLanguages) "No ${type.label.lowercase()} found. The server may not have internet access or a matching provider ID."
                    else "None in the server's metadata language. Try \"All languages\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(ScreenPadding),
                )
                else -> LazyVerticalGrid(GridCells.Adaptive(if (type == ImageType.PRIMARY) 110.dp else 200.dp), contentPadding = PaddingValues(ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.url }) { img ->
                        val small = dev.nitrate.core.admin.RemoteImages.isSmall(type, img.width, img.height)
                        Column(Modifier.pressable({ if (img.url !in picked) { picked += img.url; onPick(img.url) } })) {
                            Box(Modifier.fillMaxWidth().aspectRatio(img.width?.let { w -> img.height?.let { h -> w.toFloat() / h } } ?: 1f).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))) {
                                AsyncImage(img.thumbnailUrl ?: img.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (img.url in picked) Text("Picked", style = MaterialTheme.typography.labelMedium, color = Color.White,
                                    modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                            Text(listOfNotNull(img.providerName, img.language?.uppercase(), listOfNotNull(img.width, img.height).joinToString("×").ifBlank { null }, "small".takeIf { small }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = if (small) Color(0xFFFFC266) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshPane(actions: AppActions, item: MediaItem, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(RefreshMode.MISSING) }
    var replaceImages by remember { mutableStateOf(false) }
    var trickplay by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RefreshMode.entries.forEach { r ->
            Row(Modifier.fillMaxWidth().selectable(mode == r) { mode = r }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == r, onClick = null)
                Text(r.label, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Replace existing images", modifier = Modifier.weight(1f))
            Switch(checked = replaceImages, onCheckedChange = { replaceImages = it }, modifier = Modifier.labelled("Replace existing images"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Regenerate scrubbing thumbnails")
                Text("Trickplay images for the seek bar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = trickplay, onCheckedChange = { trickplay = it }, modifier = Modifier.labelled("Regenerate scrubbing thumbnails"))
        }
        PillButton("Refresh", Icons.Default.Refresh, primary = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = {
            scope.launch {
                runCatching { actions.server.refreshItem(item.id, mode, replaceImages, trickplay) }
                    .onSuccess { notify("Refresh started on the server") }
                    .onFailure { notify("Couldn't refresh: ${it.message}") }
            }
        })
        val account by actions.container.account.collectAsState()
        val companion by actions.container.companion.collectAsState()
        if (account?.isAdmin == true && companion?.features?.contains(dev.nitrate.core.playback.CreditsReport.FEATURE) == true &&
            (item.kind == ItemKind.MOVIE || item.kind == ItemKind.EPISODE)
        ) CreditsCheck(actions, item)
    }
}

/**
 * Admins: the Companion's credits reader on this title (where the credits start, any scene during
 * or after them, and why), with the whole frame-by-frame report to copy for tuning. While the
 * server reads the title it asks again every [CreditsReport.RECHECK_MS].
 */
@Composable
private fun CreditsCheck(actions: AppActions, item: MediaItem) {
    val platform = dev.nitrate.mobile.platform.rememberPlatformActions()
    val scope = rememberCoroutineScope()
    var report by remember(item.id) { mutableStateOf<dev.nitrate.core.playback.CreditsReport?>(null) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var asking by remember(item.id) { mutableStateOf(false) }
    fun ask() {
        asking = true
        scope.launch {
            runCatching { actions.server.creditsReport(item.id) }
                .onSuccess { report = it; error = null }
                .onFailure { error = "Couldn't check: ${it.message}" }
            asking = false
        }
    }
    LaunchedEffect(report) {
        if (report?.reading == true) { kotlinx.coroutines.delay(dev.nitrate.core.playback.CreditsReport.RECHECK_MS); ask() }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Credits check", style = MaterialTheme.typography.titleSmall)
        Text("How the server's credits reader sees the end of this title", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PillButton(if (asking) "Checking…" else "Check now", null, primary = false, modifier = Modifier.fillMaxWidth(), onClick = { if (!asking) ask() })
        report?.let { r ->
            Text(r.summary(), style = MaterialTheme.typography.bodyMedium)
            if (r.rows > 0) PillButton("Copy report", null, primary = false, modifier = Modifier.fillMaxWidth(), onClick = { platform.copy(r.json, "Credits report") })
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

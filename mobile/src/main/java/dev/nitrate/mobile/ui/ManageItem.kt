package dev.nitrate.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
private val LOCKABLE = listOf("Name", "OriginalTitle", "SortName", "Overview", "Genres", "Tags", "Studios", "Cast", "OfficialRating", "ProductionLocations", "Runtime")

/** Admin: edit an item's metadata, identify it, manage its images, refresh it. */
@Composable
fun ManageItemScreen(actions: AppActions, itemId: String) {
    val server = actions.server
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
            Field("Title", meta.name) { m = meta.copy(name = it) }
            Field("Original title", meta.originalTitle.orEmpty()) { m = meta.copy(originalTitle = it) }
            Field("Sort title", meta.sortName.orEmpty()) { m = meta.copy(sortName = it) }
            Field("Tagline", meta.tagline.orEmpty()) { m = meta.copy(tagline = it) }
            Field("Overview", meta.overview.orEmpty(), lines = 5) { m = meta.copy(overview = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Year", meta.productionYear?.toString().orEmpty(), Modifier.weight(1f), number = true) { m = meta.copy(productionYear = it.toIntOrNull()) }
                Field("Released (YYYY-MM-DD)", meta.premiereDate.orEmpty(), Modifier.weight(2f)) { m = meta.copy(premiereDate = it) }
            }
            if (item.kind == ItemKind.SERIES) Field("Ended (YYYY-MM-DD)", meta.endDate.orEmpty()) { m = meta.copy(endDate = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Age rating", meta.officialRating.orEmpty(), Modifier.weight(1f)) { m = meta.copy(officialRating = it) }
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
            ChipList("Genres", meta.genres) { m = meta.copy(genres = it) }
            ChipList("Tags", meta.tags) { m = meta.copy(tags = it) }
            ChipList("Studios", meta.studios) { m = meta.copy(studios = it) }
            PeopleEditor(meta.people) { m = meta.copy(people = it) }
            ProviderIdsEditor(meta.providerIds) { m = meta.copy(providerIds = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lock this item", style = MaterialTheme.typography.bodyLarge)
                    Text("Refreshes won't change anything", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = meta.lockData, onCheckedChange = { m = meta.copy(lockData = it) })
            }
            if (!meta.lockData) {
                Text("Lock fields", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LOCKABLE.forEach { f ->
                        FilterChip(selected = f in meta.lockedFields, onClick = { m = meta.copy(lockedFields = if (f in meta.lockedFields) meta.lockedFields - f else meta.lockedFields + f) }, label = { Text(f) })
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

@Composable
private fun ProviderIdsEditor(ids: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("External IDs", style = MaterialTheme.typography.titleSmall)
        val keys = (listOf("Tmdb", "Imdb", "Tvdb") + ids.keys).distinct()
        keys.forEach { k -> Field(k, ids[k].orEmpty()) { v -> onChange(if (v.isBlank()) ids - k else ids + (k to v.trim())) } }
    }
}

@Composable
private fun IdentifyPane(actions: AppActions, item: MediaItem, notify: (String) -> Unit) {
    val server = actions.server
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(item.displayTitle) }
    var year by remember { mutableStateOf(item.productionYear?.toString().orEmpty()) }
    var results by remember { mutableStateOf<Result<List<RemoteMatch>>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<RemoteMatch?>(null) }
    val supported = item.kind in setOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.BOX_SET, ItemKind.MUSIC_VIDEO)
    fun search() {
        searching = true
        scope.launch {
            results = runCatching { server.remoteSearch(item, name, year.toIntOrNull()) }
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
                        Switch(checked = replaceImages, onCheckedChange = { replaceImages = it })
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

@Composable
private fun ImagesPane(actions: AppActions, item: MediaItem, notify: (String) -> Unit) {
    val server = actions.server
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val images by produceState<Result<List<ItemImage>>?>(null, reload) { value = runCatching { server.itemImages(item.id) } }
    var browsing by remember { mutableStateOf<ImageType?>(null) }
    var uploadType by remember { mutableStateOf(ImageType.PRIMARY) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                server.uploadImage(item.id, uploadType, bytes, mime)
            }.onSuccess { notify("Uploaded"); reload++ }.onFailure { notify("Upload failed: ${it.message}") }
        }
    }
    val list = images?.getOrNull()
    if (list == null) {
        if (images?.isFailure == true) ErrorMessage("Couldn't load images: ${images?.exceptionOrNull()?.message}") else Loading()
        return
    }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(ImageType.entries, key = { it.name }) { type ->
            val ofType = list.filter { it.type == type }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { browsing = type }) { Text("Find") }
                    TextButton(onClick = {
                        uploadType = type
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Upload") }
                }
                if (ofType.isEmpty()) {
                    Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ofType, key = { "${it.type}-${it.index}" }) { img ->
                            Box(Modifier.height(140.dp).aspectRatio(img.width?.let { w -> img.height?.let { h -> w.toFloat() / h } } ?: 1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                AsyncImage(img.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Text(listOfNotNull(img.width, img.height).joinToString("×"), style = MaterialTheme.typography.labelSmall, color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
                                IconButton(onClick = {
                                    scope.launch {
                                        runCatching { server.deleteImage(item.id, img.type, img.index) }.onSuccess { reload++ }.onFailure { notify("Couldn't delete: ${it.message}") }
                                    }
                                }, modifier = Modifier.align(Alignment.TopEnd)) {
                                    Icon(Icons.Default.Delete, "Delete", tint = Color.White, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    browsing?.let { type ->
        RemoteImagesDialog(actions, item, type, onPick = { url ->
            browsing = null
            scope.launch {
                runCatching { server.downloadRemoteImage(item.id, type, url) }.onSuccess { notify("Image set"); reload++ }.onFailure { notify("Couldn't set image: ${it.message}") }
            }
        }, onDismiss = { browsing = null })
    }
}

@Composable
private fun RemoteImagesDialog(actions: AppActions, item: MediaItem, type: ImageType, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val found by produceState<Result<List<RemoteImage>>?>(null, type) { value = runCatching { actions.server.remoteImages(item.id, type) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                Text("${type.label} from providers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            val list = found?.getOrNull()
            when {
                found == null -> Loading()
                list == null -> ErrorMessage("Couldn't search: ${found?.exceptionOrNull()?.message}")
                list.isEmpty() -> Text("No ${type.label.lowercase()} found. The server may not have internet access or a matching provider id.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(ScreenPadding))
                else -> LazyVerticalGrid(GridCells.Adaptive(if (type == ImageType.PRIMARY) 110.dp else 200.dp), contentPadding = PaddingValues(ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.url }) { img ->
                        Column(Modifier.pressable({ onPick(img.url) })) {
                            Box(Modifier.fillMaxWidth().aspectRatio(img.width?.let { w -> img.height?.let { h -> w.toFloat() / h } } ?: 1f).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))) {
                                AsyncImage(img.thumbnailUrl ?: img.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text(listOfNotNull(img.providerName, img.language?.uppercase(), listOfNotNull(img.width, img.height).joinToString("×").ifBlank { null }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
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
    Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RefreshMode.entries.forEach { r ->
            Row(Modifier.fillMaxWidth().selectable(mode == r) { mode = r }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == r, onClick = null)
                Text(r.label, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Replace existing images", modifier = Modifier.weight(1f))
            Switch(checked = replaceImages, onCheckedChange = { replaceImages = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Regenerate scrubbing thumbnails")
                Text("Trickplay images for the seek bar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = trickplay, onCheckedChange = { trickplay = it })
        }
        PillButton("Refresh", Icons.Default.Refresh, primary = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = {
            scope.launch {
                runCatching { actions.server.refreshItem(item.id, mode, replaceImages, trickplay) }
                    .onSuccess { notify("Refresh started on the server") }
                    .onFailure { notify("Couldn't refresh: ${it.message}") }
            }
        })
    }
}

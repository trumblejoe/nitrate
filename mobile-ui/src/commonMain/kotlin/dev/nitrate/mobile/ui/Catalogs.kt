package dev.nitrate.mobile.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.catalogs.CatalogBook
import dev.nitrate.core.catalogs.CatalogItem
import dev.nitrate.core.catalogs.CatalogKind
import dev.nitrate.core.catalogs.CatalogPage
import dev.nitrate.core.catalogs.ComicCatalog
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object CatalogsRoute

@Serializable data class CatalogFeedRoute(val catalogId: String, val target: String? = null)

/** A catalog book to read; the book travels as JSON (catalog books have no ids of Nitrate's own). */
@Serializable data class CatalogBookRoute(val catalogId: String, val book: String)

internal val catalogJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** The Library tab's way in to comic catalogs. */
@Composable
internal fun CatalogsEntry(actions: AppActions) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .pressable({ actions.nav.navigate(CatalogsRoute) }).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Comic catalogs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Read from an OPDS server (Komga, Kavita, Calibre…) or CLU", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = accentColor())
    }
}

/** The comic catalogs added on this device, with adding, changing and removing them. */
@Composable
fun CatalogsScreen(actions: AppActions) {
    val store = actions.container.catalogs
    val catalogs by store.catalogs.collectAsState(emptyList())
    var editing by remember { mutableStateOf<ComicCatalog?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Comic catalogs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(
                    "Comics from servers other than Jellyfin. Kept on this device only, with their sign-in details; where you are in each book is kept here too, and goes back to CLU.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { editing = ComicCatalog(id = "c" + dev.nitrate.android.nowMs().toString(36), name = "", url = "") }) { Text("Add a catalog") }
            }
        }
        items(catalogs.size) { i ->
            val c = catalogs[i]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .pressable({ actions.nav.navigate(CatalogFeedRoute(c.id)) }).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${c.kind.label} · ${c.url}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { editing = c }) { Text("Change") }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
    editing?.let { c ->
        CatalogDialog(
            c, isNew = catalogs.none { it.id == c.id },
            onSave = { scope.launch { store.save(it) }; editing = null },
            onRemove = { scope.launch { store.remove(c.id) }; editing = null },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogDialog(start: ComicCatalog, isNew: Boolean, onSave: (ComicCatalog) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(start.kind) }
    var name by remember { mutableStateOf(start.name) }
    var url by remember { mutableStateOf(start.url) }
    var user by remember { mutableStateOf(start.user.orEmpty()) }
    var password by remember { mutableStateOf(start.password.orEmpty()) }
    var token by remember { mutableStateOf(start.token.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add a catalog" else "Change catalog") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatalogKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }) }
                }
                Text(
                    if (kind == CatalogKind.OPDS) "The catalog's OPDS address, e.g. http://server:25600/opds/v1.2/catalog (Komga) or http://server:5000/opds/browse (CLU)."
                    else "CLU's address, e.g. http://server:5577, and an API token from CLU's My Account → API Tokens.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (kind == CatalogKind.OPDS) {
                    OutlinedTextField(user, { user = it }, label = { Text("User name (if it asks)") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                OutlinedTextField(token, { token = it.trim() }, label = { Text(if (kind == CatalogKind.CLU) "API token" else "Token (instead of a password)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.startsWith("http") && (kind == CatalogKind.OPDS || token.isNotBlank()),
                onClick = {
                    onSave(
                        start.copy(
                            kind = kind, name = name.ifBlank { url.substringAfter("://").substringBefore('/') }, url = url,
                            user = user.takeIf { kind == CatalogKind.OPDS && it.isNotBlank() }, password = password.takeIf { kind == CatalogKind.OPDS && user.isNotBlank() },
                            token = token.ifBlank { null },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** One page of a catalog: its sections and books, with search where the catalog has it, and more as you scroll. */
@Composable
fun CatalogFeedScreen(actions: AppActions, catalogId: String, target: String?) {
    val container = actions.container
    val catalog by produceState<ComicCatalog?>(null, catalogId) { value = container.catalogs.catalog(catalogId) }
    val c = catalog ?: return
    val client = remember(c) { container.catalogs.client(c) }
    var page by remember(c, target) { mutableStateOf<CatalogPage?>(null) }
    var error by remember(c, target) { mutableStateOf<String?>(null) }
    val items = remember(c, target) { mutableStateListOf<CatalogItem>() }
    var next by remember(c, target) { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun show(load: suspend () -> CatalogPage?) {
        error = null
        runCatching { load() }
            .onSuccess { p -> if (p != null) { page = p; items.clear(); items += p.items; next = p.next } }
            .onFailure { error = it.message ?: "The catalog didn't answer." }
    }
    LaunchedEffect(c, target) { show { if (target == null) client.root() else client.open(target) } }

    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, next) {
        val n = next
        if (nearEnd && n != null && !loadingMore) {
            loadingMore = true
            runCatching { client.open(n) }.onSuccess { p -> items += p.items; next = p.next }
            loadingMore = false
        }
    }

    LazyVerticalGrid(GridCells.Adaptive(118.dp), Modifier.fillMaxSize(), grid, PaddingValues(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(page?.title ?: c.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // Tagging, converting and the rest of managing a library happen in CLU itself.
                    if (c.kind == CatalogKind.CLU && target == null) {
                        val platform = dev.nitrate.mobile.platform.rememberPlatformActions()
                        TextButton(onClick = { platform.openUrl(c.url) }) { Text("Open CLU") }
                    }
                }
                if (page?.searchable == true) OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search ${c.name}") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) scope.launch { show { client.search(query.trim()) } } }),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (page == null && error == null) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (page != null && items.isEmpty()) Text("Nothing here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Sections across the width, books as covers.
        items(items.filter { !it.isBook }, key = { "s" + it.id }, span = { GridItemSpan(maxLineSpan) }) { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .pressable({ s.target?.let { actions.nav.navigate(CatalogFeedRoute(catalogId, it)) } }).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    s.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = accentColor())
            }
        }
        items(items.filter { it.isBook }, key = { "b" + it.id }) { b ->
            Column(Modifier.pressable({ b.book?.let { actions.nav.navigate(CatalogBookRoute(catalogId, catalogJson.encodeToString(CatalogBook.serializer(), it))) } })) {
                CatalogCover(actions, c, b.cover, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                Text(b.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                b.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
}

/** A catalog's picture, fetched with the catalog's sign-in details. */
@Composable
internal fun CatalogCover(actions: AppActions, catalog: ComicCatalog, url: String?, modifier: Modifier) {
    val path by produceState<String?>(null, catalog, url) { value = url?.let { actions.container.catalogs.image(catalog, it, actions.container.platform.cacheDir) } }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        path?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

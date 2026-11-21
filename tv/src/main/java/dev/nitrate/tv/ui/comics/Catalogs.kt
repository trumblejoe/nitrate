package dev.nitrate.tv.ui.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.AppContainer
import dev.nitrate.core.catalogs.CatalogBook
import dev.nitrate.core.catalogs.CatalogItem
import dev.nitrate.core.catalogs.CatalogKind
import dev.nitrate.core.catalogs.CatalogPage
import dev.nitrate.core.catalogs.ComicCatalog
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PagePadding = 48.dp

/** The comic catalogs added on this TV (OPDS, CLU), with adding, changing and removing them. */
@Composable
fun CatalogsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val store = container.catalogs
    val catalogs by store.catalogs.collectAsState(emptyList())
    var editing by remember { mutableStateOf<ComicCatalog?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Comic catalogs", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Comics from servers other than Jellyfin: any OPDS catalog (Komga, Kavita, Calibre…) or CLU. Kept on this TV only, with their sign-in details; where you are in each book is kept here too, and goes back to CLU.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(900.dp),
                )
                Button(onClick = { editing = ComicCatalog(id = "c" + dev.nitrate.android.nowMs().toString(36), name = "", url = "") }, modifier = Modifier.focusRequester(first)) { Text("Add a catalog") }
            }
        }
        items(catalogs, key = { it.id }) { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Card(onClick = { onOpen(c.id) }, modifier = Modifier.width(720.dp)) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleLarge)
                        Text("${c.kind.label} · ${c.url}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(onClick = { editing = c }) { Text("Change") }
            }
        }
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

@Composable
private fun CatalogDialog(start: ComicCatalog, isNew: Boolean, onSave: (ComicCatalog) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(start.kind) }
    var name by remember { mutableStateOf(start.name) }
    var url by remember { mutableStateOf(start.url) }
    var user by remember { mutableStateOf(start.user.orEmpty()) }
    var password by remember { mutableStateOf(start.password.orEmpty()) }
    var token by remember { mutableStateOf(start.token.orEmpty()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    TvDialog(onDismiss, width = 760) {
        Text(if (isNew) "Add a catalog" else "Change catalog", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogKind.entries.forEachIndexed { i, k ->
                FilterChip(kind == k, { kind = k }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(k.label) }
            }
        }
        Text(
            if (kind == CatalogKind.OPDS) "The catalog's OPDS address, e.g. http://server:25600/opds/v1.2/catalog (Komga) or http://server:5000/opds/browse (CLU)."
            else "CLU's address, e.g. http://server:5577, and an API token from CLU's My Account → API Tokens.",
            style = MaterialTheme.typography.bodySmall,
        )
        TvTextField(name, { name = it }, "Name", Modifier.fillMaxWidth())
        TvTextField(url, { url = it.trim() }, "Address", Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (kind == CatalogKind.OPDS) {
            TvTextField(user, { user = it }, "User name (if it asks)", Modifier.fillMaxWidth())
            TvTextField(password, { password = it }, "Password", Modifier.fillMaxWidth(), isPassword = true)
        }
        TvTextField(token, { token = it.trim() }, if (kind == CatalogKind.CLU) "API token" else "Token (instead of a password)", Modifier.fillMaxWidth(), isPassword = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
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
            if (!isNew) OutlinedButton(onClick = onRemove) { Text("Remove") }
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

/** One page of a catalog: sections across the top, books as covers, search where the catalog has it, more as you go down. */
@Composable
fun CatalogFeedScreen(container: AppContainer, catalogId: String, target: String?, onOpen: (String) -> Unit, onRead: (CatalogBook) -> Unit) {
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
    val first = remember { FocusRequester() }

    suspend fun show(load: suspend () -> CatalogPage?) {
        error = null
        runCatching { load() }
            .onSuccess { p -> if (p != null) { page = p; items.clear(); items += p.items; next = p.next } }
            .onFailure { error = it.message ?: "The catalog didn't answer." }
    }
    LaunchedEffect(c, target) {
        show { if (target == null) client.root() else client.open(target) }
        delay(80)
        runCatching { first.requestFocus() }
    }

    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, next) {
        val n = next
        if (nearEnd && n != null && !loadingMore) {
            loadingMore = true
            runCatching { client.open(n) }.onSuccess { p -> items += p.items; next = p.next }
            loadingMore = false
        }
    }
    val sections = items.filter { !it.isBook }
    val books = items.filter { it.isBook }

    LazyVerticalGrid(
        GridCells.Adaptive(170.dp), Modifier.fillMaxSize(), grid, PaddingValues(PagePadding),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var qr by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(page?.title ?: c.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Tagging, converting and the rest of managing a library happen in CLU itself: on a phone or computer.
                    if (c.kind == CatalogKind.CLU && target == null) OutlinedButton(onClick = { qr = true }) { Text("Open CLU on your phone") }
                }
                if (qr) TvDialog({ qr = false }) {
                    Text("Open CLU", style = MaterialTheme.typography.headlineSmall)
                    Text("Scan this with your phone to manage the library in CLU: tags, conversions, reading lists.", style = MaterialTheme.typography.bodyMedium)
                    dev.nitrate.tv.ui.components.QrCode(c.url)
                    Text(c.url, style = MaterialTheme.typography.bodySmall)
                    val close = remember { FocusRequester() }
                    Button(onClick = { qr = false }, modifier = Modifier.focusRequester(close)) { Text("Done") }
                    LaunchedEffect(Unit) { delay(80); runCatching { close.requestFocus() } }
                }
                if (page?.searchable == true) TvTextField(
                    query, { query = it }, "Search ${c.name}", Modifier.width(640.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) scope.launch { show { client.search(query.trim()) } } }),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (page == null && error == null) Text("Loading…")
                if (page != null && items.isEmpty()) Text("Nothing here.")
            }
        }
        items(sections, key = { "s" + it.id }, span = { GridItemSpan(2) }) { s ->
            val i = sections.indexOf(s)
            Card(
                onClick = { s.target?.let(onOpen) },
                modifier = Modifier.height(96.dp).let { if (i == 0) it.focusRequester(first) else it },
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    s.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        items(books, key = { "b" + it.id }) { b ->
            val i = books.indexOf(b)
            Column {
                Card(
                    onClick = { b.book?.let(onRead) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).let { if (i == 0 && sections.isEmpty()) it.focusRequester(first) else it },
                ) { CatalogCover(container, c, b.cover) }
                Text(b.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** A catalog's picture, fetched with the catalog's sign-in details. */
@Composable
private fun CatalogCover(container: AppContainer, catalog: ComicCatalog, url: String?) {
    val path by produceState<String?>(null, catalog, url) { value = url?.let { container.catalogs.image(catalog, it, container.platform.cacheDir) } }
    Box(Modifier.fillMaxSize().background(Color(0xFF22252B))) {
        path?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

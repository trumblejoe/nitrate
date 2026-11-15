package dev.nitrate.tv.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.diagnostics.HealthCheck
import dev.nitrate.core.diagnostics.HealthFinding
import dev.nitrate.core.diagnostics.HealthFix
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

/**
 * Library health (Settings, admins): checks a library for missing artwork, descriptions and
 * matches, copies to merge, what this TV always converts, and missing episodes, with a fix for
 * each where Jellyfin has one. Identify and "how it plays" open the item's own screens.
 */
@Composable
fun LibraryHealthScreen(container: AppContainer, onOpen: (String) -> Unit, onManage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val libraries by produceState(emptyList<MediaItem>()) {
        value = runCatching { container.server.views() }.getOrDefault(emptyList()).filter { it.collection == CollectionKind.MOVIES || it.collection == CollectionKind.TV_SHOWS }
    }
    val account by container.account.collectAsState()
    val admin = account?.isAdmin == true
    var libraryId by remember { mutableStateOf(container.lastHealth?.first) }
    var found by remember { mutableStateOf(container.lastHealth?.second) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The first group of findings starts open.
    var open by remember { mutableStateOf(container.lastHealth?.second?.keys?.firstOrNull()) }
    var done by remember { mutableStateOf(setOf<String>()) }
    var confirmMerge by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    fun check() {
        checking = true
        error = null
        scope.launch {
            runCatching { container.checkLibraryHealth(libraryId) }
                .onSuccess { found = it; open = it.keys.firstOrNull(); done = emptySet() }
                .onFailure { error = it.message ?: "Couldn't read the library" }
            checking = false
        }
    }
    fun fix(f: HealthFinding, x: HealthFix) {
        val key = "${f.check}:${f.item.id}"
        when (x) {
            HealthFix.IDENTIFY -> onManage(f.item.id)
            HealthFix.HOW_IT_PLAYS, HealthFix.OPEN -> onOpen(f.item.id)
            HealthFix.MERGE -> if (confirmMerge != key) { confirmMerge = key; return } else Unit
            else -> Unit
        }
        if (x == HealthFix.FIND_IMAGES || x == HealthFix.FIND_METADATA || x == HealthFix.MERGE) scope.launch {
            confirmMerge = null
            runCatching { container.applyHealthFix(f, x) }
                .onSuccess { done = done + key }
                .onFailure { error = it.message }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Library health", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Finds what's missing or off in a library, with a fix where Jellyfin has one. \"Always converted\" is about this TV.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(libraryId == null, { libraryId = null }, modifier = Modifier.focusRequester(first)) { Text("All libraries") } }
                    items(libraries, key = { it.id }) { l -> FilterChip(libraryId == l.id, { libraryId = l.id }) { Text(l.name) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::check, enabled = !checking) { Text(if (found == null) "Check" else "Check again") }
                    if (checking) Text("Reading the library…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (found?.isEmpty() == true) Text("All good: nothing to fix.", color = MaterialTheme.colorScheme.primary)
            }
        }
        found?.forEach { (check, list) ->
            item(key = "h-$check") {
                ListItem(
                    selected = open == check,
                    onClick = { open = if (open == check) null else check },
                    headlineContent = { Text("${check.title} · ${list.size}") },
                    supportingContent = { Text(check.explain) },
                    trailingContent = { Text(if (open == check) "▲" else "▼") },
                )
            }
            if (open == check) items(list, key = { "$check:${it.item.id}" }) { f ->
                val key = "${f.check}:${f.item.id}"
                val fixed = key in done
                Column(Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.item.name + (f.item.productionYear?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (fixed) "Asked the server; it'll update shortly." else f.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fixed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        f.fixes.filter { admin || !it.needsAdmin }.forEach { x ->
                            OutlinedButton(onClick = { fix(f, x) }, enabled = !fixed) {
                                Text(if (x == HealthFix.MERGE && confirmMerge == key) "Select again to merge ${f.related.size + 1} copies" else x.label)
                            }
                        }
                        if (HealthFix.OPEN !in f.fixes && HealthFix.HOW_IT_PLAYS !in f.fixes) OutlinedButton(onClick = { onOpen(f.item.id) }) { Text("Open") }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

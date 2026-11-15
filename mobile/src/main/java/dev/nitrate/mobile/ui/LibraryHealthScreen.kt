package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.diagnostics.HealthCheck
import dev.nitrate.core.diagnostics.HealthFinding
import dev.nitrate.core.diagnostics.HealthFix
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

/**
 * Library health (Settings, admins): checks a library for missing artwork, descriptions and
 * matches, copies to merge, what this phone always converts, and missing episodes, with a fix
 * for each where Jellyfin has one.
 */
@Composable
fun LibraryHealthScreen(actions: AppActions) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val libraries by produceState(emptyList<MediaItem>()) {
        value = runCatching { actions.server.views() }.getOrDefault(emptyList()).filter { it.collection == CollectionKind.MOVIES || it.collection == CollectionKind.TV_SHOWS }
    }
    val account by container.account.collectAsState()
    val admin = account?.isAdmin == true
    var libraryId by remember { mutableStateOf(container.lastHealth?.first) }
    var found by remember { mutableStateOf(container.lastHealth?.second) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The first group of findings starts open.
    var open by remember { mutableStateOf(container.lastHealth?.second?.keys?.firstOrNull()) }
    val done = remember { mutableStateOf(setOf<String>()) }
    var merging by remember { mutableStateOf<HealthFinding?>(null) }
    fun check() {
        checking = true
        error = null
        scope.launch {
            runCatching { container.checkLibraryHealth(libraryId) }
                .onSuccess { found = it; open = it.keys.firstOrNull(); done.value = emptySet() }
                .onFailure { error = it.message ?: "Couldn't read the library" }
            checking = false
        }
    }
    fun fix(f: HealthFinding, x: HealthFix) {
        when (x) {
            HealthFix.IDENTIFY -> actions.nav.navigate(ManageItemRoute(f.item.id))
            HealthFix.HOW_IT_PLAYS, HealthFix.OPEN -> actions.nav.navigate(DetailsRoute(f.item.id))
            HealthFix.MERGE -> merging = f
            else -> scope.launch {
                runCatching { container.applyHealthFix(f, x) }
                    .onSuccess { done.value = done.value + "${f.check}:${f.item.id}" }
                    .onFailure { error = it.message }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Library health", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Finds what's missing or off in a library, with a fix where Jellyfin has one. \"Always converted\" is about this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(libraryId == null, { libraryId = null }, label = { Text("All") })
                    libraries.forEach { l -> FilterChip(libraryId == l.id, { libraryId = l.id }, label = { Text(l.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::check, enabled = !checking) { Text(if (found == null) "Check" else "Check again") }
                    if (checking) { CircularProgressIndicator(Modifier.padding(4.dp)); Text("Reading the library…", style = MaterialTheme.typography.bodySmall) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (found?.isEmpty() == true) Text("All good: nothing to fix.", color = accentColor())
            }
        }
        found?.forEach { (check, list) ->
            item(key = "h-$check") {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (open == check) null else check }.padding(horizontal = ScreenPadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${check.title} · ${list.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(check.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (open == check) "▲" else "▼", color = accentColor())
                }
            }
            if (open == check) items(list, key = { "$check:${it.item.id}" }) { f ->
                val fixed = "${f.check}:${f.item.id}" in done.value
                Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding + 8.dp, vertical = 6.dp)) {
                    Text(f.item.name + (f.item.productionYear?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    Text(if (fixed) "Asked the server; it'll update shortly." else f.detail, style = MaterialTheme.typography.bodySmall, color = if (fixed) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.fixes.filter { admin || !it.needsAdmin }.forEach { x ->
                            OutlinedButton(onClick = { fix(f, x) }, enabled = !fixed) { Text(x.label) }
                        }
                        if (HealthFix.OPEN !in f.fixes && HealthFix.HOW_IT_PLAYS !in f.fixes) TextButton(onClick = { fix(f, HealthFix.OPEN) }) { Text("Open") }
                    }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
    merging?.let { f ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Merge ${f.related.size + 1} copies?") },
            text = { Text("“${f.item.name}” becomes one title with ${f.related.size + 1} versions to choose from. Watched state and favourites of the copies are kept by Jellyfin.") },
            confirmButton = {
                TextButton(onClick = {
                    merging = null
                    scope.launch {
                        runCatching { container.applyHealthFix(f, HealthFix.MERGE) }
                            .onSuccess { done.value = done.value + "${f.check}:${f.item.id}" }
                            .onFailure { e -> error = e.message }
                    }
                }) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }
}

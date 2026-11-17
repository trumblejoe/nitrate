package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.admin.BulkEdit
import dev.nitrate.core.admin.LockableField
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

/** "a, b , c" → {a, b, c}. */
fun words(text: String): Set<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * Edits many titles at once (admins): add or remove genres, tags and studios, set or clear the
 * age rating, lock or unlock fields or whole items. Always shows what will change, and to how
 * many titles, before anything is saved; then each title's result.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkEditSheet(actions: AppActions, items: List<MediaItem>, libraryGenres: List<String>, onDone: (changed: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("edit") }
    var addGenres by remember { mutableStateOf("") }
    var removeGenres by remember { mutableStateOf(setOf<String>()) }
    var addTags by remember { mutableStateOf("") }
    var removeTags by remember { mutableStateOf("") }
    var addStudios by remember { mutableStateOf("") }
    var removeStudios by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<String?>(null) }
    var locks by remember { mutableStateOf(mapOf<LockableField, Boolean>()) }
    var lockItem by remember { mutableStateOf<Boolean?>(null) }
    var progress by remember { mutableStateOf(0 to items.size) }
    var result by remember { mutableStateOf<BulkEdit.Result?>(null) }
    val edit = BulkEdit(
        addGenres = words(addGenres), removeGenres = removeGenres,
        addTags = words(addTags), removeTags = words(removeTags),
        addStudios = words(addStudios), removeStudios = words(removeStudios),
        officialRating = rating,
        lock = locks.filterValues { it }.keys, unlock = locks.filterValues { !it }.keys,
        lockItem = lockItem,
    )
    // The genres the selected titles have (to remove), with the library's as a fallback.
    val presentGenres = (items.flatMap { it.genres } + libraryGenres).distinct().sorted()

    ModalBottomSheet(onDismissRequest = { if (step != "running") onDone(result?.changed?.let { it > 0 } == true) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Edit ${items.size} titles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when (step) {
                "edit" -> {
                    Text("Only what you fill in changes; everything else about each title stays as it is.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Genres", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(addGenres, { addGenres = it }, label = { Text("Add (comma between)") }, modifier = Modifier.fillMaxWidth())
                    if (presentGenres.isNotEmpty()) {
                        Text("Remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            presentGenres.forEach { g -> FilterChip(g in removeGenres, { removeGenres = if (g in removeGenres) removeGenres - g else removeGenres + g }, label = { Text(g) }) }
                        }
                    }
                    Text("Tags", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(addTags, { addTags = it }, label = { Text("Add (comma between)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(removeTags, { removeTags = it }, label = { Text("Remove") }, modifier = Modifier.fillMaxWidth())
                    Text("Studios", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(addStudios, { addStudios = it }, label = { Text("Add (comma between)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(removeStudios, { removeStudios = it }, label = { Text("Remove") }, modifier = Modifier.fillMaxWidth())
                    Text("Age rating", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(rating == null, { rating = null }, label = { Text("Unchanged") })
                        FilterChip(rating == "", { rating = "" }, label = { Text("Clear") })
                        OutlinedTextField(rating?.takeIf { it.isNotEmpty() }.orEmpty(), { rating = it.ifBlank { null } }, label = { Text("Set to") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Text("Locked against refreshes", style = MaterialTheme.typography.titleSmall)
                    Text("Select once to lock, again to unlock, again to leave as it is.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LockableField.entries.forEach { f ->
                            val state = locks[f]
                            FilterChip(state != null, {
                                locks = when (state) { null -> locks + (f to true); true -> locks + (f to false); false -> locks - f }
                            }, label = { Text(when (state) { true -> "🔒 "; false -> "🔓 "; null -> "" } + f.label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(lockItem == null, { lockItem = null }, label = { Text("Whole item: unchanged") })
                        FilterChip(lockItem == true, { lockItem = true }, label = { Text("Lock") })
                        FilterChip(lockItem == false, { lockItem = false }, label = { Text("Unlock") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onDone(false) }) { Text("Cancel") }
                        TextButton(onClick = { step = "review" }, enabled = !edit.isEmpty) { Text("Review") }
                    }
                }
                "review" -> {
                    Text("This will, for each of the ${items.size} titles:", style = MaterialTheme.typography.bodyMedium)
                    edit.describe().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text(items.take(8).joinToString { it.name } + if (items.size > 8) " and ${items.size - 8} more" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = "edit" }) { Text("Back") }
                        TextButton(onClick = {
                            step = "running"
                            scope.launch {
                                result = edit.run(actions.server, items) { done, total -> progress = done to total }
                                step = "done"
                            }
                        }) { Text("Apply to ${items.size}") }
                    }
                }
                "running" -> {
                    Text("Saving ${progress.first} of ${progress.second}…")
                    LinearProgressIndicator(progress = { if (progress.second == 0) 0f else progress.first.toFloat() / progress.second }, modifier = Modifier.fillMaxWidth())
                }
                else -> result?.let { r ->
                    Text("Changed ${r.changed}" + (if (r.unchanged > 0) " · already so: ${r.unchanged}" else "") + if (r.failed.isNotEmpty()) " · couldn't: ${r.failed.size}" else "", style = MaterialTheme.typography.bodyLarge)
                    r.failed.forEach { Text("${it.item.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { onDone(r.changed > 0) }) { Text("Done") }
                }
            }
        }
    }
}

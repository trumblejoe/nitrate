package dev.nitrate.tv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.admin.BulkEdit
import dev.nitrate.core.admin.LockableField
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch

private fun words(text: String): Set<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * Edits many titles at once (admins), D-pad first: genres, tags and studios to add or remove,
 * the age rating, field and item locks. Shows exactly what will change before saving, then each
 * title's result.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkEditDialog(server: MediaServer, items: List<MediaItem>, libraryGenres: List<String>, onDone: (changed: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("edit") }
    var addGenres by remember { mutableStateOf("") }
    var removeGenres by remember { mutableStateOf(setOf<String>()) }
    var addTags by remember { mutableStateOf("") }
    var removeTags by remember { mutableStateOf("") }
    var addStudios by remember { mutableStateOf("") }
    var removeStudios by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<String?>(null) }
    var ratingText by remember { mutableStateOf("") }
    var locks by remember { mutableStateOf(mapOf<LockableField, Boolean>()) }
    var lockItem by remember { mutableStateOf<Boolean?>(null) }
    var progress by remember { mutableStateOf(0 to items.size) }
    var result by remember { mutableStateOf<BulkEdit.Result?>(null) }
    val first = remember { FocusRequester() }
    val edit = BulkEdit(
        addGenres = words(addGenres), removeGenres = removeGenres,
        addTags = words(addTags), removeTags = words(removeTags),
        addStudios = words(addStudios), removeStudios = words(removeStudios),
        officialRating = rating,
        lock = locks.filterValues { it }.keys, unlock = locks.filterValues { !it }.keys,
        lockItem = lockItem,
    )
    val presentGenres = (items.flatMap { it.genres } + libraryGenres).distinct().sorted()

    dev.nitrate.tv.ui.lists.TvDialog({ if (step != "running") onDone(result?.changed?.let { it > 0 } == true) }, width = 980) {
        Text("Edit ${items.size} titles", style = MaterialTheme.typography.headlineSmall)
        when (step) {
            "edit" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onDone(false) }, modifier = Modifier.focusRequester(first)) { Text("Cancel") }
                    Button(onClick = { step = "review" }, enabled = !edit.isEmpty) { Text("Review") }
                }
                Text("Only what you fill in changes; everything else about each title stays as it is. Use commas between several.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Genres", style = MaterialTheme.typography.titleSmall)
                TvTextField(addGenres, { addGenres = it }, "Add genres")
                if (presentGenres.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presentGenres.forEach { g -> FilterChip(g in removeGenres, { removeGenres = if (g in removeGenres) removeGenres - g else removeGenres + g }) { Text("Remove $g") } }
                }
                Text("Tags and studios", style = MaterialTheme.typography.titleSmall)
                TvTextField(addTags, { addTags = it }, "Add tags")
                TvTextField(removeTags, { removeTags = it }, "Remove tags")
                TvTextField(addStudios, { addStudios = it }, "Add studios")
                TvTextField(removeStudios, { removeStudios = it }, "Remove studios")
                Text("Age rating", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(rating == null, { rating = null }) { Text("Unchanged") }
                    FilterChip(rating == "", { rating = "" }) { Text("Clear") }
                    FilterChip(!rating.isNullOrEmpty(), { if (ratingText.isNotBlank()) rating = ratingText.trim() }) { Text(if (ratingText.isBlank()) "Set (type below)" else "Set to ${ratingText.trim()}") }
                }
                TvTextField(ratingText, { ratingText = it; if (!rating.isNullOrEmpty()) rating = it.trim().ifEmpty { null } }, "Age rating to set (e.g. PG-13)")
                Text("Locked against refreshes (select: lock, again: unlock, again: unchanged)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LockableField.entries.forEach { f ->
                        val state = locks[f]
                        FilterChip(state != null, { locks = when (state) { null -> locks + (f to true); true -> locks + (f to false); false -> locks - f } }) {
                            Text(when (state) { true -> "🔒 "; false -> "🔓 "; null -> "" } + f.label)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(lockItem == null, { lockItem = null }) { Text("Whole item: unchanged") }
                    FilterChip(lockItem == true, { lockItem = true }) { Text("Lock whole item") }
                    FilterChip(lockItem == false, { lockItem = false }) { Text("Unlock whole item") }
                }
            }
            "review" -> {
                Text("This will, for each of the ${items.size} titles:")
                edit.describe().forEach { Text("• $it") }
                Text(items.take(10).joinToString { it.name } + if (items.size > 10) " and ${items.size - 10} more" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { step = "edit" }, modifier = Modifier.focusRequester(first)) { Text("Back") }
                    Button(onClick = {
                        step = "running"
                        scope.launch {
                            result = edit.run(server, items) { done, total -> progress = done to total }
                            step = "done"
                        }
                    }) { Text("Apply to ${items.size}") }
                }
            }
            "running" -> Text("Saving ${progress.first} of ${progress.second}…")
            else -> result?.let { r ->
                Text("Changed ${r.changed}" + (if (r.unchanged > 0) " · already so: ${r.unchanged}" else "") + if (r.failed.isNotEmpty()) " · couldn't: ${r.failed.size}" else "")
                r.failed.forEach { Text("${it.item.name}: ${it.reason}", color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onDone(r.changed > 0) }, modifier = Modifier.focusRequester(first)) { Text("Done") }
            }
        }
    }
    LaunchedEffect(step) { kotlinx.coroutines.delay(50); runCatching { first.requestFocus() } }
}

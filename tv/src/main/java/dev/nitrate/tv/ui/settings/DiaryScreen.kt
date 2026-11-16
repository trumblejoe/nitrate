package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.diary.Diary
import dev.nitrate.core.diary.DiaryEntry
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The watch diary on TV (Companion plugin): every film and episode finished, newest first, with
 * the user's rating and note. OK on one rates it, adds a note or removes it.
 */
@Composable
fun DiaryScreen(server: MediaServer) {
    var reload by remember { mutableIntStateOf(0) }
    val entries by produceState<Result<List<DiaryEntry>>?>(null, reload) { value = runCatching { server.diary() } }
    var editing by remember { mutableStateOf<DiaryEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = entries?.getOrNull()
    val rewatches = remember(list) { list?.let(Diary::rewatches).orEmpty() }
    val date = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()) }
    val first = remember { FocusRequester() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text("Watch diary", style = MaterialTheme.typography.displaySmall)
            Text("Every film and episode you finish, in any app. Export it for Letterboxd from the phone app.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        when {
            entries == null -> item { Text("Opening your diary…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            entries?.isFailure == true -> item { Text("Couldn't open your diary: ${entries?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error) }
            list.isNullOrEmpty() -> item { Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(list, key = { it.id }) { e ->
                ListItem(
                    selected = false,
                    onClick = { editing = e },
                    modifier = if (e.id == list.first().id) Modifier.focusRequester(first) else Modifier,
                    overlineContent = { Text(date.format(Date(e.watchedAtMs))) },
                    headlineContent = { Text(e.title + (e.year?.takeIf { e.episodeLabel == null }?.let { " ($it)" } ?: "")) },
                    supportingContent = {
                        val sub = listOfNotNull(e.episodeLabel, "Rewatch".takeIf { e.id in rewatches }, e.note)
                        if (sub.isNotEmpty()) Text(sub.joinToString(" · "), maxLines = 1)
                    },
                    trailingContent = { Text(Diary.stars(e.rating), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
    LaunchedEffect(list?.firstOrNull()?.id) { if (list?.isNotEmpty() == true) runCatching { first.requestFocus() } }

    editing?.let { e ->
        DiaryEditDialog(
            e,
            onSave = { rating, note ->
                editing = null
                scope.launch {
                    runCatching { server.editDiary(e.id, rating = rating ?: 0, note = note) }.onFailure { message = "Couldn't save: ${it.message}" }
                    reload++
                }
            },
            onDelete = {
                editing = null
                scope.launch {
                    runCatching { server.deleteDiary(e.id) }.onFailure { message = "Couldn't remove it: ${it.message}" }
                    reload++
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiaryEditDialog(e: DiaryEntry, onSave: (rating: Int?, note: String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var rating by remember { mutableStateOf(e.rating) }
    var note by remember { mutableStateOf(e.note.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(820.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(e.title, style = MaterialTheme.typography.headlineSmall)
            e.episodeLabel?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Your rating", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = rating == null, onClick = { rating = null }, modifier = Modifier.focusRequester(focus)) { Text("None") }
                (1..10).forEach { r -> FilterChip(selected = rating == r, onClick = { rating = r }) { Text(Diary.stars(r)) } }
            }
            TvTextField(note, { note = it }, placeholder = "A note (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSave(rating, note) }) { Text("Save") }
                OutlinedButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) { Text(if (confirmDelete) "Remove from diary" else "Remove…") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.core.diary.Diary
import dev.nitrate.core.diary.DiaryEntry
import kotlinx.coroutines.launch

/**
 * The watch diary (Companion plugin): every film and episode finished, newest first by month,
 * with the user's rating and note. Tap one to rate it, add a note or remove it; films export
 * for Letterboxd.
 */
@Composable
fun DiaryScreen(actions: AppActions) {
    var reload by remember { mutableIntStateOf(0) }
    val entries by produceState<Result<List<DiaryEntry>>?>(null, reload) { value = runCatching { actions.server.diary() } }
    var editing by remember { mutableStateOf<DiaryEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val files = dev.nitrate.mobile.platform.rememberFilePickers(onOpened = {}, onResult = { message = it })
    val list = entries?.getOrNull()
    val rewatches = remember(list) { list?.let(Diary::rewatches).orEmpty() }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Watch diary", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (!list.isNullOrEmpty()) TextButton(onClick = {
                    val offset = dev.nitrate.mobile.platform.timeZoneOffsetMs()
                    files.save("nitrate-diary-letterboxd.csv", "text/csv", Diary.letterboxdCsv(list, offset).encodeToByteArray())
                }) { Text("Export") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when {
            entries == null -> item { Text("Opening your diary…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            entries?.isFailure == true -> item { Text("Couldn't open your diary: ${entries?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error) }
            list.isNullOrEmpty() -> item { Text("Nothing yet. Every film and episode you finish, in any app, is added here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> list.groupBy { dev.nitrate.mobile.platform.formatPattern(it.watchedAtMs, "MMMM yyyy") }.forEach { (name, inMonth) ->
                item(key = name) { Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                items(inMonth, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().pressable({ editing = e }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dev.nitrate.mobile.platform.formatPattern(e.watchedAtMs, "EEE d"), style = MaterialTheme.typography.labelMedium, color = accentColor(), modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title + (e.year?.takeIf { e.episodeLabel == null }?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(e.episodeLabel, "Rewatch".takeIf { e.id in rewatches })
                            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        Text(Diary.stars(e.rating), color = accentColor(), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }

    editing?.let { e ->
        DiaryEditDialog(
            e,
            onSave = { rating, note ->
                editing = null
                scope.launch {
                    runCatching { actions.server.editDiary(e.id, rating = rating ?: 0, note = note) }.onFailure { message = "Couldn't save: ${it.message}" }
                    reload++
                }
            },
            onDelete = {
                editing = null
                scope.launch {
                    runCatching { actions.server.deleteDiary(e.id) }.onFailure { message = "Couldn't remove it: ${it.message}" }
                    reload++
                }
            },
            onDismiss = { editing = null },
        )
    }
}

/** Rate (half stars), add a note, or remove an entry. */
@Composable
private fun DiaryEditDialog(e: DiaryEntry, onSave: (rating: Int?, note: String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var rating by remember { mutableFloatStateOf((e.rating ?: 0).toFloat()) }
    var note by remember { mutableStateOf(e.note.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                e.episodeLabel?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    if (rating.toInt() == 0) "Not rated" else Diary.stars(rating.toInt()),
                    style = MaterialTheme.typography.headlineSmall, color = accentColor(),
                )
                Slider(value = rating, onValueChange = { rating = it }, valueRange = 0f..10f, steps = 9)
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (confirmDelete) Text("Remove this watch from your diary?", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rating.toInt().takeIf { it > 0 }, note) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) { Text(if (confirmDelete) "Remove" else "Remove…", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

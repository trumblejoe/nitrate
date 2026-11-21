package dev.nitrate.tv.ui.comics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import dev.nitrate.core.comics.ComicInfoFields
import dev.nitrate.core.comics.ComicListing
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An admin changing a CBZ book's ComicInfo (Companion feature `comics`), saved into the file on the server. */
@Composable
fun ComicInfoDialog(server: MediaServer, item: MediaItem, onDone: () -> Unit) {
    val listing by produceState<Result<ComicListing?>?>(null, item.id) { value = runCatching { server.comicListing(item.id) } }
    val before = remember(listing) { ComicInfoFields.values(listing?.getOrNull()?.comicInfo) }
    val values = remember(before) { mutableStateMapOf<String, String>().apply { putAll(before) } }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val l = listing
    val editable = l?.getOrNull()?.format == "zip"
    LaunchedEffect(l) { delay(80); runCatching { first.requestFocus() } }
    TvDialog(onDone, width = 820) {
        Text("Comic details", style = MaterialTheme.typography.headlineSmall)
        when {
            l == null -> Text("Reading the book…")
            !editable -> {
                Text(l.exceptionOrNull()?.message ?: "Only CBZ books can be changed here. Convert others to CBZ first (CLU can convert a whole library).")
                Button(onClick = onDone, modifier = Modifier.focusRequester(first)) { Text("Back") }
            }
            else -> {
                Text("Saved into the book's ComicInfo.xml on the server; Jellyfin reads it again afterwards.", style = MaterialTheme.typography.bodySmall)
                ComicInfoFields.EDITABLE.forEachIndexed { i, f ->
                    TvTextField(values[f.name].orEmpty(), { values[f.name] = it }, f.label, Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it })
                }
                Text("Reading direction", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicInfoFields.MANGA.forEach { (v, label) -> FilterChip((values["Manga"] ?: "") == v, { values["Manga"] = v }) { Text(label) } }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !saving, onClick = {
                        val changes = ComicInfoFields.changes(before, values.toMap())
                        if (changes.isEmpty()) return@Button onDone()
                        saving = true
                        scope.launch {
                            runCatching { server.saveComicInfo(item.id, changes) }
                                .onSuccess { onDone() }
                                .onFailure { message = it.message ?: "Couldn't save."; saving = false }
                        }
                    }) { Text(if (saving) "Saving…" else "Save") }
                    OutlinedButton(onClick = onDone) { Text("Cancel") }
                }
            }
        }
    }
}

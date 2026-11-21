package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nitrate.core.comics.ComicInfoFields
import dev.nitrate.core.comics.ComicListing
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

/** "Edit comic details" on a book's page: admins, with the Companion plugin, for CBZ books. */
@Composable
internal fun ComicInfoButton(actions: AppActions, item: MediaItem) {
    val account by actions.container.account.collectAsState()
    val companion by actions.container.companion.collectAsState()
    if (account?.isAdmin != true || companion?.features?.contains(ComicListing.FEATURE) != true) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Edit comic details") }
    if (open) ComicInfoDialog(actions, item) { open = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicInfoDialog(actions: AppActions, item: MediaItem, onDone: () -> Unit) {
    val listing by produceState<Result<ComicListing?>?>(null, item.id) { value = runCatching { actions.server.comicListing(item.id) } }
    val before = remember(listing) { ComicInfoFields.values(listing?.getOrNull()?.comicInfo) }
    val values = remember(before) { mutableStateMapOf<String, String>().apply { putAll(before) } }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val l = listing
    val editable = l?.getOrNull()?.format == "zip"
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Comic details") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    l == null -> Text("Reading the book…")
                    !editable -> Text(
                        l.exceptionOrNull()?.message ?: "Only CBZ books can be changed here. Convert others to CBZ first (CLU can convert a whole library).",
                    )
                    else -> {
                        Text("Saved into the book's ComicInfo.xml on the server; Jellyfin reads it again afterwards.", style = MaterialTheme.typography.bodySmall)
                        ComicInfoFields.EDITABLE.forEach { f ->
                            OutlinedTextField(
                                values[f.name].orEmpty(), { values[f.name] = it }, Modifier.fillMaxWidth(),
                                label = { Text(f.label) }, singleLine = !f.long, minLines = if (f.long) 3 else 1,
                            )
                        }
                        Text("Reading direction", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ComicInfoFields.MANGA.forEach { (v, label) -> FilterChip((values["Manga"] ?: "") == v, { values["Manga"] = v }, label = { Text(label) }) }
                        }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = editable && !saving, onClick = {
                val changes = ComicInfoFields.changes(before, values.toMap())
                if (changes.isEmpty()) return@TextButton onDone()
                saving = true
                scope.launch {
                    runCatching { actions.server.saveComicInfo(item.id, changes) }
                        .onSuccess { onDone() }
                        .onFailure { message = it.message ?: "Couldn't save."; saving = false }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

package dev.nitrate.tv.ui.player

import dev.nitrate.core.playback.withSoundMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.MediaServer
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.RemoteSubtitle
import kotlinx.coroutines.launch

private val LANGUAGES = listOf("eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German", "ita" to "Italian", "por" to "Portuguese", "jpn" to "Japanese", "kor" to "Korean")

/** Find subtitles on subtitle sites through the server, by language; [onDone] reloads with the new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleSearch(repository: MediaServer, session: PlaybackSession, onDone: (String) -> Unit, onBack: () -> Unit) {
    val item by session.item.collectAsState()
    var language by remember { mutableStateOf("eng") }
    var downloading by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val itemId = item?.id
    val results by produceState<Result<List<RemoteSubtitle>>?>(null, language, itemId) {
        value = null
        if (itemId != null) value = runCatching { repository.searchSubtitles(itemId, language) }
    }
    val first = remember { FocusRequester() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Find subtitles", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGUAGES.forEachIndexed { i, (code, name) -> FilterChip(selected = language == code, onClick = { language = code }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(name) } }
    }
    val list = results?.getOrNull()
    when {
        results == null -> Text("Searching…")
        list == null -> Text("Search failed. The server needs a subtitle provider plugin such as OpenSubtitles.", color = MaterialTheme.colorScheme.error)
        list.isEmpty() -> Text("No subtitles found in that language.")
        else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(list, key = { it.id }) { sub ->
                ListItem(
                    selected = false,
                    enabled = downloading == null,
                    onClick = {
                        downloading = sub.id
                        scope.launch { runCatching { repository.downloadSubtitle(itemId!!, sub.id) }.onSuccess { onDone(language) }.onFailure { downloading = null } }
                    },
                    headlineContent = { Text(sub.name, maxLines = 1) },
                    supportingContent = {
                        Text(listOfNotNull(sub.provider, "Matches this file".takeIf { sub.hashMatch }, sub.downloads?.let { "$it downloads" }, "Forced".takeIf { sub.forced },
                            "SDH".takeIf { sub.hearingImpaired }, "Downloading…".takeIf { downloading == sub.id }).joinToString(" · "))
                    },
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

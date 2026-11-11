package dev.nitrate.tv.ui.player

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

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)
private val LANGUAGES = listOf("eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German", "ita" to "Italian", "por" to "Portuguese", "jpn" to "Japanese", "kor" to "Korean")

/** Speed, sleep timer, night mode, subtitle timing and subtitle search, for the remote. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerMoreDialog(
    repository: MediaServer,
    settings: PlaybackSettingsRepository,
    session: PlaybackSession,
    sleepAt: Long?,
    sleepAtEnd: Boolean,
    onSleep: (Long?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val speed by session.speed.collectAsState()
    val night by session.nightModeOn.collectAsState()
    val offset by session.subtitleOffsetMs.collectAsState()
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(760.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (searching) {
                SubtitleSearch(repository, session, onDone = { lang -> session.reloadWithNewSubtitle(lang); onDismiss() }, onBack = { searching = false })
                return@Column
            }
            Text("Playback", style = MaterialTheme.typography.headlineSmall)
            Text("Speed", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEachIndexed { i, s ->
                    FilterChip(selected = speed == s, onClick = { session.setSpeed(s) }, modifier = if (i == 2) Modifier.focusRequester(first) else Modifier) { Text(if (s == 1f) "Normal" else "${s}×") }
                }
            }
            Text("Sleep timer" + (sleepAt?.let { " · stops at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))}" } ?: if (sleepAtEnd) " · end of this" else ""), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sleepAt == null && !sleepAtEnd, onClick = { onSleep(null, false) }) { Text("Off") }
                SLEEP_MINUTES.forEach { m -> FilterChip(selected = false, onClick = { onSleep(System.currentTimeMillis() + m * 60_000L, false) }) { Text("$m min") } }
                FilterChip(selected = sleepAtEnd, onClick = { onSleep(null, true) }) { Text("End of this") }
            }
            ListItem(
                selected = false,
                onClick = {
                    session.setNightMode(!night)
                    scope.launch { settings.update { it.copy(nightMode = !night) } }
                },
                headlineContent = { Text("Night mode") },
                supportingContent = { Text("Quieter loud parts, clearer dialogue (when this TV decodes the audio, not with passthrough)") },
                trailingContent = { Switch(checked = night, onCheckedChange = null) },
            )
            Text("Subtitle timing" + if (offset != 0L) " · ${if (offset > 0) "+" else ""}${offset / 1000.0}s" else "", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1000L, -250L, 250L, 1000L).forEach { d ->
                    OutlinedButton(onClick = { session.setSubtitleOffset(offset + d) }) { Text((if (d > 0) "+" else "−") + "${kotlin.math.abs(d) / 1000.0}s") }
                }
                if (offset != 0L) OutlinedButton(onClick = { session.setSubtitleOffset(0) }) { Text("Reset") }
            }
            ListItem(selected = false, onClick = { searching = true }, headlineContent = { Text("Find subtitles…") },
                supportingContent = { Text("Search subtitle sites through your server") })
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleSearch(repository: MediaServer, session: PlaybackSession, onDone: (String) -> Unit, onBack: () -> Unit) {
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

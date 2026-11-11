package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.playback.RemoteSubtitle
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

/** Languages offered for subtitle search (ISO 639-2), most common first. */
val SUBTITLE_LANGUAGES = listOf("eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German", "ita" to "Italian", "por" to "Portuguese",
    "dut" to "Dutch", "swe" to "Swedish", "nor" to "Norwegian", "dan" to "Danish", "fin" to "Finnish", "pol" to "Polish", "rus" to "Russian",
    "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese", "ara" to "Arabic", "heb" to "Hebrew", "tur" to "Turkish", "hin" to "Hindi")

private enum class MorePage { MAIN, SUBTITLES, PLAY_ON, TOGETHER }

/** Speed, sleep timer, night mode, subtitle timing and search, play elsewhere, picture-in-picture. */
@Composable
fun PlayerMoreSheet(
    actions: AppActions,
    session: PlaybackSession,
    sleepAt: Long?,
    sleepAtEnd: Boolean,
    onSleep: (at: Long?, atEnd: Boolean) -> Unit,
    onPictureInPicture: () -> Unit,
    onPlayedElsewhere: () -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(MorePage.MAIN) }
    val speed by session.speed.collectAsState()
    val night by session.nightModeOn.collectAsState()
    val offset by session.subtitleOffsetMs.collectAsState()
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (page) {
                    MorePage.MAIN -> "Playback"
                    MorePage.SUBTITLES -> "Find subtitles"
                    MorePage.PLAY_ON -> "Play on another device"
                    MorePage.TOGETHER -> "Watch together"
                },
            )
        },
        text = {
            when (page) {
                MorePage.MAIN -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Section("Speed") {
                        SPEEDS.forEach { s -> FilterChip(selected = speed == s, onClick = { session.setSpeed(s) }, label = { Text(if (s == 1f) "Normal" else "${s}×") }) }
                    }
                    Section("Sleep timer" + (sleepAt?.let { " · stops at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))}" } ?: if (sleepAtEnd) " · end of this" else "")) {
                        FilterChip(selected = sleepAt == null && !sleepAtEnd, onClick = { onSleep(null, false) }, label = { Text("Off") })
                        SLEEP_MINUTES.forEach { m -> FilterChip(selected = false, onClick = { onSleep(System.currentTimeMillis() + m * 60_000L, false) }, label = { Text("$m min") }) }
                        FilterChip(selected = sleepAtEnd, onClick = { onSleep(null, true) }, label = { Text("End of this") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { setNight(actions, session, !night) }) {
                        Column(Modifier.weight(1f)) {
                            Text("Night mode", style = MaterialTheme.typography.bodyLarge)
                            Text("Quieter loud parts, clearer dialogue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = night, onCheckedChange = { setNight(actions, session, it) })
                    }
                    Section("Subtitle timing" + if (offset != 0L) " · ${if (offset > 0) "+" else ""}${offset / 1000.0}s" else "") {
                        listOf(-1000L, -250L, 250L, 1000L).forEach { d -> OutlinedButton(onClick = { session.setSubtitleOffset(offset + d) }) { Text((if (d > 0) "+" else "−") + "${kotlin.math.abs(d) / 1000.0}s") } }
                        if (offset != 0L) TextButton(onClick = { session.setSubtitleOffset(0) }) { Text("Reset") }
                    }
                    HorizontalDivider()
                    MenuRow("Find subtitles…", "Search subtitle sites through your server") { page = MorePage.SUBTITLES }
                    val group by actions.container.syncPlay.group.collectAsState()
                    MenuRow("Watch together…", group?.let { "In ${it.name} · ${it.participants.size} watching" } ?: "Play in step with others, anywhere") { page = MorePage.TOGETHER }
                    MenuRow("Play on another device…", "Continue on your TV or another app") { page = MorePage.PLAY_ON }
                    MenuRow("Picture-in-picture", "Keep watching in a small window", onPictureInPicture)
                }
                MorePage.SUBTITLES -> SubtitleSearch(actions, session, onDone = { language ->
                    session.reloadWithNewSubtitle(language)
                    onDismiss()
                })
                MorePage.PLAY_ON -> PlayOn(actions, session, onPlayed = onPlayedElsewhere)
                MorePage.TOGETHER -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    WatchTogetherPanel(actions.container.syncPlay, defaultGroupName(actions))
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (page == MorePage.MAIN) onDismiss() else page = MorePage.MAIN }) { Text(if (page == MorePage.MAIN) "Close" else "Back") } },
    )
}

private fun setNight(actions: AppActions, session: PlaybackSession, on: Boolean) {
    session.setNightMode(on)
    // Remembered for next time on this device.
    actions.scope.launch { actions.container.playbackSettings.update { it.copy(nightMode = on) } }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun MenuRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubtitleSearch(actions: AppActions, session: PlaybackSession, onDone: (String) -> Unit) {
    val item by session.item.collectAsState()
    var language by remember { mutableStateOf("eng") }
    var downloading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val itemId = item?.id
    val results by produceState<Result<List<RemoteSubtitle>>?>(null, language, itemId) {
        value = null
        if (itemId != null) value = runCatching { actions.server.searchSubtitles(itemId, language) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SUBTITLE_LANGUAGES.take(8).forEach { (code, name) -> FilterChip(selected = language == code, onClick = { language = code }, label = { Text(name) }) }
        }
        val list = results?.getOrNull()
        when {
            results == null -> CircularProgressIndicator()
            list == null -> Text("Search failed. The server needs a subtitle provider plugin (e.g. OpenSubtitles). ${results?.exceptionOrNull()?.message.orEmpty()}", color = MaterialTheme.colorScheme.error)
            list.isEmpty() -> Text("No subtitles found in that language.")
            else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(list, key = { it.id }) { sub ->
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = downloading == null) {
                            downloading = sub.id
                            scope.launch {
                                runCatching { actions.server.downloadSubtitle(itemId!!, sub.id) }
                                    .onSuccess { onDone(language) }
                                    .onFailure { error = it.message; downloading = null }
                            }
                        }.padding(vertical = 8.dp),
                    ) {
                        Text(sub.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            listOfNotNull(
                                sub.provider, sub.format?.uppercase(), "Matches this file".takeIf { sub.hashMatch }, sub.downloads?.let { "$it downloads" },
                                "Forced".takeIf { sub.forced }, "SDH".takeIf { sub.hearingImpaired }, "Machine translated".takeIf { sub.machineTranslated },
                                "Downloading…".takeIf { downloading == sub.id },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sub.hashMatch) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        error?.let { Text("Couldn't download: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PlayOn(actions: AppActions, session: PlaybackSession, onPlayed: () -> Unit) {
    val item by session.item.collectAsState()
    val scope = rememberCoroutineScope()
    val targets by produceState<Result<List<ServerSession>>?>(null) {
        value = runCatching { actions.server.sessions().filter { it.supportsMediaControl && it.deviceId != actions.server.deviceId } }
    }
    val list = targets?.getOrNull()
    when {
        targets == null -> CircularProgressIndicator()
        list.isNullOrEmpty() -> Text("No other devices can be controlled right now. Open Nitrate or another Jellyfin app on your TV first.")
        else -> Column {
            list.forEach { t ->
                MenuRow(t.deviceName ?: t.client ?: "Device", listOfNotNull(t.userName, t.client).joinToString(" · ")) {
                    val id = item?.id ?: return@MenuRow
                    val at = session.player.currentPosition
                    scope.launch {
                        runCatching { actions.server.playOn(t.id, listOf(id), at) }.onSuccess { onPlayed() }
                    }
                }
            }
        }
    }
}

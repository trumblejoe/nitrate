package dev.nitrate.tv.ui.settings

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.playback.Answer
import dev.nitrate.core.playback.CheckClip
import dev.nitrate.core.playback.DeviceCheck
import dev.nitrate.core.playback.SeenClip
import dev.nitrate.core.playback.TvCheck
import kotlinx.coroutines.launch

/** Amber for "No", green for "Yes" (whatever the theme's own colours). */
private val Amber = Color(0xFFFFB74D)
private val Green = Color(0xFF81C784)

private val PCM = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_8BIT)

/**
 * Settings → Tools → TV & receiver check: the Companion plugin's clips (HDR10, Dolby Vision, HLG;
 * Dolby Digital, DD+, TrueHD, DTS, 7.1 PCM) played full screen one by one, each followed by "Did
 * you see it?", since only the person in front of the TV can see its badge or the receiver's
 * display. Answers are kept per device ([DeviceCheck.seen]), quoted by How it plays, and turned
 * into settings to change ([TvCheck.advice]).
 */
@OptIn(UnstableApi::class)
@Composable
fun TvCheckScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clips by produceState<List<CheckClip>?>(null) { value = runCatching { container.server.checkClips() }.getOrDefault(emptyList()) }
    val settings by container.playbackSettings.settings.collectAsState(null)
    val seen = settings?.deviceCheck?.seen.orEmpty()
    // The clips still to play when checking them all, and the one playing or being asked about.
    var queue by remember { mutableStateOf(emptyList<CheckClip>()) }
    var playing by remember { mutableStateOf<CheckClip?>(null) }
    var asking by remember { mutableStateOf<CheckClip?>(null) }
    var encoding by remember { mutableStateOf<Int?>(null) }
    var applied by remember { mutableStateOf(emptySet<String>()) }
    val first = remember { FocusRequester() }

    fun play(clip: CheckClip) {
        encoding = null
        playing = clip
    }

    fun answer(clip: CheckClip, a: Answer) {
        val record = SeenClip(
            answer = a, format = clip.format, title = clip.title, atMs = System.currentTimeMillis(),
            passthrough = encoding?.let { it !in PCM }?.takeIf { !clip.isPicture },
            displayHdr = dev.nitrate.android.playback.DisplayInfo.read(context)?.hdr?.takeIf { clip.isPicture },
        )
        scope.launch {
            container.playbackSettings.update { s ->
                val check = s.deviceCheck ?: DeviceCheck(System.currentTimeMillis(), container.capabilities.model)
                s.copy(deviceCheck = check.copy(seen = check.seen + (clip.id to record)))
            }
        }
        asking = null
        queue.firstOrNull()?.let { next -> queue = queue.drop(1); play(next) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TV & receiver check", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Short clips from your server, each about 20 seconds. Watch the TV for its HDR or Dolby Vision badge (or its picture mode changing) " +
                        "and the receiver's display for the sound format, then say what you saw. Only you can see those; Nitrate keeps your answers for this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    clips == null -> Text("Asking the server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    clips!!.isEmpty() -> Text("Your server doesn't have the clips: they come with the Nitrate Companion plugin (update it in Jellyfin's Dashboard → Plugins).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Button(onClick = { val all = clips!!; queue = all.drop(1); play(all.first()) }, modifier = Modifier.focusRequester(first)) {
                        Text("Check them all (about ${(clips!!.size * 30 + 59) / 60} min)")
                    }
                }
            }
        }
        val list = clips.orEmpty()
        listOf("Picture: does the TV switch?" to list.filter { it.isPicture }, "Sound: what does the receiver show?" to list.filter { !it.isPicture }).forEach { (title, group) ->
            if (group.isNotEmpty()) item { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
            items(group, key = { it.id }) { clip ->
                val s = seen[clip.id]
                ListItem(
                    selected = false,
                    onClick = { queue = emptyList(); play(clip) },
                    headlineContent = { Text(clip.title) },
                    supportingContent = { Text(clip.lookFor, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        s?.let {
                            Text(
                                when (it.answer) { Answer.YES -> "Seen"; Answer.NO -> "Not seen"; Answer.UNSURE -> "Not sure" } +
                                    (it.passthrough?.let { p -> if (p) " · sent untouched" else " · decoded here" } ?: ""),
                                color = when (it.answer) { Answer.NO -> Amber; Answer.YES -> Green; Answer.UNSURE -> MaterialTheme.colorScheme.onSurfaceVariant },
                            )
                        } ?: Text("Not checked", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
        val advice = settings?.let { TvCheck.advice(seen, container.capabilities, it) }.orEmpty()
        if (advice.isNotEmpty()) {
            item { Text("What to do", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
            items(advice, key = { it.text }) { a ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val change = a.change
                    if (change != null) {
                        val key = a.changeLabel ?: a.text
                        if (key in applied) Text("Done", color = MaterialTheme.colorScheme.primary)
                        else OutlinedButton(onClick = { scope.launch { container.playbackSettings.update(change::apply); applied = applied + key } }) { Text(a.changeLabel ?: "Change it") }
                    }
                }
            }
        }
    }
    LaunchedEffect(clips) { if (!clips.isNullOrEmpty()) runCatching { first.requestFocus() } }

    playing?.let { clip ->
        Dialog(onDismissRequest = { playing = null; asking = clip }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val player = remember(clip.id) {
                val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to container.server.authorizationHeader()))
                ExoPlayer.Builder(context)
                    .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                    .build().apply {
                        addAnalyticsListener(object : AnalyticsListener {
                            override fun onAudioTrackInitialized(e: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                                encoding = config.encoding
                            }
                        })
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_ENDED) { playing = null; asking = clip }
                            }
                        })
                        container.server.checkClipUrl(clip.id)?.let { setMediaItem(MediaItem.fromUri(it)) }
                        prepare()
                        playWhenReady = true
                    }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize())
                Text(
                    "${clip.title} · Back to stop",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }

    asking?.let { clip ->
        dev.nitrate.tv.ui.lists.TvDialog(onDismiss = { asking = null; queue = emptyList() }, width = 760) {
            Text(clip.title, style = MaterialTheme.typography.headlineSmall)
            Text(clip.lookFor, style = MaterialTheme.typography.bodyMedium)
            Text(if (clip.isPicture) "Did the TV show it?" else "Did the receiver show it?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            val firstAnswer = remember { FocusRequester() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Answer.entries.forEachIndexed { i, a ->
                    FilterChip(false, { answer(clip, a) }, modifier = if (i == 0) Modifier.focusRequester(firstAnswer) else Modifier) { Text(a.label) }
                }
                OutlinedButton(onClick = { asking = null; play(clip) }) { Text("Play it again") }
            }
            if (queue.isNotEmpty()) Text("${queue.size} more to go", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            LaunchedEffect(Unit) { runCatching { firstAnswer.requestFocus() } }
        }
    }
}

package dev.nitrate.tv.ui.settings

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.tv.data.playback.ClipKind
import dev.nitrate.tv.data.playback.ClipResult
import dev.nitrate.tv.data.playback.DeviceCapabilities
import dev.nitrate.tv.data.playback.Outcome
import dev.nitrate.tv.data.playback.PlaybackSettingsStore
import dev.nitrate.tv.data.playback.PlaybackTest
import dev.nitrate.tv.data.playback.Suggestion
import dev.nitrate.tv.data.playback.TestClip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val PagePadding = 58.dp
private const val CLIP_TIMEOUT_MS = 12_000L

/** More than this share of frames dropped means the decoder can't keep up in real time. */
private const val MAX_DROPPED_SHARE = 0.3

/**
 * Plays each bundled clip and records what really happens, next to what the TV claims.
 * The two often differ: decoders that list a format but fail on it, or work but aren't listed.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlaybackTestScreen(
    store: PlaybackSettingsStore,
    capabilities: DeviceCapabilities,
) {
    val context = LocalContext.current
    var decoder by remember { mutableStateOf<String?>(null) }
    val player = remember {
        ExoPlayer.Builder(context)
            // Same fallback as real playback, so results match what playback would do.
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .build()
            .apply {
                volume = 0.3f
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        decoder = name
                    }
                    override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        if (decoder == null) decoder = name
                    }
                })
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val results = remember { mutableStateListOf<ClipResult>() }
    var running by remember { mutableStateOf<TestClip?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var applied by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }

    fun runAll() {
        job?.cancel()
        results.clear()
        job = scope.launch {
            for (clip in PlaybackTest.CLIPS) {
                running = clip
                decoder = null
                results += player.runClip(clip) { decoder }
            }
            running = null
            player.stop()
        }
    }

    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    LaunchedEffect(results.size, running, applied) {
        suggestions = if (running == null && results.isNotEmpty()) {
            PlaybackTest.suggestions(results.toList(), capabilities, store.current())
        } else {
            emptyList()
        }
    }

    Row(Modifier.fillMaxSize().padding(start = PagePadding, end = 32.dp, top = 32.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Playback test", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Plays short clips in each format and compares the result with what ${capabilities.model} reports. " +
                            "HDR clips test decoding; whether the colours look right in the preview shows whether HDR output works. " +
                            "Dolby Vision 5 looks green and purple unless Dolby Vision is really applied, and your TV's Dolby Vision badge should appear.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = ::runAll, enabled = running == null, modifier = Modifier.focusRequester(startFocus)) {
                            Text(if (results.isEmpty()) "Start test" else "Run again")
                        }
                        if (running != null) OutlinedButton(onClick = { job?.cancel(); running = null; player.stop() }) { Text("Stop") }
                    }
                }
            }

            if (suggestions.isNotEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Suggested changes", style = MaterialTheme.typography.titleLarge)
                        for (s in suggestions) Text("• ${s.description}", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            scope.launch {
                                store.update { current -> suggestions.fold(current) { acc, s -> s.apply(acc) } }
                                applied++
                            }
                        }) { Text("Apply to playback settings") }
                    }
                }
            } else if (running == null && results.isNotEmpty()) {
                item {
                    Text(
                        "No changes needed: everything behaves as this TV reports (or is already overridden).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            items(PlaybackTest.CLIPS, key = { it.asset }) { clip ->
                ResultRow(clip, results.firstOrNull { it.clip == clip }, clip.claimed(capabilities), clip == running)
            }
        }

        // Live preview of the clip being tested.
        Column(Modifier.width(420.dp).padding(start = 24.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
                    modifier = Modifier.fillMaxSize(),
                )
                running?.let {
                    Text(it.label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
            }
            decoder?.let {
                Text("Decoder: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ResultRow(clip: TestClip, result: ClipResult?, claimed: Boolean, active: Boolean) {
    val mismatch = result != null && claimed != result.ok
    ListItem(
        selected = active,
        onClick = {},
        headlineContent = { Text(clip.label) },
        supportingContent = {
            Column {
                Text("TV reports: " + if (claimed) "supported" else "not supported", style = MaterialTheme.typography.bodySmall)
                result?.let {
                    val decoderNote = it.decoder?.let { d -> " · $d" + if (it.softwareDecoder) " (software)" else "" }.orEmpty()
                    Text(it.detail + decoderNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (mismatch) {
                    Text(
                        if (claimed) "Reported as supported, but doesn't play properly" else "Plays, although the TV doesn't report it",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (claimed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        trailingContent = {
            Text(
                when {
                    active -> "Testing…"
                    result == null -> "–"
                    else -> result.outcome.label
                },
                color = when (result?.outcome) {
                    Outcome.PASSED -> MaterialTheme.colorScheme.primary
                    Outcome.FALLBACK -> MaterialTheme.colorScheme.secondary
                    Outcome.SLOW, Outcome.FAILED -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}

/** Plays one clip to the end (or failure/timeout) and grades it. */
@OptIn(UnstableApi::class)
private suspend fun ExoPlayer.runClip(clip: TestClip, decoderName: () -> String?): ClipResult {
    stop()
    clearMediaItems()
    setMediaItem(MediaItem.fromUri(clip.uri))
    prepare()
    play()

    // Ended normally -> success; player error -> failure carrying it; null -> timed out.
    val finished: Result<Unit>? = withTimeoutOrNull(CLIP_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && cont.isActive) { removeListener(this); cont.resume(Result.success(Unit)) }
                }
                override fun onPlayerError(e: PlaybackException) {
                    if (cont.isActive) { removeListener(this); cont.resume(Result.failure(e)) }
                }
            }
            addListener(listener)
            cont.invokeOnCancellation { removeListener(listener) }
        }
    }

    if (finished == null) return ClipResult(clip, Outcome.FAILED, decoderName(), "Timed out without finishing")
    (finished.exceptionOrNull() as? PlaybackException)?.let {
        return ClipResult(clip, Outcome.FAILED, decoderName(), it.errorCodeName)
    }

    if (clip.kind == ClipKind.AUDIO) return ClipResult(clip, Outcome.PASSED, decoderName(), "Played to the end")

    val counters = videoDecoderCounters?.also { it.ensureUpdated() }
    // Profile 8.1 falls back to plain HEVC without a Dolby Vision decoder; that isn't Dolby Vision.
    val dvFallback = clip.dolbyVisionProfile != null && !DeviceCapabilities.isDolbyVisionDecoder(decoderName())
    val rendered = counters?.renderedOutputBufferCount ?: 0
    val dropped = (counters?.droppedBufferCount ?: 0) + (counters?.skippedOutputBufferCount ?: 0)
    val total = rendered + dropped
    return when {
        rendered < 5 -> ClipResult(clip, Outcome.FAILED, decoderName(), "No frames shown", rendered, dropped)
        total > 0 && dropped.toDouble() / total > MAX_DROPPED_SHARE ->
            ClipResult(clip, Outcome.SLOW, decoderName(), "Dropped $dropped of $total frames", rendered, dropped)
        dvFallback && clip.dolbyVisionProfile == 5 ->
            ClipResult(clip, Outcome.FAILED, decoderName(), "Decoded without Dolby Vision, so the colours are wrong", rendered, dropped)
        dvFallback -> ClipResult(clip, Outcome.FALLBACK, decoderName(), "No Dolby Vision decoder used; showed the HDR10 base layer", rendered, dropped)
        else -> ClipResult(clip, Outcome.PASSED, decoderName(), "Showed $rendered frames" + if (dropped > 0) ", dropped $dropped" else "", rendered, dropped)
    }
}

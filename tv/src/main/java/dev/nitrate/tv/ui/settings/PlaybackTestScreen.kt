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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.nitrate.android.playback.DeviceProbe
import dev.nitrate.tv.ui.player.findActivity
import dev.nitrate.core.playback.ClipKind
import dev.nitrate.core.playback.ClipResult
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.Outcome
import dev.nitrate.android.storage.PlaybackSettingsStore
import dev.nitrate.core.playback.PlaybackTest
import dev.nitrate.core.playback.Suggestion
import dev.nitrate.core.playback.TestClip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val PagePadding = 58.dp
private const val CLIP_TIMEOUT_MS = 12_000L

/** The Companion's heavy clip (about 28 MB) is fetched as it plays. */
private const val SERVER_CLIP_TIMEOUT_MS = 60_000L

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
    /** The server, for the Companion's heavy profile 7 clip (null: bundled clips only). */
    server: dev.nitrate.core.MediaServer? = null,
) {
    val context = LocalContext.current
    // Profile 7 clips are opened the way real files are (DolbyVisionProfile7), converted or as their base layer.
    val profile7Way = remember { java.util.concurrent.atomic.AtomicReference(dev.nitrate.core.playback.Profile7Playback.AS_PROFILE_8) }
    var decoder by remember { mutableStateOf<String?>(null) }
    // How the last clip's sound went out: untouched (bitstream) or decoded.
    var audioEncoding by remember { mutableStateOf<Int?>(null) }
    val player = remember {
        // Bundled clips from the app, the heavy one from the Companion (signed in, like playback).
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(server?.let { mapOf("Authorization" to it.authorizationHeader()) }.orEmpty())
        ExoPlayer.Builder(context)
            // Same fallback as real playback, so results match what playback would do.
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    androidx.media3.datasource.DefaultDataSource.Factory(context, http),
                    dev.nitrate.android.playback.DolbyVisionProfile7({ profile7Way.get() }),
                ),
            )
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
                    override fun onAudioTrackInitialized(e: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                        audioEncoding = config.encoding
                    }
                })
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val results = remember { mutableStateListOf<ClipResult>() }
    // The profile 7 clips this run (with the Companion's heavy one when it has it).
    var profile7List by remember { mutableStateOf(PlaybackTest.profile7Clips(heavy = false)) }
    val passthrough = remember { mutableMapOf<String, Boolean>() }
    var refresh by remember { mutableStateOf<dev.nitrate.core.playback.RefreshCheck?>(null) }
    var testingRefresh by remember { mutableStateOf(false) }
    val saved by store.settings.collectAsState(initial = null)
    val activity = remember(context) { context.findActivity() }
    var running by remember { mutableStateOf<TestClip?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var applied by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val startFocus = remember { FocusRequester() }
    // Start takes focus once it's placed (asked a few times: the list lays out a beat later).
    var startFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(10) {
            kotlinx.coroutines.delay(60)
            runCatching { startFocus.requestFocus() }
            if (startFocused) return@LaunchedEffect
        }
    }

    fun runAll() {
        job?.cancel()
        results.clear()
        job = scope.launch {
            passthrough.clear()
            refresh = null
            // The profile 7 clips after the rest; the heavy one only when the Companion has it.
            val heavy = runCatching { server?.checkClips().orEmpty().any { it.id == PlaybackTest.HEAVY_FEL_CLIP } }.getOrDefault(false)
            profile7List = PlaybackTest.profile7Clips(heavy)
            for (clip in PlaybackTest.CLIPS + profile7List) {
                running = clip
                decoder = null
                audioEncoding = null
                clip.profile7?.let(profile7Way::set)
                val uri = clip.fromServer?.let { id -> server?.checkClipUrl(id) } ?: clip.uri
                val result = player.runClip(clip, uri) { decoder }
                results += result
                if (clip.kind == ClipKind.AUDIO && result.ok) audioEncoding?.let { passthrough[clip.asset] = it !in PCM_ENCODINGS }
            }
            running = null
            player.stop()
            // One real refresh-rate switch: how long it takes, and whether surround passthrough comes back.
            activity?.let { a ->
                testingRefresh = true
                refresh = runCatching { dev.nitrate.tv.ui.player.refreshCheck(a) }.getOrNull()
                testingRefresh = false
            }
            // Kept, so How it plays and the playback overview can say what's confirmed here.
            store.update { s ->
                s.copy(deviceCheck = dev.nitrate.core.playback.DeviceCheck(
                    atMs = System.currentTimeMillis(),
                    model = capabilities.model,
                    clips = results.associate { r -> r.clip.key to dev.nitrate.core.playback.CheckedClip(r.outcome, r.decoder, passthrough[r.clip.asset], r.framesRendered, r.framesDropped) },
                    refresh = refresh,
                ))
            }
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
                        Button(onClick = ::runAll, enabled = running == null, modifier = Modifier.focusRequester(startFocus).onFocusChanged { startFocused = it.isFocused }) {
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

            item(key = "refresh") {
                val shown = refresh ?: saved?.deviceCheck?.refresh.takeIf { results.isEmpty() }
                ListItem(
                    selected = false, onClick = {},
                    headlineContent = { Text("Refresh-rate switch") },
                    supportingContent = {
                        Text(when {
                            testingRefresh -> "Switching the TV to a film's rate and back…"
                            shown == null -> "Tested after the clips: how long the TV goes black, and whether surround sound still passes through afterwards"
                            else -> refreshText(shown)
                        }, style = MaterialTheme.typography.bodySmall)
                    },
                )
            }
            saved?.deviceCheck?.takeIf { running == null && !testingRefresh }?.let { check ->
                item(key = "saved") {
                    Text(
                        "Results saved " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(check.atMs)) +
                            ": How it plays and the playback overview quote them.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(PlaybackTest.CLIPS, key = { it.key }) { clip ->
                ResultRow(clip, results.firstOrNull { it.clip == clip }, clip.claimed(capabilities), clip == running)
            }
            // Profile 7: each kind both ways; what they find chooses how profile 7 plays here, by itself.
            item(key = "profile7") {
                Column(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dolby Vision profile 7", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "MEL and FEL (a minimal or a full enhancement layer), each converted to 8.1 and as its HDR10 base layer. " +
                            "If a kind shows no picture converted but plays as its base layer, films of that kind play that way on this TV from now on." +
                            (if (server == null) "" else " The disc-like FEL clip comes from the Nitrate Companion, when your server has it."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(profile7List, key = { it.key }) { clip ->
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

/** Media3's decoded (PCM) sound encodings; anything else went out untouched. */
private val PCM_ENCODINGS = setOf(
    androidx.media3.common.C.ENCODING_PCM_16BIT, androidx.media3.common.C.ENCODING_PCM_FLOAT, androidx.media3.common.C.ENCODING_PCM_24BIT,
    androidx.media3.common.C.ENCODING_PCM_32BIT, androidx.media3.common.C.ENCODING_PCM_8BIT,
)

/** The refresh-rate test in words. */
internal fun refreshText(r: dev.nitrate.core.playback.RefreshCheck): String {
    fun hz(v: Float) = "%.3f".format(v).trimEnd('0').trimEnd('.')
    if (r.fromHz == r.toHz) return "This TV offers no film rate (23.976 or 24 Hz) to switch to from ${hz(r.fromHz)} Hz"
    if (!r.switched) return "Asked for ${hz(r.toHz)} Hz, but the TV stayed at ${hz(r.fromHz)} Hz" + if (dev.nitrate.tv.ui.player.isFireTv()) " (on Fire TV, turn on Match Original Frame Rate)" else ""
    val black = r.blackMs?.let { "about %.1f s".format(it / 1000.0) } ?: "a moment"
    val back = r.surroundBackMs
    val surround = when {
        r.surroundBefore.isEmpty() -> "no surround passthrough to lose"
        back != null -> "surround passthrough back after %.1f s".format(back / 1000.0)
        else -> "surround passthrough didn't come back within the wait"
    }
    return "${hz(r.fromHz)} → ${hz(r.toHz)} Hz: $black of black; $surround"
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
private suspend fun ExoPlayer.runClip(clip: TestClip, uri: String, decoderName: () -> String?): ClipResult {
    stop()
    clearMediaItems()
    setMediaItem(MediaItem.fromUri(uri))
    prepare()
    play()

    // Ended normally -> success; player error -> failure carrying it; null -> timed out (the
    // Companion's clip is downloaded as it plays, so it's given longer).
    val finished: Result<Unit>? = withTimeoutOrNull(if (clip.fromServer != null) SERVER_CLIP_TIMEOUT_MS else CLIP_TIMEOUT_MS) {
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
    // Profile 7 as its base layer is HDR10 by design: no Dolby Vision decoder is expected.
    val dvFallback = clip.dolbyVisionProfile != null && clip.profile7 != dev.nitrate.core.playback.Profile7Playback.BASE_LAYER &&
        !DeviceProbe.isDolbyVisionDecoder(decoderName())
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

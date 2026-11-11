package dev.nitrate.tv.ui.settings

import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.playback.ChannelClip
import dev.nitrate.tv.data.playback.ChannelTest
import dev.nitrate.tv.data.playback.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val PagePadding = 58.dp

/** What actually leaves the TV for a clip, from the audio track Media3 opened. */
private data class AudioOutput(val passthrough: Boolean, val description: String, val channels: Int?)

@OptIn(UnstableApi::class)
private fun describe(config: AudioSink.AudioTrackConfig, decoder: String?): AudioOutput {
    val bitstream = when (config.encoding) {
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> "Dolby Digital Plus"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        C.ENCODING_DTS, C.ENCODING_DTS_HD -> "DTS"
        else -> null
    }
    return if (bitstream != null) {
        AudioOutput(true, "$bitstream bitstream sent over HDMI; your receiver or soundbar decodes it", null)
    } else {
        val channels = Integer.bitCount(config.channelConfig)
        AudioOutput(false, "Decoded by the TV${decoder?.let { " ($it)" }.orEmpty()}, sent as $channels-channel PCM at ${config.sampleRate / 1000} kHz", channels)
    }
}

/**
 * Walks through each speaker in turn so you can check that every channel comes out of the right
 * speaker, and shows what the TV actually sends (bitstream or PCM, and how many channels).
 */
@OptIn(UnstableApi::class)
@Composable
fun AudioChannelTestScreen(repository: JellyfinRepository) {
    val context = LocalContext.current
    val serverClips by produceState<List<ChannelClip>?>(null) {
        value = runCatching { repository.channelTestClips() }.getOrDefault(emptyList())
    }
    var decoder by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<AudioOutput?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember {
        // Bundled clips come from assets; server files need the Jellyfin auth header.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to repository.authorizationHeader()))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    decoder = name
                }
                override fun onAudioTrackInitialized(e: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                    output = describe(config, decoder)
                }
            })
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = "This TV can't play it: ${e.errorCodeName}"
                }
            })
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var clip by remember { mutableStateOf<ChannelClip?>(null) }
    var looping by remember { mutableStateOf<Speaker?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    val firstClip = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstClip.requestFocus() } }

    fun play(c: ChannelClip) {
        clip = c
        looping = null
        decoder = null
        output = null
        error = null
        player.setMediaItem(MediaItem.fromUri(c.uri))
        player.prepare()
        player.play()
    }

    // Follow playback for the diagram, and keep a looped speaker inside its slot.
    LaunchedEffect(Unit) {
        while (isActive) {
            position = player.currentPosition
            val c = clip
            val loop = looping
            if (c != null && loop != null) {
                val start = c.slotStartMs(loop)
                if (start != null && (position < start || position >= start + ChannelTest.SEGMENT_MS - 100)) player.seekTo(start)
            }
            delay(80)
        }
    }

    val current = clip
    val active = if (current != null && player.isPlaying) current.speakerAt(position) else null

    Row(Modifier.fillMaxSize().padding(start = PagePadding, end = 40.dp, top = 32.dp)) {
        LazyColumn(Modifier.width(420.dp).fillMaxHeight(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Audio channel test", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Each speaker says its name, then plays a burst of noise. Check that the sound comes from the speaker lit up on the map. " +
                            "Turn the volume down before you start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ChannelTest.CLIPS, key = { it.uri }) { c ->
                ListItem(
                    selected = c == current,
                    onClick = { play(c) },
                    headlineContent = { Text("${c.layout} · ${c.format}") },
                    supportingContent = { Text(c.speakers.joinToString(" ") { it.short }) },
                    modifier = if (c == ChannelTest.CLIPS.first()) Modifier.focusRequester(firstClip) else Modifier,
                )
            }
            item {
                Column(Modifier.padding(top = 24.dp, bottom = 6.dp)) {
                    Text("From your server", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Files whose title contains \"${ChannelTest.SERVER_TAG}\" appear here and are always played as the original file. " +
                            "Titles with \"Walk\" and a layout (e.g. \"${ChannelTest.SERVER_TAG} Walk 7.1 TrueHD\") follow the map.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val fromServer = serverClips
            when {
                fromServer == null -> item { Text("Looking…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                fromServer.isEmpty() -> item { Text("None found", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> items(fromServer, key = { it.serverItemId ?: it.uri }) { c ->
                    ListItem(
                        selected = c == current,
                        onClick = { play(c) },
                        headlineContent = { Text("${c.layout} · ${c.format}") },
                        supportingContent = { Text(if (c.walk) "Walk-through: " + c.speakers.joinToString(" ") { it.short } else "Plays without the map following") },
                    )
                }
            }
        }

        Column(Modifier.weight(1f).padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RoomMap(
                speakers = current?.speakers.orEmpty(),
                active = active,
                looping = looping,
                onSpeaker = { s -> looping = if (looping == s) null else s; current?.slotStartMs(s)?.let(player::seekTo) },
                modifier = Modifier.fillMaxWidth().aspectRatio(1.6f),
            )
            Text(
                when {
                    current == null -> "Choose a format to start"
                    active != null -> active.label + if (looping != null) "  (looping)" else ""
                    !current.walk -> "Playing ${current.layout}: listen for each channel"
                    else -> "…"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutputReadout(current, output, error)
        }
    }
}

@Composable
private fun OutputReadout(clip: ChannelClip?, output: AudioOutput?, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (clip == null || output == null) return@Column
        Text(output.description, style = MaterialTheme.typography.bodyMedium)
        val channels = output.channels
        val hint = when {
            output.passthrough ->
                "If a speaker is wrong or silent here, check the receiver's speaker setup (size, distance, wiring)." to false
            channels != null && channels < clip.speakers.size ->
                "The TV is downmixing ${clip.speakers.size} channels to $channels. Surround speakers will be silent or folded into the front. " +
                    "Check the TV's audio output setting (e.g. set it to Auto, Passthrough or Surround instead of Stereo/PCM)." to true
            else -> "The TV sends every channel; a wrong speaker here points at wiring or receiver setup." to false
        }
        Text(
            hint.first,
            style = MaterialTheme.typography.bodySmall,
            color = if (hint.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Select a speaker on the map to repeat just that channel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Top-down room: screen at the top, you in the middle, speakers around. Speakers are focusable. */
@Composable
private fun RoomMap(
    speakers: List<Speaker>,
    active: Speaker?,
    looping: Speaker?,
    onSpeaker: (Speaker) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .background(colors.surface, RoundedCornerShape(20.dp))
            .border(1.dp, colors.border, RoundedCornerShape(20.dp)),
    ) {
        val w = maxWidth
        val h = maxHeight
        val inset = 48.dp

        // Screen
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).width(w * 0.36f).height(8.dp)
                .background(colors.onSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
        )
        Text("Screen", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp))

        // Listener
        Box(Modifier.align(Alignment.Center).offset(y = h * 0.06f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(34.dp).background(colors.surfaceVariant, CircleShape))
            Text("You", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }

        for (speaker in Speaker.entries) {
            if (speaker !in speakers) continue
            val isActive = speaker == active
            val glow by animateFloatAsState(if (isActive) 1f else 0f, label = "speaker-${speaker.name}")
            // Map room coordinates to the box, leaving room for the speaker and its label.
            val x = (w - inset * 2) * ((speaker.x + 1f) / 2f) + inset - 30.dp
            val y = (h - inset * 2) * speaker.y + inset - 30.dp
            Column(Modifier.offset(x, y), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(
                    onClick = { onSpeaker(speaker) },
                    shape = CardDefaults.shape(CircleShape),
                    colors = CardDefaults.colors(
                        containerColor = if (isActive) colors.primary else colors.surfaceVariant,
                        focusedContainerColor = if (isActive) colors.primary else colors.secondary,
                    ),
                    modifier = Modifier.size(60.dp).scale(1f + 0.15f * glow),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            speaker.short,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive) colors.onPrimary else colors.onSurface,
                        )
                    }
                }
                Text(
                    speaker.label + if (speaker == looping) " ⟲" else "",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp).width(96.dp).alpha(if (isActive || speaker == looping) 1f else 0.7f),
                )
            }
        }
    }
}

package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.playback.PlaybackMoments
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import dev.nitrate.core.remote.RemotePrompt
import dev.nitrate.mobile.platform.rememberArtworkColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [RemoteRoute.sessionId] for the Chromecast being cast to. */
const val CAST_REMOTE = "cast"

/**
 * Controls another device's playback, or a Chromecast's: what's on and where, its questions
 * ("Still watching?", Up Next…) to answer from here, skipping intros and credits, seeking with
 * previews, the transport, tracks and volume, the chapters and the season's episodes; and for a
 * TV running Nitrate an arrow pad for its menus.
 */
@Composable
fun RemoteScreen(actions: AppActions, sessionId: String, target: RemoteTarget? = null) {
    val cast = dev.nitrate.mobile.platform.rememberCast()
    val remote = target ?: remember(sessionId, cast) {
        if (sessionId == CAST_REMOTE) cast?.let { CastTarget(actions.container, it) } else SessionTarget(actions.container, sessionId)
    }
    if (remote == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Not casting") }
        return
    }
    // Lock-screen and notification controls while it plays (a device through the server).
    val follow = dev.nitrate.mobile.platform.rememberRemoteFollower()
    LaunchedEffect(sessionId) { if (sessionId != CAST_REMOTE && target == null) follow(sessionId) }

    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val view by produceState<RemoteView?>(null, tick, remote) {
        while (true) {
            value = runCatching { remote.view() }.getOrNull()
            delay(remote.pollMs)
        }
    }
    fun send(block: suspend RemoteTarget.() -> Unit) = scope.launch { runCatching { remote.block() }; delay(300); tick++ }
    var arrows by remember { mutableStateOf(false) }
    // A question answered here goes at once, not at the next look.
    var answered by remember { mutableStateOf(setOf<String>()) }
    val np = view?.item
    val tint by rememberArtworkColor(np?.let { actions.server.backdropUrl(it, 320) ?: actions.server.imageUrl(it) })

    TintedBackdrop(tint, strength = 0.45f) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(if (sessionId == CAST_REMOTE) "Casting" else "Remote", style = MaterialTheme.typography.labelLarge, color = accentColor())
                    Text(view?.deviceName ?: "Device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (view?.navigable == true) Chip(if (arrows) "Playback" else "Arrows", selected = arrows) { arrows = !arrows }
            }
            view?.prompt?.takeIf { it.id !in answered }?.let { p -> PromptCard(p) { choice -> answered = answered + p.id; send { answer(p, choice) } } }
            if (arrows) {
                ArrowPad { command -> send { navigate(command) } }
                return@Column
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                val art = np?.let { actions.server.cardImage(it, CardShape.POSTER, 600) }
                if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(if (view == null) "Not connected" else "Nothing playing", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(np?.displayTitle ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            np?.episodeLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }

            val v = view
            if (np != null && v != null) Playing(actions, np, v, ::send)
            else Spacer(Modifier.height(24.dp))
        }
    }
}

/** The controls and lists for what's playing there. */
@Composable
private fun Playing(actions: AppActions, np: MediaItem, view: RemoteView, send: (suspend RemoteTarget.() -> Unit) -> Unit) {
    val server = actions.server
    // The whole item (chapters, season), which a session's "now playing" leaves out.
    val full by produceState(np, np.id) { value = runCatching { server.getItem(np.id) }.getOrNull() ?: np }
    val duration = full.runtimeMs ?: np.runtimeMs ?: 0L
    // Moves along between looks while it plays, so the clock and skip button stay right.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(view) { now = 0; while (!view.paused) { delay(500); now += 500 } }
    val position = (view.positionMs + now).let { if (duration > 0) it.coerceAtMost(duration) else it }

    // Skip the intro, recap or credits, as the TV offers.
    val segments by produceState(emptyList<dev.nitrate.core.model.MediaSegment>(), np.id) { value = runCatching { server.mediaSegments(np.id) }.getOrDefault(emptyList()) }
    PlaybackMoments.skippable(segments, position)?.let { seg ->
        Text(
            seg.kind.skipLabel, color = contentOn(accentColor()), fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).background(accentColor()).pressable({ send { seek(seg.endMs) } })
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }

    if (duration > 0) {
        var seeking by remember { mutableStateOf<Float?>(null) }
        var barWidth by remember { mutableIntStateOf(0) }
        val trickplay by produceState<dev.nitrate.core.model.TrickplayInfo?>(null, np.id) { value = runCatching { server.trickplay(np.id, null) }.getOrNull() }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).onSizeChanged { barWidth = it.width }) {
            Slider(
                value = seeking ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    val to = ((seeking ?: 0f) * duration).toLong()
                    seeking = null
                    send { seek(to) }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Position" },
            )
            // A frame from where the finger is, while seeking.
            seeking?.let { f -> TrickplayPreview(server, np.id, trickplay, (f * duration).toLong(), f * barWidth, 0f) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(position), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatClock(duration), style = MaterialTheme.typography.labelMedium)
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteKey(NitrateIcons.SkipPrevious, "Previous") { send { previous() } }
        RemoteKey(NitrateIcons.Replay, "Back 10 seconds", "10") { send { seek(position - 10_000) } }
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(accentColor()).pressable({ send { setPaused(!view.paused) } }),
            Alignment.Center,
        ) {
            if (view.paused) Icon(Icons.Default.PlayArrow, "Play", tint = contentOn(accentColor()), modifier = Modifier.size(40.dp))
            else Icon(NitrateIcons.Pause, "Pause", tint = contentOn(accentColor()), modifier = Modifier.size(36.dp))
        }
        RemoteKey(NitrateIcons.Forward, "Ahead 30 seconds", "30") { send { seek(position + 30_000) } }
        RemoteKey(NitrateIcons.SkipNext, "Next") { send { next() } }
    }

    // The audio and subtitles playing there, each changeable from here.
    val source by produceState<dev.nitrate.core.model.MediaSource?>(null, np.id) { value = runCatching { server.mediaSource(np.id) }.getOrNull() }
    var picking by remember { mutableStateOf<StreamKind?>(null) }
    source?.let { src ->
        val audio = src.audioChoices()
        val subs = src.subtitleChoices()
        if (audio.size > 1 || subs.size > 1) {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (audio.size > 1) TrackPill("Audio", audio.firstOrNull { it.index == view.audioIndex }?.title ?: "Default") { picking = StreamKind.AUDIO }
                if (subs.size > 1) TrackPill("Subtitles", subs.firstOrNull { it.index == (view.subtitleIndex ?: -1) }?.title ?: "Off") { picking = StreamKind.SUBTITLE }
            }
        }
        picking?.let { kind ->
            val isAudio = kind == StreamKind.AUDIO
            TrackPicker(
                if (isAudio) "Audio" else "Subtitles",
                if (isAudio) audio else subs,
                if (isAudio) view.audioIndex else view.subtitleIndex ?: -1,
                onSelect = { index -> picking = null; send { track(kind, index) } },
            ) { picking = null }
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteKey(NitrateIcons.VolumeDown, "Volume down") { send { volume(GeneralCommand.VOLUME_DOWN) } }
        RemoteKey(NitrateIcons.VolumeOff, "Mute") { send { volume(GeneralCommand.TOGGLE_MUTE) } }
        RemoteKey(NitrateIcons.VolumeUp, "Volume up") { send { volume(GeneralCommand.VOLUME_UP) } }
        RemoteKey(NitrateIcons.Stop, "Stop") { send { stop() } }
    }

    // Chapters: tap one to go there.
    if (full.chapters.size > 1) {
        SectionLabel("Chapters")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(full.chapters) { i, c ->
                val current = position >= c.startMs && (full.chapters.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE) > position
                StillCard(
                    picture = server.chapterImageUrl(full, i, 320), blur = false,
                    title = c.name.ifBlank { "Chapter ${i + 1}" }, line = formatClock(c.startMs), current = current,
                ) { send { seek(c.startMs) } }
            }
        }
    }

    // The season's episodes: tap one to play it there.
    val seriesId = full.seriesId
    val seasonId = full.seasonId
    if (full.isEpisode && seriesId != null && seasonId != null) {
        val episodes by produceState(emptyList<MediaItem>(), seasonId) { value = runCatching { server.episodes(seriesId, seasonId) }.getOrDefault(emptyList()) }
        if (episodes.size > 1) {
            SectionLabel("Episodes")
            val start = episodes.indexOfFirst { it.id == np.id }.coerceAtLeast(0)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth(),
                state = androidx.compose.foundation.lazy.rememberLazyListState(start),
            ) {
                itemsIndexed(episodes, key = { _, e -> e.id }) { _, e ->
                    StillCard(
                        picture = server.imageUrl(e, dev.nitrate.core.model.ImageKind.PRIMARY, 320), blur = spoilerStill(e),
                        title = "E${e.indexNumber ?: "?"} · ${e.name}", line = e.runtimeMs?.let { "${it / 60_000} min" }, current = e.id == np.id,
                    ) { if (e.id != np.id) send { play(e, if (e.user.played) 0 else e.user.positionMs) } }
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** A question the device is asking on screen, with its answers. */
@Composable
private fun PromptCard(prompt: RemotePrompt, onChoice: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("On the TV", style = MaterialTheme.typography.labelMedium, color = accentColor())
        Text(prompt.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        prompt.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            prompt.choices.forEachIndexed { i, c -> Chip(c.label, selected = i == 0) { onChoice(c.id) } }
        }
    }
}

/** Arrows, OK, Back and Home for the TV's menus. */
@Composable
private fun ArrowPad(onKey: (GeneralCommand) -> Unit) {
    Column(Modifier.padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Move round the TV's menus as with its own remote.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = 16.dp).size(260.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
            PadKey(NitrateIcons.ArrowUp, "Up", Modifier.align(Alignment.TopCenter)) { onKey(GeneralCommand.MOVE_UP) }
            PadKey(NitrateIcons.ArrowDown, "Down", Modifier.align(Alignment.BottomCenter)) { onKey(GeneralCommand.MOVE_DOWN) }
            PadKey(NitrateIcons.ArrowLeft, "Left", Modifier.align(Alignment.CenterStart)) { onKey(GeneralCommand.MOVE_LEFT) }
            PadKey(NitrateIcons.ArrowRight, "Right", Modifier.align(Alignment.CenterEnd)) { onKey(GeneralCommand.MOVE_RIGHT) }
            Box(
                Modifier.align(Alignment.Center).size(96.dp).clip(CircleShape).background(accentColor()).pressable({ onKey(GeneralCommand.SELECT) }),
                Alignment.Center,
            ) { Text("OK", color = contentOn(accentColor()), fontWeight = FontWeight.Black, fontSize = 22.sp) }
        }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteKey(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onKey(GeneralCommand.BACK) }
            RemoteKey(NitrateIcons.Home, "Home") { onKey(GeneralCommand.GO_HOME) }
        }
    }
}

@Composable
private fun PadKey(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(80.dp).clip(CircleShape).pressable(onClick), Alignment.Center) { Icon(icon, label, modifier = Modifier.size(44.dp)) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
    )
}

/** A chapter or episode: its picture, name and a line, tapped to go there. */
@Composable
private fun StillCard(picture: String?, blur: Boolean, title: String, line: String?, current: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(12.dp)).pressable(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))) {
            picture?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(if (blur) Modifier.blur(16.dp) else Modifier)) }
            if (current) Text(
                "Now", color = contentOn(accentColor()), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(accentColor()).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        line?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontWeight = FontWeight.SemiBold,
        color = if (selected) contentOn(accentColor()) else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) accentColor() else Color.White.copy(alpha = 0.12f))
            .pressable(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A remote button: an icon (with a small number for the jumps) and the name it's read out by. */
@Composable
private fun RemoteKey(icon: ImageVector, label: String, number: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.height(52.dp).width(60.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.1f)).pressable(onClick),
        Alignment.Center,
    ) {
        Icon(icon, label, modifier = Modifier.size(if (number != null) 34.dp else 28.dp))
        number?.let { Text(it, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp)) }
    }
}

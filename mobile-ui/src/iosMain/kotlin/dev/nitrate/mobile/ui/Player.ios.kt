package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.CreditSkipper
import dev.nitrate.core.playback.NextInCollection
import dev.nitrate.core.playback.PlaybackMoments
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import platform.AVKit.AVPlayerViewController

/**
 * Apple's player (AVPlayerViewController: its own controls, picture-in-picture and AirPlay) with
 * Nitrate's shared overlays on top: Audio and Subtitles menus, skip intro/recap, Up Next for
 * episodes and the next film of a collection, and scenes in the credits.
 */
@Composable
actual fun PlayerScreen(
    actions: AppActions,
    itemId: String,
    fromStart: Boolean,
    onExit: () -> Unit,
    onEnded: () -> Unit,
    local: Boolean,
    startMs: Long?,
    onPlayNext: ((MediaItem) -> Unit)?,
    clipEndMs: Long?,
) {
    val container = actions.container
    val session = remember(itemId) { ApplePlayerSession(container, reported = clipEndMs == null) }
    DisposableEffect(session) {
        session.load(itemId, fromStart, startMs)
        container.remote.active = session
        onDispose {
            if (container.remote.active === session) container.remote.active = null
            session.release()
        }
    }

    val item by session.item.collectAsState()
    val plan by session.plan.collectAsState()
    val error by session.error.collectAsState()
    val positionMs by session.positionMs.collectAsState()
    val durationMs by session.durationMs.collectAsState()
    val segments by session.segments.collectAsState()
    val creditScenes by session.creditScenes.collectAsState()
    val ended by session.ended.collectAsState()
    var picking by remember { mutableStateOf<StreamKind?>(null) }
    var upNext by remember(itemId) { mutableStateOf<MediaItem?>(null) }
    var upNextHidden by remember(itemId) { mutableStateOf(false) }

    // A recap clip ends at its end; anything else when the stream does.
    LaunchedEffect(positionMs, ended) {
        if (ended || (clipEndMs != null && positionMs >= clipEndMs)) onEnded()
    }
    // What comes next: the following episode, or the next film of a small collection.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        if (onPlayNext == null) return@LaunchedEffect
        upNext = when {
            current.kind == ItemKind.MOVIE -> runCatching { actions.server.nextInCollection(current) }.getOrNull()
            current.isEpisode -> current.seriesId?.let { s -> runCatching { dev.nitrate.core.episodes.UpNext.after(current, actions.server.allEpisodes(s)) }.getOrNull() }
            else -> null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        UIKitViewController(
            factory = { AVPlayerViewController().apply { player = session.player; showsPlaybackControls = true } },
            modifier = Modifier.fillMaxSize(),
        )

        // Back, the title, and the track menus (Apple's own controls stay below).
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(
                listOfNotNull(item?.displayTitle, item?.episodeLabel).joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (plan != null && clipEndMs == null) {
                TextButton(onClick = { picking = StreamKind.AUDIO }) { Text("Audio", color = Color.White) }
                TextButton(onClick = { picking = StreamKind.SUBTITLE }) { Text("Subtitles", color = Color.White) }
            }
        }

        val next = upNext
        val film = next != null && !next.isEpisode
        val upNextAt = (if (film) NextInCollection.offerAtMs(durationMs, segments, item?.chapters.orEmpty()) else PlaybackMoments.upNextAtMs(durationMs, segments))
            .let { at -> creditScenes?.upNextAt(at, durationMs) ?: at }
        val showUpNext = next != null && onPlayNext != null && !upNextHidden && upNextAt != null && positionMs >= upNextAt
        if (clipEndMs == null) SkipButton(PlaybackMoments.skippable(segments, positionMs).takeIf { !showUpNext }) { session.seekTo(it.endMs) }
        if (showUpNext && next != null) {
            val remaining = ((durationMs - positionMs) / 1000).toInt()
            UpNextCard(next, actions.server, seconds = remaining.coerceIn(5, 15).takeIf { !film }, onPlay = { onPlayNext?.invoke(next) }, onHide = { upNextHidden = true })
        }

        // Scenes in the credits: skipped to when sure (and allowed), else offered or a heads-up.
        val autoSkip = LocalDisplayPrefs.current.creditScenes && container.syncPlay.group.collectAsState().value == null
        val skipper = remember(creditScenes, autoSkip) { creditScenes?.let { CreditSkipper(it, autoSkip) } }
        var creditAction by remember(item?.id) { mutableStateOf<CreditSkipper.Action?>(null) }
        LaunchedEffect(skipper, positionMs / 500) {
            if (clipEndMs != null) return@LaunchedEffect
            val a = skipper?.at(positionMs) ?: return@LaunchedEffect
            if (a is CreditSkipper.Action.Skip) session.seekTo(a.toMs)
            creditAction = a
        }
        CreditSceneBanner(creditAction.takeIf { !showUpNext }, onAct = { a ->
            when (a) {
                is CreditSkipper.Action.Skip -> session.seekTo(a.backMs)
                is CreditSkipper.Action.Offer -> session.seekTo(a.toMs)
                else -> Unit
            }
        }) { creditAction = null }

        when {
            error != null -> Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(24.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = onExit, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
            }
            plan == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = accentColor())
        }
    }

    val p = plan
    when {
        p == null || picking == null -> Unit
        picking == StreamKind.AUDIO -> TrackPicker("Audio", p.audioChoices(), p.audioIndex, { session.selectTrack(StreamKind.AUDIO, it); picking = null }) { picking = null }
        else -> TrackPicker("Subtitles", p.subtitleChoices(), p.subtitleIndex, { session.selectTrack(StreamKind.SUBTITLE, it); picking = null }) { picking = null }
    }
}

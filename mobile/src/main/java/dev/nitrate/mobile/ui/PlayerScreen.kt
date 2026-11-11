package dev.nitrate.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.TrackChoice
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.infoRows
import dev.nitrate.core.playback.subtitleChoices
import dev.nitrate.mobile.platform.FullscreenPlayback
import dev.nitrate.mobile.platform.VideoSurface

private enum class PlayerDialog { NONE, AUDIO, SUBTITLES, INFO }

@Composable
fun PlayerScreen(actions: AppActions, itemId: String, fromStart: Boolean, onExit: () -> Unit, onEnded: () -> Unit) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val session = remember(itemId) {
        PlaybackSession(context, actions.server, actions.container.playbackSettings, deviceNoun = "This device", onEnded = { ended() })
            .also { it.load(itemId, fromStart) }
    }
    DisposableEffect(session) { onDispose { session.release() } }
    FullscreenPlayback()
    BackHandler(onBack = onExit)

    val item by session.item.collectAsState()
    val plan by session.plan.collectAsState()
    val error by session.error.collectAsState()
    val notice by session.notice.collectAsState()
    val videoDecoder by session.videoDecoder.collectAsState()
    val audioDecoder by session.audioDecoder.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf(PlayerDialog.NONE) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(session.player, onControlsVisible = { controlsVisible = it }, modifier = Modifier.fillMaxSize())

        // Top bar that shows and hides with the player's own controls.
        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(item?.displayTitle.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(item?.episodeLabel, plan?.method?.takeIf { it != PlayMethod.DIRECT_PLAY }?.let { if (it == PlayMethod.TRANSCODE) "Transcoding" else "Direct stream" })
                    if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                TextButton(onClick = { dialog = PlayerDialog.AUDIO }, enabled = plan != null) { Text("Audio", color = Color.White) }
                TextButton(onClick = { dialog = PlayerDialog.SUBTITLES }, enabled = plan != null) { Text("Subtitles", color = Color.White) }
                IconButton(onClick = { dialog = PlayerDialog.INFO }, enabled = plan != null) { Icon(Icons.Default.Info, "Playback info", tint = Color.White) }
            }
        }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).width(420.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(16.dp),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
    }

    val p = plan
    when {
        p == null || dialog == PlayerDialog.NONE -> Unit
        dialog == PlayerDialog.AUDIO -> TrackPicker("Audio", p.audioChoices(), p.audioIndex, { session.selectTrack(StreamKind.AUDIO, it); dialog = PlayerDialog.NONE }) { dialog = PlayerDialog.NONE }
        dialog == PlayerDialog.SUBTITLES -> TrackPicker("Subtitles", p.subtitleChoices(), p.subtitleIndex, { session.selectTrack(StreamKind.SUBTITLE, it); dialog = PlayerDialog.NONE }) { dialog = PlayerDialog.NONE }
        dialog == PlayerDialog.INFO -> AlertDialog(
            onDismissRequest = { dialog = PlayerDialog.NONE },
            title = { Text("Playback info") },
            text = {
                Column {
                    p.infoRows(videoDecoder, audioDecoder).forEach { (label, value) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = PlayerDialog.NONE }) { Text("Close") } },
        )
    }
}

@Composable
private fun TrackPicker(title: String, choices: List<TrackChoice>, selected: Int?, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (choices.isEmpty()) Text("No tracks available")
            LazyColumn {
                items(choices, key = { it.index }) { choice ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(choice.index) },
                        leadingContent = { RadioButton(selected = choice.index == selected, onClick = { onSelect(choice.index) }) },
                        headlineContent = { Text(choice.title) },
                        supportingContent = choice.detail?.let { { Text(it) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

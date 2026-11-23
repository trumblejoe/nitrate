package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.mobile.platform.PlatformBackHandler

/** The desktop player (mpv comes next): for now, says so. */
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
    testPlay: ((dev.nitrate.core.playback.TestPlayResult) -> Unit)?,
) {
    PlatformBackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Player coming next", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onExit) { Text("Back") }
        }
    }
}

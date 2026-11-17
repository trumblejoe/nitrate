package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Dual subtitles: the second subtitle's current line, at the top of the picture. */
@Composable
fun SecondSubtitleLine(session: dev.nitrate.android.playback.PlaybackSession, scale: Float, modifier: Modifier = Modifier) {
    val second by session.secondSubtitle.collectAsState()
    val s = second ?: return
    val line by produceState<String?>(null, s) {
        while (true) {
            value = dev.nitrate.core.playback.SecondSubtitles.at(s.cues, session.player.currentPosition)
            delay(100)
        }
    }
    line?.let {
        Text(
            it, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * scale),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

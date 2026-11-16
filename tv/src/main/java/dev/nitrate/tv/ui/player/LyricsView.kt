package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.music.Lyrics

/**
 * A song's lyrics on the TV, beside the cover: timed ones follow the song (the line being sung
 * bright and kept in view), plain ones shown whole. Nothing here takes focus; the remote keeps
 * controlling playback.
 */
@Composable
fun LyricsView(lyrics: Lyrics, positionMs: Long, modifier: Modifier = Modifier) {
    val current = lyrics.currentIndex(positionMs)
    val list = rememberLazyListState()
    LaunchedEffect(current) { current?.let { list.animateScrollToItem((it - 2).coerceAtLeast(0)) } }
    LazyColumn(
        modifier.fillMaxWidth(0.5f).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xD9000000), Color(0xF2000000)))),
        state = list,
        userScrollEnabled = false,
        contentPadding = PaddingValues(start = 64.dp, end = 56.dp, top = 120.dp, bottom = 240.dp),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val sung = current == i
            Text(
                line.text.ifBlank { " " },
                style = if (sung) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = if (sung) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    !lyrics.synced || sung -> Color.White
                    current != null && i < current -> Color.White.copy(alpha = 0.4f)
                    else -> Color.White.copy(alpha = 0.65f)
                },
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

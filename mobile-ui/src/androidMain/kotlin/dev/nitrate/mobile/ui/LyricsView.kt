package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nitrate.core.music.Lyrics

/**
 * A song's lyrics over the player: timed ones follow the song (the line being sung bright and
 * kept in view; tap a line to go there), plain ones scroll as text.
 */
@Composable
fun LyricsView(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val current = lyrics.currentIndex(positionMs)
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        // Keeps the sung line a little above the middle.
        current?.let { list.animateScrollToItem((it - 2).coerceAtLeast(0)) }
    }
    LazyColumn(
        modifier.fillMaxSize().background(Color(0xCC000000)),
        state = list,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 96.dp),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val sung = current == i
            val past = current != null && i < current
            Text(
                line.text.ifBlank { " " },
                style = if (sung) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = if (sung) FontWeight.Bold else FontWeight.SemiBold,
                color = when {
                    !lyrics.synced -> Color.White
                    sung -> Color.White
                    past -> Color.White.copy(alpha = 0.45f)
                    else -> Color.White.copy(alpha = 0.7f)
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
                    .then(line.startMs?.let { start -> Modifier.pressable({ onSeek(start) }) } ?: Modifier)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

package dev.nitrate.tv.ui.player

import dev.nitrate.core.playlists.upNextHeading
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.delay

/**
 * What plays next, near the end: the next episode (playing by itself after [seconds]) or the
 * next film in a collection ([seconds] null: only when asked, since a film can have scenes in its
 * credits). Takes focus on "Play now" when it appears; "Hide" (or Back) keeps watching. [compact]:
 * one line with no picture, to sit in the controls' row of buttons while they're up (the same card,
 * so the countdown and focus carry on as the controls come and go).
 */
@Composable
fun UpNextCard(
    next: MediaItem, server: MediaServer, seconds: Int?, onPlay: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier, onCountdownEnd: () -> Unit = onPlay, compact: Boolean = false,
    /** The playlist whose order this follows ("Next in Kids Bedtime"); null: the show's next episode (or the collection's next film). */
    playlist: String? = null,
) {
    var left by remember(next.id) { mutableIntStateOf(seconds ?: 0) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(next.id) {
        runCatching { playFocus.requestFocus() }
        if (seconds == null) return@LaunchedEffect
        while (left > 0) {
            delay(1_000)
            left--
        }
        onCountdownEnd()
    }
    // Spoiler-safe: an episode not seen yet shows as "S1:E5", blurred.
    val shownNext = dev.nitrate.tv.ui.partners.spoilerSafe(next)
    // Which order it follows, said plainly: the playlist's, the show's or the collection's.
    val heading = upNextHeading(seconds?.let { left }, playlist, next)
    val name = shownNext.episodeLabel ?: (shownNext.name + (shownNext.productionYear?.let { " ($it)" } ?: ""))
    if (compact) {
        Row(
            modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216)).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.widthIn(max = 190.dp)) {
                Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onPlay, modifier = Modifier.focusRequester(playFocus)) { Text("Play now") }
            OutlinedButton(onClick = onHide) { Text("Hide") }
        }
        return
    }
    Row(
        modifier.width(560.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(200.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.DarkGray)) {
            (server.backdropUrl(next, 400) ?: server.imageUrl(next, dev.nitrate.core.model.ImageKind.PRIMARY, 400))?.let {
                AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(if (shownNext.spoiler) Modifier.blur(20.dp) else Modifier))
            }
        }
        Column(Modifier.padding(start = 18.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (seconds == null) "NEXT IN THE COLLECTION" else "UP NEXT · ${left}s",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
            )
            Text(
                shownNext.episodeLabel ?: (shownNext.name + (shownNext.productionYear?.let { " ($it)" } ?: "")),
                style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = onPlay, modifier = Modifier.focusRequester(playFocus)) { Text("Play now") }
                OutlinedButton(onClick = onHide) { Text("Hide") }
            }
        }
    }
}

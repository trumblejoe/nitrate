package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.recap.Recap
import dev.nitrate.core.recap.RecapClip
import dev.nitrate.core.recap.Recaps
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import kotlinx.coroutines.delay

/**
 * "Last time" on TV: before resuming after a break, the last few things watched (nothing ahead),
 * where you are, and the ending of the last one as a clip. Play is focused, so one press carries on.
 */
@Composable
fun RecapScreen(container: AppContainer, onPlayOn: (Recap) -> Unit, onClip: (RecapClip) -> Unit, onBack: () -> Unit) {
    val recap = container.pendingRecap
    if (recap == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val server = container.server
    val play = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        dev.nitrate.tv.ui.components.Backdrop(recap.cards.lastOrNull()?.let(server::backdropUrl), Modifier.fillMaxSize())
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last time", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(recap.title, style = MaterialTheme.typography.displaySmall)
                    Text("${recap.where} · you last watched ${recap.since}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onPlayOn(recap) }, modifier = Modifier.focusRequester(play)) { Text("Play ${Recaps.label(recap.next)}") }
                    recap.clip?.let { clip ->
                        OutlinedButton(onClick = { onClip(clip) }) { Text("Watch the ending of ${Recaps.label(clip.item)} (${(clip.endMs - clip.startMs) / 60_000} min)") }
                    }
                }
            }
            recap.cards.forEach { item ->
                item(key = item.id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth(0.8f)) {
                        Box(Modifier.width(260.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            (server.imageUrl(item, ImageKind.PRIMARY, 300) ?: server.backdropUrl(item, 480))?.let {
                                AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(Recaps.label(item), style = MaterialTheme.typography.titleMedium)
                            item.user.lastPlayedMs?.let { Text("Watched ${Recaps.since(it, System.currentTimeMillis())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            item.overview?.takeIf { LocalDisplayPrefs.current.overview }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { play.requestFocus() } }
}

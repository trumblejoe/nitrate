package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.recap.Recaps

/**
 * "Last time": before resuming a show (or a playlist in story order) after a break, the last few
 * things the user watched (only those: nothing ahead is shown), where they are, and the ending of
 * the last one as a clip. Then Play carries on.
 */
@Composable
fun RecapScreen(actions: AppActions) {
    val recap = actions.container.pendingRecap
    if (recap == null) {
        LaunchedEffect(Unit) { actions.nav.popBackStack() }
        return
    }
    val server = actions.server
    fun playOn() {
        actions.nav.popBackStack()
        if (recap.then.isNotEmpty()) actions.playQueue(listOf(recap.next.id) + recap.then)
        else actions.nav.navigate(PlayerRoute(recap.next.id))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Last time", style = MaterialTheme.typography.labelLarge, color = accentColor())
        Text(recap.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("${recap.where} · you last watched ${recap.since}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Play ${Recaps.label(recap.next)}", Icons.Default.PlayArrow, primary = true, onClick = ::playOn, modifier = Modifier.weight(1f))
        }
        recap.clip?.let { clip ->
            PillButton(
                "Watch the ending of ${Recaps.label(clip.item)} (${(clip.endMs - clip.startMs) / 60_000} min)", null, primary = false,
                onClick = { actions.nav.navigate(PlayerRoute(clip.item.id, startMs = clip.startMs, clipEndMs = clip.endMs)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        recap.cards.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    server.cardImage(item, CardShape.WIDE, 280)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f)) {
                    Text(Recaps.label(item), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.user.lastPlayedMs?.let { Text("Watched ${Recaps.since(it, dev.nitrate.android.nowMs())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item.overview?.takeIf { LocalDisplayPrefs.current.overview }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.copyOnLongPress(it))
            }
        }
        TextButton(onClick = ::playOn) { Text("Skip the recap") }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

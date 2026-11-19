package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.help.PlaybackGuide

/** Settings → Tools → Checking how things play: the guide to the playback tools ([PlaybackGuide]). */
@Composable
fun GuideScreen(admin: Boolean) {
    val sections = remember(admin) { PlaybackGuide.sectionsFor(tv = false, admin = admin) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Checking how things play", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("What each tool is for, how to use it, and what to do about the usual problems.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(sections, key = { it.id }) { s ->
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = accentColor())
                Text(s.intro, style = MaterialTheme.typography.bodyMedium)
                s.steps.forEachIndexed { i, step ->
                    Row {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                s.tips.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item { Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
}

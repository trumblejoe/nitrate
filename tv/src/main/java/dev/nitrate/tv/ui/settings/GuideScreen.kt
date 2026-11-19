package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nitrate.core.help.GuideSection
import dev.nitrate.core.help.PlaybackGuide

/**
 * Settings → Tools → Checking how things play: the guide to Nitrate's playback tools
 * ([PlaybackGuide]), one part per card so ▲▼ moves through it a part at a time.
 */
@Composable
fun GuideScreen(admin: Boolean) {
    val first = remember { FocusRequester() }
    val sections = remember(admin) { PlaybackGuide.sectionsFor(tv = true, admin = admin) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                Text("Checking how things play", style = MaterialTheme.typography.headlineMedium)
                Text("What each tool is for, how to use it, and what to do about the usual problems. ▲▼ moves through it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(sections, key = { it.id }) { s ->
            GuideCard(s, if (s == sections.first()) Modifier.focusRequester(first) else Modifier)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun GuideCard(s: GuideSection, modifier: Modifier) {
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleLarge)
            Text(s.intro, style = MaterialTheme.typography.bodyLarge)
            s.steps.forEachIndexed { i, step ->
                Row {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(32.dp))
                    Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            s.tips.forEach { tip -> Text(tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

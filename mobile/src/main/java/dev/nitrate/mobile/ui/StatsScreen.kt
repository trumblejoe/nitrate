package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.stats.Tally
import dev.nitrate.core.stats.WatchStats
import dev.nitrate.core.stats.WatchStatsBuilder

private val MONTHS = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

/** "Your year": what you watched and listened to, from your server's record. */
@Composable
fun StatsScreen(actions: AppActions) {
    val history by produceState<Result<List<MediaItem>>?>(null) { value = runCatching { actions.server.watchHistory() } }
    val offset = remember { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() }
    val list = history?.getOrNull()
    val years = remember(list) { list?.let { WatchStatsBuilder.years(it, offset) }.orEmpty() }
    var year by remember(years) { mutableStateOf(years.firstOrNull()) }
    val stats = remember(list, year) { list?.let { WatchStatsBuilder.build(it, year, offset) } }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(year?.let { "Your $it" } ?: "Your watching", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        }
        when {
            history == null -> Text("Adding it up…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            history?.isFailure == true -> Text("Couldn't read your history: ${history?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
            stats == null || (stats.isEmpty && years.isEmpty()) -> Text("Nothing finished yet. Watch something and come back.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    years.forEach { y -> FilterChip(selected = year == y, onClick = { year = y }, label = { Text("$y") }) }
                    FilterChip(selected = year == null, onClick = { year = null }, label = { Text("All time") })
                }
                StatsBody(stats)
                Text(
                    "From your server's record of what you finished. It keeps only the last time you watched each title, so a title counts in that year; time counts every play.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsBody(s: WatchStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigNumber(WatchStatsBuilder.duration(s.watchMinutes), "watching", Modifier.weight(1f))
        BigNumber("${s.films}", if (s.films == 1) "film" else "films", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigNumber("${s.episodes}", "episodes of ${s.shows} show${if (s.shows == 1) "" else "s"}", Modifier.weight(1f))
        BigNumber("${s.songs}", if (s.songs == 1) "song" else "songs", Modifier.weight(1f))
    }
    if (s.byMonth.any { it > 0 }) Months(s.byMonth)
    Tallies("Top shows", s.topShows) { "${it.count} episode${if (it.count == 1) "" else "s"} · ${WatchStatsBuilder.duration(it.minutes)}" }
    Tallies("Top genres", s.topGenres) { WatchStatsBuilder.duration(it.minutes) }
    Tallies("Watched again", s.rewatched) { "${it.count} times" }
    Tallies("Top artists", s.topArtists) { "${it.count} play${if (it.count == 1) "" else "s"}" }
}

@Composable
private fun BigNumber(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = accentColor())
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Months(months: List<Int>) {
    val most = (months.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        SectionLabel("Month by month")
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            months.forEachIndexed { i, n ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(0.8f * n / most + 0.02f).clip(RoundedCornerShape(6.dp))
                            .background(if (n > 0) accentColor() else Color.White.copy(alpha = 0.08f)),
                    )
                    Text(MONTHS[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Tallies(title: String, items: List<Tally>, detail: (Tally) -> String) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title)
        items.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = accentColor(), modifier = Modifier.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(detail(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))

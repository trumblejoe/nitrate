package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.stats.Tally
import dev.nitrate.core.stats.WatchStats
import dev.nitrate.core.stats.WatchStatsBuilder

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Your year" on TV: what you watched and listened to, from your server's record. */
@Composable
fun StatsScreen(server: MediaServer) {
    val history by produceState<Result<List<MediaItem>>?>(null) { value = runCatching { server.watchHistory() } }
    val offset = remember { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() }
    val list = history?.getOrNull()
    val years = remember(list) { list?.let { WatchStatsBuilder.years(it, offset) }.orEmpty() }
    var year by remember(years) { mutableStateOf(years.firstOrNull()) }
    val stats = remember(list, year) { list?.let { WatchStatsBuilder.build(it, year, offset) } }
    val first = remember { FocusRequester() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text(year?.let { "Your $it" } ?: "Your watching", style = MaterialTheme.typography.displaySmall) }
        when {
            history == null -> item { Text("Adding it up…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            history?.isFailure == true -> item { Text("Couldn't read your history: ${history?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error) }
            stats == null || years.isEmpty() -> item { Text("Nothing finished yet. Watch something and come back.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(years.size) { i ->
                            val y = years[i]
                            FilterChip(selected = year == y, onClick = { year = y }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text("$y") }
                        }
                        item { FilterChip(selected = year == null, onClick = { year = null }) { Text("All time") } }
                    }
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                }
                item { Body(stats) }
                item {
                    Text(
                        "From your server's record of what you finished. It keeps only the last time you watched each title, so a title counts in that year; time counts every play.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Body(s: WatchStats) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Big(WatchStatsBuilder.duration(s.watchMinutes), "watching", Modifier.weight(1f))
            Big("${s.films}", if (s.films == 1) "film" else "films", Modifier.weight(1f))
            Big("${s.episodes}", "episodes of ${s.shows} show${if (s.shows == 1) "" else "s"}", Modifier.weight(1f))
            Big("${s.songs}", if (s.songs == 1) "song" else "songs", Modifier.weight(1f))
        }
        if (s.byMonth.any { it > 0 }) Months(s.byMonth)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Tallies("Top shows", s.topShows, Modifier.weight(1f)) { "${it.count} episode${if (it.count == 1) "" else "s"} · ${WatchStatsBuilder.duration(it.minutes)}" }
            Tallies("Top genres", s.topGenres, Modifier.weight(1f)) { WatchStatsBuilder.duration(it.minutes) }
            Tallies("Watched again", s.rewatched, Modifier.weight(1f)) { "${it.count} times" }
            Tallies("Top artists", s.topArtists, Modifier.weight(1f)) { "${it.count} play${if (it.count == 1) "" else "s"}" }
        }
    }
}

@Composable
private fun Big(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Months(months: List<Int>) {
    val most = (months.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        Text("Month by month", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
        Row(Modifier.fillMaxWidth(0.7f).height(110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            months.forEachIndexed { i, n ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.8f * n / most + 0.02f).clip(RoundedCornerShape(6.dp))
                        .background(if (n > 0) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)))
                    Text(MONTHS[i], style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Tallies(title: String, items: List<Tally>, modifier: Modifier, detail: (Tally) -> String) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) return@Column
        Text(title, style = MaterialTheme.typography.titleLarge)
        items.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 14.dp))
                Column {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(detail(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

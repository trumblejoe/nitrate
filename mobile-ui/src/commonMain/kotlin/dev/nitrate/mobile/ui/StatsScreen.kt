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
import androidx.compose.runtime.collectAsState
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
    // With the watch diary (Companion plugin), every watch counts on its own date.
    var fromDiary by remember { mutableStateOf<Long?>(null) }
    // The server's record and the diary (whose entries say which were watched with a group).
    val history by produceState<Result<Pair<List<MediaItem>, List<dev.nitrate.core.diary.DiaryEntry>?>>?>(null) {
        value = runCatching {
            val record = actions.server.watchHistory()
            val diary = if (actions.server.companion()?.features?.contains(dev.nitrate.core.diary.Diary.FEATURE) == true) runCatching { actions.server.diary() }.getOrNull() else null
            fromDiary = diary?.minOfOrNull { it.watchedAtMs }
            record to diary?.takeIf { it.isNotEmpty() }
        }
    }
    val offset = remember { dev.nitrate.mobile.platform.timeZoneOffsetMs() }
    val groupsInfo by actions.container.partners.collectAsState()
    val loaded = history?.getOrNull()
    // Years with any watching, the user's own or with groups.
    val list = remember(loaded) { loaded?.let { (record, diary) -> if (diary == null) record else dev.nitrate.core.diary.Diary.asHistory(diary, record) } }
    val years = remember(list) { list?.let { WatchStatsBuilder.years(it, offset) }.orEmpty() }
    var year by remember(years) { mutableStateOf(years.firstOrNull()) }
    val split = remember(loaded, year, groupsInfo) {
        loaded?.let { (record, diary) ->
            dev.nitrate.core.stats.SplitStatsBuilder.build(diary, record, year, offset) { id -> groupsInfo?.groups?.firstOrNull { it.id == id }?.let(dev.nitrate.core.partners.WithChip::nameOf) }
        }
    }
    val stats = split?.own
    // Real watch time (Companion `watching`): every sitting, pauses left out.
    val sittings by produceState(emptyList<dev.nitrate.core.companion.Sitting>()) {
        value = runCatching { actions.server.sittings(0, dev.nitrate.android.nowMs()) }.getOrDefault(emptyList())
    }
    val watchTime = remember(sittings, year) { dev.nitrate.core.companion.WatchTime.forYear(sittings, year, offset) }

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
                split?.takeIf { it.groups.isNotEmpty() || !it.withGroups.isEmpty }?.let { GroupsBody(it) }
                watchTime?.let { w ->
                    Column {
                        Text("Time in front of the screen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(dev.nitrate.core.companion.WatchTime.line(w), style = MaterialTheme.typography.bodyMedium)
                        Text("Counted by your server as you watch, in any app that tells it what plays: rewatches, unfinished titles and stops part way included, pauses not.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    fromDiary?.let { "From your watch diary, which counts every watch on its own date (since ${dev.nitrate.mobile.platform.formatDate(it)}; before that, your server's record of the last time you watched each title)." }
                        ?: "From your server's record of what you finished. It keeps only the last time you watched each title, so a title counts in that year; time counts every play.",
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

/** Watching with groups, kept apart from the user's own: the total, then each group's share. */
@Composable
private fun GroupsBody(s: dev.nitrate.core.stats.SplitStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("With your watch groups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Watched with a group: counted here, not in your own figures above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigNumber(WatchStatsBuilder.duration(s.withGroups.watchMinutes), "with groups", Modifier.weight(1f))
            BigNumber("${s.withGroups.films + s.withGroups.episodes}", "films and episodes", Modifier.weight(1f))
        }
        Tallies("By group", s.groups.map { Tally(it.name, it.count, it.minutes, it.groupId) }) { "${it.count} watched · ${WatchStatsBuilder.duration(it.minutes)}" }
    }
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

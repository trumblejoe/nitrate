package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.nitrate.android.viewmodel.DetailsViewModel
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.model.formatRuntime

@Composable
fun DetailsScreen(actions: AppActions, itemId: String, seasonId: String?) {
    val vm = viewModel(key = "details-$itemId") { DetailsViewModel(actions.server, itemId, seasonId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var first by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!first) vm.load()
        first = false
        onPauseOrDispose { }
    }

    val item = state.item
    if (item == null) {
        if (state.error != null) ErrorMessage(state.error!!, vm::load) else Loading()
        return
    }
    val server = actions.server
    val bg = MaterialTheme.colorScheme.background
    val records by actions.container.downloads.downloads.collectAsState(emptyList())
    val downloaded = remember(records) { records.map { it.itemId }.toSet() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                AsyncImage(
                    server.backdropUrl(item),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to bg))
                    },
                )
                IconButton(
                    onClick = { actions.nav.popBackStack() },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000), contentColor = Color.White),
                    modifier = Modifier.padding(top = 36.dp, start = 8.dp),
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                val logo = server.logoUrl(item)
                Box(Modifier.align(Alignment.BottomStart).padding(ScreenPadding)) {
                    if (logo != null) {
                        AsyncImage(logo, item.displayTitle, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                            modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 90.dp))
                    } else {
                        Text(item.displayTitle, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item(key = "info") {
            Column(Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(item.metaLine(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.genres.isNotEmpty()) Text(item.genres.take(4).joinToString(" / "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                PlayButtons(actions, item, state.nextUp ?: state.episodes.firstOrNull(), vm)
                item.tagline?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary) }
                item.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (item.kind == ItemKind.SERIES && state.seasons.isNotEmpty()) {
            item(key = "seasons") {
                PrimaryScrollableTabRow(selectedTabIndex = state.selectedSeason, edgePadding = ScreenPadding, modifier = Modifier.padding(top = 16.dp)) {
                    state.seasons.forEachIndexed { i, season ->
                        Tab(selected = i == state.selectedSeason, onClick = { vm.selectSeason(i) }, text = { Text(season.name) })
                    }
                }
            }
            item(key = "download-season") {
                val missing = state.episodes.filter { it.id !in downloaded }
                var picking by remember { mutableStateOf(false) }
                if (missing.isNotEmpty()) {
                    TextButton(onClick = { picking = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(NitrateIcons.Download, null, modifier = Modifier.padding(end = 8.dp))
                        Text(if (missing.size == state.episodes.size) "Download season" else "Download ${missing.size} more episodes")
                    }
                }
                if (picking) {
                    val season = state.seasons.getOrNull(state.selectedSeason)?.name.orEmpty()
                    DownloadDialog(actions, missing, "${item.name} · $season") { picking = false }
                }
            }
            items(state.episodes, key = { "ep-${it.id}" }) { episode ->
                ListItem(
                    modifier = Modifier.clickable { actions.open(episode) },
                    leadingContent = {
                        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            server.cardImage(episode, CardShape.WIDE, 200)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            if (episode.id in downloaded) {
                                Icon(NitrateIcons.DownloadDone, "Downloaded", tint = Color.White,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000), CircleShape).size(18.dp).padding(2.dp))
                            }
                            if (episode.user.played) {
                                Icon(Icons.Default.Check, "Watched", tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape).size(18.dp).padding(2.dp))
                            }
                        }
                    },
                    headlineContent = { Text("${episode.indexNumber ?: "–"}. ${episode.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(episode.runtimeMs?.let(::formatRuntime), episode.overview).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { IconButton(onClick = { actions.play(episode, false) }) { Icon(Icons.Default.PlayArrow, "Play") } },
                )
            }
        }
        val people = item.people.filter { it.imageTag != null || it.role != null }.take(20)
        if (people.isNotEmpty()) {
            item(key = "cast") {
                Column(Modifier.padding(top = 24.dp)) {
                    SectionTitle("Cast & crew")
                    LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(people, key = { "${it.id}-${it.role}" }) { person ->
                            Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                                    val url = server.personImageUrl(person)
                                    if (url != null) AsyncImage(url, person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    else Text(person.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""))
                                }
                                Text(person.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                person.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        }
        if (state.similar.isNotEmpty()) {
            item(key = "similar") {
                Column(Modifier.padding(top = 24.dp)) {
                    SectionTitle("More like this")
                    LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.similar, key = { it.id }) { s ->
                            MediaCard(s, server.cardImage(s, CardShape.POSTER), CardShape.POSTER, onClick = { actions.open(s) }, width = 110.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButtons(actions: AppActions, item: MediaItem, seriesTarget: MediaItem?, vm: DetailsViewModel) {
    // "Play" resumes a movie or episode, or plays the next episode of a series.
    val target = if (item.kind == ItemKind.SERIES) seriesTarget else item.takeIf { it.isPlayable }
    val resumeMs = target?.user?.positionMs ?: 0L
    val label = when {
        target == null -> "Play"
        item.kind == ItemKind.SERIES -> (if (resumeMs > 0) "Resume" else "Play") + " S${target.seasonNumber}:E${target.indexNumber}"
        resumeMs > 0 -> "Resume from ${formatClock(resumeMs)}"
        else -> "Play"
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { target?.let { actions.play(it, false) } }, enabled = target != null) {
            Icon(Icons.Default.PlayArrow, null)
            Text(label)
        }
        if (resumeMs > 0 && target != null) OutlinedButton(onClick = { actions.play(target, true) }) { Text("From start") }
        FilledTonalIconToggleButton(checked = item.user.played, onCheckedChange = { vm.togglePlayed() }) {
            Icon(Icons.Default.Check, if (item.user.played) "Watched" else "Mark watched")
        }
        FilledTonalIconToggleButton(checked = item.user.isFavorite, onCheckedChange = { vm.toggleFavorite() }) {
            Icon(if (item.user.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite")
        }
        if (item.isPlayable) DownloadButton(actions, item)
    }
}

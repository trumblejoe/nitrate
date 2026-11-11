package dev.nitrate.mobile.ui

import dev.nitrate.core.pages.facts
import dev.nitrate.core.pages.isSpoiler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
import dev.nitrate.core.model.formatOneDecimal
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.mobile.platform.rememberArtworkColor

@Composable
fun DetailsScreen(actions: AppActions, itemId: String, seasonId: String?, artKey: String? = null) {
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
    val backdrop = server.backdropUrl(item) ?: server.imageUrl(item, maxHeight = 1080)
    val tint by rememberArtworkColor(backdrop)
    val records by actions.container.downloads.downloads.collectAsState(emptyList())
    val downloaded = remember(records) { records.map { it.itemId }.toSet() }
    val list = rememberLazyListState()
    val bg = MaterialTheme.colorScheme.background
    val prefs = LocalDisplayPrefs.current
    val account by actions.container.account.collectAsState()

    TintedBackdrop(tint, strength = 0.32f) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "hero") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val heroHeight = (maxWidth * 1.05f).coerceIn(340.dp, 560.dp)
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        AsyncImage(
                            backdrop,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().sharedArtwork(artKey).fadeBottom(0.55f).graphicsLayer {
                                // Parallax: the picture scrolls at half speed and fades as it leaves.
                                val scrolled = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else size.height
                                translationY = scrolled * 0.5f
                                alpha = 1f - (scrolled / size.height).coerceIn(0f, 1f) * 0.6f
                            },
                        )
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x88000000), 0.25f to Color.Transparent, 1f to Color.Transparent)))
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val logo = server.logoUrl(item)?.takeIf { prefs.logos }
                            if (logo != null) {
                                AsyncImage(logo, item.displayTitle, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                                    modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 100.dp))
                            } else {
                                Text(item.displayTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White) }
                        }
                    }
                }
            }
            item(key = "facts") {
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { prefs.facts(item).forEach { GlassChip(it) } }
                    if (item.genres.isNotEmpty() && prefs.genres) Text(item.genres.take(4).joinToString("  •  "), color = accentColor(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    PlaySection(actions, item, state.nextUp ?: state.episodes.firstOrNull())
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RoundAction(Icons.Default.Check, "Watched", onClick = vm::togglePlayed, active = item.user.played)
                        RoundAction(if (item.user.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite", onClick = vm::toggleFavorite, active = item.user.isFavorite)
                        if (item.isPlayable) DownloadAction(actions, item)
                        if (item.kind == ItemKind.SERIES) SmartDownloadAction(actions, item)
                        var adding by remember { mutableStateOf(false) }
                        if (item.isPlayable || item.kind == ItemKind.SERIES) RoundAction(Icons.Default.Add, "Add to", onClick = { adding = true })
                        if (adding) AddToListDialog(actions, item) { adding = false }
                        if (account?.isAdmin == true) RoundAction(Icons.Default.Edit, "Manage", onClick = { actions.nav.navigate(ManageItemRoute(item.id)) })
                    }
                    item.tagline?.takeIf { prefs.tagline }?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White) }
                    item.overview?.takeIf { prefs.overview && !prefs.isSpoiler(item) }?.let { ExpandableText(it) }
                }
            }
            if (item.kind == ItemKind.SERIES && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(Modifier.padding(top = 12.dp)) {
                        SectionHeader("Episodes")
                        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.seasons.size) { i ->
                                val selected = i == state.selectedSeason
                                val accent = accentColor()
                                Text(
                                    state.seasons[i].name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) contentOn(accent) else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                        .background(if (selected) accent else MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .pressable({ vm.selectSeason(i) }).padding(horizontal = 16.dp, vertical = 9.dp),
                                )
                            }
                        }
                    }
                }
                item(key = "download-season") {
                    val missing = state.episodes.filter { it.id !in downloaded }
                    var picking by remember { mutableStateOf(false) }
                    if (missing.isNotEmpty()) {
                        Row(
                            Modifier.padding(start = ScreenPadding, top = 10.dp).clip(RoundedCornerShape(20.dp)).pressable({ picking = true }).padding(vertical = 6.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(NitrateIcons.Download, null, tint = accentColor(), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (missing.size == state.episodes.size) "Download season" else "Download ${missing.size} more episodes", color = accentColor(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (picking) {
                        val season = state.seasons.getOrNull(state.selectedSeason)?.name.orEmpty()
                        DownloadDialog(actions, missing, "${item.name} · $season") { picking = false }
                    }
                }
                items(state.episodes, key = { "ep-${it.id}" }) { episode -> EpisodeCard(actions, episode, episode.id in downloaded) }
            }
            val people = item.people.filter { it.imageTag != null || it.role != null }.take(20).takeIf { prefs.cast }.orEmpty()
            if (people.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("Cast & crew")
                        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(people, key = { "${it.id}-${it.role}" }) { person ->
                                PersonBubble(person, actions) { actions.nav.navigate(PersonRoute(person.id, person.name)) }
                            }
                        }
                    }
                }
            }
            if (state.contents.isNotEmpty()) {
                item(key = "contents") {
                    var removing by remember { mutableStateOf(false) }
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("In this collection") {
                            if (account?.canManageCollections == true) androidx.compose.material3.TextButton(onClick = { removing = !removing }) { Text(if (removing) "Done" else "Edit") }
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.contents, key = { it.id }) { m ->
                                val key = "contents-${item.id}-${m.id}"
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    MediaCard(m, server.cardImage(m, CardShape.POSTER), CardShape.POSTER, onClick = { actions.open(m, key) }, width = 116.dp, sharedKey = key)
                                    if (removing) androidx.compose.material3.TextButton(onClick = { vm.removeFromCollection(m) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
            if (state.similar.isNotEmpty() && prefs.similar) {
                item(key = "similar") {
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("More like this")
                        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.similar, key = { it.id }) { s ->
                                val key = "similar-${item.id}-${s.id}"
                                MediaCard(s, server.cardImage(s, CardShape.POSTER), CardShape.POSTER, onClick = { actions.open(s, key) }, width = 116.dp, sharedKey = key)
                            }
                        }
                    }
                }
            }
        }
        IconButton(
            onClick = { actions.nav.popBackStack() },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000), contentColor = Color.White),
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 8.dp, top = 4.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }
}


/** The big Play (or Resume) button, with progress and "From start" when part-watched. */
@Composable
private fun PlaySection(actions: AppActions, item: MediaItem, seriesTarget: MediaItem?) {
    val target = if (item.kind == ItemKind.SERIES) seriesTarget else item.takeIf { it.isPlayable }
    val resumeMs = target?.user?.positionMs ?: 0L
    val label = when {
        target == null -> "Play"
        item.kind == ItemKind.SERIES -> (if (resumeMs > 0) "Resume" else "Play") + " S${target.seasonNumber}:E${target.indexNumber}"
        resumeMs > 0 -> "Resume from ${formatClock(resumeMs)}"
        else -> "Play"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(label, Icons.Default.PlayArrow, primary = true, onClick = { target?.let { actions.play(it, false) } }, modifier = Modifier.weight(1f))
            if (resumeMs > 0 && target != null) PillButton("Restart", Icons.Default.Refresh, primary = false, onClick = { actions.play(target, true) })
        }
        val pct = target?.user?.playedPercentage?.toFloat()?.div(100f)
        if (pct != null && pct > 0f && !target.user.played) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(pct).height(4.dp).background(accentColor()))
                }
                timeLeft(target)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp)) }
            }
        }
    }
}

@Composable
private fun ExpandableText(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.85f),
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.animateContentSize().pressable({ expanded = !expanded }),
    )
}

@Composable
private fun EpisodeCard(actions: AppActions, episode: MediaItem, downloaded: Boolean) {
    Column(Modifier.fillMaxWidth().pressable({ actions.open(episode, "ep-${episode.id}") }).padding(horizontal = ScreenPadding, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(148.dp).aspectRatio(16f / 9f).sharedArtwork("ep-${episode.id}").clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
            ) {
                val spoiler = LocalDisplayPrefs.current.isSpoiler(episode)
                actions.server.cardImage(episode, CardShape.WIDE, 240)?.let {
                    AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(if (spoiler) Modifier.blur(16.dp) else Modifier))
                }
                // Play straight from the list.
                Box(
                    Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(Color(0x88000000)).pressable({ actions.play(episode, false) }),
                    Alignment.Center,
                ) { Icon(Icons.Default.PlayArrow, "Play", tint = Color.White) }
                val pct = episode.user.playedPercentage?.toFloat()?.div(100f)
                if (pct != null && pct > 0f && !episode.user.played && LocalDisplayPrefs.current.progressBars) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x99000000))) {
                        Box(Modifier.fillMaxWidth(pct).height(3.dp).background(accentColor()))
                    }
                }
                Row(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (downloaded) Badge(NitrateIcons.DownloadDone, "Downloaded")
                    if (episode.user.played && LocalDisplayPrefs.current.watchedMarks) Badge(Icons.Default.Check, "Watched")
                }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text("${episode.indexNumber ?: "–"}. ${episode.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                episode.runtimeMs?.let { Text(formatRuntime(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
        episode.overview?.takeIf { LocalDisplayPrefs.current.let { p -> p.overview && !p.isSpoiler(episode) } }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Icon(icon, label, tint = Color.White, modifier = Modifier.background(Color(0xAA000000), CircleShape).padding(3.dp).size(14.dp))
}

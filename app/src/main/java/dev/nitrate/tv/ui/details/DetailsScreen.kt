package dev.nitrate.tv.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.focusable
import dev.nitrate.tv.data.PlaybackPlan
import dev.nitrate.tv.ui.player.PlaybackInfoPanel
import org.jellyfin.sdk.model.api.MediaType
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import coil3.compose.AsyncImage
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.ticksToMs
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.GenreLine
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.tv.ui.components.episodeLabel
import dev.nitrate.tv.ui.components.formatRuntime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

private val PagePadding = 58.dp

@Composable
fun DetailsScreen(
    repository: JellyfinRepository,
    itemId: String,
    seasonId: String?,
    onPlay: (item: BaseItemDto, fromStart: Boolean) -> Unit,
    onOpen: (BaseItemDto) -> Unit,
) {
    val vm = viewModel(key = "details-$itemId") {
        DetailsViewModel(repository, UUID.fromString(itemId), seasonId?.let(UUID::fromString))
    }
    val state by vm.state.collectAsStateWithLifecycle()

    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) vm.load()
        firstResume = false
        onPauseOrDispose { }
    }

    val item = state.item
    if (item == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(state.error ?: "Loading…", color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    var focusedEpisode by remember { mutableStateOf<BaseItemDto?>(null) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.id) { runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(repository.backdropUrl(item), Modifier.fillMaxSize())

        LazyColumn(
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            item(key = "header") {
                Header(item, repository, state, vm, playFocus, onPlay)
            }

            if (item.type == BaseItemKind.SERIES && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonSelector(state.seasons, state.selectedSeason, vm::selectSeason)
                }
                item(key = "episodes") {
                    Column {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            items(state.episodes, key = { it.id }) { episode ->
                                MediaCard(
                                    item = episode,
                                    imageUrl = repository.imageUrl(episode, ImageType.PRIMARY, 300),
                                    width = 280.dp,
                                    aspectRatio = 16f / 9f,
                                    title = "${episode.indexNumber ?: "–"}. ${episode.name.orEmpty()}",
                                    subtitle = episode.runTimeTicks?.let(::formatRuntime),
                                    onFocus = { focusedEpisode = episode },
                                    onClick = { onOpen(episode) },
                                    onPlay = { onPlay(episode, false) },
                                )
                            }
                        }
                        // Overview of the focused episode, so the row stays compact.
                        val ep = focusedEpisode?.takeIf { f -> state.episodes.any { it.id == f.id } }
                        Text(
                            ep?.overview.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = PagePadding).padding(top = 12.dp).fillMaxWidth(0.6f).height(44.dp),
                        )
                    }
                }
            }

            val people = item.people.orEmpty().filter { it.primaryImageTag != null || it.role != null }.take(20)
            if (people.isNotEmpty()) {
                item(key = "cast") {
                    Column {
                        RowTitle("Cast & crew")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(people, key = { "${it.id}-${it.role}" }) { person ->
                                PersonCard(person, repository.personImageUrl(person.id, person.primaryImageTag))
                            }
                        }
                    }
                }
            }

            if (state.similar.isNotEmpty()) {
                item(key = "similar") {
                    Column {
                        RowTitle("More like this")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            items(state.similar, key = { it.id }) { similar ->
                                MediaCard(
                                    item = similar,
                                    imageUrl = repository.imageUrl(similar, ImageType.PRIMARY, 360),
                                    width = 140.dp,
                                    aspectRatio = 2f / 3f,
                                    onClick = { onOpen(similar) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    item: BaseItemDto,
    repository: JellyfinRepository,
    state: DetailsState,
    vm: DetailsViewModel,
    playFocus: FocusRequester,
    onPlay: (BaseItemDto, Boolean) -> Unit,
) {
    Column(
        Modifier.padding(start = PagePadding, end = PagePadding, top = 48.dp).fillMaxWidth(0.6f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(60.dp))
        TitleOrLogo(item, repository.logoUrl(item))
        item.episodeLabel()?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        ItemMetaLine(item)
        GenreLine(item)
        item.taglines?.firstOrNull()?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        }
        item.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // What "Play" means depends on the type: resume a movie, or the next episode of a series.
            val playTarget: BaseItemDto? = if (item.type == BaseItemKind.SERIES) state.nextUp ?: state.episodes.firstOrNull() else item
            val resumeMs = playTarget?.userData?.playbackPositionTicks?.ticksToMs() ?: 0L
            val playLabel = when {
                playTarget == null -> "Play"
                item.type == BaseItemKind.SERIES && resumeMs > 0 -> "Resume S${playTarget.parentIndexNumber}:E${playTarget.indexNumber}"
                item.type == BaseItemKind.SERIES -> "Play S${playTarget.parentIndexNumber}:E${playTarget.indexNumber}"
                resumeMs > 0 -> "Resume from ${formatClock(resumeMs)}"
                else -> "Play"
            }
            Button(
                onClick = { playTarget?.let { onPlay(it, false) } },
                enabled = playTarget != null && (item.type == BaseItemKind.SERIES || repository.isPlayable(item)),
                modifier = Modifier.focusRequester(playFocus),
            ) { Text(playLabel) }
            if (resumeMs > 0 && playTarget != null) {
                OutlinedButton(onClick = { onPlay(playTarget, true) }) { Text("From start") }
            }
            OutlinedButton(onClick = vm::togglePlayed) {
                Text(if (item.userData?.played == true) "Watched ✓" else "Mark watched")
            }
            OutlinedButton(onClick = vm::toggleFavorite) {
                Text(if (item.userData?.isFavorite == true) "Favorite ♥" else "Favorite ♡")
            }
            if (repository.isPlayable(item)) {
                var showInfo by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showInfo = true }) { Text("Playback info") }
                if (showInfo) PlaybackDecisionDialog(repository, item) { showInfo = false }
            }
        }
    }
}

@Composable
private fun SeasonSelector(seasons: List<BaseItemDto>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PagePadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.id }) { index, season ->
            // Focusing a season switches to it, so the D-pad alone browses seasons.
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                modifier = Modifier.onFocusChanged { if (it.isFocused) onSelect(index) },
            ) {
                Text(season.name ?: "Season ${season.indexNumber}")
            }
        }
    }
}

@Composable
private fun PersonCard(person: BaseItemPerson, imageUrl: String?) {
    Column {
        Card(
            onClick = {},
            modifier = Modifier.padding(bottom = 6.dp).height(150.dp).aspectRatio(2f / 3f),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    imageUrl,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(person.name.orEmpty().split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Text(person.name.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(100.dp))
        person.role?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(100.dp))
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = PagePadding, bottom = 12.dp))
}

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** What the server would do if this item were played now, and why, without starting playback. */
@Composable
private fun PlaybackDecisionDialog(repository: JellyfinRepository, item: BaseItemDto, onDismiss: () -> Unit) {
    val plan by produceState<Result<PlaybackPlan>?>(null, item.id) {
        value = runCatching { repository.resolvePlayback(item.id, item.mediaType == MediaType.AUDIO, startMs = 0) }
    }
    Dialog(onDismissRequest = onDismiss) {
        val p = plan
        when {
            p == null -> Text("Asking the server…")
            p.isFailure -> Text(p.exceptionOrNull()?.message ?: "Failed", color = MaterialTheme.colorScheme.error)
            else -> PlaybackInfoPanel(p.getOrThrow(), videoDecoder = null, audioDecoder = null, modifier = Modifier.focusable())
        }
    }
}

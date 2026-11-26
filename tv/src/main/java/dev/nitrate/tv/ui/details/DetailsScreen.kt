package dev.nitrate.tv.ui.details

import androidx.tv.material3.ListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.launch
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import androidx.compose.runtime.rememberCoroutineScope
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.android.viewmodel.DetailsState
import dev.nitrate.android.viewmodel.DetailsViewModel
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.focusable
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.tv.ui.player.PlaybackInfoPanel
import dev.nitrate.core.model.MediaKind
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
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
import dev.nitrate.core.MediaServer
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.GenreLine
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.tv.ui.components.RoundAction
import dev.nitrate.tv.ui.components.Crumb
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.ImageKind

internal val PagePadding = 58.dp

@Composable
fun DetailsScreen(
    repository: MediaServer,
    itemId: String,
    seasonId: String?,
    onPlay: (item: MediaItem, fromStart: Boolean) -> Unit,
    onOpen: (MediaItem) -> Unit,
    /** Admins: opens the item's management screen. */
    onManage: ((String) -> Unit)? = null,
    /** May add to collections (playlists are anyone's). */
    canCollect: Boolean = false,
    /** Plays a queue (album tracks, an instant mix). */
    onPlayQueue: (List<MediaItem>) -> Unit = {},
    /** Opens everything from a studio or a year: (title, studio, year). */
    onBrowse: (String, String?, Int?) -> Unit = { _, _, _ -> },
    /** How an item would play here and why (the fixer); null shows the server's raw decision. */
    checkPlayback: (suspend (MediaItem) -> dev.nitrate.core.playback.PlaybackDiagnosis)? = null,
    applyPlaybackFix: suspend (MediaItem, dev.nitrate.core.playback.PlaybackFix) -> Unit = { _, _ -> },
    /** The server has watch partners (Companion plugin): shows partners' progress. */
    watchPartners: Boolean = false,
    /** The tracks a title will start with, and picking others (remembered like a pick in the player). */
    trackSetup: (suspend (MediaItem) -> dev.nitrate.core.playback.TrackSetup)? = null,
    pickTrack: suspend (MediaItem, dev.nitrate.core.playback.TrackSetup, dev.nitrate.core.model.StreamKind, Int) -> dev.nitrate.core.playback.TrackSetup = { _, s, _, _ -> s },
    /** Plays from a point (a marked moment). */
    onPlayFrom: (MediaItem, Long) -> Unit = { i, _ -> onPlay(i, false) },
    /** The Chat choice in "What it starts with" (null: none, as before the Companion). */
    chat: dev.nitrate.android.playback.DetailsChat? = null,
) {
    // Playing from here hands the page's chat pick (Off unless chosen) to that one playback.
    @Suppress("NAME_SHADOWING") val onPlay: (MediaItem, Boolean) -> Unit = { i, fromStart -> dev.nitrate.android.playback.ChatChoices.arm(i.id); onPlay(i, fromStart) }
    @Suppress("NAME_SHADOWING") val onPlayFrom: (MediaItem, Long) -> Unit = { i, at -> dev.nitrate.android.playback.ChatChoices.arm(i.id); onPlayFrom(i, at) }
    val dataCache = dev.nitrate.tv.ui.lists.rememberDataCache()
    // "More like this" off: the server isn't asked for similar titles at all.
    val similarOn = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.similar
    val vm = viewModel(key = "details-$itemId") {
        DetailsViewModel(repository, itemId, seasonId, dataCache, wantsSimilar = { similarOn })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs = LocalDisplayPrefs.current

    // Saved with the screen, so coming back (from the player) counts as coming back.
    var firstResume by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) vm.refresh()
        firstResume = false
        onPauseOrDispose { }
    }

    val item = state.item
    if (item == null) {
        // On its way: the page in its own shape; a failure says so.
        if (state.error != null) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(state.error!!, color = MaterialTheme.colorScheme.error) }
        else dev.nitrate.tv.ui.components.DetailsPlaceholder()
        return
    }

    var focusedEpisode by remember { mutableStateOf<MediaItem?>(null) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.id, state.tracks.isEmpty()) { runCatching { playFocus.requestFocus() } }

    // Watch partners on this show: spoiler hiding goes by whoever is furthest behind.
    val showId = if (item.kind == ItemKind.SERIES) item.id else item.seriesId
    val partnerProgress by androidx.compose.runtime.produceState(emptyList<dev.nitrate.core.partners.PartnerProgress>(), showId, watchPartners, state.episodes) {
        value = if (watchPartners && showId != null) runCatching { repository.partnerProgress(showId) }.getOrDefault(emptyList()) else emptyList()
    }

    androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.partners.LocalPartnerProgress provides partnerProgress) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(repository.backdropUrl(item), Modifier.fillMaxSize())

        LazyColumn(
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            item(key = "header") {
                Header(item, repository, state, vm, playFocus, onPlay, onManage, canCollect, onPlayQueue, onOpen, onBrowse, checkPlayback, applyPlaybackFix, trackSetup, pickTrack, onPlayFrom, chat = chat,
                    season = if (seasonId != null && item.kind == ItemKind.SERIES) state.seasons.getOrNull(state.selectedSeason) else null)
            }

            // A show's seasons and episodes; on an episode's page, the rest of its season (and the show's other seasons).
            if ((item.kind == ItemKind.SERIES || item.kind == ItemKind.EPISODE) && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonSelector(state.seasons, state.selectedSeason, vm::selectSeason)
                }
                item(key = "episodes") {
                    Column {
                        // Coming down into the row lands on this episode (then the one last focused there).
                        val here = remember { FocusRequester() }
                        val start = state.episodes.indexOfFirst { it.id == item.id }
                        val safeEpisodes = dev.nitrate.tv.ui.partners.spoilerSafe(state.episodes)
                        LazyRow(
                            state = androidx.compose.foundation.lazy.rememberLazyListState((start - 1).coerceAtLeast(0)),
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = if (start >= 0) Modifier.focusRestorer(here) else Modifier,
                        ) {
                            // Spoiler-safe: an episode not seen yet is "Episode 5", blurred, with no description.
                            items(safeEpisodes, key = { it.id }) { episode ->
                                val thisOne = episode.id == item.id
                                // Entered from a watch group: its own mark on each episode it has (toggled on the episode's page).
                                val groupMark = dev.nitrate.tv.ui.partners.groupMarkFor(episode)?.takeIf { dev.nitrate.core.partners.GroupMarks.watched(it, episode) }
                                    ?.let { "✓ with " + dev.nitrate.core.partners.GroupMarks.caption(it) }
                                MediaCard(
                                    item = episode,
                                    imageUrl = repository.imageUrl(episode, ImageKind.PRIMARY, 300),
                                    width = 280.dp,
                                    aspectRatio = 16f / 9f,
                                    title = if (episode.spoiler) episode.name else "${episode.indexNumber ?: "–"}. ${episode.name.orEmpty()}",
                                    subtitle = listOfNotNull(
                                        if (thisOne) "This one" else dev.nitrate.core.model.AirDates.status(episode, System.currentTimeMillis()) ?: episode.runtimeMs?.let(::formatRuntime),
                                        groupMark,
                                    ).joinToString(" · ").ifEmpty { null },
                                    onFocus = { focusedEpisode = episode },
                                    onClick = { if (thisOne) { if (episode.isPlayable) onPlay(episode, false) } else onOpen(episode) },
                                    onPlay = { if (episode.isPlayable) onPlay(episode, false) },
                                    modifier = if (thisOne) Modifier.focusRequester(here) else Modifier,
                                )
                            }
                        }
                        // Overview of the focused episode, so the row stays compact.
                        val ep = focusedEpisode?.takeIf { f -> state.episodes.any { it.id == f.id } }
                        val prefs = LocalDisplayPrefs.current
                        Text(
                            ep?.overview?.takeIf { prefs.overview && !dev.nitrate.tv.ui.partners.descriptionHidden(ep) }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = PagePadding).padding(top = 12.dp).fillMaxWidth(0.6f).height(44.dp),
                        )
                    }
                }
            }

            // A film's collection (its saga), in release order, this one marked.
            if (state.collection.isNotEmpty()) {
                item(key = "collection") {
                    Column {
                        RowTitle("The collection")
                        val here = remember { FocusRequester() }
                        val start = state.collection.indexOfFirst { it.id == item.id }
                        LazyRow(
                            state = androidx.compose.foundation.lazy.rememberLazyListState((start - 1).coerceAtLeast(0)),
                            contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = if (start >= 0) Modifier.focusRestorer(here) else Modifier,
                        ) {
                            items(state.collection, key = { it.id }) { film ->
                                val thisOne = film.id == item.id
                                MediaCard(
                                    item = film,
                                    imageUrl = repository.imageUrl(film, ImageKind.PRIMARY, 360),
                                    width = 140.dp,
                                    aspectRatio = 2f / 3f,
                                    title = film.name,
                                    subtitle = if (thisOne) "This one" else film.productionYear?.toString(),
                                    onClick = { if (!thisOne) onOpen(film) },
                                    modifier = if (thisOne) Modifier.focusRequester(here) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
            val people = item.people.filter { it.imageTag != null || it.role != null }.take(20).takeIf { prefs.cast }.orEmpty()
            if (people.isNotEmpty()) {
                item(key = "cast") {
                    Column {
                        RowTitle("Cast & crew")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(people, key = { i, it -> "$i:${it.id}" }) { _, person ->
                                PersonCard(person, repository.personImageUrl(person))
                            }
                        }
                    }
                }
            }

            if (state.tracks.isNotEmpty()) {
                items(state.tracks.size, key = { "track-" + state.tracks[it].id }) { i ->
                    val t = state.tracks[i]
                    ListItem(
                        selected = false,
                        onClick = { onPlayQueue(state.tracks.drop(i)) },
                        modifier = Modifier.padding(horizontal = PagePadding),
                        leadingContent = { Text("${t.indexNumber ?: i + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        headlineContent = { Text(t.name) },
                        supportingContent = t.artists.joinToString().takeIf { it.isNotBlank() && it != item.albumArtist }?.let { by -> { Text(by) } },
                        trailingContent = t.runtimeMs?.takeIf { prefs.runtime }?.let { ms -> { Text(dev.nitrate.core.model.formatClock(ms)) } },
                    )
                }
            }
            if (state.albums.isNotEmpty()) {
                item(key = "albums") {
                    Column {
                        RowTitle("Albums")
                        LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            items(state.albums, key = { it.id }) { album ->
                                MediaCard(item = album, imageUrl = repository.imageUrl(album, ImageKind.PRIMARY, 360), width = 180.dp, aspectRatio = 1f, title = album.name, subtitle = album.productionYear?.toString(), onClick = { onOpen(album) })
                            }
                        }
                    }
                }
            }
            if (state.issues.isNotEmpty()) {
                item(key = "issues") {
                    Column {
                        RowTitle("In this series")
                        val start = state.issues.indexOfFirst { it.id == state.item?.id }.coerceAtLeast(0)
                        LazyRow(
                            state = androidx.compose.foundation.lazy.rememberLazyListState((start - 1).coerceAtLeast(0)),
                            contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            items(state.issues, key = { it.id }) { book ->
                                MediaCard(
                                    item = book,
                                    imageUrl = repository.imageUrl(book, ImageKind.PRIMARY, 360),
                                    width = 140.dp,
                                    aspectRatio = 2f / 3f,
                                    title = dev.nitrate.core.comics.ComicSeries.label(book),
                                    subtitle = when { book.id == state.item?.id -> "This one"; book.user.played -> "Read"; dev.nitrate.core.comics.ComicSeries.inProgress(book) -> "Reading"; else -> null },
                                    onClick = { if (book.id != state.item?.id) onOpen(book) },
                                )
                            }
                        }
                    }
                }
            }
            if (state.contents.isNotEmpty()) {
                item(key = "contents") {
                    var removing by remember { mutableStateOf<MediaItem?>(null) }
                    Column {
                        RowTitle(if (canCollect) "In this collection (hold OK on one to remove it)" else "In this collection")
                        LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            items(state.contents, key = { it.id }) { member ->
                                MediaCard(
                                    item = member,
                                    imageUrl = repository.imageUrl(member, ImageKind.PRIMARY, 360),
                                    width = 140.dp,
                                    aspectRatio = 2f / 3f,
                                    onClick = { onOpen(member) },
                                    onLongClick = if (canCollect) ({ removing = member }) else null,
                                )
                            }
                        }
                    }
                    removing?.let { member ->
                        val keep = remember { androidx.compose.ui.focus.FocusRequester() }
                        dev.nitrate.tv.ui.lists.TvDialog({ removing = null }, width = 520) {
                            Text("Remove “${member.name}” from this collection?", style = MaterialTheme.typography.headlineSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { removing = null }, modifier = Modifier.focusRequester(keep)) { Text("Keep it") }
                                Button(onClick = { vm.removeFromCollection(member); removing = null }) { Text("Remove") }
                            }
                            LaunchedEffect(Unit) { runCatching { keep.requestFocus() } }
                        }
                    }
                }
            }
            if (state.extras.isNotEmpty()) {
                item(key = "extras") {
                    Column {
                        RowTitle("Extras")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            items(state.extras, key = { it.id }) { x ->
                                MediaCard(
                                    item = x,
                                    imageUrl = repository.imageUrl(x, ImageKind.THUMB, 360) ?: repository.backdropUrl(x, 640) ?: repository.imageUrl(x, ImageKind.PRIMARY, 360),
                                    width = 260.dp,
                                    aspectRatio = 16f / 9f,
                                    title = x.name,
                                    subtitle = listOfNotNull(x.extraType, x.runtimeMs?.let { dev.nitrate.core.model.formatRuntime(it) }).joinToString(" · "),
                                    onClick = { onPlay(x, true) },
                                )
                            }
                        }
                    }
                }
            }
            if (state.similar.isNotEmpty() && prefs.similar) {
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
                                    imageUrl = repository.imageUrl(similar, ImageKind.PRIMARY, 360),
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
}

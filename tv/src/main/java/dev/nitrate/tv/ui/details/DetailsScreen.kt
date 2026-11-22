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
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.ImageKind

private val PagePadding = 58.dp

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
) {
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
                Header(item, repository, state, vm, playFocus, onPlay, onManage, canCollect, onPlayQueue, onOpen, onBrowse, checkPlayback, applyPlaybackFix, trackSetup, pickTrack, onPlayFrom)
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
                        LazyRow(
                            state = androidx.compose.foundation.lazy.rememberLazyListState((start - 1).coerceAtLeast(0)),
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = if (start >= 0) Modifier.focusRestorer(here) else Modifier,
                        ) {
                            items(state.episodes, key = { it.id }) { episode ->
                                val thisOne = episode.id == item.id
                                MediaCard(
                                    item = episode,
                                    imageUrl = repository.imageUrl(episode, ImageKind.PRIMARY, 300),
                                    width = 280.dp,
                                    aspectRatio = 16f / 9f,
                                    title = "${episode.indexNumber ?: "–"}. ${episode.name.orEmpty()}",
                                    subtitle = if (thisOne) "This one" else dev.nitrate.core.model.AirDates.status(episode, System.currentTimeMillis()) ?: episode.runtimeMs?.let(::formatRuntime),
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

@Composable
private fun Header(
    item: MediaItem,
    repository: MediaServer,
    state: DetailsState,
    vm: DetailsViewModel,
    playFocus: FocusRequester,
    onPlay: (MediaItem, Boolean) -> Unit,
    onManage: ((String) -> Unit)?,
    canCollect: Boolean,
    onPlayQueue: (List<MediaItem>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onBrowse: (String, String?, Int?) -> Unit,
    checkPlayback: (suspend (MediaItem) -> dev.nitrate.core.playback.PlaybackDiagnosis)?,
    applyPlaybackFix: suspend (MediaItem, dev.nitrate.core.playback.PlaybackFix) -> Unit,
    trackSetup: (suspend (MediaItem) -> dev.nitrate.core.playback.TrackSetup)? = null,
    pickTrack: suspend (MediaItem, dev.nitrate.core.playback.TrackSetup, dev.nitrate.core.model.StreamKind, Int) -> dev.nitrate.core.playback.TrackSetup = { _, s, _, _ -> s },
    onPlayFrom: (MediaItem, Long) -> Unit = { i, _ -> onPlay(i, false) },
) {
    // Music pages have more buttons in a row.
    val wideHeader = item.kind == ItemKind.MUSIC_ALBUM || item.kind == ItemKind.MUSIC_ARTIST
    Column(
        Modifier.padding(start = PagePadding, end = PagePadding, top = 48.dp).fillMaxWidth(if (wideHeader) 0.95f else 0.6f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(60.dp))
        if (item.kind == ItemKind.EPISODE) {
            WhereItBelongs(item, state, onOpen)
            Text(
                listOfNotNull(item.indexNumber?.let { "$it." }, item.name).joinToString(" "),
                style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        } else {
            TitleOrLogo(item, repository.logoUrl(item))
            item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        }
        val music = item.kind == ItemKind.MUSIC_ALBUM || item.kind == ItemKind.MUSIC_ARTIST
        if (item.kind == ItemKind.MUSIC_ALBUM) item.albumArtist?.let { Text("by $it", style = MaterialTheme.typography.titleLarge) }
        ItemMetaLine(item)
        GenreLine(item)
        val prefs = LocalDisplayPrefs.current
        item.tagline?.takeIf { prefs.tagline }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        }
        item.overview?.takeIf { prefs.overview }?.let { overview ->
            // Spoiler hiding: unwatched movies and episodes keep their description until asked for.
            var revealed by remember(item.id) { mutableStateOf(false) }
            if (!dev.nitrate.tv.ui.partners.descriptionHidden(item) || revealed) Text(overview, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            else OutlinedButton(onClick = { revealed = true }) { Text("Plot hidden · Show") }
        }
        Spacer(Modifier.height(4.dp))
        if (music) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val scope = rememberCoroutineScope()
            val tracks = state.tracks
            if (tracks.isNotEmpty()) {
                Button(onClick = { onPlayQueue(tracks) }, modifier = Modifier.focusRequester(playFocus)) { Text("Play") }
                OutlinedButton(onClick = { onPlayQueue(tracks.shuffled()) }) { Text("Shuffle") }
            }
            OutlinedButton(
                onClick = { scope.launch { vm.instantMix().takeIf { it.isNotEmpty() }?.let(onPlayQueue) } },
                modifier = if (tracks.isEmpty()) Modifier.focusRequester(playFocus) else Modifier,
            ) { Text("Instant mix") }
            item.albumArtistId?.takeIf { item.kind == ItemKind.MUSIC_ALBUM }?.let { artistId ->
                OutlinedButton(onClick = { onOpen(MediaItem(artistId, item.albumArtist.orEmpty(), ItemKind.MUSIC_ARTIST)) }) { Text("Artist") }
            }
            OutlinedButton(onClick = vm::toggleFavorite) { Text(if (item.user.isFavorite) "Favorite ♥" else "Favorite ♡") }
            if (item.kind == ItemKind.MUSIC_ALBUM) {
                var adding by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { adding = true }) { Text("Add to…") }
                if (adding) dev.nitrate.tv.ui.lists.AddToListDialog(repository, item, canCollect) { adding = false }
            }
        } else {
            // What "Play" means depends on the type: resume a movie, or the next episode of a series.
            val playTarget: MediaItem? = if (item.kind == ItemKind.SERIES) state.nextUp ?: state.episodes.firstOrNull() else item
            val resumeMs = playTarget?.user?.positionMs ?: 0L
            val playLabel = when {
                // A comic: Read (or where it was left), opened in the reader.
                item.kind == ItemKind.BOOK -> dev.nitrate.core.comics.ComicProgress.readLabel(item)
                playTarget == null -> "Play"
                item.kind == ItemKind.SERIES && resumeMs > 0 -> "Resume S${playTarget.seasonNumber}:E${playTarget.indexNumber}"
                item.kind == ItemKind.SERIES -> "Play S${playTarget.seasonNumber}:E${playTarget.indexNumber}"
                resumeMs > 0 -> "Resume from ${formatClock(resumeMs)}"
                else -> "Play"
            }
            val trailer = state.extras.firstOrNull { it.extraType == "Trailer" }
            val context = androidx.compose.ui.platform.LocalContext.current
            var moreOpen by remember { mutableStateOf(false) }
            var hint by remember { mutableStateOf<String?>(null) }
            // One short row: play, start over, the trailer, then watched, favourite and everything else under More.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { playTarget?.let { onPlay(it, false) } },
                    enabled = playTarget != null && (item.kind == ItemKind.SERIES || item.kind == ItemKind.BOOK || item.isPlayable),
                    modifier = Modifier.focusRequester(playFocus),
                ) { Text(playLabel) }
                if (resumeMs > 0 && playTarget != null) OutlinedButton(onClick = { onPlay(playTarget, true) }) { Text("From start") }
                if (trailer != null || item.remoteTrailers.isNotEmpty()) OutlinedButton(onClick = {
                    if (trailer != null) onPlay(trailer, true)
                    else runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.remoteTrailers.first().url))) }
                }) { Text("Trailer") }
                RoundAction(
                    Icons.Filled.Check,
                    if (item.user.played) "Watched" else "Mark watched",
                    active = item.user.played, onHint = { hint = it }, onClick = vm::togglePlayed,
                )
                RoundAction(
                    if (item.user.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (item.user.isFavorite) "Favorite" else "Add to favorites",
                    active = item.user.isFavorite, onHint = { hint = it }, onClick = vm::toggleFavorite,
                )
                RoundAction(Icons.Filled.MoreVert, "More", active = false, onHint = { hint = it }, onClick = { moreOpen = true })
            }
            // The focused round button's name, in room kept for it so nothing moves.
            Text(hint.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(18.dp))
            // What Play starts with: sound, subtitles and which version, in one quiet line.
            StartsWithLine(item, playTarget, trackSetup, pickTrack)
            if (moreOpen) MorePanel(item, repository, playTarget, canCollect, onManage, onBrowse, checkPlayback, applyPlaybackFix, onPlayFrom) { moreOpen = false }
        }
        val partners = dev.nitrate.tv.ui.partners.LocalPartnerProgress.current
        partners.groupBy { it.groupId }.values.forEach { group ->
            Text(
                "Watching with " + group.joinToString(", ") { "${it.userName} (${it.last ?: "not started"})" },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A round icon button for the action row; its name shows under the row while focused (and is read out). */
@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onHint: (String?) -> Unit, onClick: () -> Unit) {
    androidx.tv.material3.OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused) onHint(label) else onHint(null) },
    ) {
        androidx.tv.material3.Icon(icon, contentDescription = label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** A quiet link: text that's only a button once focused. */
@Composable
private fun Crumb(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.surface,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) { Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
}

/** "The Night Reel  ›  Season 1": an episode's show and season, each opening its page. */
@Composable
private fun WhereItBelongs(item: MediaItem, state: DetailsState, onOpen: (MediaItem) -> Unit) {
    val seriesId = item.seriesId ?: return
    val season = state.seasons.firstOrNull { it.id == item.seasonId }
    // Lined up with the text below: the links' own padding sits outside the column's edge.
    Row(Modifier.offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
        Crumb(item.seriesName ?: "The show") { onOpen(MediaItem(seriesId, item.seriesName.orEmpty(), ItemKind.SERIES)) }
        val seasonName = season?.name ?: item.seasonNumber?.let { "Season $it" }
        if (seasonName != null) {
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Crumb(seasonName) { onOpen(MediaItem(item.seasonId ?: seriesId, seasonName, ItemKind.SEASON, seriesId = seriesId)) }
        }
    }
}

/**
 * "Audio: English · 5.1  ·  Subtitles: Off  ·  Version: 4K": what Play starts with, as one quiet line.
 * OK opens the choices; a track picked is remembered for the show (or title), as in the player.
 */
@Composable
private fun StartsWithLine(
    item: MediaItem,
    playTarget: MediaItem?,
    trackSetup: (suspend (MediaItem) -> dev.nitrate.core.playback.TrackSetup)?,
    pickTrack: suspend (MediaItem, dev.nitrate.core.playback.TrackSetup, dev.nitrate.core.model.StreamKind, Int) -> dev.nitrate.core.playback.TrackSetup,
) {
    var setup by remember(playTarget?.id) { mutableStateOf<dev.nitrate.core.playback.TrackSetup?>(null) }
    LaunchedEffect(playTarget?.id) {
        setup = playTarget?.takeIf { it.isPlayable && trackSetup != null }?.let { runCatching { trackSetup!!(it) }.getOrNull() }
    }
    var version by remember(item.id) { mutableStateOf(dev.nitrate.android.playback.VersionChoices[item.id]) }
    val versions = item.versions.size > 1
    val s = setup?.takeIf { it.hasChoices }
    if (s == null && !versions) return
    val versionName = item.versions.firstOrNull { it.id == version }?.name ?: "Auto"
    val parts = listOfNotNull(s?.let { "Audio: ${it.audioName}" }, s?.let { "Subtitles: ${it.subtitleName}" }, if (versions) "Version: $versionName" else null)
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Crumb(parts.joinToString("   ·   "), Modifier.offset(x = (-10).dp)) { open = true }
    if (open) {
        val first = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog({ open = false }) {
            Text("What it starts with", style = MaterialTheme.typography.headlineSmall)
            if (s != null) {
                ListItem(selected = false, onClick = { open = false; picking = "audio" }, headlineContent = { Text("Audio") }, supportingContent = { Text(s.audioName) }, modifier = Modifier.focusRequester(first))
                ListItem(selected = false, onClick = { open = false; picking = "subtitles" }, headlineContent = { Text("Subtitles") }, supportingContent = { Text(s.subtitleName) })
            }
            if (versions) ListItem(selected = false, onClick = { open = false; picking = "version" }, headlineContent = { Text("Version") }, supportingContent = { Text(versionName) },
                modifier = if (s == null) Modifier.focusRequester(first) else Modifier)
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    when (picking) {
        "audio", "subtitles" -> if (s != null && playTarget != null) {
            val audio = picking == "audio"
            val kind = if (audio) dev.nitrate.core.model.StreamKind.AUDIO else dev.nitrate.core.model.StreamKind.SUBTITLE
            dev.nitrate.tv.ui.player.TrackDialog(
                if (audio) "Audio" else "Subtitles",
                if (audio) s.source.audioChoices() else s.source.subtitleChoices(),
                if (audio) s.audioIndex else s.subtitleIndex,
                onSelect = { index -> picking = null; scope.launch { setup = pickTrack(playTarget, s, kind, index) } },
                onDismiss = { picking = null },
            )
        }
        "version" -> {
            val first = remember { FocusRequester() }
            dev.nitrate.tv.ui.lists.TvDialog({ picking = null }) {
                Text("Which version plays", style = MaterialTheme.typography.headlineSmall)
                val choices = listOf<Pair<String?, String>>(null to "Auto — the best for this TV") + item.versions.map { it.id to it.name }
                choices.forEachIndexed { i, (id, name) ->
                    ListItem(
                        selected = id == version,
                        onClick = { version = id; dev.nitrate.android.playback.VersionChoices.set(item.id, id); picking = null },
                        headlineContent = { Text(name) },
                        modifier = if (id == version || (i == 0 && version == null)) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

/** Everything the action row leaves out: how it plays, moments, chat, lists, more like it, managing. */
@Composable
private fun MorePanel(
    item: MediaItem,
    repository: MediaServer,
    playTarget: MediaItem?,
    canCollect: Boolean,
    onManage: ((String) -> Unit)?,
    onBrowse: (String, String?, Int?) -> Unit,
    checkPlayback: (suspend (MediaItem) -> dev.nitrate.core.playback.PlaybackDiagnosis)?,
    applyPlaybackFix: suspend (MediaItem, dev.nitrate.core.playback.PlaybackFix) -> Unit,
    onPlayFrom: (MediaItem, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var open by remember { mutableStateOf<String?>(null) }
    val features = rememberCompanionFeatures(repository)
    val video = item.isPlayable && item.mediaKind != MediaKind.AUDIO
    val studio = item.studios.firstOrNull()
    val year = item.productionYear
    if (open == null) {
        val first = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog(onDismiss) {
            Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val entries = buildList<Triple<String, String?, () -> Unit>> {
                if (item.isPlayable) add(Triple("How it plays", "Whether it plays as it is here, and why", { open = "plays" }))
                if (video && "moments" in features) add(Triple("Moments", "Marked places, yours and your groups'", { open = "moments" }))
                if (video && "chathistory" in features) add(Triple("Earlier chat", "What was said watching it together", { open = "chat" }))
                if (item.isPlayable || item.kind == ItemKind.SERIES) add(Triple("Add to a playlist or collection", null, { open = "add" }))
                studio?.let { add(Triple("More from $it", null, { onDismiss(); onBrowse(it, it, null) })) }
                year?.let { add(Triple("More from $it", null, { onDismiss(); onBrowse("From $it", null, it) })) }
                if (onManage != null) add(Triple("Manage", "Details, images, identify", { onDismiss(); onManage(item.id) }))
                if (onManage != null && item.kind == ItemKind.BOOK) add(Triple("Comic details", null, { open = "comic" }))
            }
            entries.forEachIndexed { i, (label, note, action) ->
                ListItem(
                    selected = false,
                    onClick = action,
                    headlineContent = { Text(label) },
                    supportingContent = note?.let { { Text(it) } },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    when (open) {
        "plays" -> if (checkPlayback != null) CheckPlaybackDialog(item, checkPlayback, applyPlaybackFix, onDismiss) else PlaybackDecisionDialog(repository, item, onDismiss)
        "moments" -> MomentsDialog(repository, item.id, { at -> onPlayFrom(item, at) }, onDismiss)
        "chat" -> EarlierChatDialog(repository, item.id, onDismiss)
        "add" -> dev.nitrate.tv.ui.lists.AddToListDialog(repository, item, canCollect, onDismiss)
        "comic" -> dev.nitrate.tv.ui.comics.ComicInfoDialog(repository, item, onDismiss)
    }
}

@Composable
private fun SeasonSelector(seasons: List<MediaItem>, selected: Int, onSelect: (Int) -> Unit) {
    val selectedChip = remember { FocusRequester() }
    var rowEntered by remember { mutableStateOf(false) }
    LazyRow(
        // Coming in from above lands on the season shown (then the last one focused).
        modifier = Modifier.focusRestorer(selectedChip).onFocusChanged { rowEntered = it.hasFocus },
        contentPadding = PaddingValues(horizontal = PagePadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.id }) { index, season ->
            // Moving along the chips switches season, so the D-pad alone browses them; arriving
            // from above lands on the season already shown, so it doesn't switch on the way in.
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                modifier = Modifier
                    .then(if (index == selected) Modifier.focusRequester(selectedChip) else Modifier)
                    .onFocusChanged { if (it.isFocused && rowEntered) onSelect(index) },
            ) {
                Text(season.name.ifBlank { "Season ${season.indexNumber}" })
            }
        }
    }
}

@Composable
private fun PersonCard(person: Person, imageUrl: String?) {
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


/** What the server would do if this item were played now, and why, without starting playback. */
@Composable
private fun PlaybackDecisionDialog(repository: MediaServer, item: MediaItem, onDismiss: () -> Unit) {
    val plan by produceState<Result<PlaybackPlan>?>(null, item.id) {
        value = runCatching { repository.resolvePlayback(item.id, item.mediaKind == MediaKind.AUDIO, startMs = 0) }
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

/** How the item would play on this TV, why, and fixes that can be made before playing. */
@Composable
private fun CheckPlaybackDialog(
    item: MediaItem,
    check: suspend (MediaItem) -> dev.nitrate.core.playback.PlaybackDiagnosis,
    apply: suspend (MediaItem, dev.nitrate.core.playback.PlaybackFix) -> Unit,
    onDismiss: () -> Unit,
) {
    // Asked again after each fix, so the dialog shows what the fix changed.
    var round by remember { mutableStateOf(0) }
    val result by produceState<Result<dev.nitrate.core.playback.PlaybackDiagnosis>?>(null, item.id, round) {
        value = null
        value = runCatching { check(item) }
    }
    val scope = rememberCoroutineScope()
    dev.nitrate.tv.ui.player.PlaybackFixDialog(
        diagnosis = result?.getOrNull(),
        error = result?.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
        onFix = { fix -> scope.launch { apply(item, fix); round++ } },
        onDismiss = onDismiss,
        canApply = { it is dev.nitrate.core.playback.PlaybackFix.Change || it is dev.nitrate.core.playback.PlaybackFix.UseVersion },
    )
}

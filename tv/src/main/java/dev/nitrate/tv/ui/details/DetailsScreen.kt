package dev.nitrate.tv.ui.details

import androidx.tv.material3.ListItem
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

            if (item.kind == ItemKind.SERIES && state.seasons.isNotEmpty()) {
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
                                    imageUrl = repository.imageUrl(episode, ImageKind.PRIMARY, 300),
                                    width = 280.dp,
                                    aspectRatio = 16f / 9f,
                                    title = "${episode.indexNumber ?: "–"}. ${episode.name.orEmpty()}",
                                    subtitle = dev.nitrate.core.model.AirDates.status(episode, System.currentTimeMillis()) ?: episode.runtimeMs?.let(::formatRuntime),
                                    onFocus = { focusedEpisode = episode },
                                    onClick = { onOpen(episode) },
                                    onPlay = { if (episode.isPlayable) onPlay(episode, false) },
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
        TitleOrLogo(item, repository.logoUrl(item))
        item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
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
            if (!dev.nitrate.tv.ui.partners.descriptionHidden(item) || revealed) Text(overview, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
        } else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Wraps onto another line, so every button (Playback info, Add to, Manage...) stays on screen.
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
            Button(
                onClick = { playTarget?.let { onPlay(it, false) } },
                enabled = playTarget != null && (item.kind == ItemKind.SERIES || item.kind == ItemKind.BOOK || item.isPlayable),
                modifier = Modifier.focusRequester(playFocus),
            ) { Text(playLabel) }
            if (resumeMs > 0 && playTarget != null) {
                OutlinedButton(onClick = { onPlay(playTarget, true) }) { Text("From start") }
            }
            OutlinedButton(onClick = vm::togglePlayed) {
                Text(if (item.user.played) "Watched ✓" else "Mark watched")
            }
            OutlinedButton(onClick = vm::toggleFavorite) {
                Text(if (item.user.isFavorite) "Favorite ♥" else "Favorite ♡")
            }
            if (item.versions.size > 1) VersionButton(item)
            if (trackSetup != null && playTarget != null && playTarget.isPlayable) TrackButtons(playTarget, trackSetup, pickTrack)
            val trailer = state.extras.firstOrNull { it.extraType == "Trailer" }
            if (trailer != null || item.remoteTrailers.isNotEmpty()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                OutlinedButton(onClick = {
                    if (trailer != null) onPlay(trailer, true)
                    else runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.remoteTrailers.first().url))) }
                }) { Text("Trailer") }
            }
            if (item.isPlayable) {
                var showInfo by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showInfo = true }) { Text("How it plays") }
                if (showInfo) {
                    if (checkPlayback != null) CheckPlaybackDialog(item, checkPlayback, applyPlaybackFix) { showInfo = false }
                    else PlaybackDecisionDialog(repository, item) { showInfo = false }
                }
            }
            if (item.isPlayable && item.mediaKind != dev.nitrate.core.model.MediaKind.AUDIO) MomentsAndChatButtons(repository, item.id) { at -> onPlayFrom(item, at) }
            if (item.isPlayable || item.kind == ItemKind.SERIES) {
                var adding by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { adding = true }) { Text("Add to…") }
                if (adding) dev.nitrate.tv.ui.lists.AddToListDialog(repository, item, canCollect) { adding = false }
            }
            if (onManage != null) OutlinedButton(onClick = { onManage(item.id) }) { Text("Manage") }
            if (onManage != null && item.kind == ItemKind.BOOK) {
                var editing by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { editing = true }) { Text("Comic details") }
                if (editing) dev.nitrate.tv.ui.comics.ComicInfoDialog(repository, item) { editing = false }
            }
        }
        val partners = dev.nitrate.tv.ui.partners.LocalPartnerProgress.current
        partners.groupBy { it.groupId }.values.forEach { group ->
            Text(
                "Watching with " + group.joinToString(", ") { "${it.userName} (${it.last ?: "not started"})" },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            )
        }
        // More like it, by studio or year.
        val studio = item.studios.firstOrNull()
        val year = item.productionYear
        if (!wideHeader && (studio != null || year != null)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            studio?.let { OutlinedButton(onClick = { onBrowse(it, it, null) }) { Text("More from $it") } }
            year?.let { OutlinedButton(onClick = { onBrowse("From $it", null, it) }) { Text("More from $it") } }
        }
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

/** "Version: Auto" — picks which copy of the title plays (4K, 1080p...), or the best for this TV. */
@Composable
private fun VersionButton(item: MediaItem) {
    var chosen by remember(item.id) { mutableStateOf(dev.nitrate.android.playback.VersionChoices[item.id]) }
    var picking by remember { mutableStateOf(false) }
    val label = item.versions.firstOrNull { it.id == chosen }?.name ?: "Auto"
    OutlinedButton(onClick = { picking = true }) { Text("Version: $label") }
    if (picking) {
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog({ picking = false }) {
            Text("Which version plays", style = MaterialTheme.typography.headlineSmall)
            val choices = listOf<Pair<String?, String>>(null to "Auto — the best for this TV") + item.versions.map { it.id to it.name }
            choices.forEachIndexed { i, (id, name) ->
                androidx.tv.material3.ListItem(
                    selected = id == chosen,
                    onClick = { chosen = id; dev.nitrate.android.playback.VersionChoices.set(item.id, id); picking = false },
                    headlineContent = { Text(name) },
                    modifier = if (id == chosen || (i == 0 && chosen == null)) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}

/**
 * "Audio: Japanese · 5.1" and "Subtitles: English": what Play starts with, each opening the
 * player's track list; a pick is remembered for the show (or title), as in the player.
 */
@Composable
private fun TrackButtons(
    item: MediaItem,
    trackSetup: suspend (MediaItem) -> dev.nitrate.core.playback.TrackSetup,
    pickTrack: suspend (MediaItem, dev.nitrate.core.playback.TrackSetup, dev.nitrate.core.model.StreamKind, Int) -> dev.nitrate.core.playback.TrackSetup,
) {
    var setup by remember(item.id) { mutableStateOf<dev.nitrate.core.playback.TrackSetup?>(null) }
    androidx.compose.runtime.LaunchedEffect(item.id) { setup = runCatching { trackSetup(item) }.getOrNull() }
    val s = setup?.takeIf { it.hasChoices } ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var picking by remember { mutableStateOf<dev.nitrate.core.model.StreamKind?>(null) }
    OutlinedButton(onClick = { picking = dev.nitrate.core.model.StreamKind.AUDIO }) { Text("Audio: ${s.audioName}") }
    OutlinedButton(onClick = { picking = dev.nitrate.core.model.StreamKind.SUBTITLE }) { Text("Subtitles: ${s.subtitleName}") }
    picking?.let { kind ->
        val audio = kind == dev.nitrate.core.model.StreamKind.AUDIO
        dev.nitrate.tv.ui.player.TrackDialog(
            if (audio) "Audio" else "Subtitles",
            if (audio) s.source.audioChoices() else s.source.subtitleChoices(),
            if (audio) s.audioIndex else s.subtitleIndex,
            onSelect = { index ->
                picking = null
                scope.launch { setup = pickTrack(item, s, kind, index) }
            },
            onDismiss = { picking = null },
        )
    }
}

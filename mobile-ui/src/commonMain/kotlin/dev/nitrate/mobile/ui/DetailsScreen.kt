package dev.nitrate.mobile.ui

import androidx.compose.foundation.lazy.itemsIndexed
import kotlinx.coroutines.launch
import dev.nitrate.core.pages.facts
import dev.nitrate.core.pages.hidesDescription
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.produceState
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
    // "More like this" off: the server isn't asked for similar titles at all.
    val similarOn = LocalDisplayPrefs.current.similar
    val vm = viewModel(key = "details-$itemId") { DetailsViewModel(actions.server, itemId, seasonId, actions.container.dataCache, wantsSimilar = { similarOn }) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Entered from a watch group: its own marks beside the user's (GroupMarks), one question at most.
    val marker = rememberGroupMarker(actions)
    GroupMarkQuestion(marker)
    // Saved with the screen, so coming back (from the player) counts as coming back.
    var first by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!first) vm.refresh()
        first = false
        onPauseOrDispose { }
    }

    val item = state.item
    if (item == null) {
        if (state.error != null) ErrorMessage(state.error!!, vm::load) else DetailsPlaceholder()
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
    // Watch partners on this show: spoiler hiding goes by whoever is furthest behind.
    val showId = if (item.kind == ItemKind.SERIES) item.id else item.seriesId
    val partnerProgress = rememberPartnerProgress(actions, showId)
    // A season's page (a show's page opened at one of its seasons): named and played as that season,
    // and following the season chips as they change.
    val season = if (item.kind == ItemKind.SERIES && seasonId != null) state.seasons.getOrNull(state.selectedSeason) else null
    val seasonEpisodes = state.episodes.filter { season == null || it.seasonId == null || it.seasonId == season.id }
    val playTarget = if (season != null) seasonEpisodes.firstOrNull { !it.user.played && !it.isMissing } ?: seasonEpisodes.firstOrNull()
        else state.nextUp ?: state.episodes.firstOrNull()

    androidx.compose.runtime.CompositionLocalProvider(LocalPartnerProgress provides partnerProgress) {
    TintedBackdrop(tint, strength = 0.32f) {
        // The page's parts: the picture and title, the facts and actions, and the rows below. A wide
        // window (a tablet, a phone on its side at 840 dp and up) puts the first two in a column on the
        // left that stays put, with the rows scrolling beside it; narrower, they're one list.
        val hero: @Composable (wide: Boolean) -> Unit = { wide ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val heroHeight = if (wide) (maxWidth * 0.62f).coerceIn(240.dp, 420.dp) else (maxWidth * 1.05f).coerceIn(340.dp, 560.dp)
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        AsyncImage(
                            backdrop,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().sharedArtwork(artKey).fadeBottom(0.55f).graphicsLayer {
                                if (wide) return@graphicsLayer
                                // Parallax: the picture scrolls at half speed and fades as it leaves.
                                val scrolled = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else size.height
                                translationY = scrolled * 0.5f
                                alpha = 1f - (scrolled / size.height).coerceIn(0f, 1f) * 0.6f
                            },
                        )
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x88000000), 0.25f to Color.Transparent, 1f to Color.Transparent)))
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val logo = server.logoUrl(item)?.takeIf { prefs.logos && item.kind != ItemKind.EPISODE }
                            if (season != null) {
                                ShowCrumb(actions, item)
                                Text(season.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            } else if (item.kind == ItemKind.EPISODE) {
                                WhereItBelongs(actions, item, state.seasons.firstOrNull { it.id == item.seasonId }?.name)
                                Text(
                                    listOfNotNull(item.indexNumber?.let { "$it." }, item.name).joinToString(" "),
                                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                )
                            } else if (logo != null) {
                                AsyncImage(logo, item.displayTitle, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                                    modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 100.dp))
                            } else {
                                Text(item.displayTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (item.kind != ItemKind.EPISODE) item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White) }
                        }
                    }
                }
            }
        val facts: @Composable () -> Unit = {
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // A season: how many episodes and how many watched, in place of the show's facts.
                    val chips = if (season != null && seasonEpisodes.isNotEmpty()) {
                        val shown = seasonEpisodes.filter { !it.isMissing }
                        listOfNotNull("${shown.size} ${if (shown.size == 1) "episode" else "episodes"}", shown.count { it.user.played }.takeIf { it > 0 }?.let { "$it watched" })
                    } else prefs.facts(item)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { GlassChip(it) } }
                    if (item.genres.isNotEmpty() && prefs.genres) Text(item.genres.take(4).joinToString("  •  "), color = accentColor(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    val music = item.kind == ItemKind.MUSIC_ALBUM || item.kind == ItemKind.MUSIC_ARTIST
                    if (item.kind == ItemKind.MUSIC_ALBUM) item.albumArtist?.let { artist ->
                        val artistId = item.albumArtistId
                        Text(
                            "by $artist",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = if (artistId != null) Modifier.pressable({ actions.nav.navigate(DetailsRoute(artistId)) }) else Modifier,
                        )
                    }
                    // Entered from a watch group: only that group's line, saying what the page does for it.
                    val enteredGroup = actions.groupOf(item)
                    partnerProgress.filter { enteredGroup == null || it.groupId == enteredGroup }.groupBy { it.groupId }.values.forEach { group ->
                        Text(
                            "Watching with " + group.joinToString(", ") { "${it.userName} (${it.last ?: "not started"})" } +
                                if (enteredGroup != null) ". Plays here count for the group; the people button marks it for them." else "",
                            style = MaterialTheme.typography.labelLarge, color = accentColor(),
                        )
                    }
                    if (!music) PlaySection(actions, item, playTarget)
                    if (item.versions.size > 1) VersionChooser(item)
                    // The tracks Play starts with, and a way to change them (a show's page: its next episode).
                    if (!music) (if (item.isPlayable) item else playTarget)?.let { TrackLine(actions, it) }
                    // One short row: watched, favourite, the trailer, downloading, playing elsewhere; the rest under More.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!music) RoundAction(Icons.Default.Check, "Watched", onClick = vm::togglePlayed, active = item.user.played)
                        // The group's own mark (entered from a group): separate from Watched, which is the user's alone.
                        if (!music) GroupMarkAction(actions, item, marker)
                        RoundAction(if (item.user.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite", onClick = vm::toggleFavorite, active = item.user.isFavorite)
                        val trailer = state.extras.firstOrNull { it.extraType == "Trailer" }
                        val uri = androidx.compose.ui.platform.LocalUriHandler.current
                        if (trailer != null || item.remoteTrailers.isNotEmpty()) RoundAction(Icons.Default.PlayArrow, "Trailer", onClick = {
                            if (trailer != null) actions.play(trailer, true) else runCatching { uri.openUri(item.remoteTrailers.first().url) }
                        })
                        if (item.isPlayable) DownloadAction(actions, item)
                        if (item.kind == ItemKind.SERIES) SmartDownloadAction(actions, item)
                        // Send it to the TV (or another of your devices) and control it from here.
                        var playingOn by remember { mutableStateOf(false) }
                        val sendable = if (item.kind == ItemKind.SERIES) playTarget else item.takeIf { it.isPlayable }
                        if (sendable != null) RoundAction(NitrateIcons.Tv, "Play on…", onClick = { playingOn = true })
                        if (playingOn && sendable != null) PlayOnDialog(actions, sendable, sendable.user.positionMs, onDismiss = { playingOn = false })
                        var more by remember { mutableStateOf(false) }
                        RoundAction(Icons.Default.MoreVert, "More", onClick = { more = true })
                        // Manage: admins, and metadata editors for titles in their libraries (the Companion says).
                        val canManage by produceState(account?.isAdmin == true, item.id, account) { value = actions.container.canManageMetadata(item.id) }
                        if (more) DetailsMoreSheet(actions, item, canManage = canManage, music = music) { more = false }
                    }
                    // A change the server refused, and why (the marks stay as the server has them).
                    state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    item.tagline?.takeIf { prefs.tagline }?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White) }
                    // A season's own description when it has one, else the show's.
                    (season?.overview ?: item.overview)?.takeIf { prefs.overview }?.let { overview ->
                        // Spoiler hiding: unwatched movies and episodes keep their description until asked for.
                        var revealed by remember(item.id) { mutableStateOf(false) }
                        if (!descriptionHidden(item) || revealed) ExpandableText(overview)
                        else Text(
                            "Plot hidden · Show",
                            style = MaterialTheme.typography.bodyMedium, color = accentColor(),
                            modifier = Modifier.pressable({ revealed = true }).padding(vertical = 6.dp),
                        )
                    }
                }
            }
        fun androidx.compose.foundation.lazy.LazyListScope.rows() {
            // A show's seasons and episodes; on an episode's page, the rest of its season (and the show's other seasons).
            if ((item.kind == ItemKind.SERIES || item.kind == ItemKind.EPISODE) && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(Modifier.padding(top = 12.dp)) {
                        SectionHeader(if (item.kind == ItemKind.EPISODE) "In this season" else "Episodes")
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
                items(state.episodes, key = { "ep-${it.id}" }) { episode -> EpisodeCard(actions, episode, episode.id in downloaded, thisOne = episode.id == item.id, marker = marker) }
            }
            // A film's collection (its saga), in release order, this one marked.
            if (state.collection.isNotEmpty()) {
                item(key = "collection") {
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("The collection")
                        val start = state.collection.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        LazyRow(
                            state = rememberLazyListState((start - 1).coerceAtLeast(0)),
                            contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.collection, key = { it.id }) { f ->
                                val key = "collection-${item.id}-${f.id}"
                                MediaCard(
                                    f, server.cardImage(f, CardShape.POSTER), CardShape.POSTER, onClick = { if (f.id != item.id) actions.open(f, key) }, width = 116.dp, sharedKey = key,
                                    title = f.name, subtitle = if (f.id == item.id) "This one" else f.productionYear?.toString(),
                                )
                            }
                        }
                    }
                }
            }
            val people = item.people.filter { it.imageTag != null || it.role != null }.take(20).takeIf { prefs.cast }.orEmpty()
            if (people.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("Cast & crew")
                        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(people, key = { i, it -> "$i:${it.id}" }) { _, person ->
                                PersonBubble(person, actions) { actions.nav.navigate(PersonRoute(person.id, person.name)) }
                            }
                        }
                    }
                }
            }
            if (item.kind == ItemKind.MUSIC_ALBUM || item.kind == ItemKind.MUSIC_ARTIST) {
                item(key = "music-actions") {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    val tracks = state.tracks
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tracks.isNotEmpty()) {
                            PillButton("Play", Icons.Default.PlayArrow, primary = true, onClick = { actions.playItems(tracks) })
                            PillButton("Shuffle", null, primary = false, onClick = { actions.playItems(tracks, shuffle = true) })
                        }
                        PillButton("Instant mix", null, primary = tracks.isEmpty(), onClick = {
                            scope.launch { vm.instantMix().takeIf { it.isNotEmpty() }?.let { mix -> actions.playItems(mix) } }
                        })
                        // The album's songs as they are, to play with no connection.
                        if (tracks.isNotEmpty()) MusicDownloadButton(actions, tracks, "album")
                    }
                }
            }
            if (state.tracks.isNotEmpty()) {
                item(key = "tracks-header") { Column(Modifier.padding(top = 20.dp)) { SectionHeader("${state.tracks.size} tracks") } }
                items(state.tracks.size, key = { "track-" + state.tracks[it].id }) { i ->
                    val t = state.tracks[i]
                    Row(
                        Modifier.fillMaxWidth().pressable({ actions.playItems(state.tracks, start = i) }).padding(horizontal = ScreenPadding, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("${t.indexNumber ?: i + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val by = t.artists.joinToString().takeIf { it.isNotBlank() && it != item.albumArtist }
                            if (by != null) Text(by, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        t.runtimeMs?.takeIf { prefs.runtime }?.let { Text(dev.nitrate.core.model.formatClock(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (state.albums.isNotEmpty()) {
                item(key = "albums") {
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("Albums")
                        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.albums, key = { it.id }) { a ->
                                val key = "albums-${item.id}-${a.id}"
                                MediaCard(a, server.cardImage(a, CardShape.SQUARE), CardShape.SQUARE, title = a.name, subtitle = a.productionYear?.toString(), onClick = { actions.open(a, key) }, width = 140.dp, sharedKey = key)
                            }
                        }
                    }
                }
            }
            if (state.issues.isNotEmpty()) {
                item(key = "issues") {
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("In this series")
                        val start = state.issues.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        LazyRow(
                            state = androidx.compose.foundation.lazy.rememberLazyListState((start - 1).coerceAtLeast(0)),
                            contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.issues, key = { it.id }) { b ->
                                val key = "issues-${item.id}-${b.id}"
                                MediaCard(
                                    b, server.cardImage(b, CardShape.POSTER), CardShape.POSTER, onClick = { if (b.id != item.id) actions.open(b, key) }, width = 110.dp, sharedKey = key,
                                    title = dev.nitrate.core.comics.ComicSeries.label(b),
                                    subtitle = when { b.id == item.id -> "This one"; b.user.played -> "Read"; dev.nitrate.core.comics.ComicSeries.inProgress(b) -> "Reading"; else -> null },
                                )
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
            if (state.extras.isNotEmpty()) {
                item(key = "extras") {
                    Column(Modifier.padding(top = 28.dp)) {
                        SectionHeader("Extras")
                        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.extras, key = { it.id }) { x ->
                                MediaCard(
                                    x, server.cardImage(x, CardShape.WIDE), CardShape.WIDE,
                                    onClick = { actions.play(x, true) },
                                    width = 220.dp, title = x.name, subtitle = listOfNotNull(x.extraType, x.runtimeMs?.let { dev.nitrate.core.model.formatRuntime(it) }).joinToString(" · "),
                                )
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
        if (!wideDetails()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset(40.dp))) {
                item(key = "hero") { hero(false) }
                item(key = "facts") { facts() }
                rows()
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = bottomInset(40.dp))) {
                    hero(true)
                    facts()
                }
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    contentPadding = PaddingValues(top = 24.dp, bottom = bottomInset(40.dp)),
                ) { rows() }
            }
        }
        IconButton(
            onClick = { actions.nav.popBackStack() },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000), contentColor = Color.White),
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 8.dp, top = 4.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }
}
}


/** The big Play (or Resume) button, with progress and "From start" when part-watched. */
@Composable
private fun PlaySection(actions: AppActions, item: MediaItem, seriesTarget: MediaItem?) {
    // A comic: Read (or Continue at the page left off), in the reader.
    if (item.kind == ItemKind.BOOK) {
        PillButton(
            dev.nitrate.core.comics.ComicProgress.readLabel(item), Icons.Default.PlayArrow, primary = true,
            onClick = { actions.nav.navigate(ComicRoute(item.id)) }, modifier = Modifier.fillMaxWidth(),
        )
        ComicInfoButton(actions, item)
        return
    }
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
        // Tap: more or less; long-press: copy.
        modifier = Modifier.animateContentSize().copyOnLongPress(text) { expanded = !expanded },
    )
}

@Composable
private fun EpisodeCard(actions: AppActions, episode: MediaItem, downloaded: Boolean, thisOne: Boolean = false, marker: GroupMarker? = null) {
    // The episode whose page this is: marked, and tapping it plays it rather than opening it again.
    Column(
        Modifier.fillMaxWidth()
            .then(if (thisOne) Modifier.background(Color.White.copy(alpha = 0.06f)) else Modifier)
            .pressable({ if (thisOne) { if (episode.isPlayable) actions.play(episode, false) } else actions.open(episode, "ep-${episode.id}") })
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(148.dp).aspectRatio(16f / 9f).sharedArtwork("ep-${episode.id}").clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
            ) {
                val spoiler = spoilerStill(episode)
                actions.server.cardImage(episode, CardShape.WIDE, 240)?.let {
                    AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(if (spoiler) Modifier.blur(16.dp) else Modifier))
                }
                // Play straight from the list (not an episode the library doesn't have).
                if (episode.isPlayable) Box(
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
                val status = if (thisOne) "This one" else dev.nitrate.core.model.AirDates.status(episode, dev.nitrate.android.nowMs())
                if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = accentColor(), modifier = Modifier.padding(top = 2.dp))
                else episode.runtimeMs?.let { Text(formatRuntime(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
            }
            // The group's own mark for this episode (entered from a group), beside its title.
            if (marker != null && episode.isPlayable) GroupMarkToggle(actions, episode, marker, Modifier.padding(start = 8.dp))
        }
        episode.overview?.takeIf { LocalDisplayPrefs.current.overview && !descriptionHidden(episode) }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).copyOnLongPress(it) { actions.open(episode, "ep-${episode.id}") })
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Icon(icon, label, tint = Color.White, modifier = Modifier.background(Color(0xAA000000), CircleShape).padding(3.dp).size(14.dp))
}


/** "Version: Auto ▾" — which copy of the title plays (4K, 1080p...), or the best for this device. */
@Composable
private fun VersionChooser(item: MediaItem) {
    var chosen by remember(item.id) { mutableStateOf(dev.nitrate.android.playback.VersionChoices[item.id]) }
    var open by remember { mutableStateOf(false) }
    val label = item.versions.firstOrNull { it.id == chosen }?.name ?: "Auto"
    Box {
        Text(
            "Version: $label  ▾",
            style = MaterialTheme.typography.labelLarge,
            color = accentColor(),
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).pressable({ open = true }).padding(horizontal = 4.dp, vertical = 6.dp),
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<Pair<String?, String>>(null to "Auto — the best for this device") + item.versions.map { it.id to it.name }).forEach { (id, name) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = if (id == chosen) ({ Icon(Icons.Default.Check, null) }) else null,
                    onClick = { chosen = id; dev.nitrate.android.playback.VersionChoices.set(item.id, id); open = false },
                )
            }
        }
    }
}

/** "The Night Reel  ›  Season 1": an episode's show and season, each opening its page. */
@Composable
private fun WhereItBelongs(actions: AppActions, item: MediaItem, seasonName: String?) {
    val seriesId = item.seriesId ?: return
    val season = seasonName ?: item.seasonNumber?.let { "Season $it" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            item.seriesName ?: "The show", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).pressable({ actions.nav.navigate(DetailsRoute(seriesId, group = actions.groupOf(item))) }).padding(vertical = 4.dp),
        )
        if (season != null) {
            Text("›", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
            Text(
                season, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                modifier = Modifier.pressable({ actions.nav.navigate(DetailsRoute(seriesId, seasonId = item.seasonId, group = actions.groupOf(item))) }).padding(vertical = 4.dp),
            )
        }
    }
}

/** Everything the action row leaves out: how it plays, moments, chat, lists, more like it, managing. */
@Composable
private fun DetailsMoreSheet(actions: AppActions, item: MediaItem, canManage: Boolean, music: Boolean, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    val container = actions.container
    val video = item.isPlayable && !music
    val studio = item.studios.firstOrNull()
    val year = item.productionYear
    val standings by container.groupStandings.collectAsState()
    val together = dev.nitrate.core.partners.TogetherRow.titleOf(item, standings) in standings
    // A choice opening a dialog replaces the sheet with it; one going elsewhere closes it.
    if (open == null) ChoiceSheet(item.displayTitle, onDismiss, onChoose = {}) { entry ->
        if (video) entry("How it plays", "Whether it plays as it is here, and why") { open = "plays" }
        if (video && (container.companionOffers("notes") || container.companionOffers("moments"))) entry("Group notes", "What your groups said along it, up to where you've got") { open = "moments" }
        if (video && container.companionOffers("chathistory")) entry("Earlier chat", "What was said watching it together") { open = "chat" }
        // In one of the user's watch groups: the same choices as a long press on its Watching together card.
        if (together) entry("Watch group…", "Mark for the group, start a Group Watch Session, same room, invite, leave") { open = "together" }
        if (item.isPlayable || item.kind == ItemKind.SERIES || item.kind == ItemKind.MUSIC_ALBUM) entry("Add to a playlist or collection", null) { open = "add" }
        studio?.let { entry("More from $it", null) { onDismiss(); actions.nav.navigate(PersonRoute("", it, studio = it)) } }
        year?.let { entry("More from $it", null) { onDismiss(); actions.nav.navigate(PersonRoute("", "From $it", year = it)) } }
        if (canManage) entry("Manage", "Details, images, identify") { onDismiss(); actions.nav.navigate(ManageItemRoute(item.id)) }
    }
    when (open) {
        "plays" -> CheckPlaybackDialog(actions, item, onDismiss)
        "moments" -> GroupNotesDialog(actions, item, { at -> actions.playFrom(item, at) }, onDismiss)
        "chat" -> EarlierChatDialog(actions, item.id, onDismiss)
        "add" -> AddToListDialog(actions, item, onDismiss)
        "together" -> TogetherChoicesSheet(actions, item, onStart = {}, onDismiss = onDismiss)
    }
}

/** Width from which a details page sits in two columns (tablets, wide windows). */
val WIDE_DETAILS = 840.dp

/** Whether this window is wide enough for two-column pages (details, settings). */
@Composable
fun wideDetails(): Boolean = dev.nitrate.mobile.platform.windowSizeDp().width >= WIDE_DETAILS

/** "The Night Reel  ›": a season page's show, opening the show's own page. */
@Composable
private fun ShowCrumb(actions: AppActions, show: MediaItem) {
    Text(
        "${show.displayTitle}  ›", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.pressable({ actions.nav.navigate(DetailsRoute(show.id, group = actions.groupOf(show))) }).padding(vertical = 4.dp),
    )
}

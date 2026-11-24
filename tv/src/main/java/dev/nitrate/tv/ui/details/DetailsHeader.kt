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

@Composable
internal fun Header(
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
    /** The season this show's page was opened at (a season's page): the heading names it. */
    season: MediaItem? = null,
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
        } else if (season != null) {
            // A season's page: the show (opening its own page), the season, and how far through it the user is.
            Row(Modifier.offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
                Crumb(item.name) { onOpen(item) }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                Text(season.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 10.dp))
            }
            Text(season.name.ifBlank { "Season ${season.indexNumber}" }, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val episodes = state.episodes.filter { !it.isMissing }
            if (episodes.isNotEmpty()) Text(
                "${episodes.size} ${if (episodes.size == 1) "episode" else "episodes"} · ${episodes.count { it.user.played }} watched",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TitleOrLogo(item, repository.logoUrl(item))
            item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        }
        val music = item.kind == ItemKind.MUSIC_ALBUM || item.kind == ItemKind.MUSIC_ARTIST
        if (item.kind == ItemKind.MUSIC_ALBUM) item.albumArtist?.let { Text("by $it", style = MaterialTheme.typography.titleLarge) }
        if (season == null) {
            ItemMetaLine(item)
            GenreLine(item)
        }
        val prefs = LocalDisplayPrefs.current
        item.tagline?.takeIf { prefs.tagline && season == null }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        }
        // A season's own description when it has one, else the show's.
        (season?.overview?.takeIf { it.isNotBlank() } ?: item.overview)?.takeIf { prefs.overview }?.let { overview ->
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
            // A season's page plays that season: its next unwatched episode, else its first.
            val seasonEpisodes = state.episodes.filter { !it.isMissing }
            val playTarget: MediaItem? = when {
                season != null -> seasonEpisodes.firstOrNull { !it.user.played } ?: seasonEpisodes.firstOrNull()
                item.kind == ItemKind.SERIES -> state.nextUp ?: state.episodes.firstOrNull()
                else -> item
            }
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
                // Entered from a watch group: the group's own mark, apart from Watched (the user's alone).
                val marks = dev.nitrate.tv.ui.partners.LocalGroupMarks.current
                dev.nitrate.tv.ui.partners.groupMarkFor(item)?.let { g ->
                    RoundAction(
                        dev.nitrate.tv.ui.partners.GroupIcon, dev.nitrate.core.partners.GroupMarks.label(g, item),
                        active = dev.nitrate.core.partners.GroupMarks.watched(g, item), onHint = { hint = it }, onClick = { marks?.toggle(g, item) },
                    )
                }
                RoundAction(
                    if (item.user.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (item.user.isFavorite) "Favorite" else "Add to favorites",
                    active = item.user.isFavorite, onHint = { hint = it }, onClick = vm::toggleFavorite,
                )
                RoundAction(Icons.Filled.MoreVert, "More", active = false, onHint = { hint = it }, onClick = { moreOpen = true })
            }
            // The focused round button's name, in room kept for it so nothing moves.
            dev.nitrate.tv.ui.components.ActionHint(hint)
            // What Play starts with: sound, subtitles and which version, in one quiet line.
            StartsWithLine(item, playTarget, trackSetup, pickTrack)
            if (moreOpen) MorePanel(item, repository, playTarget, canCollect, onManage, onBrowse, checkPlayback, applyPlaybackFix, onPlayFrom, onPlay = { onPlay(it, false) }, onOpenShow = { id -> onOpen(MediaItem(id, "", ItemKind.SERIES)) }) { moreOpen = false }
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

/** "The Night Reel  ›  Season 1": an episode's show and season, each opening its page. */
@Composable
internal fun WhereItBelongs(item: MediaItem, state: DetailsState, onOpen: (MediaItem) -> Unit) {
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
internal fun StartsWithLine(
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

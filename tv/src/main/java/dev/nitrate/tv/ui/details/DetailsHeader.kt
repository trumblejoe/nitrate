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
import androidx.compose.foundation.layout.heightIn
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
    chat: dev.nitrate.android.playback.DetailsChat? = null,
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
            // An episode not seen yet goes by "Episode 5" here too.
            val safe = dev.nitrate.tv.ui.partners.spoilerSafe(item)
            Text(
                if (safe.spoiler) safe.name else listOfNotNull(item.indexNumber?.let { "$it." }, item.name).joinToString(" "),
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
            // A change the server refused, and why (the marks stay as the server has them).
            val actionError = vm.state.collectAsState().value.actionError
            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            // What Play starts with: sound, subtitles and which version, in one quiet line.
            StartsWithLine(item, playTarget, trackSetup, pickTrack, chat)
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
 * "Audio: English · 5.1  ·  Subtitles: Off  ·  Version: 4K  ·  Chat: Off": what Play starts with, as
 * one quiet line. OK opens the choices; a track picked is remembered for the show (or title), as in
 * the player. Chat (a title in one of the user's watch groups): Off, New chat or an earlier chat to
 * replay, for the playback Play starts from this page only (`ChatChoices`).
 */
@Composable
internal fun StartsWithLine(
    item: MediaItem,
    playTarget: MediaItem?,
    trackSetup: (suspend (MediaItem) -> dev.nitrate.core.playback.TrackSetup)?,
    pickTrack: suspend (MediaItem, dev.nitrate.core.playback.TrackSetup, dev.nitrate.core.model.StreamKind, Int) -> dev.nitrate.core.playback.TrackSetup,
    chat: dev.nitrate.android.playback.DetailsChat? = null,
) {
    var setup by remember(playTarget?.id) { mutableStateOf<dev.nitrate.core.playback.TrackSetup?>(null) }
    val chatFor = playTarget?.takeIf { it.isPlayable && chat?.choosable?.invoke(it) == true }
    val picks by dev.nitrate.android.playback.ChatChoices.picks.collectAsState()
    // Until picked here, the user's own setting (Settings, Group chat).
    val chatDefault = dev.nitrate.core.companion.ChatPick.default(chat?.chatOn?.invoke(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current) ?: true)
    val chatPick = chatFor?.let { picks[it.id] } ?: chatDefault
    // The groups' threads, asked for when the Chat choice first opens (and for an older thread's name).
    var threads by remember(chatFor?.id) { mutableStateOf<List<dev.nitrate.core.companion.ThreadGroup>?>(null) }
    val offsetAt = { ms: Long -> java.util.TimeZone.getDefault().getOffset(ms) / 60_000 }
    val chatName = dev.nitrate.core.companion.ChatPick.label(chatPick, threads.orEmpty(), offsetAt)
    LaunchedEffect(playTarget?.id) {
        setup = playTarget?.takeIf { it.isPlayable && trackSetup != null }?.let { runCatching { trackSetup!!(it) }.getOrNull() }
    }
    var version by remember(item.id) { mutableStateOf(dev.nitrate.android.playback.VersionChoices[item.id]) }
    val versions = item.versions.size > 1
    val s = setup?.takeIf { it.hasChoices }
    if (s == null && !versions && chatFor == null) return
    val versionName = item.versions.firstOrNull { it.id == version }?.name ?: "Auto"
    val parts = listOfNotNull(
        s?.let { "Audio: ${it.audioName}" }, s?.let { "Subtitles: ${it.subtitleName}" }, if (versions) "Version: $versionName" else null,
        chatFor?.let { "Chat: $chatName" },
    )
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
            if (chatFor != null) ListItem(selected = false, onClick = { open = false; picking = "chat" }, headlineContent = { Text("Chat") }, supportingContent = { Text(chatName) },
                modifier = if (s == null && !versions) Modifier.focusRequester(first) else Modifier)
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
        "chat" -> if (chatFor != null && chat != null) {
            var asking by remember { mutableStateOf<dev.nitrate.core.companion.ThreadGroup?>(null) }
            suspend fun reload() { threads = chat.threads(chatFor) }
            LaunchedEffect(chatFor.id) { if (threads == null) reload() }
            val first = remember { FocusRequester() }
            dev.nitrate.tv.ui.lists.TvDialog({ picking = null }) {
                Text("Chat", style = MaterialTheme.typography.headlineSmall)
                Text("For this playback only. Your setting: Settings, Group chat.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                fun pick(p: dev.nitrate.core.companion.ChatPick) { dev.nitrate.android.playback.ChatChoices.choose(chatFor.id, p); picking = null }
                val off = dev.nitrate.core.companion.ChatPick.Off
                val on = dev.nitrate.core.companion.ChatPick.On
                val groups = threads
                // Focus on what's picked (on On while an older thread picked is still being looked for).
                val pickedListed = chatPick !is dev.nitrate.core.companion.ChatPick.Replay || groups?.any { g -> g.threads.any { it.id == chatPick.threadId } } == true
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        ListItem(selected = chatPick == off, onClick = { pick(off) }, headlineContent = { Text("Off") }, supportingContent = { Text("Nothing pops up; turn it on in the player any time") },
                            modifier = if (chatPick == off) Modifier.focusRequester(first) else Modifier)
                    }
                    item {
                        ListItem(selected = chatPick == on, onClick = { pick(on) }, headlineContent = { Text("On") }, supportingContent = { Text("The group's conversation pops up as you reach it") },
                            modifier = if (chatPick == on || !pickedListed) Modifier.focusRequester(first) else Modifier)
                    }
                    if (groups == null) item { Text("Looking for earlier threads…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    groups.orEmpty().forEach { g ->
                        if (g.older.isNotEmpty()) item(key = "h-${g.groupId}") {
                            Text("Replay an older thread with ${g.title}: its messages pop up too", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(g.older.size, key = { g.older[it].id }) { i ->
                            val t = g.older[i]
                            val p = dev.nitrate.core.companion.ChatPick.Replay(t.id)
                            // ▶ from the thread's line reaches its heart: a favourite is never deleted by the server's time rules.
                            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                ListItem(
                                    selected = chatPick == p, onClick = { pick(p) },
                                    headlineContent = { Text("Replay " + dev.nitrate.core.companion.Threads.date(t.startedMs, offsetAt(t.startedMs))) },
                                    supportingContent = { Text(dev.nitrate.core.companion.Threads.detail(t)) },
                                    modifier = Modifier.weight(1f).then(if (chatPick == p) Modifier.focusRequester(first) else Modifier),
                                )
                                ThreadHeart(t.favourite) { scope.launch { runCatching { chat.favourite(chatFor, t.id, !t.favourite) }.onSuccess { reload() } } }
                            }
                        }
                        // The group's creator: a new thread (a rewatch), asked first.
                        if (g.canStart && g.current != null) item(key = "new-${g.groupId}") {
                            ListItem(selected = false, onClick = { asking = g }, headlineContent = { Text("Start a new thread") }, supportingContent = { Text("For a rewatch: the conversation so far is kept to replay") })
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            asking?.let { g ->
                dev.nitrate.tv.ui.lists.TvDialog({ asking = null }) {
                    Text("Start a new thread with ${g.title}?", style = MaterialTheme.typography.headlineSmall)
                    Text("Everyone's messages from now go into a new conversation. This one is kept, to replay.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val yes = remember { FocusRequester() }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        androidx.tv.material3.Button(onClick = { asking = null; scope.launch { runCatching { chat.startThread(chatFor, g.groupId) }.onSuccess { reload() } } }, modifier = Modifier.focusRequester(yes)) { Text("Start it") }
                        androidx.tv.material3.OutlinedButton(onClick = { asking = null }) { Text("Cancel") }
                    }
                    LaunchedEffect(Unit) { runCatching { yes.requestFocus() } }
                }
            }
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

/** A thread's heart: filled for a favourite; says "Favourite" or "Not a favourite". */
@Composable
private fun ThreadHeart(favourite: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.IconButton(onClick = onClick) {
        androidx.tv.material3.Icon(
            if (favourite) androidx.compose.material.icons.Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Filled.FavoriteBorder,
            contentDescription = if (favourite) dev.nitrate.core.companion.Threads.FAVOURITE else dev.nitrate.core.companion.Threads.NOT_FAVOURITE,
        )
    }
}

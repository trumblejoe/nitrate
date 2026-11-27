package dev.nitrate.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.core.MediaServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ItemKind

@kotlinx.serialization.Serializable
data class DetailsState(
    val item: MediaItem? = null,
    val error: String? = null,
    val similar: List<MediaItem> = emptyList(),
    // A show's (and an episode's own show's): its seasons, the one chosen and that season's episodes.
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeason: Int = 0,
    val episodes: List<MediaItem> = emptyList(),
    val nextUp: MediaItem? = null,
    /** What a collection (box set) holds. */
    val contents: List<MediaItem> = emptyList(),
    /** An album's tracks. */
    val tracks: List<MediaItem> = emptyList(),
    /** An artist's albums. */
    val albums: List<MediaItem> = emptyList(),
    /** Trailers first, then behind the scenes, deleted scenes and other extras. */
    val extras: List<MediaItem> = emptyList(),
    /** A book's series, in reading order (more than just this book). */
    val issues: List<MediaItem> = emptyList(),
    /** A film's collection (a saga: films only, in release order, this one included); empty when it's in none. */
    val collection: List<MediaItem> = emptyList(),
    /** Why the last change didn't happen ("Couldn't mark it watched: …"); the marks stay as the server has them. Not kept. */
    @kotlinx.serialization.Transient val actionError: String? = null,
)

class DetailsViewModel(
    private val repository: MediaServer,
    private val itemId: String,
    private val initialSeasonId: String?,
    /** What this page showed last time, shown at once while it reloads. */
    private val cache: dev.nitrate.android.storage.DataCache? = null,
    /**
     * Whether "More like this" shows (`DisplayPrefs.similar`): the server's similar-titles search is
     * one of the costliest things it's asked for, so it isn't asked when the section is off.
     */
    private val wantsSimilar: suspend () -> Boolean = { true },
) : ViewModel() {
    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state
    private val account = repository.currentAccountKey ?: "${repository.serverUrl}|${repository.userId}"
    private val key = "details/$itemId"
    private fun seasonKey(seasonId: String) = "$key/season/$seasonId"

    init {
        if (cache != null) viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Details")) {
            val cached = cache.read(account, key, DetailsState.serializer()) ?: return@launch
            // Only while nothing fresh has arrived.
            if (_state.value.item == null) _state.value = cached.copy(error = null)
        }
        load()
    }

    /** Loads everything the page shows (once on opening; coming back uses [refresh]). */
    fun load() {
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Details")) {
            val item = runCatching { repository.getItem(itemId) }
                .getOrElse { e -> _state.update { it.copy(error = e.message ?: "Failed to load") }; return@launch }
            _state.update { it.copy(item = item, error = null) }

            if (item.kind == ItemKind.SERIES) loadSeries(item.id)
            // An episode's page shows the rest of its season (and the show's other seasons) too.
            if (item.kind == ItemKind.EPISODE) item.seriesId?.let { loadSeries(it, item.seasonId) }
            if (item.kind == ItemKind.MOVIE) {
                // The same requests as the player's Next in collection, so the kept answers serve both.
                val sagas = runCatching { repository.collectionsOf(item.id).take(5).map { repository.collectionItems(it.id) } }.getOrDefault(emptyList())
                val saga = if (sagas.isEmpty()) emptyList() else dev.nitrate.core.playback.NextInCollection.saga(item, sagas)
                _state.update { it.copy(collection = saga.takeIf { l -> l.size > 1 }.orEmpty()) }
            }
            if (item.kind == ItemKind.MUSIC_ALBUM) _state.update { it.copy(tracks = runCatching { repository.albumTracks(itemId) }.getOrDefault(emptyList())) }
            if (item.kind == ItemKind.MUSIC_ARTIST) _state.update { it.copy(albums = runCatching { repository.artistAlbums(itemId) }.getOrDefault(emptyList())) }
            if (item.kind == ItemKind.BOOK) {
                _state.update { it.copy(issues = runCatching { repository.seriesIssues(item) }.getOrDefault(emptyList()).takeIf { l -> l.size > 1 }.orEmpty()) }
            }
            if (item.kind == ItemKind.BOX_SET) {
                _state.update { it.copy(contents = runCatching { repository.collectionItems(itemId) }.getOrDefault(emptyList())) }
            }
            if (item.localTrailerCount + item.specialFeatureCount > 0) {
                _state.update { it.copy(extras = runCatching { repository.extras(itemId) }.getOrDefault(it.extras)) }
            }
            val similar = if (runCatching { wantsSimilar() }.getOrDefault(true)) runCatching { repository.similar(itemId) }.getOrDefault(emptyList()) else emptyList()
            _state.update { it.copy(similar = similar) }
            cache?.write(account, key, _state.value.copy(error = null), DetailsState.serializer())
        }
    }

    /**
     * Coming back to the page (from the player, after marking something watched): the title itself
     * and the watch marks of what the page shows, in two light requests; what the page holds (seasons,
     * episodes, collections, similar titles) stays as it was. Before the first load, a full [load].
     */
    fun refresh() {
        if (_state.value.item == null) { load(); return }
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Details")) {
            // Back from the player: what the playback did, not what was true before it.
            repository.stopsSettled()
            val item = runCatching { repository.getItem(itemId) }.getOrNull() ?: return@launch
            val shown = _state.value
            val ids = (shown.episodes + shown.collection + shown.contents + shown.issues + shown.similar).map { it.id }.distinct()
            val states = if (ids.isEmpty()) emptyMap() else runCatching { repository.userStates(ids) }.getOrDefault(emptyMap())
            fun List<MediaItem>.fresh() = map { e -> states[e.id]?.let { e.copy(user = it) } ?: e }
            // A show's next episode may have moved on.
            val nextUp = if (item.kind == ItemKind.SERIES) runCatching { repository.nextUpFor(item.id) }.getOrNull() ?: shown.nextUp else shown.nextUp
            _state.update {
                it.copy(
                    item = item, error = null, nextUp = nextUp,
                    episodes = it.episodes.fresh(), collection = it.collection.fresh(), contents = it.contents.fresh(),
                    issues = it.issues.fresh(), similar = it.similar.fresh(),
                )
            }
            cache?.write(account, key, _state.value.copy(error = null), DetailsState.serializer())
        }
    }

    /** Tracks like this album or artist, for "Instant mix". */
    suspend fun instantMix(): List<MediaItem> = runCatching { repository.instantMix(itemId) }.getOrDefault(emptyList())

    /** Takes [member] out of this collection. */
    fun removeFromCollection(member: MediaItem) {
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Details")) {
            runCatching { repository.removeFromCollection(itemId, listOf(member.id)) }
                .onSuccess { _state.update { s -> s.copy(contents = s.contents.filterNot { it.id == member.id }) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Couldn't remove it") } }
        }
    }

    /** The show these seasons and episodes belong to: the page's own item, or an episode's show. */
    private var seriesId: String = itemId

    private suspend fun loadSeries(series: String, ownSeason: String? = null) {
        seriesId = series
        val seasons = runCatching { repository.seasons(series) }.getOrDefault(emptyList())
        val nextUp = if (ownSeason == null) runCatching { repository.nextUpFor(series) }.getOrNull() else null
        val current = _state.value
        // Keep the user's season on reload; otherwise open on the episode's own, the requested or the next-up season.
        val seasonIndex = when {
            current.seasons.isNotEmpty() -> current.selectedSeason
            ownSeason != null -> seasons.indexOfFirst { it.id == ownSeason }
            initialSeasonId != null -> seasons.indexOfFirst { it.id == initialSeasonId }
            nextUp?.seasonId != null -> seasons.indexOfFirst { it.id == nextUp.seasonId }
            else -> 0
        }.coerceIn(0, (seasons.size - 1).coerceAtLeast(0))
        _state.update { it.copy(seasons = seasons, nextUp = nextUp, selectedSeason = seasonIndex) }
        loadEpisodes(seasonIndex)
    }

    fun selectSeason(index: Int) {
        if (index == _state.value.selectedSeason && _state.value.episodes.isNotEmpty()) return
        _state.update { it.copy(selectedSeason = index, episodes = emptyList()) }
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Details")) {
            // That season's episodes as last seen, while they reload.
            val season = _state.value.seasons.getOrNull(index)
            if (cache != null && season != null) {
                val cached = cache.read(account, seasonKey(season.id), kotlinx.serialization.builtins.ListSerializer(MediaItem.serializer()))
                if (cached != null && _state.value.selectedSeason == index && _state.value.episodes.isEmpty()) _state.update { it.copy(episodes = cached) }
            }
            loadEpisodes(index)
        }
    }

    private suspend fun loadEpisodes(index: Int) {
        val season = _state.value.seasons.getOrNull(index) ?: return
        val episodes = runCatching { repository.episodes(seriesId, season.id) }.getOrNull() ?: return
        if (_state.value.selectedSeason == index) _state.update { it.copy(episodes = episodes) }
        cache?.write(account, seasonKey(season.id), episodes, kotlinx.serialization.builtins.ListSerializer(MediaItem.serializer()))
    }

    /**
     * Watched/Unwatched shows at once (the title, and the episodes on show when it's a show or a season),
     * while the server does it. Once it's saved the page is read again; taken to be saved a moment later
     * (the Companion's queue), what's shown stays. Not done: the page shows what the server has, and why.
     */
    fun togglePlayed() {
        val item = _state.value.item ?: return
        val played = !item.user.played
        val now = dev.nitrate.android.nowMs()
        fun inside(e: MediaItem) = e.id == item.id || e.seriesId == item.id || e.seasonId == item.id
        _state.update { s ->
            s.copy(
                actionError = null,
                item = s.item?.copy(user = item.user.marked(played, now)),
                episodes = s.episodes.map { e -> if (inside(e)) e.copy(user = e.user.marked(played, now)) else e },
                contents = s.contents.map { e -> if (inside(e)) e.copy(user = e.user.marked(played, now)) else e },
            )
        }
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Details")) {
            val saved = runCatching { repository.setPlayed(item.id, played, item.user) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(actionError = (if (played) "Couldn't mark it watched: " else "Couldn't mark it unwatched: ") + (e.message ?: "no answer from the server")) }
            }.getOrNull()
            if (saved != false) refresh()
        }
    }

    fun toggleFavorite() = mutate(if (_state.value.item?.user?.isFavorite == true) "Couldn't take it out of favourites" else "Couldn't add it to favourites") { item ->
        repository.setFavorite(item.id, item.user.isFavorite.not())
    }

    /**
     * Asks the server for a change, then shows what the server has: nothing on screen changes
     * until it has (never a mark that didn't happen). A refusal or no answer says why ([failed]: …).
     */
    private fun mutate(failed: String, action: suspend (MediaItem) -> Unit) {
        val item = _state.value.item ?: return
        _state.update { it.copy(actionError = null) }
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Details")) {
            runCatching { action(item) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(actionError = "$failed: " + (e.message ?: "no answer from the server")) }
            }
            refresh()
        }
    }
}


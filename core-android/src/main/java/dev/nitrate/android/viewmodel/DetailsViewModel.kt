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
    // Series only
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
)

class DetailsViewModel(
    private val repository: MediaServer,
    private val itemId: String,
    private val initialSeasonId: String?,
    /** What this page showed last time, shown at once while it reloads. */
    private val cache: dev.nitrate.android.storage.DataCache? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state
    private val account = repository.currentAccountKey ?: "${repository.serverUrl}|${repository.userId}"
    private val key = "details/$itemId"
    private fun seasonKey(seasonId: String) = "$key/season/$seasonId"

    init {
        if (cache != null) viewModelScope.launch {
            val cached = cache.read(account, key, DetailsState.serializer()) ?: return@launch
            // Only while nothing fresh has arrived.
            if (_state.value.item == null) _state.value = cached.copy(error = null)
        }
        load()
    }

    /** Reloads user data (watched, resume point) e.g. after returning from the player. */
    fun load() {
        viewModelScope.launch {
            val item = runCatching { repository.getItem(itemId) }
                .getOrElse { e -> _state.update { it.copy(error = e.message ?: "Failed to load") }; return@launch }
            _state.update { it.copy(item = item, error = null) }

            if (item.kind == ItemKind.SERIES) loadSeries(item)
            if (item.kind == ItemKind.MUSIC_ALBUM) _state.update { it.copy(tracks = runCatching { repository.albumTracks(itemId) }.getOrDefault(emptyList())) }
            if (item.kind == ItemKind.MUSIC_ARTIST) _state.update { it.copy(albums = runCatching { repository.artistAlbums(itemId) }.getOrDefault(emptyList())) }
            if (item.kind == ItemKind.BOX_SET) {
                _state.update { it.copy(contents = runCatching { repository.collectionItems(itemId) }.getOrDefault(emptyList())) }
            }
            val similar = runCatching { repository.similar(itemId) }.getOrDefault(emptyList())
            _state.update { it.copy(similar = similar) }
            cache?.write(account, key, _state.value.copy(error = null), DetailsState.serializer())
        }
    }

    /** Tracks like this album or artist, for "Instant mix". */
    suspend fun instantMix(): List<MediaItem> = runCatching { repository.instantMix(itemId) }.getOrDefault(emptyList())

    /** Takes [member] out of this collection. */
    fun removeFromCollection(member: MediaItem) {
        viewModelScope.launch {
            runCatching { repository.removeFromCollection(itemId, listOf(member.id)) }
                .onSuccess { _state.update { s -> s.copy(contents = s.contents.filterNot { it.id == member.id }) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Couldn't remove it") } }
        }
    }

    private suspend fun loadSeries(series: MediaItem) {
        val seasons = runCatching { repository.seasons(series.id) }.getOrDefault(emptyList())
        val nextUp = runCatching { repository.nextUpFor(series.id) }.getOrNull()
        val current = _state.value
        // Keep the user's season on reload; otherwise open on the requested or next-up season.
        val seasonIndex = when {
            current.seasons.isNotEmpty() -> current.selectedSeason
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
        viewModelScope.launch {
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
        val episodes = runCatching { repository.episodes(itemId, season.id) }.getOrNull() ?: return
        if (_state.value.selectedSeason == index) _state.update { it.copy(episodes = episodes) }
        cache?.write(account, seasonKey(season.id), episodes, kotlinx.serialization.builtins.ListSerializer(MediaItem.serializer()))
    }

    fun togglePlayed() = mutate { item ->
        val played = !item.user.played
        repository.setPlayed(item.id, played)
    }

    fun toggleFavorite() = mutate { item ->
        repository.setFavorite(item.id, item.user.isFavorite.not())
    }

    private fun mutate(action: suspend (MediaItem) -> Unit) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { action(item) }
            load()
        }
    }
}

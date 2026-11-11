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
)

class DetailsViewModel(
    private val repository: MediaServer,
    private val itemId: String,
    private val initialSeasonId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state

    init { load() }

    /** Reloads user data (watched, resume point) e.g. after returning from the player. */
    fun load() {
        viewModelScope.launch {
            val item = runCatching { repository.getItem(itemId) }
                .getOrElse { e -> _state.update { it.copy(error = e.message ?: "Failed to load") }; return@launch }
            _state.update { it.copy(item = item, error = null) }

            if (item.kind == ItemKind.SERIES) loadSeries(item)
            if (item.kind == ItemKind.BOX_SET) {
                _state.update { it.copy(contents = runCatching { repository.collectionItems(itemId) }.getOrDefault(emptyList())) }
            }
            val similar = runCatching { repository.similar(itemId) }.getOrDefault(emptyList())
            _state.update { it.copy(similar = similar) }
        }
    }

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
        viewModelScope.launch { loadEpisodes(index) }
    }

    private suspend fun loadEpisodes(index: Int) {
        val season = _state.value.seasons.getOrNull(index) ?: return
        val episodes = runCatching { repository.episodes(itemId, season.id) }.getOrDefault(emptyList())
        if (_state.value.selectedSeason == index) _state.update { it.copy(episodes = episodes) }
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

package dev.nitrate.tv.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.tv.data.JellyfinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

data class DetailsState(
    val item: BaseItemDto? = null,
    val error: String? = null,
    val similar: List<BaseItemDto> = emptyList(),
    // Series only
    val seasons: List<BaseItemDto> = emptyList(),
    val selectedSeason: Int = 0,
    val episodes: List<BaseItemDto> = emptyList(),
    val nextUp: BaseItemDto? = null,
)

class DetailsViewModel(
    private val repository: JellyfinRepository,
    private val itemId: UUID,
    private val initialSeasonId: UUID?,
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

            if (item.type == BaseItemKind.SERIES) loadSeries(item)
            val similar = runCatching { repository.similar(itemId) }.getOrDefault(emptyList())
            _state.update { it.copy(similar = similar) }
        }
    }

    private suspend fun loadSeries(series: BaseItemDto) {
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
        val played = item.userData?.played != true
        repository.setPlayed(item.id, played)
    }

    fun toggleFavorite() = mutate { item ->
        repository.setFavorite(item.id, item.userData?.isFavorite != true)
    }

    private fun mutate(action: suspend (BaseItemDto) -> Unit) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { action(item) }
            load()
        }
    }
}

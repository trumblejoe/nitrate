package dev.nitrate.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.Browse
import dev.nitrate.core.pages.BrowseOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseState(
    val options: BrowseOptions = BrowseOptions(),
    val items: List<MediaItem> = emptyList(),
    val total: Int = -1,
    val error: String? = null,
) {
    val complete get() = total >= 0 && items.size >= total
    val loading get() = total < 0 && error == null
}

/**
 * A library's full list with the user's sort, filters and view (saved per library), paged as
 * they scroll, with A–Z jumps when sorted by name.
 */
class BrowseViewModel(
    private val server: MediaServer,
    private val store: DisplaySettingsStore,
    private val library: MediaItem,
) : ViewModel() {
    private val userId = server.userId
    private val key = "browse/${library.id}"
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state
    private val _genres = MutableStateFlow<List<String>>(emptyList())

    /** Genres in this library, for the filter. */
    val genres: StateFlow<List<String>> = _genres
    private var loading: Job? = null

    init {
        viewModelScope.launch {
            (userId?.let { store.view(it, key, BrowseOptions()) } ?: flowOf(BrowseOptions())).distinctUntilChanged().collect { options ->
                if (options != _state.value.options || _state.value.total < 0) restart(options)
            }
        }
        viewModelScope.launch { _genres.value = runCatching { server.genres(library.id) }.getOrDefault(emptyList()) }
    }

    /** Changes the options (saved, so they're there next time and on other devices). */
    fun update(change: (BrowseOptions) -> BrowseOptions) {
        val next = change(_state.value.options)
        if (next == _state.value.options) return
        restart(next)
        val user = userId ?: return
        viewModelScope.launch { store.saveView(user, key, next) }
    }

    private fun restart(options: BrowseOptions) {
        loading?.cancel()
        loading = null
        _state.value = BrowseState(options = options)
        loadMore()
    }

    fun loadMore() {
        if (loading?.isActive == true || _state.value.complete || _state.value.error != null && _state.value.items.isNotEmpty()) return
        val current = _state.value
        loading = viewModelScope.launch {
            runCatching { server.queryPage(current.options.query(library.id, library.collection), current.items.size, PAGE_SIZE) }
                .onSuccess { (items, total) -> _state.update { if (it.options == current.options) it.copy(items = it.items + items, total = total, error = null) else it } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Failed to load") } }
        }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    /**
     * Where [letter] starts in the list (loading up to there), or null if nothing does.
     * Only meaningful when sorted by name.
     */
    suspend fun positionOf(letter: String): Int? {
        val options = _state.value.options
        val query = options.query(library.id, library.collection)
        val before = Browse.before(query, letter)?.let { runCatching { server.queryPage(it, 0, 0).second }.getOrNull() ?: return null } ?: 0
        // Descending name order puts the letter's items after everything greater.
        val index = if (options.descending) {
            val total = _state.value.total.takeIf { it >= 0 } ?: return null
            val upTo = Browse.LETTERS.getOrNull(Browse.LETTERS.indexOf(letter) + 1)?.let { next ->
                runCatching { server.queryPage(Browse.before(query, next)!!, 0, 0).second }.getOrNull()
            } ?: total
            total - upTo
        } else before
        while (_state.value.items.size <= index && !_state.value.complete && _state.value.error == null) {
            loading?.join()
            if (_state.value.items.size > index || _state.value.complete) break
            loadMore()
            loading?.join()
        }
        return index.takeIf { it < _state.value.items.size }
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

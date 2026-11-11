package dev.nitrate.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.core.MediaServer
import dev.nitrate.core.LibrarySort
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.nitrate.core.model.MediaItem

data class GridState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = -1,
    val sort: LibrarySort = LibrarySort.NAME,
    val error: String? = null,
) {
    val complete get() = total >= 0 && items.size >= total
}

/** Pages through a library for the grid views, loading more as the user scrolls. */
class LibraryGridViewModel(
    private val repository: MediaServer,
    private val library: MediaItem,
    initialSort: LibrarySort,
    private val includeTags: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(GridState(sort = initialSort))
    val state: StateFlow<GridState> = _state
    private var loading: Job? = null

    init { loadMore() }

    fun setSort(sort: LibrarySort) {
        if (sort == _state.value.sort) return
        loading?.cancel()
        loading = null
        _state.value = GridState(sort = sort)
        loadMore()
    }

    fun loadMore() {
        if (loading?.isActive == true || _state.value.complete) return
        val start = _state.value.items.size
        loading = viewModelScope.launch {
            runCatching { repository.libraryPage(library, _state.value.sort, start, PAGE_SIZE, includeTags) }
                .onSuccess { (items, total) -> _state.update { it.copy(items = it.items + items, total = total, error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Failed to load") } }
        }
    }

    /** Loads every remaining page, for views that must see the whole library (e.g. grouping). */
    fun loadAll() {
        viewModelScope.launch {
            while (!_state.value.complete && _state.value.error == null) {
                loading?.join()
                if (_state.value.complete) break
                loadMore()
                loading?.join()
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

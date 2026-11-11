package dev.nitrate.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Any list loaded a page at a time as it scrolls (e.g. a music library's artists). */
class PagedViewModel(private val load: suspend (startIndex: Int, limit: Int) -> Pair<List<MediaItem>, Int>) : ViewModel() {
    private val _state = MutableStateFlow(GridState())
    val state: StateFlow<GridState> = _state
    private var loading: Job? = null

    init { loadMore() }

    fun loadMore() {
        if (loading?.isActive == true || _state.value.complete) return
        val start = _state.value.items.size
        loading = viewModelScope.launch {
            runCatching { load(start, 100) }
                .onSuccess { (items, total) -> _state.update { it.copy(items = it.items + items, total = total, error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Failed to load") } }
        }
    }
}

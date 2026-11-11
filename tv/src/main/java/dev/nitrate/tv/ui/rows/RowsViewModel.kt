package dev.nitrate.tv.ui.rows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.android.storage.DisplaySettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RowsState {
    data object Loading : RowsState
    data class Loaded(val rows: List<MediaRow>) : RowsState
    data class Error(val message: String) : RowsState
}

/** Loads a page of rows and the user's display overrides for them. Used by Home and libraries. */
class RowsViewModel(
    private val load: suspend () -> List<MediaRow>,
    private val store: DisplaySettingsStore,
    private val userId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow<RowsState>(RowsState.Loading)
    val state: StateFlow<RowsState> = _state

    val overrides: StateFlow<Map<String, RowDisplay>> =
        (userId?.let(store::overrides) ?: emptyFlow())
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val result = runCatching { load() }
            // Keep showing old rows if a background refresh fails.
            _state.value = result.fold(
                { RowsState.Loaded(it) },
                { if (_state.value is RowsState.Loaded) _state.value else RowsState.Error(it.message ?: "Failed to load") },
            )
        }
    }

    fun save(row: MediaRow, display: RowDisplay) {
        val user = userId ?: return
        viewModelScope.launch { store.save(user, row, display) }
    }
}

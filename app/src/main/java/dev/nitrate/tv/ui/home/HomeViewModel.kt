package dev.nitrate.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.tv.data.HomeRow
import dev.nitrate.tv.data.JellyfinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface HomeState {
    data object Loading : HomeState
    data class Loaded(val rows: List<HomeRow>) : HomeState
    data class Error(val message: String) : HomeState
}

class HomeViewModel(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = runCatching { repository.homeRows() }
                .fold({ HomeState.Loaded(it) }, { HomeState.Error(it.message ?: "Failed to load library") })
        }
    }
}

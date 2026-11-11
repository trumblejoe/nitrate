package dev.nitrate.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.core.MediaServer
import dev.nitrate.core.livetv.Guide
import dev.nitrate.core.livetv.LiveChannel
import dev.nitrate.core.livetv.TvProgram
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiveTvState(
    val channels: List<LiveChannel>? = null,
    /** Programmes by channel id, for the guide window. */
    val guide: Map<String, List<TvProgram>> = emptyMap(),
    val recordings: List<MediaItem> = emptyList(),
    val windowStartMs: Long = 0,
    val nowMs: Long = 0,
    val error: String? = null,
    val message: String? = null,
)

/** Channels with what's on, a guide window that can move later or earlier, recordings, scheduling. */
class LiveTvViewModel(private val server: MediaServer, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvState(windowStartMs = Guide.windowStart(now()), nowMs = now()))
    val state: StateFlow<LiveTvState> = _state

    init {
        refresh()
        // Keep "now" (progress, what's airing) current.
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                _state.update { it.copy(nowMs = now()) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { server.liveChannels() }
                .onSuccess { channels ->
                    _state.update { it.copy(channels = channels, error = null, nowMs = now()) }
                    loadGuide()
                }
                .onFailure { e -> _state.update { it.copy(channels = it.channels ?: emptyList(), error = e.message ?: "Couldn't load channels") } }
            _state.update { it.copy(recordings = runCatching { server.recordings() }.getOrDefault(emptyList())) }
        }
    }

    /** Moves the guide window by [slots] half hours (never before the current half hour). */
    fun shift(slots: Int) {
        val earliest = Guide.windowStart(now())
        _state.update { it.copy(windowStartMs = maxOf(earliest, it.windowStartMs + slots * Guide.SLOT_MS)) }
        viewModelScope.launch { loadGuide() }
    }

    private suspend fun loadGuide() {
        val s = _state.value
        val ids = s.channels.orEmpty().map { it.id }
        val programs = runCatching { server.guide(ids, s.windowStartMs, s.windowStartMs + WINDOW_MS) }.getOrDefault(emptyList())
        _state.update { it.copy(guide = programs.groupBy { p -> p.channelId }) }
    }

    /** Records [program], or cancels its recording if it's already scheduled. */
    fun toggleRecording(program: TvProgram) {
        viewModelScope.launch {
            val timer = program.timerId
            runCatching { if (timer != null) server.cancelRecording(timer) else server.record(program.id) }
                .onSuccess { _state.update { it.copy(message = if (timer != null) "Won't record ${program.title}" else "Will record ${program.title}") } }
                .onFailure { e -> _state.update { it.copy(message = "Couldn't change the recording: ${e.message}") } }
            loadGuide()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** How much of the guide shows at once. */
        const val WINDOW_MS = 3 * 60 * 60_000L
    }
}

package dev.nitrate.android.viewmodel

import dev.nitrate.android.nowMs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.core.MediaServer
import dev.nitrate.core.channels.ChannelGuide
import dev.nitrate.core.channels.HouseChannel
import dev.nitrate.core.livetv.Guide
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChannelsState(
    /** Each channel with its programs in the window; null until loaded. */
    val guides: List<ChannelGuide>? = null,
    val windowStartMs: Long = 0,
    val nowMs: Long = 0,
    val error: String? = null,
    val message: String? = null,
)

/**
 * The household's channels (Nitrate Companion): a shared guide, moved by half hours, and tuning
 * in, which gives the program on now from its start and what follows in order, to play as a queue.
 * Nothing is streamed until the queue plays.
 */
class ChannelsViewModel(private val server: MediaServer, private val now: () -> Long = ::nowMs) : ViewModel() {
    private val _state = MutableStateFlow(ChannelsState(windowStartMs = Guide.windowStart(now()), nowMs = now()))
    val state: StateFlow<ChannelsState> = _state

    init {
        refresh()
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Channels")) {
            while (isActive) {
                delay(30_000)
                _state.update { it.copy(nowMs = now()) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Channels")) {
            val s = _state.value
            runCatching { server.channelGuide(s.windowStartMs, s.windowStartMs + WINDOW_MS) }
                .onSuccess { g -> _state.update { it.copy(guides = g, error = null, nowMs = now()) } }
                .onFailure { e -> _state.update { it.copy(guides = it.guides ?: emptyList(), error = e.message ?: "Couldn't load the channels") } }
        }
    }

    /** Moves the guide by [slots] half hours (never before the current half hour). */
    fun shift(slots: Int) {
        val earliest = Guide.windowStart(now())
        _state.update { it.copy(windowStartMs = maxOf(earliest, it.windowStartMs + slots * Guide.SLOT_MS)) }
        refresh()
    }

    /**
     * Tunes in: the ids to play as a queue (each from its start), or null with a message when
     * nothing on the channel can be watched now.
     */
    suspend fun tune(channel: HouseChannel): List<String>? = runCatching { server.tuneChannel(channel.id) }
        .fold(
            onSuccess = { programs ->
                programs.map { it.itemId }.ifEmpty {
                    _state.update { it.copy(message = "Nothing on ${channel.name} you can watch right now.") }
                    null
                }
            },
            onFailure = { e ->
                _state.update { it.copy(message = "Couldn't tune in: ${e.message}") }
                null
            },
        )

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** Three hours of guide. */
        const val WINDOW_MS = 3 * 60 * 60_000L

        /** Things that can go in a lineup, for the editor: shows, movies and collections matching [query]. */
        suspend fun findSources(server: MediaServer, query: String): List<MediaItem> {
            if (query.isBlank()) return emptyList()
            val r = server.search(query.trim())
            return r.shows + r.movies + r.collections
        }
    }
}

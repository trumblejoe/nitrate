package dev.nitrate.testing

import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.music.ListenState
import dev.nitrate.core.music.Listening
import dev.nitrate.core.music.Repeat
import kotlinx.coroutines.flow.MutableStateFlow

/** A listening player for screens in tests: a fixed state, and what was asked of it. */
class FakeListening(start: ListenState = ListenState(), private val position: Long = 0) : Listening {
    override val state = MutableStateFlow(start)
    val asked = mutableListOf<String>()
    override fun positionMs() = position
    override fun play(items: List<MediaItem>, startIndex: Int, shuffle: Boolean) { asked += "play ${items.size} from $startIndex${if (shuffle) " shuffled" else ""}"; state.value = ListenState(items, startIndex, playing = true, durationMs = items.getOrNull(startIndex)?.runtimeMs ?: 0, shuffle = shuffle) }
    override fun playNext(items: List<MediaItem>) { asked += "next ${items.size}" }
    override fun addToQueue(items: List<MediaItem>) { asked += "add ${items.size}" }
    override fun toggle() { asked += "toggle"; state.value = state.value.copy(playing = !state.value.playing) }
    override fun pause() { asked += "pause"; state.value = state.value.copy(playing = false) }
    override fun next() { asked += "next" }
    override fun previous() { asked += "previous" }
    override fun seekTo(ms: Long) { asked += "seek $ms" }
    override fun skipTo(index: Int) { asked += "skip $index" }
    override fun setShuffle(on: Boolean) { asked += "shuffle $on" }
    override fun setRepeat(repeat: Repeat) { asked += "repeat $repeat" }
    override fun remove(index: Int) { asked += "remove $index" }
    override fun move(from: Int, to: Int) { asked += "move $from $to" }
    override fun stop() { asked += "stop"; state.value = ListenState() }
}

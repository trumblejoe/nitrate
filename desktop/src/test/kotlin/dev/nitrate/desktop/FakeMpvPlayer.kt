package dev.nitrate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nitrate.mobile.ui.MpvMedia
import dev.nitrate.mobile.ui.MpvPlayer
import dev.nitrate.mobile.ui.MpvState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A player that plays nothing: it remembers what it was asked and moves only when told to. */
class FakeMpvPlayer : MpvPlayer {
    override val state = MutableStateFlow(MpvState())
    override val nativeWindow = false
    val loads = mutableListOf<MpvMedia>()
    val calls = mutableListOf<String>()
    var released = false

    @Composable
    override fun Video(modifier: Modifier) = Box(modifier.background(Color(0xFF203040)))

    override fun load(media: MpvMedia) {
        loads += media
        state.value = MpvState(positionSeconds = media.startSeconds, durationSeconds = 2_700.0, loaded = true)
    }
    override fun setAudio(id: Int) { calls += "aid=$id" }
    override fun setSubtitle(id: Int) { calls += "sid=$id" }
    override fun play() { calls += "play"; state.update { it.copy(paused = false) } }
    override fun pause() { calls += "pause"; state.update { it.copy(paused = true) } }
    override fun seek(seconds: Double) { calls += "seek=${seconds.toLong()}"; state.update { it.copy(positionSeconds = seconds) } }
    override fun setSpeed(speed: Double) { calls += "speed=$speed" }
    override fun setVolume(volume: Int) { calls += "volume=$volume" }
    override fun setMuted(muted: Boolean) { calls += "mute=$muted" }
    override fun setAudioDelay(ms: Int) { calls += "audio-delay=$ms" }
    override fun setSubtitleDelay(ms: Int) { calls += "sub-delay=$ms" }
    override fun describe() = listOf("Video decoder" to "h264", "Hardware decoding" to "d3d11va")
    override fun release() { released = true }
}

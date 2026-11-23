package dev.nitrate.desktop

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.SegmentKind
import dev.nitrate.testing.FakeServer

/** [FakeServer] that also answers for playing: the file as it is (or converted when forced), reports kept. */
class PlayingServer(private val fake: FakeServer = FakeServer(), private val segments: List<MediaSegment> = emptyList()) : MediaServer by fake {
    val resolved = mutableListOf<Boolean>()
    val reports = mutableListOf<String>()

    override fun authorizationHeader() = "MediaBrowser Client=\"Nitrate\", Token=\"secret\""

    override suspend fun resolvePlayback(
        itemId: String, isAudio: Boolean, startMs: Long, forceTranscode: Boolean, audioIndex: Int?, subtitleIndex: Int?,
        mediaSourceId: String?, deviceCaps: dev.nitrate.core.playback.DeviceCapabilities?,
    ): PlaybackPlan {
        resolved += forceTranscode
        val source = fake.mediaSource(itemId)
        return PlaybackPlan(
            itemId = itemId,
            url = if (forceTranscode) "$serverUrl/videos/$itemId/master.m3u8" else "$serverUrl/Videos/$itemId/stream?static=true",
            method = if (forceTranscode) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
            playSessionId = "ps",
            source = source,
            isHls = forceTranscode,
            audioIndex = audioIndex ?: source.defaultAudioIndex,
            subtitleIndex = subtitleIndex ?: -1,
            externalSubtitles = listOf(ExternalSubtitle(9, "$serverUrl/Videos/$itemId/Subtitles/9/Stream.srt", "application/x-subrip", "fra", "French")),
            transcodeReasons = emptyList(),
        )
    }

    override suspend fun reportStart(plan: PlaybackPlan, positionMs: Long) { reports += "start ${plan.method} at $positionMs" }
    override suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) { reports += "progress a${plan.audioIndex} s${plan.subtitleIndex}" }
    override fun reportStopped(plan: PlaybackPlan, positionMs: Long) { reports += "stopped ${plan.method}" }
    override suspend fun mediaSegments(itemId: String) = segments

    companion object {
        val INTRO = MediaSegment(SegmentKind.INTRO, 60_000, 120_000)
    }
}

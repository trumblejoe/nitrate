package dev.nitrate.testing

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.AudioCodecCaps
import dev.nitrate.core.playback.AudioDelivery
import dev.nitrate.core.playback.AudioPath
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackTrail
import dev.nitrate.core.playback.TrailInput
import dev.nitrate.core.playback.VideoCodecCaps
import dev.nitrate.core.playback.VideoPath

/** A 4K Dolby Vision film direct streamed to a TV that reports no HDR, its TrueHD sent untouched: for screenshots. */
fun sampleTrail(): PlaybackTrail {
    val caps = DeviceCapabilities(
        model = "Test TV",
        video = mapOf("hevc" to VideoCodecCaps("hevc", "video/hevc", "OMX.MTK.VIDEO.DECODER.HEVC", true, 3840, 2176, true)),
        audio = mapOf("truehd" to AudioCodecCaps("truehd", "ffmpeg", passthrough = true)),
        hdr = emptySet(),
        displayWidth = 3840,
        displayHeight = 2160,
    )
    val source = MediaSource(
        "s", "mkv", 60_000_000,
        listOf(
            MediaStream(0, StreamKind.VIDEO, codec = "hevc", width = 3840, height = 2160, bitDepth = 10, rangeType = "DOVIWithHDR10", frameRate = 23.976f),
            MediaStream(1, StreamKind.AUDIO, codec = "truehd", channels = 8, language = "eng", isDefault = true),
        ),
    )
    val settings = PlaybackSettings(overrides = mapOf(PlaybackSettings.hdrId("HDR10") to Override.ALWAYS, PlaybackSettings.hdrId("DOVI") to Override.ALWAYS))
    return PlaybackTrail.build(
        TrailInput(
            title = "Northern Lights (2021)", source = source,
            plan = PlaybackPlan("a", "/Videos/a/main.m3u8", PlayMethod.DIRECT_STREAM, "p", source, isHls = true, audioIndex = 1, subtitleIndex = -1, externalSubtitles = emptyList(), transcodeReasons = listOf("ContainerNotSupported")),
            caps = caps, settings = settings,
            prediction = dev.nitrate.core.jellyfin.ProfileCheck.predict(caps, settings, source),
            video = VideoPath(inCodec = "hevc", inWidth = 3840, inHeight = 2160, inFps = 23.976f, inRange = "Dolby Vision", displayWidth = 3840, displayHeight = 2160, displayHz = 23.976f, displayHdr = emptySet()),
            audio = AudioPath(inCodec = "truehd", inChannels = 8, inRateHz = 48_000, delivery = AudioDelivery.BITSTREAM, outFormat = "Dolby TrueHD", outChannels = 8, output = "DENON-AVR (HDMI) · speakers 7.1"),
            arrivedVideoCodec = "video/dolby-vision", arrivedAudioCodec = "audio/true-hd",
            videoDecoder = "OMX.MTK.VIDEO.DECODER.HEVC", displayHzBefore = 59.94f,
        ),
    )
}

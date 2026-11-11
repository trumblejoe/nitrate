package dev.nitrate.core.playback

fun video(codec: String, maxHeight: Int = 2160, tenBit: Boolean = true) =
    VideoCodecCaps(codec, "video/x", "c2.vendor.$codec", hardware = true, maxWidth = maxHeight * 16 / 9, maxHeight = maxHeight, tenBit = tenBit)

fun caps(
    video: List<VideoCodecCaps> = listOf(video("h264"), video("hevc")),
    audio: Map<String, Boolean> = mapOf("aac" to true, "ac3" to true),
    hdr: Set<String> = setOf("HDR10", "HLG"),
) = DeviceCapabilities(
    video = video.associateBy { it.codec },
    audio = audio.mapValues { (codec, ok) -> AudioCodecCaps(codec, if (ok) "c2.$codec" else null, passthrough = false) },
    hdr = hdr,
    displayWidth = 3840,
    displayHeight = 2160,
    model = "Test TV",
)

fun clip(label: String) = PlaybackTest.CLIPS.first { it.label == label }

fun pass(label: String) = ClipResult(clip(label), Outcome.PASSED, "c2.vendor", "")
fun fail(label: String) = ClipResult(clip(label), Outcome.FAILED, null, "decoder init failed")
fun slow(label: String) = ClipResult(clip(label), Outcome.SLOW, "c2.android.x", "dropped 80%")

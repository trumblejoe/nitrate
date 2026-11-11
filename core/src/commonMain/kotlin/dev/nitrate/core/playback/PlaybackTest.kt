package dev.nitrate.core.playback

enum class ClipKind { VIDEO, AUDIO }

/** A bundled test clip (see tools/generate-test-clips.sh). */
data class TestClip(
    val asset: String,
    val label: String,
    val kind: ClipKind,
    /** Jellyfin codec name. */
    val codec: String,
    val height: Int = 0,
    val tenBit: Boolean = false,
    /** HDR format as a Jellyfin range name (HDR10, HLG, DOVI), or null for SDR. */
    val hdr: String? = null,
    /** Dolby Vision profile for DOVI clips (5 or 8). */
    val dolbyVisionProfile: Int? = null,
) {
    val uri get() = "asset:///testclips/$asset"

    /** Whether the device's reported capabilities say this clip should play. */
    fun claimed(caps: DeviceCapabilities): Boolean = when (kind) {
        ClipKind.AUDIO -> caps.audio[codec]?.supported == true
        ClipKind.VIDEO -> {
            val v = caps.video[codec]
            val dv = dolbyVisionProfile == null || dolbyVisionProfile in caps.dolbyVisionProfiles
            v != null && v.maxHeight >= height && (!tenBit || v.tenBit) && (hdr == null || hdr in caps.hdr) && dv
        }
    }
}

enum class Outcome(val label: String) {
    PASSED("Plays"),

    /** Dolby Vision Profile 8.1 that played as its HDR10 base layer, without Dolby Vision. */
    FALLBACK("Plays as HDR10"),

    /** Decoded, but dropped so many frames it isn't watchable. */
    SLOW("Too slow"),
    FAILED("Fails"),
}

data class ClipResult(
    val clip: TestClip,
    val outcome: Outcome,
    val decoder: String?,
    val detail: String,
    val framesRendered: Int = 0,
    val framesDropped: Int = 0,
) {
    val ok get() = outcome == Outcome.PASSED
    val softwareDecoder get() = decoder?.let { it.startsWith("c2.android.") || it.startsWith("OMX.google.") } == true
}

/** One proposed settings change, explained in plain words. */
data class Suggestion(val description: String, val apply: (PlaybackSettings) -> PlaybackSettings)

object PlaybackTest {
    val CLIPS = listOf(
        TestClip("h264_1080p.mp4", "H.264 1080p", ClipKind.VIDEO, "h264", 1080),
        TestClip("h264_1080p_10bit.mp4", "H.264 1080p 10-bit", ClipKind.VIDEO, "h264", 1080, tenBit = true),
        TestClip("h264_2160p.mp4", "H.264 4K", ClipKind.VIDEO, "h264", 2160),
        TestClip("hevc_1080p.mp4", "HEVC 1080p", ClipKind.VIDEO, "hevc", 1080),
        TestClip("hevc_1080p_hlg.mp4", "HEVC 1080p HLG", ClipKind.VIDEO, "hevc", 1080, tenBit = true, hdr = "HLG"),
        TestClip("hevc_2160p_10bit.mp4", "HEVC 4K 10-bit", ClipKind.VIDEO, "hevc", 2160, tenBit = true),
        TestClip("hevc_2160p_hdr10.mp4", "HEVC 4K HDR10", ClipKind.VIDEO, "hevc", 2160, tenBit = true, hdr = "HDR10"),
        TestClip("dv_p81_2160p.mp4", "Dolby Vision 8.1 4K", ClipKind.VIDEO, "hevc", 2160, tenBit = true, hdr = "DOVI", dolbyVisionProfile = 8),
        TestClip("dv_p5_2160p.mp4", "Dolby Vision 5 4K", ClipKind.VIDEO, "hevc", 2160, tenBit = true, hdr = "DOVI", dolbyVisionProfile = 5),
        TestClip("vp9_1080p.webm", "VP9 1080p", ClipKind.VIDEO, "vp9", 1080),
        TestClip("vp9_2160p_10bit.webm", "VP9 4K 10-bit", ClipKind.VIDEO, "vp9", 2160, tenBit = true),
        TestClip("av1_1080p.mp4", "AV1 1080p", ClipKind.VIDEO, "av1", 1080),
        TestClip("av1_2160p_10bit.mp4", "AV1 4K 10-bit", ClipKind.VIDEO, "av1", 2160, tenBit = true),
        TestClip("mpeg2_1080p.ts", "MPEG-2 1080p", ClipKind.VIDEO, "mpeg2video", 1080),
        TestClip("aac_stereo.m4a", "AAC stereo", ClipKind.AUDIO, "aac"),
        TestClip("mp3_stereo.mp3", "MP3 stereo", ClipKind.AUDIO, "mp3"),
        TestClip("ac3_51.mka", "Dolby Digital 5.1", ClipKind.AUDIO, "ac3"),
        TestClip("eac3_51.mka", "Dolby Digital Plus 5.1", ClipKind.AUDIO, "eac3"),
        TestClip("dts_51.mka", "DTS 5.1", ClipKind.AUDIO, "dts"),
        TestClip("truehd_51.mka", "Dolby TrueHD 5.1", ClipKind.AUDIO, "truehd"),
        TestClip("opus_stereo.webm", "Opus stereo", ClipKind.AUDIO, "opus"),
        TestClip("vorbis_stereo.ogg", "Vorbis stereo", ClipKind.AUDIO, "vorbis"),
        TestClip("flac_stereo.flac", "FLAC stereo", ClipKind.AUDIO, "flac"),
    )

    /**
     * Turns test results into settings changes, attributing each failure to the narrowest cause:
     * a resolution limit, a bit-depth limit, an HDR format, or the codec as a whole. Only proposes
     * changes that differ from [current].
     */
    fun suggestions(results: List<ClipResult>, caps: DeviceCapabilities, current: PlaybackSettings): List<Suggestion> {
        val out = mutableListOf<Suggestion>()

        for ((codec, codecResults) in results.filter { it.clip.kind == ClipKind.VIDEO }.groupBy { it.clip.codec }) {
            val sdr = codecResults.filter { it.clip.hdr == null }
            val base = sdr.minWithOrNull(compareBy({ it.clip.height }, { it.clip.tenBit })) ?: continue
            val name = base.clip.label.substringBefore(' ')

            if (!base.ok) {
                if (current.overrides[codec] != Override.NEVER) {
                    out += Suggestion("Never direct play $name: even ${base.clip.label} ${base.outcome.label.lowercase()}") {
                        it.copy(overrides = it.overrides + (codec to Override.NEVER))
                    }
                }
                continue
            }
            if (codec !in caps.video && current.overrides[codec] != Override.ALWAYS) {
                out += Suggestion("Always allow $name: it plays even though the TV doesn't report it") {
                    it.copy(overrides = it.overrides + (codec to Override.ALWAYS))
                }
            }

            // Highest passing height per bit depth tells resolution limits apart from bit-depth ones.
            // A passing HDR clip counts too: it proves the decoder handles that depth and size.
            val passedHeight = { tenBit: Boolean -> codecResults.filter { it.ok && it.clip.tenBit == tenBit }.maxOfOrNull { it.clip.height } }
            for (failed in sdr.filter { !it.ok }) {
                val lowerPass = passedHeight(failed.clip.tenBit)?.takeIf { it < failed.clip.height }
                when {
                    lowerPass != null && (current.codecMaxHeight[codec] ?: Int.MAX_VALUE) > lowerPass ->
                        out += Suggestion("Limit $name to ${lowerPass}p: ${failed.clip.label} ${failed.outcome.label.lowercase()}") {
                            it.copy(codecMaxHeight = it.codecMaxHeight + (codec to lowerPass))
                        }
                    lowerPass == null && failed.clip.tenBit && passedHeight(false) != null && codec !in current.eightBitOnly ->
                        out += Suggestion("Limit $name to 8-bit: ${failed.clip.label} ${failed.outcome.label.lowercase()}") {
                            it.copy(eightBitOnly = it.eightBitOnly + codec)
                        }
                }
            }

            // Dolby Vision: the TV claims it, but a clip fails or plays without Dolby Vision.
            val dvId = PlaybackSettings.hdrId("DOVI")
            val dvProblem = codecResults.firstOrNull { it.clip.hdr == "DOVI" && it.clip.claimed(caps) && !it.ok }
            if (dvProblem != null && current.overrides[dvId] != Override.NEVER) {
                out += Suggestion("Never direct play Dolby Vision: ${dvProblem.clip.label} ${dvProblem.outcome.label.lowercase()}; the server will send the HDR10 layer or tone-map") {
                    it.copy(overrides = it.overrides + (dvId to Override.NEVER))
                }
            }

            // HDR clips that fail while an SDR clip of the same depth passes point at the HDR format.
            for (failed in codecResults.filter { it.clip.hdr != null && it.clip.hdr != "DOVI" && !it.ok }) {
                val sdrTwin = sdr.any { it.ok && it.clip.tenBit == failed.clip.tenBit && it.clip.height >= failed.clip.height }
                val id = PlaybackSettings.hdrId(failed.clip.hdr!!)
                if (sdrTwin && current.overrides[id] != Override.NEVER) {
                    out += Suggestion("Never direct play ${failed.clip.hdr}: ${failed.clip.label} ${failed.outcome.label.lowercase()}; the server will tone-map") {
                        it.copy(overrides = it.overrides + (id to Override.NEVER))
                    }
                }
            }
        }

        for (result in results.filter { it.clip.kind == ClipKind.AUDIO }) {
            val codec = result.clip.codec
            val claimed = result.clip.claimed(caps)
            when {
                !result.ok && claimed && current.overrides[codec] != Override.NEVER ->
                    out += Suggestion("Never direct play ${result.clip.label}: the TV reports it but it fails") {
                        it.copy(overrides = it.overrides + (codec to Override.NEVER))
                    }
                result.ok && !claimed && current.overrides[codec] != Override.ALWAYS ->
                    out += Suggestion("Always allow ${result.clip.label}: it plays even though the TV doesn't report it") {
                        it.copy(overrides = it.overrides + (codec to Override.ALWAYS))
                    }
            }
        }
        return out
    }
}

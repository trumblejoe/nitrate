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
    /** Dolby Vision profile for DOVI clips (5, 7 or 8). */
    val dolbyVisionProfile: Int? = null,
    /** Profile 7: the way it's played in this run (as Dolby Vision 7, converted to 8.1, or its HDR10 base layer), as real files are. */
    val profile7: Profile7Playback? = null,
    /** Profile 7: which kind the clip is. */
    val layers: DolbyVisionLayers? = null,
    /** Served by the Companion (its test clip id) rather than bundled: too big to ship in the app. */
    val fromServer: String? = null,
    /** A stretch of a file in the user's own library (its item id): the hardest of its format ([PlaybackTest.librarySample]). */
    val fromLibrary: String? = null,
    /** Where the stretch starts, for [fromLibrary]. */
    val startMs: Long = 0,
) {
    val uri get() = "asset:///testclips/$asset"

    /** What its result is kept under: the file, and for profile 7 the way it was played. */
    val key: String get() = asset + (profile7?.let { "#${it.name}" } ?: "")

    /** Whether the device's reported capabilities say this clip should play. */
    fun claimed(caps: DeviceCapabilities): Boolean = when (kind) {
        ClipKind.AUDIO -> caps.audio[codec]?.supported == true
        ClipKind.VIDEO -> if (profile7 != null) {
            val v = caps.video[codec]
            val decodes = v != null && v.maxHeight >= height && (!tenBit || v.tenBit)
            decodes && when (profile7) {
                Profile7Playback.DOLBY_VISION -> 7 in caps.dolbyVisionProfiles
                Profile7Playback.AS_PROFILE_8 -> 8 in caps.dolbyVisionProfiles && caps.dolbyVisionConverter
                Profile7Playback.BASE_LAYER -> "HDR10" in caps.hdr
            }
        } else {
            val v = caps.video[codec]
            val dv = dolbyVisionProfile == null || dolbyVisionProfile in caps.dolbyVisionProfiles
            v != null && v.maxHeight >= height && (!tenBit || v.tenBit) && (hdr == null || hdr in caps.hdr) && dv
        }
    }
}

/**
 * The hardest file to decode of one format in the user's library (the Companion's format index):
 * the most pixels a second, then the highest bit rate. Its name is never shown (an episode's would
 * be a spoiler); it's told by its numbers.
 */
data class HeaviestFile(
    val codec: String,
    val itemId: String,
    val width: Int,
    val height: Int,
    val frameRate: Double? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val interlaced: Boolean = false,
    val runTimeMs: Long = 0,
) {
    /** "1080p, 24 Mbps, 23.976 fps". */
    val describe: String get() = listOfNotNull(
        "${height}${if (interlaced) "i" else "p"}",
        bitDepth?.takeIf { it > 8 }?.let { "$it-bit" },
        bitRate?.let { "${(it + 500_000) / 1_000_000} Mbps" },
        frameRate?.let { f -> "${(kotlin.math.round(f * 1000) / 1000).toString().removeSuffix(".0")} fps" },
    ).joinToString(", ")
}

@kotlinx.serialization.Serializable
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
    val softwareDecoder get() = decoder?.let { it.startsWith("c2.android.") || it.startsWith("OMX.google.") || it.startsWith("nitrate.ffmpeg.") || it.startsWith(DeviceCapabilities.SOFTWARE_VIDEO_DECODER) } == true
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

    /** How long a stretch of a library file plays ([librarySample]). */
    const val LIBRARY_SAMPLE_MS = 20_000L

    /**
     * More than this share of frames dropped and a library sample is too slow: a film should play
     * without visible stutter, so this is far stricter than the bundled clips' "it decodes at all".
     */
    const val LIBRARY_MAX_DROPPED_SHARE = 0.02

    /** What a library sample's result is kept under in [DeviceCheck.clips]. */
    fun libraryKey(codec: String) = "library:$codec"

    /** What the device check measured for [codec] on the library's hardest file, in words; null if it wasn't. */
    fun measured(check: DeviceCheck?, codec: String): String? {
        val r = check?.clips?.get(libraryKey(codec)) ?: return null
        val frames = (r.shown + r.dropped).takeIf { it > 0 }?.let { " (${r.dropped} of $it frames dropped)" }.orEmpty()
        return when (r.outcome) {
            Outcome.PASSED, Outcome.FALLBACK -> "Device check: your hardest ${codecLabel(codec)} file played smoothly here$frames"
            Outcome.SLOW -> "Device check: your hardest ${codecLabel(codec)} file was too much for this device$frames; Server converts suits it"
            Outcome.FAILED -> "Device check: your hardest ${codecLabel(codec)} file didn't play here; Server converts suits it"
        }
    }

    /** Video formats this device decodes only in software (no hardware decoder of its own). */
    fun softwareOnly(caps: DeviceCapabilities): List<String> = caps.video.values.filter { !it.hardware }.map { it.codec }.distinct().sorted()

    /**
     * A stretch of [file], the hardest of its format in the library: [LIBRARY_SAMPLE_MS] from a third
     * of the way in (past any quiet opening), timed by dropped frames like a real watch.
     */
    fun librarySample(file: HeaviestFile): TestClip = TestClip(
        asset = libraryKey(file.codec),
        label = "Your hardest ${codecLabel(file.codec)} file (${file.describe})",
        kind = ClipKind.VIDEO,
        codec = file.codec,
        height = file.height,
        tenBit = (file.bitDepth ?: 8) > 8,
        fromLibrary = file.itemId,
        startMs = if (file.runTimeMs > 3 * LIBRARY_SAMPLE_MS) (file.runTimeMs / 3 / 1000) * 1000 else 0,
    )

    /** The heavy FEL clip's id on the Companion (disc-like bitrate and frame sizes, too big for the app). */
    const val HEAVY_FEL_CLIP = "dolby_vision_7_fel_heavy"

    /**
     * Dual-layer Dolby Vision (profile 7), each kind played the ways real files can be: converted to
     * 8.1 and as its HDR10 base layer, and as Dolby Vision 7 itself on a device that reports a
     * profile 7 decoder ([native]). Their results decide how profile 7 plays here
     * ([Profile7Choice]). [heavy]: with the Companion's disc-like FEL clip too.
     */
    fun profile7Clips(heavy: Boolean, native: Boolean = false): List<TestClip> = buildList {
        val ways = listOfNotNull(
            (Profile7Playback.DOLBY_VISION to "as Dolby Vision 7").takeIf { native },
            Profile7Playback.AS_PROFILE_8 to "converted",
            Profile7Playback.BASE_LAYER to "base layer",
        )
        fun both(asset: String, name: String, layers: DolbyVisionLayers, server: String? = null) = ways.forEach { (way, word) ->
            add(TestClip(asset, "$name ($word)", ClipKind.VIDEO, "hevc", 2160, tenBit = true, hdr = "DOVI", dolbyVisionProfile = 7, profile7 = way, layers = layers, fromServer = server))
        }
        both("dv_p7_mel_2160p.mkv", "Dolby Vision 7 MEL 4K", DolbyVisionLayers.MEL)
        both("dv_p7_fel_2160p.mkv", "Dolby Vision 7 FEL 4K", DolbyVisionLayers.FEL)
        if (heavy) both("$HEAVY_FEL_CLIP.mkv", "Dolby Vision 7 FEL 4K, disc-like", DolbyVisionLayers.FEL, HEAVY_FEL_CLIP)
    }

    /**
     * Turns test results into settings changes, attributing each failure to the narrowest cause:
     * a resolution limit, a bit-depth limit, an HDR format, or the codec as a whole. Only proposes
     * changes that differ from [current].
     */
    fun suggestions(all: List<ClipResult>, caps: DeviceCapabilities, current: PlaybackSettings): List<Suggestion> {
        // Profile 7 clips aren't for settings: their results choose how profile 7 plays by themselves (Profile7Choice).
        val results = all.filter { it.clip.profile7 == null && it.clip.fromLibrary == null }
        val out = mutableListOf<Suggestion>()

        // The library's hardest file of a software-only format, too much for this device: the server converts that format here.
        for (r in all.filter { it.clip.fromLibrary != null && !it.ok }) {
            val codec = r.clip.codec
            if (current.overrides[codec] == Override.NEVER) continue
            out += Suggestion("Have the server convert ${codecLabel(codec)} here: ${r.clip.label.replaceFirstChar { it.lowercase() }}: ${r.detail.replaceFirstChar { it.lowercase() }}") {
                it.copy(overrides = it.overrides + (codec to Override.NEVER))
            }
        }

        for ((codec, codecResults) in results.filter { it.clip.kind == ClipKind.VIDEO }.groupBy { it.clip.codec }) {
            val sdr = codecResults.filter { it.clip.hdr == null }
            val base = sdr.minWithOrNull(compareBy({ it.clip.height }, { it.clip.tenBit })) ?: continue
            val name = base.clip.label.substringBefore(' ')

            if (!base.ok) {
                if (current.overrides[codec] != Override.NEVER) {
                    out += Suggestion("Have the server convert $name: even ${base.clip.label} ${base.outcome.label.lowercase()}") {
                        it.copy(overrides = it.overrides + (codec to Override.NEVER))
                    }
                }
                continue
            }
            if (codec !in caps.video && current.overrides[codec] != Override.ALWAYS) {
                out += Suggestion("Play $name on this device: it plays even though the TV doesn't report it") {
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

            // The TV says 8-bit only, but a 10-bit clip played: let the server send 10-bit.
            val tenBitPass = sdr.firstOrNull { it.ok && it.clip.tenBit }
            if (tenBitPass != null && caps.video[codec]?.tenBit == false && caps.video[codec]?.softwareTenBit != true && codec !in current.tenBitAllowed && codec !in current.eightBitOnly) {
                out += Suggestion("Allow 10-bit $name: ${tenBitPass.clip.label} played, though the TV doesn't report 10-bit") {
                    it.copy(tenBitAllowed = it.tenBitAllowed + codec)
                }
            }

            // Software-only decoders stop at 1080p, unless a bigger clip played smoothly.
            val bigPass = sdr.filter { it.ok && it.clip.height > CapabilityRules.SOFTWARE_MAX_HEIGHT }.maxByOrNull { it.clip.height }
            if (bigPass != null && caps.video[codec]?.hardware == false && current.overrides[codec] == null) {
                out += Suggestion("Allow $name above 1080p: ${bigPass.clip.label} played smoothly on the software decoder") {
                    it.copy(overrides = it.overrides + (codec to Override.ALWAYS))
                }
            }

            // Dolby Vision: the TV claims it, but a clip fails or plays without Dolby Vision.
            val dvId = PlaybackSettings.hdrId("DOVI")
            val dvProblem = codecResults.firstOrNull { it.clip.hdr == "DOVI" && it.clip.claimed(caps) && !it.ok }
            if (dvProblem != null && current.overrides[dvId] != Override.NEVER) {
                out += Suggestion("Have the server convert Dolby Vision: ${dvProblem.clip.label} ${dvProblem.outcome.label.lowercase()}; the server will send the HDR10 layer or tone-map") {
                    it.copy(overrides = it.overrides + (dvId to Override.NEVER))
                }
            }

            // HDR clips that fail while an SDR clip of the same depth passes point at the HDR format.
            for (failed in codecResults.filter { it.clip.hdr != null && it.clip.hdr != "DOVI" && !it.ok }) {
                val sdrTwin = sdr.any { it.ok && it.clip.tenBit == failed.clip.tenBit && it.clip.height >= failed.clip.height }
                val id = PlaybackSettings.hdrId(failed.clip.hdr!!)
                if (sdrTwin && current.overrides[id] != Override.NEVER) {
                    out += Suggestion("Have the server convert ${failed.clip.hdr}: ${failed.clip.label} ${failed.outcome.label.lowercase()}; the server will tone-map") {
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
                    out += Suggestion("Have the server convert ${result.clip.label}: the TV reports it but it fails") {
                        it.copy(overrides = it.overrides + (codec to Override.NEVER))
                    }
                result.ok && !claimed && current.overrides[codec] != Override.ALWAYS ->
                    out += Suggestion("Play ${result.clip.label} on this device: it plays even though the TV doesn't report it") {
                        it.copy(overrides = it.overrides + (codec to Override.ALWAYS))
                    }
            }
        }
        return out
    }
}

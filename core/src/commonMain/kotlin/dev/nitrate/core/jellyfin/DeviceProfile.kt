package dev.nitrate.core.jellyfin

import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettings
import kotlinx.serialization.Serializable

@Serializable
data class ProfileConditionDto(val condition: String, val property: String, val value: String, val isRequired: Boolean = true)

@Serializable
data class DirectPlayProfileDto(val container: String, val audioCodec: String? = null, val videoCodec: String? = null, val type: String)

@Serializable
data class TranscodingProfileDto(
    val container: String,
    val type: String,
    val videoCodec: String,
    val audioCodec: String,
    val protocol: String,
    val context: String = "Streaming",
    val minSegments: Int = 0,
    val breakOnNonKeyFrames: Boolean = false,
    val conditions: List<ProfileConditionDto> = emptyList(),
)

@Serializable
data class CodecProfileDto(
    val type: String,
    val codec: String,
    val conditions: List<ProfileConditionDto>,
    val applyConditions: List<ProfileConditionDto> = emptyList(),
)

@Serializable
data class SubtitleProfileDto(val format: String, val method: String)

/** Jellyfin's DeviceProfile: what this device can play, for PlaybackInfo negotiation. */
@Serializable
data class DeviceProfileDto(
    val name: String,
    val maxStreamingBitrate: Int,
    val maxStaticBitrate: Int,
    val directPlayProfiles: List<DirectPlayProfileDto>,
    val transcodingProfiles: List<TranscodingProfileDto>,
    val containerProfiles: List<String> = emptyList(),
    val codecProfiles: List<CodecProfileDto>,
    val subtitleProfiles: List<SubtitleProfileDto>,
)

/** Builds the Jellyfin device profile from [CapabilityRules]. */
object JellyfinDeviceProfile {
    /**
     * What Media3 reads itself: MP4, Matroska, MPEG-TS, and (Media3's AVI, MPEG program stream and
     * FLV extractors) AVI, .mpg/.vob and FLV, so those play as they are instead of being repackaged
     * by the server. The codecs inside still have to be ones the device decodes.
     */
    private const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts,m2ts,avi,mpg,mpeg,vob,flv"
    private const val MPV_VIDEO_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts,m2ts,avi,wmv,asf,flv,mpg,mpeg,vob,ogv,3gp"
    private const val AUDIO_CONTAINERS = "mp3,flac,m4a,mp4,aac,ogg,oga,opus,webm,wav,mka"
    private const val APPLE_VIDEO_CONTAINERS = "mp4,m4v,mov"
    private const val APPLE_AUDIO_CONTAINERS = "mp3,m4a,m4b,mp4,aac,flac,wav,aif,aiff"

    fun create(caps: DeviceCapabilities, settings: PlaybackSettings): DeviceProfileDto {
        val video = CapabilityRules.videoCodecs(caps, settings).filter { it.allowed }.map { it.id }
        val audio = CapabilityRules.audioCodecs(caps, settings).filter { it.allowed }.map { it.id } + listOf("pcm_s16le", "pcm_s24le")
        val ranges = CapabilityRules.allowedRangeTypes(caps, settings)

        val apple = caps.engine == dev.nitrate.core.playback.PlayerEngine.APPLE
        val directPlay = if (apple) listOf(
            DirectPlayProfileDto(APPLE_VIDEO_CONTAINERS, audio.joinToString(","), video.joinToString(","), "Video"),
            DirectPlayProfileDto(APPLE_AUDIO_CONTAINERS, audio.joinToString(","), null, "Audio"),
        ) else buildList {
            // Codecs Media3 reads only from Matroska are direct played from MKV only (mpv reads them anywhere).
            val everywhere = if (caps.engine == dev.nitrate.core.playback.PlayerEngine.MPV) video else video.filterNot { it in DeviceCapabilities.MATROSKA_ONLY_VIDEO }
            if (everywhere.isNotEmpty()) {
                add(DirectPlayProfileDto(if (caps.engine == dev.nitrate.core.playback.PlayerEngine.MPV) MPV_VIDEO_CONTAINERS else DIRECT_PLAY_CONTAINERS, audio.joinToString(","), everywhere.joinToString(","), "Video"))
            }
            if (everywhere.size < video.size) {
                add(DirectPlayProfileDto("mkv,webm", audio.joinToString(","), video.joinToString(","), "Video"))
            }
            add(DirectPlayProfileDto(AUDIO_CONTAINERS, audio.joinToString(","), null, "Audio"))
        }

        // Prefer HEVC output when allowed: half the bitrate of H.264 for the same quality.
        val transcodeVideo = listOfNotNull("hevc".takeIf { it in video }, "h264").joinToString(",")
        val transcodeAudio = listOf("aac", "ac3", "eac3").filter { it in audio }.ifEmpty { listOf("aac") }.joinToString(",")
        val transcoding = listOf(
            // AVPlayer takes HEVC and HDR only in fragmented MP4 segments.
            TranscodingProfileDto(if (apple) "mp4" else "ts", "Video", transcodeVideo, transcodeAudio, "hls", minSegments = 1, breakOnNonKeyFrames = true),
            if (apple) TranscodingProfileDto("aac", "Audio", "", "aac", "hls", minSegments = 1) else TranscodingProfileDto("mp3", "Audio", "", "mp3", "http"),
        )

        val codecProfiles = CapabilityRules.videoLimits(caps, settings).flatMap { limit ->
            listOfNotNull(
                CodecProfileDto(
                    type = "Video",
                    codec = limit.codec,
                    conditions = listOfNotNull(
                        ProfileConditionDto("LessThanEqual", "Width", limit.maxWidth.toString()),
                        ProfileConditionDto("LessThanEqual", "Height", limit.maxHeight.toString()),
                        ProfileConditionDto("LessThanEqual", "VideoBitDepth", if (limit.tenBit) "10" else "8"),
                        // Other ranges are tone-mapped by the server rather than shown with wrong colours.
                        ProfileConditionDto("EqualsAny", "VideoRangeType", ranges.joinToString("|")),
                        // Software decoding doesn't deinterlace; the server does when converting.
                        ProfileConditionDto("NotEquals", "IsInterlaced", "true").takeIf { limit.progressiveOnly },
                    ) + (if (limit.softwareProfiles) emptyList() else limit.missingProfiles.sorted().map {
                        // Profiles no decoder here takes (H.264 4:2:2 from cameras, HEVC 4:2:2/4:4:4): converted
                        // up front, rather than failing and teaching Nitrate to convert the whole codec.
                        ProfileConditionDto("NotEquals", "VideoProfile", it)
                    }),
                ),
                // Those profiles in Nitrate's software decoder instead, which keeps to 1080p progressive.
                limit.missingProfiles.takeIf { limit.softwareProfiles && it.isNotEmpty() }?.let { missing ->
                    CodecProfileDto(
                        type = "Video",
                        codec = limit.codec,
                        applyConditions = listOf(ProfileConditionDto("EqualsAny", "VideoProfile", missing.sorted().joinToString("|"))),
                        conditions = listOf(
                            ProfileConditionDto("LessThanEqual", "Width", "1920"),
                            ProfileConditionDto("LessThanEqual", "Height", CapabilityRules.SOFTWARE_MAX_HEIGHT.toString()),
                            ProfileConditionDto("NotEquals", "IsInterlaced", "true"),
                        ),
                    )
                },
                // 10-bit only from the software decoder, which keeps to 1080p.
                limit.tenBitMaxHeight?.let { h ->
                    CodecProfileDto(
                        type = "Video",
                        codec = limit.codec,
                        applyConditions = listOf(ProfileConditionDto("GreaterThanEqual", "VideoBitDepth", "9")),
                        conditions = listOf(
                            ProfileConditionDto("LessThanEqual", "Width", (h * 16 / 9).toString()),
                            ProfileConditionDto("LessThanEqual", "Height", h.toString()),
                            ProfileConditionDto("NotEquals", "IsInterlaced", "true"),
                        ),
                    )
                },
            )
        }

        // Text formats the player renders itself; image formats (PGS, VobSub) work embedded in MKV
        // but must be burned in when transcoding.
        // Styled subtitles shown exactly: the server draws them into the picture (libass), fonts and all.
        // mpv draws styled subtitles exactly itself; the others may have the server draw them in.
        val exact = settings.subtitleStyle.styled == dev.nitrate.core.playback.StyledSubtitles.EXACT && caps.engine != dev.nitrate.core.playback.PlayerEngine.MPV
        val subtitles = if (apple) appleSubtitles() else buildList {
            for (format in listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "ttml")) {
                if (exact && dev.nitrate.core.playback.isStyledSubtitle(format)) {
                    add(SubtitleProfileDto(format, "Encode"))
                    continue
                }
                add(SubtitleProfileDto(format, "External"))
                add(SubtitleProfileDto(format, "Embed"))
            }
            for (format in listOf("pgssub", "dvdsub", "dvbsub")) {
                add(SubtitleProfileDto(format, "Embed"))
                add(SubtitleProfileDto(format, "Encode"))
            }
        }

        return DeviceProfileDto(
            name = "Nitrate (${caps.model})",
            maxStreamingBitrate = settings.maxBitrate,
            maxStaticBitrate = settings.maxBitrate,
            directPlayProfiles = directPlay,
            transcodingProfiles = transcoding,
            codecProfiles = codecProfiles,
            subtitleProfiles = subtitles,
        )
    }

    /**
     * AVPlayer shows subtitles only as WebVTT inside an HLS stream; anything else (styled ASS,
     * PGS, VobSub) is drawn into the picture by the server.
     */
    private fun appleSubtitles(): List<SubtitleProfileDto> = buildList {
        for (format in listOf("srt", "subrip", "vtt", "webvtt", "ttml")) {
            add(SubtitleProfileDto(format, "Hls"))
            add(SubtitleProfileDto(format, "Encode"))
        }
        for (format in listOf("ass", "ssa", "pgssub", "dvdsub", "dvbsub")) add(SubtitleProfileDto(format, "Encode"))
    }
}

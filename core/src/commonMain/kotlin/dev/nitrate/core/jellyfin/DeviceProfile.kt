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
    private const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts,m2ts"
    private const val AUDIO_CONTAINERS = "mp3,flac,m4a,mp4,aac,ogg,oga,opus,webm,wav,mka"

    fun create(caps: DeviceCapabilities, settings: PlaybackSettings): DeviceProfileDto {
        val video = CapabilityRules.videoCodecs(caps, settings).filter { it.allowed }.map { it.id }
        val audio = CapabilityRules.audioCodecs(caps, settings).filter { it.allowed }.map { it.id } + listOf("pcm_s16le", "pcm_s24le")
        val ranges = CapabilityRules.allowedRangeTypes(caps, settings)

        val directPlay = buildList {
            if (video.isNotEmpty()) {
                add(DirectPlayProfileDto(DIRECT_PLAY_CONTAINERS, audio.joinToString(","), video.joinToString(","), "Video"))
            }
            add(DirectPlayProfileDto(AUDIO_CONTAINERS, audio.joinToString(","), null, "Audio"))
        }

        // Prefer HEVC output when allowed: half the bitrate of H.264 for the same quality.
        val transcodeVideo = listOfNotNull("hevc".takeIf { it in video }, "h264").joinToString(",")
        val transcodeAudio = listOf("aac", "ac3", "eac3").filter { it in audio }.ifEmpty { listOf("aac") }.joinToString(",")
        val transcoding = listOf(
            TranscodingProfileDto("ts", "Video", transcodeVideo, transcodeAudio, "hls", minSegments = 1, breakOnNonKeyFrames = true),
            TranscodingProfileDto("mp3", "Audio", "", "mp3", "http"),
        )

        val codecProfiles = CapabilityRules.videoLimits(caps, settings).map { limit ->
            CodecProfileDto(
                type = "Video",
                codec = limit.codec,
                conditions = listOf(
                    ProfileConditionDto("LessThanEqual", "Width", limit.maxWidth.toString()),
                    ProfileConditionDto("LessThanEqual", "Height", limit.maxHeight.toString()),
                    ProfileConditionDto("LessThanEqual", "VideoBitDepth", if (limit.tenBit) "10" else "8"),
                    // Other ranges are tone-mapped by the server rather than shown with wrong colours.
                    ProfileConditionDto("EqualsAny", "VideoRangeType", ranges.joinToString("|")),
                ),
            )
        }

        // Text formats the player renders itself; image formats (PGS, VobSub) work embedded in MKV
        // but must be burned in when transcoding.
        val subtitles = buildList {
            for (format in listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "ttml")) {
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
}

package dev.nitrate.android.jellyfin

import android.os.Build
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettings

/** Builds the Jellyfin DeviceProfile from [CapabilityRules], for PlaybackInfo negotiation. */
object JellyfinDeviceProfile {
    fun create(caps: DeviceCapabilities, settings: PlaybackSettings): DeviceProfile {
        val maxBitrate = settings.maxBitrate
        val video = CapabilityRules.videoCodecs(caps, settings).filter { it.allowed }.map { it.id }
        val audio = CapabilityRules.audioCodecs(caps, settings).filter { it.allowed }.map { it.id } + listOf("pcm_s16le", "pcm_s24le")
        val ranges = CapabilityRules.allowedRangeTypes(caps, settings)

        val directPlay = buildList {
            if (video.isNotEmpty()) {
                add(
                    DirectPlayProfile(
                        container = DIRECT_PLAY_CONTAINERS,
                        videoCodec = video.joinToString(","),
                        audioCodec = audio.joinToString(","),
                        type = DlnaProfileType.VIDEO,
                    ),
                )
            }
            add(DirectPlayProfile(container = AUDIO_CONTAINERS, audioCodec = audio.joinToString(","), type = DlnaProfileType.AUDIO))
        }

        // Prefer HEVC output when allowed: half the bitrate of H.264 for the same quality.
        val transcodeVideo = listOfNotNull("hevc".takeIf { it in video }, "h264").joinToString(",")
        val transcodeAudio = listOf("aac", "ac3", "eac3").filter { it in audio }.ifEmpty { listOf("aac") }.joinToString(",")
        val transcoding = listOf(
            TranscodingProfile(
                container = "ts",
                type = DlnaProfileType.VIDEO,
                videoCodec = transcodeVideo,
                audioCodec = transcodeAudio,
                protocol = MediaStreamProtocol.HLS,
                context = EncodingContext.STREAMING,
                minSegments = 1,
                breakOnNonKeyFrames = true,
                conditions = emptyList(),
            ),
            TranscodingProfile(
                container = "mp3",
                type = DlnaProfileType.AUDIO,
                videoCodec = "",
                audioCodec = "mp3",
                protocol = MediaStreamProtocol.HTTP,
                context = EncodingContext.STREAMING,
                conditions = emptyList(),
            ),
        )

        val codecProfiles = CapabilityRules.videoLimits(caps, settings).map { limit ->
            val codec = limit.codec
            val width = limit.maxWidth
            val height = limit.maxHeight
            val tenBit = limit.tenBit
            CodecProfile(
                type = CodecType.VIDEO,
                codec = codec,
                conditions = listOf(
                    condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, width.toString()),
                    condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.HEIGHT, height.toString()),
                    condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_BIT_DEPTH, if (tenBit) "10" else "8"),
                    // Other ranges are tone-mapped by the server rather than shown with wrong colours.
                    condition(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, ranges.joinToString("|")),
                ),
                applyConditions = emptyList(),
            )
        }

        // Text formats ExoPlayer renders itself; image formats (PGS, VobSub) work embedded in MKV
        // but must be burned in when transcoding.
        val subtitles = buildList {
            for (format in listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "ttml")) {
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL))
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED))
            }
            for (format in listOf("pgssub", "dvdsub", "dvbsub")) {
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED))
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.ENCODE))
            }
        }

        return DeviceProfile(
            name = "Nitrate (${Build.MANUFACTURER} ${Build.MODEL})",
            maxStreamingBitrate = maxBitrate,
            maxStaticBitrate = maxBitrate,
            directPlayProfiles = directPlay,
            transcodingProfiles = transcoding,
            containerProfiles = emptyList(),
            codecProfiles = codecProfiles,
            subtitleProfiles = subtitles,
        )
    }

    private fun condition(type: ProfileConditionType, property: ProfileConditionValue, value: String) =
        ProfileCondition(type, property, value, true)

    private const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts,m2ts"
    private const val AUDIO_CONTAINERS = "mp3,flac,m4a,mp4,aac,ogg,oga,opus,webm,wav,mka"
}

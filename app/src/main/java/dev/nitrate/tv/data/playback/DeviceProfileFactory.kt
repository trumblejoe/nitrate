package dev.nitrate.tv.data.playback

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

/** The effective, post-override view of one capability. Shared by the profile and the settings UI. */
data class EffectiveCapability(val id: String, val claimed: Boolean, val allowed: Boolean)

/**
 * Describes what this TV can play, so the server can choose between direct play, remuxing
 * (direct stream) and transcoding. Starts from what the device reports ([DeviceCapabilities]),
 * then applies the user's overrides and failures learned on this TV ([PlaybackSettings]).
 */
object DeviceProfileFactory {

    fun videoCodecs(caps: DeviceCapabilities, settings: PlaybackSettings) =
        DeviceCapabilities.VIDEO_CODECS.keys.map { EffectiveCapability(it, it in caps.video, settings.allows(it, it in caps.video)) }

    fun audioCodecs(caps: DeviceCapabilities, settings: PlaybackSettings) =
        DeviceCapabilities.AUDIO_CODECS.keys.map {
            val claimed = caps.audio[it]?.supported == true
            EffectiveCapability(it, claimed, settings.allows(it, claimed))
        }

    fun hdrFormats(caps: DeviceCapabilities, settings: PlaybackSettings) =
        DeviceCapabilities.HDR_FORMATS.map {
            EffectiveCapability(PlaybackSettings.hdrId(it), it in caps.hdr, settings.allows(PlaybackSettings.hdrId(it), it in caps.hdr))
        }

    /**
     * Jellyfin video range types that can be shown without tone mapping. Dolby Vision and HDR10+
     * files with an HDR10/HLG base layer play as that base layer when the enhanced format is off.
     */
    fun allowedRangeTypes(caps: DeviceCapabilities, settings: PlaybackSettings): List<String> {
        val allowed = hdrFormats(caps, settings).filter { it.allowed }.map { it.id.removePrefix("range:") }.toSet()
        return buildList {
            add("SDR")
            add("DOVIWithSDR")
            if ("HDR10" in allowed) addAll(listOf("HDR10", "DOVIWithHDR10", "HDR10Plus", "DOVIWithHDR10Plus"))
            if ("HDR10Plus" in allowed) add("HDR10Plus")
            if ("HLG" in allowed) addAll(listOf("HLG", "DOVIWithHLG"))
            if ("DOVI" in allowed) addAll(listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithHDR10Plus"))
        }.distinct()
    }

    fun create(caps: DeviceCapabilities, settings: PlaybackSettings): DeviceProfile {
        val maxBitrate = settings.maxBitrate
        val video = videoCodecs(caps, settings).filter { it.allowed }.map { it.id }
        val audio = audioCodecs(caps, settings).filter { it.allowed }.map { it.id } + listOf("pcm_s16le", "pcm_s24le")
        val ranges = allowedRangeTypes(caps, settings)

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

        val userWidth = settings.maxResolution.width ?: Int.MAX_VALUE
        val userHeight = settings.maxResolution.height ?: Int.MAX_VALUE
        val codecProfiles = video.map { codec ->
            // A codec forced on that the device doesn't report gets 4K (or the panel size) and 10-bit.
            val c = caps.video[codec]
            val width = minOf(c?.maxWidth ?: maxOf(caps.displayWidth, 3840), userWidth)
            val height = minOf(c?.maxHeight ?: maxOf(caps.displayHeight, 2160), userHeight, settings.codecMaxHeight[codec] ?: Int.MAX_VALUE)
            val tenBit = (c?.tenBit ?: true) && codec !in settings.eightBitOnly
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

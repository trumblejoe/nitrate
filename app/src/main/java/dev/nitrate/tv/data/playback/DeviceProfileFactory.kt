package dev.nitrate.tv.data.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
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

/**
 * Describes what this TV can play, so the server can decide between direct play,
 * remuxing (direct stream) and transcoding. Built from the device's real decoders
 * rather than a hard-coded list, because TV hardware varies wildly.
 */
class DeviceProfileFactory(private val context: Context) {

    /** Capabilities of one decodable video codec. */
    private data class VideoCaps(val maxWidth: Int, val maxHeight: Int, val tenBit: Boolean)

    private val decoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    }

    private fun hasDecoder(mime: String) = decoders.any { info -> info.supportedTypes.any { it.equals(mime, true) } }

    private fun videoCaps(mime: String): VideoCaps? {
        var best: VideoCaps? = null
        for (info in decoders) {
            val type = info.supportedTypes.firstOrNull { it.equals(mime, true) } ?: continue
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
            val video = caps.videoCapabilities ?: continue
            val tenBit = caps.profileLevels.any { it.profile in TEN_BIT_PROFILES[mime].orEmpty() }
            val candidate = VideoCaps(video.supportedWidths.upper, video.supportedHeights.upper, tenBit)
            if (best == null || candidate.maxWidth * candidate.maxHeight > best.maxWidth * best.maxHeight) {
                best = candidate
            }
        }
        return best
    }

    private fun displaySupportsHdr(): Boolean {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        @Suppress("DEPRECATION")
        val types = display.hdrCapabilities?.supportedHdrTypes ?: return false
        return types.isNotEmpty()
    }

    fun create(maxBitrate: Int): DeviceProfile {
        val video = VIDEO_CODECS.mapNotNull { (jellyfinName, mime) -> videoCaps(mime)?.let { jellyfinName to it } }.toMap()
        val audio = AUDIO_CODECS.filter { (_, mime) -> mime == null || hasDecoder(mime) }.keys.toList()
        val hdr = displaySupportsHdr()

        val videoCodecs = video.keys.joinToString(",")
        val audioCodecs = audio.joinToString(",")

        val directPlay = listOf(
            DirectPlayProfile(
                container = DIRECT_PLAY_CONTAINERS,
                videoCodec = videoCodecs,
                audioCodec = audioCodecs,
                type = DlnaProfileType.VIDEO,
            ),
        )

        // Prefer HEVC output when the device can decode it: half the bitrate for the same quality.
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
        )

        val codecProfiles = video.map { (codec, caps) ->
            CodecProfile(
                type = CodecType.VIDEO,
                codec = codec,
                conditions = buildList {
                    add(condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, caps.maxWidth))
                    add(condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.HEIGHT, caps.maxHeight))
                    add(condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_BIT_DEPTH, if (caps.tenBit) 10 else 8))
                    // Without an HDR display, let the server tone-map rather than showing washed-out colours.
                    if (!hdr) add(ProfileCondition(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, "SDR", true))
                },
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

    private fun condition(type: ProfileConditionType, property: ProfileConditionValue, value: Int) =
        ProfileCondition(type, property, value.toString(), true)

    private companion object {
        /** Jellyfin codec name to Android MIME type. Order matters: first match is preferred. */
        val VIDEO_CODECS = linkedMapOf(
            "av1" to "video/av01",
            "hevc" to "video/hevc",
            "vp9" to "video/x-vnd.on2.vp9",
            "h264" to "video/avc",
            "vp8" to "video/x-vnd.on2.vp8",
            "mpeg2video" to "video/mpeg2",
            "mpeg4" to "video/mp4v-es",
        )

        /** Null MIME means ExoPlayer handles it without a platform decoder. */
        val AUDIO_CODECS = linkedMapOf(
            "aac" to "audio/mp4a-latm",
            "mp3" to "audio/mpeg",
            "ac3" to "audio/ac3",
            "eac3" to "audio/eac3",
            "truehd" to "audio/true-hd",
            "dts" to "audio/vnd.dts",
            "opus" to "audio/opus",
            "vorbis" to "audio/vorbis",
            "flac" to "audio/flac",
            "alac" to "audio/alac",
            "pcm_s16le" to null,
            "pcm_s24le" to null,
        )

        val TEN_BIT_PROFILES = mapOf(
            "video/hevc" to setOf(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            ),
            "video/x-vnd.on2.vp9" to setOf(
                MediaCodecInfo.CodecProfileLevel.VP9Profile2,
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
            ),
            "video/av01" to setOf(
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
            ),
        )

        const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts,m2ts"
    }
}

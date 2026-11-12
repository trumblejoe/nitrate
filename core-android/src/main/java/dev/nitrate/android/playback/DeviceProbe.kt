package dev.nitrate.android.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import dev.nitrate.core.playback.AudioCodecCaps
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.VideoCodecCaps

/** Reads what this Android device *claims* it can play: decoders, display HDR, HDMI passthrough. */
object DeviceProbe {
    /** Whether the named decoder handles Dolby Vision (as opposed to plain HEVC fallback). */
    fun isDolbyVisionDecoder(name: String?): Boolean = name != null &&
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            info.name == name && info.supportedTypes.any { it.equals(DeviceCapabilities.DOLBY_VISION_MIME, true) }
        }

    /** Media3 encodings for bitstreaming a codec over HDMI. */
    private val PASSTHROUGH = mapOf(
        "ac3" to C.ENCODING_AC3,
        "eac3" to C.ENCODING_E_AC3,
        "truehd" to C.ENCODING_DOLBY_TRUEHD,
        "dts" to C.ENCODING_DTS,
    )

    private val DV_PROFILE_NUMBERS = mapOf(
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr to 4,
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn to 5,
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth to 6,
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb to 7,
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt to 8,
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe to 9,
    )

    private val TEN_BIT_PROFILES = mapOf(
        // High 10 is rare in hardware, but some boxes have it.
        "video/avc" to setOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10),
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

    /** How the FFmpeg audio decoder shows up in capabilities and Playback settings. */
    const val SOFTWARE_AUDIO_DECODER = "FFmpeg (software)"

    @OptIn(UnstableApi::class)
    private fun ffmpegDecodes(mime: String): Boolean =
        runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() && androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)

    @OptIn(UnstableApi::class)
    fun probe(context: Context): DeviceCapabilities {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }

        val video = DeviceCapabilities.VIDEO_CODECS.mapNotNull { (codec, mime) ->
            decoders.mapNotNull { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(mime, true) } ?: return@mapNotNull null
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@mapNotNull null
                // Secure-only decoders play DRM streams, never an ordinary file.
                if (info.name.endsWith(".secure", ignoreCase = true) ||
                    caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                ) return@mapNotNull null
                val v = caps.videoCapabilities ?: return@mapNotNull null
                VideoCodecCaps(
                    codec = codec,
                    mime = mime,
                    decoderName = info.name,
                    hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android"),
                    maxWidth = v.supportedWidths.upper,
                    maxHeight = v.supportedHeights.upper,
                    tenBit = caps.profileLevels.any { it.profile in TEN_BIT_PROFILES[mime].orEmpty() },
                )
            }.let(VideoCodecCaps::combine)?.let { codec to it }
        }.toMap()

        val passthrough = runCatching {
            AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        }.getOrNull()
        val audio = DeviceCapabilities.AUDIO_CODECS.map { (codec, mime) ->
            val encoding = PASSTHROUGH[codec]
            // The device's own decoder, else Nitrate's FFmpeg one (DTS, TrueHD, Dolby, ALAC) when built in.
            val decoder = decoders.firstOrNull { info -> info.supportedTypes.any { it.equals(mime, true) } }?.name
                ?: SOFTWARE_AUDIO_DECODER.takeIf { ffmpegDecodes(mime) }
            codec to AudioCodecCaps(codec, decoder, encoding != null && passthrough?.supportsEncoding(encoding) == true)
        }.toMap()

        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        // Android 14 moved HDR types onto the display mode; older ones only have the capabilities.
        @Suppress("DEPRECATION")
        val hdrTypes = (
            display?.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty() +
                (if (Build.VERSION.SDK_INT >= 34) display?.mode?.supportedHdrTypes?.toList().orEmpty() else emptyList())
            ).distinct()
        val hdr = hdrTypes.mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOVI"
                4 /* HDR_TYPE_HDR10_PLUS, API 29 */ -> "HDR10Plus"
                else -> null
            }
        }.toSet()

        val dolbyVision = decoders.flatMap { info ->
            info.supportedTypes.filter { it.equals(DeviceCapabilities.DOLBY_VISION_MIME, true) }.flatMap { type ->
                runCatching { info.getCapabilitiesForType(type).profileLevels.mapNotNull { DV_PROFILE_NUMBERS[it.profile] } }.getOrDefault(emptyList())
            }
        }.toSet()

        // Physical panel size, not the (often 1080p) UI size.
        val mode = display?.mode
        return DeviceCapabilities(
            video = video,
            audio = audio,
            hdr = hdr,
            displayWidth = mode?.physicalWidth ?: 1920,
            displayHeight = mode?.physicalHeight ?: 1080,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            dolbyVisionProfiles = dolbyVision,
        )
    }
}

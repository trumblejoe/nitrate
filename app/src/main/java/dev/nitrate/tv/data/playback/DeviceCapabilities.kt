package dev.nitrate.tv.data.playback

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

/** A video codec as the device's decoders describe it. */
data class VideoCodecCaps(
    val codec: String,
    val mime: String,
    val decoderName: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val tenBit: Boolean,
)

/** An audio codec: decoded on the device, passed through to the receiver over HDMI, or both. */
data class AudioCodecCaps(val codec: String, val decoder: String?, val passthrough: Boolean) {
    val supported get() = decoder != null || passthrough
}

/** What the TV *claims* it can play. Real behaviour can differ; see the playback test. */
data class DeviceCapabilities(
    val video: Map<String, VideoCodecCaps>,
    val audio: Map<String, AudioCodecCaps>,
    /** HDR formats the display reports, as Jellyfin video range types (HDR10, HLG, DOVI, HDR10Plus). */
    val hdr: Set<String>,
    val displayWidth: Int,
    val displayHeight: Int,
    val model: String,
) {
    companion object {
        /** Jellyfin codec name to Android MIME type. */
        val VIDEO_CODECS = linkedMapOf(
            "av1" to "video/av01",
            "hevc" to "video/hevc",
            "vp9" to "video/x-vnd.on2.vp9",
            "h264" to "video/avc",
            "vp8" to "video/x-vnd.on2.vp8",
            "mpeg2video" to "video/mpeg2",
            "mpeg4" to "video/mp4v-es",
        )

        /** Jellyfin codec name to (Android MIME, Media3 passthrough encoding or null). */
        val AUDIO_CODECS = linkedMapOf(
            "aac" to ("audio/mp4a-latm" to null),
            "mp3" to ("audio/mpeg" to null),
            "ac3" to ("audio/ac3" to C.ENCODING_AC3),
            "eac3" to ("audio/eac3" to C.ENCODING_E_AC3),
            "truehd" to ("audio/true-hd" to C.ENCODING_DOLBY_TRUEHD),
            "dts" to ("audio/vnd.dts" to C.ENCODING_DTS),
            "opus" to ("audio/opus" to null),
            "vorbis" to ("audio/vorbis" to null),
            "flac" to ("audio/flac" to null),
            "alac" to ("audio/alac" to null),
        )

        val HDR_FORMATS = listOf("HDR10", "HDR10Plus", "HLG", "DOVI")

        private val TEN_BIT_PROFILES = mapOf(
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

        @OptIn(UnstableApi::class)
        fun probe(context: Context): DeviceCapabilities {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }

            val video = VIDEO_CODECS.mapNotNull { (codec, mime) ->
                decoders.mapNotNull { info ->
                    val type = info.supportedTypes.firstOrNull { it.equals(mime, true) } ?: return@mapNotNull null
                    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@mapNotNull null
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
                }.maxByOrNull { it.maxWidth.toLong() * it.maxHeight + if (it.hardware) 1 else 0 }?.let { codec to it }
            }.toMap()

            val passthrough = runCatching {
                AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            }.getOrNull()
            val audio = AUDIO_CODECS.map { (codec, spec) ->
                val (mime, encoding) = spec
                val decoder = decoders.firstOrNull { info -> info.supportedTypes.any { it.equals(mime, true) } }?.name
                codec to AudioCodecCaps(codec, decoder, encoding != null && passthrough?.supportsEncoding(encoding) == true)
            }.toMap()

            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
            val hdr = hdrTypes.mapNotNull {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOVI"
                    4 /* HDR_TYPE_HDR10_PLUS, API 29 */ -> "HDR10Plus"
                    else -> null
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
            )
        }
    }
}

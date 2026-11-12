package dev.nitrate.core.playback

import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind

/** The effective, post-override view of one capability. Shared by the profile and the settings UI. */
data class EffectiveCapability(val id: String, val claimed: Boolean, val allowed: Boolean)

/**
 * What this device may direct play: what it reports ([DeviceCapabilities]) with the user's
 * overrides, limits and learned failures ([PlaybackSettings]) applied. Every platform's
 * server profile is built from these rules.
 */
object CapabilityRules {

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

    /** Size and bit-depth limit for direct playing one video codec. */
    data class VideoLimit(val codec: String, val maxWidth: Int, val maxHeight: Int, val tenBit: Boolean)

    /** Limits for every allowed video codec, after per-codec and global caps. */
    fun videoLimits(caps: DeviceCapabilities, settings: PlaybackSettings): List<VideoLimit> {
        val userWidth = settings.maxResolution.width ?: Int.MAX_VALUE
        val userHeight = settings.maxResolution.height ?: Int.MAX_VALUE
        return videoCodecs(caps, settings).filter { it.allowed }.map { cap ->
            // A codec forced on that the device doesn't report gets 4K (or the panel size) and 10-bit.
            val c = caps.video[cap.id]
            VideoLimit(
                codec = cap.id,
                maxWidth = minOf(c?.maxWidth ?: maxOf(caps.displayWidth, 3840), userWidth),
                maxHeight = minOf(c?.maxHeight ?: maxOf(caps.displayHeight, 2160), userHeight, settings.codecMaxHeight[cap.id] ?: Int.MAX_VALUE),
                tenBit = (c?.tenBit ?: true || cap.id in settings.tenBitAllowed) && cap.id !in settings.eightBitOnly,
            )
        }
    }

    /**
     * Which capability to blame when direct play fails: the HDR format for HDR video (the codec
     * usually works in SDR), else the video or audio codec. Null when the plan doesn't say.
     */
    fun failureCapabilityId(plan: PlaybackPlan, videoFailed: Boolean): String? {
        val streams = plan.source.streams
        return if (videoFailed) {
            val video = streams.firstOrNull { it.kind == StreamKind.VIDEO } ?: return null
            val range = video.rangeType
            when {
                range == null || range == "SDR" || range == "Unknown" -> video.codec?.lowercase()
                range.startsWith("DOVI") -> PlaybackSettings.hdrId("DOVI")
                range.startsWith("HDR10Plus") -> PlaybackSettings.hdrId("HDR10Plus")
                else -> PlaybackSettings.hdrId(range)
            }
        } else {
            streams.firstOrNull { it.index == plan.audioIndex }?.codec?.lowercase()
        }
    }
}

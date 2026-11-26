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
     * Dual-layer Dolby Vision (profile 7, from UHD Blu-ray: "DOVIWithEL") plays as its HDR10 base
     * layer too, or as Dolby Vision where [profile7] allows.
     */
    fun allowedRangeTypes(caps: DeviceCapabilities, settings: PlaybackSettings): List<String> {
        val allowed = hdrFormats(caps, settings).filter { it.allowed }.map { it.id.removePrefix("range:") }.toSet()
        return buildList {
            add("SDR")
            add("DOVIWithSDR")
            if ("HDR10" in allowed) addAll(listOf("HDR10", "DOVIWithHDR10", "HDR10Plus", "DOVIWithHDR10Plus", "DOVIWithEL", "DOVIWithELHDR10Plus"))
            if (profile7(caps, settings) != Profile7Playback.BASE_LAYER) addAll(listOf("DOVIWithEL", "DOVIWithELHDR10Plus"))
            if ("HDR10Plus" in allowed) add("HDR10Plus")
            if ("HLG" in allowed) addAll(listOf("HLG", "DOVIWithHLG"))
            if ("DOVI" in allowed) addAll(listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithHDR10Plus"))
        }.distinct()
    }

    /**
     * How dual-layer Dolby Vision (profile 7) plays here: as itself where a decoder takes profile 7,
     * else rewritten as profile 8.1 where a decoder takes profile 8 (and the setting allows), else
     * its HDR10 base layer. Dolby Vision must be allowed for either of the first two.
     */
    fun profile7(caps: DeviceCapabilities, settings: PlaybackSettings): Profile7Playback {
        val dolbyVision = hdrFormats(caps, settings).any { it.id == PlaybackSettings.hdrId("DOVI") && it.allowed }
        return when {
            dolbyVision && 7 in caps.dolbyVisionProfiles -> Profile7Playback.DOLBY_VISION
            dolbyVision && 8 in caps.dolbyVisionProfiles && caps.dolbyVisionConverter && settings.convertProfile7 -> Profile7Playback.AS_PROFILE_8
            else -> Profile7Playback.BASE_LAYER
        }
    }

    /** The tallest video a software-only decoder is trusted with. */
    const val SOFTWARE_MAX_HEIGHT = 1080

    /** Size and bit-depth limit for direct playing one video codec. */
    data class VideoLimit(
        val codec: String,
        val maxWidth: Int,
        val maxHeight: Int,
        val tenBit: Boolean,
        /** 10-bit only up to this height (a software decoder takes it, the hardware doesn't). */
        val tenBitMaxHeight: Int? = null,
        /** Decoded in software, which doesn't deinterlace: interlaced video is left to the server. */
        val progressiveOnly: Boolean = false,
        /** Profiles (Jellyfin's names) the device's decoders don't take: see [softwareProfiles]. */
        val missingProfiles: Set<String> = emptySet(),
        /** Those profiles go to Nitrate's software decoder (to 1080p, progressive) rather than the server. */
        val softwareProfiles: Boolean = false,
    )

    /** Limits for every allowed video codec, after per-codec and global caps. */
    fun videoLimits(caps: DeviceCapabilities, settings: PlaybackSettings): List<VideoLimit> {
        val userWidth = settings.maxResolution.width ?: Int.MAX_VALUE
        val userHeight = settings.maxResolution.height ?: Int.MAX_VALUE
        return videoCodecs(caps, settings).filter { it.allowed }.map { cap ->
            // A codec forced on that the device doesn't report gets 4K (or the panel size) and 10-bit.
            val c = caps.video[cap.id]
            // Software decoding keeps up to 1080p; above that it stutters on TV boxes and phones.
            // "Always" for the codec lifts it (the user, or the playback test, knows better).
            val softCap = if (c != null && !c.hardware && settings.overrides[cap.id] != Override.ALWAYS) SOFTWARE_MAX_HEIGHT else Int.MAX_VALUE
            val maxHeight = minOf(c?.maxHeight ?: maxOf(caps.displayHeight, 2160), userHeight, settings.codecMaxHeight[cap.id] ?: Int.MAX_VALUE, softCap)
            // "10-bit allowed by test" lifts an 8-bit-only hardware report, unless Nitrate's own software
            // decoder is what played the 10-bit clip: then 10-bit stays at its 1080p limit (below).
            val byTest = cap.id in settings.tenBitAllowed && c?.softwareTenBit != true
            val tenBit = (c?.tenBit ?: true || byTest) && cap.id !in settings.eightBitOnly
            // The hardware is 8-bit only but Nitrate's software decoder takes 10-bit up to 1080p.
            val softTenBit = !tenBit && c?.softwareTenBit == true && cap.id !in settings.eightBitOnly && maxHeight > 0
            VideoLimit(
                codec = cap.id,
                maxWidth = minOf(c?.maxWidth ?: maxOf(caps.displayWidth, 3840), userWidth, if (softCap < Int.MAX_VALUE) softCap * 16 / 9 else Int.MAX_VALUE),
                maxHeight = maxHeight,
                tenBit = tenBit || softTenBit,
                tenBitMaxHeight = if (softTenBit) minOf(SOFTWARE_MAX_HEIGHT, maxHeight) else null,
                progressiveOnly = c != null && !c.hardware && settings.overrides[cap.id] != Override.ALWAYS,
                // "Always" for the codec: the user says it plays everything.
                missingProfiles = if (settings.overrides[cap.id] == Override.ALWAYS) emptySet() else c?.missingProfiles.orEmpty(),
                softwareProfiles = c?.softwareProfiles == true,
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

/**
 * Which kind of dual-layer Dolby Vision (profile 7) a file is, by how much its enhancement layer
 * carries: MEL (minimal: next to nothing, as most streaming-style encodes) or FEL (full: a second
 * picture layer, as many disc copies). Found while playing (its size per frame), then remembered
 * for the file on this device.
 */
enum class DolbyVisionLayers(val short: String, val label: String) {
    MEL("MEL", "MEL (a minimal enhancement layer)"),
    FEL("FEL", "FEL (a full enhancement layer)");

    companion object {
        /** Above this many enhancement-layer bytes a frame on average, it's a full layer. */
        const val FEL_BYTES_PER_FRAME = 3_000

        /** Frames looked at before saying. */
        const val FRAMES = 48

        fun of(averageBytesPerFrame: Long): DolbyVisionLayers = if (averageBytesPerFrame > FEL_BYTES_PER_FRAME) FEL else MEL

        fun parse(name: String?): DolbyVisionLayers? = entries.firstOrNull { it.name == name }
    }
}

/** How dual-layer Dolby Vision (profile 7) is played: see [CapabilityRules.profile7]. */
enum class Profile7Playback(val label: String) {
    DOLBY_VISION("as Dolby Vision (profile 7)"),
    AS_PROFILE_8("as Dolby Vision, converted to profile 8.1 while playing"),
    BASE_LAYER("as HDR10 (its base layer)"),
}

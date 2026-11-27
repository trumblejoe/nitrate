package dev.nitrate.core.jellyfin

import dev.nitrate.core.playback.Override

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.DeliveryVerdict
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PredictedDelivery
import dev.nitrate.core.playback.codecLabel

/**
 * What the server will do with a file for this device, worked out on the device from the file's
 * streams against the very profile Nitrate sends ([JellyfinDeviceProfile.create]), the way Jellyfin
 * weighs it: play it as it is, repackage it, convert only its sound, or convert the picture. Used
 * before playing (How it plays) and for a whole library at once (the playback overview), so it needs
 * no call to the server; the server's own answer, when asked, is the final word.
 */
object ProfileCheck {

    fun verdict(
        caps: DeviceCapabilities,
        settings: PlaybackSettings,
        source: MediaSource,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        itemId: String? = null,
    ): DeliveryVerdict {
        val profile = JellyfinDeviceProfile.create(caps, settings)
        val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val audio = dev.nitrate.core.playback.PlaybackPredictor.audio(source, audioIndex)
        val subtitle = dev.nitrate.core.playback.PlaybackPredictor.subtitle(source, subtitleIndex)
        val containers = list(source.container)
        val videoCodec = video?.codec?.lowercase()
        val audioCodec = audio?.codec?.lowercase()
        val direct = profile.directPlayProfiles.filter { it.type == "Video" }

        val videoListed = videoCodec == null || direct.any { videoCodec in list(it.videoCodec) }
        val containerOk = containers.isEmpty() || direct.any { p -> containers.any { it in list(p.container) } && (videoCodec == null || videoCodec in list(p.videoCodec)) }
        val audioOk = audioCodec == null || direct.any { audioCodec in list(it.audioCodec) }
        val pictureLimits = video?.let { pictureFailures(profile, it, caps, settings) }.orEmpty()
        val bitrateOk = (source.bitrate ?: 0) <= profile.maxStreamingBitrate
        val burns = subtitle != null && burnsIn(profile, subtitle)
        val videoOk = videoListed && pictureLimits.isEmpty()
        val transcodeVideo = list(profile.transcodingProfiles.firstOrNull { it.type == "Video" }?.videoCodec)
        val convertsSoundTo = list(profile.transcodingProfiles.firstOrNull { it.type == "Video" }?.audioCodec).firstOrNull()

        val reasons = buildList {
            if (!videoListed) add("${codecLabel(videoCodec)} video isn't something this device decodes")
            addAll(pictureLimits)
            if (!bitrateOk) add("Its bitrate (${mbps(source.bitrate)}) is above this device's limit (${mbps(profile.maxStreamingBitrate)})")
            if (burns) add("The subtitles chosen are drawn into the picture by the server")
            if (!settings.allowDirectPlay) add("Direct play is turned off on this device")
        }
        val copyable = videoOk && bitrateOk && !burns && (videoCodec == null || videoCodec in transcodeVideo)
        return when {
            videoOk && audioOk && containerOk && bitrateOk && !burns && settings.allowDirectPlay ->
                DeliveryVerdict(PredictedDelivery.DIRECT, emptyList())
            copyable && settings.allowDirectStream && !audioOk ->
                DeliveryVerdict(PredictedDelivery.SOUND_CONVERTED, reasons + "${codecLabel(audioCodec)} sound isn't something this device plays, so the server converts it to ${codecLabel(convertsSoundTo)}", convertsSoundTo)
            copyable && settings.allowDirectStream ->
                DeliveryVerdict(PredictedDelivery.REMUX, reasons + "The ${containers.firstOrNull()?.uppercase() ?: "file"} container is repackaged by the server; picture and sound stay as they are")
            else -> DeliveryVerdict(
                PredictedDelivery.CONVERTED,
                reasons.ifEmpty { listOf(if (videoCodec != null && videoCodec !in transcodeVideo && !containerOk) "The server can't repackage ${codecLabel(videoCodec)} from this container, so it converts the picture" else "The server converts it") },
                convertsSoundTo.takeIf { !audioOk },
            )
        }
    }

    /** The picture's limits from the profile's codec rules that this video breaks, in plain words. */
    private fun pictureFailures(profile: DeviceProfileDto, video: MediaStream, caps: DeviceCapabilities, settings: PlaybackSettings): List<String> {
        val codec = video.codec?.lowercase() ?: return emptyList()
        val name = codecLabel(codec)
        return profile.codecProfiles.filter { it.type == "Video" && codec in list(it.codec) && it.applyConditions.all { c -> holds(c, video) } }
            .flatMap { it.conditions }
            .filterNot { holds(it, video) }
            .map { c ->
                when (c.property) {
                    "Width", "Height" -> "${video.height ?: "?"}p $name is more than this device decodes it at (up to ${if (c.property == "Height") c.value else (c.value.toIntOrNull()?.let { it * 9 / 16 } ?: c.value)}p)"
                    "VideoBitDepth" -> "${video.bitDepth}-bit $name isn't something this device decodes"
                    "VideoRangeType" -> rangeReason(video.rangeType, caps, settings)
                    "IsInterlaced" -> "It's interlaced, which the software decoder can't show; the server deinterlaces it"
                    "VideoProfile" -> "$name ${video.profile} (4:2:2/4:4:4) isn't something any decoder here takes"
                    else -> "$name ${c.property} isn't within this device's limits"
                }
            }.distinct()
    }

    /** Whether the subtitle has to be drawn into the picture by the server. */
    private fun burnsIn(profile: DeviceProfileDto, subtitle: MediaStream): Boolean {
        val format = subtitle.codec?.lowercase()?.let { SUBTITLE_NAMES[it] ?: it } ?: return false
        val methods = profile.subtitleProfiles.filter { it.format == format }.map { it.method }.toSet()
        if (methods.isEmpty()) return false // text the server can hand over as SRT
        return if (subtitle.isExternal) "External" !in methods && "Hls" !in methods else "Embed" !in methods && "Hls" !in methods
    }

    /** Whether a profile condition holds for this video (unknown values count as holding). */
    private fun holds(c: ProfileConditionDto, v: MediaStream): Boolean {
        val value: String = when (c.property) {
            "Width" -> v.width?.toString()
            "Height" -> v.height?.toString()
            "VideoBitDepth" -> (v.bitDepth ?: 8).toString()
            "VideoRangeType" -> v.rangeType ?: "SDR"
            "IsInterlaced" -> v.isInterlaced.toString()
            "VideoProfile" -> v.profile
            else -> null
        } ?: return true
        return when (c.condition) {
            "LessThanEqual" -> (value.toDoubleOrNull() ?: return true) <= (c.value.toDoubleOrNull() ?: return true)
            "GreaterThanEqual" -> (value.toDoubleOrNull() ?: return true) >= (c.value.toDoubleOrNull() ?: return true)
            "Equals" -> value.equals(c.value, ignoreCase = true)
            "NotEquals" -> !value.equals(c.value, ignoreCase = true)
            "EqualsAny" -> c.value.split('|').any { it.equals(value, ignoreCase = true) }
            else -> true
        }
    }

    private fun list(s: String?) = s.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private fun mbps(bps: Int?) = bps?.let { dev.nitrate.core.playback.formatBitrate(it) } ?: "?"
    /**
     * Why an HDR picture is tone-mapped, naming the real cause for each format that could have
     * shown it (the file's own, then the base layer it falls back to): set to Never, turned off
     * after failures here, or not reported by the display.
     */
    internal fun rangeReason(range: String?, caps: DeviceCapabilities, settings: PlaybackSettings): String {
        val formats = when (range) {
            "HDR10" -> listOf("HDR10")
            "HDR10Plus" -> listOf("HDR10Plus", "HDR10")
            "HLG" -> listOf("HLG")
            "DOVI", "DOVIInvalid" -> listOf("DOVI")
            "DOVIWithHDR10", "DOVIWithEL" -> listOf("DOVI", "HDR10")
            "DOVIWithHDR10Plus", "DOVIWithELHDR10Plus" -> listOf("DOVI", "HDR10Plus", "HDR10")
            "DOVIWithHLG" -> listOf("DOVI", "HLG")
            else -> emptyList()
        }
        fun name(f: String) = when (f) { "DOVI" -> "Dolby Vision"; "HDR10Plus" -> "HDR10+"; else -> f }
        fun names(fs: List<String>) = fs.map(::name).let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + " and " + it.last() }
        val never = formats.filter { settings.overrides[PlaybackSettings.hdrId(it)] == Override.NEVER }
        val unreported = formats.filter { it !in never && it !in caps.hdr && settings.overrides[PlaybackSettings.hdrId(it)] == null }
        val causes = buildList {
            if (never.isNotEmpty()) add("${names(never)} ${if (never.size == 1) "is" else "are"} set to Server converts (Picture & sound → Advanced → Formats)")
            if (unreported.isNotEmpty()) add("the display doesn't report ${names(unreported)} (Android says it shows ${caps.hdr.sorted().joinToString { name(it) }.ifEmpty { "no HDR" }})")
        }
        return if (causes.isEmpty()) "${rangeName(range)} isn't shown as it is here, so the server tone-maps it to SDR"
        else "${rangeName(range)} is tone-mapped to SDR by the server: ${causes.joinToString("; ")}"
    }

    private fun rangeName(range: String?) = when {
        range == null -> "HDR"
        range.startsWith("DOVI") -> "Dolby Vision"
        range.startsWith("HDR10Plus") -> "HDR10+"
        else -> range
    }

    /** Jellyfin's subtitle codec names as the profile lists them. */
    private val SUBTITLE_NAMES = mapOf("srt" to "srt", "subrip" to "subrip", "dvd_subtitle" to "dvdsub", "hdmv_pgs_subtitle" to "pgssub", "dvb_subtitle" to "dvbsub")

    /** The whole prediction: the server's part from the profile, then the device's. */
    fun predict(
        caps: DeviceCapabilities,
        settings: PlaybackSettings,
        source: MediaSource,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        itemId: String? = null,
        display: dev.nitrate.core.playback.DisplaySnapshot? = null,
        dvLayers: dev.nitrate.core.playback.DolbyVisionLayers? = null,
    ): dev.nitrate.core.playback.PlaybackPrediction = dev.nitrate.core.playback.PlaybackPredictor.predict(
        source, verdict(caps, settings, source, audioIndex, subtitleIndex, itemId), caps, settings, audioIndex, subtitleIndex, display, dvLayers,
    )
}

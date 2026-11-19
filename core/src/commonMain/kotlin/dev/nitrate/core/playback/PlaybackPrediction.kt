package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.formatOneDecimal

/** What the server will do with a file for this device. */
enum class PredictedDelivery(val label: String) {
    DIRECT("Plays as it is"),
    REMUX("Repackaged by the server"),
    SOUND_CONVERTED("Sound converted by the server"),
    CONVERTED("Picture converted by the server"),
}

/** The server's side of a prediction (from the profile Nitrate sends): see `ProfileCheck`. */
data class DeliveryVerdict(
    val delivery: PredictedDelivery,
    val reasons: List<String>,
    /** The codec the sound is converted to, when it is. */
    val convertsSoundTo: String? = null,
)

/** Which decoder shows the picture. */
enum class DecoderTier(val label: String) {
    HARDWARE("Hardware decoder"),
    DEVICE_SOFTWARE("Android's software decoder"),
    NITRATE_SOFTWARE("Nitrate's software decoder (FFmpeg)"),
    NONE("No picture"),
}

/** How the sound reaches the speakers. */
enum class SoundRoute(val label: String) {
    PASSTHROUGH("Sent untouched for the TV or receiver to decode"),
    DECODED("Decoded on this device"),
    CONVERTED("Converted by the server"),
    NONE("No sound"),
}

/** The display's mode now and the modes it offers (TV only; null on phones). */
data class DisplaySnapshot(val current: DisplayMode, val modes: List<DisplayMode>)

/**
 * How a file will play on this device, worked out before playing from its streams, what the device
 * reports (and the device check confirmed), this device's settings and the display: the server's
 * part (as is, repackaged, sound or picture converted, and why), the decoder, the HDR path, the
 * sound's route, the refresh rate and the subtitles. For How it plays and the playback overview.
 */
data class PlaybackPrediction(
    val delivery: PredictedDelivery,
    val reasons: List<String>,
    /** "HEVC 4K 10-bit HDR10". */
    val picture: String?,
    val decoder: DecoderTier,
    val decoderName: String?,
    val hdr: String?,
    val sound: SoundRoute,
    /** "Dolby Digital Plus 5.1", with why when it's decoded. */
    val soundDetail: String?,
    val refresh: String?,
    val subtitles: String?,
    /** What the device check confirmed (or found wrong) for this kind of file. */
    val checked: List<String> = emptyList(),
) {
    /** Label → text, for How it plays and the overview's detail. */
    fun rows(): List<Pair<String, String>> = buildList {
        add("Server" to (delivery.label + reasons.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = ": ").orEmpty()))
        picture?.let { add("Picture" to it) }
        if (decoder != DecoderTier.NONE) add("Decoder" to decoder.label + (decoderName?.let { " ($it)" } ?: ""))
        hdr?.let { add("HDR" to it) }
        if (sound != SoundRoute.NONE) add("Sound" to listOfNotNull(soundDetail, sound.label).joinToString(": "))
        refresh?.let { add("Refresh rate" to it) }
        subtitles?.let { add("Subtitles" to it) }
        checked.forEach { add("Device check" to it) }
    }

    /** One line for a list: the route, the picture and the sound. */
    val summary: String
        get() = listOfNotNull(
            delivery.label,
            picture?.let { p -> if (decoder == DecoderTier.HARDWARE || decoder == DecoderTier.NONE) p else "$p (${decoder.label.lowercase()})" },
            soundDetail?.substringBefore(':')?.let { when (sound) {
                SoundRoute.PASSTHROUGH -> "$it untouched"
                SoundRoute.DECODED -> "$it decoded"
                else -> it // already says what it's converted to
            } },
        ).joinToString(" · ")
}

object PlaybackPredictor {

    /** The audio track that would start: the one asked for, else the server's pick, else the default. */
    fun audio(source: MediaSource, audioIndex: Int? = null): MediaStream? =
        source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.index == (audioIndex ?: source.defaultAudioIndex) }
            ?: source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.isDefault }
            ?: source.streams.firstOrNull { it.kind == StreamKind.AUDIO }

    /** The subtitle that would start (null: none). */
    fun subtitle(source: MediaSource, subtitleIndex: Int? = null): MediaStream? =
        (subtitleIndex ?: source.defaultSubtitleIndex)?.takeIf { it >= 0 }?.let { i -> source.streams.firstOrNull { it.kind == StreamKind.SUBTITLE && it.index == i } }

    fun predict(
        source: MediaSource,
        verdict: DeliveryVerdict,
        caps: DeviceCapabilities,
        settings: PlaybackSettings,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        display: DisplaySnapshot? = null,
    ): PlaybackPrediction {
        val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val audio = audio(source, audioIndex)
        val subtitle = subtitle(source, subtitleIndex)
        val converted = verdict.delivery == PredictedDelivery.CONVERTED
        val check = settings.deviceCheck

        // The picture and its decoder: the file's own, or what the server converts it to.
        val outCodec = if (converted) listOf("hevc", "h264").firstOrNull { c -> CapabilityRules.videoCodecs(caps, settings).any { it.id == c && it.allowed } } ?: "h264" else video?.codec?.lowercase()
        val picture = video?.let { v ->
            if (converted) "${describe(v)}, converted to ${codecLabel(outCodec)}" + (if (tonemapped(verdict)) " SDR" else "")
            else describe(v)
        }
        val (tier, decoderName) = when {
            video == null -> DecoderTier.NONE to null
            else -> tier(outCodec, if (converted) null else video, caps)
        }

        val hdr = video?.let { hdrPath(it, verdict, caps, settings) }

        // Sound.
        val processing = listOfNotNull("dialogue boost".takeIf { settings.dialogueBoost }, "night mode".takeIf { settings.nightMode })
        val audioCodec = audio?.codec?.lowercase()
        val audioDesc = audio?.let { a -> listOfNotNull(codecLabel(a.codec), channels(a.channels)).joinToString(" ") }
        val (sound, soundDetail) = when {
            audio == null -> SoundRoute.NONE to null
            verdict.convertsSoundTo != null -> SoundRoute.CONVERTED to "$audioDesc, converted to ${codecLabel(verdict.convertsSoundTo)}"
            audioCodec in AudioPath.BITSTREAM_CODECS && caps.audio[audioCodec]?.passthrough == true && processing.isEmpty() -> SoundRoute.PASSTHROUGH to audioDesc
            audioCodec in AudioPath.BITSTREAM_CODECS -> SoundRoute.DECODED to (audioDesc + ": " + (AudioPath.decodeReason(audioCodec, processing, 1f, outputAccepts = caps.audio[audioCodec]?.passthrough == true) ?: "decoded here"))
            else -> SoundRoute.DECODED to audioDesc
        }

        val refresh = display?.let { d -> video?.frameRate?.let { fps -> refreshPlan(fps, d, settings, check?.refresh) } }

        val subtitles = subtitle?.let { s ->
            val codec = s.codec?.lowercase()
            when {
                verdict.reasons.any { "subtitles" in it } -> "Drawn into the picture by the server"
                isStyledSubtitle(codec) -> when (settings.subtitleStyle.styled) {
                    StyledSubtitles.FILE -> "Drawn here as the file styles them"
                    StyledSubtitles.SIMPLE -> "Drawn here, simplified"
                    StyledSubtitles.MINE -> "Drawn here in your subtitle style"
                    StyledSubtitles.EXACT -> "Drawn into the picture by the server"
                }
                codec in setOf("pgssub", "dvdsub", "dvbsub", "hdmv_pgs_subtitle", "dvd_subtitle") -> "Shown as they are (picture subtitles)"
                else -> "Drawn here in your subtitle style"
            }
        }

        return PlaybackPrediction(
            delivery = verdict.delivery,
            reasons = verdict.reasons,
            picture = picture,
            decoder = tier,
            decoderName = decoderName,
            hdr = hdr,
            sound = sound,
            soundDetail = soundDetail,
            refresh = refresh,
            subtitles = subtitles,
            checked = check?.let { checked(it, if (converted) null else video, outCodec, audioCodec, sound) }.orEmpty(),
        )
    }

    private fun tonemapped(verdict: DeliveryVerdict) = verdict.reasons.any { "tone-maps" in it }

    /** Which decoder takes the picture: hardware first, then Android's software one, then Nitrate's. */
    private fun tier(codec: String?, video: MediaStream?, caps: DeviceCapabilities): Pair<DecoderTier, String?> {
        val c = codec?.let { caps.video[it] } ?: return DecoderTier.NONE to null
        val tenBit = (video?.bitDepth ?: 8) > 8
        val profile = video?.profile?.lowercase()
        return when {
            c.decoderName == DeviceCapabilities.SOFTWARE_VIDEO_DECODER -> DecoderTier.NITRATE_SOFTWARE to null
            profile != null && profile in c.missingProfiles && c.softwareProfiles -> DecoderTier.NITRATE_SOFTWARE to null
            tenBit && !c.tenBit && c.softwareTenBit -> DecoderTier.NITRATE_SOFTWARE to null
            !c.hardware -> DecoderTier.DEVICE_SOFTWARE to c.decoderName
            else -> DecoderTier.HARDWARE to c.decoderName
        }
    }

    private fun hdrPath(v: MediaStream, verdict: DeliveryVerdict, caps: DeviceCapabilities, settings: PlaybackSettings): String? {
        val range = v.rangeType ?: return null
        if (range == "SDR" || range == "Unknown") return null
        if (tonemapped(verdict)) return "Tone-mapped to SDR by the server (this display doesn't show it as it is)"
        val allowed = CapabilityRules.allowedRangeTypes(caps, settings).toSet()
        val dv = CapabilityRules.hdrFormats(caps, settings).any { it.id == PlaybackSettings.hdrId("DOVI") && it.allowed }
        return when {
            range.startsWith("DOVIWithEL") || v.dvProfile == 7 -> "Dolby Vision profile 7 " + CapabilityRules.profile7(caps, settings).label
            range.startsWith("DOVI") && dv -> "Dolby Vision" + (v.dvProfile?.let { " (profile $it)" } ?: "")
            range == "DOVIWithHDR10" || range == "DOVIWithHDR10Plus" -> "HDR10 (Dolby Vision's base layer; this display doesn't take Dolby Vision)"
            range == "DOVIWithHLG" -> "HLG (Dolby Vision's base layer)"
            range == "DOVIWithSDR" -> "SDR (Dolby Vision's base layer)"
            range == "HDR10Plus" && "HDR10Plus" in allowed -> "HDR10+"
            range == "HDR10Plus" -> "HDR10 (without the HDR10+ extras)"
            range in allowed -> "$range as it is"
            else -> range
        }
    }

    /** What happens to the refresh rate for [fps] on this display with this device's setting. */
    fun refreshPlan(fps: Float, display: DisplaySnapshot, settings: PlaybackSettings, checked: RefreshCheck? = null): String {
        val now = display.current
        val cadence = DisplayModes.cadence(fps, now.refreshRate)
        val stays = when (cadence) {
            DisplayModes.Cadence.JUDDER -> "Stays at ${hz(now.refreshRate)} Hz: slight judder for ${hz(fps)} fps"
            else -> "Stays at ${hz(now.refreshRate)} Hz, which suits ${hz(fps)} fps"
        }
        return when (settings.frameRateMatch) {
            FrameRateMatch.OFF -> if (cadence == DisplayModes.Cadence.JUDDER) "$stays (refresh-rate matching is off)" else stays
            FrameRateMatch.SEAMLESS -> if (cadence == DisplayModes.Cadence.JUDDER) "Switches to suit ${hz(fps)} fps only if the TV can without a blank screen; else ${stays.replaceFirstChar { it.lowercase() }}" else stays
            FrameRateMatch.ALWAYS -> DisplayModes.best(display.modes, now, fps)?.let { t ->
                "Switches to ${hz(t.refreshRate)} Hz before it starts" + (checked?.blackMs?.let { " (about ${formatOneDecimal(it / 1000.0)} s of black on this TV)" } ?: " (a moment of black)") +
                    // Measured by the device check: surround that the receiver stopped taking after a switch.
                    (if (checked != null && checked.switched && checked.surroundBefore.isNotEmpty() && checked.surroundBackMs == null) "; in the device check, surround sound wasn't passed through again after the switch" else "")
            } ?: if (cadence == DisplayModes.Cadence.JUDDER) "$stays (this TV offers no better mode)" else stays
        }
    }

    /** What the device check says about this kind of file. */
    private fun checked(check: DeviceCheck, video: MediaStream?, outCodec: String?, audioCodec: String?, sound: SoundRoute): List<String> = buildList {
        val clips = PlaybackTest.CLIPS.associateBy { it.asset }
        val codec = video?.codec?.lowercase() ?: outCodec
        if (codec != null) {
            val height = video?.height ?: 1080
            val tenBit = (video?.bitDepth ?: 8) > 8
            val hdr = video?.rangeType?.let { r -> when { r.startsWith("DOVI") -> "DOVI"; r.startsWith("HDR10") -> "HDR10"; r == "HLG" -> "HLG"; else -> null } }
            // The closest clip: same codec, as big or bigger, same bit depth and HDR where there is one.
            val match = check.clips.entries.mapNotNull { (asset, r) -> clips[asset]?.let { it to r } }
                .filter { (c, _) -> c.kind == ClipKind.VIDEO && c.codec == codec }
                .sortedWith(compareBy({ (c, _) -> if (c.height >= height) 0 else 1 }, { (c, _) -> if (c.tenBit == tenBit) 0 else 1 }, { (c, _) -> if (c.hdr == hdr) 0 else 1 }, { (c, _) -> kotlin.math.abs(c.height - height) }))
                .firstOrNull()
            match?.let { (c, r) -> add("${c.label}: ${r.outcome.label.lowercase()}" + (r.decoder?.let { " ($it)" } ?: "")) }
        }
        if (audioCodec != null) {
            check.clips.entries.firstOrNull { (asset, _) -> clips[asset]?.let { it.kind == ClipKind.AUDIO && it.codec == audioCodec } == true }?.let { (asset, r) ->
                val label = clips.getValue(asset).label
                add(when {
                    r.outcome != Outcome.PASSED -> "$label: ${r.outcome.label.lowercase()}"
                    r.passthrough == true -> "$label went out untouched"
                    r.passthrough == false && audioCodec in AudioPath.BITSTREAM_CODECS && sound == SoundRoute.PASSTHROUGH -> "$label was decoded in the check, though the output now takes it untouched"
                    r.passthrough == false -> "$label was decoded here"
                    else -> "$label plays"
                })
            }
        }
    }

    private fun describe(v: MediaStream): String = listOfNotNull(
        codecLabel(v.codec),
        v.height?.let { h -> when { h >= 2000 -> "4K"; h >= 1000 -> "1080" + if (v.isInterlaced) "i" else "p"; h >= 700 -> "720p"; else -> "${h}" + if (v.isInterlaced) "i" else "p" } },
        v.bitDepth?.takeIf { it > 8 }?.let { "$it-bit" },
        v.rangeType?.takeIf { it != "SDR" && it != "Unknown" }?.let { r -> when { r.startsWith("DOVI") -> "Dolby Vision"; r == "HDR10Plus" -> "HDR10+"; else -> r } },
        v.frameRate?.let { "${hz(it)} fps" },
    ).joinToString(" ")

    private fun channels(n: Int?) = when (n) { null, 0 -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch" }

    /** 23.976, 24, 59.94. */
    private fun hz(v: Float): String {
        val millis = kotlin.math.round(v * 1000).toLong()
        val whole = millis / 1000
        val frac = (millis % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole" else "$whole.$frac"
    }
}

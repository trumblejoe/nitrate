package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.model.subtitleMimeType

/** A change to this device's playback settings that a fix makes. */
sealed interface SettingsChange {
    fun apply(s: PlaybackSettings): PlaybackSettings

    data class Bitrate(val bps: Int) : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(maxBitrate = bps)
    }

    data object ResolutionAuto : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(maxResolution = MaxResolution.AUTO)
    }

    data class LiftHeightLimit(val codec: String) : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(codecMaxHeight = s.codecMaxHeight - codec)
    }

    data class AllowTenBit(val codec: String) : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(eightBitOnly = s.eightBitOnly - codec, tenBitAllowed = s.tenBitAllowed + codec)
    }

    /** Back to Auto for a capability the user had set to Never. */
    data class ClearOverride(val id: String) : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(overrides = s.overrides - id)
    }

    /** "Always" for a capability the device doesn't claim (or decodes only in software). */
    data class ForceAllow(val id: String) : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(overrides = s.overrides + (id to Override.ALWAYS))
    }

    data class ForgetFailure(val id: String) : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(learnedFailures = s.learnedFailures - id)
    }

    data object AllowDirectPlay : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(allowDirectPlay = true)
    }

    data object AllowDirectStream : SettingsChange {
        override fun apply(s: PlaybackSettings) = s.copy(allowDirectStream = true)
    }
}

/** Something the user can do about how an item plays. */
sealed interface PlaybackFix {
    val label: String
    /** One line on what happens, or the risk. */
    val detail: String?

    data class UseVersion(val versionId: String, override val label: String, override val detail: String?) : PlaybackFix
    data class UseAudio(val index: Int, override val label: String, override val detail: String?) : PlaybackFix
    data class UseSubtitle(val index: Int, override val label: String, override val detail: String?) : PlaybackFix
    /** Changes a playback setting (saved for this device) and asks the server again. */
    data class Change(val change: SettingsChange, override val label: String, override val detail: String?) : PlaybackFix
    /** Asks the server again without forcing a transcode (after an earlier failure). */
    data class Retry(override val label: String, override val detail: String?) : PlaybackFix
}

/** One reason an item doesn't play as it is, in plain words, with what can be done. */
data class PlaybackFinding(
    val title: String,
    val text: String,
    val fixes: List<PlaybackFix> = emptyList(),
    /** What only the server's admin can change, when that's the real fix. */
    val adminTip: String? = null,
)

/** How much of the original reaches the screen. */
enum class PlaybackQuality(val label: String) {
    ORIGINAL("Plays directly"),
    REPACKAGED("Repackaged"),
    SOUND_CONVERTED("Sound converted"),
    PICTURE_CONVERTED("Picture converted"),
}

data class PlaybackDiagnosis(
    val quality: PlaybackQuality,
    /** One sentence on what the server is doing. */
    val summary: String,
    val findings: List<PlaybackFinding>,
    /** What the server answered, for the media information. */
    val plan: dev.nitrate.core.model.PlaybackPlan? = null,
) {
    /** True when there's anything to show beyond "it plays as it is". */
    val hasAdvice get() = findings.isNotEmpty()
}

/**
 * Works out why an item doesn't play as it is on this device, from what the server decided (its
 * transcode reasons), the file's streams, what the device reports and this device's playback
 * settings, and turns it into plain sentences with fixes the user can apply in one step.
 */
object PlaybackDoctor {

    fun diagnose(
        plan: PlaybackPlan,
        caps: DeviceCapabilities,
        settings: PlaybackSettings,
        /** True when Nitrate itself asked for a transcode after direct play failed. */
        forcedTranscode: Boolean = false,
    ): PlaybackDiagnosis {
        val reasons = plan.transcodeReasons.toSet()
        val streams = plan.source.streams
        val video = streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val audio = streams.firstOrNull { it.index == plan.audioIndex && it.kind == StreamKind.AUDIO }
        val subtitle = streams.firstOrNull { it.index == plan.subtitleIndex && it.kind == StreamKind.SUBTITLE }
        val burnIn = plan.method == PlayMethod.TRANSCODE && subtitle != null &&
            (subtitle.delivery == SubtitleDelivery.ENCODE || "SubtitleCodecNotSupported" in reasons)

        val findings = mutableListOf<PlaybackFinding>()
        val handled = mutableSetOf<String>()
        fun handles(vararg r: String) = r.any { it in reasons }.also { if (it) handled += r }

        if (plan.method != PlayMethod.DIRECT_PLAY) {
            if (!settings.allowDirectPlay) findings += PlaybackFinding(
                "Direct play is off",
                "Direct play is turned off in this device's Playback settings, so everything goes through the server.",
                listOf(PlaybackFix.Change(SettingsChange.AllowDirectPlay, "Turn direct play on", null)),
            )
            if (!settings.allowDirectStream && plan.method == PlayMethod.TRANSCODE) findings += PlaybackFinding(
                "Repackaging is off",
                "Direct stream is turned off in Playback settings, so files the server could simply repackage are converted instead.",
                listOf(PlaybackFix.Change(SettingsChange.AllowDirectStream, "Turn direct stream on", null)),
            )
        }

        if (forcedTranscode) findings += PlaybackFinding(
            "Switched after a playback error",
            "Playing the file directly failed earlier, so Nitrate asked the server to convert it.",
            listOf(PlaybackFix.Retry("Try direct play again", "If it fails again, Nitrate goes back to converting.")),
        )

        if (video != null) {
            val codec = video.codec?.lowercase()
            if (handles("VideoCodecNotSupported", "VideoProfileNotSupported", "VideoLevelNotSupported", "VideoCodecTagNotSupported")) {
                findings += videoCodecFinding(video, codec, caps, settings, plan)
            }
            if (handles("VideoResolutionNotSupported")) findings += resolutionFinding(video, codec, caps, settings, plan)
            if (handles("VideoBitDepthNotSupported")) findings += bitDepthFinding(video, codec, caps, settings, plan)
            if (handles("VideoRangeTypeNotSupported")) findings += rangeFinding(video, caps, settings, plan)
            if (handles("VideoFramerateNotSupported", "RefFramesNotSupported", "AnamorphicVideoNotSupported", "InterlacedVideoNotSupported", "VideoRotationNotSupported")) {
                val what = when {
                    "InterlacedVideoNotSupported" in reasons -> "The video is interlaced (made for old TVs), and this device can't show it as it is"
                    "AnamorphicVideoNotSupported" in reasons -> "The video uses stretched (anamorphic) pixels, which this device can't show as they are"
                    "VideoFramerateNotSupported" in reasons -> "The video's frame rate is more than this device's decoder takes"
                    "VideoRotationNotSupported" in reasons -> "The video is stored rotated, which this device can't undo itself"
                    else -> "The video uses more reference frames than this device's decoder takes"
                }
                findings += PlaybackFinding("The picture needs converting", "$what, so the server converts the picture.", versionFixes(plan),
                    adminTip = "A version encoded for playback devices (for example H.264 or HEVC, progressive) would play directly.")
            }
        }

        if (handles("ContainerBitrateExceedsLimit", "VideoBitrateNotSupported", "AudioBitrateNotSupported")) {
            findings += bitrateFinding(plan, settings)
        }

        if (audio != null && handles(
                "AudioCodecNotSupported", "AudioChannelsNotSupported", "AudioProfileNotSupported", "AudioSampleRateNotSupported",
                "AudioBitDepthNotSupported", "SecondaryAudioNotSupported", "UnknownAudioStreamInfo",
            )
        ) {
            findings += audioFinding(audio, reasons, streams, caps, settings, pictureTouched = videoTouched(reasons, burnIn, forcedTranscode))
        }
        if (handles("AudioIsExternal")) findings += PlaybackFinding(
            "The sound is a separate file",
            "This audio track is stored next to the video as its own file, so the server joins the two as it streams.",
            otherAudio(streams, audio, caps, settings, external = false),
        )

        if (burnIn) {
            handled += "SubtitleCodecNotSupported"
            findings += subtitleFinding(subtitle!!, streams)
        }

        if (handles("ContainerNotSupported")) findings += PlaybackFinding(
            "Repackaged for this device",
            "This device doesn't open ${plan.source.container?.uppercase()?.let { "$it files" } ?: "this kind of file"}, so the server " +
                "repackages it as it streams. Nothing is re-encoded and nothing is lost.",
        )
        if (handles("StreamCountExceedsLimit")) findings += PlaybackFinding(
            "Too many tracks",
            "The file holds more tracks than this device opens at once, so the server sends only the ones you're using.",
        )
        if (handles("UnknownVideoStreamInfo")) findings += PlaybackFinding(
            "The server doesn't know this file well",
            "Jellyfin hasn't fully analysed this file's video, so it plays it safe and converts it.",
            adminTip = "Refreshing the item's metadata (or a library scan) usually lets the server read the file properly.",
        )
        if (handles("DirectPlayError") && !forcedTranscode) findings += PlaybackFinding(
            "Direct play failed",
            "The server noted that playing this file directly failed, so it converts it.",
            listOf(PlaybackFix.Retry("Try direct play again", null)),
        )

        // Anything the server named that isn't covered above, said as plainly as we can.
        (reasons - handled).forEach { r ->
            findings += PlaybackFinding(r.toReadableReason(), "The server gave this as a reason to convert the file.", versionFixes(plan))
        }

        val quality = when {
            plan.method == PlayMethod.DIRECT_PLAY -> PlaybackQuality.ORIGINAL
            plan.method == PlayMethod.DIRECT_STREAM -> PlaybackQuality.REPACKAGED
            videoTouched(reasons, burnIn, forcedTranscode) || video == null && audio == null -> PlaybackQuality.PICTURE_CONVERTED
            else -> PlaybackQuality.SOUND_CONVERTED
        }
        val summary = when (quality) {
            PlaybackQuality.ORIGINAL -> "The file goes to this device untouched: best quality, no work for the server."
            PlaybackQuality.REPACKAGED -> "Same picture and sound; the server only rewraps the file as it streams."
            PlaybackQuality.SOUND_CONVERTED -> if (video == null) "The server converts the sound for this device." else "The picture is sent as it is; only the sound is converted."
            PlaybackQuality.PICTURE_CONVERTED -> "The server re-encodes the picture: this costs some quality and a lot of server power."
        }
        return PlaybackDiagnosis(quality, summary, findings.filter { plan.method != PlayMethod.DIRECT_PLAY || it.fixes.isNotEmpty() })
    }

    private val VIDEO_REASONS = setOf(
        "VideoCodecNotSupported", "VideoProfileNotSupported", "VideoLevelNotSupported", "VideoCodecTagNotSupported",
        "VideoResolutionNotSupported", "VideoBitDepthNotSupported", "VideoRangeTypeNotSupported", "VideoFramerateNotSupported",
        "RefFramesNotSupported", "AnamorphicVideoNotSupported", "InterlacedVideoNotSupported", "VideoRotationNotSupported",
        "VideoBitrateNotSupported", "ContainerBitrateExceedsLimit", "UnknownVideoStreamInfo", "DirectPlayError",
    )

    private fun videoTouched(reasons: Set<String>, burnIn: Boolean, forced: Boolean) = burnIn || forced || reasons.any { it in VIDEO_REASONS }

    private fun codecName(codec: String?) = when (codec?.lowercase()) {
        null -> "this format"
        "hevc", "h265" -> "HEVC"
        "h264", "avc" -> "H.264"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "vp8" -> "VP8"
        "mpeg2video" -> "MPEG-2"
        "mpeg4" -> "MPEG-4"
        "eac3" -> "Dolby Digital Plus"
        "ac3" -> "Dolby Digital"
        "truehd" -> "Dolby TrueHD"
        "dts" -> "DTS"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "pgssub" -> "PGS"
        "dvdsub", "dvd_subtitle" -> "DVD"
        "dvbsub" -> "DVB"
        else -> codec.uppercase()
    }

    private fun rangeName(range: String) = when {
        range.startsWith("DOVI") -> "Dolby Vision"
        range.startsWith("HDR10Plus") -> "HDR10+"
        else -> range
    }

    /** Other versions that would play better here, best first. */
    private fun versionFixes(plan: PlaybackPlan): List<PlaybackFix> =
        plan.versions.filter { it.id != plan.mediaSourceId && (it.directPlay || it.directStream && plan.method == PlayMethod.TRANSCODE) }
            .sortedByDescending { it.directPlay }
            .map { v ->
                PlaybackFix.UseVersion(
                    v.id, "Play ${v.name?.let { "\"$it\"" } ?: "another version"} instead",
                    if (v.directPlay) "That version plays directly on this device." else "That version only needs repackaging.",
                )
            }

    /** The user's own settings that stop [id], with the fixes for each. */
    private fun settingsBlocks(id: String, name: String, settings: PlaybackSettings): Pair<String, List<PlaybackFix>>? = when {
        settings.overrides[id] == Override.NEVER ->
            "$name is set to Never in this device's Playback settings." to
                listOf(PlaybackFix.Change(SettingsChange.ClearOverride(id), "Set $name back to Auto", null))
        settings.useLearnedFailures && id in settings.learnedFailures && settings.overrides[id] != Override.ALWAYS -> {
            val f = settings.learnedFailures.getValue(id)
            "$name failed to play on this device before (${f.lastError}), so Nitrate stopped asking for it." to
                listOf(PlaybackFix.Change(SettingsChange.ForgetFailure(id), "Try $name again", "If it fails again, Nitrate goes back to converting it."))
        }
        else -> null
    }

    private fun videoCodecFinding(video: MediaStream, codec: String?, caps: DeviceCapabilities, settings: PlaybackSettings, plan: PlaybackPlan): PlaybackFinding {
        val name = codecName(codec)
        codec?.let { settingsBlocks(it, name, settings) }?.let { (text, fixes) ->
            return PlaybackFinding("$name is turned off here", text, fixes + versionFixes(plan))
        }
        val decoder = codec?.let { caps.video[it] }
        val detail = listOfNotNull(video.profile, video.bitDepth?.let { "$it-bit" }).joinToString(", ")
        return if (decoder == null) PlaybackFinding(
            "This device can't decode $name",
            "There's no $name decoder on this device, so the server converts the picture.",
            versionFixes(plan) + listOfNotNull(
                codec?.takeIf { it in DeviceCapabilities.VIDEO_CODECS }?.let {
                    PlaybackFix.Change(SettingsChange.ForceAllow(it), "Try $name anyway", "Some devices play more than they report. If it fails, Nitrate goes back to converting.")
                },
            ),
            adminTip = "A version in H.264 or HEVC would play directly on this device.",
        ) else PlaybackFinding(
            "This $name file is beyond the decoder",
            "This device decodes $name, but not this file's kind" + (if (detail.isNotEmpty()) " ($detail)" else "") + ", so the server converts the picture.",
            versionFixes(plan),
            adminTip = "A version encoded with a more common $name profile would play directly.",
        )
    }

    private fun resolutionFinding(video: MediaStream, codec: String?, caps: DeviceCapabilities, settings: PlaybackSettings, plan: PlaybackPlan): PlaybackFinding {
        val height = video.height ?: 0
        val name = codecName(codec)
        val size = if (height >= 2000) "4K" else "${height}p"
        val fixes = mutableListOf<PlaybackFix>()
        val reasons = mutableListOf<String>()
        settings.maxResolution.height?.takeIf { height > it }?.let {
            reasons += "this device's Playback settings limit videos to ${settings.maxResolution.label}"
            fixes += PlaybackFix.Change(SettingsChange.ResolutionAuto, "Set the resolution limit to Auto", null)
        }
        codec?.let { settings.codecMaxHeight[it] }?.takeIf { height > it }?.let {
            reasons += "$name is limited to ${it}p here (set after the playback test)"
            fixes += PlaybackFix.Change(SettingsChange.LiftHeightLimit(codec), "Lift the ${it}p limit for $name", "It was set because larger $name videos stuttered here.")
        }
        val decoder = codec?.let { caps.video[it] }
        if (decoder != null && !decoder.hardware && height > CapabilityRules.SOFTWARE_MAX_HEIGHT && settings.overrides[codec] != Override.ALWAYS) {
            reasons += "this device decodes $name in software only, which Nitrate keeps to 1080p"
            fixes += PlaybackFix.Change(SettingsChange.ForceAllow(codec), "Allow $size $name anyway", "Software decoding may stutter. If it fails, Nitrate goes back to converting.")
        }
        if (decoder != null && height > decoder.maxHeight) reasons += "this device's $name decoder only goes up to ${decoder.maxHeight}p"
        val text = if (reasons.isEmpty()) "The video is $size, more than this device takes, so the server scales it down." else
            "The video is $size, but ${reasons.joinToString("; ")}. The server scales it down."
        return PlaybackFinding("The video is too large for this device", text, fixes + versionFixes(plan))
    }

    private fun bitDepthFinding(video: MediaStream, codec: String?, caps: DeviceCapabilities, settings: PlaybackSettings, plan: PlaybackPlan): PlaybackFinding {
        val name = codecName(codec)
        val fixes = mutableListOf<PlaybackFix>()
        val text = when {
            codec != null && codec in settings.eightBitOnly -> {
                fixes += PlaybackFix.Change(SettingsChange.AllowTenBit(codec), "Allow 10-bit $name", "It was turned off here because 10-bit $name failed.")
                "10-bit $name is turned off in this device's Playback settings."
            }
            codec != null && caps.video[codec]?.tenBit == false -> {
                fixes += PlaybackFix.Change(SettingsChange.AllowTenBit(codec), "Try 10-bit $name", "Many devices play 10-bit without saying so. The playback test can check for sure.")
                "This device doesn't report 10-bit $name, so the server converts the picture to 8-bit."
            }
            else -> "The video is ${video.bitDepth ?: 10}-bit, more than this device's decoder takes, so the server converts it."
        }
        return PlaybackFinding("10-bit video", text, fixes + versionFixes(plan))
    }

    private fun rangeFinding(video: MediaStream, caps: DeviceCapabilities, settings: PlaybackSettings, plan: PlaybackPlan): PlaybackFinding {
        val range = video.rangeType ?: "HDR"
        val name = rangeName(range)
        val id = CapabilityRules.failureCapabilityId(plan, videoFailed = true)
        id?.let { settingsBlocks(it, name, settings) }?.let { (text, fixes) ->
            return PlaybackFinding("$name is turned off here", text, fixes + versionFixes(plan))
        }
        val profile5 = range == "DOVI"
        val text = if (profile5) "This video is Dolby Vision with no HDR10 layer to fall back on, and this display doesn't do Dolby Vision, so the server converts its colours to SDR."
        else "This display doesn't show $name, so the server converts the colours to SDR (tone mapping). Colours may look flatter than intended."
        return PlaybackFinding("$name isn't shown on this display", text, versionFixes(plan),
            adminTip = "Hardware tone mapping on the server keeps this smooth; an SDR or HDR10 version would play directly.")
    }

    private fun bitrateFinding(plan: PlaybackPlan, settings: PlaybackSettings): PlaybackFinding {
        val bitrate = plan.source.bitrate
        val needed = bitrate?.let { b -> PlaybackSettings.BITRATES.filter { it >= b }.minOrNull() }
        val mbps = { b: Int -> "${(b + 999_999) / 1_000_000} Mbps" }
        val fixes = buildList<PlaybackFix> {
            if (needed != null && needed > settings.maxBitrate) add(PlaybackFix.Change(SettingsChange.Bitrate(needed), "Raise the limit to ${mbps(needed)}", "Only if your connection to the server keeps up."))
            addAll(versionFixes(plan))
        }
        val text = when {
            bitrate == null -> "The file is more than the bitrate limit allows, so the server sends a smaller stream."
            bitrate > settings.maxBitrate -> "The file needs ${mbps(bitrate)}, but this device's limit is ${mbps(settings.maxBitrate)}, so the server sends a smaller stream."
            else -> "The file needs ${mbps(bitrate)}. That's within this device's limit, so the limit is likely the server's own for your account."
        }
        return PlaybackFinding("The file is bigger than the limit", text, fixes,
            adminTip = "If you're away from home, the server's remote streaming limit for your account may apply (Dashboard → Users → your user).")
    }

    private fun audioFinding(audio: MediaStream, reasons: Set<String>, streams: List<MediaStream>, caps: DeviceCapabilities, settings: PlaybackSettings, pictureTouched: Boolean): PlaybackFinding {
        val codec = audio.codec?.lowercase()
        val name = codecName(codec)
        val others = otherAudio(streams, audio, caps, settings, external = true)
        val reassure = if (pictureTouched) "" else " The picture isn't touched."
        codec?.let { settingsBlocks(it, name, settings) }?.let { (text, fixes) ->
            return PlaybackFinding("$name sound is turned off here", text + reassure, fixes + others)
        }
        val supported = codec?.let { caps.audio[it]?.supported } == true
        val text = when {
            "AudioChannelsNotSupported" in reasons -> "This track has ${audio.channelLayout ?: "${audio.channels ?: "more"} channels"}, more than this device plays, so the server mixes it down."
            "SecondaryAudioNotSupported" in reasons -> "This device only plays a file's first audio track, so the server sends the one you picked on its own."
            !supported -> "This device can't decode $name sound or pass it to a receiver, so the server converts it."
            else -> "This $name track is a kind this device's decoder doesn't take, so the server converts it."
        }
        return PlaybackFinding("The sound needs converting", text + reassure, others)
    }

    /** Audio tracks this device plays as they are, the same language first. */
    private fun otherAudio(streams: List<MediaStream>, current: MediaStream?, caps: DeviceCapabilities, settings: PlaybackSettings, external: Boolean): List<PlaybackFix> =
        streams.filter { s ->
            s.kind == StreamKind.AUDIO && s.index != current?.index && (external || !s.isExternal) &&
                s.codec?.lowercase()?.let { c -> settings.allows(c, caps.audio[c]?.supported == true) } == true
        }
            .sortedByDescending { it.language != null && it.language == current?.language }
            .take(2)
            .map { s ->
                PlaybackFix.UseAudio(s.index, "Switch to ${s.title ?: listOfNotNull(s.language, codecName(s.codec)).joinToString(" ")}", "This track plays as it is here.")
            }

    private fun subtitleFinding(subtitle: MediaStream, streams: List<MediaStream>): PlaybackFinding {
        val name = codecName(subtitle.codec)
        val text = streams.filter { it.kind == StreamKind.SUBTITLE && it.index != subtitle.index && subtitleMimeType(it.codec) != null }
            .sortedByDescending { it.language != null && it.language == subtitle.language }
        val sameLanguage = text.firstOrNull { it.language == subtitle.language && it.isForced == subtitle.isForced }
        val fixes = buildList<PlaybackFix> {
            sameLanguage?.let { add(PlaybackFix.UseSubtitle(it.index, "Use ${it.title ?: it.language ?: "the text"} subtitles", "Text subtitles are drawn by this device: no converting.")) }
            add(PlaybackFix.UseSubtitle(-1, "Turn subtitles off", null))
        }
        val image = subtitleMimeType(subtitle.codec) == null
        val why = if (image) "$name subtitles are pictures, and this device can't draw them" else "This device can't show these $name subtitles itself"
        return PlaybackFinding(
            "Subtitles are being burned in",
            "$why, so the server paints them into the video. That means converting the whole picture.",
            fixes,
            adminTip = if (sameLanguage == null) "Downloading text subtitles for this title (Subtitles → Search) avoids it." else null,
        )
    }
}

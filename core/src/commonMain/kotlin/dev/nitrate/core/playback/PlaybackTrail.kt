package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import kotlinx.serialization.Serializable

/** Who made a step of the trail happen. */
@Serializable
enum class TrailActor(val label: String) {
    FILE("The file"),
    NITRATE("Nitrate"),
    SERVER("Jellyfin server"),
    MEASURED("Measured here"),
    DEVICE("This device"),
    YOU("You"),
}

/**
 * One step from the file to what comes out: [what] happened, decided by [who], [why]; [differs]
 * says what was predicted when it turned out otherwise.
 */
@Serializable
data class TrailStep(val step: String, val who: TrailActor, val what: String, val why: String? = null, val differs: String? = null)

/** The steps for one part of playback: picture, sound, subtitles or refresh rate. */
@Serializable
data class Trail(val title: String, val steps: List<TrailStep>)

/** What a trail is built from: the file, Nitrate's ask, the server's answer and what was measured while it played. */
data class TrailInput(
    val title: String,
    val source: MediaSource,
    val plan: PlaybackPlan?,
    val caps: DeviceCapabilities,
    val settings: PlaybackSettings,
    /** What was expected before playing (How it plays), to mark what turned out otherwise. */
    val prediction: PlaybackPrediction? = null,
    val video: VideoPath? = null,
    val audio: AudioPath? = null,
    /** The codecs that actually arrived at the decoders ("hevc", "dolby-vision", "eac3"), measured. */
    val arrivedVideoCodec: String? = null,
    val arrivedAudioCodec: String? = null,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    /** The display's refresh rate before playback started (TV), to show a switch. */
    val displayHzBefore: Float? = null,
    /** The refresh-rate choice for this playback. */
    val frameRateMatch: FrameRateMatch? = null,
    /** What the server itself reported doing while this played (its session's conversion), when it converted. */
    val serverReport: ServerConversion? = null,
    /** Smoothness measured while it played. */
    val smoothness: Smoothness? = null,
)

/** How smoothly it went: start-up, stalls, the network against what the stream needs, and a seek. */
@Serializable
data class Smoothness(
    /** From asking to play to the first picture. */
    val startMs: Long? = null,
    /** Times it stopped to wait for data after starting, and for how long in all. */
    val stalls: Int = 0,
    val stalledMs: Long = 0,
    val droppedFrames: Int = 0,
    /** The network as measured, and what the stream needs. */
    val networkBps: Long? = null,
    val streamBps: Long? = null,
    /** A test play's seek: how long until it played again, and whether surround still went out untouched after. */
    val seekResumeMs: Long? = null,
    val seekFailed: Boolean = false,
    val passthroughAfterSeek: Boolean? = null,
    /** What knocked sound and picture out of step, if anything ([SyncWatch]). */
    val syncTrouble: String? = null,
)

/**
 * Every decision between a file and what leaves the device, in order, with who made it and why:
 * the file, what Nitrate asked the server for (from this device and its settings), what the server
 * did, what arrived, the decoder, Nitrate's own processing, what went out and what only the TV or
 * receiver can show. Built while playing (the player's Info) or after a test play (the overview).
 */
@Serializable
data class PlaybackTrail(val title: String, val trails: List<Trail>) {
    /** Steps that turned out other than predicted. */
    val differences: List<String>
        get() = trails.flatMap { t -> t.steps.mapNotNull { s -> s.differs?.let { "${t.title}, ${s.step.lowercase()}: ${s.what} ($it)" } } }

    /** As text, for the server's logs. */
    fun render(): String = buildString {
        appendLine("Playback trail: $title")
        trails.forEach { t ->
            appendLine("== ${t.title} ==")
            t.steps.forEachIndexed { i, s ->
                appendLine("${i + 1}. ${s.step} [${s.who.label}]: ${s.what}")
                s.why?.let { appendLine("   why: $it") }
                s.differs?.let { appendLine("   differs: $it") }
            }
        }
        val d = differences
        appendLine(if (d.isEmpty()) "Everything went as predicted." else "Not as predicted:\n" + d.joinToString("\n") { "- $it" })
    }

    companion object {
        fun build(input: TrailInput): PlaybackTrail = with(input) {
            val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
            val audio = PlaybackPredictor.audio(source, plan?.audioIndex)
            val subtitle = plan?.subtitleIndex?.takeIf { it >= 0 }?.let { i -> source.streams.firstOrNull { it.kind == StreamKind.SUBTITLE && it.index == i } }
            PlaybackTrail(title, listOfNotNull(
                video?.let { picture(it) },
                audio?.let { sound(it) },
                subtitles(subtitle),
                video?.frameRate?.takeIf { it > 0 && displayHzBefore != null }?.let { refresh(it) },
                smoothness?.let { smooth(it) },
            ))
        }

        private fun TrailInput.picture(v: MediaStream): Trail {
            val codec = v.codec?.lowercase()
            val limit = CapabilityRules.videoLimits(caps, settings).firstOrNull { it.codec == codec }
            val hdr = CapabilityRules.hdrFormats(caps, settings)
            val ranges = hdr.joinToString("; ") { f ->
                val name = rangeName(f.id.removePrefix("range:"))
                val how = when (settings.overrides[f.id]) {
                    Override.ALWAYS -> "allowed (set to This device)"
                    Override.NEVER -> "refused (set to Server converts)"
                    null -> when {
                        f.claimed -> "allowed (the display reports it)"
                        else -> "refused (the display doesn't report it)"
                    }
                }
                "$name $how"
            }
            val asked = if (limit == null) "Not a codec this device plays: the server must convert it"
            else "${codecLabel(codec)} up to ${limit.maxWidth}×${limit.maxHeight}, ${if (limit.tenBit) "10-bit" else "8-bit"}" +
                (limit.tenBitMaxHeight?.let { " (10-bit up to ${it}p)" } ?: "")
            val converted = videoConverted()
            val expected = prediction?.delivery
            val actual = actualDelivery(converted)
            val serverWhat = when (plan?.method) {
                null -> "Not answered"
                PlayMethod.DIRECT_PLAY -> "Sent the file as it is (direct play)"
                PlayMethod.DIRECT_STREAM -> "Repackaged it (direct stream): the picture copied untouched"
                PlayMethod.TRANSCODE -> if (converted) "Converted the picture" + (video?.convertingTo?.let { " to $it" } ?: "") + if (toneMapped(v)) ", tone-mapped to SDR" else ""
                else "Copied the picture untouched (only other parts converted)"
            }
            val arrived = listOfNotNull(
                arrivedVideoCodec?.let(::mimeCodecLabel) ?: video?.inCodec?.let(::codecLabel),
                size(video?.inWidth, video?.inHeight),
                video?.inFps?.takeIf { it > 0 }?.let { "${PlaybackPredictor.hz(it)} fps" },
                video?.inRange,
            ).joinToString(" · ").ifEmpty { null }
            val keptHdr = v.rangeType?.let { it != "SDR" && it != "Unknown" } == true
            val decoderTier = videoDecoder?.let(Decoders::tierOf)
            val processing = listOfNotNull(
                video?.profile7?.let { p -> when (p) {
                    Profile7Playback.AS_PROFILE_8 -> "Dolby Vision profile 7 rewritten as 8.1"
                    Profile7Playback.BASE_LAYER -> "Dolby Vision profile 7 played as its HDR10 base layer"
                    Profile7Playback.DOLBY_VISION -> null
                } },
                video?.subtitles?.takeIf { "libass" in it }?.let { "styled subtitles drawn in a layer on top (picture untouched)" },
            )
            val out = listOfNotNull(
                size(video?.displayWidth, video?.displayHeight)?.let { s -> s + (video?.displayHz?.let { " @ ${PlaybackPredictor.hz(it)} Hz" } ?: "") },
                video?.hdrPath()?.substringAfter(", ", "")?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ").ifEmpty { null }
            val shownAs = when {
                video?.inRange == null || video.inRange == "SDR" -> "SDR"
                video.displayHdr?.contains(video.inRange) == true -> video.inRange
                video.inRange == "Dolby Vision" && video.displayHdr?.contains("HDR10") == true -> "HDR10"
                else -> "${video.inRange} if this device sends it as HDR, else SDR (Android reports no HDR here)"
            }
            return Trail("Picture", listOfNotNull(
                TrailStep("File", TrailActor.FILE, PlaybackPredictor.describe(v) + (source.container?.let { " in ${it.uppercase()}" } ?: "")),
                TrailStep("Asked for", TrailActor.NITRATE, asked, "HDR: $ranges"),
                TrailStep("Server", TrailActor.SERVER, serverWhat, plan?.transcodeReasons?.takeIf { it.isNotEmpty() }?.joinToString("; ") { reasonText(it) },
                    differs = if (expected != null && actual != null && expected != actual) "predicted: ${expected.label.lowercase()}" else null),
                serverReport?.let { r ->
                    val keeps = r.keepsUp(v.frameRate)
                    TrailStep("Server reported", TrailActor.SERVER, r.describe(v.frameRate),
                        r.reasons.takeIf { it.isNotEmpty() }?.joinToString("; ") { reasonText(it) },
                        differs = if (keeps == false) "too slow for real time: it will pause to catch up" else null)
                },
                TrailStep("Arrived", TrailActor.MEASURED, arrived ?: "Not yet",
                    differs = if (keptHdr && !converted && video?.inRange == "SDR") "the file is HDR but SDR arrived" else null),
                TrailStep("Decoded by", TrailActor.MEASURED, videoDecoder?.let(Decoders::describe) ?: "Not yet",
                    differs = prediction?.decoder?.takeIf { decoderTier != null && it != decoderTier && it != DecoderTier.NONE }?.let { "predicted: ${it.label.lowercase()}" }),
                TrailStep("Nitrate's processing", TrailActor.NITRATE, processing.ifEmpty { listOf("None: the picture goes on as decoded") }.joinToString("; ")),
                TrailStep("Went out", TrailActor.DEVICE, out ?: "Not known", video?.displayHdr?.let { "Android reports ${it.sorted().joinToString().ifEmpty { "no HDR" }} on this display" }),
                TrailStep("At the TV", TrailActor.YOU, "Expect $shownAs", "The app can't see the HDMI signal itself: check the TV's badge or picture mode."),
            ))
        }

        private fun TrailInput.sound(a: MediaStream): Trail {
            val codec = a.codec?.lowercase()
            val allowed = CapabilityRules.audioCodecs(caps, settings).any { it.id == codec && it.allowed }
            val passthrough = caps.audio[codec]?.passthrough == true
            val asked = when {
                !allowed -> "${codecLabel(codec)} isn't taken here: the server must convert it"
                passthrough -> "${codecLabel(codec)} as it is; the output takes it untouched"
                else -> "${codecLabel(codec)} as it is, decoded here"
            }
            val soundConverted = plan?.method == PlayMethod.TRANSCODE && plan.transcodeReasons.any { it.startsWith("Audio") } ||
                (arrivedAudioCodec != null && codec != null && !sameAudio(codec, arrivedAudioCodec))
            val serverWhat = when {
                plan == null -> "Not answered"
                soundConverted -> "Converted the sound" + (arrivedAudioCodec?.let { " to ${mimeCodecLabel(it)}" } ?: "")
                else -> "Sent the sound untouched"
            }
            val path = audio
            val arrived = listOfNotNull(arrivedAudioCodec?.let(::mimeCodecLabel), PlaybackPredictor.channels(path?.inChannels), path?.inRateHz?.let { "${it / 1000.0} kHz".replace(".0 ", " ") }).joinToString(" · ").ifEmpty { null }
            val decodedHere = path?.delivery == AudioDelivery.DECODED
            val processing = listOfNotNull(path?.upmixedTo?.let { "upmixed stereo → $it" }) + path?.processing.orEmpty()
            val out = listOfNotNull(path?.outFormat, PlaybackPredictor.channels(path?.outChannels), path?.output).joinToString(" · ").ifEmpty { null }
            val expectedRoute = prediction?.sound
            val routeDiffers = when {
                path?.delivery == null || expectedRoute == null -> null
                expectedRoute == SoundRoute.PASSTHROUGH && decodedHere -> "predicted: sent untouched"
                expectedRoute == SoundRoute.DECODED && path.delivery == AudioDelivery.BITSTREAM -> "predicted: decoded here"
                else -> null
            }
            return Trail("Sound", listOf(
                TrailStep("File", TrailActor.FILE, listOfNotNull(codecLabel(codec), PlaybackPredictor.channels(a.channels), a.language?.let { "($it)" }).joinToString(" ")),
                TrailStep("Asked for", TrailActor.NITRATE, asked),
                TrailStep("Server", TrailActor.SERVER, serverWhat),
                TrailStep("Arrived", TrailActor.MEASURED, arrived ?: "Not yet"),
                TrailStep("Decoded by", TrailActor.MEASURED, when {
                    path?.delivery == AudioDelivery.BITSTREAM -> "Not decoded here: ${Decoders.PASSED_THROUGH.replaceFirstChar { it.lowercase() }}"
                    audioDecoder != null -> Decoders.describe(audioDecoder)
                    else -> "Not yet"
                }, path?.decodedWhy?.takeIf { decodedHere }),
                TrailStep("Nitrate's processing", TrailActor.NITRATE, processing.ifEmpty { listOf("None") }.joinToString("; ")),
                TrailStep("Went out", TrailActor.DEVICE, out ?: "Not yet", differs = routeDiffers),
                TrailStep("At the receiver", TrailActor.YOU,
                    "Expect " + if (path?.delivery == AudioDelivery.BITSTREAM) (path.outFormat ?: codecLabel(codec)) else "PCM ${PlaybackPredictor.channels(path?.outChannels) ?: ""}".trim(),
                    "Check the receiver's display."),
            ))
        }

        private fun TrailInput.subtitles(s: MediaStream?): Trail? {
            if (s == null) return null
            val server = when (s.delivery) {
                SubtitleDelivery.ENCODE -> "Burned them into the picture (the picture is converted)"
                SubtitleDelivery.EXTERNAL -> "Sent them as their own file"
                SubtitleDelivery.HLS -> "Sent them in the stream"
                else -> "Left them in the file"
            }
            return Trail("Subtitles", listOf(
                TrailStep("File", TrailActor.FILE, listOfNotNull(s.codec?.uppercase(), s.language, s.title).joinToString(" · ")),
                TrailStep("Server", TrailActor.SERVER, server),
                TrailStep("Drawn by", TrailActor.NITRATE, video?.subtitles ?: "Not yet"),
            ))
        }

        private fun TrailInput.refresh(fps: Float): Trail {
            val before = displayHzBefore!!
            val now = video?.displayHz
            val what = when {
                now == null -> "Not known"
                kotlin.math.abs(now - before) > 0.01f -> "Switched the TV from ${PlaybackPredictor.hz(before)} Hz to ${PlaybackPredictor.hz(now)} Hz"
                else -> "Stayed at ${PlaybackPredictor.hz(now)} Hz"
            }
            return Trail("Refresh rate", listOfNotNull(
                TrailStep("File", TrailActor.FILE, "${PlaybackPredictor.hz(fps)} fps"),
                TrailStep("Setting", TrailActor.NITRATE, (frameRateMatch ?: settings.frameRateMatch).label),
                TrailStep("Display", TrailActor.NITRATE, what, now?.let { n -> when (DisplayModes.cadence(fps, n)) {
                    DisplayModes.Cadence.EXACT -> "Every frame shown for the same time: smooth"
                    DisplayModes.Cadence.NEAR -> "Close: a tiny repeat now and then"
                    DisplayModes.Cadence.JUDDER -> "Uneven: frames shown for different times (judder)"
                    else -> null
                } }),
            ))
        }

        private fun TrailInput.smooth(m: Smoothness): Trail {
            fun mbps(b: Long) = "${dev.nitrate.core.model.formatOneDecimal(b / 1_000_000.0)} Mbps"
            val slowNet = m.networkBps != null && m.streamBps != null && m.networkBps < m.streamBps
            return Trail("Smoothness", listOfNotNull(
                m.startMs?.let { TrailStep("Start", TrailActor.MEASURED, "First picture after ${dev.nitrate.core.model.formatOneDecimal(it / 1000.0)} s", "includes any refresh-rate switch",
                    differs = if (it > 15_000) "slow to start" else null) },
                TrailStep("Stalls", TrailActor.MEASURED,
                    if (m.stalls == 0) "None: it never stopped to wait for data" else "${m.stalls} stall${if (m.stalls == 1) "" else "s"}, ${dev.nitrate.core.model.formatOneDecimal(m.stalledMs / 1000.0)} s in all",
                    if (m.stalls > 0 && slowNet) "The network is slower than the stream needs" else null,
                    differs = if (m.stalls > 0) "stopped to buffer" else null),
                TrailStep("Sound and picture", TrailActor.MEASURED, m.syncTrouble ?: "In step: nothing knocked them apart",
                    differs = if (m.syncTrouble != null) "out of step" else null),
                TrailStep("Dropped frames", TrailActor.MEASURED, "${m.droppedFrames}",
                    differs = if (m.droppedFrames > TestPlay.DROPPED_OK) "more than a few: the picture stuttered" else null),
                m.networkBps?.let { n -> TrailStep("Network", TrailActor.MEASURED, mbps(n) + (m.streamBps?.let { " (the stream needs ${mbps(it)})" } ?: ""),
                    if (slowNet) "Slower than the stream: it plays only while the buffer lasts" else null) },
                if (m.seekResumeMs != null || m.seekFailed) TrailStep("Seek", TrailActor.MEASURED,
                    if (m.seekFailed) "Jumped ahead but didn't play again" else "Jumped ahead and played again after ${dev.nitrate.core.model.formatOneDecimal(m.seekResumeMs!! / 1000.0)} s",
                    m.passthroughAfterSeek?.let { if (it) "Surround still went out untouched after it" else "Surround was decoded after it" },
                    differs = when {
                        m.seekFailed -> "seeking failed"
                        m.passthroughAfterSeek == false -> "lost untouched surround after seeking"
                        (m.seekResumeMs ?: 0) > 10_000 -> "slow to play again"
                        else -> null
                    }) else null,
            ))
        }

        private fun TrailInput.videoConverted(): Boolean {
            val p = plan ?: return false
            if (p.method != PlayMethod.TRANSCODE) return false
            serverReport?.videoDirect?.let { return !it }
            if (p.transcodeReasons.isEmpty()) return true
            return p.transcodeReasons.any { it.startsWith("Video") || it.startsWith("Subtitle") || "Bitrate" in it || it == "DirectPlayError" || it == "UnknownVideoStreamInfo" }
        }

        private fun TrailInput.actualDelivery(converted: Boolean): PredictedDelivery? = when (plan?.method) {
            null -> null
            PlayMethod.DIRECT_PLAY -> PredictedDelivery.DIRECT
            PlayMethod.DIRECT_STREAM -> PredictedDelivery.REMUX
            PlayMethod.TRANSCODE -> if (converted) PredictedDelivery.CONVERTED else PredictedDelivery.SOUND_CONVERTED
        }

        private fun TrailInput.toneMapped(v: MediaStream): Boolean =
            v.rangeType?.let { it != "SDR" && it != "Unknown" } == true && video?.inRange == "SDR"

        private fun sameAudio(fileCodec: String, arrived: String): Boolean {
            val a = arrived.lowercase().substringAfter('/')
            return when (fileCodec) {
                "ac3" -> a == "ac3"
                "eac3" -> a == "eac3" || a == "eac3-joc"
                "dts" -> a.startsWith("vnd.dts")
                "truehd" -> a == "true-hd" || a == "truehd"
                "aac" -> a == "mp4a-latm" || a == "aac"
                "mp3" -> a == "mpeg"
                "flac" -> a == "flac"
                "opus" -> a == "opus"
                "vorbis" -> a == "vorbis"
                "alac" -> a == "alac"
                else -> a.contains(fileCodec) || a.startsWith("raw")
            }
        }

        /** A Media3 MIME type as a codec name: "video/hevc" → HEVC, "audio/eac3-joc" → Dolby Digital Plus (Atmos). */
        fun mimeCodecLabel(mime: String): String = when (mime.lowercase().substringAfter('/')) {
            "hevc" -> "HEVC"
            "dolby-vision" -> "HEVC (Dolby Vision)"
            "avc" -> "H.264"
            "av01" -> "AV1"
            "x-vnd.on2.vp9" -> "VP9"
            "mpeg2" -> "MPEG-2"
            "mp4v-es" -> "MPEG-4"
            "wvc1" -> "VC-1"
            "ac3" -> "Dolby Digital"
            "eac3" -> "Dolby Digital Plus"
            "eac3-joc" -> "Dolby Digital Plus (Atmos)"
            "true-hd" -> "Dolby TrueHD"
            "vnd.dts" -> "DTS"
            "vnd.dts.hd" -> "DTS-HD"
            "vnd.dts.uhd;profile=p2" -> "DTS:X"
            "mp4a-latm" -> "AAC"
            "mpeg" -> "MP3"
            "raw" -> "PCM"
            else -> codecLabel(mime.substringAfter('/'))
        }

        /** Jellyfin's transcode reasons in plain words. */
        fun reasonText(reason: String): String = when (reason) {
            "ContainerNotSupported" -> "the container isn't one this device takes"
            "VideoCodecNotSupported" -> "the video codec isn't one this device takes"
            "AudioCodecNotSupported" -> "the sound's codec isn't one this device takes"
            "SubtitleCodecNotSupported" -> "the subtitles had to be drawn into the picture"
            "AudioIsExternal" -> "the sound is in a separate file"
            "SecondaryAudioNotSupported" -> "a second sound track isn't supported"
            "VideoProfileNotSupported" -> "the video profile isn't one this device takes"
            "VideoLevelNotSupported" -> "the video level is above this device's"
            "VideoResolutionNotSupported" -> "the resolution is above this device's limit"
            "VideoBitDepthNotSupported" -> "the bit depth isn't one this device takes"
            "VideoFramerateNotSupported" -> "the frame rate isn't one this device takes"
            "RefFramesNotSupported" -> "too many reference frames for this device"
            "AnamorphicVideoNotSupported" -> "anamorphic video isn't supported"
            "InterlacedVideoNotSupported" -> "interlaced video isn't supported here"
            "AudioChannelsNotSupported" -> "too many sound channels for this device"
            "AudioProfileNotSupported" -> "the sound's profile isn't one this device takes"
            "AudioSampleRateNotSupported" -> "the sound's sample rate isn't one this device takes"
            "AudioBitDepthNotSupported" -> "the sound's bit depth isn't one this device takes"
            "ContainerBitrateExceedsLimit" -> "the bitrate is above the limit"
            "VideoBitrateNotSupported" -> "the video bitrate is above the limit"
            "AudioBitrateNotSupported" -> "the sound's bitrate is above the limit"
            "UnknownVideoStreamInfo", "UnknownAudioStreamInfo" -> "the server doesn't know enough about the file"
            "DirectPlayError" -> "playing it as it is failed before"
            "VideoRangeTypeNotSupported" -> "the HDR format isn't allowed for this device"
            "VideoCodecTagNotSupported" -> "the video's codec tag isn't one this device takes"
            "StreamCountExceedsLimit" -> "too many streams in the file"
            else -> reason
        }

        private fun rangeName(r: String) = when (r) { "DOVI" -> "Dolby Vision"; "HDR10Plus" -> "HDR10+"; else -> r }

        private fun size(w: Int?, h: Int?) = if (w != null && h != null && w > 0 && h > 0) "${w}×$h" else null
    }
}

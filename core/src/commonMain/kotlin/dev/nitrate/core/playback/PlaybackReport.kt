package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind

/**
 * A plain-text report for working out a playback problem: the app and device, what the device
 * reports and what Nitrate allows from it, the file's streams, what the server decided and why,
 * and what the player did. Sent to the user's own Jellyfin server as a client log; no tokens.
 */
data class PlaybackReport(
    val app: String,
    val version: String,
    val createdAt: String,
    val caps: DeviceCapabilities,
    val settings: PlaybackSettings,
    val item: MediaItem? = null,
    val plan: PlaybackPlan? = null,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    /** The player's recent events, oldest first. */
    val events: List<String> = emptyList(),
    val error: String? = null,
) {
    fun render(): String = buildString {
        line("$app $version playback report, $createdAt")
        line("Device: ${caps.model}, display ${caps.displayWidth}x${caps.displayHeight}")
        line()

        item?.let { i ->
            section("Item")
            line("${i.displayTitle}${i.episodeLabel?.let { " $it" }.orEmpty()} (${i.kind}, id ${i.id})")
            line()
        }

        plan?.let { p ->
            section("Server decision")
            line("Method: ${p.method}")
            if (p.transcodeReasons.isNotEmpty()) line("Reasons: ${p.transcodeReasons.joinToString(", ")}")
            line("Container: ${p.source.container ?: "?"}, bitrate ${p.source.bitrate?.let { "${it / 1000} kbps" } ?: "?"}")
            line("Audio index: ${p.audioIndex ?: "default"}, subtitle index: ${p.subtitleIndex}")
            line("URL: ${redact(p.url)}")
            line()
            section("File streams")
            p.source.streams.forEach { s ->
                val parts = listOfNotNull(
                    s.codec, s.profile,
                    s.width?.let { w -> s.height?.let { h -> "${w}x$h" } },
                    s.bitDepth?.let { "$it-bit" }, s.rangeType,
                    s.channelLayout ?: s.channels?.let { "$it ch" },
                    s.language, s.delivery?.name?.lowercase(),
                    "default".takeIf { s.isDefault }, "forced".takeIf { s.isForced }, "external".takeIf { s.isExternal },
                )
                line("#${s.index} ${s.kind}: ${parts.joinToString(", ")}" + (s.title?.let { "  ($it)" } ?: ""))
            }
            line()
        }

        if (videoDecoder != null || audioDecoder != null) {
            section("Decoders in use")
            videoDecoder?.let { line("Video: $it") }
            audioDecoder?.let { line("Audio: $it") }
            line()
        }

        section("What this device reports")
        caps.video.values.sortedBy { it.codec }.forEach { v ->
            line("Video ${v.codec}: ${v.decoderName} (${if (v.hardware) "hardware" else "software"}), up to ${v.maxWidth}x${v.maxHeight}, ${if (v.tenBit) "10-bit" else "8-bit"}")
        }
        caps.audio.values.sortedBy { it.codec }.forEach { a ->
            line("Audio ${a.codec}: decoder ${a.decoder ?: "none"}, passthrough ${if (a.passthrough) "yes" else "no"}")
        }
        line("HDR on the display: ${caps.hdr.sorted().joinToString(", ").ifEmpty { "none" }}")
        if (caps.dolbyVisionProfiles.isNotEmpty()) line("Dolby Vision decoder profiles: ${caps.dolbyVisionProfiles.sorted().joinToString(", ")}")
        line()

        section("What Nitrate allows (sent to the server)")
        CapabilityRules.videoLimits(caps, settings).forEach { l ->
            line("Video ${l.codec}: up to ${l.maxWidth}x${l.maxHeight}, ${if (l.tenBit) "10-bit" else "8-bit"}")
        }
        line("Video off: ${CapabilityRules.videoCodecs(caps, settings).filterNot { it.allowed }.joinToString(", ") { it.id }.ifEmpty { "none" }}")
        line("Audio: ${CapabilityRules.audioCodecs(caps, settings).filter { it.allowed }.joinToString(", ") { it.id }}")
        line("Ranges shown as they are: ${CapabilityRules.allowedRangeTypes(caps, settings).joinToString(", ")}")
        line("Max bitrate ${settings.maxBitrate / 1_000_000} Mbps, max resolution ${settings.maxResolution.label}, direct play ${settings.allowDirectPlay}, direct stream ${settings.allowDirectStream}")
        if (settings.overrides.isNotEmpty()) line("Overrides: ${settings.overrides.entries.joinToString(", ") { "${it.key}=${it.value.label}" }}")
        if (settings.learnedFailures.isNotEmpty()) line("Learned failures: ${settings.learnedFailures.entries.joinToString(", ") { "${it.key} (${it.value.count}x, ${it.value.lastError})" }}")
        if (settings.codecMaxHeight.isNotEmpty()) line("Height limits: ${settings.codecMaxHeight}")
        if (settings.eightBitOnly.isNotEmpty()) line("8-bit only: ${settings.eightBitOnly.joinToString(", ")}")
        if (settings.tenBitAllowed.isNotEmpty()) line("10-bit allowed by test: ${settings.tenBitAllowed.joinToString(", ")}")
        line()

        error?.let {
            section("Error")
            line(it)
            line()
        }

        if (events.isNotEmpty()) {
            section("Player events")
            events.forEach { line(it) }
        }
    }

    private fun StringBuilder.line(text: String = "") { append(text).append('\n') }
    private fun StringBuilder.section(title: String) { line("== $title ==") }

    companion object {
        /** Query parameters that carry credentials, dropped from any URL in a report. */
        private val SECRET_PARAMS = Regex("([?&])(api_key|apikey|accesstoken|access_token|token|X-Emby-Token|X-MediaBrowser-Token)=[^&]*", RegexOption.IGNORE_CASE)

        fun redact(url: String): String = url.replace(SECRET_PARAMS, "$1$2=<removed>")

        /** Which streams a plan carries, for an event line. */
        fun streamSummary(plan: PlaybackPlan): String = plan.source.streams
            .filter { it.kind == StreamKind.VIDEO || it.kind == StreamKind.AUDIO }
            .joinToString("; ") { listOfNotNull(it.kind.name.lowercase(), it.codec, it.bitDepth?.let { b -> "$b-bit" }, it.rangeType).joinToString(" ") }
    }
}

package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind

/** A track as the player has it, while playing. */
data class PlayerTrack(
    /** "video", "audio", "text"... */
    val type: String,
    val id: String?,
    val language: String?,
    val label: String?,
    val mimeType: String?,
    val codecs: String?,
    val selected: Boolean,
    val supported: Boolean,
)

/** How subtitles are being drawn right now. */
data class SubtitleDrawing(val mode: StyledSubtitles, val fileStyles: Boolean)

/**
 * Extended media information as plain text, for troubleshooting and copying: the file and every
 * stream, the server's picks for this user, and while playing, what the player has, what's on,
 * how subtitles are drawn, and what a downloaded subtitle file holds.
 */
object MediaInfo {
    fun render(
        item: MediaItem?,
        source: MediaSource,
        plan: PlaybackPlan? = null,
        local: Boolean = false,
        playerTracks: List<PlayerTrack>? = null,
        subtitles: SubtitleDrawing? = null,
        videoDecoder: String? = null,
        audioDecoder: String? = null,
        /** For a download: what each saved subtitle file holds (name → a few lines about it). */
        subtitleFiles: Map<String, List<String>> = emptyMap(),
    ): String = buildString {
        item?.let { i ->
            section("Title")
            line("${i.displayTitle}${i.episodeLabel?.let { " $it" }.orEmpty()} (${i.kind.name.lowercase()}, id ${i.id})")
            line()
        }

        section("File")
        if (local) line("Downloaded copy on this device")
        source.name?.let { line("Version: $it") }
        line("Container: ${source.container ?: "?"}")
        source.size?.let { line("Size: ${size(it)}") }
        source.bitrate?.let { line("Bitrate: ${it / 1000} kbps") }
        line()

        section("Streams")
        source.streams.forEach { s ->
            val marks = listOfNotNull(
                "server's pick".takeIf { source.defaultsKnown && (s.index == source.defaultAudioIndex || s.index == source.defaultSubtitleIndex) },
                "selected".takeIf { plan != null && (s.index == plan.audioIndex || s.index == plan.subtitleIndex) && s.kind != StreamKind.VIDEO },
            )
            line("#${s.index} ${describe(s)}" + if (marks.isEmpty()) "" else "  ← ${marks.joinToString(", ")}")
        }
        line()

        if (source.defaultsKnown) {
            section("Your server's picks (from your account's language settings)")
            line("Audio: ${source.defaultAudioIndex?.let { "#$it" } ?: "none"}")
            line("Subtitles: ${source.defaultSubtitleIndex?.takeIf { it >= 0 }?.let { "#$it" } ?: "off"}")
            line()
        }

        plan?.let { p ->
            section("Playing")
            line("Method: ${p.method}${if (local) " (from the device)" else ""}")
            if (p.transcodeReasons.isNotEmpty()) line("Converting because: ${p.transcodeReasons.joinToString(", ")}")
            line("Audio: ${p.audioIndex?.let { "#$it" } ?: "default"}, subtitles: ${p.subtitleIndex.takeIf { it >= 0 }?.let { "#$it" } ?: "off"}")
            if (p.externalSubtitles.isNotEmpty()) {
                line("Subtitles loaded beside the video: " + p.externalSubtitles.joinToString(", ") { "#${it.streamIndex} ${it.mimeType}${it.language?.let { l -> " $l" }.orEmpty()}" })
            }
            line()
        }

        subtitles?.let { d ->
            section("Subtitle drawing")
            line("Styled (ASS/SSA) subtitles: ${d.mode.label}")
            line("The file's own styles: ${if (d.fileStyles) "shown" else "replaced by your subtitle style"}")
            line()
        }

        if (videoDecoder != null || audioDecoder != null) {
            section("Decoders")
            videoDecoder?.let { line("Video: $it") }
            audioDecoder?.let { line("Audio: $it") }
            line()
        }

        playerTracks?.let { tracks ->
            section("What the player has")
            if (tracks.isEmpty()) line("No tracks yet")
            tracks.forEach { t ->
                val parts = listOfNotNull(t.language, t.label?.let { "\"$it\"" }, t.mimeType, t.codecs, t.id?.let { "id $it" }, "can't play here".takeIf { !t.supported })
                line("${if (t.selected) "[on] " else "     "}${t.type}: ${parts.joinToString(" · ")}")
            }
            line()
        }

        subtitleFiles.forEach { (name, about) ->
            section("Subtitle file $name")
            about.forEach { line(it) }
            line()
        }
    }.trimEnd() + "\n"

    /** One stream in a line: what it is, its language and title, and how it's flagged. */
    fun describe(s: MediaStream): String {
        val parts = listOfNotNull(
            s.codec, s.profile,
            s.width?.let { w -> s.height?.let { h -> "${w}x$h" } },
            s.bitDepth?.let { "$it-bit" }, s.rangeType, s.frameRate?.let { "${fps(it)} fps" },
            s.channelLayout?.let { l -> s.channels?.let { "$l ($it ch)" } ?: l } ?: s.channels?.let { "$it ch" },
            s.language, s.title?.let { "\"$it\"" },
            "default".takeIf { s.isDefault }, "forced".takeIf { s.isForced },
            "external file".takeIf { s.isExternal }, s.delivery?.let { "delivered ${it.name.lowercase()}" },
        )
        val kind = s.kind.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$kind: ${parts.joinToString(" · ")}"
    }

    /**
     * A few lines about an ASS/SSA file's text: its script header and how many styles it defines
     * (with the first few), so missing styling can be traced to the file or the player.
     */
    fun aboutAss(text: String): List<String> {
        val lines = text.removePrefix("﻿").lines()
        val styles = lines.filter { it.startsWith("Style:", ignoreCase = true) }
        val events = lines.count { it.startsWith("Dialogue:", ignoreCase = true) }
        return buildList {
            add("Starts with: ${lines.firstOrNull()?.take(40).orEmpty()}${if (text.startsWith("﻿")) " (after a byte-order mark)" else ""}")
            add("Styles: ${styles.size}, lines of dialogue: $events")
            styles.take(5).forEach { add("  $it") }
            if (styles.size > 5) add("  …and ${styles.size - 5} more")
        }
    }

    /** 23.976, 25, 59.94: up to three decimals, no trailing zeros. */
    private fun fps(rate: Float): String {
        val thousandths = kotlin.math.round(rate * 1000).toLong()
        val whole = thousandths / 1000
        val frac = (thousandths % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole" else "$whole.$frac"
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
        bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
        else -> "${bytes / 1024} KB"
    }

    private fun StringBuilder.line(text: String = "") { append(text).append('\n') }
    private fun StringBuilder.section(title: String) { line("== $title ==") }
}

package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind

/** One choice in a track list; [index] is the server stream index (-1 for "Off"). */
data class TrackChoice(val index: Int, val title: String, val detail: String?)

fun PlaybackPlan.audioChoices(): List<TrackChoice> =
    source.streams.filter { it.kind == StreamKind.AUDIO }.map { it.toChoice() }

fun PlaybackPlan.subtitleChoices(): List<TrackChoice> =
    listOf(TrackChoice(-1, "Off", null)) + source.streams.filter { it.kind == StreamKind.SUBTITLE }.map { it.toChoice() }

private fun MediaStream.toChoice(): TrackChoice {
    val flags = buildList {
        if (isDefault) add("Default")
        if (isForced) add("Forced")
        if (isExternal) add("External")
        codec?.let { add(it.uppercase()) }
        channelLayout?.let { add(it) }
    }
    return TrackChoice(index, title ?: language ?: "Track $index", flags.joinToString(" · ").ifEmpty { null })
}

/** "VideoCodecNotSupported" -> "Video codec not supported". */
fun String.toReadableReason(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

/** Label/value rows describing how a plan plays, for an info panel. */
fun PlaybackPlan.infoRows(videoDecoder: String?, audioDecoder: String?): List<Pair<String, String>> {
    val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
    val audio = source.streams.firstOrNull { it.index == audioIndex }
    return buildList {
        add(
            "Method" to when (method) {
                PlayMethod.DIRECT_PLAY -> "Direct play"
                PlayMethod.DIRECT_STREAM -> "Direct stream (remux)"
                PlayMethod.TRANSCODE -> "Transcode"
            },
        )
        if (transcodeReasons.isNotEmpty()) add("Why" to transcodeReasons.joinToString(", ") { it.toReadableReason() })
        add("Container" to (source.container ?: "?"))
        source.bitrate?.let { add("Bitrate" to "%.1f Mbps".format(it / 1_000_000.0)) }
        video?.let {
            add("Video" to listOfNotNull(it.codec?.uppercase(), it.profile, "${it.width}×${it.height}", it.rangeType, it.bitDepth?.let { b -> "$b-bit" }).joinToString(" · "))
        }
        audio?.let { add("Audio" to listOfNotNull(it.codec?.uppercase(), it.channelLayout, it.language).joinToString(" · ")) }
        videoDecoder?.let { add("Video decoder" to it) }
        audioDecoder?.let { add("Audio decoder" to it) }
    }
}

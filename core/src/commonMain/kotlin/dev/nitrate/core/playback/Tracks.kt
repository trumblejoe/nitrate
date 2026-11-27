package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.formatOneDecimal

/** One choice in a track list; [index] is the server stream index (-1 for "Off"). */
data class TrackChoice(val index: Int, val title: String, val detail: String?)

fun PlaybackPlan.audioChoices(): List<TrackChoice> = source.audioChoices()

fun PlaybackPlan.subtitleChoices(): List<TrackChoice> = source.subtitleChoices()

fun MediaSource.audioChoices(): List<TrackChoice> = streams.filter { it.kind == StreamKind.AUDIO }.map { it.toChoice() }

fun MediaSource.subtitleChoices(): List<TrackChoice> =
    listOf(TrackChoice(-1, "Off", null)) + streams.filter { it.kind == StreamKind.SUBTITLE }.map { it.toChoice() }

/** A short name for stream [index] ("Japanese · 5.1", "English (Signs)"), or "Off" for -1. */
fun MediaSource.trackName(index: Int?): String {
    if (index == null) return "Default"
    if (index < 0) return "Off"
    val s = streams.firstOrNull { it.index == index } ?: return "Track $index"
    val language = s.language?.let(::languageName)
    val base = language ?: s.title ?: "Track $index"
    val extra = listOfNotNull(s.title?.takeIf { language != null && !it.equals(language, ignoreCase = true) && !it.contains(language, ignoreCase = true) }, "Forced".takeIf { s.isForced })
    return base + (if (extra.isEmpty()) "" else " (${extra.joinToString(", ")})") + (s.channelLayout?.let { " · $it" } ?: "")
}

/** "jpn"/"ja" → "Japanese" for the common ones; anything else as it is. */
fun languageName(code: String): String = LANGUAGES[code.lowercase()] ?: code

private val LANGUAGES = mapOf(
    "eng" to "English", "en" to "English", "jpn" to "Japanese", "ja" to "Japanese", "spa" to "Spanish", "es" to "Spanish",
    "fre" to "French", "fra" to "French", "fr" to "French", "ger" to "German", "deu" to "German", "de" to "German",
    "ita" to "Italian", "it" to "Italian", "por" to "Portuguese", "pt" to "Portuguese", "kor" to "Korean", "ko" to "Korean",
    "chi" to "Chinese", "zho" to "Chinese", "zh" to "Chinese", "rus" to "Russian", "ru" to "Russian", "dut" to "Dutch", "nld" to "Dutch",
    "nl" to "Dutch", "swe" to "Swedish", "sv" to "Swedish", "nor" to "Norwegian", "no" to "Norwegian", "dan" to "Danish", "da" to "Danish",
    "fin" to "Finnish", "fi" to "Finnish", "pol" to "Polish", "pl" to "Polish", "hin" to "Hindi", "hi" to "Hindi", "ara" to "Arabic", "ar" to "Arabic",
)

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

/**
 * Label/value rows describing how a plan plays, for an info panel: what the server does, the file,
 * and who decodes the picture and the sound, in plain words with the decoder's name in brackets
 * ([Decoders]; [soundPassedThrough]: the sound goes on untouched to the TV or receiver).
 */
fun PlaybackPlan.infoRows(
    videoDecoder: String?, audioDecoder: String?, soundPassedThrough: Boolean = false,
    /** Already in plain words (mpv's, Apple's player): shown as they are in place of [videoDecoder]/[audioDecoder]. */
    pictureWords: String? = null, soundWords: String? = null,
    /** The quality limit this plays with (away, mobile data, lowered); a row only when it's more than home with no limit. */
    quality: QualityDecision? = null,
): List<Pair<String, String>> {
    val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
    val audio = source.streams.firstOrNull { it.index == audioIndex }
    return buildList {
        add("Server" to Decoders.serverWords(this@infoRows))
        if (transcodeReasons.isNotEmpty()) add("Why" to transcodeReasons.joinToString(", ") { it.toReadableReason() })
        quality?.takeIf { it.place != NetworkPlace.HOME || it.lowered || it.limitBps < PlaybackSettings.NO_LIMIT }?.let { add("Quality" to it.describe()) }
        add("Container" to (source.container ?: "?"))
        source.bitrate?.let { add("Bitrate" to "${formatOneDecimal(it / 1_000_000.0)} Mbps") }
        video?.let {
            add("Video" to listOfNotNull(it.codec?.uppercase(), it.profile, "${it.width}×${it.height}", it.rangeType, it.bitDepth?.let { b -> "$b-bit" }).joinToString(" · "))
        }
        audio?.let { add("Audio" to listOfNotNull(it.codec?.uppercase(), it.channelLayout, it.language).joinToString(" · ")) }
        (pictureWords ?: videoDecoder?.let(Decoders::describe))?.let { add("Picture decoder" to it) }
        (soundWords ?: Decoders.describeSound(audioDecoder, soundPassedThrough))?.let { add("Sound decoder" to it) }
    }
}

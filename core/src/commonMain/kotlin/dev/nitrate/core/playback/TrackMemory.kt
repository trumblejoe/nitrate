package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import kotlinx.serialization.Serializable

/** The audio and subtitle languages someone picked for a show, reused for its other episodes. */
@Serializable
data class LanguageChoice(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean = false,
    /** They picked a forced (signs and songs) track rather than full subtitles. */
    val subtitleForced: Boolean = false,
    /**
     * 2: only what the user picked is set, the rest is left to the server's settings. Earlier
     * choices (0) also pinned whatever happened to be playing for the other kind, so they're ignored.
     */
    val version: Int = 0,
)

/** Where per-show track choices are kept (per user, synced). */
interface TrackMemoryStore {
    suspend fun get(seriesId: String): LanguageChoice?
    suspend fun set(seriesId: String, choice: LanguageChoice)
}

object TrackMemory {
    const val VERSION = 2

    /**
     * What to remember after the user picks stream [index] of [kind] (-1 turns subtitles off).
     * Only that kind changes: the other stays as the user last picked it, or the server's to decide.
     */
    fun remember(kind: StreamKind, streams: List<MediaStream>, index: Int, previous: LanguageChoice?): LanguageChoice {
        val base = previous?.takeIf { it.version >= VERSION } ?: LanguageChoice(version = VERSION)
        val stream = streams.firstOrNull { it.kind == kind && it.index == index }
        return when (kind) {
            StreamKind.AUDIO -> base.copy(audioLanguage = stream?.language ?: base.audioLanguage)
            StreamKind.SUBTITLE ->
                if (index == -1) base.copy(subtitleLanguage = null, subtitlesOff = true, subtitleForced = false)
                else base.copy(subtitleLanguage = stream?.language, subtitlesOff = false, subtitleForced = stream?.isForced == true)
            else -> base
        }
    }

    /**
     * The audio and subtitle stream indices matching [choice] in an episode's [streams]:
     * null leaves the server's default, -1 turns subtitles off.
     */
    fun pick(streams: List<MediaStream>, choice: LanguageChoice): Pair<Int?, Int?> {
        if (choice.version < VERSION) return null to null
        val audio = choice.audioLanguage?.let { lang ->
            val matches = streams.filter { it.kind == StreamKind.AUDIO && it.language.equals(lang, ignoreCase = true) }
            (matches.firstOrNull { it.isDefault } ?: matches.maxByOrNull { it.channels ?: 0 })?.index
        }
        val subtitle = when {
            choice.subtitlesOff -> -1
            choice.subtitleLanguage == null -> null
            else -> {
                val matches = streams.filter { it.kind == StreamKind.SUBTITLE && it.language.equals(choice.subtitleLanguage, ignoreCase = true) }
                (matches.firstOrNull { it.isForced == choice.subtitleForced } ?: matches.firstOrNull())?.index
            }
        }
        return audio to subtitle
    }
}

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
)

/** Where per-show track choices are kept (per user, synced). */
interface TrackMemoryStore {
    suspend fun get(seriesId: String): LanguageChoice?
    suspend fun set(seriesId: String, choice: LanguageChoice)
}

object TrackMemory {
    /** What to remember after the user picks [audioIndex] and [subtitleIndex] (-1 is off). */
    fun remember(streams: List<MediaStream>, audioIndex: Int?, subtitleIndex: Int?, previous: LanguageChoice?): LanguageChoice {
        val audio = streams.firstOrNull { it.kind == StreamKind.AUDIO && it.index == audioIndex }
        val sub = streams.firstOrNull { it.kind == StreamKind.SUBTITLE && it.index == subtitleIndex }
        return LanguageChoice(
            audioLanguage = audio?.language ?: previous?.audioLanguage,
            subtitleLanguage = sub?.language ?: previous?.subtitleLanguage.takeIf { subtitleIndex != -1 },
            subtitlesOff = subtitleIndex == -1,
            subtitleForced = sub?.isForced == true,
        )
    }

    /**
     * The audio and subtitle stream indices matching [choice] in an episode's [streams]:
     * null leaves the server's default, -1 turns subtitles off.
     */
    fun pick(streams: List<MediaStream>, choice: LanguageChoice): Pair<Int?, Int?> {
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

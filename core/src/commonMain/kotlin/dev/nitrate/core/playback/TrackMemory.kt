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
    /** The picked tracks' titles ("Commentary", "Signs & Songs"), so the same kind is found again. */
    val audioTitle: String? = null,
    val subtitleTitle: String? = null,
    /**
     * 2: only what the user picked is set, the rest is left to the server's settings. Earlier
     * choices (0) also pinned whatever happened to be playing for the other kind, so they're ignored.
     */
    val version: Int = 0,
)

/**
 * Where track choices are kept (per user, synced): per show for episodes, per title for anything
 * else ([TrackSelection.memoryKey]).
 */
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
            StreamKind.AUDIO -> base.copy(audioLanguage = stream?.language ?: base.audioLanguage, audioTitle = stream?.title)
            StreamKind.SUBTITLE ->
                if (index == -1) base.copy(subtitleLanguage = null, subtitlesOff = true, subtitleForced = false, subtitleTitle = null)
                else base.copy(subtitleLanguage = stream?.language, subtitlesOff = false, subtitleForced = stream?.isForced == true, subtitleTitle = stream?.title)
            else -> base
        }
    }

    /**
     * The audio and subtitle stream indices matching [choice] in an episode's [streams]:
     * null leaves the server's default, -1 turns subtitles off.
     */
    fun pick(streams: List<MediaStream>, choice: LanguageChoice): Pair<Int?, Int?> {
        if (choice.version < VERSION) return null to null
        fun candidates(kind: StreamKind, language: String?, title: String?) = streams.filter { it.kind == kind }.let { all ->
            when {
                language != null -> all.filter { it.language.equals(language, ignoreCase = true) }
                title != null -> all
                else -> emptyList()
            }
        }
        val audio = candidates(StreamKind.AUDIO, choice.audioLanguage, choice.audioTitle).let { matches ->
            sameTitle(matches, choice.audioTitle) ?: matches.firstOrNull { it.isDefault } ?: matches.maxByOrNull { it.channels ?: 0 }
        }?.index
        val subtitle = when {
            choice.subtitlesOff -> -1
            choice.subtitleLanguage == null && choice.subtitleTitle == null -> null
            else -> candidates(StreamKind.SUBTITLE, choice.subtitleLanguage, choice.subtitleTitle).let { matches ->
                sameTitle(matches, choice.subtitleTitle) ?: matches.firstOrNull { it.isForced == choice.subtitleForced } ?: matches.firstOrNull()
            }?.index
        }
        return audio to subtitle
    }

    /**
     * The stream whose title is [title], or shares its telling words ("Commentary", "Signs"), or
     * null when none does (or there's no title to match).
     */
    private fun sameTitle(streams: List<MediaStream>, title: String?): MediaStream? {
        title ?: return null
        streams.firstOrNull { it.title.equals(title, ignoreCase = true) }?.let { return it }
        val words = words(title)
        if (words.isEmpty()) return null
        return streams.map { it to (words intersect words(it.title.orEmpty())).size }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
}

/** Which audio and subtitles a title starts with, wherever it's played or downloaded from. */
object TrackSelection {
    /** Where choices are remembered: the show for an episode (so the next one follows), else the title. */
    fun memoryKey(item: dev.nitrate.core.model.MediaItem): String = item.seriesId ?: item.id

    /**
     * What starts: what the user picked (for this show or title), then the server's picks from
     * their language settings, then the file's own flags. -1 is subtitles off.
     */
    fun initial(source: dev.nitrate.core.model.MediaSource, choice: LanguageChoice?, describedAudio: Boolean = false): Pair<Int?, Int> {
        val (audio, subtitle) = dev.nitrate.core.downloads.DownloadTracks.pick(source)
        val (pickedAudio, pickedSubtitle) = choice?.let { TrackMemory.pick(source.streams, it) } ?: (null to null)
        // An audio picked for this show stays; otherwise the audio-description preference applies.
        return (pickedAudio ?: AudioDescription.apply(source, audio, describedAudio)) to (pickedSubtitle ?: subtitle)
    }
}

/** A title's file and the audio and subtitles it will start with (-1: subtitles off). */
data class TrackSetup(val source: dev.nitrate.core.model.MediaSource, val audioIndex: Int?, val subtitleIndex: Int) {
    val audioName: String get() = source.trackName(audioIndex)
    val subtitleName: String get() = source.trackName(subtitleIndex)
    val hasChoices: Boolean get() = source.streams.count { it.kind == StreamKind.AUDIO } > 1 || source.streams.any { it.kind == StreamKind.SUBTITLE }
}

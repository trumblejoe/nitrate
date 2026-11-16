package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind

/**
 * Audio description (a narrator describing what's on screen), a per-user choice. Jellyfin has no
 * flag for it (only hearing-impaired subtitles), and Media3 reads a file's own "describes video"
 * flag only from streaming playlists, not MKV or MP4, so a described track is recognised by its
 * name ("Audio Description", "Descriptive Video", "English AD"...).
 */
object AudioDescription {
    private val PHRASES = listOf(
        "audio description", "audio-description", "audiodescription", "audio described", "described video",
        "descriptive video", "descriptive audio", "video description", "visually impaired", "visual impaired",
        "for the blind", "audiodeskription", "audiodescripción", "audiodescrizione",
    )

    /** "AD", "DVS" as a word on its own (upper case), e.g. "English AD 5.1" or "[AD]". */
    private val TOKEN = Regex("""(^|[^A-Za-z])(AD|DVS)([^A-Za-z]|$)""")

    fun isDescribed(stream: MediaStream): Boolean {
        if (stream.kind != StreamKind.AUDIO) return false
        val title = stream.title ?: return false
        val lower = title.lowercase()
        return PHRASES.any { it in lower } || TOKEN.containsMatchIn(title)
    }

    /**
     * The audio to start with given the preference: [chosen] (null: the file's default) moved to a
     * described track in the same language when [wantDescribed], or away from one when not. Never
     * changes the language; returns [chosen] when there's nothing better.
     */
    fun apply(source: MediaSource, chosen: Int?, wantDescribed: Boolean): Int? {
        val audio = source.streams.filter { it.kind == StreamKind.AUDIO }
        val current = audio.firstOrNull { it.index == chosen } ?: audio.firstOrNull { it.isDefault } ?: audio.firstOrNull() ?: return chosen
        if (isDescribed(current) == wantDescribed) return chosen
        val sameLanguage = audio.filter { it.index != current.index && isDescribed(it) == wantDescribed && sameLanguage(it, current) }
        // The track most like the current one otherwise (the default first, then the file's order).
        return (sameLanguage.firstOrNull { it.isDefault } ?: sameLanguage.firstOrNull())?.index ?: chosen
    }

    private fun sameLanguage(a: MediaStream, b: MediaStream) =
        a.language.isNullOrBlank() || b.language.isNullOrBlank() || a.language.equals(b.language, ignoreCase = true)
}

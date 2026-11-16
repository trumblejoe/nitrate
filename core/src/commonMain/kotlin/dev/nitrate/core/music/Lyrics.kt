package dev.nitrate.core.music

/** One line of a song's lyrics; [startMs] when it's sung (timed lyrics only). */
data class LyricLine(val text: String, val startMs: Long? = null)

/** A song's lyrics from the server: timed ([synced], they follow the song) or plain. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    /** The line being sung at [positionMs] (timed lyrics), or null before the first. */
    fun currentIndex(positionMs: Long): Int? {
        if (!synced) return null
        val i = lines.indexOfLast { (it.startMs ?: Long.MAX_VALUE) <= positionMs }
        return i.takeIf { it >= 0 }
    }

    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }
}

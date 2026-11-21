package dev.nitrate.core.comics

import dev.nitrate.core.model.MediaItem

/**
 * Books as series of issues: which books belong together, their order, and which to read next.
 * A series is the server's series name (Jellyfin's Bookshelf plugin reads it from ComicInfo.xml),
 * else the folder the books are in (the usual "Series/Issue 001.cbz" layout).
 */
object ComicSeries {
    private val trailingNumber = Regex("""(?:#|\b|_)(\d+(?:\.\d+)?)(?:\s*\(.*\))*\s*$""")

    /** The issue's number: the server's, else the last number in its name ("Saga 012", "Saga #12 (2014)"). */
    fun number(book: MediaItem): Double? =
        book.indexNumber?.toDouble() ?: trailingNumber.find(book.name.replace(Regex("""\.[A-Za-z]{2,4}$"""), "").trim())?.groupValues?.get(1)?.toDoubleOrNull()

    /** "#12" (or "#12.5"), else the book's name. */
    fun label(book: MediaItem): String = number(book)?.let { n -> "#" + if (n % 1.0 == 0.0) n.toLong().toString() else n.toString() } ?: book.name

    /** What groups [book] with the rest of its series. */
    fun key(book: MediaItem): String? = book.seriesName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "name:$it" } ?: book.parentId?.let { "folder:$it" }

    /** The series' issues in reading order: by number, then name. */
    fun order(issues: List<MediaItem>): List<MediaItem> = issues.sortedWith(
        compareBy<MediaItem, Double?>(nullsLast()) { number(it) }.thenComparator { a, b -> ComicPages.natural(a.name, b.name) },
    )

    /** Only the books of [book]'s series among [siblings] (a folder can hold more than one series). */
    fun of(book: MediaItem, siblings: List<MediaItem>): List<MediaItem> {
        val name = book.seriesName?.trim()?.lowercase()
        return order(siblings.filter { name.isNullOrEmpty() || it.seriesName?.trim()?.lowercase() == name || it.id == book.id })
    }

    fun inProgress(book: MediaItem) = !book.user.played && book.user.positionMs > 0

    /** What to read next in a series: one in progress, else the first unread after the last read, else the first unread. */
    fun next(issues: List<MediaItem>): MediaItem? {
        val ordered = order(issues)
        ordered.firstOrNull(::inProgress)?.let { return it }
        val lastRead = ordered.indexOfLast { it.user.played }
        return ordered.drop(lastRead + 1).firstOrNull { !it.user.played } ?: ordered.firstOrNull { !it.user.played }
    }

    /** The issue after [book] in its series, if any. */
    fun after(book: MediaItem, issues: List<MediaItem>): MediaItem? {
        val ordered = order(issues)
        val i = ordered.indexOfFirst { it.id == book.id }
        return if (i < 0) null else ordered.getOrNull(i + 1)
    }

    /**
     * The Continue Reading row: books half read, newest first, and for each series finished up to
     * an issue lately, its next issue, placed by when that last one was read. One per series.
     * [issuesOf] gives a finished book's series.
     */
    suspend fun continueReading(
        reading: List<MediaItem>,
        finished: List<MediaItem>,
        limit: Int = 20,
        issuesOf: suspend (MediaItem) -> List<MediaItem>,
    ): List<MediaItem> {
        val out = mutableListOf<Pair<Long, MediaItem>>()
        val seen = mutableSetOf<String>()
        for (b in reading.filter(::inProgress)) {
            val k = key(b) ?: "book:${b.id}"
            if (seen.add(k)) out += (b.user.lastPlayedMs ?: 0) to b
        }
        for (b in finished.sortedByDescending { it.user.lastPlayedMs ?: 0 }) {
            val k = key(b) ?: continue
            if (k in seen) continue
            seen += k
            val issues = runCatching { issuesOf(b) }.getOrDefault(emptyList())
            val next = after(b, issues)?.takeIf { !it.user.played } ?: continue
            out += (b.user.lastPlayedMs ?: 0) to next
        }
        return out.sortedByDescending { it.first }.map { it.second }.take(limit)
    }
}

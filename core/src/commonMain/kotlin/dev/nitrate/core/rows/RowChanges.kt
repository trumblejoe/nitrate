package dev.nitrate.core.rows

/** What the server can say changed since a page was loaded (the Companion's change counters). */
enum class ChangeArea {
    /** Titles added, changed or removed. */
    LIBRARY,
    /** Libraries themselves added, renamed or removed. */
    VIEWS,
    /** Playlists made, changed or deleted. */
    PLAYLISTS,
    /** The user's own watch state (played, progress, favourites), from any app. */
    WATCH,
    /** The user's "Because you watched" was made again. */
    BECAUSE,
}

/** The server's change counters now ([token], kept with the page) and what differs from the ones given. */
data class ChangeCheck(val token: String, val changed: Set<ChangeArea>)

/**
 * Which rows a page can keep as they are, from what changed since they were loaded: a row whose
 * contents depend only on areas that haven't changed is kept (its cards' watched marks read afresh if
 * the user's watch state changed); everything else loads as usual. Rows nothing is known about (a
 * watch group's row, a custom page's rows) always load.
 */
object RowChanges {
    private const val VERSION = "1"

    /** The token for the Companion's counters, in order: epoch, library, views, playlists, watch, because. */
    fun token(epoch: String, library: Long, views: Long, playlists: Long, watch: Long, because: Long): String =
        listOf(VERSION, epoch, library, views, playlists, watch, because).joinToString("|")

    /** What differs between two tokens; everything when [old] is missing, unreadable or from another run. */
    fun compare(old: String?, new: String): Set<ChangeArea> {
        val a = old?.split('|')
        val b = new.split('|')
        if (a == null || a.size != 7 || b.size != 7 || a[0] != VERSION || b[0] != VERSION || a[1] != b[1]) return ChangeArea.entries.toSet()
        return listOf(ChangeArea.LIBRARY, ChangeArea.VIEWS, ChangeArea.PLAYLISTS, ChangeArea.WATCH, ChangeArea.BECAUSE)
            .filterIndexed { i, _ -> a[i + 2] != b[i + 2] }.toSet()
    }

    private val WATCHING = setOf(
        "home/continue_watching", "home/next_up", ContinueAndNext.KEY, "home/continue_reading", "home/continue_listening",
    )

    /** What a row's contents depend on (watched marks aside), or null when it always loads. */
    fun dependsOn(rowKey: String): Set<ChangeArea>? = when {
        rowKey.startsWith("home/latest/") -> setOf(ChangeArea.LIBRARY, ChangeArea.VIEWS)
        rowKey in WATCHING -> setOf(ChangeArea.LIBRARY, ChangeArea.WATCH)
        rowKey == "home/continue_playlists" -> setOf(ChangeArea.LIBRARY, ChangeArea.WATCH, ChangeArea.PLAYLISTS)
        rowKey in BecauseYouWatched.slots().keys -> setOf(ChangeArea.LIBRARY, ChangeArea.WATCH, ChangeArea.BECAUSE)
        // A library's own rows (Recently added, Top unwatched…).
        rowKey.startsWith("library/") -> setOf(ChangeArea.LIBRARY, ChangeArea.WATCH)
        else -> null
    }

    /**
     * What a custom row's contents depend on (watched marks aside), or null when it always loads: a
     * query on titles and watch state (it can filter on watched), a collection on titles, a playlist
     * on playlists and titles, an app row as that row.
     */
    fun dependsOn(source: dev.nitrate.core.pages.RowSource): Set<ChangeArea>? = when (source) {
        is dev.nitrate.core.pages.RowSource.Query -> setOf(ChangeArea.LIBRARY, ChangeArea.WATCH)
        is dev.nitrate.core.pages.RowSource.Collection -> setOf(ChangeArea.LIBRARY)
        is dev.nitrate.core.pages.RowSource.Playlist -> setOf(ChangeArea.PLAYLISTS, ChangeArea.LIBRARY)
        is dev.nitrate.core.pages.RowSource.AppRow -> dependsOn(source.key)
        is dev.nitrate.core.pages.RowSource.BuiltIn -> null
    }

    /**
     * Whether a custom row loaded under [stamp] (as made by [stampOf]) can be kept now the counters are
     * [token]: the same row (its source unchanged) and nothing it depends on changed.
     */
    fun keepCustom(source: dev.nitrate.core.pages.RowSource, stamp: String?, token: String): Boolean {
        val depends = dependsOn(source) ?: return false
        if (stamp == null) return false
        val old = stamp.substringBefore('#')
        if (stamp.substringAfter('#', "") != source.toString()) return false
        return compare(old, token).none(depends::contains)
    }

    /** What's kept with a custom row: the counters it was loaded under and what it is. */
    fun stampOf(source: dev.nitrate.core.pages.RowSource, token: String) = "$token#$source"

    /** The rows of [rows] that can be kept as they are, given [changed]. */
    fun keepable(rows: List<MediaRow>, changed: Set<ChangeArea>): List<MediaRow> =
        rows.filter { r -> dependsOn(r.key)?.let { it.none(changed::contains) } == true }

    /**
     * The page: [fresh] rows and the [kept] ones together, in [before]'s order (the order the page
     * had), anything new after; a row in neither is gone (it came back empty).
     */
    fun merge(before: List<MediaRow>, kept: List<MediaRow>, fresh: List<MediaRow>): List<MediaRow> {
        val now = (kept + fresh).associateBy { it.key }
        val old = before.mapNotNull { now[it.key] }
        val seen = old.map { it.key }.toSet()
        return old + fresh.filter { it.key !in seen }
    }
}

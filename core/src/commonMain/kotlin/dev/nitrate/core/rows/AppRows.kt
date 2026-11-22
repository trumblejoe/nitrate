package dev.nitrate.core.rows

/**
 * One of the app's own rows that can go on any page (custom pages, library pages), as
 * [dev.nitrate.core.pages.RowSource.AppRow]. [library]: whether it can be narrowed to one library
 * ([LibraryUse.OPTIONAL]) or needs one ([LibraryUse.REQUIRED]).
 */
data class AppRowKind(val key: String, val title: String, val description: String, val library: LibraryUse = LibraryUse.NONE)

enum class LibraryUse { NONE, OPTIONAL, REQUIRED }

/** The app rows on offer and their keys (stored in layouts: never rename one). */
object AppRows {
    const val CONTINUE_WATCHING = "continue_watching"
    const val NEXT_UP = "next_up"
    const val CONTINUE_AND_NEXT = "continue_and_next"
    const val BECAUSE_1 = "because_you_watched_1"
    const val BECAUSE_2 = "because_you_watched_2"
    const val LATEST = "latest"
    const val CONTINUE_LISTENING = "continue_listening"
    const val CONTINUE_READING = "continue_reading"
    const val WATCHING_TOGETHER = "watching_together"

    val ALL = listOf(
        AppRowKind(CONTINUE_WATCHING, "Continue Watching", "What you're part way through", LibraryUse.OPTIONAL),
        AppRowKind(NEXT_UP, "Next Up", "The next episode of each show you're watching", LibraryUse.OPTIONAL),
        AppRowKind(CONTINUE_AND_NEXT, "Continue Watching and Next Up", "Both in one row: part way first, then next episodes"),
        AppRowKind(LATEST, "Latest in a library", "What's newest in one library", LibraryUse.REQUIRED),
        AppRowKind(BECAUSE_1, "Because you watched", "Titles like something you watched lately (shared with Home's, worked out once a day)"),
        AppRowKind(BECAUSE_2, "Because you watched (second)", "Titles like another thing you watched lately"),
        AppRowKind(CONTINUE_LISTENING, "Continue Listening", "Audiobooks you're part way through"),
        AppRowKind(CONTINUE_READING, "Continue Reading", "Comics and books part read, and the next issue of series you're reading", LibraryUse.OPTIONAL),
        AppRowKind(WATCHING_TOGETHER, "Watching together", "Shows you watch with someone, and invites"),
    )

    fun kind(key: String): AppRowKind? = ALL.firstOrNull { it.key == key }

    /** What a row of this kind is called before it has loaded, with the library's name when narrowed. */
    fun title(key: String, libraryName: String?): String {
        val kind = kind(key) ?: return key
        return when {
            key == LATEST && libraryName != null -> "Latest in $libraryName"
            libraryName != null -> "${kind.title} · $libraryName"
            else -> kind.title
        }
    }
}

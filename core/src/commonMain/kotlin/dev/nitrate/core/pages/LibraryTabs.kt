package dev.nitrate.core.pages

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable

/** The kinds of tab a library page can have. [OWN] is a page of rows the user builds. */
@Serializable
enum class LibraryTabKind(val label: String, val detail: String) {
    RECOMMENDED("Recommended", "The library's suggested rows (latest, next up, by genre…)"),
    ALL("All", "Every title, with sort, filters and A–Z"),
    GENRES("Genres", "A row for each genre"),
    COLLECTIONS("Collections", "Box sets, as a grid"),
    UNWATCHED("Unwatched", "Every title you haven't watched, with sort and filters"),
    STUDIOS("Studios", "A row for each studio"),
    DECADES("Decades", "A row for each decade"),
    OWN("Your tab", "A page of rows you build"),
}

/** One tab on a library page: a built-in kind, or the user's own (with its own rows). */
@Serializable
data class LibraryTab(val id: String, val kind: LibraryTabKind, val title: String? = null, val hidden: Boolean = false) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: kind.label
}

object LibraryTabs {
    /** Built-in kinds a library offers: Recommended only for films and shows. */
    fun builtIn(hasRecommended: Boolean): List<LibraryTabKind> =
        LibraryTabKind.entries.filter { it != LibraryTabKind.OWN && (hasRecommended || it != LibraryTabKind.RECOMMENDED) }

    /** Shown at first: Recommended (where there is one) and All; the rest are there to switch on. */
    private val shownByDefault = setOf(LibraryTabKind.RECOMMENDED, LibraryTabKind.ALL)

    /**
     * The tabs in the user's order, hidden ones included: what was saved, then any built-in kind it
     * doesn't mention yet (new in an update, or never arranged), hidden unless it's shown by default.
     */
    fun resolve(saved: List<LibraryTab>, hasRecommended: Boolean): List<LibraryTab> {
        val offered = builtIn(hasRecommended)
        val kept = saved.filter { it.kind == LibraryTabKind.OWN || it.kind in offered }
        val missing = offered.filter { kind -> kept.none { it.kind == kind } }
            .map { LibraryTab(it.name.lowercase(), it, hidden = it !in shownByDefault) }
        return kept + missing
    }

    /** The tabs the page shows, in order; at least one (All) whatever was hidden. */
    fun shown(tabs: List<LibraryTab>): List<LibraryTab> =
        tabs.filterNot { it.hidden }.ifEmpty { tabs.filter { it.kind == LibraryTabKind.ALL }.ifEmpty { listOf(LibraryTab("all", LibraryTabKind.ALL)) } }

    /** What a tab's rows (and their looks) are saved against. Recommended keeps the library's own key. */
    fun pageKey(libraryId: String, tab: LibraryTab): String =
        if (tab.kind == LibraryTabKind.RECOMMENDED) PageKeys.library(libraryId) else "${PageKeys.library(libraryId)}/tab/${tab.id}"

    /** A new tab of the user's own, with an id not used yet. */
    fun newOwn(existing: List<LibraryTab>, title: String): LibraryTab {
        var n = existing.count { it.kind == LibraryTabKind.OWN } + 1
        while (existing.any { it.id == "own$n" }) n++
        return LibraryTab("own$n", LibraryTabKind.OWN, title)
    }

    fun move(tabs: List<LibraryTab>, id: String, by: Int): List<LibraryTab> {
        val from = tabs.indexOfFirst { it.id == id }
        if (from < 0) return tabs
        val to = (from + by).coerceIn(0, tabs.lastIndex)
        return tabs.toMutableList().apply { add(to, removeAt(from)) }
    }

    // ---- The rows of the built-in row tabs (Genres, Studios, Decades) ----

    /** What a library holds, for its tabs' queries. */
    fun typesFor(collection: dev.nitrate.core.model.CollectionKind?): List<ItemKind> = Browse.typesFor(collection)

    /** One row's query in a genre/studio/decade tab: newest first, as many as a row shows. */
    fun genreQuery(libraryId: String, types: List<ItemKind>, genre: String) =
        ItemQuery(libraryId = libraryId, types = types, genres = listOf(genre), sort = QuerySort.DATE_ADDED, descending = true, limit = ROW_LIMIT)

    fun studioQuery(libraryId: String, types: List<ItemKind>, studio: String) =
        ItemQuery(libraryId = libraryId, types = types, studios = listOf(studio), sort = QuerySort.RELEASE_DATE, descending = true, limit = ROW_LIMIT)

    fun decadeQuery(libraryId: String, types: List<ItemKind>, decade: Int) =
        ItemQuery(libraryId = libraryId, types = types, yearFrom = decade, yearTo = decade + 9, sort = QuerySort.RATING, descending = true, limit = ROW_LIMIT)

    /** Row keys stay stable (row looks are saved against them): the library, the tab kind and the value. */
    fun rowKey(libraryId: String, kind: LibraryTabKind, value: String) = "${PageKeys.library(libraryId)}/${kind.name.lowercase()}/$value"

    /** The rows of a [kind] tab from what the library has, each loaded by [load]; empty rows left out. */
    suspend fun rows(
        libraryId: String,
        kind: LibraryTabKind,
        types: List<ItemKind>,
        genres: suspend () -> List<String>,
        studios: suspend () -> List<String>,
        years: suspend () -> List<Int>,
        load: suspend (ItemQuery) -> List<dev.nitrate.core.model.MediaItem>,
    ): List<MediaRow> = kotlinx.coroutines.coroutineScope {
        val specs: List<Pair<String, ItemQuery>> = when (kind) {
            LibraryTabKind.GENRES -> genres().take(MAX_ROWS).map { it to genreQuery(libraryId, types, it) }
            LibraryTabKind.STUDIOS -> studios().take(MAX_ROWS).map { it to studioQuery(libraryId, types, it) }
            LibraryTabKind.DECADES -> Browse.decadesFor(years()).take(MAX_ROWS).map { "${it}s" to decadeQuery(libraryId, types, it) }
            else -> emptyList()
        }
        specs.map { (title, query) ->
            async {
                val items = runCatching { load(query) }.getOrDefault(emptyList())
                MediaRow(rowKey(libraryId, kind, title), title, items, RowDisplay())
            }
        }.awaitAll().filter { it.items.isNotEmpty() }
    }

    private const val ROW_LIMIT = 24
    private const val MAX_ROWS = 30
}

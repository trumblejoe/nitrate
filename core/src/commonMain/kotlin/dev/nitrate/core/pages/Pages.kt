package dev.nitrate.core.pages

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a custom row shows: a saved search over the library. */
@Serializable
data class ItemQuery(
    /** Limit to one library; null searches all of them. */
    val libraryId: String? = null,
    val types: List<ItemKind> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val personId: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** Minimum community rating, 0-10. */
    val minRating: Float? = null,
    val watched: WatchFilter = WatchFilter.ANY,
    val favoritesOnly: Boolean = false,
    val resolution: ResolutionFilter = ResolutionFilter.ANY,
    val sort: QuerySort = QuerySort.DATE_ADDED,
    val descending: Boolean = true,
    val limit: Int = 24,
    /** Names from this one on (sorting by name), for A–Z. */
    val nameFrom: String? = null,
    /** Names before this one, for counting where a letter starts. */
    val nameBefore: String? = null,
    /** Only items with video in one of these codecs ([Codecs.VIDEO] ids); shows count their episodes. */
    val videoCodecs: List<String> = emptyList(),
    /** Only items with audio in one of these codecs ([Codecs.AUDIO] ids). */
    val audioCodecs: List<String> = emptyList(),
    /** Only items with video of one of these bit depths (8, 10, 12). */
    val bitDepths: List<Int> = emptyList(),
    /** Only items with video in one of these ranges ([Codecs.RANGES] ids). */
    val videoRanges: List<String> = emptyList(),
) {
    /** Jellyfin can't filter by codec, bit depth or HDR type: such queries are filtered by Nitrate. */
    val filtersCodecs: Boolean get() = videoCodecs.isNotEmpty() || audioCodecs.isNotEmpty() || bitDepths.isNotEmpty() || videoRanges.isNotEmpty()

    /** A short description for editors, e.g. "Unwatched · Horror · 1980–1989 · Random". */
    fun describe(): String = buildList {
        if (types.isNotEmpty()) add(types.joinToString("/") { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) + "s" })
        if (watched != WatchFilter.ANY) add(watched.label)
        if (favoritesOnly) add("Favorites")
        if (genres.isNotEmpty()) add(genres.joinToString(", "))
        if (tags.isNotEmpty()) add("Tags: " + tags.joinToString(", "))
        if (studios.isNotEmpty()) add(studios.joinToString(", "))
        when {
            yearFrom != null && yearTo != null -> add(if (yearFrom == yearTo) "$yearFrom" else "$yearFrom–$yearTo")
            yearFrom != null -> add("$yearFrom on")
            yearTo != null -> add("Up to $yearTo")
        }
        minRating?.let { add("★ $it+") }
        if (resolution != ResolutionFilter.ANY) add(resolution.label)
        if (videoCodecs.isNotEmpty()) add(videoCodecs.joinToString("/") { Codecs.label(it) })
        if (bitDepths.isNotEmpty()) add(bitDepths.joinToString("/") { "$it-bit" })
        if (videoRanges.isNotEmpty()) add(videoRanges.joinToString("/") { Codecs.label(it) })
        if (audioCodecs.isNotEmpty()) add(audioCodecs.joinToString("/") { Codecs.label(it) })
        add(sort.label + if (sort != QuerySort.RANDOM && !descending) " (ascending)" else "")
    }.joinToString(" · ")
}

@Serializable
enum class WatchFilter(val label: String) { ANY("Any"), UNWATCHED("Unwatched"), WATCHED("Watched"), IN_PROGRESS("In progress") }

@Serializable
enum class ResolutionFilter(val label: String) { ANY("Any"), HD("HD and up"), UHD("4K") }

@Serializable
enum class QuerySort(val label: String) {
    DATE_ADDED("Date added"),
    RELEASE_DATE("Release date"),
    NAME("Name"),
    RATING("Rating"),
    CRITIC_RATING("Critic rating"),
    RUNTIME("Runtime"),
    DATE_PLAYED("Last watched"),
    RANDOM("Random"),
}

/** Where a row's items come from. */
@Serializable
sealed interface RowSource {
    /** A row the app builds (Continue Watching, Recently Added...), by its stable key. */
    @Serializable @SerialName("builtin")
    data class BuiltIn(val key: String) : RowSource

    @Serializable @SerialName("query")
    data class Query(val query: ItemQuery) : RowSource

    @Serializable @SerialName("collection")
    data class Collection(val id: String, val name: String) : RowSource

    @Serializable @SerialName("playlist")
    data class Playlist(val id: String, val name: String) : RowSource
}

/** One row on a page, in the order the user chose. [title] overrides the row's own title. */
@Serializable
data class RowEntry(
    val id: String,
    val source: RowSource,
    val title: String? = null,
    val hidden: Boolean = false,
    /** Display for a custom row (built-in rows keep theirs in the row overrides). */
    val display: RowDisplay? = null,
) {
    val isCustom get() = source !is RowSource.BuiltIn

    /** The row key that display overrides are saved against; stable for the entry's life. */
    val rowKey get() = (source as? RowSource.BuiltIn)?.key ?: "custom/$id"

    companion object {
        fun builtIn(key: String) = RowEntry("builtin:$key", RowSource.BuiltIn(key))
    }
}

/** A page's rows in the user's order; empty means "as the app lays it out". */
@Serializable
data class PageLayout(val entries: List<RowEntry> = emptyList())

/** A page the user made from their own rows. [pinned] puts it on Home (phones) or the menu (TV). */
@Serializable
data class CustomPage(val id: String, val title: String, val pinned: Boolean = true)

/** Page keys: what layouts are saved against. */
object PageKeys {
    const val HOME = "home"
    fun library(id: String) = "library/$id"
    fun custom(id: String) = "custom/$id"
}

object PageComposer {
    /**
     * The page's entries: the user's order, plus any built-in rows the layout doesn't mention yet
     * (new in an update, or never edited), placed after the built-in row they follow by default.
     * Built-in rows that are empty right now (nothing to continue) keep their place.
     */
    fun entries(builtIn: List<String>, layout: PageLayout): List<RowEntry> {
        val result = layout.entries.toMutableList()
        val known = result.map { it.rowKey }.toMutableSet()
        builtIn.forEachIndexed { i, key ->
            if (key in known) return@forEachIndexed
            val previous = builtIn.subList(0, i).lastOrNull { it in known }
            val at = if (previous == null) 0 else result.indexOfFirst { it.rowKey == previous } + 1
            result.add(at, RowEntry.builtIn(key))
            known += key
        }
        return result
    }

    /**
     * The rows to show, in order: hidden and empty rows left out, titles replaced where the user
     * renamed them. [custom] holds loaded custom rows by entry id.
     */
    fun rows(entries: List<RowEntry>, builtIn: List<MediaRow>, custom: Map<String, MediaRow>): List<MediaRow> {
        val byKey = builtIn.associateBy { it.key }
        return entries.filterNot { it.hidden }.mapNotNull { e ->
            val row = if (e.isCustom) custom[e.id] else byKey[e.rowKey]
            row?.takeIf { it.items.isNotEmpty() }?.let { r -> if (e.title.isNullOrBlank()) r else r.copy(title = e.title) }
        }
    }

    /** Moves the entry [id] by [delta] places (negative is up). */
    fun move(layout: List<RowEntry>, id: String, delta: Int): List<RowEntry> {
        val from = layout.indexOfFirst { it.id == id }
        if (from < 0) return layout
        val to = (from + delta).coerceIn(0, layout.lastIndex)
        return layout.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Default display for a custom row: posters, titles off; wide for episodes. */
    fun defaultDisplay(source: RowSource): RowDisplay = when {
        source is RowSource.Query && source.query.types == listOf(ItemKind.EPISODE) -> RowDisplay(RowStyle.WIDE, showTitles = true)
        else -> RowDisplay()
    }
}

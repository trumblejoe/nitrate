package dev.nitrate.core.pages

import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemKind
import kotlinx.serialization.Serializable

/** How a library's full list is shown: a grid of posters or of artwork, or a list with details. */
@Serializable
enum class BrowseView(val label: String) { GRID("Posters"), ARTWORK("Artwork"), LIST("List") }

/**
 * Sorting, filters and view for browsing one library, saved per library and user (synced like
 * other display settings).
 */
@Serializable
data class BrowseOptions(
    val sort: QuerySort = QuerySort.NAME,
    val descending: Boolean = false,
    val watched: WatchFilter = WatchFilter.ANY,
    val favoritesOnly: Boolean = false,
    val genres: List<String> = emptyList(),
    /** First year of a decade (1990 = the 1990s). */
    val decade: Int? = null,
    val minRating: Float? = null,
    val resolution: ResolutionFilter = ResolutionFilter.ANY,
    val view: BrowseView = BrowseView.GRID,
    /** Video codecs, any of ([Codecs.VIDEO] ids). */
    val videoCodecs: List<String> = emptyList(),
    /** Audio codecs, any of ([Codecs.AUDIO] ids). */
    val audioCodecs: List<String> = emptyList(),
) {
    /** How many filters narrow the list, for a badge on the Filter button. */
    val filterCount: Int
        get() = listOf(watched != WatchFilter.ANY, favoritesOnly, genres.isNotEmpty(), decade != null, minRating != null, resolution != ResolutionFilter.ANY, videoCodecs.isNotEmpty(), audioCodecs.isNotEmpty()).count { it }

    /** A–Z only makes sense when sorted by name. */
    val alphabetical: Boolean get() = sort == QuerySort.NAME

    fun clearFilters() = copy(watched = WatchFilter.ANY, favoritesOnly = false, genres = emptyList(), decade = null, minRating = null, resolution = ResolutionFilter.ANY, videoCodecs = emptyList(), audioCodecs = emptyList())

    /** The query for [libraryId] of [kind]. */
    fun query(libraryId: String, kind: CollectionKind?): ItemQuery = ItemQuery(
        libraryId = libraryId,
        types = Browse.typesFor(kind),
        genres = genres,
        yearFrom = decade,
        yearTo = decade?.plus(9),
        minRating = minRating,
        watched = watched,
        favoritesOnly = favoritesOnly,
        resolution = resolution,
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        sort = sort,
        // Names read best A first; everything else newest/highest first unless flipped.
        descending = descending,
    )

    /** Turns [codec] on or off in the video (or audio) codec filter. */
    fun toggleVideoCodec(codec: String) = copy(videoCodecs = if (codec in videoCodecs) videoCodecs - codec else videoCodecs + codec)
    fun toggleAudioCodec(codec: String) = copy(audioCodecs = if (codec in audioCodecs) audioCodecs - codec else audioCodecs + codec)

    /** Default direction when picking a sort. */
    fun withSort(newSort: QuerySort) = copy(sort = newSort, descending = newSort != QuerySort.NAME)
}

object Browse {
    /** "#" for names that don't start with a letter, then A to Z. */
    val LETTERS: List<String> = listOf("#") + ('A'..'Z').map(Char::toString)

    /** Decades offered as filters. */
    fun decades(fromYear: Int = 1920, toYear: Int = 2020): List<Int> = (fromYear..toYear step 10).toList().reversed()

    fun typesFor(kind: CollectionKind?): List<ItemKind> = when (kind) {
        CollectionKind.MOVIES -> listOf(ItemKind.MOVIE)
        CollectionKind.TV_SHOWS -> listOf(ItemKind.SERIES)
        CollectionKind.BOX_SETS -> listOf(ItemKind.BOX_SET)
        CollectionKind.MUSIC_VIDEOS -> listOf(ItemKind.MUSIC_VIDEO)
        CollectionKind.MUSIC -> listOf(ItemKind.MUSIC_ALBUM)
        else -> listOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.VIDEO)
    }

    /**
     * The query counting what comes before [letter], whose total is where that letter starts
     * in a name-sorted list ("#" starts at 0).
     */
    fun before(query: ItemQuery, letter: String): ItemQuery? =
        if (letter == "#") null else query.copy(nameBefore = letter, nameFrom = null)

    /** Which letter the item at the top of the list belongs to, for highlighting the A–Z bar. */
    fun letterOf(name: String): String {
        val first = name.trim().removePrefix("The ").removePrefix("An ").removePrefix("A ").firstOrNull()?.uppercaseChar() ?: return "#"
        return if (first in 'A'..'Z') first.toString() else "#"
    }
}

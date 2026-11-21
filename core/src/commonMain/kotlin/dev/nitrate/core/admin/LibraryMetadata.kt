package dev.nitrate.core.admin

/** A metadata or image provider in a library's settings: on or off, kept in the library's order. */
data class LibraryFetcher(val name: String, val enabled: Boolean)

/** One kind of item in a library (Movie, Series, Season, Episode…) and its providers, in order. */
data class LibraryTypeSettings(val type: String, val metadata: List<LibraryFetcher>, val images: List<LibraryFetcher>)

/**
 * A library's metadata settings as Jellyfin's dashboard has them (Libraries → Manage library):
 * language and country for metadata, where artwork and NFO files are saved, how often metadata is
 * refreshed, and per kind of item which providers are asked, in what order. [raw] is the server's
 * own LibraryOptions JSON, sent back with only these parts changed.
 */
data class LibraryMetadataSettings(
    val libraryId: String,
    val name: String,
    val collectionType: String?,
    val language: String?,
    val country: String?,
    /** "Save artwork into media folders" (and metadata files beside the media). */
    val saveLocal: Boolean,
    /** NFO files written beside the media (null: the server has no NFO saver). */
    val nfo: Boolean?,
    /** Days between automatic metadata refreshes (0 = never). */
    val refreshDays: Int,
    val types: List<LibraryTypeSettings>,
    internal val raw: String = "{}",
) {
    companion object {
        /** The dashboard's refresh choices. */
        val REFRESH_DAYS = listOf(0, 30, 60, 90)
    }
}

/** A language or country for metadata: its code as the server stores it, and its name. */
data class MetadataPlace(val code: String, val name: String)

/** Merging what the server offers with what a library has set, and changing it. */
object LibraryMetadata {
    /** A provider the server offers for a kind of item, and whether a new library turns it on. */
    data class Offered(val name: String, val defaultEnabled: Boolean)

    /**
     * The providers for one kind of item: the library's saved order first (only providers that
     * still exist), then any new ones; on as the library says, or by the provider's default when
     * the library has never been set for this kind.
     */
    fun merge(offered: List<Offered>, enabled: List<String>?, order: List<String>?): List<LibraryFetcher> {
        val names = offered.map { it.name }
        val ordered = (order.orEmpty().filter { it in names } + enabled.orEmpty().filter { it in names } + names).distinct()
        return ordered.map { n -> LibraryFetcher(n, if (enabled == null) offered.first { it.name == n }.defaultEnabled else n in enabled) }
    }

    /** [list] with the provider at [index] moved one place [by] (−1 up, +1 down). */
    fun move(list: List<LibraryFetcher>, index: Int, by: Int): List<LibraryFetcher> {
        val to = index + by
        if (index !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(index)) }
    }

    fun toggle(list: List<LibraryFetcher>, index: Int): List<LibraryFetcher> =
        list.mapIndexed { i, f -> if (i == index) f.copy(enabled = !f.enabled) else f }
}

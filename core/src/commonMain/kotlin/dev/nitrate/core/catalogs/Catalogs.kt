package dev.nitrate.core.catalogs

import kotlinx.serialization.Serializable

/** What kind of server a catalog is. */
@Serializable
enum class CatalogKind(val label: String) {
    /** Any OPDS 1.x catalog (Komga, Kavita, Calibre-web, CLU's feed, Codex…), with page streaming when offered. */
    OPDS("OPDS catalog"),

    /** CLU (Comic Library Utilities) through its token API: browse by publisher and series, reading position kept on CLU. */
    CLU("CLU"),
}

/**
 * A comic catalog the server's admin set up in the Companion plugin (feature `catalogs`). The
 * plugin signs in to it for everyone, so the apps never hold its password or token.
 */
@Serializable
data class ComicCatalog(
    val id: String,
    val name: String,
    /** Where browsing starts: an OPDS feed, or CLU's own address. */
    val url: String,
    val kind: CatalogKind = CatalogKind.OPDS,
) {
    companion object {
        /** The Companion feature that lists catalogs. */
        const val FEATURE = "catalogs"

        /** What the catalog pages say when the server offers none. */
        const val NONE_SET_UP = "No comic catalogs are set up on your server yet. Your server's admin adds them (any OPDS catalog, or CLU) in Jellyfin's Dashboard → Plugins → Nitrate Companion → Comic catalogs."
    }
}

/**
 * How a catalog is reached: through the Companion plugin, which fetches only from the catalog's
 * own server with the sign-in the admin set up, serves its books page by page, and keeps each
 * person's place in them. Addresses it gives are fetched with [authorization].
 */
interface CatalogLink {
    val catalog: ComicCatalog

    /** The Jellyfin Authorization header the addresses below need. */
    val authorization: String

    /** The text at [url] on the catalog's server (a feed, an API answer). */
    suspend fun text(url: String): String

    /** Where to fetch [url] from the catalog through the server (a cover, a streamed page). */
    fun fetchUrl(url: String): String

    /** The book file at [url]'s pages, the server having fetched it once; null when it can't list them. */
    suspend fun book(url: String): dev.nitrate.core.comics.ComicListing?

    /** Page [index] of the book at [url]. */
    fun pageUrl(url: String, index: Int): String

    /** The whole book at [url] from the server's copy (PDF, EPUB). */
    fun fileUrl(url: String): String

    /** Where the user is in [bookId] (a page; an EPUB's thousandths), kept on the server per person. */
    suspend fun position(bookId: String): Int?

    suspend fun savePosition(bookId: String, page: Int)
}

/** A book in a catalog, with what's needed to read it. */
@Serializable
data class CatalogBook(
    /** Unique within its catalog. */
    val id: String,
    val title: String,
    /** Pages, when the catalog says (page streaming). */
    val pageCount: Int? = null,
    /** Where the catalog says its account left off (0-based); the household shares that account, so only a fallback. */
    val lastRead: Int? = null,
    /** A page address with `{pageNumber}` (0-based) and `{maxWidth}`, when pages are streamed. */
    val pageTemplate: String? = null,
    /** The whole file, when not streamed (or as well). */
    val fileUrl: String? = null,
    val fileType: String? = null,
    val cover: String? = null,
)

/** One thing in a catalog page: a section to open ([target]) or a book. */
@Serializable
data class CatalogItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val summary: String? = null,
    val cover: String? = null,
    /** Where a section leads, for [CatalogClient.open]. */
    val target: String? = null,
    val book: CatalogBook? = null,
) {
    val isBook get() = book != null
}

/** A page of a catalog: its sections and books, and where more of it is. */
@Serializable
data class CatalogPage(
    val title: String,
    val items: List<CatalogItem>,
    /** The next part of this page, for [CatalogClient.open]. */
    val next: String? = null,
    val searchable: Boolean = false,
)

/** Reading a catalog: its pages and search, through [link]. */
interface CatalogClient {
    val link: CatalogLink
    val catalog get() = link.catalog

    suspend fun root(): CatalogPage

    suspend fun open(target: String): CatalogPage

    /** Results for [query], or null when the catalog can't search. */
    suspend fun search(query: String): CatalogPage?
}

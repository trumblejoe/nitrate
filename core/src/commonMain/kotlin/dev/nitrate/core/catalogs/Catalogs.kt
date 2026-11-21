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
 * A comic catalog the user added on this device. Kept on the device only (never synced or in
 * backups), since it can hold a password or token.
 */
@Serializable
data class ComicCatalog(
    val id: String,
    val name: String,
    val url: String,
    val kind: CatalogKind = CatalogKind.OPDS,
    val user: String? = null,
    val password: String? = null,
    /** A bearer token (CLU's API token, or an OPDS server that takes one). */
    val token: String? = null,
)

/** A book in a catalog, with what's needed to read it. */
@Serializable
data class CatalogBook(
    /** Unique within its catalog. */
    val id: String,
    val title: String,
    /** Pages, when the catalog says (page streaming). */
    val pageCount: Int? = null,
    /** Where the catalog says the reader left off (0-based). */
    val lastRead: Int? = null,
    /** A page address with `{pageNumber}` (0-based) and `{maxWidth}`, when pages are streamed. */
    val pageTemplate: String? = null,
    /** The whole file, when not streamed (or as well). */
    val fileUrl: String? = null,
    val fileType: String? = null,
    /** What the catalog keys reading position by (CLU: the file's path). */
    val progressKey: String? = null,
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

/** Reading a catalog: its pages, search, and (where it keeps them) reading positions. */
interface CatalogClient {
    val catalog: ComicCatalog

    /** The Authorization header the catalog wants, if any (pictures and files are fetched with it too). */
    val authorization: String?

    suspend fun root(): CatalogPage

    suspend fun open(target: String): CatalogPage

    /** Results for [query], or null when the catalog can't search. */
    suspend fun search(query: String): CatalogPage?

    /** Saves where the user is on the catalog's own server; false when it keeps none (the device keeps it then). */
    suspend fun saveProgress(book: CatalogBook, page: Int, pageCount: Int): Boolean = false
}

package dev.nitrate.core.catalogs

import dev.nitrate.core.opds.Opds
import dev.nitrate.core.opds.OpdsEntry
import dev.nitrate.core.opds.OpdsFeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A catalog the server refused or couldn't answer for, in words. */
class CatalogException(message: String) : Exception(message)

/** The client for [link]'s kind of catalog. */
fun catalogClient(link: CatalogLink): CatalogClient = when (link.catalog.kind) {
    CatalogKind.OPDS -> OpdsCatalog(link)
    CatalogKind.CLU -> CluCatalog(link)
}

/** An OPDS 1.x catalog (see [Opds]). */
class OpdsCatalog(override val link: CatalogLink) : CatalogClient {
    private var template: String? = null
    private var searchDescription: String? = null

    override suspend fun root() = open(catalog.url)

    override suspend fun open(target: String): CatalogPage {
        val feed = Opds.parse(link.text(target), target)
        feed.search?.let { if (Opds.isTemplate(it)) template = it else searchDescription = it }
        return page(feed)
    }

    override suspend fun search(query: String): CatalogPage? {
        val t = template ?: searchDescription?.let { d -> runCatching { Opds.searchTemplate(link.text(d), d) }.getOrNull() }?.also { template = it }
            ?: return null
        val url = Opds.searchUrl(t, query)
        return page(Opds.parse(link.text(url), url)).copy(title = "“$query”")
    }

    private fun page(feed: OpdsFeed) = CatalogPage(
        feed.title, feed.entries.mapNotNull(::item), feed.next, searchable = template != null || searchDescription != null,
    )

    private fun item(e: OpdsEntry): CatalogItem? {
        if (e.isBook) {
            val stream = e.stream
            val file = e.acquisitions.firstOrNull()
            return CatalogItem(
                id = e.id, title = e.title, subtitle = e.author, summary = e.summary, cover = e.cover,
                book = CatalogBook(
                    id = e.id, title = e.title, pageCount = stream?.count, lastRead = stream?.lastRead,
                    pageTemplate = stream?.href, fileUrl = file?.href, fileType = file?.type, cover = e.image,
                ),
            )
        }
        val nav = e.navigation ?: return null
        return CatalogItem(id = e.id, title = e.title, subtitle = e.summary?.takeIf { it.length < 80 }, cover = e.cover, target = nav.href)
    }
}

/**
 * CLU (Comic Library Utilities) through its token API (`/api/v1`, Bearer token made in CLU's My
 * Account → API Tokens): recently added, to read, publishers → series → issues; covers for CBZ;
 * the file for reading. Reading positions stay on the server per person (CLU sees one account).
 */
class CluCatalog(override val link: CatalogLink) : CatalogClient {
    private val base = catalog.url.trimEnd('/').removeSuffix("/api/v1")
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(path: String, vararg query: Pair<String, String?>): String =
        "$base/api/v1/$path" + query.filter { it.second != null }.joinToString("&", prefix = if (query.any { it.second != null }) "?" else "") { "${it.first}=${Opds.encode(it.second!!)}" }

    override suspend fun root() = CatalogPage(
        catalog.name,
        listOf(
            CatalogItem("clu:recent", "Recently added", target = "recent"),
            CatalogItem("clu:to-read", "To read", target = "to-read"),
            CatalogItem("clu:favorites", "Favourites", target = "favorites"),
            CatalogItem("clu:publishers", "Publishers", target = "publishers"),
            CatalogItem("clu:series", "All series", target = "series"),
        ),
        searchable = true,
    )

    override suspend fun open(target: String): CatalogPage {
        val (what, rest) = target.substringBefore('?') to target.substringAfter('?', "")
        val q = rest.split('&').filter { '=' in it }.associate { it.substringBefore('=') to decode(it.substringAfter('=')) }
        val page = q["page"]
        return when (what) {
            "publishers" -> list("Publishers", api("library/publishers", "page" to page, "sort" to "alpha"), target) { o ->
                val value = o.str("value") ?: o.str("name") ?: return@list null
                CatalogItem("pub:$value", o.str("name") ?: value, subtitle = o.int("count")?.let { "$it series" }, target = "series?publisher=${Opds.encode(value)}")
            }
            "series" -> list(q["publisher"] ?: "All series", api("library/series", "publisher" to q["publisher"], "q" to q["q"], "page" to page, "sort" to "alpha"), target) { o -> series(o, q["publisher"]) }
            "issues" -> list(q["series"] ?: "Issues", api("library/issues", "series" to q["series"], "publisher" to q["publisher"], "volume" to q["volume"], "page" to page, "sort" to "alpha"), target) { o -> issue(o) }
            "recent" -> list("Recently added", api("library/recent", "page" to page), target) { o -> issue(o) }
            "to-read", "favorites" -> list(if (what == "to-read") "To read" else "Favourites", api("library/$what", "page" to page), target) { o ->
                if (o.str("type") == "folder") folder(o) else issue(o)
            }
            else -> throw CatalogException("Unknown CLU page: $what")
        }
    }

    override suspend fun search(query: String) = open("series?q=${Opds.encode(query)}").copy(title = "“$query”")

    private fun series(o: JsonObject, publisher: String?): CatalogItem? {
        val value = o.str("value") ?: o.str("name") ?: return null
        val name = o.str("name") ?: value
        val params = buildString {
            append("series=").append(Opds.encode(value))
            publisher?.let { append("&publisher=").append(Opds.encode(it)) }
        }
        return CatalogItem("series:$value", name, subtitle = o.int("count")?.let { "$it issues" }, target = "issues?$params")
    }

    private fun folder(o: JsonObject): CatalogItem? {
        val series = o.str("series") ?: o.str("value") ?: o.str("name") ?: return null
        return CatalogItem("folder:$series", o.str("name") ?: series, target = "issues?series=${Opds.encode(series)}" + (o.str("volume")?.let { "&volume=${Opds.encode(it)}" } ?: ""))
    }

    private fun issue(o: JsonObject): CatalogItem? {
        val id = o.long("id") ?: return null
        val name = o.str("name") ?: "Issue $id"
        val title = name.substringBeforeLast('.').takeIf { '.' in name && name.substringAfterLast('.').length in 2..4 } ?: name
        val cover = api("issue/$id/cover", "size" to "400")
        val lastPage = o.int("last_page")?.takeIf { o.bool("has_progress") != false }
        return CatalogItem(
            "issue:$id", title, cover = cover,
            book = CatalogBook(
                id = "clu-$id", title = title, lastRead = lastPage, fileUrl = api("issue/$id/download"),
                fileType = name.substringAfterLast('.', "").lowercase(), cover = api("issue/$id/cover", "size" to "1200"),
            ),
        )
    }

    private suspend fun list(title: String, url: String, target: String, map: (JsonObject) -> CatalogItem?): CatalogPage {
        val body = json.parseToJsonElement(link.text(url)).jsonObject
        val items = (body["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(map) }
        val page = body["page"]?.jsonPrimitive?.intOrNull ?: 1
        val more = body["has_more"]?.jsonPrimitive?.booleanOrNull == true
        val next = if (more) target.replace(Regex("[?&]page=\\d+"), "").let { t -> t + (if ('?' in t) "&" else "?") + "page=${page + 1}" } else null
        return CatalogPage(title, items, next, searchable = true)
    }

    private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.int(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    private fun JsonObject.long(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    private fun JsonObject.bool(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

    private fun decode(s: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                s.substring(i + 1, i + 3).toIntOrNull(16)?.let { bytes += it.toByte(); i += 3; continue }
            }
            if (c == '+') bytes += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}

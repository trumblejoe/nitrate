package dev.nitrate.core.opds

/** A link in a catalog feed; [href] is already absolute. */
data class OpdsLink(
    val rel: String?,
    val href: String,
    val type: String?,
    val title: String? = null,
    /** OPDS-PSE: the number of pages a stream link serves. */
    val count: Int? = null,
    /** OPDS-PSE: the last page read (0-based), when the server keeps it. */
    val lastRead: Int? = null,
)

/** An entry in a catalog feed: a section to open, or a book to read. */
data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String? = null,
    val summary: String? = null,
    val links: List<OpdsLink> = emptyList(),
) {
    /** Where a section entry leads (another feed). */
    val navigation: OpdsLink? get() = if (isBook) null else links.firstOrNull { it.type?.contains("atom+xml") == true || it.type?.contains("opds") == true || it.rel == "subsection" }

    /** The book's files, best first (CBZ, then PDF, then anything else). */
    val acquisitions: List<OpdsLink> get() = links.filter { it.rel?.startsWith(Opds.ACQUISITION) == true }
        .sortedBy { l -> Opds.formatRank(l.type, l.href) }

    /** OPDS-PSE page streaming, when the server offers it. */
    val stream: OpdsLink? get() = links.firstOrNull { it.rel == Opds.PSE_STREAM }

    val cover: String? get() = (links.firstOrNull { it.rel == Opds.THUMBNAIL } ?: links.firstOrNull { it.rel == Opds.IMAGE })?.href
    val image: String? get() = (links.firstOrNull { it.rel == Opds.IMAGE } ?: links.firstOrNull { it.rel == Opds.THUMBNAIL })?.href

    val isBook: Boolean get() = stream != null || links.any { it.rel?.startsWith(Opds.ACQUISITION) == true }
}

/** One page of a catalog feed. */
data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    /** The next page of this feed, if it's paged. */
    val next: String? = null,
    /** An OpenSearch description, or a search template with `{searchTerms}`. */
    val search: String? = null,
)

/**
 * OPDS 1.x catalogs (Atom feeds, as Komga, Kavita, Calibre, CLU and many others serve) with the
 * Page Streaming Extension (OPDS-PSE 1.2): browse sections, page on, search, and read a book page
 * by page when streaming is offered, else by its file.
 */
object Opds {
    const val ACQUISITION = "http://opds-spec.org/acquisition"
    const val IMAGE = "http://opds-spec.org/image"
    const val THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    const val PSE_STREAM = "http://vaemendis.net/opds-pse/stream"

    fun parse(xml: String, baseUrl: String): OpdsFeed {
        val root = Xml.parse(xml)
        if (root.localName != "feed") throw IllegalArgumentException("This isn't an OPDS catalog (no Atom feed).")
        val links = root.all("link").map { link(it, baseUrl) }
        val entries = root.all("entry").map { e ->
            OpdsEntry(
                id = e.textOf("id") ?: e.all("link").firstOrNull()?.attr("href") ?: e.textOf("title").orEmpty(),
                title = e.textOf("title") ?: "Untitled",
                author = e.all("author").mapNotNull { it.textOf("name") }.joinToString(", ").takeIf { it.isNotEmpty() },
                summary = (e.textOf("summary") ?: e.textOf("content"))?.let(::plainText),
                links = e.all("link").map { link(it, baseUrl) },
            )
        }
        return OpdsFeed(
            title = root.textOf("title") ?: "Catalog",
            entries = entries,
            next = links.firstOrNull { it.rel == "next" }?.href,
            search = links.firstOrNull { it.rel == "search" }?.href,
        )
    }

    /** The search template from an OpenSearch description (the Atom one first). */
    fun searchTemplate(xml: String, baseUrl: String): String? {
        val urls = Xml.parse(xml).all("Url").filter { it.attr("template") != null }
        val best = urls.firstOrNull { it.attr("type")?.contains("atom") == true } ?: urls.firstOrNull()
        return best?.attr("template")?.let { resolve(baseUrl, it) }
    }

    /** True when [search] is already a template rather than a description to fetch. */
    fun isTemplate(search: String) = "{searchTerms}" in search

    fun searchUrl(template: String, query: String) = template.replace("{searchTerms}", encode(query))
        .replace(Regex("""\{[a-zA-Z:]+\?}"""), "")

    /** Page [page] (0-based) of a PSE stream link, asked for no wider than [maxWidth]. */
    fun pageUrl(stream: OpdsLink, page: Int, maxWidth: Int) = stream.href.replace("{pageNumber}", page.toString()).replace("{maxWidth}", maxWidth.toString())

    /** Lower is better: CBZ, then PDF, EPUB, CBR, then the rest. */
    fun formatRank(type: String?, href: String): Int {
        val t = type.orEmpty().lowercase()
        val ext = href.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            "cbz" in t || "zip" in t || ext == "cbz" || ext == "zip" -> 0
            "pdf" in t || ext == "pdf" -> 1
            "epub" in t || ext == "epub" -> 2
            "cbr" in t || "rar" in t || ext == "cbr" -> 3
            else -> 4
        }
    }

    /** [href] against [base]: absolute URLs as they are, else joined the way a browser would. */
    fun resolve(base: String, href: String): String {
        val h = href.trim()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(h)) return h
        val scheme = base.substringBefore("://", "https")
        val afterScheme = base.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val origin = "$scheme://$authority"
        if (h.startsWith("//")) return "$scheme:$h"
        if (h.startsWith("/")) return origin + normalize(h)
        val basePath = afterScheme.removePrefix(authority).substringBefore('#')
        if (h.startsWith("?")) return origin + basePath.substringBefore('?').ifEmpty { "/" } + h
        if (h.isEmpty()) return base
        val dir = basePath.substringBefore('?').substringBeforeLast('/', "") + "/"
        return origin + normalize(dir + h)
    }

    private fun normalize(path: String): String {
        val query = path.substringAfter('?', "").let { if (it.isEmpty() && '?' !in path) "" else "?$it" }
        val parts = path.substringBefore('?').split('/')
        val out = ArrayList<String>()
        for ((i, p) in parts.withIndex()) {
            when (p) {
                "." -> if (i == parts.lastIndex) out += ""
                ".." -> { if (out.size > 1) out.removeAt(out.lastIndex); if (i == parts.lastIndex) out += "" }
                else -> out += p
            }
        }
        return out.joinToString("/").let { if (it.startsWith("/")) it else "/$it" } + query
    }

    fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    private fun link(e: XmlElement, base: String) = OpdsLink(
        rel = e.attr("rel"),
        href = resolve(base, e.attr("href").orEmpty()),
        type = e.attr("type"),
        title = e.attr("title"),
        // pse:count and pse:lastRead (some servers leave the prefix off).
        count = e.attr("count")?.toIntOrNull(),
        lastRead = e.attr("lastRead")?.toIntOrNull(),
    )

    private fun plainText(s: String) = Xml.unescape(s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")).trim()
}

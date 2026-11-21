package dev.nitrate.core.books

import dev.nitrate.core.opds.Xml

/** A chapter of an EPUB: its file inside the book (path from the book's root) and size, for progress. */
data class EpubChapter(val path: String, val bytes: Long)

/** What Nitrate's own EPUB reader needs from a book: its title, author and chapters in reading order. */
data class EpubPackage(val title: String?, val author: String?, val chapters: List<EpubChapter>, val rightToLeft: Boolean = false)

/** A place in a book: a chapter and how far down it (0–1). */
data class EpubPlace(val chapter: Int, val fraction: Double)

/**
 * The parts of EPUB (2 and 3) Nitrate's own reader uses where the platform has no Readium
 * (iPhone and iPad for now): the package file, the spine, and turning a place into the share of
 * the book read and back (by chapter sizes) so progress means the same on every device.
 */
object Epub {
    /** The package (OPF) file's path, from META-INF/container.xml. */
    fun packagePath(containerXml: String): String? =
        Xml.parse(containerXml).let { root ->
            fun find(e: dev.nitrate.core.opds.XmlElement): String? =
                if (e.localName == "rootfile") e.attr("full-path") else e.children.firstNotNullOfOrNull(::find)
            find(root)
        }

    /** The package file read: chapters in spine order, paths from the book's root; [size] gives each file's size. */
    fun parsePackage(opfXml: String, opfPath: String, size: (String) -> Long = { 1 }): EpubPackage {
        val root = Xml.parse(opfXml)
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val metadata = root.first("metadata")
        val manifest = root.first("manifest")?.all("item").orEmpty().associate { it.attr("id").orEmpty() to it.attr("href").orEmpty() }
        val spine = root.first("spine")
        val chapters = spine?.all("itemref").orEmpty()
            .filter { it.attr("linear") != "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .map { href -> normalize(base + decode(href.substringBefore('#'))) }
            .distinct()
            .map { EpubChapter(it, size(it).coerceAtLeast(1)) }
        return EpubPackage(
            title = metadata?.textOf("title"),
            author = metadata?.all("creator")?.mapNotNull { it.text.trim().takeIf(String::isNotEmpty) }?.joinToString(", ")?.takeIf { it.isNotEmpty() },
            chapters = chapters,
            rightToLeft = spine?.attr("page-progression-direction") == "rtl",
        )
    }

    /** The share of the book read (0–1) at [place]. */
    fun progression(book: EpubPackage, place: EpubPlace): Double {
        val total = book.chapters.sumOf { it.bytes }.toDouble()
        if (total <= 0 || book.chapters.isEmpty()) return 0.0
        val i = place.chapter.coerceIn(0, book.chapters.lastIndex)
        val before = book.chapters.take(i).sumOf { it.bytes }
        return ((before + book.chapters[i].bytes * place.fraction.coerceIn(0.0, 1.0)) / total).coerceIn(0.0, 1.0)
    }

    /** The place that far through the book. */
    fun place(book: EpubPackage, progression: Double): EpubPlace {
        val total = book.chapters.sumOf { it.bytes }.toDouble()
        if (total <= 0 || book.chapters.isEmpty()) return EpubPlace(0, 0.0)
        var target = progression.coerceIn(0.0, 1.0) * total
        book.chapters.forEachIndexed { i, c ->
            if (target <= c.bytes || i == book.chapters.lastIndex) return EpubPlace(i, (target / c.bytes).coerceIn(0.0, 1.0))
            target -= c.bytes
        }
        return EpubPlace(0, 0.0)
    }

    /** A place as kept on the device ("chapter:fraction"), and back. */
    fun encode(place: EpubPlace) = "nitrate-epub:${place.chapter}:${place.fraction}"

    fun decode(saved: String?): EpubPlace? = saved?.takeIf { it.startsWith("nitrate-epub:") }?.split(':')?.let { p ->
        val c = p.getOrNull(1)?.toIntOrNull() ?: return null
        EpubPlace(c, p.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
    }

    private fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out += part
        }
        return out.joinToString("/")
    }

    private fun decode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16)?.let { bytes += it.toByte(); i += 3; return@let null }
                ?: run { s[i].toString().encodeToByteArray().forEach { bytes += it }; i++ }
            else { s[i].toString().encodeToByteArray().forEach { bytes += it }; i++ }
        }
        return bytes.toByteArray().decodeToString()
    }
}

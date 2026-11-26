package dev.nitrate.core.comics

/** How a comic is read: page by page left to right, right to left (manga), or one long strip (webtoon). */
enum class ReadingDirection(val label: String) {
    LEFT_TO_RIGHT("Left to right"),
    RIGHT_TO_LEFT("Right to left (manga)"),
    VERTICAL("Vertical strip (webtoon)"),
}

/** How pages fit the screen. */
enum class PageFit(val label: String) { SCREEN("Whole page"), WIDTH("Fit width") }

/** What a comic's ComicInfo.xml says that the reader uses; anything missing is null/empty. */
data class ComicInfo(
    val series: String? = null,
    val number: String? = null,
    val title: String? = null,
    val summary: String? = null,
    /** "YesAndRightToLeft" in ComicInfo: read right to left. */
    val rightToLeft: Boolean = false,
    /** Pages (0-based) that are double-page spreads on one image. */
    val doublePages: Set<Int> = emptySet(),
    val pageCount: Int? = null,
) {
    val direction: ReadingDirection? get() = if (rightToLeft) ReadingDirection.RIGHT_TO_LEFT else null

    companion object {
        /**
         * Reads the parts of ComicInfo.xml (the Anansi/ComicRack schema) the reader needs. Lenient:
         * a malformed file gives what could be read.
         */
        fun parse(xml: String): ComicInfo {
            fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            val pages = Regex("<Page\\b([^>]*)/?>").findAll(xml).map { it.groupValues[1] }.toList()
            fun attr(attrs: String, name: String) = Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
            val doubles = pages.mapNotNull { a ->
                val image = attr(a, "Image")?.toIntOrNull() ?: return@mapNotNull null
                image.takeIf { attr(a, "DoublePage").equals("true", ignoreCase = true) || attr(a, "Type") == "DoublePage" }
            }.toSet()
            return ComicInfo(
                series = tag("Series"),
                number = tag("Number"),
                title = tag("Title"),
                summary = tag("Summary"),
                rightToLeft = tag("Manga").equals("YesAndRightToLeft", ignoreCase = true),
                doublePages = doubles,
                pageCount = tag("PageCount")?.toIntOrNull(),
            )
        }

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}

/** A comic archive's pages: which entries are pages, and in what order. */
object ComicPages {
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "jxl")

    /**
     * The page images among an archive's entry paths, in reading order: by folder then name, with
     * numbers compared as numbers ("page2" before "page10"). Hidden files and macOS leftovers are
     * left out.
     */
    fun order(entries: List<String>): List<String> = entries
        .filter { e ->
            val name = e.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in IMAGE_EXTENSIONS && !name.startsWith(".") && "__MACOSX" !in e
        }
        .sortedWith(::natural)

    /** Natural order: digit runs compared by value, the rest without case. */
    fun natural(a: String, b: String): Int {
        val x = chunks(a.lowercase())
        val y = chunks(b.lowercase())
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p.first().isDigit() && q.first().isDigit()) {
                val n = p.trimStart('0').length.compareTo(q.trimStart('0').length)
                if (n != 0) n else p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    private fun chunks(s: String): List<String> = Regex("\\d+|\\D+").findAll(s).map { it.value }.toList()
}

/** Pages shown together: one, or two side by side, given left to right as they appear on screen. */
data class Spread(val pages: List<Int>) {
    val first get() = pages.min()
    val last get() = pages.max()
}

/**
 * Pairs pages into spreads for two-page view: the cover alone, then facing pairs, with any wide
 * (double-page) image alone, and pairs flipped for right-to-left reading. One-page view is one
 * page per spread.
 */
object Spreads {
    fun of(pageCount: Int, twoUp: Boolean, wide: Set<Int> = emptySet(), direction: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT): List<Spread> {
        if (pageCount <= 0) return emptyList()
        if (!twoUp || direction == ReadingDirection.VERTICAL) return (0 until pageCount).map { Spread(listOf(it)) }
        val out = mutableListOf<Spread>()
        var i = 0
        while (i < pageCount) {
            // The cover, a wide page, the last page, or a page whose partner is wide: alone.
            if (i == 0 || i in wide || i + 1 >= pageCount || (i + 1) in wide) {
                out += Spread(listOf(i))
                i++
            } else {
                out += Spread(if (direction == ReadingDirection.RIGHT_TO_LEFT) listOf(i + 1, i) else listOf(i, i + 1))
                i += 2
            }
        }
        return out
    }

    /** The spread showing [page]. */
    fun indexOf(spreads: List<Spread>, page: Int): Int = spreads.indexOfFirst { page in it.pages }.coerceAtLeast(0)
}

/**
 * Reading progress, kept in Jellyfin's user data for the book: the page as the "position" (page n
 * = n seconds), marked read at the last page. Jellyfin has no page field, and this keeps progress
 * in the same place as a film's, so Continue Reading and read marks work like Continue Watching.
 */
object ComicProgress {
    const val MS_PER_PAGE = 1_000L

    fun positionMs(page: Int) = page * MS_PER_PAGE

    fun page(positionMs: Long, pageCount: Int) = (positionMs / MS_PER_PAGE).toInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    /** Finished: on the last page (or spread holding it). */
    fun finished(page: Int, pageCount: Int) = pageCount > 0 && page >= pageCount - 1

    /** The Read button for a book: "Continue from page 13", "Continue (34%)" for an EPUB, else "Read". */
    fun readLabel(book: dev.nitrate.core.model.MediaItem): String {
        if (book.user.played || book.user.positionMs <= 0) return "Read"
        return if (book.container == "epub") "Continue (${(book.user.positionMs * 100 / 1_000_000L).coerceIn(1, 99)}%)"
        else "Continue from page ${book.user.positionMs / MS_PER_PAGE + 1}"
    }

    /** "12 / 24". */
    fun label(page: Int, pageCount: Int) = "${page + 1} / $pageCount"
}

/**
 * What a reader key or tap does, given the reading direction: on screen, Left goes back and Right
 * forward in a left-to-right comic, and the other way round in a right-to-left one.
 */
object ReaderKeys {
    /** The change in spread for an arrow pointing [right] (else left). */
    fun step(right: Boolean, direction: ReadingDirection): Int = when (direction) {
        ReadingDirection.RIGHT_TO_LEFT -> if (right) -1 else 1
        else -> if (right) 1 else -1
    }
}

/**
 * A picture's size read from the start of its file (JPEG, PNG, GIF, WebP, BMP) without decoding
 * it, so wide (double-page) pages are known before they're shown. Keep in step with the
 * Companion plugin's `Comics/ImageHeader`.
 */
object ImageSize {
    /** How much of a file to read (a JPEG's frame header can sit after large EXIF data). */
    const val READ_BYTES = 256 * 1024

    /** Width and height, or null when the format isn't known or [b] is cut short. */
    fun of(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        fun le32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
        fun ascii(i: Int, s: String) = b.size >= i + s.length && s.indices.all { u8(i + it) == s[it].code }
        return when {
            b.size >= 24 && u8(0) == 0x89 && ascii(1, "PNG") -> be32(16) to be32(20)
            b.size >= 10 && ascii(0, "GIF") -> le16(6) to le16(8)
            b.size >= 26 && ascii(0, "BM") -> kotlin.math.abs(le32(18)) to kotlin.math.abs(le32(22))
            b.size >= 30 && ascii(0, "RIFF") && ascii(8, "WEBP") -> when {
                ascii(12, "VP8X") -> (1 + (u8(24) or (u8(25) shl 8) or (u8(26) shl 16))) to (1 + (u8(27) or (u8(28) shl 8) or (u8(29) shl 16)))
                ascii(12, "VP8L") -> le32(21).let { (1 + (it and 0x3FFF)) to (1 + ((it ushr 14) and 0x3FFF)) }
                ascii(12, "VP8 ") -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
                else -> null
            }
            b.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8 -> {
                var i = 2
                while (i + 9 < b.size) {
                    if (u8(i) != 0xFF) { i++; continue }
                    val marker = u8(i + 1)
                    if (marker == 0xFF) { i++; continue }
                    if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                    // Start-of-frame markers, apart from DHT (C4), JPG (C8) and DAC (CC).
                    if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return be16(i + 7) to be16(i + 5)
                    i += 2 + be16(i + 2)
                }
                null
            }
            else -> null
        }
    }

    /**
     * Whether the picture can be see-through, from the start of its file: PNG with an alpha
     * channel or a tRNS chunk before the image data, WebP with its alpha flag, SVG (drawn on
     * nothing) → true; JPEG, PNG without either, lossy WebP → false; null when [b] doesn't
     * reach far enough to tell, or for formats it doesn't read. Keep in step with the
     * Companion plugin's `Comics/ImageHeader.Transparent`.
     */
    fun transparent(b: ByteArray): Boolean? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        fun ascii(i: Int, s: String) = b.size >= i + s.length && s.indices.all { u8(i + it) == s[it].code }
        return when {
            b.size >= 26 && u8(0) == 0x89 && ascii(1, "PNG") -> {
                val colour = u8(25)
                if (colour == 4 || colour == 6) return true
                var i = 8
                while (i + 8 <= b.size) {
                    val length = be32(i)
                    if (ascii(i + 4, "tRNS")) return true
                    if (ascii(i + 4, "IDAT") || ascii(i + 4, "IEND")) return false
                    if (length < 0) return null
                    i += 12 + length
                }
                null
            }
            b.size >= 30 && ascii(0, "RIFF") && ascii(8, "WEBP") -> when {
                ascii(12, "VP8X") -> (u8(20) and 0x10) != 0
                ascii(12, "VP8L") -> ((u8(24) shr 4) and 1) == 1
                ascii(12, "VP8 ") -> false
                else -> null
            }
            b.size >= 2 && u8(0) == 0xFF && u8(1) == 0xD8 -> false
            else -> {
                val head = b.copyOf(minOf(b.size, 512)).decodeToString().trimStart()
                if (head.startsWith("<svg") || (head.startsWith("<?xml") && "<svg" in head)) true else null
            }
        }
    }

    /** Wide enough to be a double-page spread on one picture. */
    fun isWide(size: Pair<Int, Int>?) = size != null && size.first > size.second * 1.1
}

/**
 * The ComicInfo fields an admin can change from Nitrate (Companion feature `comics`, CBZ only), in
 * the order they're shown; the plugin's `ComicInfoEditor.Fields` must accept every name here.
 */
object ComicInfoFields {
    data class Field(val name: String, val label: String, val long: Boolean = false)

    val EDITABLE = listOf(
        Field("Series", "Series"), Field("Number", "Issue number"), Field("Title", "Title"), Field("Volume", "Volume"),
        Field("Year", "Year"), Field("Month", "Month"), Field("Writer", "Writer"), Field("Penciller", "Artist"),
        Field("Colorist", "Colourist"), Field("CoverArtist", "Cover artist"), Field("Publisher", "Publisher"),
        Field("Genre", "Genre"), Field("StoryArc", "Story arc"), Field("AgeRating", "Age rating"), Field("Summary", "Summary", long = true),
    )

    /** ComicInfo's reading direction values, with what they mean. */
    val MANGA = listOf("" to "Not set", "No" to "Left to right", "YesAndRightToLeft" to "Manga (right to left)", "Yes" to "Manga, left to right")

    /** Every simple field in a ComicInfo.xml, by name. */
    fun values(xml: String?): Map<String, String> = xml?.let { x ->
        runCatching { dev.nitrate.core.opds.Xml.parse(x).children.filter { it.children.isEmpty() }.associate { it.localName to it.text.trim() } }.getOrNull()
    }.orEmpty()

    /** What changed between [before] and [after] (empty = remove), for the plugin. */
    fun changes(before: Map<String, String>, after: Map<String, String>): Map<String, String?> =
        (before.keys + after.keys).filter { (before[it] ?: "") != (after[it] ?: "").trim() }.associateWith { after[it]?.trim()?.ifEmpty { null } }
}

/** A page as the Companion plugin lists it (sizes 0 when unknown). */
@kotlinx.serialization.Serializable
data class ComicPageInfo(val name: String, val width: Int, val height: Int, val size: Long)

/**
 * A book as the Companion plugin serves it (feature `comics`): [format] "zip", "rar", "7z", "tar"
 * (served page by page), "pdf" or "epub" (no pages: the apps take the whole file).
 */
@kotlinx.serialization.Serializable
data class ComicListing(val format: String, val pages: List<ComicPageInfo>, val comicInfo: String?) {
    val paged get() = format in setOf("zip", "rar", "7z", "tar") && pages.isNotEmpty()

    /** Pages wider than tall: spreads on one picture. */
    val widePages: Set<Int> get() = pages.indices.filter { ImageSize.isWide(pages[it].width to pages[it].height) }.toSet()

    companion object {
        const val FEATURE = "comics"
    }
}

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

package dev.nitrate.core.pages

import dev.nitrate.core.model.MediaItem

/** A place to jump to in a library's list: a letter, a decade, a rating band, a stretch of time added. */
data class JumpStop(val key: String, val label: String, val spoken: String)

/**
 * The jump bar every library view shares (grids and drawn views alike, on every app): its stops
 * follow the sort (letters by name, decades by release, rating bands by rating, how recently by
 * date added), in the list's own order; none for sorts with nothing to jump by (runtime, random…).
 */
object JumpBar {
    private const val DAY = 86_400_000L

    fun stops(options: BrowseOptions): List<JumpStop> = when (options.sort) {
        QuerySort.NAME -> Browse.LETTERS.map { JumpStop(it, it, if (it == "#") "Numbers and symbols" else it) }.let { if (options.descending) it.reversed() else it }
        QuerySort.RELEASE_DATE -> Browse.decades(1920, 2020).map { d -> JumpStop("y$d", "${d}s", "The ${d}s") }.let { if (options.descending) it else it.reversed() }
        QuerySort.RATING -> RATING_BANDS.map { (r, label) -> JumpStop("r$r", label, if (r == 0) "Rated under 5" else "Rated $r and up") }.let { if (options.descending) it else it.reversed() }
        QuerySort.DATE_ADDED -> ADDED.map { (days, label) -> JumpStop("a$days", label, "Added ${label.lowercase()}") }.let { if (options.descending) it else it.reversed() }
        else -> emptyList()
    }

    /** Rating bands, highest first: 9 and up … 5 and up, then under 5 (0). */
    private val RATING_BANDS = listOf(9 to "9+", 8 to "8+", 7 to "7+", 6 to "6+", 5 to "5+", 0 to "<5")

    /** How recently added, newest first: within days (0 for longer ago). */
    private val ADDED = listOf(7 to "This week", 31 to "This month", 365 to "This year", 0 to "Earlier")

    /** The stop [item] falls under for [options]' sort, or null when it has nothing to go by (no year, no rating). */
    fun stopOf(options: BrowseOptions, item: MediaItem, nowMs: Long): String? = when (options.sort) {
        QuerySort.NAME -> Browse.letterOf(item)
        QuerySort.RELEASE_DATE -> item.productionYear?.let { y -> "y${(y / 10 * 10).coerceIn(1920, 2020)}" }
        QuerySort.RATING -> item.communityRating?.let { r -> "r${RATING_BANDS.first { (b, _) -> r >= b }.first}" }
        QuerySort.DATE_ADDED -> item.dateAddedMs?.let { at -> "a${ADDED.first { (d, _) -> d == 0 || nowMs - at <= d * DAY }.first}" }
        else -> null
    }

    /**
     * Where [stop]'s first title is in [items] (in [options]' order), from a starting [estimate]
     * (the server's count, or 0), moved to the edge of the stop's titles: `items.size` when every
     * loaded title comes before it. Titles with nothing to go by sort as past every stop.
     */
    fun firstOf(items: List<MediaItem>, options: BrowseOptions, stop: String, estimate: Int, nowMs: Long): Int {
        val order = stops(options).map { it.key }
        val target = order.indexOf(stop)
        if (target < 0) return items.size
        fun rank(i: Int) = stopOf(options, items[i], nowMs)?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: order.size
        var i = estimate.coerceIn(0, items.size)
        while (i > 0 && rank(i - 1) >= target) i--
        while (i < items.size && rank(i) < target) i++
        return i
    }

    /**
     * A query counting the titles before [stop] (its total is about where the stop starts), or null
     * when the server can't count it (date added: found by reading the list).
     */
    fun before(query: ItemQuery, options: BrowseOptions, stop: String): ItemQuery? {
        val n = stop.drop(1).toIntOrNull()
        return when (options.sort) {
            QuerySort.NAME -> if (options.descending) null else Browse.before(query, stop)
            // Newest first: everything from later decades; oldest first: everything from earlier ones.
            QuerySort.RELEASE_DATE -> n?.let { d -> if (options.descending) query.copy(yearFrom = d + 10, yearTo = null) else query.copy(yearFrom = null, yearTo = d - 1) }
            // Highest first: everything in higher bands.
            QuerySort.RATING -> n?.takeIf { options.descending }?.let { r ->
                val higher = RATING_BANDS.map { it.first }.filter { if (r == 0) it == 5 else it == r + 1 }.firstOrNull() ?: return@let null
                if (r == 9) null else query.copy(minRating = higher.toFloat())
            }
            else -> null
        }
    }

    /** Letters on a phone-style number pad (2 = ABC…); 0 and 1 are numbers and symbols. */
    private val KEYPAD = mapOf(0 to "#", 1 to "#", 2 to "ABC", 3 to "DEF", 4 to "GHI", 5 to "JKL", 6 to "MNO", 7 to "PQRS", 8 to "TUV", 9 to "WXYZ")

    /** The letter for [digit] pressed [presses] times running (1, 2, 3… cycling round), when sorted by name. */
    fun keypad(digit: Int, presses: Int): String? {
        val letters = KEYPAD[digit] ?: return null
        return letters[(presses - 1).mod(letters.length)].toString()
    }
}

package dev.nitrate.core.pages

/**
 * Choosing tags to filter a library by: a library can have hundreds, so they're shown a page at a
 * time, the most used first ([rank]: the chosen ones, then by how many titles carry them, then A–Z),
 * and found by typing ([matches]: names starting with what's typed, then a word in them starting
 * with it, then any part).
 */
object TagChoices {
    const val PAGE = 24

    /** [all] tags in the order they're offered: [chosen] first, then most used ([counts]), then A–Z. */
    fun rank(all: List<String>, counts: Map<String, Int>, chosen: List<String>): List<String> {
        val known = all.toSet()
        val first = chosen.filter { it in known } + chosen.filter { it !in known }
        val rest = all.filter { it !in chosen }.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it.lowercase() })
        return (first + rest).distinct()
    }

    /** Page [page] (from 0) of [ranked]. */
    fun page(ranked: List<String>, page: Int, size: Int = PAGE): List<String> = ranked.drop(page * size).take(size)

    fun pages(count: Int, size: Int = PAGE): Int = ((count + size - 1) / size).coerceAtLeast(1)

    /** Tags matching what's [typed], best first, at most [limit]; nothing typed: nothing. */
    fun matches(all: List<String>, typed: String, counts: Map<String, Int> = emptyMap(), limit: Int = 12): List<String> {
        val q = typed.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        fun score(tag: String): Int? {
            val t = tag.lowercase()
            return when {
                t == q -> 0
                t.startsWith(q) -> 1
                t.split(' ', '-', '_', '/').any { it.startsWith(q) } -> 2
                q in t -> 3
                else -> null
            }
        }
        return all.mapNotNull { tag -> score(tag)?.let { tag to it } }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenByDescending { counts[it.first] ?: 0 }.thenBy { it.first.lowercase() })
            .take(limit).map { it.first }
    }

    /** "12 titles", for a tag's count. */
    fun countWords(n: Int?): String? = n?.let { if (it == 1) "1 title" else "$it titles" }
}

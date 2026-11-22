package dev.nitrate.core.franchise

import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.serialization.Serializable

/**
 * Franchises are the user's own Jellyfin collections: any collection of two or more films or shows is
 * a franchise hub. A collection holding several shows (Arrow, The Flash, Supergirl…) becomes one
 * universe whose episodes can be watched interleaved as they aired, so crossovers fall in place.
 * Everything shown about one (logo, backdrop, theme music) is the server's own artwork for it.
 */
@Serializable
enum class WatchOrder(val label: String, val detail: String) {
    RELEASE("Release order", "Each film and show in the order it came out, a show's episodes together"),
    AIRED("As it aired", "Every episode and film by its air date, shows interleaved so crossovers line up"),
}

/** One thing to watch in a franchise, in its order, with the crossover it's part of (if any). */
data class FranchiseEntry(val item: MediaItem, val crossover: Crossover? = null)

/** Episodes of other shows that air with this one as one story. */
data class Crossover(val name: String, val with: List<MediaItem>) {
    /** "Crossover with The Flash S3:E8 and Supergirl S2:E8". */
    val label: String get() = "Crossover with " + with.joinToString(" and ") { (it.seriesName ?: it.name) + (Franchises.code(it)?.let { c -> " $c" } ?: "") }
}

/** Where the user is in a franchise. */
data class FranchiseProgress(val watched: Int, val total: Int, val next: MediaItem?, val resuming: Boolean) {
    val share: Float get() = if (total == 0) 0f else watched / total.toFloat()
    /** "34 of 52 watched". */
    val line: String get() = if (total == 0) "Nothing to watch yet" else if (watched == total) "All $total watched" else "$watched of $total watched"
}

object Franchises {
    /** The page key a franchise hub's theme and background are kept under. */
    fun pageKey(collectionId: String) = "franchise/$collectionId"

    /** Collections worth a hub: two or more films or shows. */
    fun isFranchise(items: List<MediaItem>): Boolean = items.count { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES } >= 2

    /** The order a franchise opens in: as it aired for collections of shows, else release order. */
    fun defaultOrder(titles: List<MediaItem>): WatchOrder = if (titles.count { it.kind == ItemKind.SERIES } >= 2) WatchOrder.AIRED else WatchOrder.RELEASE

    /** "S3:E8", or null for a film. */
    fun code(item: MediaItem): String? =
        if (item.kind == ItemKind.EPISODE && item.seasonNumber != null && item.indexNumber != null) "S${item.seasonNumber}:E${item.indexNumber}" else null

    /**
     * Everything to watch, in [order]. [titles] are the collection's films and shows; [episodes] each
     * show's episodes (by series id), in the show's own order. Specials (season 0) and episodes not
     * in the library are left out.
     */
    fun entries(titles: List<MediaItem>, episodes: Map<String, List<MediaItem>>, order: WatchOrder): List<FranchiseEntry> {
        fun watchable(e: MediaItem) = !e.isMissing && (e.seasonNumber ?: 1) > 0
        val released = titles.sortedWith(compareBy({ it.premiereDateMs ?: yearMs(it.productionYear) ?: Long.MAX_VALUE }, { it.name }))
        val items: List<MediaItem> = when (order) {
            WatchOrder.RELEASE -> released.flatMap { t -> if (t.kind == ItemKind.SERIES) episodes[t.id].orEmpty().filter(::watchable) else listOf(t) }
            WatchOrder.AIRED -> {
                // Each thing gets a date; an episode without one takes its predecessor's, so it keeps its place in its show.
                val dated = ArrayList<Pair<Long, MediaItem>>()
                for (t in released) {
                    if (t.kind == ItemKind.SERIES) {
                        var last = t.premiereDateMs ?: yearMs(t.productionYear) ?: Long.MAX_VALUE / 2
                        for (e in episodes[t.id].orEmpty().filter(::watchable)) {
                            last = e.premiereDateMs ?: last
                            dated += last to e
                        }
                    } else dated += (t.premiereDateMs ?: yearMs(t.productionYear) ?: Long.MAX_VALUE / 2) to t
                }
                // Same day: films first, then shows in the order they started, then by episode.
                val showOrder = released.withIndex().associate { it.value.id to it.index }
                dated.sortedWith(compareBy<Pair<Long, MediaItem>>({ it.first / DAY_MS }, { if (it.second.kind == ItemKind.EPISODE) 1 else 0 }, { showOrder[it.second.seriesId] ?: 0 }, { it.second.seasonNumber ?: 0 }, { it.second.indexNumber ?: 0 }))
                    .map { it.second }
            }
        }
        val crossovers = crossovers(items)
        return items.map { FranchiseEntry(it, crossovers[it.id]) }
    }

    /**
     * Crossovers: episodes of two or more of the franchise's shows that aired within [CROSSOVER_DAYS]
     * of each other with the same story name ("Crisis on Infinite Earths: Part Two" and "…: Part Three",
     * "Invasion!" and "Invasion!"). Keyed by each episode's id.
     */
    fun crossovers(items: List<MediaItem>): Map<String, Crossover> {
        val episodes = items.filter { it.kind == ItemKind.EPISODE && it.seriesId != null && it.premiereDateMs != null }
        val byStory = episodes.groupBy { storyName(it.name) }.filterKeys { it.length >= 4 }
        val out = HashMap<String, Crossover>()
        for ((story, group) in byStory) {
            for (e in group) {
                val partners = group.filter { o ->
                    o.seriesId != e.seriesId && kotlin.math.abs(o.premiereDateMs!! - e.premiereDateMs!!) <= CROSSOVER_DAYS * DAY_MS
                }.sortedBy { it.premiereDateMs }
                if (partners.isNotEmpty()) out[e.id] = Crossover(story, partners.distinctBy { it.seriesId })
            }
        }
        return out
    }

    /** A story's name from an episode's: "Crisis on Infinite Earths: Part Two" → "crisis on infinite earths". */
    fun storyName(name: String): String {
        var s = name.lowercase().trim()
        s = s.replace(Regex("""\s*[:(\-–—]\s*(part|pt\.?|chapter|hour)\s+([0-9]+|one|two|three|four|five|i{1,3}|iv|v)\)?\s*$"""), "")
        s = s.replace(Regex("""\s*\(([0-9]+|i{1,3}|iv|v)\)\s*$"""), "")
        s = s.replace(Regex("""\s+(part|pt\.?)\s+([0-9]+|one|two|three|four|five)\s*$"""), "")
        return s.trim(' ', ':', '-', '–', '—', '.', '!').trim()
    }

    /**
     * Where the user is: how much is watched, and what's next — the one under way, else the first
     * unwatched after the furthest one watched, else the first unwatched at all.
     */
    fun progress(entries: List<FranchiseEntry>): FranchiseProgress {
        val items = entries.map { it.item }
        val watched = items.count { it.user.played }
        val resuming = items.firstOrNull { !it.user.played && it.user.positionMs > 0 }
        val furthest = items.indexOfLast { it.user.played }
        val next = resuming
            ?: items.drop(furthest + 1).firstOrNull { !it.user.played }
            ?: items.firstOrNull { !it.user.played }
        return FranchiseProgress(watched, items.size, next, resuming != null)
    }

    /** The position of [itemId] in a franchise's order and what comes either side, for a title's details page. */
    fun around(entries: List<FranchiseEntry>, itemId: String): Triple<Int, MediaItem?, MediaItem?>? {
        val i = entries.indexOfFirst { it.item.id == itemId || it.item.seriesId == itemId }
        if (i < 0) return null
        return Triple(i, entries.getOrNull(i - 1)?.item, entries.getOrNull(i + 1)?.item)
    }

    /**
     * A mood to suggest when the user hasn't chosen one, from the franchise's tags (TMDb keywords,
     * which Jellyfin keeps) and genres. Only a guess, made from words, never names.
     */
    fun suggestedMood(titles: List<MediaItem>): PageTheme {
        val tags = titles.flatMap { it.tags }.map { it.lowercase() }.toSet()
        val genres = titles.flatMap { it.genres }.map { it.lowercase() }
        fun tag(vararg words: String) = words.any { w -> tags.any { it.contains(w) } }
        fun genre(vararg words: String) = words.any { w -> genres.count { it.contains(w) } * 2 >= titles.size.coerceAtLeast(1) }
        return when {
            tag("speedster", "super speed") -> PageTheme.LIGHTNING_RUN
            tag("vigilante", "archer", "archery") -> PageTheme.NIGHT_WATCH
            tag("dystopia", "battle royale", "survival competition", "rebellion") -> PageTheme.ARENA_DUSK
            tag("magic", "wizard", "witch", "school of magic", "sorcery") -> PageTheme.SPELLBOUND
            tag("dragon", "medieval", "kingdom", "sword and sorcery", "middle-earth", "quest") || genre("fantasy") -> PageTheme.HIGH_KINGDOM
            tag("superhero", "based on comic", "marvel", "multiverse", "space", "alien", "outer space") || genre("science fiction", "sci-fi") -> PageTheme.COSMIC_RIFT
            genre("action", "crime", "thriller") -> PageTheme.NIGHT_WATCH
            else -> PageTheme.COSMIC_RIFT
        }
    }

    /** The moods offered for a franchise hub (any page theme can be chosen, these first). */
    val MOODS = listOf(PageTheme.COSMIC_RIFT, PageTheme.LIGHTNING_RUN, PageTheme.NIGHT_WATCH, PageTheme.ARENA_DUSK, PageTheme.HIGH_KINGDOM, PageTheme.SPELLBOUND)

    const val CROSSOVER_DAYS = 3L
    private const val DAY_MS = 86_400_000L

    /** 1 July of [year], for titles with only a year. */
    private fun yearMs(year: Int?): Long? = year?.let { ((it - 1970) * 365.2425 * DAY_MS).toLong() + 181 * DAY_MS }
}

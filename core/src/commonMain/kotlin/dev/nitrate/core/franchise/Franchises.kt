package dev.nitrate.core.franchise

import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.serialization.Serializable

/**
 * Franchises are the user's own video playlists: a playlist's order is its watch order (the user
 * curates it, often with a plugin that builds franchise playlists), so the hub follows it by default.
 * Shows put in a playlist whole count as their episodes. Collections aren't used: their order is only
 * by title or year, which breaks a franchise's. Everything shown about one (logo, backdrop, theme
 * music) is the server's own artwork for it.
 */
@Serializable
enum class WatchOrder(val label: String, val detail: String) {
    PLAYLIST("Playlist order", "Just as the playlist has it"),
    AIRED("As it aired", "Every episode and film by its air date, shows interleaved so crossovers line up"),
    RELEASE("Release order", "Each film and show in the order it came out, a show's episodes together"),
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
    fun pageKey(playlistId: String) = "franchise/$playlistId"

    /** Playlists that can be franchises: video ones (music playlists never are). */
    fun candidates(playlists: List<MediaItem>): List<MediaItem> = playlists.filter { it.mediaKind != dev.nitrate.core.model.MediaKind.AUDIO }

    /** "S3:E8", or null for a film. */
    fun code(item: MediaItem): String? =
        if (item.kind == ItemKind.EPISODE && item.seasonNumber != null && item.indexNumber != null) "S${item.seasonNumber}:E${item.indexNumber}" else null

    /** Shows put in a playlist whole: their episodes are fetched to stand in for them. */
    fun wholeShows(items: List<MediaItem>): List<String> = items.filter { it.kind == ItemKind.SERIES }.map { it.id }.distinct()

    /** Seasons put in a playlist whole: their episodes stand in for them too (keyed by season id). */
    fun wholeSeasons(items: List<MediaItem>): List<MediaItem> = items.filter { it.kind == ItemKind.SEASON }.distinctBy { it.id }

    /** The titles in a franchise: its films and the shows its episodes (or whole shows) come from. */
    fun titleIds(items: List<MediaItem>): List<String> = items.mapNotNull { if (it.kind == ItemKind.EPISODE) it.seriesId else it.id }.distinct()

    /**
     * Everything to watch, in [order], from the playlist's [items] as the playlist has them. A whole
     * show is its [episodes] (by series id) in the show's own order. Specials (season 0) and episodes
     * not in the library are left out of whole shows; anything put in the playlist by hand stays.
     */
    fun entries(items: List<MediaItem>, episodes: Map<String, List<MediaItem>>, order: WatchOrder): List<FranchiseEntry> {
        fun watchable(e: MediaItem) = !e.isMissing && (e.seasonNumber ?: 1) > 0
        val flat = items.flatMap { t -> if (t.kind == ItemKind.SERIES || t.kind == ItemKind.SEASON) episodes[t.id].orEmpty().filter(::watchable) else listOf(t) }
            .filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE || it.kind == ItemKind.VIDEO }
            .distinctBy { it.id }
        val sorted: List<MediaItem> = when (order) {
            WatchOrder.PLAYLIST -> flat
            WatchOrder.AIRED -> {
                // Each thing gets a date; one without takes the one before it, so it keeps its place.
                var last = Long.MIN_VALUE / 2
                val dated = flat.mapIndexed { i, e -> Triple(e.premiereDateMs ?: yearMs(e.productionYear) ?: last, i, e).also { last = it.first } }
                // Same day: films first, then in the playlist's own order.
                dated.sortedWith(compareBy({ it.first / DAY_MS }, { if (it.third.kind == ItemKind.EPISODE) 1 else 0 }, { it.second })).map { it.third }
            }
            WatchOrder.RELEASE -> {
                // Titles by when they began (a show by its earliest episode), each show's episodes together in the playlist's order.
                val groups = flat.groupBy { if (it.kind == ItemKind.EPISODE) it.seriesId ?: it.id else it.id }
                groups.values.sortedBy { g -> g.minOf { it.premiereDateMs ?: yearMs(it.productionYear) ?: Long.MAX_VALUE } }.flatten()
            }
        }
        val crossovers = crossovers(sorted)
        return sorted.map { FranchiseEntry(it, crossovers[it.id]) }
    }

    /**
     * Crossovers: episodes of two or more of the franchise's shows that aired within [CROSSOVER_DAYS]
     * of each other with the same story name ("Crisis on Infinite Earths: Part Two" and "…: Part Three",
     * "Invasion!" and "Invasion!"). Keyed by each episode's id.
     */
    fun crossovers(items: List<MediaItem>): Map<String, Crossover> {
        val episodes = items.filter { it.kind == ItemKind.EPISODE && it.seriesId != null && it.premiereDateMs != null }
        val out = HashMap<String, Crossover>()
        fun near(a: MediaItem, b: MediaItem) = a.seriesId != b.seriesId && kotlin.math.abs(a.premiereDateMs!! - b.premiereDateMs!!) <= CROSSOVER_DAYS * DAY_MS
        // The same story name, part by part.
        val byStory = episodes.groupBy { storyName(it.name) }.filterKeys { it.length >= 4 }
        for ((story, group) in byStory) for (e in group) {
            val partners = group.filter { near(it, e) }.sortedBy { it.premiereDateMs }
            if (partners.isNotEmpty()) out[e.id] = Crossover(story, partners.distinctBy { it.seriesId })
        }
        // Different names sharing a telling word, aired days apart ("Legends of Yesterday" / "Legends of Today"),
        // or both tagged as a crossover.
        for (e in episodes) {
            if (e.id in out) continue
            val words = tellingWords(e.name)
            val tagged = e.tags.any { it.contains("crossover", ignoreCase = true) }
            val partners = episodes.filter { o ->
                near(o, e) && ((words.isNotEmpty() && tellingWords(o.name).any { it in words }) || (tagged && o.tags.any { it.contains("crossover", ignoreCase = true) }))
            }.sortedBy { it.premiereDateMs }
            if (partners.isNotEmpty()) out[e.id] = Crossover(storyName(e.name), partners.distinctBy { it.seriesId })
        }
        return out
    }

    /** The words of a name that say what it's about: five letters or more, not the common ones. */
    fun tellingWords(name: String): Set<String> =
        Regex("[A-Za-z][A-Za-z'-]{4,}").findAll(name.lowercase()).map { it.value.trim('\'', '-') }.filter { it.length >= 5 && it !in COMMON }.toSet()

    private val COMMON = setOf("there", "their", "about", "after", "before", "where", "which", "other", "these", "those", "under", "again", "never", "every", "first", "second", "third", "would", "could", "should", "still", "while", "night", "world", "things", "being", "pilot", "finale", "chapter", "episode")

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

    /**
     * Where a playlist was left, from Jellyfin's own play dates (so watching in any app counts): the
     * entry played most recently, or null when nothing in it has been played.
     */
    fun lastPlayed(entries: List<FranchiseEntry>): Int? =
        entries.withIndex().filter { it.value.item.user.lastPlayedMs != null }.maxByOrNull { it.value.item.user.lastPlayedMs!! }?.index

    /** When [entries] were last played from, if ever. */
    fun lastPlayedMs(entries: List<FranchiseEntry>): Long? = lastPlayed(entries)?.let { entries[it].item.user.lastPlayedMs }

    /**
     * Whether [itemId] carries on from a playlist left at [place]: the place itself, the one before
     * (gone back), or up to [PLACE_AHEAD] past it, past any already watched since. Gives its index.
     */
    fun indexNearPlace(entries: List<FranchiseEntry>, place: Int, itemId: String): Int? {
        if (place !in entries.indices) return null
        val at = entries.indexOfFirst { it.item.id == itemId }
        val watchedAfter = entries.drop(place + 1).indexOfFirst { !it.item.user.played }.let { if (it < 0) entries.size else it }
        return at.takeIf { it >= 0 && it in (place - 1)..(place + watchedAfter + PLACE_AHEAD) }
    }

    /** What to carry on with in a playlist left at [place]: that one if not finished, else the next unwatched after it. */
    fun continueFrom(entries: List<FranchiseEntry>, place: Int): MediaItem? =
        if (place !in entries.indices) null else entries.drop(place).firstOrNull { !it.item.user.played }?.item

    /**
     * Where [startedId] sits among [entries]: the entry itself, or for a whole show or season started
     * from a playlist page, its first unwatched episode (else its first). -1 when it isn't there.
     */
    fun placeIndex(entries: List<FranchiseEntry>, startedId: String): Int {
        entries.indexOfFirst { it.item.id == startedId }.takeIf { it >= 0 }?.let { return it }
        fun inside(e: FranchiseEntry) = e.item.seriesId == startedId || e.item.seasonId == startedId
        return entries.indexOfFirst { inside(it) && !it.item.user.played }.takeIf { it >= 0 } ?: entries.indexOfFirst(::inside)
    }

    const val PLACE_AHEAD = 3

    /** A playlist counts as being watched through when something in it was played within this long. */
    const val ACTIVE_MS = 60L * 86_400_000L

    /** The moods offered for a franchise hub (any page theme can be chosen, these first). */
    val MOODS = listOf(PageTheme.COSMIC_RIFT, PageTheme.LIGHTNING_RUN, PageTheme.NIGHT_WATCH, PageTheme.ARENA_DUSK, PageTheme.HIGH_KINGDOM, PageTheme.SPELLBOUND)

    const val CROSSOVER_DAYS = 3L
    private const val DAY_MS = 86_400_000L

    /** 1 July of [year], for titles with only a year. */
    private fun yearMs(year: Int?): Long? = year?.let { ((it - 1970) * 365.2425 * DAY_MS).toLong() + 181 * DAY_MS }
}

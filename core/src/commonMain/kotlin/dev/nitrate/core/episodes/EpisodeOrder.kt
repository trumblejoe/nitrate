package dev.nitrate.core.episodes

/** The parts of an episode the viewing-order logic needs. Season 0 means a special. */
data class EpisodeRef(
    val id: String,
    val season: Int?,
    val episode: Int?,
    /** Last episode covered by a multi-episode file (S01E01-E02), if any. */
    val episodeEnd: Int? = null,
    val airsBeforeSeason: Int? = null,
    val airsBeforeEpisode: Int? = null,
    val airsAfterSeason: Int? = null,
    val played: Boolean = false,
    val label: String = "",
) {
    val isSpecial get() = season == 0
}

/** A hole in the numbering: episodes or a whole season that aren't in the library. */
sealed interface Gap {
    /** Where the gap sits in viewing order: it comes right before this episode. */
    val before: EpisodeRef

    data class MissingEpisodes(val season: Int, val from: Int, val to: Int, override val before: EpisodeRef) : Gap
    data class MissingSeason(val season: Int, override val before: EpisodeRef) : Gap
}

object EpisodeOrder {
    /**
     * Sorts a series into viewing order. Regular episodes go by season and episode. Specials are
     * placed by their "airs before/after" metadata: before an episode, before a season, or after
     * a season (which covers end-of-show specials). Specials with no placement are left out,
     * since nothing says where they belong.
     */
    fun viewingOrder(episodes: List<EpisodeRef>): List<EpisodeRef> =
        episodes.mapNotNull { ep -> sortKey(ep)?.let { it to ep } }
            .sortedWith(compareBy<Pair<SortKey, EpisodeRef>>({ it.first.season }, { it.first.episode }, { it.first.rank }, { it.second.episode ?: 0 }))
            .map { it.second }

    private data class SortKey(val season: Int, val episode: Int, val rank: Int)

    private fun sortKey(ep: EpisodeRef): SortKey? = when {
        !ep.isSpecial -> ep.season?.let { s -> ep.episode?.let { e -> SortKey(s, e, RANK_EPISODE) } }
        ep.airsBeforeSeason != null && ep.airsBeforeEpisode != null -> SortKey(ep.airsBeforeSeason, ep.airsBeforeEpisode, RANK_BEFORE)
        ep.airsBeforeSeason != null -> SortKey(ep.airsBeforeSeason, 0, RANK_BEFORE)
        ep.airsAfterSeason != null -> SortKey(ep.airsAfterSeason, Int.MAX_VALUE, RANK_AFTER)
        else -> null
    }

    private const val RANK_BEFORE = 0
    private const val RANK_EPISODE = 1
    private const val RANK_AFTER = 2

    /**
     * Gaps in regular-episode numbering: a season that doesn't start at episode 1, a jump between
     * episodes, or a skipped season number. Episodes missing after the last one present can't be
     * detected without outside data, so they aren't reported.
     */
    fun gaps(episodes: List<EpisodeRef>): List<Gap> {
        val regular = episodes.filter { !it.isSpecial && it.season != null && it.episode != null }
        val bySeason = regular.groupBy { it.season!! }.entries.sortedBy { it.key }.associate { it.key to it.value }
        val out = mutableListOf<Gap>()

        var previousSeason: Int? = null
        for ((season, eps) in bySeason) {
            val sorted = eps.sortedBy { it.episode }
            if (previousSeason != null) {
                for (missing in (previousSeason + 1) until season) out += Gap.MissingSeason(missing, sorted.first())
            }
            var expected = 1
            for (ep in sorted) {
                val number = ep.episode!!
                if (number > expected) out += Gap.MissingEpisodes(season, expected, number - 1, ep)
                expected = maxOf(expected, (ep.episodeEnd ?: number) + 1)
            }
            previousSeason = season
        }
        return out
    }

    /** What stands between the user and [target], given how far back to look. */
    data class Check(val unwatched: List<EpisodeRef>, val gaps: List<Gap>) {
        val clear get() = unwatched.isEmpty() && gaps.isEmpty()
    }

    @kotlinx.serialization.Serializable
    enum class Scope { PREVIOUS, SEASON, SERIES }

    fun check(
        episodes: List<EpisodeRef>,
        targetId: String,
        scope: Scope,
        includeSpecials: Boolean,
        detectGaps: Boolean,
    ): Check {
        val order = viewingOrder(episodes).filter { includeSpecials || !it.isSpecial || it.id == targetId }
        val index = order.indexOfFirst { it.id == targetId }
        if (index <= 0) return Check(emptyList(), emptyList())
        val target = order[index]
        // The season a special belongs to for scoping is the one it airs within or before.
        fun seasonOf(ep: EpisodeRef) = if (ep.isSpecial) ep.airsBeforeSeason ?: ep.airsAfterSeason else ep.season
        val earlier = order.subList(0, index)
        val window = when (scope) {
            Scope.PREVIOUS -> listOf(earlier.last())
            Scope.SEASON -> earlier.filter { seasonOf(it) == seasonOf(target) }
            Scope.SERIES -> earlier
        }
        val unwatched = window.filter { !it.played }

        val gaps = if (!detectGaps) emptyList() else {
            val windowIds = (window + target).map { it.id }.toSet()
            // A gap counts when it sits right before something in the window (or the target).
            gaps(episodes).filter { it.before.id in windowIds && (scope != Scope.PREVIOUS || it.before.id == target.id) }
        }
        return Check(unwatched, gaps)
    }
}

package dev.nitrate.core.episodes

import dev.nitrate.core.episodes.EpisodeOrder.Scope
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardChoice
import dev.nitrate.core.plugins.GuardDecision
import dev.nitrate.core.plugins.PlaybackGuard
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.plugins.current
import kotlinx.serialization.Serializable

@Serializable
data class PreviousEpisodeSettings(
    val scope: Scope = Scope.PREVIOUS,
    val includeSpecials: Boolean = true,
    val detectGaps: Boolean = true,
    /** Don't ask about an episode that's already part-watched: the user started it on purpose. */
    val skipWhenResuming: Boolean = true,
)

/** Warns before playing an episode when an earlier one is unwatched or missing from the library. */
object PreviousEpisodePlugin : PlaybackGuard {
    override val id = "previous-episode"
    override val name = "Unwatched previous episode"
    override val description =
        "Before an episode plays, checks that the episodes and specials before it have been watched, " +
            "and spots gaps in the numbering that mean an episode is missing from your library."
    override val enabledByDefault = true

    private const val MAX_LISTED = 3

    override suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision {
        if (!item.isEpisode) return GuardDecision.Allow
        val seriesId = item.seriesId ?: return GuardDecision.Allow
        val s = host.store.current(id, PreviousEpisodeSettings())
        if (s.skipWhenResuming && item.user.positionMs > 0L) return GuardDecision.Allow

        val episodes = host.server.allEpisodes(seriesId)
        val byId = episodes.associateBy { it.id }
        val check = EpisodeOrder.check(episodes.map { it.toRef() }, item.id, s.scope, s.includeSpecials, s.detectGaps)
        if (check.clear) return GuardDecision.Allow

        val lines = buildList {
            check.unwatched.take(MAX_LISTED).forEach { add("• ${it.label} hasn't been watched") }
            if (check.unwatched.size > MAX_LISTED) add("• …and ${check.unwatched.size - MAX_LISTED} more")
            check.gaps.forEach { add("• ${it.describe()} not in your library") }
        }
        val choices = buildList {
            check.unwatched.firstOrNull()?.let { first ->
                byId[first.id]?.let { add(GuardChoice("Play ${first.label} instead", GuardAction.PlayInstead(it))) }
            }
            if (check.unwatched.isNotEmpty()) {
                val what = if (check.unwatched.size == 1) "it" else "all ${check.unwatched.size}"
                add(GuardChoice("Mark $what as watched and play", GuardAction.MarkWatchedThenPlay(check.unwatched.map { it.id })))
            }
            add(GuardChoice("Play ${item.toRef().label} anyway", GuardAction.PlayAnyway))
            add(GuardChoice("Cancel", GuardAction.Cancel))
        }
        val title = if (check.unwatched.isNotEmpty()) "You may have missed something" else "Something seems to be missing"
        return GuardDecision.Ask(title, lines.joinToString("\n"), choices)
    }

    private fun MediaItem.toRef() = EpisodeRef(
        id = id,
        season = seasonNumber,
        episode = indexNumber,
        episodeEnd = indexNumberEnd,
        airsBeforeSeason = airsBeforeSeason,
        airsBeforeEpisode = airsBeforeEpisode,
        airsAfterSeason = airsAfterSeason,
        played = user.played,
        label = if (seasonNumber == 0) "Special “$name”" else "S${seasonNumber}E$indexNumber",
    )

    private fun Gap.describe() = when (this) {
        is Gap.MissingEpisodes -> if (from == to) "S${season}E$from is" else "S${season}E$from–E$to are"
        is Gap.MissingSeason -> "Season $season is"
    }
}

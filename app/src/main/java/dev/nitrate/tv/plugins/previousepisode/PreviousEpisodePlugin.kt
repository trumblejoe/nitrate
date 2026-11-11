package dev.nitrate.tv.plugins.previousepisode

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.tv.plugins.core.GuardAction
import dev.nitrate.tv.plugins.core.GuardChoice
import dev.nitrate.tv.plugins.core.GuardDecision
import dev.nitrate.tv.plugins.core.PlaybackGuard
import dev.nitrate.tv.plugins.core.PluginContext
import dev.nitrate.tv.plugins.previousepisode.EpisodeOrder.Scope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

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

    private suspend fun settings(context: PluginContext) = context.store.current(id, PreviousEpisodeSettings())

    override suspend fun beforePlay(item: BaseItemDto, context: PluginContext): GuardDecision {
        if (item.type != BaseItemKind.EPISODE) return GuardDecision.Allow
        val seriesId = item.seriesId ?: return GuardDecision.Allow
        val s = settings(context)
        if (s.skipWhenResuming && (item.userData?.playbackPositionTicks ?: 0L) > 0L) return GuardDecision.Allow

        val episodes = context.repository.allEpisodes(seriesId)
        val byId = episodes.associateBy { it.id.toString() }
        val check = EpisodeOrder.check(episodes.map { it.toRef() }, item.id.toString(), s.scope, s.includeSpecials, s.detectGaps)
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
                add(GuardChoice("Mark $what as watched and play", GuardAction.MarkWatchedThenPlay(check.unwatched.mapNotNull { byId[it.id]?.id })))
            }
            add(GuardChoice("Play ${item.toRef().label} anyway", GuardAction.PlayAnyway))
            add(GuardChoice("Cancel", GuardAction.Cancel))
        }
        val title = if (check.unwatched.isNotEmpty()) "You may have missed something" else "Something seems to be missing"
        return GuardDecision.Ask(title, lines.joinToString("\n"), choices)
    }

    private const val MAX_LISTED = 3

    private fun BaseItemDto.toRef() = EpisodeRef(
        id = id.toString(),
        season = parentIndexNumber,
        episode = indexNumber,
        episodeEnd = indexNumberEnd,
        airsBeforeSeason = airsBeforeSeasonNumber,
        airsBeforeEpisode = airsBeforeEpisodeNumber,
        airsAfterSeason = airsAfterSeasonNumber,
        played = userData?.played == true,
        label = if (parentIndexNumber == 0) "Special “${name.orEmpty()}”" else "S${parentIndexNumber}E${indexNumber}",
    )

    private fun Gap.describe() = when (this) {
        is Gap.MissingEpisodes -> if (from == to) "S${season}E$from is" else "S${season}E$from–E$to are"
        is Gap.MissingSeason -> "Season $season is"
    }

    override val settings: (@Composable (PluginContext) -> Unit) = { context -> SettingsPage(context) }

    @Composable
    private fun SettingsPage(context: PluginContext) {
        val s by context.store.settings(id, PreviousEpisodeSettings()).collectAsState(PreviousEpisodeSettings())
        val scope = rememberCoroutineScope()
        fun update(f: (PreviousEpisodeSettings) -> PreviousEpisodeSettings) = scope.launch { context.store.update(id, PreviousEpisodeSettings(), f) }
        Column {
            ListItem(
                selected = false,
                onClick = { update { it.copy(scope = Scope.entries[(it.scope.ordinal + 1) % Scope.entries.size]) } },
                headlineContent = { Text("Check") },
                supportingContent = { Text("How far back to look for unwatched episodes") },
                trailingContent = {
                    Text(
                        when (s.scope) {
                            Scope.PREVIOUS -> "Just the one before  ›"
                            Scope.SEASON -> "The whole season  ›"
                            Scope.SERIES -> "The whole series  ›"
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            Toggle("Include specials", "Specials placed before, during or after seasons count as episodes", s.includeSpecials) { v -> update { it.copy(includeSpecials = v) } }
            Toggle("Spot missing episodes", "Gaps in episode or season numbering", s.detectGaps) { v -> update { it.copy(detectGaps = v) } }
            Toggle("Skip when resuming", "Don't ask about an episode you've already started", s.skipWhenResuming) { v -> update { it.copy(skipWhenResuming = v) } }
        }
    }

    @Composable
    private fun Toggle(label: String, detail: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        ListItem(
            selected = false,
            onClick = { onToggle(!checked) },
            headlineContent = { Text(label) },
            supportingContent = { Text(detail) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        )
    }
}

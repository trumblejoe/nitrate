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
import dev.nitrate.core.episodes.EpisodeOrder.Scope
import dev.nitrate.core.episodes.PreviousEpisodePlugin
import dev.nitrate.core.episodes.PreviousEpisodeSettings
import dev.nitrate.core.plugins.settings
import dev.nitrate.core.plugins.update
import dev.nitrate.tv.plugins.core.PluginContext
import dev.nitrate.tv.plugins.core.TvPluginUi
import kotlinx.coroutines.launch

/** TV settings page for [PreviousEpisodePlugin]; the check itself lives in :core. */
object PreviousEpisodeUi : TvPluginUi {
    override val pluginId = PreviousEpisodePlugin.id

    override val settings: (@Composable (PluginContext) -> Unit) = { context -> SettingsPage(context) }

    @Composable
    private fun SettingsPage(context: PluginContext) {
        val id = PreviousEpisodePlugin.id
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

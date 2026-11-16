package dev.nitrate.tv.plugins.writing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.core.plugins.PlaybackControls
import dev.nitrate.core.plugins.settings
import dev.nitrate.core.plugins.update
import dev.nitrate.core.writing.WritingPlugin
import dev.nitrate.core.writing.WritingSettings
import dev.nitrate.tv.plugins.core.PlayerPanel
import dev.nitrate.tv.plugins.core.PluginContext
import dev.nitrate.tv.plugins.core.ScreenPlugin
import kotlinx.coroutines.launch

/** TV screens for [WritingPlugin]: a pane beside the video (Ctrl+E) and a full page from Home. */
object WritingUi : PlayerPanel, ScreenPlugin {
    override val pluginId = WritingPlugin.id
    override val panelLabel = "Write"
    override val shortcut = Key.E
    override val screenLabel = "Writing"

    @Composable
    private fun Editor(context: PluginContext, playback: PlaybackControls?, modifier: Modifier) {
        val settings by context.store.settings(pluginId, WritingSettings()).collectAsState(WritingSettings())
        val account by context.container.account.collectAsState()
        WritingEditor(context.container.writing, settings.backupToServer, playback, modifier, shared = context.container.sharedWriting, selfName = account?.name)
    }

    @Composable
    override fun Panel(context: PluginContext, playback: PlaybackControls, modifier: Modifier) =
        Editor(context, playback, modifier)

    @Composable
    override fun Screen(context: PluginContext) = Editor(context, null, Modifier.fillMaxSize())

    override val settings: (@Composable (PluginContext) -> Unit) = { context ->
        val s by context.store.settings(pluginId, WritingSettings()).collectAsState(WritingSettings())
        val scope = rememberCoroutineScope()
        ListItem(
            selected = false,
            onClick = { scope.launch { context.store.update(pluginId, WritingSettings()) { it.copy(backupToServer = !it.backupToServer) } } },
            headlineContent = { Text("Back up screenplays to the server") },
            supportingContent = { Text("Keeps a copy of each screenplay in your Jellyfin account, so another device or a reset one can restore it") },
            trailingContent = { Switch(checked = s.backupToServer, onCheckedChange = null) },
        )
        // Which accounts' scripts sync with their server (this TV only; Primary by default).
        val shared = context.container.sharedWriting
        var refresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
        val options by androidx.compose.runtime.produceState(emptyList<dev.nitrate.android.writing.SharedWriting.SyncOption>(), refresh) {
            value = runCatching { shared.syncOptions() }.getOrDefault(emptyList())
        }
        val available by shared.available.collectAsState(false)
        Text(
            if (available) "Sync scripts with: they stay in step on all your devices, with the full history. Off: they stay on this TV, history kept here."
            else "Syncing scripts needs the Nitrate Companion plugin on the server; until then scripts and their history stay on this TV.",
            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
        )
        options.forEach { o ->
            ListItem(
                selected = false,
                onClick = {
                    shared.setSync(o.account.key, !o.on)
                    refresh++
                    scope.launch { runCatching { shared.autoSync() } }
                },
                headlineContent = { Text("Sync scripts with ${o.account.serverName} · ${o.account.userName}") },
                supportingContent = { Text(if (o.primary) "Primary server" else "Another server") },
                trailingContent = { Switch(checked = o.on, onCheckedChange = null) },
            )
        }
    }
}

/** Every plugin's TV screens. */
val TV_PLUGIN_UI: List<dev.nitrate.tv.plugins.core.TvPluginUi> = listOf(dev.nitrate.tv.plugins.previousepisode.PreviousEpisodeUi, WritingUi)

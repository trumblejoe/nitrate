package dev.nitrate.tv.plugins.writing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.tv.plugins.core.PlaybackControls
import dev.nitrate.tv.plugins.core.PlayerPanel
import dev.nitrate.tv.plugins.core.PluginContext
import dev.nitrate.tv.plugins.core.ScreenPlugin
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class WritingSettings(val backupToServer: Boolean = true)

/** A Fountain screenplay editor beside the video, or full screen, for a Bluetooth keyboard. */
object WritingPlugin : PlayerPanel, ScreenPlugin {
    override val id = "writing"
    override val name = "Writing pane"
    override val description =
        "A screenplay editor for a Bluetooth keyboard, beside the video while it plays (Ctrl+E) or full screen from Home. " +
            "Saves Fountain files that Fade In opens."
    override val panelLabel = "Write"
    override val shortcut = Key.E
    override val screenLabel = "Writing"

    private var store: WritingStore? = null

    private fun store(context: PluginContext) = store ?: WritingStore(context.appContext, context.repository).also { store = it }

    @Composable
    private fun Editor(context: PluginContext, playback: PlaybackControls?, modifier: Modifier) {
        val settings by context.store.settings(id, WritingSettings()).collectAsState(WritingSettings())
        WritingEditor(store(context), settings.backupToServer, playback, modifier)
    }

    @Composable
    override fun Panel(context: PluginContext, playback: PlaybackControls, modifier: Modifier) =
        Editor(context, playback, modifier)

    @Composable
    override fun Screen(context: PluginContext) = Editor(context, null, Modifier.fillMaxSize())

    override val settings: (@Composable (PluginContext) -> Unit) = { context ->
        val s by context.store.settings(id, WritingSettings()).collectAsState(WritingSettings())
        val scope = rememberCoroutineScope()
        ListItem(
            selected = false,
            onClick = { scope.launch { context.store.update(id, WritingSettings()) { it.copy(backupToServer = !it.backupToServer) } } },
            headlineContent = { Text("Back up to your Jellyfin server") },
            supportingContent = { Text("Keeps a copy of each script in your Jellyfin account, so another TV or a reset TV can restore it") },
            trailingContent = { Switch(checked = s.backupToServer, onCheckedChange = null) },
        )
    }
}

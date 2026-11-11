package dev.nitrate.tv.plugins.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import dev.nitrate.android.AppContainer
import dev.nitrate.core.plugins.PlaybackControls
import dev.nitrate.core.plugins.PluginStorage

/** What a plugin's TV screens get to work with. */
class PluginContext(val container: AppContainer) {
    val server get() = container.server
    val store: PluginStorage get() = container.plugins.host.store
}

/**
 * The TV screens for a plugin whose logic lives in :core (matched by [pluginId]). A plugin can
 * have a settings page, a panel beside the video, and a full page reachable from Home.
 */
interface TvPluginUi {
    val pluginId: String

    /** Optional settings, shown under the plugin in Plugins. */
    val settings: (@Composable (PluginContext) -> Unit)? get() = null
}

/** A panel beside the video in the player, opened from the controls or with Ctrl + [shortcut]. */
interface PlayerPanel : TvPluginUi {
    val panelLabel: String
    val shortcut: Key

    @Composable
    fun Panel(context: PluginContext, playback: PlaybackControls, modifier: Modifier)
}

/** A full-screen page reachable from Home. */
interface ScreenPlugin : TvPluginUi {
    val screenLabel: String

    @Composable
    fun Screen(context: PluginContext)
}

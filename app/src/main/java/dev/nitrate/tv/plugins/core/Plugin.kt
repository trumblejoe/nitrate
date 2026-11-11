package dev.nitrate.tv.plugins.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import dev.nitrate.tv.data.JellyfinRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * A feature that can be switched on and off in Settings → Plugins.
 *
 * Plugins are compiled into the app (Android TV apps may not download and run code), and
 * extend it through the interfaces below. A plugin implements any of them that it needs.
 */
interface NitratePlugin {
    /** Stable id; settings are stored under it, so never change it. */
    val id: String
    val name: String
    val description: String
    val enabledByDefault: Boolean get() = false

    /** Optional settings page, shown under the plugin in Settings → Plugins. */
    val settings: (@Composable (PluginContext) -> Unit)? get() = null
}

/** What plugins get to work with. */
class PluginContext(
    val repository: JellyfinRepository,
    val store: PluginStore,
    val appContext: android.content.Context,
)

/** Runs before an item plays and may warn or suggest something else. */
interface PlaybackGuard : NitratePlugin {
    suspend fun beforePlay(item: BaseItemDto, context: PluginContext): GuardDecision
}

sealed interface GuardDecision {
    data object Allow : GuardDecision

    /** Ask the user; [choices] are shown in order, the first focused. */
    data class Ask(val title: String, val message: String, val choices: List<GuardChoice>) : GuardDecision
}

data class GuardChoice(val label: String, val action: GuardAction)

sealed interface GuardAction {
    data object PlayAnyway : GuardAction
    data object Cancel : GuardAction
    data class PlayInstead(val item: BaseItemDto) : GuardAction

    /** Marks [itemIds] watched, then plays the original item. */
    data class MarkWatchedThenPlay(val itemIds: List<UUID>) : GuardAction
}

/** Lets a player panel control playback without owning the player. */
interface PlaybackControls {
    val isPlaying: Boolean
    fun togglePlay()
    fun seekBy(deltaMs: Long)
}

/** A panel beside the video in the player, opened from the controls or with Ctrl + [shortcut]. */
interface PlayerPanel : NitratePlugin {
    val panelLabel: String
    val shortcut: Key

    @Composable
    fun Panel(context: PluginContext, playback: PlaybackControls, modifier: Modifier)
}

/** A full-screen page reachable from Home. */
interface ScreenPlugin : NitratePlugin {
    val screenLabel: String

    @Composable
    fun Screen(context: PluginContext)
}

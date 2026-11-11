package dev.nitrate.core.plugins

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * A feature that can be switched on and off in Settings → Plugins. This is the
 * platform-neutral part; each app adds its own screens for a plugin (see the apps' plugin UI).
 * Plugins are compiled in: app stores don't allow downloading and running code.
 */
interface NitratePlugin {
    /** Stable id; settings are stored under it, so never change it. */
    val id: String
    val name: String
    val description: String
    val enabledByDefault: Boolean get() = false
}

/** What plugins get to work with. */
class PluginHost(val server: MediaServer, val store: PluginStorage)

/** Runs before an item plays and may warn or suggest something else. */
interface PlaybackGuard : NitratePlugin {
    suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision
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
    data class PlayInstead(val item: MediaItem) : GuardAction

    /** Marks [itemIds] watched, then plays the original item. */
    data class MarkWatchedThenPlay(val itemIds: List<String>) : GuardAction
}

/** Lets a panel or page control playback without owning the player. */
interface PlaybackControls {
    val isPlaying: Boolean
    fun togglePlay()
    fun seekBy(deltaMs: Long)
}

/** Per-device plugin state: which plugins are on, and each plugin's own settings as JSON. */
interface PluginStorage {
    /** Explicit on/off choices; plugins without one use their default. */
    val enabledOverrides: Flow<Map<String, Boolean>>
    suspend fun setEnabled(pluginId: String, enabled: Boolean)
    fun raw(pluginId: String): Flow<String?>
    suspend fun saveRaw(pluginId: String, value: String)
}

val pluginJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

inline fun <reified T> PluginStorage.settings(pluginId: String, default: T): Flow<T> = raw(pluginId).map { value ->
    value?.let { runCatching { pluginJson.decodeFromString<T>(it) }.getOrNull() } ?: default
}

suspend inline fun <reified T> PluginStorage.current(pluginId: String, default: T): T = settings(pluginId, default).first()

suspend inline fun <reified T> PluginStorage.update(pluginId: String, default: T, transform: (T) -> T) =
    saveRaw(pluginId, pluginJson.encodeToString(transform(current(pluginId, default))))

/** Every plugin in the app, and which are switched on. */
class PluginRegistry(val all: List<NitratePlugin>, val host: PluginHost) {
    init {
        require(all.map { it.id }.toSet().size == all.size) { "Plugin ids must be unique" }
    }

    val enabledIds: Flow<Set<String>> = host.store.enabledOverrides.map { overrides ->
        all.filter { overrides[it.id] ?: it.enabledByDefault }.map { it.id }.toSet()
    }

    val enabled: Flow<List<NitratePlugin>> = enabledIds.map { ids -> all.filter { it.id in ids } }

    suspend fun enabledNow(): List<NitratePlugin> = enabled.first()

    suspend fun setEnabled(id: String, on: Boolean) = host.store.setEnabled(id, on)

    /**
     * Runs enabled guards before [item] plays. [ask] shows a guard's question and returns the
     * user's choice; the first guard that asks decides. A failing guard never blocks playback.
     */
    suspend fun checkBeforePlay(item: MediaItem, ask: suspend (GuardDecision.Ask) -> GuardAction): GuardAction {
        for (guard in enabledNow().filterIsInstance<PlaybackGuard>()) {
            val decision = runCatching { guard.beforePlay(item, host) }.getOrDefault(GuardDecision.Allow)
            if (decision is GuardDecision.Ask) return ask(decision)
        }
        return GuardAction.PlayAnyway
    }
}

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

/** What plugins get to work with; [together]: this device's watch-together session (null where there's none to ask). */
class PluginHost(val server: MediaServer, val store: PluginStorage, val together: TogetherSession? = null)

/** This device's watch-together (SyncPlay) session, as plugins see it. */
interface TogetherSession {
    /** The names of the people in this device's Group Watch Session (the user among them); empty outside one. */
    fun participants(): List<String>

    /** The id of this device's Group Watch Session, null outside one. */
    fun sessionId(): String? = null

    /** Whether "Start a Group Watch Session" can start one (the server has Group Watch Sessions). */
    fun available(): Boolean = true

    /**
     * Starts watching [item] together: a Group Watch Session of group [groupId] on it (or the
     * group's live one), the members ([userIds], [names]) told at once. The item is then played as usual.
     */
    suspend fun start(item: MediaItem, groupId: String, userIds: List<String>, names: String)
}

/** Runs before an item plays and may warn or suggest something else. */
interface PlaybackGuard : NitratePlugin {
    suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision

    /**
     * The same, knowing where the playback started ([origin]: a watch group's card, elsewhere, or
     * "for myself"; null: not said, taken as elsewhere; see [dev.nitrate.core.partners.WatchPartners.countingFor]).
     */
    suspend fun beforePlay(item: MediaItem, host: PluginHost, origin: dev.nitrate.core.partners.PlayOrigin?): GuardDecision = beforePlay(item, host)

    /** The user said "Not now" (or the play went no further): forget anything kept for [item]'s playback. */
    fun cancelled(item: MediaItem, host: PluginHost) {}

    /**
     * Where [item]'s playback started, told to every guard before any of them asks: the first guard
     * that asks decides, so one that comes later would otherwise never hear it (a group's card
     * then played as if from elsewhere, and counted for no group).
     */
    fun noteOrigin(item: MediaItem, host: PluginHost, origin: dev.nitrate.core.partners.PlayOrigin?) {}
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

    /**
     * Runs [run] (a plugin's own step, e.g. noting who's watching, or starting a watch-together
     * session), then plays the original item; if [run] fails, plays only when [playIfFailed].
     */
    class RunThenPlay(val playIfFailed: Boolean = true, val run: suspend () -> Unit) : GuardAction

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
    suspend fun checkBeforePlay(
        item: MediaItem,
        /** Where the playback started (a watch group's card, elsewhere); null: not said. */
        origin: dev.nitrate.core.partners.PlayOrigin? = null,
        ask: suspend (GuardDecision.Ask) -> GuardAction,
    ): GuardAction {
        val guards = enabledNow().filterIsInstance<PlaybackGuard>()
        guards.forEach { g -> runCatching { g.noteOrigin(item, host, origin) } }
        for (guard in guards) {
            val decision = runCatching { guard.beforePlay(item, host, origin) }.getOrDefault(GuardDecision.Allow)
            if (decision is GuardDecision.Ask) return ask(decision).also { if (it == GuardAction.Cancel) guards.forEach { g -> g.cancelled(item, host) } }
        }
        return GuardAction.PlayAnyway
    }

    /**
     * Before this device tells another one to play [item] (the phone as a remote, "Play on…"): the
     * same guards, asked here with [ask]. The item to send there, or null when nothing should be
     * sent: Cancel sends nothing, "play this instead" sends that one, marking watched happens first
     * (through [markWatched]), and a plugin's own step runs first, sending only if it worked or the
     * guard plays anyway on failure.
     */
    suspend fun checkBeforeSending(
        item: MediaItem,
        ask: suspend (GuardDecision.Ask) -> GuardAction,
        origin: dev.nitrate.core.partners.PlayOrigin? = null,
        markWatched: suspend (String) -> Unit,
    ): MediaItem? = when (val action = checkBeforePlay(item, origin, ask)) {
        GuardAction.PlayAnyway -> item
        GuardAction.Cancel -> null
        is GuardAction.PlayInstead -> action.item
        is GuardAction.MarkWatchedThenPlay -> item.also { action.itemIds.forEach { id -> runCatching { markWatched(id) } } }
        is GuardAction.RunThenPlay -> item.takeIf { runCatching { action.run() }.isSuccess || action.playIfFailed }
    }
}

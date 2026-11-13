package dev.nitrate.android.sync

import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.pages.DisplayProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps display settings (every display profile, plus what's shared) in the user's Jellyfin
 * display preferences, so they follow the user to other devices. Merged setting by setting
 * against the last copy agreed with the server ([DisplayProfiles.merge]): what changed here wins,
 * everything else comes from the server, so two devices never undo each other's changes.
 *
 * Profiles live under their own display-preferences id; the old single set (still written by
 * older Nitrate versions) is only read once, to start "My settings" on a device that has none.
 * Device-specific settings (playback, which profile this device uses) stay local.
 */
class SettingsSync(
    private val repository: MediaServer,
    private val store: DisplaySettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null
    private val lock = Mutex()

    init {
        store.onChanged = ::schedulePush
    }

    /** On sign-in: brings this device and the server together. */
    fun pull() {
        val userId = repository.userId ?: return
        scope.launch { sync(userId) }
    }

    /** Debounced so flipping through options sends one update. */
    private fun schedulePush(userId: String) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            sync(userId)
        }
    }

    private suspend fun sync(userId: String) = lock.withLock {
        // Settings from before profiles become "My settings"; that's not a change to send over
        // another device's, so it becomes the base too.
        store.migrateLocal(userId)
        if (store.syncBase(userId) == null) store.export(userId).takeIf { it.isNotEmpty() }?.let { store.setSyncBase(userId, it) }
        val remote = runCatching { repository.loadCustomPrefs(PREFS_ID) }.getOrNull() ?: return@withLock
        var local = store.export(userId)
        if (remote.isEmpty()) {
            // First device on profiles: start from this device's settings, or the old shared set.
            if (local.isEmpty()) {
                val old = runCatching { repository.loadCustomPrefs() }.getOrNull().orEmpty()
                if (old.isNotEmpty()) {
                    local = DisplayProfiles.migrate(old) { o ->
                        o["prefs"]?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.nitrate.core.pages.DisplayPrefs>(it) }.getOrNull() }
                            ?.let { """{"hideSpoilers":${it.hideSpoilers}}""" }
                    }
                    store.import(userId, local)
                }
            }
            if (local.isEmpty() || runCatching { repository.saveCustomPrefs(local, PREFS_ID) }.isSuccess) store.setSyncBase(userId, local)
            return@withLock
        }
        val base = store.syncBase(userId).orEmpty()
        val merged = DisplayProfiles.merge(base, local, remote)
        if (merged != remote && runCatching { repository.saveCustomPrefs(merged, PREFS_ID) }.isFailure) return@withLock
        // Anything changed here while the server was being asked is kept too.
        val now = store.export(userId)
        store.import(userId, DisplayProfiles.merge(local, now, merged))
        store.setSyncBase(userId, merged)
    }

    private companion object {
        const val PUSH_DELAY_MS = 1_500L
        /** Display profiles (older versions use [MediaServer.DEFAULT_PREFS] for the single set). */
        const val PREFS_ID = "nitrate-display"
    }
}

package dev.nitrate.tv.data

import dev.nitrate.tv.data.rows.DisplaySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps display settings in the user's Jellyfin display preferences, so rows and views follow
 * the user to other TVs. The server copy wins on sign-in; local changes are pushed shortly after.
 * Device-specific settings (playback overrides) deliberately stay local.
 */
class SettingsSync(
    private val repository: JellyfinRepository,
    private val store: DisplaySettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    init {
        store.onChanged = ::schedulePush
    }

    /** Pulls the server copy into local storage. If the server has none yet, seeds it from this TV. */
    fun pull() {
        val userId = repository.userId ?: return
        scope.launch {
            val remote = runCatching { repository.loadCustomPrefs() }.getOrNull() ?: return@launch
            if (remote.isEmpty()) {
                push(userId)
            } else {
                store.import(userId, remote)
            }
        }
    }

    /** Debounced so flipping through options sends one update. */
    private fun schedulePush(userId: String) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            push(userId)
        }
    }

    private suspend fun push(userId: String) {
        val local = store.export(userId)
        if (local.isNotEmpty()) runCatching { repository.saveCustomPrefs(local) }
    }

    private companion object {
        const val PUSH_DELAY_MS = 1_500L
    }
}

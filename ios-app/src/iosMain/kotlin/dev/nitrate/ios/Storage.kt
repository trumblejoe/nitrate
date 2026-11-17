package dev.nitrate.ios

import dev.nitrate.core.jellyfin.SessionStorage
import dev.nitrate.core.jellyfin.StoredSession
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** Small values kept in the app's user defaults. */
internal object Defaults {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    fun get(key: String): String? = defaults.stringForKey(key)

    fun set(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}

internal val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/**
 * Every signed-in server and user, and which is current, as on Android's SessionStore. Kept in
 * user defaults for now (the Keychain comes with the shared container).
 */
internal class IosSessionStore : SessionStorage {
    private val lock = Mutex()

    private fun sessions(): List<StoredSession> =
        Defaults.get(ACCOUNTS)?.let { runCatching { StoreJson.decodeFromString<List<StoredSession>>(it) }.getOrNull() }.orEmpty()

    private fun currentKey(): String? = Defaults.get(CURRENT)

    private fun write(sessions: List<StoredSession>, current: String?) {
        Defaults.set(ACCOUNTS, StoreJson.encodeToString(sessions))
        Defaults.set(CURRENT, current)
    }

    override suspend fun load(): StoredSession? = lock.withLock {
        val current = currentKey() ?: return null
        sessions().firstOrNull { it.key == current }
    }

    override suspend fun all(): List<StoredSession> = lock.withLock { sessions() }

    override suspend fun save(session: StoredSession) = lock.withLock {
        write(sessions().filterNot { it.key == session.key } + session, session.key)
    }

    override suspend fun update(session: StoredSession) = lock.withLock {
        val all = sessions()
        if (all.none { it.key == session.key }) return@withLock
        // In place, and whichever account was current stays current.
        write(all.map { if (it.key == session.key) session else it }, currentKey())
    }

    override suspend fun select(key: String): StoredSession? = lock.withLock {
        val all = sessions()
        val found = all.firstOrNull { it.key == key } ?: return null
        write(all, key)
        found
    }

    override suspend fun remove(key: String) = lock.withLock {
        write(sessions().filterNot { it.key == key }, currentKey().takeIf { it != key })
    }

    override suspend fun clear() {
        val key = lock.withLock { currentKey() } ?: return
        remove(key)
    }

    private companion object {
        const val ACCOUNTS = "accounts"
        const val CURRENT = "current_account"
    }
}

/** This device's playback settings (never synced), as on Android. */
internal class IosPlaybackSettings : PlaybackSettingsRepository {
    private val state = MutableStateFlow(
        Defaults.get(KEY)?.let { runCatching { StoreJson.decodeFromString<PlaybackSettings>(it) }.getOrNull() } ?: PlaybackSettings(),
    )
    override val settings: StateFlow<PlaybackSettings> = state

    override suspend fun current(): PlaybackSettings = state.value

    override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
        val next = transform(state.value)
        state.value = next
        Defaults.set(KEY, StoreJson.encodeToString(next))
    }

    private companion object {
        const val KEY = "playback_settings"
    }
}

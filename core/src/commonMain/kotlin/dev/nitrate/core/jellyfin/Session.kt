package dev.nitrate.core.jellyfin

import kotlinx.serialization.Serializable

/** Who this app is to the server: shown in the server's device list and tied to access tokens. */
data class ClientIdentity(val clientName: String, val version: String, val deviceId: String, val deviceName: String)

/** A signed-in server and user. Several can be saved; one is current. */
@Serializable
data class StoredSession(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val serverName: String = "",
    val imageTag: String? = null,
) {
    val key: String get() = accountKey(serverUrl, userId)
}

fun accountKey(serverUrl: String, userId: String) = "${serverUrl.trimEnd('/')}|$userId"

/**
 * Where sign-ins are kept (DataStore on Android, Keychain/UserDefaults on iOS): every saved
 * account, and which one is current.
 */
interface SessionStorage {
    /** The current account. */
    suspend fun load(): StoredSession?

    /** Adds or updates [session] and makes it current. */
    suspend fun save(session: StoredSession)

    /** Forgets the current account (signing out). */
    suspend fun clear()

    suspend fun all(): List<StoredSession> = listOfNotNull(load())

    /** Makes the saved account with [key] current, returning it (null if not saved). */
    suspend fun select(key: String): StoredSession? = load()?.takeIf { it.key == key }

    suspend fun remove(key: String) {
        if (load()?.key == key) clear()
    }
}

/** Keeps sessions in memory: tests, previews, and platforms without storage yet. */
class MemorySessionStorage(initial: List<StoredSession> = emptyList()) : SessionStorage {
    private val sessions = initial.toMutableList()
    private var current: String? = initial.firstOrNull()?.key

    override suspend fun load() = sessions.firstOrNull { it.key == current }
    override suspend fun save(session: StoredSession) {
        sessions.removeAll { it.key == session.key }
        sessions += session
        current = session.key
    }
    override suspend fun clear() {
        current?.let { remove(it) }
    }
    override suspend fun all(): List<StoredSession> = sessions.toList()
    override suspend fun select(key: String) = sessions.firstOrNull { it.key == key }?.also { current = key }
    override suspend fun remove(key: String) {
        sessions.removeAll { it.key == key }
        if (current == key) current = null
    }
}

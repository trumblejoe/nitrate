package dev.nitrate.core.jellyfin

import kotlinx.serialization.Serializable

/** Who this app is to the server: shown in the server's device list and tied to access tokens. */
data class ClientIdentity(val clientName: String, val version: String, val deviceId: String, val deviceName: String)

/** The signed-in server and user. */
@Serializable
data class StoredSession(val serverUrl: String, val accessToken: String, val userId: String, val userName: String)

/** Where the sign-in is kept (DataStore on Android, Keychain/UserDefaults on iOS). */
interface SessionStorage {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()
}

package dev.nitrate.android.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nitrate.core.jellyfin.SessionStorage
import dev.nitrate.core.jellyfin.StoredSession
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every server and user signed in on this device, and which one is current. */
class SessionStore(private val store: DataStore<Preferences>) : SessionStorage {
    private object Keys {
        val accounts = stringPreferencesKey("accounts")
        val current = stringPreferencesKey("current")

        // Before multiple accounts: one sign-in, migrated on first read.
        val serverUrl = stringPreferencesKey("server_url")
        val accessToken = stringPreferencesKey("access_token")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
    }

    private fun Preferences.sessions(): List<StoredSession> {
        val json = this[Keys.accounts]
        if (json == null) {
            // The single sign-in saved by older versions.
            val url = this[Keys.serverUrl] ?: return emptyList()
            val token = this[Keys.accessToken] ?: return emptyList()
            val user = this[Keys.userId] ?: return emptyList()
            return listOf(StoredSession(url, token, user, this[Keys.userName].orEmpty()))
        }
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun text(name: String) = o[name]?.jsonPrimitive?.contentOrNull.orEmpty()
            StoredSession(
                serverUrl = text("serverUrl").ifEmpty { return@mapNotNull null },
                accessToken = text("accessToken").ifEmpty { return@mapNotNull null },
                userId = text("userId").ifEmpty { return@mapNotNull null },
                userName = text("userName"),
                serverName = text("serverName"),
                imageTag = text("imageTag").ifBlank { null },
                localUrl = text("localUrl").ifBlank { null },
                vpnUrl = text("vpnUrl").ifBlank { null },
                remoteUrl = text("remoteUrl").ifBlank { null },
                serverId = text("serverId").ifBlank { null },
                primary = o["primary"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    /** No current account after signing out of one: the app then offers the others. */
    private fun Preferences.currentKey(): String? =
        this[Keys.current] ?: if (this[Keys.accounts] == null) sessions().firstOrNull()?.key else null

    private suspend fun write(sessions: List<StoredSession>, current: String?) {
        store.edit { prefs ->
            prefs[Keys.accounts] = JsonArray(
                sessions.map { s ->
                    JsonObject(
                        mapOf(
                            "serverUrl" to JsonPrimitive(s.serverUrl),
                            "accessToken" to JsonPrimitive(s.accessToken),
                            "userId" to JsonPrimitive(s.userId),
                            "userName" to JsonPrimitive(s.userName),
                            "serverName" to JsonPrimitive(s.serverName),
                            "imageTag" to JsonPrimitive(s.imageTag ?: ""),
                            "localUrl" to JsonPrimitive(s.localUrl ?: ""),
                            "vpnUrl" to JsonPrimitive(s.vpnUrl ?: ""),
                            "remoteUrl" to JsonPrimitive(s.remoteUrl ?: ""),
                            "serverId" to JsonPrimitive(s.serverId ?: ""),
                            "primary" to JsonPrimitive(s.primary),
                        ),
                    )
                },
            ).toString()
            if (current != null) prefs[Keys.current] = current else prefs.remove(Keys.current)
            listOf(Keys.serverUrl, Keys.accessToken, Keys.userId, Keys.userName).forEach { prefs.remove(it) }
        }
    }

    override suspend fun load(): StoredSession? {
        val prefs = store.data.first()
        val current = prefs.currentKey() ?: return null
        return prefs.sessions().firstOrNull { it.key == current }
    }

    override suspend fun all(): List<StoredSession> = store.data.first().sessions()

    override suspend fun save(session: StoredSession) {
        val sessions = all().filterNot { it.key == session.key } + session
        write(sessions, session.key)
    }

    override suspend fun update(session: StoredSession) {
        val prefs = store.data.first()
        val sessions = prefs.sessions()
        if (sessions.none { it.key == session.key }) return
        // In place, and whichever account was current stays current.
        write(sessions.map { if (it.key == session.key) session else it }, prefs.currentKey())
    }

    override suspend fun select(key: String): StoredSession? {
        val found = all().firstOrNull { it.key == key } ?: return null
        write(all(), key)
        return found
    }

    override suspend fun remove(key: String) {
        val prefs = store.data.first()
        val current = prefs.currentKey().takeIf { it != key }
        write(prefs.sessions().filterNot { it.key == key }, current)
    }

    override suspend fun clear() {
        store.data.first().currentKey()?.let { remove(it) }
    }
}

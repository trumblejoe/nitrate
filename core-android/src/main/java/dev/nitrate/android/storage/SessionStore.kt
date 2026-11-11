package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nitrate.core.jellyfin.SessionStorage
import dev.nitrate.core.jellyfin.StoredSession
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "session")

/** Every server and user signed in on this device, and which one is current. */
class SessionStore(private val context: Context) : SessionStorage {
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
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            StoredSession(
                serverUrl = o.getString("serverUrl"),
                accessToken = o.getString("accessToken"),
                userId = o.getString("userId"),
                userName = o.optString("userName"),
                serverName = o.optString("serverName"),
                imageTag = o.optString("imageTag").ifBlank { null },
            )
        }
    }

    /** No current account after signing out of one: the app then offers the others. */
    private fun Preferences.currentKey(): String? =
        this[Keys.current] ?: if (this[Keys.accounts] == null) sessions().firstOrNull()?.key else null

    private suspend fun write(sessions: List<StoredSession>, current: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.accounts] = JSONArray(
                sessions.map { s ->
                    JSONObject()
                        .put("serverUrl", s.serverUrl)
                        .put("accessToken", s.accessToken)
                        .put("userId", s.userId)
                        .put("userName", s.userName)
                        .put("serverName", s.serverName)
                        .put("imageTag", s.imageTag ?: "")
                },
            ).toString()
            if (current != null) prefs[Keys.current] = current else prefs.remove(Keys.current)
            listOf(Keys.serverUrl, Keys.accessToken, Keys.userId, Keys.userName).forEach { prefs.remove(it) }
        }
    }

    override suspend fun load(): StoredSession? {
        val prefs = context.dataStore.data.first()
        val current = prefs.currentKey() ?: return null
        return prefs.sessions().firstOrNull { it.key == current }
    }

    override suspend fun all(): List<StoredSession> = context.dataStore.data.first().sessions()

    override suspend fun save(session: StoredSession) {
        val sessions = all().filterNot { it.key == session.key } + session
        write(sessions, session.key)
    }

    override suspend fun select(key: String): StoredSession? {
        val found = all().firstOrNull { it.key == key } ?: return null
        write(all(), key)
        return found
    }

    override suspend fun remove(key: String) {
        val prefs = context.dataStore.data.first()
        val current = prefs.currentKey().takeIf { it != key }
        write(prefs.sessions().filterNot { it.key == key }, current)
    }

    override suspend fun clear() {
        context.dataStore.data.first().currentKey()?.let { remove(it) }
    }
}

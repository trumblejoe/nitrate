package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nitrate.core.jellyfin.SessionStorage
import dev.nitrate.core.jellyfin.StoredSession
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "session")

/** Persists the signed-in server + user. Single session for now; multi-server/user later. */
class SessionStore(private val context: Context) : SessionStorage {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val accessToken = stringPreferencesKey("access_token")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
    }

    override suspend fun load(): StoredSession? {
        val prefs = context.dataStore.data.first()
        return StoredSession(
            serverUrl = prefs[Keys.serverUrl] ?: return null,
            accessToken = prefs[Keys.accessToken] ?: return null,
            userId = prefs[Keys.userId] ?: return null,
            userName = prefs[Keys.userName] ?: "",
        )
    }

    override suspend fun save(session: StoredSession) {
        context.dataStore.edit {
            it[Keys.serverUrl] = session.serverUrl
            it[Keys.accessToken] = session.accessToken
            it[Keys.userId] = session.userId
            it[Keys.userName] = session.userName
        }
    }

    override suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

package dev.nitrate.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "session")

data class Session(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
)

/** Persists the signed-in server + user. Single session for now; multi-server/user later. */
class SessionStore(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val accessToken = stringPreferencesKey("access_token")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
    }

    suspend fun load(): Session? {
        val prefs = context.dataStore.data.first()
        return Session(
            serverUrl = prefs[Keys.serverUrl] ?: return null,
            accessToken = prefs[Keys.accessToken] ?: return null,
            userId = prefs[Keys.userId] ?: return null,
            userName = prefs[Keys.userName] ?: "",
        )
    }

    suspend fun save(session: Session) {
        context.dataStore.edit {
            it[Keys.serverUrl] = session.serverUrl
            it[Keys.accessToken] = session.accessToken
            it[Keys.userId] = session.userId
            it[Keys.userName] = session.userName
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

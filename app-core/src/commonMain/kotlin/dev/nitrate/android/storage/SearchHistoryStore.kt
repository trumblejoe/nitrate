package dev.nitrate.android.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


/** Recent searches on this device, newest first. */
class SearchHistoryStore(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("recent")

    val recent: Flow<List<String>> = store.data.map { prefs ->
        prefs[key]?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty()
    }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        store.edit { prefs ->
            val list = prefs[key]?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty()
            prefs[key] = (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX).joinToString(SEPARATOR)
        }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }

    private companion object {
        const val SEPARATOR = "\u001f"
        const val MAX = 12
    }
}

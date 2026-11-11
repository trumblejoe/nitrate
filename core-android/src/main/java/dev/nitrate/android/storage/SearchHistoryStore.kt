package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.searchStore by preferencesDataStore(name = "search_history")

/** Recent searches on this device, newest first. */
class SearchHistoryStore(private val context: Context) {
    private val key = stringPreferencesKey("recent")

    val recent: Flow<List<String>> = context.searchStore.data.map { prefs ->
        prefs[key]?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty()
    }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        context.searchStore.edit { prefs ->
            val list = prefs[key]?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty()
            prefs[key] = (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX).joinToString(SEPARATOR)
        }
    }

    suspend fun clear() {
        context.searchStore.edit { it.remove(key) }
    }

    private companion object {
        const val SEPARATOR = "\u001f"
        const val MAX = 12
    }
}

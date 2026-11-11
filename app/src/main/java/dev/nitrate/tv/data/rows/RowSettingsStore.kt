package dev.nitrate.tv.data.rows

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.rowDataStore by preferencesDataStore(name = "row_settings")

/**
 * Saves row display overrides per user, as a map of row key to [RowDisplay].
 * Rows without an entry use their own default, so defaults can improve in later versions.
 */
class RowSettingsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun key(userId: String) = stringPreferencesKey("rows_$userId")

    fun overrides(userId: String): Flow<Map<String, RowDisplay>> =
        context.rowDataStore.data.map { prefs ->
            prefs[key(userId)]?.let { runCatching { json.decodeFromString<Map<String, RowDisplay>>(it) }.getOrNull() }
                ?: emptyMap()
        }

    suspend fun save(userId: String, row: MediaRow, display: RowDisplay) {
        context.rowDataStore.edit { prefs ->
            val current = prefs[key(userId)]
                ?.let { runCatching { json.decodeFromString<Map<String, RowDisplay>>(it) }.getOrNull() }
                .orEmpty()
            val updated = if (display == row.defaultDisplay) current - row.key else current + (row.key to display)
            prefs[key(userId)] = json.encodeToString(updated)
        }
    }
}

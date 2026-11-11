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
 * Saves row display overrides and page view options per user, as a map of row key to [RowDisplay].
 * Rows without an entry use their own default, so defaults can improve in later versions.
 */
class DisplaySettingsStore(private val context: Context) {
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun key(userId: String) = stringPreferencesKey("rows_$userId")

    fun overrides(userId: String): Flow<Map<String, RowDisplay>> =
        context.rowDataStore.data.map { prefs ->
            prefs[key(userId)]?.let { runCatching { json.decodeFromString<Map<String, RowDisplay>>(it) }.getOrNull() }
                ?: emptyMap()
        }

    /** Saved view options for a page (e.g. a library), or [default] when nothing is stored. */
    inline fun <reified T> view(userId: String, pageKey: String, default: T): Flow<T> = viewFlow(userId, pageKey).map { raw ->
        raw?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: default
    }

    suspend inline fun <reified T> saveView(userId: String, pageKey: String, value: T) =
        saveViewRaw(userId, pageKey, json.encodeToString(value))

    @PublishedApi
    internal fun viewFlow(userId: String, pageKey: String): Flow<String?> =
        context.rowDataStore.data.map { it[stringPreferencesKey("view_${userId}_$pageKey")] }

    @PublishedApi
    internal suspend fun saveViewRaw(userId: String, pageKey: String, raw: String) {
        context.rowDataStore.edit { it[stringPreferencesKey("view_${userId}_$pageKey")] = raw }
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

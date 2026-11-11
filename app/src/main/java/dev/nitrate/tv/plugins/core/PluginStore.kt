package dev.nitrate.tv.plugins.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.pluginDataStore by preferencesDataStore(name = "plugins")

/** Per-device plugin state: which plugins are on, and each plugin's own settings as JSON. */
class PluginStore(private val context: Context) {
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val enabledKey = stringPreferencesKey("enabled")

    /** Explicit on/off choices; plugins without one use their default. */
    val enabledOverrides: Flow<Map<String, Boolean>> = context.pluginDataStore.data.map { prefs ->
        prefs[enabledKey]?.let { runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrNull() }.orEmpty()
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        context.pluginDataStore.edit { prefs ->
            val current = prefs[enabledKey]?.let { runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrNull() }.orEmpty()
            prefs[enabledKey] = json.encodeToString(current + (pluginId to enabled))
        }
    }

    inline fun <reified T> settings(pluginId: String, default: T): Flow<T> = raw(pluginId).map { value ->
        value?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: default
    }

    suspend inline fun <reified T> current(pluginId: String, default: T): T = settings(pluginId, default).first()

    suspend inline fun <reified T> update(pluginId: String, default: T, transform: (T) -> T) {
        saveRaw(pluginId, json.encodeToString(transform(current(pluginId, default))))
    }

    @PublishedApi
    internal fun raw(pluginId: String): Flow<String?> = context.pluginDataStore.data.map { it[stringPreferencesKey("cfg_$pluginId")] }

    @PublishedApi
    internal suspend fun saveRaw(pluginId: String, value: String) {
        context.pluginDataStore.edit { it[stringPreferencesKey("cfg_$pluginId")] = value }
    }
}

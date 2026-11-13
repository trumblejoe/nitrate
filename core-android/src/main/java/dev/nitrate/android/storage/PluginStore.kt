package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dev.nitrate.core.plugins.PluginStorage
import kotlinx.serialization.json.Json

private val Context.pluginDataStore by preferencesDataStore(name = "plugins")

/** Per-device plugin state in DataStore: which plugins are on, and each plugin's settings as JSON. */
class PluginStore(private val context: Context) : PluginStorage {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val enabledKey = stringPreferencesKey("enabled")

    override val enabledOverrides: Flow<Map<String, Boolean>> = context.pluginDataStore.data.map { prefs ->
        prefs[enabledKey]?.let { runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrNull() }.orEmpty()
    }

    override suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        context.pluginDataStore.edit { prefs ->
            val current = prefs[enabledKey]?.let { runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrNull() }.orEmpty()
            prefs[enabledKey] = json.encodeToString(current + (pluginId to enabled))
        }
    }

    /** Every plugin's settings JSON, by plugin id (for device backups). */
    val allRaw: Flow<Map<String, String>> = context.pluginDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) -> if (k.name.startsWith("cfg_") && v is String) k.name.removePrefix("cfg_") to v else null }.toMap()
    }

    override fun raw(pluginId: String): Flow<String?> = context.pluginDataStore.data.map { it[stringPreferencesKey("cfg_$pluginId")] }

    override suspend fun saveRaw(pluginId: String, value: String) {
        context.pluginDataStore.edit { it[stringPreferencesKey("cfg_$pluginId")] = value }
    }
}

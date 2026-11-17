package dev.nitrate.android.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json


/** Per-device playback settings in DataStore. Never synced: they describe this device. */
class PlaybackSettingsStore(private val store: DataStore<Preferences>) : PlaybackSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val key = stringPreferencesKey("settings")

    override val settings: Flow<PlaybackSettings> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<PlaybackSettings>(it) }.getOrNull() } ?: PlaybackSettings()
    }

    override suspend fun current(): PlaybackSettings = settings.first()

    override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
        store.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString<PlaybackSettings>(it) }.getOrNull() } ?: PlaybackSettings()
            prefs[key] = json.encodeToString(transform(current))
        }
    }
}

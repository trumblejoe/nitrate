package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

// The DataStore files behind the shared stores (one per file per process), where Android has
// always kept them.
internal val Context.playbackDataStore by preferencesDataStore(name = "playback_settings")
internal val Context.searchStore by preferencesDataStore(name = "search_history")
internal val Context.pluginDataStore by preferencesDataStore(name = "plugins")
internal val Context.profilesDataStore by preferencesDataStore(name = "profiles")
internal val Context.displayDataStore by preferencesDataStore(name = "display_settings")

/** Display settings in the file Android has always used. */
fun DisplaySettingsStore(context: Context, deviceKind: dev.nitrate.core.pages.DeviceKind = dev.nitrate.core.pages.DeviceKind.PHONE, deviceName: String = "This device") =
    DisplaySettingsStore(context.applicationContext.displayDataStore, deviceKind, deviceName)

/** Last time's screens, in the app's cache folder. */
fun DataCache(context: Context) = DataCache(context.cacheDir.absolutePath)

/** Last time's library lists, in the shared preferences Android has always used. */
fun LibraryCache(context: Context): LibraryCache {
    val prefs = context.getSharedPreferences("libraries", Context.MODE_PRIVATE)
    return LibraryCache(object : KeyValues {
        override fun get(key: String) = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    })
}
internal val Context.sessionDataStore by preferencesDataStore(name = "session")
internal val Context.backupDataStore by preferencesDataStore(name = "device_backup")

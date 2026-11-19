package dev.nitrate.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.nitrate.android.notifications.NewEpisodeNotifier
import dev.nitrate.android.playback.ThemeMusic
import dev.nitrate.android.storage.KeyValues
import dev.nitrate.android.update.AppUpdater
import dev.nitrate.core.MediaServer
import dev.nitrate.core.downloads.DownloadScheduler
import dev.nitrate.core.jellyfin.ClientIdentity
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.pages.DeviceKind
import dev.nitrate.core.playback.DeviceCapabilities
import io.ktor.client.engine.HttpClientEngine

/** The DataStore files behind the app's stores (one instance per file per process). */
class AppStores(
    val display: DataStore<Preferences>,
    val playback: DataStore<Preferences>,
    val session: DataStore<Preferences>,
    val search: DataStore<Preferences>,
    val plugins: DataStore<Preferences>,
    val profiles: DataStore<Preferences>,
    val backup: DataStore<Preferences>,
)

/** What [AppContainer] needs from the platform it runs on (Android: core-android; iOS: ios-app). */
interface AppPlatform {
    val deviceKind: DeviceKind
    val identity: ClientIdentity

    /** Which app this is in device backups and updates: "tv", "mobile" or "ios". */
    val appKind: String
    val stores: AppStores
    val filesDir: String
    val cacheDir: String
    val downloadDir: String

    /** Small values read at once, like last time's library list. */
    val libraryKeyValues: KeyValues

    /** The HTTP engine for the server (allowing many requests at once: pages ask for all rows together). */
    fun serverEngine(): HttpClientEngine
    fun downloadEngine(): HttpClientEngine
    fun downloadScheduler(): DownloadScheduler

    /** What this device plays (probed once per run). */
    fun probeCapabilities(): DeviceCapabilities
    fun themeMusic(server: MediaServer): ThemeMusic
    fun newEpisodes(server: MediaServer): NewEpisodeNotifier
    fun updater(): AppUpdater

    /** Calls [onChange] when the network changes (leaving home Wi-Fi…), to re-pick the server address. */
    fun watchNetwork(onChange: () -> Unit)

    /** Now, as "yyyy-MM-dd HH:mm:ss Z" in the device's time zone, for reports. */
    fun timestamp(): String

    /**
     * The display's mode and the modes it offers, where Nitrate matches the refresh rate (TV);
     * null elsewhere (phones, iPad), so predictions say nothing about it.
     */
    fun displaySnapshot(): dev.nitrate.core.playback.DisplaySnapshot? = null
}

/** What a player contributes to a playback report. */
class PlayerReport(
    val item: MediaItem?,
    val plan: PlaybackPlan?,
    val videoDecoder: String?,
    val audioDecoder: String?,
    val events: List<String>,
    val error: String?,
)

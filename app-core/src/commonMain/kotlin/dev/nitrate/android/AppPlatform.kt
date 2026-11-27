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

    /**
     * Whether the network in use now is mobile data or a metered Wi-Fi (a phone's hotspot), for the
     * mobile-data quality limit: phones and tablets; TVs and computers say false.
     */
    fun onMobileData(): Boolean = false

    /** Now, as "yyyy-MM-dd HH:mm:ss Z" in the device's time zone, for reports. */
    fun timestamp(): String

    /**
     * The display's mode and the modes it offers, where Nitrate matches the refresh rate (TV);
     * null elsewhere (phones, iPad), so predictions say nothing about it.
     */
    fun displaySnapshot(): dev.nitrate.core.playback.DisplaySnapshot? = null

    /** Draws PDF pages for the comic reader; null where the device can't. */
    fun pdfPages(): dev.nitrate.android.comics.PdfPages? = null

    /** The listening player (music); null where there is none yet (iOS). */
    fun listening(server: MediaServer, settings: dev.nitrate.android.storage.PlaybackSettingsStore, downloads: dev.nitrate.core.music.ListenDownloads): dev.nitrate.core.music.Listening? = null

    /** Reads comic pages small and grey, for finding panels (guided view); null where it can't. */
    fun pictureReader(): dev.nitrate.android.comics.PictureReader? = null

    /** Opens EPUB books in the platform's own book reader; null where the app shows its own. */
    fun epubReader(): dev.nitrate.android.comics.EpubReaderHost? = null
}

/** What a player contributes to a playback report. */
class PlayerReport(
    val item: MediaItem?,
    val plan: PlaybackPlan?,
    val videoDecoder: String?,
    val audioDecoder: String?,
    val events: List<String>,
    val error: String?,
    /** How smoothly the player's screen drew ([dev.nitrate.core.playback.FrameStats.lines]). */
    val uiFrames: List<String> = emptyList(),
)

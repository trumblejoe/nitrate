package dev.nitrate.ios

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.nitrate.android.AppContainer
import dev.nitrate.android.AppPlatform
import dev.nitrate.android.AppStores
import dev.nitrate.android.notifications.NewEpisodeNotifier
import dev.nitrate.android.playback.ThemeMusic
import dev.nitrate.android.storage.KeyValues
import dev.nitrate.android.update.AppUpdater
import dev.nitrate.android.update.NoUpdates
import dev.nitrate.core.MediaServer
import dev.nitrate.core.downloads.DownloadScheduler
import dev.nitrate.core.pages.DeviceKind
import io.ktor.client.engine.darwin.Darwin
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

/** iOS's side of [AppContainer]: files in the app's folders, URLSession, Apple's player. */
class IosPlatform(private val version: String) : AppPlatform {
    override val deviceKind: DeviceKind =
        if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) DeviceKind.TABLET else DeviceKind.PHONE
    override val identity = iosIdentity()
    override val appKind = "ios"
    override val filesDir: String = directory(NSDocumentDirectory)
    override val cacheDir: String = directory(NSCachesDirectory)
    override val downloadDir: String = "$filesDir/downloads"

    private fun store(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(produceFile = { "$filesDir/datastore/$name.preferences_pb".toPath() })

    override val stores = AppStores(
        display = store("display_settings"),
        playback = store("playback_settings"),
        session = store("session"),
        search = store("search_history"),
        plugins = store("plugins"),
        profiles = store("profiles"),
        backup = store("device_backup"),
    )

    override val libraryKeyValues: KeyValues = object : KeyValues {
        private val defaults = NSUserDefaults.standardUserDefaults
        override fun get(key: String) = defaults.stringForKey("libraries:$key")
        override fun put(key: String, value: String) = defaults.setObject(value, forKey = "libraries:$key")
    }

    override fun serverEngine() = Darwin.create()
    override fun downloadEngine() = Darwin.create()

    override fun downloadScheduler(): DownloadScheduler = IosApp.downloadScheduler

    override fun probeCapabilities() = appleCapabilities()
    override fun themeMusic(server: MediaServer): ThemeMusic = IosThemeMusic(server)

    override fun listening(server: MediaServer, settings: dev.nitrate.android.storage.PlaybackSettingsStore, downloads: dev.nitrate.core.music.ListenDownloads): dev.nitrate.core.music.Listening =
        IosListening(server, settings, downloads)

    /** Readium through the Swift app's bridge ([useBookReader]); null (Nitrate's own `EpubScreen`) until it ships one. */
    override fun epubReader(): dev.nitrate.android.comics.EpubReaderHost? = BookReaders.host()
    override fun newEpisodes(server: MediaServer): NewEpisodeNotifier = IosNewEpisodeNotifier(server, store("notifications"))
    override fun updater(): AppUpdater = NoUpdates(version)

    override fun pdfPages(): dev.nitrate.android.comics.PdfPages = IosPdfPages()

    override fun pictureReader(): dev.nitrate.android.comics.PictureReader = IosPictureReader()

    // Reconnecting on network changes (NWPathMonitor) comes later; the app re-checks when it opens.
    override fun watchNetwork(onChange: () -> Unit) = Unit

    override fun timestamp(): String = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd HH:mm:ss Z"
        locale = NSLocale("en_US_POSIX")
    }.stringFromDate(NSDate())

    private companion object {
        fun directory(kind: ULong): String =
            NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String
    }
}

/** The one container for the app run. */
object IosApp {
    /** An item to open once signed in (a tapped notification). */
    val openItem = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Runs the download queue while the app is open (and a little after). */
    val downloadScheduler = IosDownloadScheduler()

    val container: AppContainer by lazy {
        val version = platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"
        AppContainer(IosPlatform(version), clientName = "Nitrate", version = version)
    }
}

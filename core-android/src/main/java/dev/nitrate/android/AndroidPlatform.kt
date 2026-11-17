package dev.nitrate.android

import android.content.Context
import dev.nitrate.android.downloads.WorkManagerScheduler
import dev.nitrate.android.notifications.AndroidNewEpisodeNotifier
import dev.nitrate.android.playback.AndroidThemeMusic
import dev.nitrate.android.playback.DeviceProbe
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.android.storage.KeyValues
import dev.nitrate.android.storage.backupDataStore
import dev.nitrate.android.storage.displayDataStore
import dev.nitrate.android.storage.playbackDataStore
import dev.nitrate.android.storage.pluginDataStore
import dev.nitrate.android.storage.profilesDataStore
import dev.nitrate.android.storage.searchStore
import dev.nitrate.android.storage.sessionDataStore
import dev.nitrate.android.update.AndroidUpdater
import dev.nitrate.core.MediaServer
import dev.nitrate.core.pages.DeviceKind
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

/** Android's side of [AppContainer]: DataStore files, OkHttp, the decoder probe, WorkManager, notifications. */
class AndroidPlatform(context: Context, clientName: String, version: String) : AppPlatform {
    private val appContext = context.applicationContext

    override val deviceKind: DeviceKind = when {
        appContext.packageName.contains(".tv") -> DeviceKind.TV
        appContext.resources.configuration.smallestScreenWidthDp >= 600 -> DeviceKind.TABLET
        else -> DeviceKind.PHONE
    }
    override val identity = androidIdentity(appContext, clientName, version)
    override val appKind: String = if (appContext.packageName.contains(".tv")) "tv" else "mobile"
    override val stores = AppStores(
        display = appContext.displayDataStore,
        playback = appContext.playbackDataStore,
        session = appContext.sessionDataStore,
        search = appContext.searchStore,
        plugins = appContext.pluginDataStore,
        profiles = appContext.profilesDataStore,
        backup = appContext.backupDataStore,
    )
    override val filesDir: String = appContext.filesDir.absolutePath
    override val cacheDir: String = appContext.cacheDir.absolutePath

    // App-specific storage: removed with the app, no storage permission needed.
    override val downloadDir: String by lazy { File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads").absolutePath }

    override val libraryKeyValues: KeyValues by lazy {
        val prefs = appContext.getSharedPreferences("libraries", Context.MODE_PRIVATE)
        object : KeyValues {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        }
    }

    // Pages ask for all their rows at once; OkHttp would otherwise send 5 at a time per server.
    override fun serverEngine() = OkHttp.create {
        config { dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }) }
    }

    override fun downloadEngine() = OkHttp.create()
    override fun downloadScheduler() = WorkManagerScheduler(appContext)
    override fun probeCapabilities() = DeviceProbe.cached(appContext)
    override fun themeMusic(server: MediaServer) = AndroidThemeMusic(appContext, server)
    override fun newEpisodes(server: MediaServer) = AndroidNewEpisodeNotifier(appContext, server)
    override fun updater() = AndroidUpdater(appContext, appKind).also { AndroidUpdater.current = it }

    override fun watchNetwork(onChange: () -> Unit) {
        val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = onChange()
                override fun onLost(network: android.net.Network) = onChange()
            })
        }
    }

    override fun timestamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())
}

/** The container for an Android app ([clientName] and [version] show in the server's device list). */
fun AppContainer(
    context: Context,
    clientName: String,
    version: String,
    serverOverride: MediaServer? = null,
    downloadStoreOverride: dev.nitrate.core.downloads.DownloadStore? = null,
) = AppContainer(AndroidPlatform(context, clientName, version), clientName, version, serverOverride, downloadStoreOverride)

/** What [session] contributes to a playback report. */
fun PlaybackSession.report() = PlayerReport(item.value, plan.value, videoDecoder.value, audioDecoder.value, eventLog(), error.value)

/** A playback report with [session]'s item, server decision and player events. */
suspend fun AppContainer.playbackReport(session: PlaybackSession?): String = playbackReport(session?.report())

/** Sends a playback report with [session]'s details to the server's logs. */
suspend fun AppContainer.sendPlaybackReport(session: PlaybackSession?): Result<String> = sendPlaybackReport(session?.report())

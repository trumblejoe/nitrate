package dev.nitrate.android

import kotlinx.coroutines.launch

import android.content.Context
import dev.nitrate.android.downloads.WorkManagerScheduler
import dev.nitrate.android.playback.DeviceProbe
import dev.nitrate.android.remote.RemoteControlHub
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.android.storage.PlaybackSettingsStore
import dev.nitrate.android.storage.PluginStore
import dev.nitrate.android.storage.SearchHistoryStore
import dev.nitrate.android.storage.SessionStore
import dev.nitrate.android.storage.ProfileStore
import dev.nitrate.core.model.SessionState
import kotlinx.coroutines.flow.first
import dev.nitrate.core.events.ServerEvent
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import dev.nitrate.android.sync.SettingsSync
import dev.nitrate.android.writing.WritingStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.admin.AccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.nitrate.core.downloads.DownloadManager
import dev.nitrate.core.downloads.DownloadStore
import dev.nitrate.core.downloads.FileDownloadStore
import dev.nitrate.core.episodes.PreviousEpisodePlugin
import dev.nitrate.core.jellyfin.JellyfinClient
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.plugins.PluginRegistry
import dev.nitrate.core.writing.WritingPlugin
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.io.files.Path
import java.io.File

/**
 * Manual dependency container shared by the TV and phone/tablet apps. Each app passes its own
 * [clientName] and [version], which the server shows in its device list.
 */
class AppContainer(
    context: Context,
    clientName: String,
    version: String,
    /** Replaces the Jellyfin server, for screenshot tests and previews. */
    serverOverride: MediaServer? = null,
    /** Replaces the downloads database, for screenshot tests. */
    downloadStoreOverride: DownloadStore? = null,
) {
    private val appContext = context.applicationContext

    val displaySettings = DisplaySettingsStore(appContext)

    val playbackSettings = PlaybackSettingsStore(appContext)

    /** Probed once: decoder lists don't change while the app runs. */
    val capabilities: DeviceCapabilities by lazy { DeviceProbe.probe(appContext) }

    val server: MediaServer = serverOverride ?: JellyfinClient(
        engine = OkHttp.create(),
        identity = androidIdentity(appContext, clientName, version),
        sessionStorage = SessionStore(appContext),
        capabilities = { capabilities },
        playbackSettings = playbackSettings,
    )

    val settingsSync = SettingsSync(server, displaySettings)

    val writing by lazy { WritingStore(appContext, server) }

    /** App-specific storage: removed with the app, no storage permission needed. */
    private val downloadDir by lazy { File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads") }

    /** Offline downloads (phone and tablet app). */
    val downloads by lazy {
        DownloadManager(
            server = server,
            store = downloadStoreOverride ?: FileDownloadStore(Path(File(appContext.filesDir, "downloads.json").path)),
            engine = OkHttp.create(),
            root = Path(downloadDir.path),
            scheduler = WorkManagerScheduler(appContext),
        )
    }

    val searchHistory by lazy { SearchHistoryStore(appContext) }

    /** The server's live connection: remote control and live updates. */
    val remote by lazy { RemoteControlHub(server, syncPlay, onItemsAdded = { newEpisodes.checkSoon() }) }

    /** Library or watch-state changes pushed by the server, for pages to reload. */
    val serverChanges: kotlinx.coroutines.flow.Flow<Unit>
        get() = remote.events.filter { it is ServerEvent.LibraryChanged || it is ServerEvent.UserDataChanged }.map { }

    /** Watch together: this device's SyncPlay group, if any. */
    val syncPlay by lazy {
        dev.nitrate.core.syncplay.SyncPlayController(server, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate))
    }

    /** Per-show audio and subtitle languages, for the signed-in user. */
    val trackMemory by lazy { displaySettings.trackMemory { server.userId } }

    private val accountState = MutableStateFlow<AccountInfo?>(null)

    /** The signed-in account (null until known, or offline); admin tools show only for admins. */
    val account: StateFlow<AccountInfo?> = accountState

    /** Re-reads the account, after sign-in or when the server comes back. */
    suspend fun refreshAccount() {
        accountState.value = runCatching { server.account() }.getOrNull()
    }

    /** New-episode notifications (phone). */
    val newEpisodes by lazy { dev.nitrate.android.notifications.NewEpisodeNotifier(appContext, server) }

    /** Libraries as last seen, for menus that should be whole at once. */
    val libraryCache by lazy { dev.nitrate.android.storage.LibraryCache(appContext) }

    /** Updates from the project's releases, for whichever app this is (phone or TV). */
    val updater by lazy {
        dev.nitrate.android.update.AppUpdater(appContext, if (appContext.packageName.contains(".tv")) "tv" else "mobile")
            .also { dev.nitrate.android.update.AppUpdater.current = it }
    }

    /** PIN locks and "who's watching" for the accounts saved here. */
    val profiles by lazy { ProfileStore(appContext) }

    /** After signing in or switching account: settings, account details and the live connection. */
    suspend fun onSignedIn() {
        settingsSync.pull()
        remote.start()
        refreshAccount()
        watchNetwork()
    }

    private val networkScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var networkWatched = false
    private var reconnecting: kotlinx.coroutines.Job? = null

    /**
     * When the network changes (out of the house, onto mobile data, back home), moves to whichever
     * of the server's addresses answers and restarts the live connection if the address changed.
     */
    private fun watchNetwork() {
        if (networkWatched) return
        networkWatched = true
        val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { reconnectSoon() }
                override fun onLost(network: android.net.Network) { reconnectSoon() }
            })
        }
    }

    private fun reconnectSoon() {
        reconnecting?.cancel()
        reconnecting = networkScope.launch {
            // Networks settle in a moment; don't chase every flap.
            kotlinx.coroutines.delay(1_500)
            reconnect()
        }
    }

    /** Re-picks the server address now (also after the addresses change). */
    suspend fun reconnect(): SessionState {
        if (server.currentAccountKey == null) return SessionState.NONE
        val before = server.serverUrl
        val state = server.reconnect()
        if (state == SessionState.VALID && server.serverUrl != before) {
            remote.stop()
            remote.start()
        }
        return state
    }

    /** Saves the current server's home and away addresses and moves to whichever answers. */
    suspend fun saveServerAddresses(local: String?, remote: String?): SessionState {
        val before = server.serverUrl
        val state = server.saveServerAddresses(local, remote)
        if (state == SessionState.VALID && server.serverUrl != before) {
            this.remote.stop()
            this.remote.start()
        }
        return state
    }

    /** Switches to a saved account; on VALID everything follows the new user. */
    suspend fun switchAccount(key: String): SessionState {
        leaveSession()
        val state = server.switchAccount(key)
        if (state == SessionState.VALID) onSignedIn()
        return state
    }

    /** Signs out of the current account and forgets it here. */
    suspend fun signOut() {
        leaveSession()
        server.currentAccountKey?.let { profiles.forget(it) }
        server.logout()
    }

    /** Forgets a saved account that isn't current. */
    suspend fun forgetAccount(key: String) {
        profiles.forget(key)
        server.forgetAccount(key)
    }

    /** Where to go at startup or after signing out: true when the "who's watching" picker should show. */
    suspend fun shouldPickProfile(state: SessionState): Boolean {
        val saved = server.savedAccounts()
        return when (state) {
            SessionState.NONE -> saved.isNotEmpty()
            SessionState.VALID -> {
                val current = server.currentAccountKey
                // A locked profile can't be reopened without its PIN.
                (current != null && profiles.hasPin(current)) || (saved.size > 1 && profiles.askAtStart.first())
            }
            else -> false
        }
    }

    private suspend fun leaveSession() {
        if (syncPlay.inGroup) syncPlay.leave()
        remote.stop()
        accountState.value = null
    }

    /** Free space where downloads are saved. */
    fun downloadSpaceFree(): Long = downloadDir.also { it.mkdirs() }.usableSpace

    /** Implemented by each app's Application so background work can find the container. */
    interface Owner {
        val container: AppContainer
    }

    val plugins = PluginRegistry(
        all = listOf(PreviousEpisodePlugin, WritingPlugin),
        host = PluginHost(server, PluginStore(appContext)),
    )
}

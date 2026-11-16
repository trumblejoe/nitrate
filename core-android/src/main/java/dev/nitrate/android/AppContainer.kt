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
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
    private val clientName: String,
    private val version: String,
    /** Replaces the Jellyfin server, for screenshot tests and previews. */
    serverOverride: MediaServer? = null,
    /** Replaces the downloads database, for screenshot tests. */
    downloadStoreOverride: DownloadStore? = null,
) {
    private val appContext = context.applicationContext

    /** Which built-in display profile this device starts on. */
    val deviceKind: dev.nitrate.core.pages.DeviceKind = when {
        appContext.packageName.contains(".tv") -> dev.nitrate.core.pages.DeviceKind.TV
        appContext.resources.configuration.smallestScreenWidthDp >= 600 -> dev.nitrate.core.pages.DeviceKind.TABLET
        else -> dev.nitrate.core.pages.DeviceKind.PHONE
    }

    val displaySettings = DisplaySettingsStore(appContext, deviceKind, androidIdentity(appContext, clientName, version).deviceName)

    val playbackSettings = PlaybackSettingsStore(appContext)

    /** Probed once: decoder lists don't change while the app runs. */
    val capabilities: DeviceCapabilities by lazy { DeviceProbe.cached(appContext) }

    /** Each page's rows as last loaded, so pages open with them while fresh ones load. */
    val dataCache: dev.nitrate.android.storage.DataCache by lazy { dev.nitrate.android.storage.DataCache(appContext) }

    val server: MediaServer = serverOverride ?: JellyfinClient(
        // Pages ask for all their rows at once; OkHttp would otherwise send 5 at a time per server.
        engine = OkHttp.create {
            config {
                dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            }
        },
        identity = androidIdentity(appContext, clientName, version),
        sessionStorage = SessionStore(appContext),
        capabilities = { capabilities },
        playbackSettings = playbackSettings,
        textCache = dataCache.text,
    )

    val settingsSync = SettingsSync(server, displaySettings)

    val writing by lazy {
        WritingStore(appContext, server).also { store ->
            store.account = server.currentAccountKey
            store.author = { server.userId.orEmpty() to (account.value?.name ?: "Me") }
        }
    }

    /** Shared screenplays (Companion plugin): links scripts here to shared ones, per account. */
    val sharedWriting by lazy { dev.nitrate.android.writing.SharedWriting(writing, server, companion) { account.value?.name } }

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
            trackMemory = trackMemory,
            describedAudio = ::prefersDescribedAudio,
        )
    }

    /** The signed-in user's audio-description preference (a display setting, so it follows them). */
    suspend fun prefersDescribedAudio(): Boolean {
        val user = server.userId ?: return false
        return runCatching { displaySettings.prefs(user).first().describedAudio }.getOrDefault(false)
    }

    val searchHistory by lazy { SearchHistoryStore(appContext) }

    /** The server's live connection: remote control and live updates. */
    /** "Still watching?": episodes rolling on with nobody pressing anything (fed by the players). */
    val stillWatching = dev.nitrate.core.playback.StillWatching(System::currentTimeMillis)

    /**
     * Puts things back as they were at the last press: that episode's resume point (a little
     * earlier), and the episodes that played on by themselves since unwatched again.
     */
    suspend fun rewindStillWatching(rewind: dev.nitrate.core.playback.StillWatching.Rewind) {
        rewind.unwatch.forEach { runCatching { server.setPlayed(it, false) } }
        runCatching { server.saveUserData(rewind.back.itemId, rewind.back.positionMs, rewind.back.wasPlayed, System.currentTimeMillis()) }
        stillWatching.clear()
    }

    /** [rewindStillWatching], then [then] with the title to resume (null: nothing to go back to), outliving the player. */
    fun stillWatchingRewind(rewind: dev.nitrate.core.playback.StillWatching.Rewind?, then: (dev.nitrate.core.model.MediaItem?) -> Unit) {
        networkScope.launch {
            rewind?.let { rewindStillWatching(it) }
            then(rewind?.let { runCatching { server.getItem(it.back.itemId) }.getOrNull() })
        }
    }

    val remote by lazy { RemoteControlHub(server, syncPlay, onItemsAdded = { newEpisodes.checkSoon() }) }

    /** Library or watch-state changes pushed by the server, for pages to reload. */
    val serverChanges: kotlinx.coroutines.flow.Flow<Unit>
        get() = remote.events.filter { it is ServerEvent.LibraryChanged || it is ServerEvent.UserDataChanged }.map { }

    /** Watch together: this device's SyncPlay group, if any. */
    val syncPlay by lazy {
        dev.nitrate.core.syncplay.SyncPlayController(server, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate))
    }

    private val companionState = MutableStateFlow<dev.nitrate.core.companion.CompanionInfo?>(null)

    /** The Nitrate Companion server plugin, when the server has it (null until known or without it). */
    val companion: StateFlow<dev.nitrate.core.companion.CompanionInfo?> = companionState

    /** A group whose chat this device follows without watching in it (a phone beside the TV). */
    private val chatOnlyGroup = MutableStateFlow<String?>(null)

    /**
     * Watch-together chat (needs the Companion plugin): follows this device's group, or the group
     * picked with [chatAlongside] when this device isn't watching itself.
     */
    val chat by lazy {
        dev.nitrate.core.companion.GroupChat(server, networkScope) { server.userId }.also { chat ->
            networkScope.launch {
                kotlinx.coroutines.flow.combine(syncPlay.group, companionState, chatOnlyGroup) { group, companion, alongside ->
                    if (companion?.chat == true) group?.id ?: alongside else null
                }.collect { chat.follow(it) }
            }
        }
    }

    /** Chats in [groupId] without playing (null stops); this device's own group comes first. */
    fun chatAlongside(groupId: String?) {
        chat
        chatOnlyGroup.value = groupId
    }

    /** Tests: asks the (fake) server for its Companion plugin now, as signing in does. */
    @androidx.annotation.VisibleForTesting
    suspend fun refreshCompanionForTests() = refreshCompanion()

    private var scriptSyncJob: kotlinx.coroutines.Job? = null

    /**
     * Keeps this profile's scripts in step with its server (Companion plugin): after sign-in, a
     * few seconds after scripts change here, and every few minutes for ones made elsewhere.
     */
    private fun syncScripts() {
        scriptSyncJob?.cancel()
        scriptSyncJob = networkScope.launch {
            launch {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                writing.docs.map { docs -> docs.map { it.id to it.sharedId }.toSet() }.distinctUntilChanged().drop(1).debounce(3_000).collect { runCatching { sharedWriting.autoSync() } }
            }
            while (true) {
                runCatching { sharedWriting.autoSync() }
                kotlinx.coroutines.delay(5 * 60_000L)
            }
        }
    }

    private suspend fun refreshCompanion() {
        companionState.value = runCatching { server.companion() }.getOrNull()
        if (companionState.value?.features?.contains("scripts") == true) syncScripts()
        if (companionState.value?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) == true) {
            refreshPartners()
            followTogether()
        }
        chat
    }

    /** The recap the "Last time" page shows (set just before it opens). */
    var pendingRecap: dev.nitrate.core.recap.Recap? = null

    /**
     * The "Last time" recap due before playing [item] (as a show's next episode, or the next item of
     * [playlist] when it's marked in story order), or null. Gives up after a moment so playing never waits long.
     */
    suspend fun recapBefore(item: dev.nitrate.core.model.MediaItem, playlist: dev.nitrate.core.model.MediaItem? = null): dev.nitrate.core.recap.Recap? {
        val user = server.userId ?: return null
        val prefs = displaySettings.prefs(user).first()
        if ((if (playlist != null) prefs.playlistRecapAfterDays else prefs.recapAfterDays) <= 0) return null
        return kotlinx.coroutines.withTimeoutOrNull(2_500) {
            dev.nitrate.core.recap.Recaps.before(server, item, prefs, System.currentTimeMillis(), playlist)
        }.also { pendingRecap = it }
    }

    /** Marks a playlist "in story order" (it then gets recaps), or not. Shared on every device. */
    suspend fun setRecapPlaylist(playlistId: String, on: Boolean) {
        val user = server.userId ?: return
        val prefs = displaySettings.prefs(user).first()
        displaySettings.savePrefs(user, prefs.copy(recapPlaylists = if (on) prefs.recapPlaylists + playlistId else prefs.recapPlaylists - playlistId))
    }

    /** Watching shows with partners (see [dev.nitrate.core.partners.WatchPartnersPlugin]). */
    val watchPartners = dev.nitrate.core.partners.WatchPartnersPlugin(dev.nitrate.core.partners.TogetherSessions(System::currentTimeMillis), System::currentTimeMillis)

    private val partnersState = MutableStateFlow<dev.nitrate.core.partners.PartnersInfo?>(null)

    /** This user's watch partners and requests (null until known, or without the Companion plugin). */
    val partners: StateFlow<dev.nitrate.core.partners.PartnersInfo?> = partnersState

    /** Requests to answer, shown when the app opens until answered or put off ("Later" hides them until next time). */
    val partnerRequestsLater = MutableStateFlow(emptySet<String>())

    suspend fun refreshPartners() {
        runCatching { server.partners() }.onSuccess { partnersState.value = it }
    }

    /** After leaving a player: counts what was just watched with partners (in case no live update came). */
    fun settleTogether() {
        if (watchPartners.sessions.all().isEmpty()) return
        networkScope.launch {
            kotlinx.coroutines.delay(2_000)
            runCatching { watchPartners.settle(server) }
        }
    }

    /** Stops watching [seriesId] with partners. */
    fun stopTogether(seriesId: String) = watchPartners.sessions.stop(seriesId)

    private var followingTogether = false

    /** Once the user's watched state changes, counts episodes watched with partners for them too. */
    private fun followTogether() {
        if (followingTogether) return
        followingTogether = true
        networkScope.launch {
            remote.events.filter { it is ServerEvent.UserDataChanged }.collect {
                if (watchPartners.sessions.all().isNotEmpty()) {
                    // Let the server finish saving the stop first.
                    kotlinx.coroutines.delay(1_500)
                    runCatching { watchPartners.settle(server) }
                }
            }
        }
    }

    /** Theme music behind details pages (each app's details screens ask for it; players stop it). */
    val themeMusic by lazy { dev.nitrate.android.playback.ThemeMusic(appContext, server) }

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

    /**
     * Builds a playback report (this device, what Nitrate allows, and [session]'s item, server
     * decision and player events if given) as text.
     */
    suspend fun playbackReport(session: dev.nitrate.android.playback.PlaybackSession? = null): String =
        dev.nitrate.core.playback.PlaybackReport(
            app = clientName,
            version = version,
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()),
            caps = capabilities,
            settings = playbackSettings.current(),
            item = session?.item?.value,
            plan = session?.plan?.value,
            videoDecoder = session?.videoDecoder?.value,
            audioDecoder = session?.audioDecoder?.value,
            events = session?.eventLog().orEmpty(),
            error = session?.error?.value,
        ).render()

    /**
     * Sends a playback report to the signed-in Jellyfin server as a client log, and returns the
     * file name it's saved as there (Dashboard → Logs).
     */
    suspend fun sendPlaybackReport(session: dev.nitrate.android.playback.PlaybackSession? = null): Result<String> =
        runCatching { server.sendClientLog(playbackReport(session)) }

    /**
     * The audio and subtitles [item] will start with (the user's pick for its show or itself, the
     * server's picks, the file's flags), with its file, for a details page or download dialog.
     */
    suspend fun trackSetup(item: MediaItem): dev.nitrate.core.playback.TrackSetup {
        val source = server.mediaSource(item.id)
        val choice = runCatching { trackMemory.get(dev.nitrate.core.playback.TrackSelection.memoryKey(item)) }.getOrNull()
        val (audio, subtitle) = dev.nitrate.core.playback.TrackSelection.initial(source, choice, prefersDescribedAudio())
        return dev.nitrate.core.playback.TrackSetup(source, audio, subtitle)
    }

    /** The user picked a track for [item] before playing: remembered for its show (or itself), as in the player. */
    suspend fun pickTrack(item: MediaItem, setup: dev.nitrate.core.playback.TrackSetup, kind: dev.nitrate.core.model.StreamKind, index: Int): dev.nitrate.core.playback.TrackSetup {
        val key = dev.nitrate.core.playback.TrackSelection.memoryKey(item)
        trackMemory.set(key, dev.nitrate.core.playback.TrackMemory.remember(kind, setup.source.streams, index, trackMemory.get(key)))
        return if (kind == dev.nitrate.core.model.StreamKind.AUDIO) setup.copy(audioIndex = index) else setup.copy(subtitleIndex = index)
    }

    /** How [itemId] would play on this device and why, before playing it (details page), with the tracks it would start with. */
    suspend fun checkPlayback(itemId: String, isAudio: Boolean = false, item: MediaItem? = null): dev.nitrate.core.playback.PlaybackDiagnosis {
        val tracks = item?.let { runCatching { trackSetup(it) }.getOrNull() }
        val plan = server.resolvePlayback(
            itemId = itemId, isAudio = isAudio, startMs = 0, forceTranscode = false,
            audioIndex = tracks?.audioIndex, subtitleIndex = tracks?.subtitleIndex, mediaSourceId = dev.nitrate.android.playback.VersionChoices[itemId],
        )
        return dev.nitrate.core.playback.PlaybackDoctor.diagnose(plan, capabilities, playbackSettings.current()).copy(plan = plan)
    }

    /** Applies a fix found by [checkPlayback] (settings and versions; tracks are chosen in the player). */
    suspend fun applyPlaybackFix(itemId: String, fix: dev.nitrate.core.playback.PlaybackFix) {
        when (fix) {
            is dev.nitrate.core.playback.PlaybackFix.Change -> playbackSettings.update(fix.change::apply)
            is dev.nitrate.core.playback.PlaybackFix.UseVersion -> dev.nitrate.android.playback.VersionChoices.set(itemId, fix.versionId)
            else -> Unit
        }
    }


    /**
     * Times typical requests from this device to the server (see [MediaServer.speedCheck]),
     * reporting each step as it finishes, and returns the whole report.
     */
    /** The last library health check, kept while the app runs: (library id or null for all, findings). */
    var lastHealth: Pair<String?, Map<dev.nitrate.core.diagnostics.HealthCheck, List<dev.nitrate.core.diagnostics.HealthFinding>>>? = null
        private set

    /**
     * Checks a library (null: all) for missing artwork, descriptions and matches, unmerged
     * copies, what this device always has to convert, and missing episodes.
     */
    suspend fun checkLibraryHealth(libraryId: String?): Map<dev.nitrate.core.diagnostics.HealthCheck, List<dev.nitrate.core.diagnostics.HealthFinding>> {
        val found = dev.nitrate.core.diagnostics.LibraryHealth.findings(server.healthScan(libraryId), capabilities, playbackSettings.current())
        lastHealth = libraryId to found
        return found
    }

    /** Carries out a health fix that changes the server (admins); the others open a screen. */
    suspend fun applyHealthFix(finding: dev.nitrate.core.diagnostics.HealthFinding, fix: dev.nitrate.core.diagnostics.HealthFix) = when (fix) {
        dev.nitrate.core.diagnostics.HealthFix.FIND_IMAGES -> server.refreshItem(finding.item.id, dev.nitrate.core.admin.RefreshMode.MISSING, replaceImages = false, regenerateTrickplay = false)
        dev.nitrate.core.diagnostics.HealthFix.FIND_METADATA -> server.refreshItem(finding.item.id, dev.nitrate.core.admin.RefreshMode.MISSING, replaceImages = false, regenerateTrickplay = false)
        dev.nitrate.core.diagnostics.HealthFix.MERGE -> server.mergeVersions(listOf(finding.item.id) + finding.related.map { it.id })
        else -> Unit
    }

    suspend fun speedCheck(onStep: (dev.nitrate.core.diagnostics.SpeedStep) -> Unit): dev.nitrate.core.diagnostics.SpeedReport {
        val steps = server.speedCheck(onStep)
        return dev.nitrate.core.diagnostics.SpeedReport(
            app = "$clientName $version",
            device = "${capabilities.model} (${androidIdentity(appContext, clientName, version).deviceName})",
            server = server.serverUrl.orEmpty(),
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()),
            steps = steps,
        )
    }

    /** Saves a speed check in the server's logs (Dashboard → Logs); returns the file name. */
    suspend fun sendSpeedReport(report: dev.nitrate.core.diagnostics.SpeedReport): Result<String> =
        runCatching { server.sendClientLog(report.render()) }

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
        backups.onSignedIn()
        remote.start()
        // Not waited for: Home shouldn't wait on the account details (admin tools appear when they arrive).
        networkScope.launch { refreshAccount() }
        networkScope.launch { refreshCompanion() }
        writing.account = server.currentAccountKey
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

    /** Saves a server's home, VPN and away addresses; if it's the current one, moves to whichever answers. */
    suspend fun saveServerAddresses(serverUrl: String, local: String?, vpn: String?, remote: String?): SessionState {
        val before = server.serverUrl
        val state = server.saveServerAddresses(serverUrl, local, vpn, remote)
        if (state == SessionState.VALID && server.serverUrl != before) {
            this.remote.stop()
            this.remote.start()
        }
        return state
    }

    /** Switches to a saved account; on VALID everything follows the new user. */
    suspend fun switchAccount(key: String): SessionState {
        val previous = server.currentAccountKey
        leaveSession()
        val state = server.switchAccount(key)
        if (state == SessionState.VALID) {
            onSignedIn()
        } else if (previous != null && previous != key) {
            // Couldn't open it (server away, or it needs signing in again): stay on the account
            // that was open, so the screens behind don't act on the new one.
            if (server.switchAccount(previous) == SessionState.VALID) onSignedIn()
        }
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
        chatOnlyGroup.value = null
        companionState.value = null
        writing.account = null
        scriptSyncJob?.cancel()
        themeMusic.stop()
    }

    /** Free space where downloads are saved. */
    fun downloadSpaceFree(): Long = downloadDir.also { it.mkdirs() }.usableSpace

    /** Implemented by each app's Application so background work can find the container. */
    interface Owner {
        val container: AppContainer
    }

    private val pluginStore = PluginStore(appContext)

    val plugins = PluginRegistry(
        all = listOf(PreviousEpisodePlugin, watchPartners, WritingPlugin, dev.nitrate.core.channels.ChannelsPlugin),
        host = PluginHost(server, pluginStore),
    )

    /** This device's playback and plugin settings, backed up to the user's server and restorable on another device. */
    val backups by lazy {
        dev.nitrate.android.backup.DeviceBackupManager(
            context = appContext,
            server = server,
            playback = playbackSettings,
            plugins = pluginStore,
            display = displaySettings,
            pluginList = { plugins.all },
            deviceName = androidIdentity(appContext, clientName, version).deviceName,
            model = { capabilities.model },
            app = if (appContext.packageName.contains(".tv")) "tv" else "mobile",
            version = version,
        )
    }
}

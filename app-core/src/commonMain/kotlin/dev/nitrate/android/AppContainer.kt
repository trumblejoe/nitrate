package dev.nitrate.android

import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.launch

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
import kotlinx.io.files.Path

/**
 * Manual dependency container shared by the TV and phone/tablet apps. Each app passes its own
 * [clientName] and [version], which the server shows in its device list.
 */
class AppContainer(
    /** What differs per platform: storage, the network engine, the decoder probe, notifications… */
    val platform: AppPlatform,
    private val clientName: String,
    private val version: String,
    /** Replaces the Jellyfin server, for screenshot tests and previews. */
    serverOverride: MediaServer? = null,
    /** Replaces the downloads database, for screenshot tests. */
    downloadStoreOverride: DownloadStore? = null,
) {
    /** Which built-in display profile this device starts on. */
    val deviceKind: dev.nitrate.core.pages.DeviceKind = platform.deviceKind

    val displaySettings = DisplaySettingsStore(platform.stores.display, deviceKind, platform.identity.deviceName)

    val playbackSettings = PlaybackSettingsStore(platform.stores.playback)

    /**
     * For saves started from a screen that may close at once (a picker closing on the press that
     * chose): they finish even when the screen's own scope is cancelled.
     */
    val saveScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /** Audiobook bookmarks, synced with the user's display settings (the same on every device). */
    val bookmarks: dev.nitrate.core.music.BookmarkStore by lazy { displaySettings.bookmarks { server.userId } }

    /** The listening player (music): lives as long as the app, so browsing never stops it. */
    val listening: dev.nitrate.core.music.Listening? by lazy {
        platform.listening(server, playbackSettings, object : dev.nitrate.core.music.ListenDownloads {
            override fun file(itemId: String) = downloads.find(itemId)?.let(downloads::videoPath)
            override suspend fun played(itemId: String, positionMs: Long, durationMs: Long, ended: Boolean) =
                downloads.recordPlayback(itemId, positionMs, durationMs, ended)
        })
    }

    /** Probed once: decoder lists don't change while the app runs. */
    val capabilities: DeviceCapabilities by lazy { platform.probeCapabilities() }

    /** Each page's rows as last loaded, so pages open with them while fresh ones load. */
    val dataCache: dev.nitrate.android.storage.DataCache by lazy { dev.nitrate.android.storage.DataCache(platform.cacheDir) }

    /** Video playlists in their own order: where each was left, what plays next, Home's "Continue your playlists". */
    val playlists: dev.nitrate.android.playlists.PlaylistPlaying by lazy { dev.nitrate.android.playlists.PlaylistPlaying(server, dataCache, ::nowMs) }

    val server: MediaServer = serverOverride ?: JellyfinClient(
        engine = platform.serverEngine(),
        identity = platform.identity,
        sessionStorage = SessionStore(platform.stores.session),
        capabilities = { capabilities },
        playbackSettings = playbackSettings,
        textCache = dataCache.text,
    )

    val settingsSync = SettingsSync(server, displaySettings)

    val writing by lazy {
        WritingStore(platform.filesDir, server).also { store ->
            store.account = server.currentAccountKey
            store.author = { server.userId.orEmpty() to (account.value?.name ?: "Me") }
        }
    }

    /** Shared screenplays (Companion plugin): links scripts here to shared ones, per account. */
    val sharedWriting by lazy { dev.nitrate.android.writing.SharedWriting(writing, server, companion) { account.value?.name } }

    /** Where downloads are saved (removed with the app). */
    private val downloadDir by lazy { dev.nitrate.android.files.File(platform.downloadDir) }

    /** Offline downloads (phone and tablet app). */
    val downloads by lazy {
        DownloadManager(
            server = server,
            store = downloadStoreOverride ?: FileDownloadStore(Path(platform.filesDir, "downloads.json")),
            engine = platform.downloadEngine(),
            root = Path(downloadDir.absolutePath),
            scheduler = platform.downloadScheduler(),
            trackMemory = trackMemory,
            describedAudio = ::prefersDescribedAudio,
        )
    }

    /** The signed-in user's audio-description preference (a display setting, so it follows them). */
    suspend fun prefersDescribedAudio(): Boolean {
        val user = server.userId ?: return false
        return runCatching { displaySettings.prefs(user).first().describedAudio }.getOrDefault(false)
    }

    /** Recent searches, per account: follows the account signed in (null while switching or signed out). */
    val searchHistory by lazy { SearchHistoryStore(platform.stores.search, account.map { if (it == null) null else server.currentAccountKey }) }

    /** The server's live connection: remote control and live updates. */
    /** "Still watching?": episodes rolling on with nobody pressing anything (fed by the players). */
    val stillWatching = dev.nitrate.core.playback.StillWatching(::nowMs)

    /**
     * Puts things back as they were at the last press: that episode's resume point (a little
     * earlier), and the episodes that played on by themselves since unwatched again.
     */
    suspend fun rewindStillWatching(rewind: dev.nitrate.core.playback.StillWatching.Rewind) {
        rewind.unwatch.forEach { runCatching { server.setPlayed(it, false) } }
        runCatching { server.saveUserData(rewind.back.itemId, rewind.back.positionMs, rewind.back.wasPlayed, nowMs()) }
        stillWatching.clear()
    }

    /** [rewindStillWatching], then [then] with the title to resume (null: nothing to go back to), outliving the player. */
    fun stillWatchingRewind(rewind: dev.nitrate.core.playback.StillWatching.Rewind?, then: (dev.nitrate.core.model.MediaItem?) -> Unit) {
        networkScope.launch {
            rewind?.let { rewindStillWatching(it) }
            then(rewind?.let { runCatching { server.getItem(it.back.itemId) }.getOrNull() })
        }
    }

    val remote by lazy {
        // A TV can be steered round its menus from a phone (arrows, OK, Back, Home).
        if (deviceKind == dev.nitrate.core.pages.DeviceKind.TV) server.acceptCommands(
            dev.nitrate.core.admin.GeneralCommand.NAVIGATION.map { it.wire } + dev.nitrate.core.admin.GeneralCommand.CHOOSING,
        )
        RemoteControlHub(server, syncPlay, onItemsAdded = { newEpisodes.checkSoon() })
    }

    /**
     * Library or watch-state changes, for pages to reload. A library scan sends many changes in a
     * row, so those wait until it settles; watch state (the server's push, or its own answer just
     * after a playback stopped or Watched was pressed here) goes through at once.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val serverChanges: kotlinx.coroutines.flow.Flow<Unit>
        get() = kotlinx.coroutines.flow.merge(
            remote.events.filter { it is ServerEvent.LibraryChanged }.map { }.debounce(LIBRARY_SETTLE_MS),
            server.watchChanges().map { },
            localChanges,
        )

    private val localChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Pages reload as for a server push: after something done here changes what they show (an invite answered...). */
    fun pagesChanged() { localChanges.tryEmit(Unit) }

    /**
     * SyncPlay, kept only for compatibility: the server's own live groups (other clients, older
     * Nitrate apps); Nitrate's own is a Group Watch Session ([groupSessions]).
     */
    val syncPlay by lazy {
        dev.nitrate.core.syncplay.SyncPlayController(server, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate))
    }

    private val companionState = MutableStateFlow<dev.nitrate.core.companion.CompanionInfo?>(null)

    /** The Nitrate Companion server plugin, when the server has it (null until known or without it). */
    val companion: StateFlow<dev.nitrate.core.companion.CompanionInfo?> = companionState

    /** Group Watch Sessions (Companion feature `sessions`): this device's, the join prompt, the players' notices. */
    val groupSessions by lazy { dev.nitrate.android.partners.GroupSessions(server, networkScope, ::nowMs) }

    /** Asks to watch together: the group asked to start a Group Watch Session, someone else hosting it. */
    val groupAsks by lazy { dev.nitrate.android.partners.GroupAsks(server, networkScope) }

    /** The server takes asks to watch together (Companion feature `asks`). */
    fun asksAvailable(): Boolean = companion.value?.features?.contains(dev.nitrate.core.partners.WatchAsk.FEATURE) == true

    /**
     * Starts the Group Watch Session [ask] asked for, hosted here (the user took it): its part and
     * group, as "Start a Group Watch Session" does. The part to play, or an error.
     */
    suspend fun startAsked(ask: dev.nitrate.core.partners.WatchAsk): Pair<dev.nitrate.core.model.MediaItem?, String?> {
        val item = runCatching { server.getItem(ask.itemId) }.getOrNull() ?: return null to "That title isn't on the server"
        val group = partners.value?.groups?.firstOrNull { it.id == ask.groupId } ?: run { refreshPartners(); partners.value?.groups?.firstOrNull { it.id == ask.groupId } }
        val error = startWatchingNow(item, group)
        return (if (error == null) item.copy(watchGroupId = ask.groupId) else null) to error
    }

    /** Whether the server has Group Watch Sessions ("Start a Group Watch Session"; older plugins: hidden). */
    fun sessionsAvailable(): Boolean = companion.value?.features?.contains(dev.nitrate.core.partners.GroupWatchSession.FEATURE) == true

    /** A group whose chat this device follows without watching in it (a phone beside the TV). */
    private val chatOnlyGroup = MutableStateFlow<String?>(null)

    /**
     * Watch-together chat (needs the Companion plugin): follows this device's Group Watch Session,
     * or the session picked with [chatAlongside] when this device isn't watching itself.
     */
    val chat by lazy {
        dev.nitrate.core.companion.GroupChat(server, networkScope) { server.userId }.also { chat ->
            networkScope.launch {
                kotlinx.coroutines.flow.combine(groupSessions.current, companionState, chatOnlyGroup) { session, companion, alongside ->
                    if (companion?.chat == true) session?.id ?: alongside else null
                }.collect { chat.follow(it) }
            }
        }
    }

    /**
     * Group chat for one playback (Companion `notes`, or live in this device's Group Watch Session): the
     * title's notes pinned along it, popping up as they're reached. The player calls `start` and
     * `at`, and `close` when it goes. When the title turns out to be shared with a watch group (its
     * notes, loaded anyway, list the groups), the groups are reloaded once for this playback, so new
     * members and renamed accounts are known; unshared titles cost nothing more. With chat histories
     * (Companion `chats`) it starts on a new chat and plays an earlier one only when picked.
     */
    fun timelineChat(scope: kotlinx.coroutines.CoroutineScope, live: Boolean): dev.nitrate.core.companion.TimelineChat =
        dev.nitrate.core.companion.TimelineChat(
            server, scope, { server.userId }, chat.takeIf { live },
            liveNotes = { companionState.value?.features?.contains("livenotes") != false },
            chatsOn = { companionState.value?.features?.contains("chats") == true },
            favouritesOn = { companionState.value?.features?.contains("chatfavourites") == true },
            onShared = { networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Watch partners")) { refreshPartners() } },
        ) { nowMs() }

    /** Shows whose cast, genres and tags an episode's quick replies were made from, this run (one request per show). */
    private val chatShows = mutableMapOf<String, dev.nitrate.core.model.MediaItem>()

    /**
     * The chat's one-press chips for [item]: its own emojis, its main characters' names and the
     * usual phrases (core `QuickReplies`, worked out here; nothing is sent anywhere). A film has all
     * it needs already; an episode's show is fetched once per show for its cast, genres and tags.
     */
    suspend fun quickPicks(item: dev.nitrate.core.model.MediaItem): dev.nitrate.core.companion.QuickPicks {
        val showId = item.seriesId?.takeIf { item.kind == dev.nitrate.core.model.ItemKind.EPISODE }
        val show = showId?.let { id ->
            chatShows[id] ?: runCatching { server.getItem(id) }.getOrNull()?.also { chatShows[id] = it }
        }
        return dev.nitrate.core.companion.QuickReplies.forTitle(item, show)
    }

    /** Group chat on or off for a title's watch groups: remembered per group, a display preference (per user). */
    suspend fun setGroupChat(groupIds: List<String>, on: Boolean) {
        val user = server.userId ?: return
        if (groupIds.isEmpty()) return
        displaySettings.savePrefs(user, displaySettings.prefs(user).first().withGroupChat(groupIds, on))
    }

    /** Chats in [groupId] without playing (null stops); this device's own group comes first. */
    fun chatAlongside(groupId: String?) {
        chat
        chatOnlyGroup.value = groupId
    }

    /** Tests: asks the (fake) server for its Companion plugin now, as signing in does. */
    suspend fun refreshCompanionForTests() = refreshCompanion()

    private var scriptSyncJob: kotlinx.coroutines.Job? = null

    /**
     * Keeps this profile's scripts in step with its server (Companion plugin): after sign-in, a
     * few seconds after scripts change here, and every few minutes for ones made elsewhere.
     */
    private fun syncScripts() {
        scriptSyncJob?.cancel()
        scriptSyncJob = networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Script sync")) {
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

    /** When the Companion was last asked about (0: not since signing in). */
    private var companionCheckedAt = 0L
    private var companionRetry: kotlinx.coroutines.Job? = null

    /**
     * Asks the server's Companion what it offers. A failed ask (no network yet, the server
     * restarting, the plugin mid-update) keeps what was known and tries again after 30 s, 2 min
     * and 10 min: settled once at sign-in, one failure used to hide every Companion feature (Watch
     * groups in Settings, invites answering from their card) until the app was closed.
     */
    private suspend fun refreshCompanion() {
        val answer = runCatching { server.companion() }
        if (answer.isFailure) { retryCompanion(); return }
        companionCheckedAt = nowMs()
        companionState.value = answer.getOrNull()
        if (companionState.value?.features?.contains("scripts") == true) syncScripts()
        if (companionState.value?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) == true) {
            refreshPartners()
            followStandings()
            followParties()
            // Asks sent while the app was closed are asked now.
            if (asksAvailable()) groupAsks.refresh()
        }
        chat
    }

    private fun retryCompanion() {
        if (companionRetry?.isActive == true) return
        companionRetry = networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Companion")) {
            for (wait in listOf(30_000L, 120_000L, 600_000L)) {
                kotlinx.coroutines.delay(wait)
                if (server.currentAccountKey == null) return@launch
                if (runCatching { server.companion() }.isSuccess) { refreshCompanion(); return@launch }
            }
        }
    }

    private val appShownState = MutableStateFlow(0L)

    /** When the app last came back into view: slow checks (another device playing, invites) look again then. */
    val appShown: StateFlow<Long> = appShownState

    /**
     * The app came back into view: the Companion is asked again when it hasn't been for 10
     * minutes (a plugin updated meanwhile may offer more), one light request.
     */
    fun onAppShown() {
        appShownState.value = nowMs()
        if (server.currentAccountKey == null || nowMs() - companionCheckedAt < 10 * 60_000L) return
        networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Companion")) { refreshCompanion() }
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
            dev.nitrate.core.recap.Recaps.before(server, item, prefs, nowMs(), playlist)
        }.also { pendingRecap = it }
    }

    /** Marks a playlist "in story order" (it then gets recaps), or not. Shared on every device. */
    suspend fun setRecapPlaylist(playlistId: String, on: Boolean) {
        val user = server.userId ?: return
        val prefs = displaySettings.prefs(user).first()
        displaySettings.savePrefs(user, prefs.copy(recapPlaylists = if (on) prefs.recapPlaylists + playlistId else prefs.recapPlaylists - playlistId))
    }

    /** Watching shows with partners (see [dev.nitrate.core.partners.WatchPartnersPlugin]). */
    val watchPartners = dev.nitrate.core.partners.WatchPartnersPlugin()

    private val partnersState = MutableStateFlow<dev.nitrate.core.partners.PartnersInfo?>(null)

    /** This user's watch partners and requests (null until known, or without the Companion plugin). */
    val partners: StateFlow<dev.nitrate.core.partners.PartnersInfo?> = partnersState

    init {
        // Kept as fresh as Home's row: an invite that arrived since the app opened answers a tap on its card.
        server.onPartnersLoaded { partnersState.value = it }
        server.onPlaylistsChanged { playlists.playlistsChanged() }
    }

    /** Requests to answer, shown when the app opens until answered or put off ("Later" hides them until next time). */
    val partnerRequestsLater = MutableStateFlow(emptySet<String>())

    private val standingsState = MutableStateFlow<Map<String, dev.nitrate.core.partners.KeepUp>>(emptyMap())

    /** Where the user stands in each show they watch with a group (cards say "Will is 2 ahead"). */
    val groupStandings: StateFlow<Map<String, dev.nitrate.core.partners.KeepUp>> = standingsState
    private var standingsJob: kotlinx.coroutines.Job? = null

    /** Reloads [groupStandings] (after sign-in, when watched state changes, after leaving a player). */
    fun refreshGroupStandings(delayMs: Long = 0) {
        standingsJob?.cancel()
        standingsJob = networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Watch partners")) {
            kotlinx.coroutines.delay(delayMs)
            runCatching { server.groupStandings() }.onSuccess { standingsState.value = it }
            // What a schedule locks moves as the group watches: cards and spoiler hiding go by it.
            if (partnersState.value?.groups.orEmpty().any { it.schedule?.active == true }) refreshPartners()
        }
    }

    suspend fun refreshPartners() {
        runCatching { server.partners() }.onSuccess { partnersState.value = it; partiesChanged() }
    }

    /**
     * Watch parties (Companion feature `party`): the prompts the Companion's news brings to the open
     * app, and their choices. Phones also set their own reminders from the groups' party times.
     */
    val parties by lazy {
        dev.nitrate.android.partners.WatchParties(
            server,
            groups = { partnersState.value?.groups.orEmpty() },
            updated = { groupId, party ->
                partnersState.value = partnersState.value?.let { info ->
                    info.copy(groups = info.groups.map { if (it.id == groupId) it.copy(party = party) else it })
                }
                partiesChanged()
            },
            joinSession = { id -> groupSessions.join(id)?.let { error(it) } },
        )
    }

    /** Schedule changes as the Companion tells them: Yes/No to someone's ask, a note when one passes, fails or lapses. */
    val scheduleAsks by lazy {
        dev.nitrate.android.partners.ScheduleAsks(
            vote = { runTogether(it) },
            changed = { networkScope.launch { refreshPartners(); pagesChanged() } },
        )
    }

    private var followingParties = false

    /** Listens for the Companion's watch party news on the live connection (sent, never polled). */
    private fun followParties() {
        if (followingParties) return
        followingParties = true
        networkScope.launch { remote.partyNews.collect { parties.heard(it) } }
        networkScope.launch { remote.scheduleNews.collect { scheduleAsks.heard(it) } }
        networkScope.launch { remote.sessionNews.collect { groupSessions.heard(it) } }
        networkScope.launch { remote.askNews.collect { groupAsks.heard(it) } }
        // A new invite: the card shows on Home, and the device notifies at once (not at its next 15-minute look).
        networkScope.launch {
            remote.inviteNews.collect {
                runCatching { refreshPartners() }
                pagesChanged()
                runCatching { newEpisodes.checkInvites() }
            }
        }
    }

    /** The groups' parties changed: a phone moves its own reminders (TVs show the prompts only while open). */
    private fun partiesChanged() {
        if (deviceKind == dev.nitrate.core.pages.DeviceKind.TV) return
        val groups = partnersState.value?.groups ?: return
        saveScope.launch { runCatching { newEpisodes.scheduleParties(groups, nowMs()) } }
    }

    /** Opens [groupId]'s watch party (a tapped reminder): one quick look at it, then its prompt. */
    suspend fun openParty(groupId: String) {
        if (partnersState.value == null) refreshPartners()
        val group = partnersState.value?.groups?.firstOrNull { it.id == groupId } ?: return
        val party = runCatching { server.watchParty(groupId) }.getOrNull() ?: group.party ?: return
        parties.open(group.copy(party = party))
    }

    /** Opens [groupId]'s waiting schedule change (a tapped notification): the groups looked at once, then its Yes/No. */
    suspend fun openScheduleAsk(groupId: String) {
        refreshPartners()
        partnersState.value?.groups?.firstOrNull { it.id == groupId }?.let { scheduleAsks.open(it) }
    }

    /**
     * The user is first to join tonight's watch party: the live session starts and the others are
     * invited (as "Watch it together now"), and the others' apps learn which session it is. The app
     * then plays the part through the guards. Returns an error, if any.
     */
    suspend fun startParty(start: dev.nitrate.android.partners.PartyOutcome.Start): String? {
        runTogether(start.choice)?.let { return it }
        groupSessions.current.value?.id?.let { session -> runCatching { server.partySession(start.groupId, session) } }
        return null
    }

    private val invitesState = MutableStateFlow<List<dev.nitrate.core.partners.WatchInvite>>(emptyList())

    /** Watch-together invites waiting for this user (Companion feature `together`), newest first. */
    val watchInvites: StateFlow<List<dev.nitrate.core.partners.WatchInvite>> = invitesState

    /** Whether the server can carry watch-together invites. */
    fun invitesAvailable(): Boolean = companion.value?.features?.contains("together") == true

    suspend fun refreshInvites() {
        if (!invitesAvailable()) { invitesState.value = emptyList(); return }
        runCatching { server.watchInvites() }.onSuccess { invitesState.value = it }
    }

    /**
     * Joins an older watch-together invite: a Group Watch Session's when it is one (its player
     * opens), else (sent by an older app) the SyncPlay group, kept for them; clears the invite.
     */
    suspend fun joinInvite(invite: dev.nitrate.core.partners.WatchInvite) {
        if (sessionsAvailable() && groupSessions.join(invite.groupId) == null) Unit else syncPlay.join(invite.groupId)
        dismissInvite(invite)
    }

    /** Turns an invite down (or clears one already acted on). */
    suspend fun dismissInvite(invite: dev.nitrate.core.partners.WatchInvite) {
        invitesState.value = invitesState.value.filterNot { it.id == invite.id }
        runCatching { server.dismissWatchInvite(invite.id) }
    }

    /** After leaving a player: where the user stands in shared shows has likely moved. */
    fun afterWatching() = refreshGroupStandings(3_000)

    private var followingStandings = false

    /** Once the user's watched state changes, refreshes where they stand in shared shows. */
    private fun followStandings() {
        if (followingStandings) return
        followingStandings = true
        networkScope.launch {
            remote.events.filter { it is ServerEvent.UserDataChanged }.collect { refreshGroupStandings(2_500) }
        }
    }

    /**
     * Carries out a choice from a "Watching together" card ([dev.nitrate.core.partners.TogetherRow]):
     * answering an invite, catching up, counting an episode for a group, spoilers by the user's own
     * progress, leaving a group. Choices that open something (Start, Invite, OpenShow, WatchNow's
     * player) are each app's. Home and the standings reload after. Returns an error message, if any.
     */
    suspend fun runTogether(choice: dev.nitrate.core.partners.TogetherChoice): String? {
        val result = runCatching {
            when (choice) {
                is dev.nitrate.core.partners.TogetherChoice.Accept -> { server.acceptPartner(choice.request.id); Unit }
                is dev.nitrate.core.partners.TogetherChoice.Decline -> server.declinePartner(choice.request.id)
                is dev.nitrate.core.partners.TogetherChoice.CatchUp -> {
                    // In the group's own record only (the user's own watched state stays theirs); older servers: Jellyfin's.
                    val groupId = choice.groupId
                    if (groupId != null && companion.value?.features?.contains("groupwatch") == true) server.markForGroups(listOf(groupId), choice.episodeIds)
                    else {
                        choice.episodeIds.forEach { server.setPlayed(it, true) }
                        groupId?.let { server.watchedWithGroup(it, choice.episodeIds, emptyList()) }
                    }
                    Unit
                }
                is dev.nitrate.core.partners.TogetherChoice.WatchedWith -> { server.watchedTogether(choice.episodeId, choice.groupId); Unit }
                // In that group's record only; never the user's own watched state.
                is dev.nitrate.core.partners.TogetherChoice.Mark -> { server.markForGroups(listOf(choice.groupId), choice.itemIds, choice.watched); Unit }
                // Everyone in the group here (changeable in the player); the app then plays from the group's card.
                is dev.nitrate.core.partners.TogetherChoice.InTheRoom ->
                    watchPartners.setRoom(plugins.host, choice.titleId, dev.nitrate.core.partners.SameRoom(choice.groupId, choice.present))
                is dev.nitrate.core.partners.TogetherChoice.RemoveTitle -> server.removeGroupTitle(choice.group.id, choice.titleId)
                is dev.nitrate.core.partners.TogetherChoice.OwnSpoilers -> {
                    val user = server.userId ?: return null
                    val prefs = displaySettings.prefs(user).first()
                    val shows = if (choice.on) prefs.ownSpoilerShows + choice.seriesId else prefs.ownSpoilerShows - choice.seriesId
                    displaySettings.savePrefs(user, prefs.copy(ownSpoilerShows = shows))
                }
                is dev.nitrate.core.partners.TogetherChoice.Leave -> server.leaveGroup(choice.group.id)
                is dev.nitrate.core.partners.TogetherChoice.WatchNow -> startWatchingNow(choice)
                is dev.nitrate.core.partners.TogetherChoice.Vote -> server.voteSchedule(choice.groupId, choice.changeId, choice.yes)
                else -> Unit
            }
        }
        refreshPartners()
        refreshGroupStandings(1_000)
        pagesChanged()
        return result.exceptionOrNull()?.let { it.message ?: "That didn't work" }
    }

    /** Adds [titles] to [groupId] (any member may). Returns an error, if any. */
    suspend fun addGroupTitles(groupId: String, titles: List<dev.nitrate.core.model.MediaItem>): String? {
        val r = runCatching { titles.forEach { server.addGroupTitle(groupId, it.id, it.name) } }
        refreshPartners()
        refreshGroupStandings(1_000)
        pagesChanged()
        return r.exceptionOrNull()?.let { it.message ?: "That didn't work" }
    }

    /**
     * Where Up Next's [next] plays from: the same title carries on from where its playback started
     * (a group's card, "for myself"), for the guards and the next player.
     */
    fun upNextOrigin(next: dev.nitrate.core.model.MediaItem): dev.nitrate.core.partners.PlayOrigin? =
        watchPartners.upNextOrigin(plugins.host, next, groupStandings.value)

    /**
     * Where a play from a details page opened from watch group [groupId]'s card starts: that
     * group's card for a part of its titles, like playing the card itself; null (elsewhere) for
     * anything else, or when the page was opened any other way ([groupId] null).
     */
    fun detailsOrigin(groupId: String?, item: dev.nitrate.core.model.MediaItem): dev.nitrate.core.partners.PlayOrigin? =
        dev.nitrate.core.partners.PlayOrigin.fromDetails(groupId, item, partners.value, groupStandings.value)

    private val countAsksState = MutableStateFlow<List<dev.nitrate.core.partners.CountAsk>>(emptyList())

    /**
     * "Also count it for Suzy, where it's next too?": playbacks from a group's card that finished
     * and are next in other groups too, asked once the player has closed (TV dialog, phone sheet).
     */
    val countAsks: StateFlow<List<dev.nitrate.core.partners.CountAsk>> = countAsksState

    private fun asked(ask: dev.nitrate.core.partners.CountAsk) {
        countAsksState.value = countAsksState.value.let { list ->
            val same = list.indexOfFirst { it.sameAs(ask) }
            if (same < 0) list + ask else list.mapIndexed { i, a -> if (i == same) a.copy(itemIds = (a.itemIds + ask.itemIds).distinct()) else a }
        }
    }

    /** The answer: "All groups where it's next" ([all]) counts it there too; "Just this group" leaves it as it is (counted already). */
    suspend fun answerCounting(ask: dev.nitrate.core.partners.CountAsk, all: Boolean): String? {
        countAsksState.value = countAsksState.value - ask
        if (!all) return null
        val r = runCatching { server.markForGroups(ask.others, ask.itemIds) }
        refreshGroupStandings(1_000)
        pagesChanged()
        return r.exceptionOrNull()?.let { it.message ?: "That didn't work" }
    }

    /** Asks [groupId] to change its schedule (it takes effect once most of the group agrees). Returns an error, if any. */
    suspend fun proposeSchedule(groupId: String, schedule: dev.nitrate.core.partners.GroupSchedule): String? {
        val r = runCatching { server.proposeSchedule(groupId, schedule) }
        refreshPartners()
        pagesChanged()
        return r.exceptionOrNull()?.let { it.message ?: "That didn't work" }
    }

    /**
     * What to start watching with someone (shows, films, playlists, collections): what was watched
     * lately, or anything in the library matching [query].
     */
    suspend fun togetherShows(query: String): List<dev.nitrate.core.model.MediaItem> = runCatching {
        if (query.isBlank()) server.recentlyWatched(20)
        else server.findTitles(query, dev.nitrate.core.partners.TogetherRow.KINDS, 40)
    }.getOrDefault(emptyList())

    /**
     * "Start a Group Watch Session": a Group Watch Session of the group on the part (or the group's live
     * one), the members told at once ("Tommy started a Group Watch Session · Join"; the server pushes it), and
     * what it plays to the end counts for the group. The app then plays the part, which waits for
     * the countdown (everyone joining within the first minute starts together).
     */
    private suspend fun startWatchingNow(choice: dev.nitrate.core.partners.TogetherChoice.WatchNow, startMs: Long = 0, gatherMinutes: Int = 0) {
        if (!sessionsAvailable()) error("Group Watch Sessions need a newer Nitrate Companion on the server")
        if (choice.groupId.isEmpty()) error("Pick a watch group to watch with")
        // A part the group's schedule hasn't let out: said before anyone is asked to join.
        watchPartners.refusedInSession(plugins.host, choice.groupId, choice.episode)?.let { error(it) }
        val wasIn = groupSessions.current.value?.id
        val joined = groupSessions.start(choice.groupId, choice.episode.id, startMs, gatherMinutes)
        // The group's live session is on something else: that one's to join, not this part.
        if (joined.session.itemId != choice.episode.id) {
            if (wasIn != joined.session.id) groupSessions.leave()
            error("Your group already has a Group Watch Session going on something else: join it from the group's card, or end it first")
        }
        // While this device stays in it, what the group hasn't watched plays as watching together (a schedule that doesn't allow playing ahead).
        watchPartners.watchingNow(plugins.host, choice.groupId, joined.session.id)
    }

    /**
     * A play the guards turned down (or that couldn't start): a Group Watch Session on it this device
     * is in, started or joined to play it, is left, since nothing here will play it; staying in it
     * left everyone waiting on a part nobody could open.
     */
    fun playTurnedDown(item: dev.nitrate.core.model.MediaItem) {
        if (groupSessions.current.value?.itemId == item.id) groupSessions.leave()
    }

    /** A profile 7 file's kind (MEL or FEL) as found when it played on this device (kept on the device). */
    fun dvLayersOf(itemId: String?): dev.nitrate.core.playback.DolbyVisionLayers? =
        itemId?.let { id -> runCatching { platform.libraryKeyValues.get("dv.layers.$id") }.getOrNull() }?.let(dev.nitrate.core.playback.DolbyVisionLayers::parse)

    /** Remembers what [dvLayersOf] says for [itemId] (told by the player once it has seen enough frames). */
    fun rememberDvLayers(itemId: String, layers: dev.nitrate.core.playback.DolbyVisionLayers) {
        runCatching { platform.libraryKeyValues.put("dv.layers.$itemId", layers.name) }
    }

    /**
     * The last playback's log (the device, the server's decision, the player's events, kept as it
     * plays and when it closes), so a playback that went wrong can be reported after leaving it.
     * Kept on the device only; sent only when asked ([sendLastPlaybackLog]).
     */
    fun keepPlaybackLog(player: PlayerReport) {
        val itemId = player.item?.id ?: return
        networkScope.launch {
            runCatching {
                val text = playbackReport(player)
                dev.nitrate.android.files.File("${platform.filesDir}/$LAST_PLAYBACK_FILE").writeText("$itemId\n$text")
            }
        }
    }

    /** The last playback's log when it was of [itemId] (else null). */
    fun lastPlaybackLog(itemId: String): String? = runCatching {
        val text = dev.nitrate.android.files.File("${platform.filesDir}/$LAST_PLAYBACK_FILE").takeIf { it.exists() }?.readText() ?: return null
        text.substringAfter('\n').takeIf { text.substringBefore('\n') == itemId }
    }.getOrNull()

    /** Saves [lastPlaybackLog] of [itemId] in the server's logs (Jellyfin Dashboard → Logs): the file's name. */
    suspend fun sendLastPlaybackLog(itemId: String): Result<String> = runCatching {
        server.sendClientLog("The last playback's log\n\n" + (lastPlaybackLog(itemId) ?: error("No log kept for this title")))
    }

    /** What went wrong when the app last closed on an error (written on the device only, Android's `CrashNote`), if it did. */
    fun lastCrash(): String? = runCatching {
        dev.nitrate.android.files.File("${platform.filesDir}/last-crash.txt").takeIf { it.exists() }?.readText()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** [lastCrash] saved in the user's own server's logs (Jellyfin Dashboard → Logs), for a bug report: the file's name. */
    suspend fun sendLastCrash(): Result<String> = runCatching {
        server.sendClientLog("Nitrate closed unexpectedly\n\n" + (lastCrash() ?: error("Nothing to send")))
    }

    /** Forgets [lastCrash] (after it's been copied or put away). */
    fun clearLastCrash() { runCatching { dev.nitrate.android.files.File("${platform.filesDir}/last-crash.txt").delete() } }

    /** This device's Group Watch Session as the playback guards see it (a group that watches together only asks for it). */
    private val togetherSession = object : dev.nitrate.core.plugins.TogetherSession {
        override fun participants(): List<String> = groupSessions.current.value?.members.orEmpty().map { it.name }
        override fun sessionId(): String? = groupSessions.current.value?.id
        override fun available(): Boolean = sessionsAvailable()
        override suspend fun start(item: dev.nitrate.core.model.MediaItem, groupId: String, userIds: List<String>, names: String) {
            startWatchingNow(dev.nitrate.core.partners.TogetherChoice.WatchNow(item, groupId, userIds, names))
        }
    }

    /**
     * A page's group mark ([dev.nitrate.core.partners.GroupMarks]): [itemIds] marked watched (or
     * not) in [groupIds]' own records only, never the user's own watched state; then the groups,
     * standings and Home are read again.
     */
    suspend fun markForGroups(groupIds: List<String>, itemIds: List<String>, watched: Boolean): Result<Unit> {
        val done = runCatching { server.markForGroups(groupIds, itemIds, watched); Unit }
        refreshPartners()
        refreshGroupStandings()
        pagesChanged()
        return done
    }

    /**
     * Where a play from group [origin]'s card starts: the group's own resume point on [item], or
     * the start (0), never the user's solo resume point. Null for any other origin, or without the
     * Companion's `groupresume` (Jellyfin's resume point then, as before).
     */
    fun groupStartMs(origin: dev.nitrate.core.partners.PlayOrigin?, item: dev.nitrate.core.model.MediaItem): Long? {
        val card = origin as? dev.nitrate.core.partners.PlayOrigin.GroupCard ?: return null
        if (companion.value?.features?.contains(dev.nitrate.core.partners.WatchPartners.RESUME_FEATURE) != true) return null
        val group = partnersState.value?.groups?.firstOrNull { it.id == card.groupId } ?: return null
        return group.resume[item.id]?.positionMs ?: 0L
    }

    /**
     * Who one playback is watched with, for the player's "With…" chip ([dev.nitrate.core.partners.WatchCompany]):
     * from the groups, standings and this device's Group Watch Session already loaded, nothing polled. What's
     * played to the end in the same room is counted for those there (`watchedWithGroup`), and the
     * standings reload after.
     */
    fun watchCompany(scope: kotlinx.coroutines.CoroutineScope): dev.nitrate.core.partners.WatchCompany =
        dev.nitrate.core.partners.WatchCompany(
            scope, partners, groupStandings, groupSessions.current,
            selfName = { accountState.value?.name },
            chosen = { title -> watchPartners.chosenOn(plugins.host, title) },
            onChosen = { title, with -> watchPartners.choose(plugins.host, title, with) },
            room = { title -> watchPartners.roomOn(plugins.host, title) },
            onRoom = { title, room -> watchPartners.setRoom(plugins.host, title, room) },
            record = { group, items, present ->
                networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Watch groups")) {
                    runCatching { server.watchedWithGroup(group, items, present) }
                    refreshGroupStandings(1_000)
                }
            },
            originOf = { item -> watchPartners.takeOrigin(plugins.host, item) },
            onPlaying = { title, origin -> watchPartners.nowPlaying(plugins.host, title, origin) },
            // What a playback counted for, in those groups' records (older servers keep none: nothing to write).
            mark = { groups, items ->
                if (companion.value?.features?.contains("groupwatch") == true) networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Watch groups")) {
                    runCatching { server.markForGroups(groups, items) }
                    refreshGroupStandings(1_000)
                    pagesChanged()
                }
            },
            ask = { if (companion.value?.features?.contains("groupwatch") == true) asked(it) },
            // Watched for groups: the server puts the user's own state back when it stops (a group watch isn't theirs).
            forGroups = { item, groups, before ->
                networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Watch groups")) { runCatching { server.groupPlay(item, groups, before) } }
            },
            // Left part-way for a group: the group's own resume point; Home then shows it on the group's card, not in Continue Watching.
            resume = { group, item, at ->
                if (companion.value?.features?.contains(dev.nitrate.core.partners.WatchPartners.RESUME_FEATURE) == true) networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Watch groups")) {
                    runCatching { server.setGroupResume(group, item, at) }
                    refreshPartners()
                    pagesChanged()
                }
            },
        )

    /**
     * "Start a Group Watch Session" from the player's group panel: a Group Watch Session of [group] on [item] from
     * [startMs] (where the player is), or the group's live one; the player already showing [item]
     * keeps pace with it (held there for the countdown). Returns an error message, if any.
     */
    suspend fun startWatchingNow(item: dev.nitrate.core.model.MediaItem, group: dev.nitrate.core.partners.WatchGroup?, startMs: Long = 0, gatherMinutes: Int = 0): String? = runCatching {
        val g = group ?: error("Pick a watch group to watch with")
        startWatchingNow(dev.nitrate.core.partners.TogetherChoice.WatchNow(item, g.id, g.others.map { it.id }, dev.nitrate.core.partners.WithChip.nameOf(g)), startMs, gatherMinutes)
    }.exceptionOrNull()?.let { it.message ?: "Couldn't start the Group Watch Session" }

    /** "Add them to the group too?": the usual watch-group invite of [userIds] into [group] (each reads "sent"). */
    suspend fun inviteToGroup(group: dev.nitrate.core.partners.WatchGroup, userIds: List<String>): String? {
        val r = runCatching { userIds.forEach { server.askPartner(it, null, group.seriesId, group.seriesName, group.id, titles = group.titles) } }
        refreshPartners()
        return r.exceptionOrNull()?.let { it.message ?: "Couldn't send the invites" }
    }

    /** Comics read from the server, kept on the device (see [dev.nitrate.android.comics.ComicLibrary]). */
    /** Comic catalogs (OPDS, CLU) the server's admin set up in the Companion plugin, reached through the server. */
    val catalogs by lazy { dev.nitrate.android.comics.CatalogStore(server, platform.stores.plugins, platform.downloadEngine()) }
    val comics by lazy { dev.nitrate.android.comics.ComicLibrary(server, platform.cacheDir, platform.downloadEngine(), pdf = platform.pdfPages(), pictures = platform.pictureReader(), now = ::nowMs) }

    /** Theme music behind details pages (each app's details screens ask for it; players stop it). */
    val themeMusic: dev.nitrate.android.playback.ThemeMusic by lazy { platform.themeMusic(server) }

    /** Per-show audio and subtitle languages, for the signed-in user. */
    val trackMemory by lazy { displaySettings.trackMemory { server.userId } }

    private val accountState = MutableStateFlow<AccountInfo?>(null)

    /** The signed-in account (null until known, or offline); admin tools show only for admins. */
    val account: StateFlow<AccountInfo?> = accountState

    /** Re-reads the account, after sign-in or when the server comes back. */
    suspend fun refreshAccount() {
        accountState.value = runCatching { server.account() }.getOrNull()
    }

    /**
     * Whether Manage (details, locks, IDs, Identify, refresh, images) shows on a title: admins
     * always; metadata editors (Companion feature `metadata`) when the title's library is in their
     * scope (the server says, kept briefly); nobody else, with nothing asked.
     */
    suspend fun canManageMetadata(itemId: String): Boolean {
        val a = accountState.value ?: return false
        if (a.isAdmin) return true
        if (!a.metadataEditor.isEditor) return false
        return runCatching { server.canEditMetadata(itemId) }.getOrDefault(false)
    }

    /** Bulk editing on a library's list: admins, or metadata editors whose scope covers [libraryId]. */
    fun canBulkEditMetadata(libraryId: String?): Boolean = accountState.value?.canEditMetadataIn(libraryId) == true

    /** Admins' Users switch and the log of edits ("Metadata edits"): only with the Companion's metadata editors. */
    fun offersMetadataEditors(): Boolean =
        accountState.value?.isAdmin == true && companionOffers(dev.nitrate.core.admin.MetadataEditors.FEATURE)

    private val companionUpdatesState = MutableStateFlow<dev.nitrate.core.companion.CompanionUpdates?>(null)

    /**
     * Where the Companion plugin's own updates stand, for admins (null for everyone else, or until
     * Settings asks): a newer version waiting for a server restart shows at the top of Settings.
     */
    val companionUpdates: StateFlow<dev.nitrate.core.companion.CompanionUpdates?> = companionUpdatesState

    private fun canSeeCompanionUpdates() = accountState.value?.isAdmin == true &&
        companionState.value?.features?.contains(dev.nitrate.core.companion.CompanionUpdates.FEATURE) == true

    /** Asks where the Companion's updates stand (admins; one request, when Settings opens). */
    suspend fun refreshCompanionUpdates() {
        if (!canSeeCompanionUpdates()) { companionUpdatesState.value = null; return }
        runCatching { server.companionUpdates() }.onSuccess { companionUpdatesState.value = it }
    }

    /** "Check for updates now": the Companion looks and installs a newer version at once. Returns an error, if any. */
    suspend fun checkCompanionUpdates(): String? {
        if (!canSeeCompanionUpdates()) return "Only an administrator can update the Nitrate Companion."
        return runCatching { server.checkCompanionUpdates() }.fold({ companionUpdatesState.value = it; null }, { it.message ?: "Couldn't check for updates" })
    }

    /** "Restart now": restarts the server to finish the Companion's update. Returns why it didn't, if it didn't. */
    suspend fun restartForCompanionUpdate(): String? =
        runCatching { server.restartForCompanionUpdate() }.fold(
            { companionUpdatesState.value = companionUpdatesState.value?.copy(restartNeeded = false, lastCheck = "Restarting the server…"); null },
            { e -> refreshCompanionUpdates(); e.message ?: "The server wasn't restarted" },
        )

    /** New-episode notifications (phone). */
    val newEpisodes: dev.nitrate.android.notifications.NewEpisodeNotifier by lazy { platform.newEpisodes(server) }

    /**
     * Builds a playback report (this device, what Nitrate allows, and [session]'s item, server
     * decision and player events if given) as text.
     */
    suspend fun playbackReport(player: PlayerReport? = null): String =
        dev.nitrate.core.playback.PlaybackReport(
            app = clientName,
            version = version,
            createdAt = platform.timestamp(),
            caps = capabilities,
            settings = playbackSettings.current(),
            item = player?.item,
            plan = player?.plan,
            videoDecoder = player?.videoDecoder,
            audioDecoder = player?.audioDecoder,
            events = player?.events.orEmpty(),
            error = player?.error,
        ).render()

    /**
     * Sends a playback report to the signed-in Jellyfin server as a client log, and returns the
     * file name it's saved as there (Dashboard → Logs).
     */
    suspend fun sendPlaybackReport(player: PlayerReport? = null): Result<String> =
        runCatching { server.sendClientLog(playbackReport(player)) }

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
        val settings = playbackSettings.current()
        // The device's part (decoder, HDR, sound, refresh rate), for the file and tracks the server answered for.
        val prediction = runCatching {
            dev.nitrate.core.jellyfin.ProfileCheck.predict(capabilities, settings, plan.source, plan.audioIndex, plan.subtitleIndex, itemId, platform.displaySnapshot(), dvLayersOf(itemId))
        }.getOrNull()
        return dev.nitrate.core.playback.PlaybackDoctor.diagnose(plan, capabilities, settings).copy(plan = plan, prediction = prediction)
    }

    /**
     * How every film and episode the user can see (in [libraryId], or all) would play on this device,
     * worked out here from each file's details: Settings → Library playback overview.
     */
    suspend fun playbackOverview(libraryId: String?, libraryName: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): dev.nitrate.core.playback.PlaybackOverview {
        val settings = playbackSettings.current()
        val display = platform.displaySnapshot()
        val sources = server.playbackSources(libraryId, onProgress)
        val entries = dev.nitrate.core.playback.PlaybackOverview.build(sources) { item, source ->
            dev.nitrate.core.jellyfin.ProfileCheck.predict(capabilities, settings, source, itemId = item.id, display = display, dvLayers = dvLayersOf(item.id))
        }
        val overview = dev.nitrate.core.playback.PlaybackOverview(
            entries = entries,
            app = "$clientName $version",
            device = "${capabilities.model} (${platform.identity.deviceName})",
            createdAt = platform.timestamp(),
            settings = dev.nitrate.core.playback.PlaybackOverview.settingsSummary(settings),
            library = libraryName,
        )
        lastOverview = libraryId to overview
        shareWithAdmin()
        return overview
    }

    /** Saves the overview in the server's logs (Dashboard → Logs), for the admin; returns the file name. */
    suspend fun sendPlaybackOverview(overview: dev.nitrate.core.playback.PlaybackOverview): Result<String> =
        runCatching { server.sendClientLog(overview.report(testPlays.value.values)) }

    /** The last overview read (library id → overview), so coming back from a test play doesn't read the library again. */
    var lastOverview: Pair<String?, dev.nitrate.core.playback.PlaybackOverview>? = null

    private val _testPlays = kotlinx.coroutines.flow.MutableStateFlow<Map<String, dev.nitrate.core.playback.TestPlayResult>>(emptyMap())
    private var testPlaysAccount: String? = null

    /**
     * Test plays from the playback overview (by item id): what really happened when each title
     * played for a short while here. Kept per account on this device and in its overview reports.
     */
    val testPlays: kotlinx.coroutines.flow.StateFlow<Map<String, dev.nitrate.core.playback.TestPlayResult>> = _testPlays

    private val testPlaySerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), dev.nitrate.core.playback.TestPlayResult.serializer())

    /** Reads this account's saved test plays (once per account). */
    suspend fun loadTestPlays() {
        val account = server.currentAccountKey ?: return
        if (testPlaysAccount == account) return
        testPlaysAccount = account
        _testPlays.value = runCatching { dataCache.read(account, "testplays", testPlaySerializer) }.getOrNull().orEmpty()
    }

    /** Keeps a test play's result (replacing an earlier one of the same title). */
    suspend fun saveTestPlay(result: dev.nitrate.core.playback.TestPlayResult) {
        loadTestPlays()
        _testPlays.value = _testPlays.value + (result.itemId to result)
        server.currentAccountKey?.let { runCatching { dataCache.write(it, "testplays", _testPlays.value, testPlaySerializer) } }
        shareWithAdmin()
    }

    private var shareJob: kotlinx.coroutines.Job? = null

    /**
     * Sends this device's playback summary (its last overview, test plays and TV check) for the
     * server's admin, a few seconds after the latest change, when the server has the Companion
     * plugin and this device hasn't switched it off ([dev.nitrate.core.playback.PlaybackSettings.shareWithAdmin]).
     */
    fun shareWithAdmin() {
        shareJob?.cancel()
        shareJob = networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Household devices")) {
            kotlinx.coroutines.delay(3_000)
            val settings = playbackSettings.current()
            if (!settings.shareWithAdmin) return@launch
            if (companionState.value?.features?.contains("devices") != true) return@launch
            loadTestPlays()
            val summary = dev.nitrate.core.playback.DeviceSummary.build(
                deviceName = platform.identity.deviceName, model = capabilities.model, app = "$clientName $version", atMs = nowMs(),
                caps = capabilities, settings = settings, overview = lastOverview?.second, tests = testPlays.value.values,
            )
            runCatching { server.sendDeviceSummary(summary) }
        }
    }

    /** Forgets every test play. */
    suspend fun clearTestPlays() {
        _testPlays.value = emptyMap()
        server.currentAccountKey?.let { runCatching { dataCache.write(it, "testplays", _testPlays.value, testPlaySerializer) } }
    }

    /** How [itemId] would play here, worked out on the device alone (no call to the server's playback decision). */
    suspend fun predictPlayback(itemId: String, source: dev.nitrate.core.model.MediaSource, audioIndex: Int? = null, subtitleIndex: Int? = null): dev.nitrate.core.playback.PlaybackPrediction =
        dev.nitrate.core.jellyfin.ProfileCheck.predict(capabilities, playbackSettings.current(), source, audioIndex, subtitleIndex, itemId, platform.displaySnapshot(), dvLayersOf(itemId))

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
        // The plugin's timing check, when it's there (admins; the health check is for admins).
        val timing = runCatching { server.fileTimingIssues() }.getOrDefault(emptyList())
        val found = dev.nitrate.core.diagnostics.LibraryHealth.findings(server.healthScan(libraryId), capabilities, playbackSettings.current(), timing)
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
            device = "${capabilities.model} (${platform.identity.deviceName})",
            server = server.serverUrl.orEmpty(),
            createdAt = platform.timestamp(),
            steps = steps,
        )
    }

    /** Saves a speed check in the server's logs (Dashboard → Logs); returns the file name. */
    /** Every request made to the server this run (the query analyzer); null when the server client records none. */
    val queryLog: dev.nitrate.core.diagnostics.QueryLog? get() = server.queryLog

    /** Whether the server's Companion plugin times requests too. */
    fun serverTimesQueries(): Boolean = companion.value?.features?.contains(QUERY_TIMES_FEATURE) == true

    /** Adds the server's own times to the log's requests (Companion feature `querytimes`); false without it. */
    suspend fun fetchServerQueryTimes(): Boolean {
        val log = queryLog ?: return false
        if (!serverTimesQueries()) return false
        val times = runCatching { dev.nitrate.core.diagnostics.withQueryOrigin("Query analyzer") { server.serverQueryTimes(log.run) } }.getOrNull() ?: return false
        log.addServerTimes(times)
        return true
    }

    /**
     * Notices "that intro was wrong" from skipping it and going straight back (players call
     * `skipped` on a Skip and `position` as they play); sent to the Companion's corrections.
     */
    val skipWatch = dev.nitrate.core.companion.SkipWatch { item, part -> networkScope.launch { server.segmentFeedback(item, part) } }

    /** Whether the Companion plugin offers [feature] (as last asked). */
    fun companionOffers(feature: String): Boolean = companion.value?.features?.contains(feature) == true

    /** This year's real watch time from the Companion (null without it): [fromMs, toMs), the device's time zone. */
    suspend fun watchTime(fromMs: Long, toMs: Long, offsetMs: Long): dev.nitrate.core.companion.WatchTime? {
        if (!companionOffers("watching")) return null
        val sittings = runCatching { server.sittings(fromMs, toMs) }.getOrNull() ?: return null
        return dev.nitrate.core.companion.WatchTime.of(sittings, offsetMs)
    }

    /** Each kind of request's typical time day by day (admins, Companion `querytimes`); empty otherwise. */
    suspend fun queryTrends(days: Int = 30): List<dev.nitrate.core.companion.QueryTrend> =
        if (account.value?.isAdmin != true || !companionOffers("querytimes")) emptyList()
        else runCatching { dev.nitrate.core.diagnostics.withQueryOrigin("Query analyzer") { server.queryTrends(days) } }.getOrDefault(emptyList())

    /** Titles that failed or were converted on the household's devices (admins, Companion `problems`); empty otherwise. */
    suspend fun problemTitles(): List<dev.nitrate.core.companion.ProblemTitle> =
        if (account.value?.isAdmin != true || !companionOffers("problems")) emptyList()
        else runCatching { server.problemTitles() }.getOrDefault(emptyList())

    /** Every person's requests by kind over the last two hours, for admins with the Companion plugin; empty otherwise. */
    suspend fun householdQueries(): List<dev.nitrate.core.diagnostics.HouseholdQuery> =
        if (account.value?.isAdmin != true || !serverTimesQueries()) emptyList()
        else dev.nitrate.core.diagnostics.withQueryOrigin("Query analyzer") { server.householdQueries() }

    /** Library and custom page ids → names, so origins read "Library Films" rather than an id. */
    suspend fun queryNames(): Map<String, String> {
        val libraries = runCatching { libraryCache.cached(server) }.getOrDefault(emptyList()).associate { it.id to it.name }
        val pages = runCatching { userIdOrNull()?.let { displaySettings.customPages(it).first() } }.getOrNull().orEmpty().associate { it.id to it.title }
        return libraries + pages
    }

    private fun userIdOrNull() = server.userId

    /** Saves the analyzer's summary of [records] in the server's logs (Dashboard → Logs). */
    suspend fun sendQueryReport(title: String, records: List<dev.nitrate.core.diagnostics.QueryRecord>): Result<String> = runCatching {
        val names = queryNames()
        val readable = records.map { it.copy(origin = dev.nitrate.core.diagnostics.QueryLog.readable(it.origin, names)) }
        server.sendClientLog(dev.nitrate.core.diagnostics.QuerySummary.of(readable).render(title, platform.identity.deviceName))
    }

    suspend fun sendSpeedReport(report: dev.nitrate.core.diagnostics.SpeedReport): Result<String> =
        runCatching { server.sendClientLog(report.render()) }

    /** Libraries as last seen, for menus that should be whole at once. */
    val libraryCache by lazy { dev.nitrate.android.storage.LibraryCache(platform.libraryKeyValues) }

    /** Updates from the project's releases, for whichever app this is (phone or TV). */
    val updater: dev.nitrate.android.update.AppUpdater by lazy { platform.updater() }

    /** PIN locks and "who's watching" for the accounts saved here. */
    val profiles by lazy { ProfileStore(platform.stores.profiles) }

    /** After signing in or switching account: settings, account details and the live connection. */
    suspend fun onSignedIn() {
        settingsSync.pull()
        // The first-run setup decides before the backup offer settles a device with no other backups.
        setup.onSignedIn(accountSettledBefore = server.currentAccountKey?.let { backups.isSettled(it) } ?: true)
        backups.onSignedIn()
        remote.start()
        // Not waited for: Home shouldn't wait on the account details (admin tools appear when they arrive).
        networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Sign-in")) { refreshAccount() }
        networkScope.launch(dev.nitrate.core.diagnostics.QueryOrigin("Sign-in")) { refreshCompanion() }
        refreshGroupStandings()
        writing.account = server.currentAccountKey
        watchNetwork()
    }

    private val networkScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate + dev.nitrate.core.diagnostics.QueryOrigin("In the background"))
    private var networkWatched = false
    private var reconnecting: kotlinx.coroutines.Job? = null

    /**
     * When the network changes (out of the house, onto mobile data, back home), moves to whichever
     * of the server's addresses answers and restarts the live connection if the address changed.
     */
    private fun watchNetwork() {
        if (networkWatched) return
        networkWatched = true
        platform.watchNetwork(::reconnectSoon)
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
        server.currentAccountKey?.let { profiles.forget(it); dataCache.forget(it); searchHistory.forget(it) }
        server.logout()
    }

    /** Forgets a saved account that isn't current. */
    suspend fun forgetAccount(key: String) {
        profiles.forget(key)
        dataCache.forget(key)
        searchHistory.forget(key)
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
        groupSessions.clear()
        groupAsks.clear()
        remote.stop()
        // The music belongs to the account that's leaving (its reports, its library).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { listening?.stop() }
        accountState.value = null
        chatOnlyGroup.value = null
        companionState.value = null; companionCheckedAt = 0L; companionRetry?.cancel()
        companionUpdatesState.value = null
        // Group standings belong to the account that's leaving.
        standingsState.value = emptyMap()
        // So do its watch parties: nothing asked, no reminders set.
        parties.clear()
        scheduleAsks.clear()
        partnersState.value = null
        countAsksState.value = emptyList()
        if (deviceKind != dev.nitrate.core.pages.DeviceKind.TV) runCatching { newEpisodes.scheduleParties(emptyList(), nowMs()) }
        writing.account = null
        scriptSyncJob?.cancel()
        themeMusic.stop()
    }

    /** Free space where downloads are saved. */
    fun downloadSpaceFree(): Long = downloadDir.also { it.mkdirs() }.usableSpace()

    /** Implemented by each app's Application so background work can find the container. */
    interface Owner {
        val container: AppContainer
    }

    private val pluginStore = PluginStore(platform.stores.plugins)

    val plugins = PluginRegistry(
        all = listOf(PreviousEpisodePlugin, watchPartners, WritingPlugin, dev.nitrate.core.channels.ChannelsPlugin),
        host = PluginHost(server, pluginStore, togetherSession),
    )

    /** The first-run setup: whether it's showing, and whether this device finished it. */
    val setup by lazy { dev.nitrate.android.backup.FirstRunSetupStore(platform.stores.backup) }

    /**
     * Backups to copy from in the setup: the backup offer's after a first sign-in, else (the setup
     * run again from Settings) every other device's.
     */
    suspend fun setupBackups(): List<dev.nitrate.core.backup.BackupSummary> =
        backups.offer.value?.backups ?: runCatching { backups.backups() }.getOrDefault(emptyList()).filter { it.deviceId != backups.deviceId }

    /** This device's playback and plugin settings, backed up to the user's server and restorable on another device. */
    val backups by lazy {
        dev.nitrate.android.backup.DeviceBackupManager(
            store = platform.stores.backup,
            server = server,
            playback = playbackSettings,
            plugins = pluginStore,
            display = displaySettings,
            pluginList = { plugins.all },
            deviceName = platform.identity.deviceName,
            model = { capabilities.model },
            app = platform.appKind,
            version = version,
        )
    }

    companion object {
        /** The Companion plugin feature that times requests on the server. */
        const val QUERY_TIMES_FEATURE = "querytimes"
    }
}

/** How long a library scan's changes are let settle before pages reload (`AppContainer.serverChanges`). */
private const val LIBRARY_SETTLE_MS = 1_700L

/** The last playback's log on the device (`AppContainer.keepPlaybackLog`). */
private const val LAST_PLAYBACK_FILE = "last-playback.txt"

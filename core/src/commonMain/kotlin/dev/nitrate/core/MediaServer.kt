package dev.nitrate.core

import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.SavedAccount
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.QuickConnectRequest
import dev.nitrate.core.model.RemoteUserData
import dev.nitrate.core.model.SearchResults
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.model.ServerAddress
import dev.nitrate.core.model.ServerUser
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.SavedServer
import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.admin.AccountInfo
import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.playback.RemoteSubtitle
import dev.nitrate.core.admin.ActivityEntry
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.ItemImage
import dev.nitrate.core.admin.ItemMetadata
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.RemoteImage
import dev.nitrate.core.admin.RemoteMatch
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.ServerTask
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.admin.UserAccount
import dev.nitrate.core.admin.UserPermissions
import dev.nitrate.core.downloads.TranscodeSpec
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.playback.ChannelClip
import dev.nitrate.core.rows.MediaRow
import kotlinx.coroutines.flow.Flow

enum class LibrarySort(val label: String) {
    NAME("Name"),
    DATE_ADDED("Date added"),
    RELEASE_DATE("Release date"),
    RATING("Rating"),
    RANDOM("Random"),
}

/**
 * Everything the apps need from a media server, in Nitrate's own types. Screens talk only to
 * this. [dev.nitrate.core.jellyfin.JellyfinClient] implements it for every platform.
 */
interface MediaServer {
    /** Signed-in user's id; scopes per-user settings. */
    val userId: String?

    /** Address of the signed-in server, for messages. */
    val serverUrl: String?

    // ---- Session ----
    suspend fun restoreSession(): SessionState
    suspend fun checkSession(): SessionState

    /** Resolves what the user typed ("192.168.1.5", "jelly.example.com") to a reachable server. */
    suspend fun findServer(input: String): ServerAddress

    /** Servers answering a broadcast on the local network. */
    fun discoverServers(): Flow<DiscoveredServer>
    suspend fun publicUsers(serverUrl: String): List<ServerUser>

    /** Throws [dev.nitrate.core.model.InvalidCredentialsException] for a wrong username or password. */
    suspend fun login(serverUrl: String, username: String, password: String)
    suspend fun quickConnectEnabled(serverUrl: String): Boolean
    suspend fun startQuickConnect(serverUrl: String): QuickConnectRequest
    suspend fun quickConnectApproved(secret: String): Boolean
    suspend fun finishQuickConnect(serverUrl: String, secret: String)

    /** The identity provider the server offers sign-in with (Nitrate Companion), or null. */
    suspend fun ssoProvider(serverUrl: String): dev.nitrate.core.model.SsoProvider? = null

    /** Starts signing in with the identity provider. */
    suspend fun startSso(serverUrl: String): dev.nitrate.core.model.SsoRequest = unsupported()

    /** Checks a started sign-in; once it's [dev.nitrate.core.model.SsoStatus.Done] this device is signed in. */
    suspend fun pollSso(serverUrl: String, request: dev.nitrate.core.model.SsoRequest): dev.nitrate.core.model.SsoStatus = unsupported()
    /** Signs out of the current account and forgets it here (other saved accounts stay). */
    suspend fun logout()

    // ---- Saved accounts ----
    /** Every server and user signed in on this device. */
    suspend fun savedAccounts(): List<SavedAccount> = emptyList()

    /**
     * Saves [text] on the server as a client log (Jellyfin keeps it with its own logs: Dashboard →
     * Logs) and returns the file name it was saved as.
     */
    suspend fun sendClientLog(text: String): String = unsupported()

    /** Every request made to the server, for the query analyzer; null where nothing is recorded. */
    val queryLog: dev.nitrate.core.diagnostics.QueryLog? get() = null

    /**
     * One of the app's own rows ([dev.nitrate.core.rows.AppRows]) for any page, narrowed to
     * [libraryId] where it can be; null when there's nothing in it.
     */
    suspend fun appRow(key: String, libraryId: String? = null): MediaRow? = null

    /** The server's own time for this app run's requests (tag → ms), from the Companion plugin (feature `querytimes`). */
    suspend fun serverQueryTimes(run: String): Map<String, Long> = emptyMap()

    // ---- Companion: what its database keeps (each empty or a no-op without the feature) ----

    /** Each request kind's server times by day, the last [days] (`querytimes`, admins). */
    suspend fun queryTrends(days: Int = 30): List<dev.nitrate.core.companion.QueryTrend> = emptyList()

    /** "That part of [itemId] was wrong" (`segmentfeedback`). */
    suspend fun segmentFeedback(itemId: String, part: dev.nitrate.core.companion.SegmentPart) {}

    /** The caller's own sittings in [fromMs, toMs) (`watching`). */
    suspend fun sittings(fromMs: Long, toMs: Long): List<dev.nitrate.core.companion.Sitting> = emptyList()

    /** This device had trouble with a title: [failed] it, else the server converted it (`problems`). */
    suspend fun reportProblem(itemId: String, failed: Boolean, reason: String?) {}

    /** Titles with trouble across the household (`problems`, admins). */
    suspend fun problemTitles(): List<dev.nitrate.core.companion.ProblemTitle> = emptyList()

    /** The caller's moments (in [itemId], else all) and those shared with their watch groups (`moments`, servers before `notes`). */
    suspend fun moments(itemId: String? = null): List<dev.nitrate.core.companion.Moment> = emptyList()

    /**
     * A title's group chat pinned to its timeline that the caller may read, and their watch groups
     * on it (`notes`; older servers' moments read as notes); null without either feature.
     */
    suspend fun titleNotes(itemId: String): dev.nitrate.core.companion.TitleNotes? = null

    /**
     * Pins a message or reaction at [positionMs], once (`notes`): for the caller's watch groups on
     * the title, or with [sessionId] (`livenotes`) in that live SyncPlay session, whose participants
     * get it at once through the session's chat and keep it. Throws [dev.nitrate.core.companion.ChatRejected].
     */
    suspend fun pinNote(itemId: String, positionMs: Long, kind: dev.nitrate.core.companion.ChatKind, text: String, sessionId: String? = null): dev.nitrate.core.companion.TimelineNote = unsupported()

    /**
     * The same, for only [groupIds] of the caller's watch groups on the title (the group the
     * playback counts for, the player's "Counts for"; `notegroups`); null: all of them. Servers
     * without `notegroups` write it for all of them.
     */
    suspend fun pinNote(itemId: String, positionMs: Long, kind: dev.nitrate.core.companion.ChatKind, text: String, sessionId: String?, groupIds: List<String>?): dev.nitrate.core.companion.TimelineNote =
        pinNote(itemId, positionMs, kind, text, sessionId)

    /**
     * "Bring our earlier notes" (`livenotes`): while the live session [sessionId] lasts, everyone in
     * it gets the caller's readable notes that went to watch group [groupId] as playback passes them,
     * never kept for them; null stops it. Throws [dev.nitrate.core.companion.ChatForbidden] outside the session.
     */
    suspend fun bringNotes(sessionId: String, groupId: String?): Unit = unsupported()

    /**
     * A title's chat histories the caller may see, newest first, and their watch groups on it
     * (Companion `chats`; nothing of what was said); null on servers without chat histories.
     */
    suspend fun titleChats(itemId: String): dev.nitrate.core.companion.TitleChats? = null

    /**
     * A chat picked to play along (`chats`): its notes and those of the chats it builds on that the
     * caller may read, earliest in the title first. Empty when none of it is theirs to read.
     */
    suspend fun chatNotes(itemId: String, chatId: String): List<dev.nitrate.core.companion.TimelineNote> = emptyList()

    /**
     * Pins a message into a chat history (`chats`): [chat]'s chat (this playback's, from the first
     * message's answer), else a new one made now, building on the chat playing along. In a live
     * session ([sessionId]) the server keeps one chat for everyone there. The answer's
     * [dev.nitrate.core.companion.TimelineNote.chatId] says which. Servers without `chats` pin it as [pinNote] does.
     */
    suspend fun pinChatNote(itemId: String, positionMs: Long, kind: dev.nitrate.core.companion.ChatKind, text: String, sessionId: String?, groupIds: List<String>?, chat: dev.nitrate.core.companion.ChatWrite): dev.nitrate.core.companion.TimelineNote =
        pinNote(itemId, positionMs, kind, text, sessionId, groupIds)

    /**
     * Plays an earlier chat along for everyone in the live session [sessionId] on [itemId]
     * (`chats`; null stops it). Throws [dev.nitrate.core.companion.ChatForbidden] outside the session or for a chat the caller can't see.
     */
    suspend fun playAlong(sessionId: String, itemId: String, chatId: String?): Unit = unsupported()

    /** The live session's chat on [itemId] (`chats`): the chat written there and the earlier one playing along with its notes; null when not in it. */
    suspend fun sessionChat(sessionId: String, itemId: String): dev.nitrate.core.companion.SessionChat? = null

    /** The notes brought into the live session [sessionId] on [itemId] (`livenotes`); empty when none or not in it. */
    suspend fun broughtNotes(sessionId: String, itemId: String): List<dev.nitrate.core.companion.TimelineNote> = emptyList()

    /** Removes one of the caller's own notes (or older moments) on a title. */
    suspend fun deleteNote(itemId: String, id: String) {}

    /** A title's kept chat from viewings the caller was part of (`chathistory`). */
    suspend fun chatHistory(itemId: String): List<dev.nitrate.core.companion.ChatRecord> = emptyList()

    /** Every person's requests of the last two hours by kind (Companion `querytimes`, admins only); empty otherwise. */
    suspend fun householdQueries(): List<dev.nitrate.core.diagnostics.HouseholdQuery> = emptyList()

    /** Lets go of answers kept to share between pages (a page refreshed by hand asks afresh). */
    suspend fun forgetAnswers() {}

    /**
     * Times typical requests to the server from this device (round trip, a signed-in call, the
     * library list, a library search, a full Home load, download speed, and reading a large
     * answer here), calling [onStep] as each finishes.
     */
    suspend fun speedCheck(onStep: (dev.nitrate.core.diagnostics.SpeedStep) -> Unit = {}): List<dev.nitrate.core.diagnostics.SpeedStep> = unsupported()

    /** Every server signed in to here, the primary first. */
    suspend fun servers(): List<SavedServer> = emptyList()

    /** Saves a server's local, VPN and remote addresses (blank = none); reconnects if it's the current one. */
    suspend fun saveServerAddresses(serverUrl: String, local: String?, vpn: String?, remote: String?): SessionState = unsupported()

    /** Makes [serverUrl] the server opened at start. */
    suspend fun setPrimaryServer(serverUrl: String): Unit = unsupported()

    /**
     * Whether a Jellyfin server answers at [input] (with or without scheme and port), and how fast;
     * with [serverUrl], also whether it's that saved server.
     */
    suspend fun testAddress(input: String, serverUrl: String? = null): AddressCheck = AddressCheck.Failed("Not supported")

    /**
     * Moves to whichever saved address answers (the local one first) and checks the sign-in; for
     * when the network changes, such as leaving home.
     */
    suspend fun reconnect(): SessionState = checkSession()

    /** The current account's [SavedAccount.key]. */
    val currentAccountKey: String? get() = null

    /** Makes a saved account current; EXPIRED means it needs signing in again. */
    suspend fun switchAccount(key: String): SessionState = unsupported()

    /** Forgets a saved account that isn't current (its token is revoked when the server answers). */
    suspend fun forgetAccount(key: String): Unit = unsupported()

    // ---- Per-user storage on the server (display preferences) ----
    suspend fun loadCustomPrefs(prefsId: String = DEFAULT_PREFS): Map<String, String>
    suspend fun saveCustomPrefs(prefs: Map<String, String>, prefsId: String = DEFAULT_PREFS)

    // ---- Browsing ----
    suspend fun views(): List<MediaItem>
    suspend fun homeRows(): List<MediaRow>
    suspend fun libraryRows(library: MediaItem): List<MediaRow>

    /** Home's rows as each arrives, so the page needn't wait for its slowest row; rows keyed in [skip] (hidden) aren't loaded. */
    fun homeRowsProgress(skip: Set<String> = emptySet()): kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress> =
        kotlinx.coroutines.flow.flow { emit(dev.nitrate.core.rows.RowsProgress(homeRows().filterNot { it.key in skip }, complete = true)) }

    /**
     * Home's rows with Continue Watching and Next Up as one row when [combineContinueNextUp]
     * (`ContinueAndNext.KEY`; the two separate rows aren't loaded then).
     */
    fun homeRowsProgress(skip: Set<String>, combineContinueNextUp: Boolean): kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress> =
        if (!combineContinueNextUp) homeRowsProgress(skip)
        else kotlinx.coroutines.flow.flow {
            val rows = homeRows()
            val merged = dev.nitrate.core.rows.ContinueAndNext.merge(
                rows.firstOrNull { it.key == "home/continue_watching" }?.items.orEmpty(),
                rows.firstOrNull { it.key == "home/next_up" }?.items.orEmpty(),
            )
            val combined = merged.takeIf { it.isNotEmpty() && dev.nitrate.core.rows.ContinueAndNext.KEY !in skip }
                ?.let { dev.nitrate.core.rows.MediaRow(dev.nitrate.core.rows.ContinueAndNext.KEY, dev.nitrate.core.rows.ContinueAndNext.TITLE, it, dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE)) }
            emit(dev.nitrate.core.rows.RowsProgress(listOfNotNull(combined) + rows.filterNot { it.key in skip || it.key == "home/continue_watching" || it.key == "home/next_up" }, complete = true))
        }

    /**
     * Only what's being watched, for things that read Home's rows in the background (the TV's
     * screensaver and home-screen channels, the phone's widgets): Continue Watching and Next Up, and
     * each library's "Latest in…" when [latest]; never the costly ones such as "Because you watched".
     */
    suspend fun watchingRows(latest: Boolean = false): List<MediaRow> =
        homeRows().filter { it.key == "home/continue_watching" || it.key == "home/next_up" || (latest && it.key.startsWith("home/latest/")) }

    /** A library's overview rows as each arrives; rows keyed in [skip] (hidden) aren't loaded. */
    fun libraryRowsProgress(library: MediaItem, skip: Set<String> = emptySet()): kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress> =
        kotlinx.coroutines.flow.flow { emit(dev.nitrate.core.rows.RowsProgress(libraryRows(library).filterNot { it.key in skip }, complete = true)) }

    /** The next [limit] items of a row from [startIndex] (what [MediaRow.more] describes); empty at its end. */
    suspend fun moreOfRow(more: dev.nitrate.core.rows.RowMore, startIndex: Int, limit: Int): List<MediaItem> = emptyList()

    /** How to continue a row made by [queryItems] with [query]; null when it can't be. */
    fun moreOfQuery(query: dev.nitrate.core.pages.ItemQuery): dev.nitrate.core.rows.RowMore? = null

    /** One page of a library for grids. Returns the items and the total count. */
    suspend fun libraryPage(library: MediaItem, sort: LibrarySort, startIndex: Int, limit: Int, includeTags: Boolean = false): Pair<List<MediaItem>, Int>
    suspend fun getItem(id: String): MediaItem
    suspend fun seasons(seriesId: String): List<MediaItem>
    suspend fun episodes(seriesId: String, seasonId: String): List<MediaItem>

    /** Every episode the library has, specials included, without placeholder "missing" items. */
    suspend fun allEpisodes(seriesId: String): List<MediaItem>
    suspend fun nextUpFor(seriesId: String): MediaItem?
    suspend fun similar(itemId: String): List<MediaItem>
    suspend fun playlistItems(playlistId: String): List<MediaItem>
    suspend fun setPlayed(itemId: String, played: Boolean)
    suspend fun setFavorite(itemId: String, favorite: Boolean)

    // ---- Images ----
    fun imageUrl(item: MediaItem, kind: ImageKind = ImageKind.PRIMARY, maxHeight: Int = 480): String?
    fun backdropUrl(item: MediaItem): String?

    /** The backdrop no wider than [maxWidth]: cards don't need full-screen art. */
    fun backdropUrl(item: MediaItem, maxWidth: Int): String? = backdropUrl(item)

    /** Every backdrop for the item (or its series), for artwork rotation. */
    fun backdropUrls(item: MediaItem): List<String>
    fun logoUrl(item: MediaItem): String?
    fun personImageUrl(person: Person): String?

    // ---- Playback ----
    /** Authorization header for requests made outside the client (the video player). */
    fun authorizationHeader(): String

    /** The item's own file, as stored on the server (a book's CBZ...); fetched with [authorizationHeader]. */
    fun fileUrl(itemId: String): String = unsupported()

    /**
     * A book's pages as the Companion plugin serves them (feature `comics`): null when it can't
     * (no plugin, or a file it doesn't read), so the app takes the whole file instead.
     */
    suspend fun comicListing(itemId: String): dev.nitrate.core.comics.ComicListing? = null

    /** Books half read, and the next issue of series read lately ([libraryId] null: every library). */
    suspend fun continueReading(libraryId: String?): List<MediaItem> = emptyList()

    /** The issues of [book]'s series in reading order ([dev.nitrate.core.comics.ComicSeries]); just [book] when it has none. */
    suspend fun seriesIssues(book: MediaItem): List<MediaItem> = listOf(book)

    /** Changes a CBZ book's ComicInfo.xml (admins, Companion feature `comics`): each field set, or removed when null. The new XML. */
    suspend fun saveComicInfo(itemId: String, changes: Map<String, String?>): String = unsupported()

    /** Page [index] of a book the plugin serves page by page; fetched with [authorizationHeader]. */
    fun comicPageUrl(itemId: String, index: Int): String = unsupported()

    /** The comic catalogs (OPDS, CLU) the server's admin set up in the Companion plugin (feature `catalogs`); empty without any. */
    suspend fun comicCatalogs(): List<dev.nitrate.core.catalogs.ComicCatalog> = emptyList()

    /** How [catalog] is reached through the server: browsing, pictures, books and reading positions. */
    fun catalogLink(catalog: dev.nitrate.core.catalogs.ComicCatalog): dev.nitrate.core.catalogs.CatalogLink = unsupported()

    /**
     * Asks the server how to play [itemId] on this device. [forceTranscode] after a local decoder
     * failure; [audioIndex]/[subtitleIndex] pick streams (null lets the server choose, subtitle -1 is off).
     * [deviceCaps] asks for another player than the device's usual one (iOS: mpv instead of Apple's).
     */
    suspend fun resolvePlayback(
        itemId: String,
        isAudio: Boolean,
        startMs: Long,
        forceTranscode: Boolean = false,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        mediaSourceId: String? = null,
        deviceCaps: dev.nitrate.core.playback.DeviceCapabilities? = null,
    ): PlaybackPlan
    suspend fun reportStart(plan: PlaybackPlan, positionMs: Long)
    suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean)

    /** Fire-and-forget: the server also stops any transcode tied to this play session. */
    fun reportStopped(plan: PlaybackPlan, positionMs: Long)

    /** Files titled "Nitrate Test …" for the audio channel test, always as the original file. */
    suspend fun channelTestClips(): List<ChannelClip>

    // ---- Administration (server admins) ----
    /** The signed-in account, including whether it's a server admin. */
    suspend fun account(): AccountInfo = unsupported()
    suspend fun metadata(itemId: String): ItemMetadata = unsupported()
    suspend fun saveMetadata(metadata: ItemMetadata): Unit = unsupported()

    /** Possible online-database matches for "Identify". */
    suspend fun remoteSearch(item: MediaItem, name: String, year: Int?): List<RemoteMatch> = remoteSearch(item, name, year, emptyMap())

    /** Identify by ID: with [providerIds] (e.g. Tmdb → 603) the providers look the title up by them. */
    suspend fun remoteSearch(item: MediaItem, name: String, year: Int?, providerIds: Map<String, String>): List<RemoteMatch> = unsupported()

    /** The IDs the server's providers accept for this title (Jellyfin's ExternalIdInfos). */
    suspend fun externalIdFields(itemId: String): List<dev.nitrate.core.admin.ExternalIdField> = emptyList()
    suspend fun applyMatch(itemId: String, match: RemoteMatch, replaceImages: Boolean): Unit = unsupported()
    suspend fun refreshItem(itemId: String, mode: RefreshMode, replaceImages: Boolean, regenerateTrickplay: Boolean): Unit = unsupported()

    /** Makes separate copies of one title versions of a single title (admins). */
    suspend fun mergeVersions(itemIds: List<String>): Unit = unsupported()

    /**
     * Reads what the library health check needs from one library (null: all): each movie and
     * show with its artwork, description, matches and files' streams, and episodes known missing.
     */
    suspend fun healthScan(libraryId: String?): dev.nitrate.core.diagnostics.HealthScan = unsupported()

    /**
     * Every film and episode the user can see (in [libraryId], or all) with its file's details, for the
     * playback overview: read a page at a time, [onProgress] (read so far, total) after each.
     */
    suspend fun playbackSources(libraryId: String?, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Pair<MediaItem, dev.nitrate.core.model.MediaSource>> = unsupported()
    suspend fun itemImages(itemId: String): List<ItemImage> = unsupported()
    suspend fun remoteImages(itemId: String, type: ImageType): List<RemoteImage> = remoteImages(itemId, type, null, allLanguages = true)

    /**
     * Images the providers have for this title: from [provider] only when given, and in the server's
     * metadata language (plus images with no language, like most backdrops) unless [allLanguages].
     */
    suspend fun remoteImages(itemId: String, type: ImageType, provider: String?, allLanguages: Boolean): List<RemoteImage> = unsupported()

    /** The image providers on the server and what they have for this title. */
    suspend fun imageProviders(itemId: String): List<dev.nitrate.core.admin.ImageProvider> = emptyList()

    /** Every library's metadata settings, as the dashboard's Manage library has them (admins). */
    suspend fun libraryMetadataSettings(): List<dev.nitrate.core.admin.LibraryMetadataSettings> = unsupported()

    /** Saves a library's metadata settings (only those parts of its options change). */
    suspend fun saveLibraryMetadataSettings(settings: dev.nitrate.core.admin.LibraryMetadataSettings): Unit = unsupported()

    /** The languages and countries the server offers for metadata. */
    suspend fun metadataLanguages(): List<dev.nitrate.core.admin.MetadataPlace> = emptyList()
    suspend fun metadataCountries(): List<dev.nitrate.core.admin.MetadataPlace> = emptyList()

    /** Moves an image of a kind that has several (backdrops) to [newIndex]; 0 is the main one. */
    suspend fun moveImage(itemId: String, type: ImageType, index: Int, newIndex: Int): Unit = unsupported()
    suspend fun downloadRemoteImage(itemId: String, type: ImageType, url: String): Unit = unsupported()
    suspend fun deleteImage(itemId: String, type: ImageType, index: Int?): Unit = unsupported()
    suspend fun uploadImage(itemId: String, type: ImageType, bytes: ByteArray, mimeType: String): Unit = unsupported()
    suspend fun scanAllLibraries(): Unit = unsupported()
    suspend fun tasks(): List<ServerTask> = unsupported()
    suspend fun startTask(taskId: String): Unit = unsupported()
    suspend fun stopTask(taskId: String): Unit = unsupported()
    suspend fun activity(limit: Int = 50): List<ActivityEntry> = unsupported()
    suspend fun sessions(): List<ServerSession> = unsupported()
    suspend fun sendMessage(sessionId: String, header: String, text: String): Unit = unsupported()
    suspend fun sessionCommand(sessionId: String, command: SessionCommand): Unit = unsupported()
    suspend fun users(): List<UserAccount> = unsupported()
    suspend fun setPermissions(userId: String, permissions: UserPermissions): Unit = unsupported()
    suspend fun createUser(name: String, password: String): UserAccount = unsupported()
    suspend fun deleteUser(userId: String): Unit = unsupported()
    suspend fun setPassword(userId: String, newPassword: String): Unit = unsupported()

    // ---- Subtitles from providers ----
    /** Subtitles the server can download for [itemId] in [language] (ISO 639-2, e.g. "eng"). */
    suspend fun searchSubtitles(itemId: String, language: String): List<RemoteSubtitle> = unsupported()
    suspend fun downloadSubtitle(itemId: String, subtitleId: String): Unit = unsupported()

    // ---- Remote control ----
    /** Tells the server this app can be controlled (play, pause, seek, messages) from elsewhere. */
    suspend fun reportCapabilities(): Unit = Unit

    /** Also say this device takes [commands] (the TV: arrows, OK, Back, Home from a phone). */
    fun acceptCommands(commands: List<String>) = Unit

    /** Starts [itemIds] on another client, from [startMs], with these tracks and version when given. */
    suspend fun playOn(
        sessionId: String, itemIds: List<String>, startMs: Long,
        audioIndex: Int? = null, subtitleIndex: Int? = null, mediaSourceId: String? = null,
    ): Unit = unsupported()
    suspend fun seekSession(sessionId: String, positionMs: Long): Unit = unsupported()
    suspend fun sendGeneralCommand(sessionId: String, command: GeneralCommand): Unit = unsupported()

    /** The signed-in user's sessions on other devices that can be controlled from here (any account type). */
    suspend fun controllableSessions(): List<ServerSession> = unsupported()

    /** Sends this device's playback summary for the household's admin (Companion feature `devices`). */
    suspend fun sendDeviceSummary(summary: dev.nitrate.core.playback.DeviceSummary): Unit = unsupported()

    /** Every household device's latest summary (admins). */
    suspend fun householdDevices(): List<dev.nitrate.core.playback.HouseholdDevice> = unsupported()

    /** Forgets one device's summary. */
    suspend fun forgetHouseholdDevice(userId: String, deviceId: String): Unit = unsupported()

    /** The Companion plugin's TV and receiver check clips (empty without the plugin). */
    suspend fun checkClips(): List<dev.nitrate.core.playback.CheckClip> = emptyList()

    /** Where to stream a check clip (with [authorizationHeader]). */
    fun checkClipUrl(id: String): String? = null

    /** What the server reports doing with this device's stream right now (null: not converting, or not known). */
    suspend fun serverConversion(): dev.nitrate.core.playback.ServerConversion? = null

    /** Switches the audio or subtitles (-1: off) playing on another device. */
    suspend fun setSessionTrack(sessionId: String, kind: dev.nitrate.core.model.StreamKind, index: Int): Unit = unsupported()

    /** A message telling Jellyfin's Chromecast receiver to play [play] (null: not signed in, or casting unsupported). */
    fun castPlay(play: dev.nitrate.core.cast.CastPlay): String? = null

    /** A control message for Jellyfin's Chromecast receiver; [value] is the seek position (ms) or volume (0–100). */
    fun castCommand(command: dev.nitrate.core.cast.CastCommand, value: Long? = null): String? = null

    /** What a message from the receiver says about playback, if anything. */
    fun castStatus(message: String): dev.nitrate.core.cast.CastStatus? = null

    /** This app's device id on the server, to leave it out of "play on" lists. */
    val deviceId: String? get() = null

    /** Live events from the server while signed in (remote control, library and watch changes). */
    fun events(): Flow<ServerEvent> = kotlinx.coroutines.flow.emptyFlow()

    // ---- Live TV ----
    /** Whether the server has Live TV set up for this user. */
    suspend fun liveTvAvailable(): Boolean = false

    /** Channels (playable items) with what's on now. */
    suspend fun liveChannels(): List<dev.nitrate.core.livetv.LiveChannel> = unsupported()

    /** Programmes on [channelIds] overlapping [fromMs, toMs). */
    suspend fun guide(channelIds: List<String>, fromMs: Long, toMs: Long): List<dev.nitrate.core.livetv.TvProgram> = unsupported()
    suspend fun recordings(): List<MediaItem> = unsupported()
    suspend fun record(programId: String): Unit = unsupported()
    suspend fun cancelRecording(timerId: String): Unit = unsupported()

    // ---- Music ----
    /** An album's tracks in disc and track order. */
    suspend fun albumTracks(albumId: String): List<MediaItem> = unsupported()

    /** An artist's albums, newest first. */
    suspend fun artistAlbums(artistId: String): List<MediaItem> = unsupported()

    /** A music library's album artists, by name, a page at a time, with the total. */
    suspend fun albumArtists(libraryId: String, startIndex: Int, limit: Int): Pair<List<MediaItem>, Int> = unsupported()

    /** Endless-radio style: tracks like a track, album or artist. */
    suspend fun instantMix(itemId: String): List<MediaItem> = unsupported()

    // ---- Collections and playlists ----
    /** Makes a collection (box set) of [itemIds]; returns its id. Needs collection permission. */
    suspend fun createCollection(name: String, itemIds: List<String>): String = unsupported()
    suspend fun addToCollection(collectionId: String, itemIds: List<String>): Unit = unsupported()
    suspend fun removeFromCollection(collectionId: String, itemIds: List<String>): Unit = unsupported()

    /** Makes a playlist of [itemIds] (audio or video); returns its id. */
    suspend fun createPlaylist(name: String, itemIds: List<String>, audio: Boolean = false): String = unsupported()
    suspend fun renamePlaylist(playlistId: String, name: String): Unit = unsupported()
    suspend fun addToPlaylist(playlistId: String, itemIds: List<String>): Unit = unsupported()

    /** Removes entries by [MediaItem.playlistEntryId] (one item can be in a playlist more than once). */
    suspend fun removeFromPlaylist(playlistId: String, entryIds: List<String>): Unit = unsupported()
    suspend fun movePlaylistItem(playlistId: String, entryId: String, newIndex: Int): Unit = unsupported()

    /** Deletes a playlist or collection (not media files). */
    suspend fun deleteList(itemId: String): Unit = unsupported()

    /** Episodes most recently added to the server, newest first, with [MediaItem.dateAddedMs]. */
    suspend fun recentEpisodes(limit: Int = 50): List<MediaItem> = unsupported()

    /** Series this user follows: favourites, and shows they're partway through (Next Up). */
    suspend fun followedSeries(): Set<String> = unsupported()

    /** The signed-in account's settings kept on the server (languages, permissions...), and the server's details. */
    suspend fun serverSettings(): dev.nitrate.core.account.ServerSettings = unsupported()

    /** Series this user has watched any of (an episode or more). */
    suspend fun watchedSeries(): Set<String> = unsupported()

    /** Signs in another device that shows a Quick Connect [code], as this user. */
    suspend fun authorizeQuickConnect(code: String): Unit = unsupported()

    // ---- SyncPlay (watch together) ----
    /** Groups this user can join. */
    // ---- Nitrate Companion (server plugin) ----

    /** People a script can be shared with; [name] finds one exactly (including hidden accounts). */
    suspend fun sharingUsers(name: String? = null): List<dev.nitrate.core.writing.UserRef> = unsupported()

    /** Scripts shared with or by this user. */
    suspend fun sharedScripts(): List<dev.nitrate.core.writing.SharedScriptInfo> = unsupported()

    /** Shares [text] as a new script owned by this user, with [members] invited. */
    suspend fun createSharedScript(text: String, members: List<String>, records: List<dev.nitrate.core.writing.story.RecordChange> = emptyList()): dev.nitrate.core.writing.SharedScriptDoc = unsupported()

    /** A shared script's text now; throws [dev.nitrate.core.writing.ScriptUnavailable] when it's not shared with this user. */
    suspend fun sharedScript(id: String): dev.nitrate.core.writing.SharedScriptDoc = unsupported()

    /** Sets who a script is shared with (its owner only). */
    suspend fun setScriptMembers(id: String, members: List<String>): dev.nitrate.core.writing.SharedScriptInfo = unsupported()

    /** Stops sharing a script with this user (they keep their own copy). */
    suspend fun leaveSharedScript(id: String): Unit = unsupported()

    /** Deletes a shared script for everyone (its owner only; everyone keeps their own copy). */
    suspend fun deleteSharedScript(id: String): Unit = unsupported()

    /** Sends a change made on [baseVersion]; taken only when that's still the latest version. */
    suspend fun sendScriptEdit(id: String, editId: String, baseVersion: Long, op: dev.nitrate.core.writing.TextOperation, cursor: Int?): dev.nitrate.core.writing.ScriptEditResult = unsupported()

    /**
     * Changes after [after] and who else is here, waiting up to [waitMs] when nothing has
     * happened since [seen]. Throws [dev.nitrate.core.writing.ScriptTooFarBehind] when those changes are gone.
     */
    suspend fun scriptEdits(id: String, after: Long, seen: Long?, waitMs: Int): dev.nitrate.core.writing.ScriptEditsPage = unsupported()

    /** Sends story data changes (they never wait on the version: each field takes the latest value). */
    suspend fun sendScriptRecords(id: String, editId: String, baseVersion: Long, records: List<dev.nitrate.core.writing.story.RecordChange>): dev.nitrate.core.writing.ScriptEditResult = unsupported()

    /** The full history of a shared script's changes from [from] on, oldest first. */
    suspend fun scriptLog(id: String, from: Long, limit: Int = 2_000): List<dev.nitrate.core.writing.ScriptEdit> = unsupported()

    /** A shared script as it was at [version] (text and story data). */
    suspend fun scriptAt(id: String, version: Long): dev.nitrate.core.writing.SharedScriptDoc = unsupported()

    /** Says where this user's cursor is in a shared script. */
    suspend fun sendScriptPresence(id: String, cursor: Int, version: Long): Unit = unsupported()

    /** The Nitrate Companion plugin on this server, or null when it isn't installed. */
    suspend fun companion(): dev.nitrate.core.companion.CompanionInfo? = null

    /** Where the Companion plugin's own updates stand (admins; feature `updates`). */
    suspend fun companionUpdates(): dev.nitrate.core.companion.CompanionUpdates = unsupported()

    /** Asks the Companion to look for a newer version of itself now and install it (admins). */
    suspend fun checkCompanionUpdates(): dev.nitrate.core.companion.CompanionUpdates = unsupported()

    /**
     * Restarts the server to finish the Companion's waiting update (admins). Fails with
     * [dev.nitrate.core.companion.RestartRefused] when nothing is waiting or something is playing.
     */
    suspend fun restartForCompanionUpdate(): Unit = unsupported()

    /** Whether this user is private (Companion feature "partners"). */
    /** The user's watch diary between two moments (either open), newest first (Companion feature `diary`). */
    suspend fun diary(fromMs: Long? = null, toMs: Long? = null): List<dev.nitrate.core.diary.DiaryEntry> = unsupported()

    /** Adds a watch by hand. */
    suspend fun logWatch(itemId: String, watchedAtMs: Long? = null, rating: Int? = null, note: String? = null): dev.nitrate.core.diary.DiaryEntry = unsupported()

    /** Changes a diary entry: rating (null keeps, 0 clears), note (null keeps, "" clears), date (null keeps). */
    suspend fun editDiary(id: String, rating: Int? = null, note: String? = null, watchedAtMs: Long? = null): dev.nitrate.core.diary.DiaryEntry = unsupported()

    suspend fun deleteDiary(id: String): Unit = unsupported()

    /** Lines said in films and episodes the user can see (Companion feature `lines`). */
    suspend fun searchLines(query: String, limit: Int = 40): List<dev.nitrate.core.lines.SpokenLine> = emptyList()

    suspend fun privacy(): dev.nitrate.core.partners.Privacy = unsupported()

    /** Makes this user private, or not (an admin's choice still holds). */
    suspend fun setPrivacy(private: Boolean): dev.nitrate.core.partners.Privacy = unsupported()

    /** People this user can ask to be watch partners: not private, not themselves. */
    suspend fun partnerPeople(): List<dev.nitrate.core.writing.UserRef> = unsupported()

    /** The user's watch groups, invites to answer and invites waiting. */
    suspend fun partners(): dev.nitrate.core.partners.PartnersInfo = unsupported()

    /**
     * Invites someone to watch together: a listed person by id, or anyone by exact user name;
     * into one of the user's groups ([groupId], which carries its titles) or a new one for
     * [titles] (else the one title [seriesId]). Always "sent".
     */
    suspend fun askPartner(
        toUserId: String?, toName: String?, seriesId: String, seriesName: String, groupId: String? = null,
        /** A new group's schedule (accepting agrees to it); ignored when inviting into a group. */
        schedule: dev.nitrate.core.partners.GroupSchedule? = null,
        /** A new group's titles (Companion feature `groupwatch`; older servers take [seriesId] alone). */
        titles: List<dev.nitrate.core.partners.GroupTitle>? = null,
        /** Invites sent together for one new group share it (Companion feature `invitebatch`): they all end up in one group. */
        batch: String? = null,
    ): dev.nitrate.core.partners.OutgoingRequest = unsupported()

    /** Adds a title to one of the user's groups (Companion feature `groupwatch`; any member may). */
    suspend fun addGroupTitle(groupId: String, titleId: String, name: String): Unit = unsupported()

    /** Removes a title from one of the user's groups (any member may; a group keeps at least one). */
    suspend fun removeGroupTitle(groupId: String, titleId: String): Unit = unsupported()

    /**
     * Marks [itemIds] watched (or, [watched] false, not) for the user in each of [groupIds]' own
     * records (Companion feature `groupwatch`): what a playback counted for
     * ([dev.nitrate.core.partners.WatchPartners.countingFor]), "All groups where it's next", or a
     * mark by hand. Never the user's own watched state nor anyone else's. Returns the groups marked.
     */
    suspend fun markForGroups(groupIds: List<String>, itemIds: List<String>, watched: Boolean = true): List<String> = unsupported()

    /** Tells the server live (SyncPlay) session [sessionId] was started for group [groupId] ("Watch now together"), so what it plays counts for the group. */
    suspend fun sessionForGroup(groupId: String, sessionId: String): Unit = unsupported()

    // ---- Group Watch Sessions (Companion feature `sessions`): every answer carries the server's clock ----

    /** The live Group Watch Sessions of the user's groups. */
    suspend fun watchSessions(): List<dev.nitrate.core.partners.GroupWatchSession> = emptyList()

    /** Starts a Group Watch Session of [groupId] on [itemId] (or joins the group's live one); the group's open apps are told at once. */
    suspend fun startWatchSession(groupId: String, itemId: String, startMs: Long = 0): dev.nitrate.core.partners.SessionJoin = unsupported()

    /** One Group Watch Session as the user sees it. */
    suspend fun watchSession(sessionId: String): dev.nitrate.core.partners.GroupWatchSession = unsupported()

    /** Joins a Group Watch Session (together while it gathers; later at where the group is, or from the beginning: [fromStart]). */
    suspend fun joinWatchSession(sessionId: String, fromStart: Boolean = false): dev.nitrate.core.partners.SessionJoin = unsupported()

    /** The starter's "Start now": the countdown starts without waiting for the rest. */
    suspend fun startWatchSessionNow(sessionId: String): dev.nitrate.core.partners.GroupWatchSession = unsupported()

    /** Where this player is (every 10 s, and at once with [event] "pause"/"play"/"seek"). */
    suspend fun reportWatchSession(sessionId: String, positionMs: Long, paused: Boolean, event: String? = null): dev.nitrate.core.partners.GroupWatchSession = unsupported()

    /** Leaves a Group Watch Session (the last one out ends it). */
    suspend fun leaveWatchSession(sessionId: String): Unit = unsupported()

    /** The starter ends the Group Watch Session for everyone. */
    suspend fun endWatchSession(sessionId: String): Unit = unsupported()

    /** Asks the group to change its schedule; it takes effect once more than half the group agrees (both, in a pair). */
    suspend fun proposeSchedule(groupId: String, schedule: dev.nitrate.core.partners.GroupSchedule): Unit = unsupported()

    /** A yes or no to the group's waiting schedule change. */
    suspend fun voteSchedule(groupId: String, changeId: String, yes: Boolean): Unit = unsupported()

    /** The group's next (or current) watch party (Companion feature `party`); null when it has no party time. One quick request. */
    suspend fun watchParty(groupId: String): dev.nitrate.core.partners.WatchParty? = null

    /** Pushes tonight's watch party back: 15 minutes at once (once per person per night), 30 or 60 asks the group. Returns the party now. */
    suspend fun partyLater(groupId: String, minutes: Int): dev.nitrate.core.partners.WatchParty? = unsupported()

    /** Asks the group to skip tonight's watch party ("Not tonight"); if it doesn't pass, the user can't make it. */
    suspend fun partySkip(groupId: String): dev.nitrate.core.partners.WatchParty? = unsupported()

    /** The user can't make tonight's watch party ([cant] false: can after all). */
    suspend fun partyCantMake(groupId: String, cant: Boolean): dev.nitrate.core.partners.WatchParty? = unsupported()

    /** A yes or no to tonight's postponement or "Not tonight" request. */
    suspend fun partyVote(groupId: String, requestId: String, yes: Boolean): dev.nitrate.core.partners.WatchParty? = unsupported()

    /** Joins tonight's watch party: the first to join starts it ([dev.nitrate.core.partners.PartyJoin.start]). */
    suspend fun partyJoin(groupId: String): dev.nitrate.core.partners.PartyJoin = unsupported()

    /** The starter tells the others' apps which live (SyncPlay) group tonight's watch party is. */
    suspend fun partySession(groupId: String, sessionId: String): Unit = unsupported()

    suspend fun acceptPartner(requestId: String): dev.nitrate.core.partners.WatchGroup = unsupported()
    suspend fun declinePartner(requestId: String): Unit = unsupported()
    suspend fun cancelPartnerRequest(requestId: String): Unit = unsupported()

    /** Leaves a watch group (one left with a single person ends). */
    suspend fun leaveGroup(groupId: String): Unit = unsupported()

    /** Where the other members of the user's groups on a show are (empty: none, or no Companion plugin). */
    /**
     * Scenes in [itemId]'s credits as the server found them (Companion feature `credits`, its
     * nightly "Find scenes in the credits"): empty = checked, none; null = not checked yet.
     */
    suspend fun serverCreditScenes(itemId: String): List<dev.nitrate.core.playback.CreditSkip>? = null

    /** Files whose timing can put sound and picture out of step (Companion feature `timing`, admins; empty without it). */
    suspend fun fileTimingIssues(): List<dev.nitrate.core.diagnostics.FileTiming> = emptyList()

    /** Invites people (from [partnerPeople]) to join watch-together group [groupId] (Companion feature `together`); always "sent". */
    suspend fun inviteToWatch(groupId: String, userIds: List<String>, itemId: String?, itemName: String?): Unit = unsupported()

    /** The signed-in user's open watch-together invites, newest first. */
    suspend fun watchInvites(): List<dev.nitrate.core.partners.WatchInvite> = emptyList()

    /** Dismisses an invite (joined or turned down). */
    suspend fun dismissWatchInvite(id: String): Unit = unsupported()

    suspend fun partnerProgress(seriesId: String): List<dev.nitrate.core.partners.PartnerProgress> = emptyList()

    /** Counts an episode in the group's record for the user and the rest of the group (who watched it with them); returns who it counted for. */
    suspend fun watchedTogether(itemId: String, groupId: String): List<String> = unsupported()

    /**
     * "We're in the same room" (Companion feature `grouprecord`): the user watched [itemIds] (parts
     * of group [groupId]'s title) with [present] (members of the group; the user counts as there).
     * They count in the group's own record for everyone there (nobody's own watched state
     * changes). The server refuses anyone who isn't in the group.
     * Returns who it counted for, the user included.
     */
    suspend fun watchedWithGroup(groupId: String, itemIds: List<String>, present: List<String>): List<String> = unsupported()

    /** The household's channels this user can watch (Companion feature "channels"); admins see all. */
    suspend fun houseChannels(): List<dev.nitrate.core.channels.HouseChannel> = unsupported()

    /** Every channel's programs between [fromMs] and [toMs]. */
    suspend fun channelGuide(fromMs: Long, toMs: Long): List<dev.nitrate.core.channels.ChannelGuide> = unsupported()

    /** Tuning in: what's on now (to play from its start) and what follows, in order, skipping what this user can't watch. */
    suspend fun tuneChannel(channelId: String, count: Int = dev.nitrate.core.channels.Channels.TUNE_COUNT): List<dev.nitrate.core.channels.ChannelProgram> = unsupported()

    /** Saves a channel (admins); a new one when its id is empty. [startOver]: every show from its beginning. */
    suspend fun saveChannel(channel: dev.nitrate.core.channels.HouseChannel, startOver: Boolean = false): dev.nitrate.core.channels.HouseChannel = unsupported()

    suspend fun deleteChannel(channelId: String): Unit = unsupported()

    /**
     * Chat messages in a watch-together group after [after] (-1: the recent ones), waiting up to
     * [waitMs] for one when there are none (long polling). Throws [dev.nitrate.core.companion.ChatForbidden]
     * when this user isn't watching in the group.
     */
    suspend fun chatMessages(groupId: String, after: Long, waitMs: Int): dev.nitrate.core.companion.ChatPage = unsupported()

    /** Sends a message to a group's chat; throws [dev.nitrate.core.companion.ChatRejected] with the reason. */
    suspend fun sendChat(groupId: String, kind: dev.nitrate.core.companion.ChatKind, text: String, positionMs: Long?): dev.nitrate.core.companion.ChatMessage = unsupported()

    suspend fun syncPlayGroups(): List<dev.nitrate.core.syncplay.SyncPlayGroup> = unsupported()
    suspend fun syncPlayCreate(name: String): Unit = unsupported()
    suspend fun syncPlayJoin(groupId: String): Unit = unsupported()
    suspend fun syncPlayLeave(): Unit = unsupported()
    suspend fun syncPlay(request: dev.nitrate.core.syncplay.SyncPlayRequest): Unit = unsupported()

    /** The server's clock, for keeping a group in step. */
    suspend fun serverTime(): dev.nitrate.core.syncplay.ServerTimeSample = unsupported()

    // ---- Custom rows ----
    /** Items matching a saved search, for custom rows. */
    suspend fun queryItems(query: ItemQuery): List<MediaItem> = unsupported()

    /** One page of a query ([ItemQuery.limit] is ignored), with how many match in all. */
    suspend fun queryPage(query: ItemQuery, startIndex: Int, limit: Int): Pair<List<MediaItem>, Int> = unsupported()

    /** A collection's (box set's) items. */
    suspend fun collectionItems(collectionId: String): List<MediaItem> = unsupported()

    /**
     * Everything the user has finished (films, episodes, songs), most recent first, with when they
     * last played each and how often: for watching stats. At most [max] items.
     */
    suspend fun watchHistory(max: Int = 20_000): List<MediaItem> = emptyList()

    /** A song's lyrics (timed or plain), or null when it has none. */
    suspend fun lyrics(itemId: String): dev.nitrate.core.music.Lyrics? = null

    /** The collections (box sets) [itemId] is in. */
    suspend fun collectionsOf(itemId: String): List<MediaItem> = emptyList()

    /**
     * The watch state of [ids] as the server has it now, in one light request (no pictures or other
     * fields, never a kept answer): for refreshing marks on a page coming back into view.
     */
    suspend fun userStates(ids: List<String>): Map<String, dev.nitrate.core.model.UserState> = emptyMap()

    /** The film after [current] in its saga ("Next in collection"), if any. */
    suspend fun nextInCollection(current: MediaItem): MediaItem? {
        if (current.kind != dev.nitrate.core.model.ItemKind.MOVIE) return null
        val sagas = collectionsOf(current.id).take(5).map { collectionItems(it.id) }
        return dev.nitrate.core.playback.NextInCollection.next(current, sagas)
    }

    /** Genres in a library (or all libraries), for building rows. */
    suspend fun genres(libraryId: String?): List<String> = unsupported()

    /** Studios with titles in a library (or all libraries), for a library's Studios tab. */
    suspend fun studios(libraryId: String?): List<String> = emptyList()

    /** All collections, for building rows. */
    suspend fun collections(): List<MediaItem> = unsupported()

    /** All playlists, for building rows. */
    suspend fun playlists(): List<MediaItem> = unsupported()

    // ---- Search ----
    /** Movies, shows, episodes, collections and people matching [query]. */
    /** The other files of a title split across several ("part 2", "part 3"...), in order. */
    suspend fun additionalParts(itemId: String): List<MediaItem> = emptyList()

    /** Theme songs for a title, or its series' when it has none of its own (played quietly on details pages). */
    suspend fun themeSongs(itemId: String): List<MediaItem> = emptyList()

    /** A title's theme music and whose it is (its own, or inherited from its series): for Manage → Details. */
    suspend fun themeMusicInfo(itemId: String): dev.nitrate.core.model.ThemeMusicInfo = dev.nitrate.core.model.ThemeMusicInfo(themeSongs(itemId), null)

    /** The address to stream an audio file as it is (with [authorizationHeader]). */
    fun audioStreamUrl(itemId: String): String = unsupported()

    /**
     * A song for the listening player: the file as it is, or converted to AAC ([convert]); fetched
     * with [authorizationHeader]. [playSessionId] ties it to its reports.
     */
    fun listenUrl(itemId: String, convert: Boolean, playSessionId: String): String = unsupported()

    /** Tells the server a song started ([start]) or how it's going, so its history, Now Playing and resume work. */
    suspend fun reportListen(itemId: String, playSessionId: String, positionMs: Long, paused: Boolean, converted: Boolean, start: Boolean) {}

    /** Tells the server a song stopped (finished, skipped or the player closed) at [positionMs]. */
    fun reportListenStopped(itemId: String, playSessionId: String, positionMs: Long) {}

    /** Where the user stands in each show they watch with a group, by series id (Companion `partners`). */
    suspend fun groupStandings(): Map<String, dev.nitrate.core.partners.KeepUp> = emptyMap()

    /**
     * What the server would play before [itemId]: its intros (Jellyfin's intro providers, such as a
     * cinema-mode plugin's trailers and pre-rolls). Empty when the server has none.
     */
    suspend fun intros(itemId: String): List<MediaItem> = emptyList()

    /** Trailers (files on the server) then extras: behind the scenes, deleted scenes, featurettes... */
    suspend fun extras(itemId: String): List<MediaItem> = emptyList()


    /** A chapter's picture, or null if it has none. */
    fun chapterImageUrl(item: MediaItem, index: Int, maxWidth: Int = 480): String? = null

    /** The genres, years, ratings and tags a library actually has, for filter choices. */
    suspend fun libraryFilters(libraryId: String?, types: List<dev.nitrate.core.model.ItemKind> = emptyList()): dev.nitrate.core.model.LibraryFilters = dev.nitrate.core.model.LibraryFilters()

    /** Whether season lists include episodes the library doesn't have (your server account's setting). */
    suspend fun showsMissingEpisodes(): Boolean = false
    suspend fun setShowMissingEpisodes(show: Boolean): Unit = unsupported()

    /**
     * Quick matches for a search being typed: names, kinds and pictures only, from the server's
     * cheap lookup, so they show at once; [search] fills in the rest (watch state and more).
     */
    suspend fun searchHints(query: String): SearchResults = search(query)

    /**
     * What a title holds, in watching order, for watching it with others: a show's episodes, a
     * playlist's items, a collection's in release order (shows in either as their episodes), or a
     * film on its own.
     */
    suspend fun titleItems(titleId: String): List<MediaItem> {
        val title = getItem(titleId)
        suspend fun expand(items: List<MediaItem>) = items.flatMap { if (it.kind == dev.nitrate.core.model.ItemKind.SERIES) allEpisodes(it.id) else listOf(it) }
        return when (title.kind) {
            dev.nitrate.core.model.ItemKind.SERIES -> allEpisodes(titleId)
            dev.nitrate.core.model.ItemKind.PLAYLIST -> expand(playlistItems(titleId))
            dev.nitrate.core.model.ItemKind.BOX_SET -> expand(collectionItems(titleId).sortedWith(compareBy({ it.premiereDateMs ?: Long.MAX_VALUE }, { it.productionYear ?: Int.MAX_VALUE })))
            else -> listOf(title)
        }
    }

    /** Titles of [kinds] whose name matches [term], from the whole library (not the quick hints, which mix in episodes). */
    suspend fun findTitles(term: String, kinds: List<dev.nitrate.core.model.ItemKind>, limit: Int = 40): List<MediaItem> =
        search(term).let { r -> (r.shows + r.movies + r.collections).filter { it.kind in kinds } }

    /**
     * What the user watched lately, most recent first: films, and shows by their episodes (Jellyfin
     * keeps no play date on a show itself), each once.
     */
    suspend fun recentlyWatched(limit: Int = 20): List<MediaItem> = emptyList()

    /**
     * The films and episodes played most recently (finished or part way), newest first, as lightly as
     * the server allows: ids, where they sit (show, season) and their watch state; no pictures or counts.
     */
    suspend fun recentlyPlayed(limit: Int = 40): List<MediaItem> = emptyList()

    /** What a playlist holds, ids and kinds only (no pictures, watch state or extra fields): for telling what's in it. */
    suspend fun playlistMembers(playlistId: String): List<MediaItem> = playlistItems(playlistId)

    suspend fun search(query: String): SearchResults = unsupported()

    /** Movies and shows [personId] appears in, newest first. */
    suspend fun itemsWithPerson(personId: String): List<MediaItem> = unsupported()

    // ---- Player extras ----
    /** Intro, recap, credits... marked on the server (empty when none, or on older servers). */
    suspend fun mediaSegments(itemId: String): List<MediaSegment> = emptyList()

    /** Scrubbing thumbnails for the item, if the server has generated them. */
    suspend fun trickplay(itemId: String, mediaSourceId: String?): TrickplayInfo? = null

    /** One thumbnail sheet (needs [authorizationHeader]). */
    fun trickplaySheetUrl(itemId: String, info: TrickplayInfo, sheet: Int): String = unsupported()

    // ---- Downloads and offline watch state ----
    /** The item's first media source, with its streams and file size, for choosing a download. */
    suspend fun mediaSource(itemId: String): MediaSource = unsupported()

    /**
     * URL of the whole file to download: the original when [transcode] is null, else a single-file
     * MP4 transcode. [sessionId] must be new per download so the server doesn't reuse another
     * transcode. Requests need [authorizationHeader].
     */
    /** [burnInSubtitle]: a subtitle stream for the server to draw into the picture (converted downloads). */
    fun downloadUrl(itemId: String, source: MediaSource, transcode: TranscodeSpec?, audioIndex: Int?, sessionId: String, burnInSubtitle: Int? = null): String = unsupported()

    /** A subtitle stream (embedded or external) as a standalone file in [format]: srt, ass or vtt. */
    fun subtitleFileUrl(itemId: String, source: MediaSource, streamIndex: Int, format: String): String = unsupported()

    /** A file carried inside the media file (a font, for styled subtitles). */
    fun attachmentUrl(itemId: String, source: MediaSource, index: Int): String = unsupported()

    /** Watched state and position as the server has it now. */
    suspend fun userData(itemId: String): RemoteUserData = unsupported()

    /** Sets watched state and position, e.g. from playback while offline, stamped [playedAtMs]. */
    suspend fun saveUserData(itemId: String, positionMs: Long, played: Boolean, playedAtMs: Long): Unit = unsupported()

    companion object {
        const val DEFAULT_PREFS = "nitrate"
    }
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("Not supported by this server")

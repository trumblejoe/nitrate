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
    /** Signs out of the current account and forgets it here (other saved accounts stay). */
    suspend fun logout()

    // ---- Saved accounts ----
    /** Every server and user signed in on this device. */
    suspend fun savedAccounts(): List<SavedAccount> = emptyList()

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

    /**
     * Asks the server how to play [itemId] on this device. [forceTranscode] after a local decoder
     * failure; [audioIndex]/[subtitleIndex] pick streams (null lets the server choose, subtitle -1 is off).
     */
    suspend fun resolvePlayback(
        itemId: String,
        isAudio: Boolean,
        startMs: Long,
        forceTranscode: Boolean = false,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        mediaSourceId: String? = null,
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
    suspend fun remoteSearch(item: MediaItem, name: String, year: Int?): List<RemoteMatch> = unsupported()
    suspend fun applyMatch(itemId: String, match: RemoteMatch, replaceImages: Boolean): Unit = unsupported()
    suspend fun refreshItem(itemId: String, mode: RefreshMode, replaceImages: Boolean, regenerateTrickplay: Boolean): Unit = unsupported()
    suspend fun itemImages(itemId: String): List<ItemImage> = unsupported()
    suspend fun remoteImages(itemId: String, type: ImageType): List<RemoteImage> = unsupported()
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

    /** Starts [itemIds] on another client, from [startMs]. */
    suspend fun playOn(sessionId: String, itemIds: List<String>, startMs: Long): Unit = unsupported()
    suspend fun seekSession(sessionId: String, positionMs: Long): Unit = unsupported()
    suspend fun sendGeneralCommand(sessionId: String, command: GeneralCommand): Unit = unsupported()

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

    /** Signs in another device that shows a Quick Connect [code], as this user. */
    suspend fun authorizeQuickConnect(code: String): Unit = unsupported()

    // ---- SyncPlay (watch together) ----
    /** Groups this user can join. */
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

    /** Genres in a library (or all libraries), for building rows. */
    suspend fun genres(libraryId: String?): List<String> = unsupported()

    /** All collections, for building rows. */
    suspend fun collections(): List<MediaItem> = unsupported()

    /** All playlists, for building rows. */
    suspend fun playlists(): List<MediaItem> = unsupported()

    // ---- Search ----
    /** Movies, shows, episodes, collections and people matching [query]. */
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
    fun downloadUrl(itemId: String, source: MediaSource, transcode: TranscodeSpec?, audioIndex: Int?, sessionId: String): String = unsupported()

    /** A subtitle stream (embedded or external) as a standalone file in [format]: srt, ass or vtt. */
    fun subtitleFileUrl(itemId: String, source: MediaSource, streamIndex: Int, format: String): String = unsupported()

    /** Watched state and position as the server has it now. */
    suspend fun userData(itemId: String): RemoteUserData = unsupported()

    /** Sets watched state and position, e.g. from playback while offline, stamped [playedAtMs]. */
    suspend fun saveUserData(itemId: String, positionMs: Long, played: Boolean, playedAtMs: Long): Unit = unsupported()

    companion object {
        const val DEFAULT_PREFS = "nitrate"
    }
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("Not supported by this server")

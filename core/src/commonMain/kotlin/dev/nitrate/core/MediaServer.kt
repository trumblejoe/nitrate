package dev.nitrate.core

import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.ImageKind
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
    suspend fun logout()

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

    // ---- Custom rows ----
    /** Items matching a saved search, for custom rows. */
    suspend fun queryItems(query: ItemQuery): List<MediaItem> = unsupported()

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

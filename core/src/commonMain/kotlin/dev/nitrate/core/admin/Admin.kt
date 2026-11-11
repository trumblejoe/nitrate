package dev.nitrate.core.admin

import dev.nitrate.core.model.MediaItem

/** The signed-in account and what it may do on the server. */
data class AccountInfo(
    val id: String,
    val name: String,
    val isAdmin: Boolean,
    val canDelete: Boolean = isAdmin,
    /** May create and edit collections (playlists are anyone's). */
    val canManageCollections: Boolean = isAdmin,
)

/** Editable metadata of one item. Dates are "YYYY-MM-DD". [lockedFields] use Jellyfin's names (Name, Overview, Genres...). */
data class ItemMetadata(
    val id: String,
    val name: String = "",
    val originalTitle: String? = null,
    val sortName: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val productionYear: Int? = null,
    val premiereDate: String? = null,
    val endDate: String? = null,
    val officialRating: String? = null,
    val communityRating: Float? = null,
    val criticRating: Float? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val people: List<PersonCredit> = emptyList(),
    /** Episode and season numbering (episodes: season in [parentIndexNumber]). */
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val airsBeforeSeason: Int? = null,
    val airsBeforeEpisode: Int? = null,
    val airsAfterSeason: Int? = null,
    val providerIds: Map<String, String> = emptyMap(),
    /** Locks the whole item against metadata refreshes. */
    val lockData: Boolean = false,
    val lockedFields: List<String> = emptyList(),
)

/** A cast or crew credit: [type] is Actor, Director, Writer, Producer, GuestStar... */
data class PersonCredit(val name: String, val role: String? = null, val type: String = "Actor")

/** A possible match from an online database, for "Identify". */
data class RemoteMatch(
    val name: String,
    val year: Int?,
    val overview: String?,
    val imageUrl: String?,
    val providerName: String?,
    val providerIds: Map<String, String>,
    /** The server's own JSON for this result, sent back unchanged to apply it. */
    internal val raw: String,
)

/** Image types an item can have. */
enum class ImageType(val wire: String, val label: String) {
    PRIMARY("Primary", "Poster"),
    BACKDROP("Backdrop", "Backdrops"),
    LOGO("Logo", "Logo"),
    THUMB("Thumb", "Thumbnail"),
    BANNER("Banner", "Banner"),
    ART("Art", "Clear art"),
    DISC("Disc", "Disc"),
}

/** An image the item has now. */
data class ItemImage(val type: ImageType, val index: Int?, val width: Int?, val height: Int?, val url: String)

/** An image the server can fetch from a provider. */
data class RemoteImage(
    val url: String,
    val thumbnailUrl: String?,
    val providerName: String?,
    val width: Int?,
    val height: Int?,
    val language: String?,
    val rating: Double?,
)

/** A scheduled task (library scan, trickplay, cleanup...). */
data class ServerTask(
    val id: String,
    val name: String,
    val category: String?,
    val description: String?,
    val state: TaskState,
    /** 0-100 while running. */
    val progress: Double?,
    val lastResult: String?,
    val lastError: String?,
    /** ISO-8601 end time of the last run. */
    val lastRunEnded: String?,
)

enum class TaskState { IDLE, RUNNING, CANCELLING }

data class ActivityEntry(
    val id: Long,
    val name: String,
    val overview: String?,
    val type: String?,
    /** ISO-8601. */
    val date: String,
    val severity: String?,
)

/** A connected client on the server. */
data class ServerSession(
    val id: String,
    val userName: String?,
    val client: String?,
    val deviceName: String?,
    val deviceId: String?,
    val nowPlaying: MediaItem?,
    val positionMs: Long?,
    val isPaused: Boolean,
    val playMethod: String?,
    val remoteAddress: String?,
    val lastActivity: String?,
    val supportsRemoteControl: Boolean,
    val supportsMediaControl: Boolean,
)

/** An account on the server, with the permissions admins usually change. */
data class UserAccount(
    val id: String,
    val name: String,
    val hasPassword: Boolean,
    val lastLogin: String?,
    val lastActivity: String?,
    val permissions: UserPermissions,
)

data class UserPermissions(
    val isAdministrator: Boolean = false,
    val isDisabled: Boolean = false,
    val isHidden: Boolean = false,
    val enableRemoteAccess: Boolean = true,
    val enableMediaPlayback: Boolean = true,
    val enableVideoTranscoding: Boolean = true,
    val enableAudioTranscoding: Boolean = true,
    val enableRemuxing: Boolean = true,
    val enableContentDownloading: Boolean = true,
    val enableContentDeletion: Boolean = false,
    val enableLiveTvAccess: Boolean = true,
    val enableCollectionManagement: Boolean = false,
    val enableSubtitleManagement: Boolean = false,
    val enableRemoteControlOfOtherUsers: Boolean = false,
    val enableAllFolders: Boolean = true,
)

/** How much a refresh redoes: new files only, missing metadata, or everything. */
enum class RefreshMode(val label: String) {
    NEW_FILES("Scan for new and changed files"),
    MISSING("Search for missing metadata"),
    REPLACE_ALL("Replace all metadata"),
}

/** Playback commands an admin can send to another client. */
enum class SessionCommand(val wire: String, val label: String) {
    STOP("Stop", "Stop"),
    PAUSE("Pause", "Pause"),
    UNPAUSE("Unpause", "Resume"),
    PLAY_PAUSE("PlayPause", "Play/pause"),
    NEXT("NextTrack", "Next"),
    PREVIOUS("PreviousTrack", "Previous"),
}

/** Non-playback commands for another client (volume, messages...), by Jellyfin's names. */
enum class GeneralCommand(val wire: String) { VOLUME_UP("VolumeUp"), VOLUME_DOWN("VolumeDown"), TOGGLE_MUTE("ToggleMute"), GO_HOME("GoHome") }

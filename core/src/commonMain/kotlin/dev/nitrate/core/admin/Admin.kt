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
    /**
     * A metadata editor's libraries (Companion feature `metadata`, `GET /Nitrate/Metadata/Me`);
     * [MetadataEditorScope.NONE] without the Companion, for admins too (they edit everything anyway).
     */
    val metadataEditor: MetadataEditorScope = MetadataEditorScope.NONE,
) {
    /** May edit metadata somewhere: an admin, or a metadata editor. */
    val editsMetadata: Boolean get() = isAdmin || metadataEditor.isEditor

    /** May edit the metadata of titles in [libraryId] (bulk editing on a library's list). */
    fun canEditMetadataIn(libraryId: String?): Boolean = isAdmin || metadataEditor.covers(libraryId)
}

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
    /** The title's pages on the providers' sites (Jellyfin's external links), name → address. */
    val links: List<Pair<String, String>> = emptyList(),
    /** Locks the whole item against metadata refreshes. */
    val lockData: Boolean = false,
    val lockedFields: List<String> = emptyList(),
    /** Where the file is on the server (read only): its folder and file names often say which film or edition it is. */
    val path: String? = null,
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

/**
 * An ID a provider on the server accepts for this kind of title (from Jellyfin's ExternalIdInfos):
 * [key] is what's stored ("Tmdb", "Imdb", "MusicBrainzAlbum"…), [name] the provider's own name.
 */
data class ExternalIdField(val key: String, val name: String, val type: String?) {
    /** "TheMovieDb", or "MusicBrainz (album)" when one provider has several kinds of ID. */
    val label get() = if (type.isNullOrBlank() || type == "Movie" || type == "Series") name else "$name ($type)"
}

/** The ID fields to show: what the providers accept, then any other IDs the title already has. */
object ExternalIds {
    fun fields(accepted: List<ExternalIdField>, existing: Map<String, String>): List<ExternalIdField> {
        val known = accepted.distinctBy { it.key }
        return known + existing.keys.filter { k -> known.none { it.key.equals(k, ignoreCase = true) } }.sorted().map { ExternalIdField(it, it, null) }
    }

    /**
     * What a pasted ID or link is: an IMDb "tt…" anywhere, a TMDb or TVDb page's number, else bare
     * digits as [digitsAre] (TMDb by default). Null when nothing recognisable is in it.
     */
    fun guess(text: String, digitsAre: String = "Tmdb"): Pair<String, String>? {
        val t = text.trim()
        Regex("tt\\d{5,}").find(t)?.let { return "Imdb" to it.value }
        Regex("themoviedb\\.org/(?:movie|tv|collection|person)/(\\d+)").find(t)?.let { return "Tmdb" to it.groupValues[1] }
        Regex("thetvdb\\.com/.*?[?&/](?:id=|series/|dereferrer/series/)(\\d+)").find(t)?.let { return "Tvdb" to it.groupValues[1] }
        return if (Regex("\\d+").matches(t)) digitsAre to t else null
    }
}

/** An image provider on the server and the kinds of image it has for this title. */
data class ImageProvider(val name: String, val types: Set<ImageType>)

/** Found images in the order worth picking from: biggest first, then best rated. */
/**
 * What a kind of image should be: its shape, and the size that looks good ([goodWidth]×[goodHeight],
 * below which it's "small": likely blocky on a TV) and the best worth having. Logos and clear art
 * are transparent PNGs of any height.
 */
data class ImageSpec(val shape: String, val goodWidth: Int, val goodHeight: Int, val bestWidth: Int, val bestHeight: Int) {
    /** "2:3 · good 1000×1500, best 2000×3000". */
    val line: String get() = "$shape · good ${goodWidth}×$goodHeight, best ${bestWidth}×$bestHeight"
}

object RemoteImages {
    fun best(images: List<RemoteImage>): List<RemoteImage> =
        images.sortedWith(compareByDescending<RemoteImage> { (it.width ?: 0).toLong() * (it.height ?: 0) }.thenByDescending { it.rating ?: 0.0 })

    /** The size guide for [type]; an episode's main image ([episode]) is a 16:9 still, not a poster. */
    fun spec(type: ImageType, episode: Boolean = false): ImageSpec = when (type) {
        ImageType.PRIMARY -> if (episode) ImageSpec("16:9", 1280, 720, 1920, 1080) else ImageSpec("2:3", 1000, 1500, 2000, 3000)
        ImageType.BACKDROP -> ImageSpec("16:9", 1920, 1080, 3840, 2160)
        ImageType.THUMB -> ImageSpec("16:9", 1280, 720, 1920, 1080)
        ImageType.LOGO -> ImageSpec("wide, transparent PNG", 800, 310, 1600, 620)
        ImageType.BANNER -> ImageSpec("about 5.4:1", 758, 140, 1000, 185)
        ImageType.ART -> ImageSpec("16:9, transparent PNG", 1000, 562, 2000, 1124)
        ImageType.DISC -> ImageSpec("1:1, transparent PNG", 1000, 1000, 1000, 1000)
    }

    /** Small for its kind (narrower than the guide's "good" width): likely to look blocky on a TV. */
    fun isSmall(type: ImageType, width: Int?, height: Int?, episode: Boolean = false): Boolean {
        val w = width ?: return false
        return w < spec(type, episode).goodWidth
    }

    /** Why it's small: "under 1000×1500"; null when it isn't (or its size isn't known). */
    fun shortfall(type: ImageType, width: Int?, height: Int?, episode: Boolean = false): String? =
        if (!isSmall(type, width, height, episode)) null else spec(type, episode).let { "under ${it.goodWidth}×${it.goodHeight}" }

    /** No language: on TMDb (and the others) an image with no words on it. */
    fun isTextless(image: RemoteImage): Boolean = image.language.isNullOrBlank()

    /** A found image's band: "TheMovieDb · Textless · 2000×3000", "Fanart · EN · 600×900 · small: under 1000×1500". */
    fun band(type: ImageType, image: RemoteImage, episode: Boolean = false): String = listOfNotNull(
        image.providerName,
        if (isTextless(image)) "Textless" else image.language?.uppercase(),
        size(image.width, image.height),
        shortfall(type, image.width, image.height, episode)?.let { "small: $it" },
    ).joinToString(" · ")

    /** "2000×3000", or null when not known. */
    fun size(width: Int?, height: Int?): String? = if (width == null || height == null) null else "${width}×$height"
}

/** Where a new image goes among a kind that holds several (backdrops). */
enum class ImagePlacement(val label: String, val note: String) {
    REPLACE_MAIN("Replace main", "Becomes the main one; the old main is deleted"),
    ADD_AS_MAIN("Add and make main", "Becomes the main one; the old main stays in the rotation"),
    ADD("Add", "Joins the rotation; the main one stays"),
}

/** Adding an image where it was asked to go (Jellyfin always adds a backdrop at the end). */
object ImagePlacing {
    /** Kinds Jellyfin keeps several of; any other kind holds one image, which a new one replaces. */
    fun holdsSeveral(type: ImageType) = type == ImageType.BACKDROP

    /**
     * Runs [add] (a download or upload of a [type] image), then moves the new image to the front
     * and drops the old main one as [placement] says. For kinds that hold one image, [add] alone
     * already replaces it.
     */
    suspend fun place(server: dev.nitrate.core.MediaServer, itemId: String, type: ImageType, placement: ImagePlacement, add: suspend () -> Unit) {
        if (!holdsSeveral(type)) return add()
        val before = server.itemImages(itemId).count { it.type == type }
        add()
        if (placement == ImagePlacement.ADD || before == 0) return
        val newest = server.itemImages(itemId).filter { it.type == type }.maxOfOrNull { it.index ?: 0 } ?: return
        if (newest > 0) server.moveImage(itemId, type, newest, 0)
        // The old main one is now second.
        if (placement == ImagePlacement.REPLACE_MAIN) server.deleteImage(itemId, type, 1)
    }
}

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
    /** Whose session it is (dashed id). */
    val userId: String? = null,
    /** The audio and subtitle streams playing there (-1: subtitles off), its volume (0–100). */
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val volume: Int? = null,
    val isMuted: Boolean = false,
    /** The general commands it says it takes ("MoveUp", "DisplayMessage"…). */
    val supportedCommands: List<String> = emptyList(),
) {
    /** It can be steered round its menus with arrows, OK, Back and Home. */
    val navigable: Boolean get() = GeneralCommand.NAVIGATION.all { it.wire in supportedCommands }

    /** The phone can open pages and titles there, and type into it (Nitrate on TV). */
    val choosable: Boolean get() = navigable && GeneralCommand.CHOOSING.all { it in supportedCommands }
}

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

/**
 * The fields Jellyfin can lock one by one against refreshes (its MetadataField names in [wire]).
 * Other fields can only be kept by locking the whole item.
 */
enum class LockableField(val wire: String, val label: String) {
    NAME("Name", "Title"),
    OVERVIEW("Overview", "Description"),
    GENRES("Genres", "Genres"),
    TAGS("Tags", "Tags"),
    STUDIOS("Studios", "Studios"),
    CAST("Cast", "Cast & crew"),
    OFFICIAL_RATING("OfficialRating", "Age rating"),
    RUNTIME("Runtime", "Runtime"),
    PRODUCTION_LOCATIONS("ProductionLocations", "Production locations"),
    ;

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }
    }
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
enum class GeneralCommand(val wire: String) {
    VOLUME_UP("VolumeUp"), VOLUME_DOWN("VolumeDown"), TOGGLE_MUTE("ToggleMute"), GO_HOME("GoHome"),
    // Moving around another app's menus (Nitrate on TV takes these as remote keys).
    MOVE_UP("MoveUp"), MOVE_DOWN("MoveDown"), MOVE_LEFT("MoveLeft"), MOVE_RIGHT("MoveRight"), SELECT("Select"), BACK("Back"),
    // Straight to a page (Nitrate on TV).
    GO_TO_SEARCH("GoToSearch");

    companion object {
        /** What a device must say it takes to be steered round its menus. */
        val NAVIGATION = listOf(MOVE_UP, MOVE_DOWN, MOVE_LEFT, MOVE_RIGHT, SELECT, BACK, GO_HOME)

        /** Jellyfin's commands for opening a library or title (with arguments: `MediaServer.showOn`). */
        const val DISPLAY_CONTENT = "DisplayContent"

        /** Jellyfin's command for typing text into the field in use (`MediaServer.typeOn`). */
        const val SEND_STRING = "SendString"

        /**
         * What a device must say it takes for the phone to choose for it: go to a page, show a
         * title, type (Nitrate on TV reports these; other apps' TVs mostly ignore them).
         */
        val CHOOSING = listOf(GO_TO_SEARCH.wire, DISPLAY_CONTENT, SEND_STRING)
    }
}

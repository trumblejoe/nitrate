package dev.nitrate.core.car

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.WatchFilter

/** One entry in the car's library: a folder to open or something to play. */
data class CarNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** The item whose picture it shows, if any. */
    val art: MediaItem? = null,
    val playable: Boolean = false,
    /** Folders the car shows as a grid of pictures rather than a list. */
    val grid: Boolean = false,
)

/** What to start: the queue and where in it, plus how far into the first one. */
data class CarQueue(val items: List<MediaItem>, val startIndex: Int = 0, val startMs: Long = 0, val shuffle: Boolean = false)

/**
 * The music and audiobooks offered to a car (Android Auto) and to voice requests: a small tree of
 * tabs (Listen now, Music, Audiobooks), each opening folders whose ids say how to fetch them, and
 * playable entries whose ids say what queue they start (a song plays on through its album or
 * playlist). Kept to what's quick to read while driving; never video.
 */
object CarLibrary {
    const val ROOT = "root"
    const val LISTEN_NOW = "now"
    const val MUSIC = "music"
    const val BOOKS = "books"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val RECENT_ALBUMS = "recent"
    const val SHUFFLE_ALL = "shuffle"

    private const val ALBUM = "album:"
    private const val ARTIST = "artist:"
    private const val PLAYLIST = "playlist:"
    private const val ARTISTS_IN = "artists:"
    /** A playable entry: "play:<context>|<itemId>", the context being the folder it was listed in. */
    private const val PLAY = "play:"

    /** How many entries a folder shows when the car doesn't page. */
    const val PAGE = 100

    val tabs = listOf(
        CarNode(LISTEN_NOW, "Listen now"),
        CarNode(MUSIC, "Music"),
        CarNode(BOOKS, "Audiobooks", grid = true),
    )

    fun playId(context: String, itemId: String) = "$PLAY$context|$itemId"

    /** A folder's entries, [page] of [pageSize] where the folder is long. */
    suspend fun children(server: MediaServer, id: String, page: Int = 0, pageSize: Int = PAGE): List<CarNode> {
        val start = page * pageSize
        return when {
            id == ROOT -> if (page == 0) tabs else emptyList()
            id == LISTEN_NOW -> if (page > 0) emptyList() else buildList {
                add(CarNode(SHUFFLE_ALL, "Shuffle all music", playable = true))
                // Books under way first: in a car it's most often "carry on".
                inProgressBooks(server).forEach { add(book(it, LISTEN_NOW)) }
                server.queryItems(ItemQuery(types = listOf(ItemKind.MUSIC_ALBUM), sort = QuerySort.DATE_ADDED, limit = 24)).forEach { add(folder(it)) }
            }
            id == MUSIC -> if (page > 0) emptyList() else listOf(
                CarNode(RECENT_ALBUMS, "Recently added", grid = true),
                CarNode(ALBUMS, "Albums", grid = true),
                CarNode(ARTISTS, "Artists"),
                CarNode(PLAYLISTS, "Playlists"),
            )
            id == RECENT_ALBUMS -> if (page > 0) emptyList()
                else server.queryItems(ItemQuery(types = listOf(ItemKind.MUSIC_ALBUM), sort = QuerySort.DATE_ADDED, limit = PAGE)).map(::folder)
            id == ALBUMS -> server.queryPage(ItemQuery(types = listOf(ItemKind.MUSIC_ALBUM), sort = QuerySort.NAME, descending = false), start, pageSize).first.map(::folder)
            id == ARTISTS -> {
                val libraries = musicLibraries(server)
                when (libraries.size) {
                    0 -> emptyList()
                    1 -> artists(server, libraries.single().id, start, pageSize)
                    else -> if (page > 0) emptyList() else libraries.map { CarNode(ARTISTS_IN + it.id, it.name) }
                }
            }
            id.startsWith(ARTISTS_IN) -> artists(server, id.removePrefix(ARTISTS_IN), start, pageSize)
            id == PLAYLISTS -> if (page > 0) emptyList() else server.playlists().filter { it.mediaKind != MediaKind.VIDEO }.map(::folder)
            id == BOOKS -> server.queryPage(ItemQuery(types = listOf(ItemKind.AUDIOBOOK), sort = QuerySort.NAME, descending = false), start, pageSize).first.map { book(it, BOOKS) }
            id.startsWith(ALBUM) -> if (page > 0) emptyList() else server.albumTracks(id.removePrefix(ALBUM)).map { song(it, id) }
            id.startsWith(PLAYLIST) -> if (page > 0) emptyList() else server.playlistItems(id.removePrefix(PLAYLIST)).filter(::listenable).map { song(it, id) }
            id.startsWith(ARTIST) -> if (page > 0) emptyList() else server.artistAlbums(id.removePrefix(ARTIST)).map(::folder)
            else -> emptyList()
        }
    }

    /** What a playable entry starts. */
    suspend fun queue(server: MediaServer, id: String): CarQueue? {
        if (id == SHUFFLE_ALL) return shuffleAll(server)
        if (!id.startsWith(PLAY)) return null
        val (context, itemId) = id.removePrefix(PLAY).split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        val around = when {
            context.startsWith(ALBUM) -> server.albumTracks(context.removePrefix(ALBUM))
            context.startsWith(PLAYLIST) -> server.playlistItems(context.removePrefix(PLAYLIST)).filter(::listenable)
            else -> emptyList()
        }
        val at = around.indexOfFirst { it.id == itemId }
        if (at >= 0) return CarQueue(around, at)
        val item = runCatching { server.getItem(itemId) }.getOrNull() ?: return null
        return single(server, item)
    }

    /** A voice request ("play … on Nitrate"): the best match, or everything shuffled when nothing was named. */
    suspend fun forVoice(server: MediaServer, query: String): CarQueue? {
        if (query.isBlank()) return shuffleAll(server)
        val found = server.findTitles(query, listOf(ItemKind.AUDIOBOOK, ItemKind.MUSIC_ALBUM, ItemKind.MUSIC_ARTIST, ItemKind.PLAYLIST, ItemKind.AUDIO), limit = 20)
        val best = bestMatch(query, found) ?: return null
        return single(server, best)
    }

    /** Search results for the car's search screen. */
    suspend fun search(server: MediaServer, query: String): List<CarNode> =
        if (query.isBlank()) emptyList()
        else server.findTitles(query, listOf(ItemKind.MUSIC_ARTIST, ItemKind.MUSIC_ALBUM, ItemKind.AUDIOBOOK, ItemKind.PLAYLIST, ItemKind.AUDIO), limit = 40)
            .mapNotNull { item ->
                when (item.kind) {
                    ItemKind.AUDIO -> song(item, item.albumId?.let { ALBUM + it } ?: "search")
                    ItemKind.AUDIOBOOK -> book(item, "search")
                    ItemKind.PLAYLIST -> item.takeIf { it.mediaKind != MediaKind.VIDEO }?.let(::folder)
                    else -> folder(item)
                }
            }

    /**
     * Which of [found] a spoken name means: an exact name first (ignoring case and "the"), then
     * one starting with it, then the server's own first answer; kinds in the server's order.
     */
    fun bestMatch(query: String, found: List<MediaItem>): MediaItem? {
        val usable = found.filter { it.kind != ItemKind.PLAYLIST || it.mediaKind != MediaKind.VIDEO }
        val q = plain(query)
        return usable.firstOrNull { plain(it.name) == q }
            ?: usable.firstOrNull { plain(it.name).startsWith(q) }
            ?: usable.firstOrNull()
    }

    private fun plain(s: String) = s.lowercase().trim().removePrefix("the ").filter { it.isLetterOrDigit() || it == ' ' }.trim()

    /** One item on its own: a song with its album around it, a whole album, playlist or artist, a book where it was left. */
    private suspend fun single(server: MediaServer, item: MediaItem): CarQueue? = when (item.kind) {
        ItemKind.AUDIO -> {
            val album = item.albumId?.let { runCatching { server.albumTracks(it) }.getOrNull() }.orEmpty()
            val at = album.indexOfFirst { it.id == item.id }
            if (at >= 0) CarQueue(album, at) else CarQueue(listOf(item))
        }
        ItemKind.AUDIOBOOK -> CarQueue(listOf(item), startMs = item.user.positionMs)
        ItemKind.MUSIC_ALBUM -> server.albumTracks(item.id).takeIf { it.isNotEmpty() }?.let { CarQueue(it) }
        ItemKind.PLAYLIST -> server.playlistItems(item.id).filter(::listenable).takeIf { it.isNotEmpty() }?.let { CarQueue(it) }
        // An artist: their songs mixed with others like them.
        ItemKind.MUSIC_ARTIST -> runCatching { server.instantMix(item.id) }.getOrNull()?.filter(::listenable)?.takeIf { it.isNotEmpty() }?.let { CarQueue(it) }
        else -> null
    }

    private suspend fun shuffleAll(server: MediaServer): CarQueue? =
        server.queryItems(ItemQuery(types = listOf(ItemKind.AUDIO), sort = QuerySort.RANDOM, limit = 200))
            .takeIf { it.isNotEmpty() }?.let { CarQueue(it) }

    private suspend fun inProgressBooks(server: MediaServer) =
        runCatching { server.queryItems(ItemQuery(types = listOf(ItemKind.AUDIOBOOK), watched = WatchFilter.IN_PROGRESS, sort = QuerySort.DATE_PLAYED, limit = 12)) }.getOrDefault(emptyList())

    private suspend fun musicLibraries(server: MediaServer) = server.views().filter { it.collection == CollectionKind.MUSIC }

    private suspend fun artists(server: MediaServer, libraryId: String, start: Int, size: Int) =
        server.albumArtists(libraryId, start, size).first.map(::folder)

    private fun listenable(item: MediaItem) = item.kind == ItemKind.AUDIO || item.kind == ItemKind.AUDIOBOOK

    private fun folder(item: MediaItem): CarNode = when (item.kind) {
        ItemKind.MUSIC_ARTIST -> CarNode(ARTIST + item.id, item.name, art = item, grid = true)
        ItemKind.PLAYLIST -> CarNode(PLAYLIST + item.id, item.name, art = item)
        else -> CarNode(ALBUM + item.id, item.name, subtitle = item.albumArtist ?: item.artists.firstOrNull(), art = item)
    }

    private fun song(item: MediaItem, context: String) =
        CarNode(playId(context, item.id), item.name, subtitle = item.artists.joinToString(", ").ifEmpty { item.albumArtist }, art = item, playable = true)

    private fun book(item: MediaItem, context: String): CarNode {
        val total = item.runtimeMs
        val left = if (total != null && item.user.positionMs > 0) dev.nitrate.core.music.Audiobooks.timeLeft(item.user.positionMs, total) else null
        return CarNode(playId(context, item.id), item.name, subtitle = left ?: item.albumArtist ?: item.artists.firstOrNull(), art = item, playable = true)
    }
}

package dev.nitrate.core.search

import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/**
 * What search looks through beyond films, shows, episodes, collections, playlists and people: only
 * the areas the user has libraries for, so a server with no music is never asked for music.
 */
enum class SearchArea(
    /** The kinds this area finds. */
    val kinds: List<ItemKind>,
) {
    MUSIC(listOf(ItemKind.MUSIC_ARTIST, ItemKind.MUSIC_ALBUM)),
    BOOKS(listOf(ItemKind.AUDIOBOOK, ItemKind.BOOK)),
    VIDEOS(listOf(ItemKind.VIDEO, ItemKind.MUSIC_VIDEO)),
    LIVE_TV(listOf(ItemKind.TV_CHANNEL)),
}

/** A song's artist in the results, with that artist's albums found. */
data class SongArtist(val name: String, val albums: List<SongAlbum>)

/** An album in the results, with its songs found (in album order). */
data class SongAlbum(val name: String, val albumId: String?, val songs: List<MediaItem>)

object SearchScope {
    /** The areas the user's [libraries] call for (mixed libraries can hold any kind of video). */
    fun areas(libraries: List<MediaItem>): Set<SearchArea> = libraries.mapNotNull { lib ->
        when (lib.collection) {
            CollectionKind.MUSIC -> SearchArea.MUSIC
            CollectionKind.BOOKS -> SearchArea.BOOKS
            CollectionKind.MUSIC_VIDEOS, CollectionKind.HOME_VIDEOS, CollectionKind.MIXED -> SearchArea.VIDEOS
            CollectionKind.LIVE_TV -> SearchArea.LIVE_TV
            else -> null
        }
    }.toSet()

    /**
     * Songs grouped as they're shown: by artist (the album's artist, else the song's first, else
     * "Unknown artist"), then album, artists and albums in the order their best match came, songs
     * in album order (disc, then track).
     */
    fun groupSongs(songs: List<MediaItem>): List<SongArtist> {
        val byArtist = LinkedHashMap<String, LinkedHashMap<String, MutableList<MediaItem>>>()
        val albumIds = HashMap<String, String?>()
        for (s in songs) {
            val artist = s.albumArtist?.takeIf { it.isNotBlank() } ?: s.artists.firstOrNull { it.isNotBlank() } ?: UNKNOWN_ARTIST
            val album = s.album?.takeIf { it.isNotBlank() } ?: NO_ALBUM
            byArtist.getOrPut(artist) { LinkedHashMap() }.getOrPut(album) { mutableListOf() } += s
            albumIds.getOrPut("$artist\u0000$album") { s.albumId }
        }
        return byArtist.map { (artist, albums) ->
            SongArtist(artist, albums.map { (album, list) ->
                SongAlbum(album, albumIds["$artist\u0000$album"], list.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.indexNumber ?: Int.MAX_VALUE })))
            })
        }
    }

    const val UNKNOWN_ARTIST = "Unknown artist"
    const val NO_ALBUM = "Singles"
}

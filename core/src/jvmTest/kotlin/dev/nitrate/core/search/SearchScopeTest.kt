package dev.nitrate.core.search

import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchScopeTest {
    private fun library(kind: CollectionKind) = MediaItem(kind.name, kind.name, ItemKind.LIBRARY, collection = kind)

    @Test
    fun onlyTheAreasTheUserHasLibrariesFor() {
        assertEquals(emptySet(), SearchScope.areas(listOf(library(CollectionKind.MOVIES), library(CollectionKind.TV_SHOWS))))
        assertEquals(
            setOf(SearchArea.MUSIC, SearchArea.BOOKS, SearchArea.VIDEOS, SearchArea.LIVE_TV),
            SearchScope.areas(listOf(library(CollectionKind.MUSIC), library(CollectionKind.BOOKS), library(CollectionKind.HOME_VIDEOS), library(CollectionKind.LIVE_TV))),
        )
        // Mixed libraries can hold any kind of video.
        assertEquals(setOf(SearchArea.VIDEOS), SearchScope.areas(listOf(library(CollectionKind.MIXED))))
    }

    private fun song(id: String, artist: String?, album: String?, track: Int?, disc: Int? = null, albumArtist: String? = artist) = MediaItem(
        id, "Song $id", ItemKind.AUDIO, album = album, albumId = album?.let { "a-$it" }, artists = listOfNotNull(artist), albumArtist = albumArtist,
        indexNumber = track, seasonNumber = disc,
    )

    @Test
    fun songsGroupByArtistThenAlbumInAlbumOrder() {
        val groups = SearchScope.groupSongs(listOf(
            song("3", "Night Owls", "Late Shift", 3),
            song("x", "Day Trippers", "Morning", 1),
            song("1", "Night Owls", "Late Shift", 1),
            song("d2", "Night Owls", "Late Shift", 1, disc = 2),
            song("s", "Night Owls", null, null),
            // A guest on someone else's album sits with the album's artist.
            song("g", "Guest", "Late Shift", 5, albumArtist = "Night Owls"),
            song("u", null, null, null, albumArtist = null),
        ))
        assertEquals(listOf("Night Owls", "Day Trippers", SearchScope.UNKNOWN_ARTIST), groups.map { it.name })
        val owls = groups.first()
        assertEquals(listOf("Late Shift", SearchScope.NO_ALBUM), owls.albums.map { it.name })
        assertEquals(listOf("1", "3", "g", "d2"), owls.albums.first().songs.map { it.id })
        assertEquals("a-Late Shift", owls.albums.first().albumId)
    }
}

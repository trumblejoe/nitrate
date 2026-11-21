package dev.nitrate.core.car

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.UserState
import dev.nitrate.core.pages.ItemQuery
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarLibraryTest {
    private val songs = (1..4).map { MediaItem("t$it", "Track $it", ItemKind.AUDIO, albumId = "al1", artists = listOf("Ana")) }
    private val book = MediaItem("bk", "The Long Reel", ItemKind.AUDIOBOOK, runtimeMs = 7_200_000, user = UserState(positionMs = 1_800_000))
    private val album = MediaItem("al1", "Silver Hours", ItemKind.MUSIC_ALBUM, albumArtist = "Ana")
    private val queries = mutableListOf<ItemQuery>()

    private val server: MediaServer = run {
        val unused = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, _ -> error("not used: ${m.name}") } as MediaServer
        object : MediaServer by unused {
            override suspend fun queryItems(query: ItemQuery): List<MediaItem> {
                queries += query
                return when (query.types.single()) {
                    ItemKind.AUDIOBOOK -> listOf(book)
                    ItemKind.MUSIC_ALBUM -> listOf(album)
                    else -> songs
                }
            }
            override suspend fun albumTracks(albumId: String) = songs
            override suspend fun getItem(id: String) = (songs + book).first { it.id == id }
            override suspend fun views() = listOf(MediaItem("mus", "Music", ItemKind.LIBRARY, collection = CollectionKind.MUSIC), MediaItem("mov", "Films", ItemKind.LIBRARY, collection = CollectionKind.MOVIES))
            override suspend fun albumArtists(libraryId: String, startIndex: Int, limit: Int) = listOf(MediaItem("ar", "Ana", ItemKind.MUSIC_ARTIST)) to 1
            override suspend fun playlists() = listOf(MediaItem("p1", "Road trip", ItemKind.PLAYLIST, mediaKind = MediaKind.AUDIO), MediaItem("p2", "Films night", ItemKind.PLAYLIST, mediaKind = MediaKind.VIDEO))
            override suspend fun findTitles(term: String, kinds: List<ItemKind>, limit: Int) =
                listOf(MediaItem("x", "Silver Hours Live", ItemKind.MUSIC_ALBUM), album, songs[2])
        }
    }

    @Test fun theRootIsThreeTabs() = runBlocking {
        assertEquals(listOf("Listen now", "Music", "Audiobooks"), CarLibrary.children(server, CarLibrary.ROOT).map { it.title })
    }

    @Test fun listenNowStartsWithShuffleThenBooksUnderWay() = runBlocking {
        val now = CarLibrary.children(server, CarLibrary.LISTEN_NOW)
        assertEquals(CarLibrary.SHUFFLE_ALL, now[0].id)
        assertEquals("The Long Reel", now[1].title)
        assertEquals("1 h 30 min left", now[1].subtitle)
        assertTrue(now[1].playable)
        assertEquals("Silver Hours", now[2].title)
        assertTrue(!now[2].playable)
    }

    @Test fun aSongPlaysOnThroughItsAlbum() = runBlocking {
        val tracks = CarLibrary.children(server, CarLibrary.children(server, CarLibrary.RECENT_ALBUMS).single().id)
        assertEquals(4, tracks.size)
        val q = CarLibrary.queue(server, tracks[2].id)!!
        assertEquals(songs, q.items)
        assertEquals(2, q.startIndex)
    }

    @Test fun aBookResumesWhereItWasLeft() = runBlocking {
        val q = CarLibrary.queue(server, CarLibrary.playId(CarLibrary.BOOKS, "bk"))!!
        assertEquals(listOf(book), q.items)
        assertEquals(1_800_000, q.startMs)
    }

    @Test fun oneMusicLibraryListsItsArtistsAndVideoPlaylistsStayOut() = runBlocking {
        assertEquals(listOf("Ana"), CarLibrary.children(server, CarLibrary.ARTISTS).map { it.title })
        assertEquals(listOf("Road trip"), CarLibrary.children(server, CarLibrary.PLAYLISTS).map { it.title })
        assertEquals(emptyList(), CarLibrary.children(server, CarLibrary.PLAYLISTS, page = 1))
    }

    @Test fun voicePrefersTheExactNameAndNothingNamedShufflesEverything() = runBlocking {
        assertEquals("al1", CarLibrary.bestMatch("the silver hours", listOf(MediaItem("x", "Silver Hours Live", ItemKind.MUSIC_ALBUM), album))?.id)
        assertEquals("x", CarLibrary.bestMatch("silver", listOf(MediaItem("x", "Silver Hours Live", ItemKind.MUSIC_ALBUM), album))?.id)
        assertNull(CarLibrary.bestMatch("x", emptyList()))
        assertEquals(songs, CarLibrary.forVoice(server, "Silver Hours")!!.items)
        assertEquals(songs, CarLibrary.forVoice(server, "")!!.items)
        assertEquals(dev.nitrate.core.pages.QuerySort.RANDOM, queries.last().sort)
    }
}

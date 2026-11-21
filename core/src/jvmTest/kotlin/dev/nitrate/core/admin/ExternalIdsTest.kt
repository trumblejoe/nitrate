package dev.nitrate.core.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalIdsTest {
    @Test fun pastedIdsAndLinksAreRecognised() {
        assertEquals("Imdb" to "tt9652782", ExternalIds.guess("https://www.imdb.com/title/tt9652782/?ref_=fn"))
        assertEquals("Tmdb" to "618355", ExternalIds.guess("https://www.themoviedb.org/movie/618355-justice-league-dark-apokolips-war"))
        assertEquals("Tvdb" to "81189", ExternalIds.guess("https://thetvdb.com/dereferrer/series/81189"))
        assertEquals("Tmdb" to "603", ExternalIds.guess(" 603 "))
        assertEquals("Tvdb" to "81189", ExternalIds.guess("81189", digitsAre = "Tvdb"))
        assertNull(ExternalIds.guess("the matrix"))
    }

    @Test fun fieldsAreWhatProvidersTakeThenWhatTheTitleHas() {
        val accepted = listOf(ExternalIdField("Tmdb", "TheMovieDb", "Movie"), ExternalIdField("Imdb", "IMDb", "Movie"), ExternalIdField("Tmdb", "TheMovieDb", "Movie"))
        val fields = ExternalIds.fields(accepted, mapOf("tmdb" to "1", "Custom" to "x"))
        assertEquals(listOf("Tmdb", "Imdb", "Custom"), fields.map { it.key })
        assertEquals("MusicBrainz (Album)", ExternalIdField("MusicBrainzAlbum", "MusicBrainz", "Album").label)
    }

    @Test fun biggestImagesFirstAndSmallOnesFlagged() {
        val small = RemoteImage("a", null, "TheTVDB", 400, 225, null, 9.0)
        val big = RemoteImage("b", null, "TheMovieDb", 3840, 2160, null, 5.0)
        assertEquals(listOf("b", "a"), RemoteImages.best(listOf(small, big)).map { it.url })
        assertEquals(true, RemoteImages.isSmall(ImageType.BACKDROP, 400, 225))
        assertEquals(false, RemoteImages.isSmall(ImageType.BACKDROP, 1920, 1080))
    }
}

class ImagePlacingTest {
    /** A title's backdrops as a list of names; Jellyfin adds at the end and moves by index. */
    private class Backdrops(vararg names: String) : dev.nitrate.core.MediaServer by stub() {
        val list = names.toMutableList()
        override suspend fun itemImages(itemId: String) = list.mapIndexed { i, _ -> ItemImage(ImageType.BACKDROP, i, 1920, 1080, "u$i") }
        override suspend fun moveImage(itemId: String, type: ImageType, index: Int, newIndex: Int) { list.add(newIndex, list.removeAt(index)) }
        override suspend fun deleteImage(itemId: String, type: ImageType, index: Int?) { list.removeAt(index!!) }
    }

    private fun run(placement: ImagePlacement): List<String> = kotlinx.coroutines.runBlocking {
        val b = Backdrops("main", "second")
        ImagePlacing.place(b, "i", ImageType.BACKDROP, placement) { b.list += "new" }
        b.list
    }

    @Test fun eachPlacementPutsTheNewBackdropWhereAsked() {
        kotlin.test.assertEquals(listOf("main", "second", "new"), run(ImagePlacement.ADD))
        kotlin.test.assertEquals(listOf("new", "main", "second"), run(ImagePlacement.ADD_AS_MAIN))
        kotlin.test.assertEquals(listOf("new", "second"), run(ImagePlacement.REPLACE_MAIN))
    }
}

private fun stub(): dev.nitrate.core.MediaServer = java.lang.reflect.Proxy.newProxyInstance(
    dev.nitrate.core.MediaServer::class.java.classLoader, arrayOf(dev.nitrate.core.MediaServer::class.java),
) { _, m, _ -> throw UnsupportedOperationException(m.name) } as dev.nitrate.core.MediaServer

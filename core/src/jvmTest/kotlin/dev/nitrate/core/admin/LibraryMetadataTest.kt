package dev.nitrate.core.admin

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryMetadataTest {
    private val offered = listOf(LibraryMetadata.Offered("TheMovieDb", true), LibraryMetadata.Offered("OMDb", true), LibraryMetadata.Offered("Fanart", false))

    @Test fun aNewKindTakesTheProvidersDefaults() {
        assertEquals(listOf("TheMovieDb" to true, "OMDb" to true, "Fanart" to false), LibraryMetadata.merge(offered, null, null).map { it.name to it.enabled })
    }

    @Test fun theSavedOrderAndChoicesWinAndNewProvidersJoinAtTheEnd() {
        val merged = LibraryMetadata.merge(offered, enabled = listOf("Fanart"), order = listOf("Fanart", "Gone", "TheMovieDb"))
        assertEquals(listOf("Fanart" to true, "TheMovieDb" to false, "OMDb" to false), merged.map { it.name to it.enabled })
    }

    @Test fun movingAndSwitching() {
        val list = LibraryMetadata.merge(offered, null, null)
        assertEquals(listOf("OMDb", "TheMovieDb", "Fanart"), LibraryMetadata.move(list, 1, -1).map { it.name })
        assertEquals(list, LibraryMetadata.move(list, 0, -1))
        assertEquals(true, LibraryMetadata.toggle(list, 2)[2].enabled)
    }
}

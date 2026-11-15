package dev.nitrate.core.admin

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkEditTest {
    private val meta = ItemMetadata(
        id = "m1", name = "Night Shift", overview = "A cook waits.", genres = listOf("Drama", "Thriller"), tags = listOf("4k"),
        studios = listOf("A24"), officialRating = "PG-13", lockedFields = listOf("Overview"), people = listOf(PersonCredit("Maya", "Lead")),
    )

    @Test fun changesOnlyWhatItSaysAndNothingElse() {
        val edit = BulkEdit(
            addGenres = setOf("drama", "Noir"), removeGenres = setOf("thriller"), addTags = setOf("Favourites"), removeTags = setOf("4K"),
            officialRating = "R", lock = setOf(LockableField.GENRES, LockableField.NAME), unlock = setOf(LockableField.OVERVIEW),
        )
        val after = edit.apply(meta)
        assertEquals(listOf("Drama", "Noir"), after.genres) // "drama" is already there (any case)
        assertEquals(listOf("Favourites"), after.tags)
        assertEquals(listOf("A24"), after.studios)
        assertEquals("R", after.officialRating)
        assertEquals(listOf("Name", "Genres"), after.lockedFields)
        // Untouched.
        assertEquals(meta.copy(genres = after.genres, tags = after.tags, officialRating = "R", lockedFields = after.lockedFields), after)
        assertEquals(null, BulkEdit(officialRating = "").apply(meta).officialRating)
        assertEquals(true, BulkEdit(lockItem = true).apply(meta).lockData)
        assertEquals(meta, BulkEdit().apply(meta))
        assertTrue(BulkEdit().isEmpty)
    }

    @Test fun saysWhatItWillDo() {
        assertEquals(
            listOf("Add genre “Noir”", "Remove tag “4K”", "Clear the age rating", "Lock genres, age rating", "Lock the whole item against refreshes"),
            BulkEdit(addGenres = setOf("Noir"), removeTags = setOf("4K"), officialRating = "", lock = setOf(LockableField.GENRES, LockableField.OFFICIAL_RATING), lockItem = true).describe(),
        )
    }

    @Test fun runsOverEachTitleSavingOnlyWhatChangedAndReportingFailures() = runBlocking {
        val saved = mutableListOf<ItemMetadata>()
        val store = mapOf("a" to meta.copy(id = "a"), "b" to meta.copy(id = "b", genres = listOf("Noir")), "c" to meta.copy(id = "c"))
        val unused = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, _ -> error("not used: ${m.name}") } as MediaServer
        val server = object : MediaServer by unused {
            override suspend fun metadata(itemId: String) = store[itemId] ?: throw IllegalStateException("Not found")
            override suspend fun saveMetadata(metadata: ItemMetadata) { saved += metadata }
        }
        val items = listOf("a", "b", "x").map { MediaItem(it, "Title $it", ItemKind.MOVIE) }
        val progress = mutableListOf<Int>()
        val result = BulkEdit(addGenres = setOf("Noir"), removeGenres = setOf("Drama", "Thriller")).run(server, items) { done, _ -> progress += done }
        assertEquals(1, result.changed)
        assertEquals(1, result.unchanged) // b already had just Noir
        assertEquals(listOf("x"), result.failed.map { it.item.id })
        assertEquals(listOf("a"), saved.map { it.id })
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test fun lockableFieldsAreJellyfinsOwnNames() {
        assertEquals(LockableField.OFFICIAL_RATING, LockableField.of("officialrating"))
        assertEquals(null, LockableField.of("SortName"))
    }
}

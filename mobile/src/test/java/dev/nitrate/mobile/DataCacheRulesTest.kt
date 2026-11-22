package dev.nitrate.mobile

import dev.nitrate.android.storage.DataCache
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The page cache: one folder per account, distinct names, and a size it stays within. */
class DataCacheRulesTest {
    private val root: File = Files.createTempDirectory("datacache").toFile()

    private fun sizeOf(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Test fun forgettingAnAccountDropsOnlyItsPages() = runBlocking {
        val cache = DataCache(root.absolutePath)
        cache.write("sam", "details/1", "Sam's", String.serializer())
        cache.write("kim", "details/1", "Kim's", String.serializer())
        cache.forget("sam")
        assertNull(cache.read("sam", "details/1", String.serializer()))
        assertEquals("Kim's", cache.read("kim", "details/1", String.serializer()))
    }

    @Test fun staysWithinItsSizeKeepingTheNewest() = runBlocking {
        val cache = DataCache(root.absolutePath, maxBytes = 20_000)
        val page = "x".repeat(1_000)
        repeat(60) { i ->
            cache.write("sam", "details/$i", page, String.serializer())
            Thread.sleep(2)
        }
        cache.prune()
        assertTrue("${sizeOf(File(root, "data"))} bytes", sizeOf(File(root, "data")) <= 20_000)
        assertEquals(page, cache.read("sam", "details/59", String.serializer()))
        assertNull(cache.read("sam", "details/0", String.serializer()))
    }

    @Test fun filesFromBeforeAccountFoldersGo() = runBlocking {
        val old = File(root, "data/1a2b3c.json").apply { parentFile.mkdirs(); writeText("old") }
        DataCache(root.absolutePath).write("sam", "rows/home", "new", String.serializer())
        assertFalse(old.exists())
    }
}

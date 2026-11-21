package dev.nitrate.android.comics

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ComicLibraryTest {
    private val cache = Files.createTempDirectory("comics").toFile()
    private val saved = mutableListOf<Triple<Long, Boolean, String>>()
    private val server = Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, args ->
        when (m.name) {
            "saveUserData" -> { saved += Triple(args[1] as Long, args[2] as Boolean, args[0] as String); Unit }
            else -> throw UnsupportedOperationException(m.name)
        }
    } as MediaServer

    private fun library(max: Long = 1L shl 30) = ComicLibrary(server, cache.path, io.ktor.client.engine.okhttp.OkHttp.create(), max) { 1_000L }

    /** A CBZ already on the device (as if fetched): three pages out of order, and ComicInfo. */
    private fun keep(id: String, extra: Int = 0) {
        val dir = File(cache, "comics/$id").apply { mkdirs() }
        ZipOutputStream(File(dir, "book").outputStream()).use { zip ->
            for (name in listOf("p10.jpg", "p2.jpg", "p1.jpg")) {
                zip.putNextEntry(ZipEntry("Issue/$name"))
                zip.write(ByteArray(10 + extra) { name.hashCode().toByte() })
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write("<ComicInfo><Series>Tides</Series><Manga>YesAndRightToLeft</Manga></ComicInfo>".toByteArray())
            zip.closeEntry()
        }
    }

    @Test fun opensPagesInOrderAndSavesWhereTheReaderIs() = runBlocking {
        keep("b1")
        val item = MediaItem("b1", "Tides 1", ItemKind.BOOK)
        val book = library().open(item)
        assertEquals(listOf("/Issue/p1.jpg", "/Issue/p2.jpg", "/Issue/p10.jpg"), book.pages)
        assertEquals("Tides", book.info?.series)
        val page = File(book.page(2))
        assertTrue(page.exists())
        assertEquals(10, page.length())
        library().saveProgress(book, 1)
        library().saveProgress(book, 2)
        // Page 2 of 3 is a position; the last page marks it read.
        assertEquals(listOf(Triple(1_000L, false, "b1"), Triple(0L, true, "b1")), saved)
    }

    @Test fun aCbrSaysWhatItNeeds() {
        val dir = File(cache, "comics/r1").apply { mkdirs() }
        File(dir, "book").writeBytes("Rar!\u001a\u0007\u0000".toByteArray())
        val e = runCatching { runBlocking { library().open(MediaItem("r1", "R", ItemKind.BOOK)) } }.exceptionOrNull()
        assertTrue(e is ComicException && e.message!!.contains("CBR"))
    }

    @Test fun keepsTheMostRecentWithinTheLimit() = runBlocking {
        keep("old", extra = 4_000)
        library().open(MediaItem("old", "Old", ItemKind.BOOK))
        File(cache, "comics/old/opened").writeText("1")
        keep("new", extra = 4_000)
        // Room for about one book (each is ~600 bytes zipped): opening the new one lets the old one go.
        library(max = 1_000).open(MediaItem("new", "New", ItemKind.BOOK))
        assertTrue(File(cache, "comics/new/book").exists())
        assertTrue(File(cache, "comics").walk().joinToString { it.relativeTo(cache).path + ":" + it.length() }, !File(cache, "comics/old").exists())
    }
}

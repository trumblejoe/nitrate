package dev.nitrate.android.comics

import dev.nitrate.core.MediaServer
import io.ktor.client.engine.mock.respond
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

    @Test fun streamsPagesFromThePluginAndReadsOfflineAfter() = runBlocking {
        var online = true
        val asked = mutableListOf<String>()
        val listing = dev.nitrate.core.comics.ComicListing(
            "rar",
            listOf(dev.nitrate.core.comics.ComicPageInfo("p1.png", 10, 20, 3), dev.nitrate.core.comics.ComicPageInfo("p2.png", 40, 20, 3), dev.nitrate.core.comics.ComicPageInfo("p3.jpg", 10, 20, 3)),
            "<ComicInfo><Series>Waves</Series></ComicInfo>",
        )
        val streaming = Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, args ->
            when (m.name) {
                "comicListing" -> if (online) listing else throw java.io.IOException("offline")
                "comicPageUrl" -> "http://server/Nitrate/Comics/${args[0]}/Pages/${args[1]}"
                "authorizationHeader" -> "MediaBrowser Token=\"t\""
                else -> throw UnsupportedOperationException(m.name)
            }
        } as MediaServer
        val engine = io.ktor.client.engine.mock.MockEngine { request ->
            asked += request.url.encodedPath
            respond(byteArrayOf(1, 2, request.url.encodedPath.last().code.toByte()), io.ktor.http.HttpStatusCode.OK)
        }
        val library = ComicLibrary(streaming, cache.path, engine) { 1_000L }
        val book = library.open(MediaItem("s1", "Waves 1", ItemKind.BOOK))
        assertEquals("Waves", book.info?.series)
        assertEquals(setOf(1), book.widePages)
        assertEquals(3, File(book.page(1)).length())
        assertEquals(listOf("/Nitrate/Comics/s1/Pages/1"), asked)
        book.fetchAll()
        assertEquals(3, asked.size)
        assertTrue(book.hasPage(0) && book.hasPage(2))
        // Offline, the kept list of pages and the pages fetched open the book again.
        online = false
        val again = library.open(MediaItem("s1", "Waves 1", ItemKind.BOOK))
        assertEquals(3, again.pageCount)
        assertTrue(File(again.page(2)).exists())
        assertEquals(3, asked.size)
    }

    @Test fun catalogBooksStreamAndRememberWhereTheReaderIs() = runBlocking {
        val saved = mutableListOf<Pair<Int, Int>>()
        val catalog = dev.nitrate.core.catalogs.ComicCatalog("k", "Komga", "http://k/opds", token = "t")
        val client = object : dev.nitrate.core.catalogs.CatalogClient {
            override val catalog = catalog
            override val authorization = "Bearer t"
            override suspend fun root() = throw UnsupportedOperationException()
            override suspend fun open(target: String) = throw UnsupportedOperationException()
            override suspend fun search(query: String) = null
            override suspend fun saveProgress(book: dev.nitrate.core.catalogs.CatalogBook, page: Int, pageCount: Int): Boolean { saved += page to pageCount; return true }
        }
        val positions = object : CatalogPositions {
            val map = mutableMapOf<String, Int>()
            override suspend fun get(catalogId: String, bookId: String) = map["$catalogId|$bookId"]
            override suspend fun set(catalogId: String, bookId: String, page: Int) { map["$catalogId|$bookId"] = page }
        }
        val asked = mutableListOf<String>()
        val engine = io.ktor.client.engine.mock.MockEngine { request ->
            asked += request.url.toString() + " " + request.headers["Authorization"]
            respond(byteArrayOf(9, 9), io.ktor.http.HttpStatusCode.OK)
        }
        val library = ComicLibrary(server, cache.path, engine) { 1_000L }
        val book = dev.nitrate.core.catalogs.CatalogBook("urn:b/1", "Saga 1", pageCount = 5, lastRead = 2, pageTemplate = "http://k/pages/{pageNumber}?w={maxWidth}")
        val open = library.openCatalog(client, book, positions)
        assertEquals(2, open.startPage)
        assertEquals("Saga 1", open.title)
        open.page(3)
        assertEquals(listOf("http://k/pages/3?w=2000 Bearer t"), asked)
        open.saveProgress(3)
        assertEquals(listOf(3 to 5), saved)
        // Next time it starts where this device left off.
        assertEquals(3, library.openCatalog(client, book, positions).startPage)
    }
}

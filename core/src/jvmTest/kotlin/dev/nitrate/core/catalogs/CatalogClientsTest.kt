package dev.nitrate.core.catalogs

import dev.nitrate.core.comics.ComicListing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogClientsTest {
    private val asked = mutableListOf<String>()

    /** The server's side of a catalog, answered from [pages] (the longest matching start wins). */
    private fun link(catalog: ComicCatalog, pages: Map<String, String>) = object : CatalogLink {
        override val catalog = catalog
        override val authorization = "MediaBrowser Token=\"t\""
        override suspend fun text(url: String): String {
            asked += url
            return pages[url] ?: pages.entries.filter { url.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
                ?: throw CatalogException("The catalog has nothing at that address (404).")
        }
        override fun fetchUrl(url: String) = "proxy:$url"
        override suspend fun book(url: String): ComicListing? = null
        override fun pageUrl(url: String, index: Int) = "proxy-book:$url#$index"
        override fun fileUrl(url: String) = "proxy-file:$url"
        override suspend fun position(bookId: String): Int? = null
        override suspend fun savePosition(bookId: String, page: Int) {}
    }

    @Test fun opdsBrowsesAndSearches() = runBlocking {
        val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Komga</title>
            <link rel="search" type="application/opensearchdescription+xml" href="/opds/v1.2/search"/>
            <entry><title>Libraries</title><id>l</id><link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="/opds/v1.2/libraries"/></entry>
            <entry><title>Saga 1</title><id>b1</id>
              <link rel="http://opds-spec.org/acquisition" type="application/vnd.comicbook+zip" href="/opds/v1.2/books/b1/file"/>
              <link rel="http://vaemendis.net/opds-pse/stream" type="image/jpeg" href="/opds/v1.2/books/b1/pages/{pageNumber}?zero_based=true" count="40"/>
            </entry></feed>"""
        val desc = """<OpenSearchDescription><Url type="application/atom+xml;profile=opds-catalog" template="/opds/v1.2/search?search={searchTerms}"/></OpenSearchDescription>"""
        val c = catalogClient(link(ComicCatalog("k", "Komga", "http://k/opds/v1.2/catalog"), mapOf("http://k/opds/v1.2/catalog" to feed, "http://k/opds/v1.2/search" to desc, "http://k/opds/v1.2/search?search=saga" to feed)))
        val root = c.root()
        assertTrue(root.searchable)
        assertEquals("http://k/opds/v1.2/libraries", root.items[0].target)
        val book = root.items[1].book!!
        assertEquals(40, book.pageCount)
        assertEquals("http://k/opds/v1.2/books/b1/file", book.fileUrl)
        val found = c.search("saga")!!
        assertEquals("“saga”", found.title)
        assertTrue(asked.last().endsWith("/opds/v1.2/search?search=saga"))
    }

    @Test fun aRefusalComesThroughInWords() = runBlocking {
        val e = assertFailsWith<CatalogException> { catalogClient(link(ComicCatalog("k", "K", "http://k/opds"), emptyMap())).root() }
        assertTrue(e.message!!.contains("404"))
    }

    @Test fun cluBrowsesIssues() = runBlocking {
        val issues = """{"items":[{"id":12,"name":"Saga 001.cbz","path":"/comics/Image/Saga/Saga 001.cbz","has_progress":true,"last_page":7},
            {"id":13,"name":"Saga 002.cbz","path":"/comics/Image/Saga/Saga 002.cbz","has_progress":false,"last_page":null}],
            "total":3,"page":1,"page_size":2,"total_pages":2,"has_more":true}"""
        val series = """{"items":[{"value":"Saga","name":"Saga","count":3}],"page":1,"has_more":false}"""
        val c = catalogClient(link(ComicCatalog("c", "CLU", "http://clu:5577/", CatalogKind.CLU), mapOf(
            "http://clu:5577/api/v1/library/issues" to issues,
            "http://clu:5577/api/v1/library/series" to series,
        )))
        assertEquals(5, c.root().items.size)
        val s = c.open("series?publisher=Image")
        assertEquals("issues?series=Saga&publisher=Image", s.items.single().target)
        val page = c.open(s.items.single().target!!)
        assertEquals("issues?series=Saga&publisher=Image&page=2", page.next)
        val first = page.items[0].book!!
        assertEquals("Saga 001", first.title)
        assertEquals(7, first.lastRead)
        assertEquals("http://clu:5577/api/v1/issue/12/download", first.fileUrl)
        assertEquals(null, page.items[1].book!!.lastRead)
    }
}

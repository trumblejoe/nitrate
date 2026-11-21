package dev.nitrate.core.catalogs

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogClientsTest {
    private val asked = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val auths = mutableListOf<String?>()

    private fun client(pages: Map<String, String>) = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        asked += "${request.method.value} $url"
        auths += request.headers[HttpHeaders.Authorization]
        if (request.method != HttpMethod.Get) bodies += request.body.toByteArray().decodeToString()
        val body = pages[url] ?: pages.entries.firstOrNull { url.startsWith(it.key) }?.value
        if (body == null) respond("", HttpStatusCode.NotFound) else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
    })

    @Test fun opdsBrowsesAndSearchesWithBasicAuth() = runBlocking {
        val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Komga</title>
            <link rel="search" type="application/opensearchdescription+xml" href="/opds/v1.2/search"/>
            <entry><title>Libraries</title><id>l</id><link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="/opds/v1.2/libraries"/></entry>
            <entry><title>Saga 1</title><id>b1</id>
              <link rel="http://opds-spec.org/acquisition" type="application/vnd.comicbook+zip" href="/opds/v1.2/books/b1/file"/>
              <link rel="http://vaemendis.net/opds-pse/stream" type="image/jpeg" href="/opds/v1.2/books/b1/pages/{pageNumber}?zero_based=true" count="40"/>
            </entry></feed>"""
        val desc = """<OpenSearchDescription><Url type="application/atom+xml;profile=opds-catalog" template="/opds/v1.2/search?search={searchTerms}"/></OpenSearchDescription>"""
        val http = client(mapOf("http://k/opds/v1.2/catalog" to feed, "http://k/opds/v1.2/search" to desc, "http://k/opds/v1.2/search?search=saga" to feed))
        val c = catalogClient(ComicCatalog("k", "Komga", "http://k/opds/v1.2/catalog", user = "ann", password = "pw"), http)
        val root = c.root()
        assertTrue(root.searchable)
        assertEquals("http://k/opds/v1.2/libraries", root.items[0].target)
        val book = root.items[1].book!!
        assertEquals(40, book.pageCount)
        assertEquals("http://k/opds/v1.2/books/b1/file", book.fileUrl)
        assertEquals("Basic YW5uOnB3", auths.first())
        val found = c.search("saga")!!
        assertEquals("“saga”", found.title)
        assertTrue(asked.last().endsWith("/opds/v1.2/search?search=saga"))
    }

    @Test fun opdsSaysWhenTheSignInIsRefused() = runBlocking {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        val e = assertFailsWith<CatalogException> { catalogClient(ComicCatalog("k", "K", "http://k/opds"), http).root() }
        assertTrue(e.message!!.contains("sign-in"))
    }

    @Test fun cluBrowsesIssuesAndSavesWhereTheReaderIs() = runBlocking {
        val issues = """{"items":[{"id":12,"name":"Saga 001.cbz","path":"/comics/Image/Saga/Saga 001.cbz","has_progress":true,"last_page":7},
            {"id":13,"name":"Saga 002.cbz","path":"/comics/Image/Saga/Saga 002.cbz","has_progress":false,"last_page":null}],
            "total":3,"page":1,"page_size":2,"total_pages":2,"has_more":true}"""
        val series = """{"items":[{"value":"Saga","name":"Saga","count":3}],"page":1,"has_more":false}"""
        val http = client(mapOf(
            "http://clu:5577/api/v1/library/issues" to issues,
            "http://clu:5577/api/v1/library/series" to series,
            "http://clu:5577/api/v1/progress" to """{"page_number":3}""",
            "http://clu:5577/api/v1/issues/read" to """{"ok":true}""",
        ))
        val c = catalogClient(ComicCatalog("c", "CLU", "http://clu:5577/", CatalogKind.CLU, token = "tok"), http)
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
        assertEquals("Bearer tok", auths.last())
        assertTrue(c.saveProgress(first, 3, 20))
        assertTrue(bodies.last().contains("\"path\":\"/comics/Image/Saga/Saga 001.cbz\"") && bodies.last().contains("\"page_number\":3"))
        c.saveProgress(first, 19, 20)
        assertTrue(asked.last().startsWith("POST http://clu:5577/api/v1/issues/read"))
    }
}

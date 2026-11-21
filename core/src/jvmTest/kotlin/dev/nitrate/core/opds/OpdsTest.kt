package dev.nitrate.core.opds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpdsTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<!-- a catalog -->
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:pse="http://vaemendis.net/opds-pse/ns" xmlns:opds="http://opds-spec.org/2010/catalog">
  <title>Series &amp; more</title>
  <link rel="next" href="?page=2" type="application/atom+xml;profile=opds-catalog"/>
  <link rel="search" href="/opds/search.xml" type="application/opensearchdescription+xml"/>
  <entry>
    <title>Marvel</title>
    <id>urn:pub:1</id>
    <link rel="subsection" href="publishers/1" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
  </entry>
  <entry>
    <title>Saga 001</title>
    <id>urn:book:7</id>
    <author><name>B. K. V.</name></author>
    <summary type="html">&lt;p&gt;Two lovers&lt;br/&gt;on the run&lt;/p&gt;</summary>
    <link rel="http://opds-spec.org/image/thumbnail" href="/covers/7?size=small" type="image/jpeg"/>
    <link rel="http://opds-spec.org/acquisition" href="/dl/7.cbr" type="application/x-cbr"/>
    <link rel="http://opds-spec.org/acquisition/open-access" href="/dl/7.cbz" type="application/x-cbz"/>
    <link rel="http://vaemendis.net/opds-pse/stream" type="image/jpeg" href="/opds/stream/7?page={pageNumber}&amp;width={maxWidth}" pse:count="24" pse:lastRead="6"/>
  </entry>
</feed>"""

    @Test fun readsSectionsBooksAndPaging() {
        val f = Opds.parse(feed, "https://comics.home/opds/browse?x=1")
        assertEquals("Series & more", f.title)
        assertEquals("https://comics.home/opds/browse?page=2", f.next)
        assertEquals("https://comics.home/opds/search.xml", f.search)
        val (section, book) = f.entries
        assertFalse(section.isBook)
        assertEquals("https://comics.home/opds/publishers/1", section.navigation?.href)
        assertTrue(book.isBook)
        assertNull(book.navigation)
        assertEquals("B. K. V.", book.author)
        assertEquals("Two lovers\non the run", book.summary)
        assertEquals("https://comics.home/dl/7.cbz", book.acquisitions.first().href)
        assertEquals("https://comics.home/covers/7?size=small", book.cover)
        val stream = book.stream!!
        assertEquals(24, stream.count)
        assertEquals(6, stream.lastRead)
        assertEquals("https://comics.home/opds/stream/7?page=3&width=1600", Opds.pageUrl(stream, 3, 1600))
    }

    @Test fun searchFromAnOpenSearchDescription() {
        val desc = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <Url type="text/html" template="/web?q={searchTerms}"/>
            <Url type="application/atom+xml;profile=opds-catalog" template="/opds/search?q={searchTerms}&amp;p={startPage?}"/>
        </OpenSearchDescription>"""
        val t = Opds.searchTemplate(desc, "https://c.home/opds/search.xml")!!
        assertEquals("https://c.home/opds/search?q=spider%20man&p=", Opds.searchUrl(t, "spider man"))
        assertTrue(Opds.isTemplate(t))
    }

    @Test fun linksResolveLikeABrowser() {
        val base = "http://h:8080/a/b/c?x=1"
        assertEquals("http://h:8080/a/b/d", Opds.resolve(base, "d"))
        assertEquals("http://h:8080/a/d", Opds.resolve(base, "../d"))
        assertEquals("http://h:8080/z?q=1", Opds.resolve(base, "/z?q=1"))
        assertEquals("http://h:8080/a/b/c?y=2", Opds.resolve(base, "?y=2"))
        assertEquals("https://other/x", Opds.resolve(base, "https://other/x"))
        assertEquals("http://cdn/x", Opds.resolve(base, "//cdn/x"))
    }

    @Test fun xmlCopesWithCdataAndReferences() {
        val e = Xml.parse("<a x='1' y=\"&#65;&#x42;\"><![CDATA[<b>raw</b>]]><c/>tail</a>")
        assertEquals("AB", e.attr("y"))
        assertEquals("<b>raw</b>tail", e.text)
        assertEquals("c", e.children.single().name)
    }
}

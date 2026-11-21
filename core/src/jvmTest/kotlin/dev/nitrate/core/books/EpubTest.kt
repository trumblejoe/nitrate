package dev.nitrate.core.books

import kotlin.test.Test
import kotlin.test.assertEquals

class EpubTest {
    private val opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Moby-Dick</dc:title><dc:creator>Herman Melville</dc:creator></metadata>
  <manifest>
    <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  </manifest>
  <spine page-progression-direction="ltr"><itemref idref="nav" linear="no"/><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""

    @Test fun readsThePackage() {
        assertEquals("OEBPS/content.opf", Epub.packagePath("""<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""))
        val book = Epub.parsePackage(opf, "OEBPS/content.opf") { if (it.endsWith("ch2.xhtml")) 300 else 100 }
        assertEquals("Moby-Dick", book.title)
        assertEquals("Herman Melville", book.author)
        assertEquals(listOf("OEBPS/text/ch 1.xhtml", "OEBPS/text/ch2.xhtml"), book.chapters.map { it.path })
    }

    @Test fun placesAndProgressGoBothWays() {
        val book = EpubPackage("t", null, listOf(EpubChapter("a", 100), EpubChapter("b", 300)))
        assertEquals(0.625, Epub.progression(book, EpubPlace(1, 0.5)))
        assertEquals(EpubPlace(1, 0.5), Epub.place(book, 0.625))
        assertEquals(EpubPlace(0, 0.5), Epub.place(book, 0.125))
        assertEquals(EpubPlace(1, 0.25), Epub.decode(Epub.encode(EpubPlace(1, 0.25))))
        assertEquals(null, Epub.decode("{\"href\":\"x\"}"))
    }
}

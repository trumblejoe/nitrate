package dev.nitrate.core.writing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Checks the pure-Kotlin ZIP/inflate against java.util.zip, which iOS can't use. */
class ZipTest {
    private val samples = listOf(
        ByteArray(0),
        "hello".encodeToByteArray(),
        "INT. ROOM - DAY\n".repeat(5_000).encodeToByteArray(), // very compressible: long back-references
        Random(7).nextBytes(70_000), // incompressible: stored blocks inside deflate
        (0 until 50_000).map { (it * 31 % 251).toByte() }.toByteArray(),
    )

    @Test
    fun `java reads what we write`() {
        val zip = Zip.write(samples.mapIndexed { i, b -> "f$i.bin" to b })
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            samples.forEachIndexed { i, expected ->
                assertEquals("f$i.bin", z.nextEntry!!.name)
                assertArrayEquals(expected, z.readBytes())
            }
        }
    }

    @Test
    fun `we read what java writes, at every compression level`() {
        for (level in listOf(Deflater.NO_COMPRESSION, Deflater.BEST_SPEED, Deflater.DEFAULT_COMPRESSION, Deflater.BEST_COMPRESSION)) {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                z.setLevel(level)
                samples.forEachIndexed { i, b -> z.putNextEntry(ZipEntry("f$i.bin")); z.write(b); z.closeEntry() }
            }
            val read = Zip.read(out.toByteArray())
            assertEquals(samples.size, read.size)
            samples.forEachIndexed { i, expected -> assertArrayEquals("level $level entry $i", expected, read[i].second) }
        }
    }

    @Test
    fun `crc matches java`() {
        for (b in samples) assertEquals(java.util.zip.CRC32().apply { update(b) }.value, Crc32.of(b))
    }

    @Test
    fun `xml handles entities, cdata, comments and doctype without resolving it`() {
        val doc = Xml.parse(
            """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><!-- c -->
               <document a="1 &amp; 2"><text bold='1'>Tom &amp; Jerry &#233;&#x1F600;</text><![CDATA[<raw>]]><e/></document>""",
        )
        assertEquals("1 & 2", doc.attr("a"))
        assertEquals("Tom & Jerry é😀", doc.firstElement("text")!!.text)
        assertEquals("1", doc.firstElement("text")!!.attr("bold"))
        assert(doc.text.contains("<raw>"))
    }
}

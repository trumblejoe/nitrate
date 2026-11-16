package dev.nitrate.core.downloads

import kotlinx.io.files.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** A converted download (fragmented MP4, as the server streams it) gets an index so it can seek. */
class Mp4IndexTest {
    private fun fixture(): File {
        val src = javaClass.getResource("/downloads/converted-fragmented.mp4")!!.readBytes()
        return File.createTempFile("converted", ".mp4").apply { writeBytes(src); deleteOnExit() }
    }

    private data class TopBox(val type: String, val offset: Int, val size: Int)

    private fun boxes(b: ByteArray): List<TopBox> {
        val out = mutableListOf<TopBox>()
        var i = 0
        while (i + 8 <= b.size) {
            val size = ByteBuffer.wrap(b, i, 4).int
            out += TopBox(String(b, i + 4, 4), i, size)
            i += size
        }
        return out
    }

    @Test fun `an unindexed conversion gets an index, and every fragment still finds its data`() {
        val file = fixture()
        val before = file.readBytes()
        assertEquals(Mp4Index.Status.FRAGMENTED_NO_INDEX, Mp4Index.status(Path(file.path)))

        assertTrue(Mp4Index.addIndex(Path(file.path)))
        assertEquals(Mp4Index.Status.INDEXED, Mp4Index.status(Path(file.path)))

        val after = file.readBytes()
        val top = boxes(after)
        // ftyp, moov, then the index, then the fragments; the old random-access box is gone.
        assertEquals(listOf("ftyp", "moov", "sidx"), top.take(3).map { it.type })
        assertFalse(top.any { it.type == "mfra" })
        val moofs = top.filter { it.type == "moof" }
        assertEquals(4, moofs.size)

        // The index: one reference per fragment, sizes covering each moof + mdat, durations adding
        // up to the 4 seconds, each starting on a keyframe.
        val sidx = top.first { it.type == "sidx" }
        val s = ByteBuffer.wrap(after, sidx.offset, sidx.size)
        s.position(sidx.offset + 12)
        s.int // track
        val timescale = s.int
        s.long; s.long; s.short
        val count = s.short.toInt()
        assertEquals(4, count)
        var duration = 0L
        val sizes = (0 until count).map {
            val size = s.int
            duration += s.int.toLong() and 0xFFFFFFFFL
            assertEquals(0x90000000.toInt(), s.int)
            size
        }
        assertEquals(moofs.map { m -> top.dropWhile { it !== m }.drop(1).takeWhile { it.type == "mdat" }.sumOf { it.size } + m.size }, sizes)
        assertEquals(4.0, duration.toDouble() / timescale, 0.1)

        // Each track fragment's absolute data offset moved with its moof (the server writes them
        // pointing at the moof itself).
        for (m in moofs) {
            var i = m.offset + 8
            while (i < m.offset + m.size) {
                val size = ByteBuffer.wrap(after, i, 4).int
                if (String(after, i + 4, 4) == "traf") {
                    val tfhd = i + 8
                    assertEquals("tfhd", String(after, tfhd + 4, 4))
                    assertEquals(m.offset.toLong(), ByteBuffer.wrap(after, tfhd + 16, 8).long)
                }
                i += size
            }
        }
        // Media bytes are untouched: the file is the old one plus the index, less the mfra.
        val oldMfra = boxes(before).first { it.type == "mfra" }.size
        assertEquals(before.size + sidx.size - oldMfra, after.size)
    }

    @Test fun `indexing twice, or anything that isn't an unindexed conversion, changes nothing`() {
        val file = fixture()
        assertTrue(Mp4Index.addIndex(Path(file.path)))
        val once = file.readBytes()
        assertFalse(Mp4Index.addIndex(Path(file.path)))
        assertTrue(once.contentEquals(file.readBytes()))

        val notMp4 = File.createTempFile("subs", ".srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n"); deleteOnExit() }
        assertEquals(Mp4Index.Status.NOT_MP4, Mp4Index.status(Path(notMp4.path)))
        assertFalse(Mp4Index.addIndex(Path(notMp4.path)))
    }
}

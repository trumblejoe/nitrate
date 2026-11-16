package dev.nitrate.mobile

import dev.nitrate.android.playback.DolbyVisionProfile7
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import dev.nitrate.core.playback.Profile7Playback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class DolbyVisionProfile7Test {
    /** VPS, SPS and PPS of a real HEVC Main 10 1080p stream (x265), as Media3 keeps them. */
    private val hevcMain10 = hex("0000000140010c01ffff02200000030090000003000003007895980900000001420101022000000300900000030000030078a003c08010e4d96566924caf016808000003000800000300c040000000014401c172b46240")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun dolbyVision(codecs: String) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs)
        .setWidth(3840).setHeight(2160).setInitializationData(listOf(hevcMain10)).build()

    // A frame as Media3 hands it over: a slice, the enhancement layer (type 63), the RPU (type 62).
    private val slice = hex("2601af1234")
    private val el = hex("7e01aabbcc")
    private val rpu = hex("7c01190800")
    private val frame = byteArrayOf(0, 0, 0, 1) + slice + byteArrayOf(0, 0, 1) + el + byteArrayOf(0, 0, 0, 1) + rpu

    /** Stands in for libdovi: marks the RPU as converted. */
    private val fakeConvert: (ByteArray) -> ByteArray? = { nal -> nal + byteArrayOf(0x81.toByte()) }

    @Test
    fun `profile 7 becomes its HEVC Main 10 base layer`() {
        val base = DolbyVisionProfile7.baseLayer(dolbyVision("dvhe.07.06"))
        assertEquals(MimeTypes.VIDEO_H265, base.sampleMimeType)
        assertEquals("hvc1.2", base.codecs!!.split('.').take(2).joinToString("."))
        assertEquals(3840, base.width)
    }

    @Test
    fun `profile 7 can be announced as profile 8 at the same level`() {
        assertEquals("dvhe.08.06", DolbyVisionProfile7.asProfile8(dolbyVision("dvhe.07.06")).codecs)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, DolbyVisionProfile7.asProfile8(dolbyVision("dvhe.07.06")).sampleMimeType)
    }

    @Test
    fun `other formats are left alone`() {
        for (codecs in listOf("dvhe.08.06", "dvhe.05.06", "dvav.09.05")) {
            val f = dolbyVision(codecs)
            assertSame(f, DolbyVisionProfile7.baseLayer(f))
            assertSame(f, DolbyVisionProfile7.asProfile8(f))
        }
    }

    @Test
    fun `a frame loses its enhancement layer and gets its RPU rewritten`() {
        val out = DolbyVisionProfile7.rewriteFrame(frame, fakeConvert)
        val expected = byteArrayOf(0, 0, 0, 1) + slice + byteArrayOf(0, 0, 0, 1) + rpu + byteArrayOf(0x81.toByte())
        assertArrayEquals(expected, out)
    }

    @Test
    fun `an RPU that can't be converted is dropped, and non-Annex B data is left alone`() {
        var failed = 0
        val out = DolbyVisionProfile7.rewriteFrame(frame, { null }) { failed++ }
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + slice, out)
        assertEquals(1, failed)
        val lengthPrefixed = byteArrayOf(0, 0, 0, 5) + slice
        assertSame(lengthPrefixed, DolbyVisionProfile7.rewriteFrame(lengthPrefixed, fakeConvert))
    }

    /** An extractor that announces a video track and an audio track, then writes [frames] to the video one. */
    private class Announcing(val video: Format, val frames: List<ByteArray>, val chunk: Int) : Extractor {
        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) {
            val v = output.track(0, C.TRACK_TYPE_VIDEO)
            v.format(video)
            output.track(1, C.TRACK_TYPE_AUDIO).format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build())
            output.endTracks()
            frames.forEachIndexed { i, f ->
                // Written in pieces, as extractors do.
                f.toList().chunked(chunk).forEach { piece -> v.sampleData(ParsableByteArray(piece.toByteArray()), piece.size) }
                v.sampleMetadata(i * 41_708L, if (i == 0) C.BUFFER_FLAG_KEY_FRAME else 0, f.size, 0, null)
            }
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    private class Recording : ExtractorOutput {
        val formats = mutableListOf<Format>()
        val samples = mutableListOf<Pair<Long, ByteArray>>()
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            var buffer = ByteArray(0)
            override fun format(format: Format) { formats += format }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = length
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val b = ByteArray(length); data.readBytes(b, 0, length); buffer += b
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                samples += timeUs to buffer.copyOfRange(buffer.size - offset - size, buffer.size - offset)
                buffer = buffer.copyOfRange(buffer.size - offset, buffer.size)
            }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    private fun play(mode: Profile7Playback, chunk: Int = 3): Recording {
        val inner = object : ExtractorsFactory {
            override fun createExtractors() = arrayOf<Extractor>(Announcing(dolbyVision("dvhe.07.06"), listOf(frame, frame), chunk))
        }
        val output = Recording()
        DolbyVisionProfile7({ mode }, inner, toProfile81 = fakeConvert).createExtractors().single().init(output)
        return output
    }

    @Test
    fun `converted to 8_1, every frame comes out rewritten and on time`() {
        val out = play(Profile7Playback.AS_PROFILE_8)
        assertEquals(listOf("dvhe.08.06", null), out.formats.map { it.codecs })
        assertEquals(listOf(0L, 41_708L), out.samples.map { it.first })
        val expected = DolbyVisionProfile7.rewriteFrame(frame, fakeConvert)
        out.samples.forEach { (_, bytes) -> assertArrayEquals(expected, bytes) }
    }

    @Test
    fun `as the base layer or as Dolby Vision, frames pass through untouched`() {
        val base = play(Profile7Playback.BASE_LAYER)
        assertEquals(MimeTypes.VIDEO_H265, base.formats.first().sampleMimeType)
        base.samples.forEach { assertArrayEquals(frame, it.second) }
        val dv = play(Profile7Playback.DOLBY_VISION)
        assertEquals("dvhe.07.06", dv.formats.first().codecs)
        dv.samples.forEach { assertArrayEquals(frame, it.second) }
    }
}

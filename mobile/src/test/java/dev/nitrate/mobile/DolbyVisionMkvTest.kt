package dev.nitrate.mobile

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import dev.nitrate.android.playback.DolbyVisionProfile7
import dev.nitrate.core.playback.DolbyVisionLayers
import dev.nitrate.core.playback.Profile7Playback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The playback test's profile 7 MKV clips through Media3's own Matroska extractor and
 * [DolbyVisionProfile7]: what the decoder gets. Dolby Vision MKVs declare block additions (their
 * dvcC), so Media3 hands every frame over as [main size][main][extra]; frames like that once went
 * through untouched, and a FEL film showed black with sound.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class DolbyVisionMkvTest {
    private class Result(val format: Format, val nalTypes: Set<Int>, val frames: Int, val layers: DolbyVisionLayers?)

    private fun play(clip: String, mode: Profile7Playback): Result {
        val file = File("../tv/src/main/assets/testclips/$clip")
        var format: Format? = null
        val types = sortedSetOf<Int>()
        var frames = 0
        var layers: DolbyVisionLayers? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int) = object : TrackOutput {
                var buffer = ByteArray(0)
                override fun format(f: Format) { if (type == C.TRACK_TYPE_VIDEO) format = f }
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val b = ByteArray(length)
                    val read = input.read(b, 0, length)
                    if (read > 0) buffer += b.copyOf(read)
                    return read
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    val b = ByteArray(length); data.readBytes(b, 0, length); buffer += b
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    var frame = buffer.copyOfRange(buffer.size - offset - size, buffer.size - offset)
                    buffer = buffer.copyOfRange(buffer.size - offset, buffer.size)
                    if (type != C.TRACK_TYPE_VIDEO) return
                    frames++
                    if (flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA != 0) {
                        val main = ParsableByteArray(frame).readInt()
                        frame = frame.copyOfRange(4, 4 + main)
                    }
                    DolbyVisionProfile7.nalUnits(frame)!!.forEach { (from, _) -> types += (frame[from].toInt() shr 1) and 0x3F }
                }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val factory = DolbyVisionProfile7({ mode }, DefaultExtractorsFactory(), toProfile81 = { it }, onLayers = { l, _ -> layers = l })
        fun open(at: Long): DefaultExtractorInput {
            val source = FileDataSource()
            val length = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(at).build())
            return DefaultExtractorInput(source, at, length)
        }
        var input = open(0)
        val extractor = factory.createExtractors(Uri.fromFile(file), emptyMap()).first {
            runCatching { it.sniff(input) }.getOrDefault(false).also { input.resetPeekPosition() }
        }
        extractor.init(output)
        val position = PositionHolder()
        while (true) {
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> input = open(position.position)
            }
        }
        return Result(format!!, types, frames, layers)
    }

    @Test
    fun `as its base layer, neither the enhancement layer nor the metadata reach the decoder`() {
        for (clip in listOf("dv_p7_mel_2160p.mkv", "dv_p7_fel_2160p.mkv")) {
            val r = play(clip, Profile7Playback.BASE_LAYER)
            assertEquals(MimeTypes.VIDEO_H265, r.format.sampleMimeType)
            assertEquals(48, r.frames)
            assertTrue("$clip: ${r.nalTypes}", 62 !in r.nalTypes && 63 !in r.nalTypes && r.nalTypes.any { it < 32 })
            // Measured here too (the clips' enhancement layer is a still picture, so even the FEL
            // one is small after its first frame: which kind isn't checked).
            assertTrue(r.layers != null)
        }
    }

    @Test
    fun `converted, the enhancement layer is gone and the metadata stays`() {
        for (clip in listOf("dv_p7_mel_2160p.mkv", "dv_p7_fel_2160p.mkv")) {
            val r = play(clip, Profile7Playback.AS_PROFILE_8)
            assertEquals("dvhe.08.06", r.format.codecs)
            assertTrue("$clip: ${r.nalTypes}", 62 in r.nalTypes && 63 !in r.nalTypes)
        }
    }

    @Test
    fun `as Dolby Vision, everything reaches the decoder`() {
        val r = play("dv_p7_fel_2160p.mkv", Profile7Playback.DOLBY_VISION)
        assertEquals("dvhe.07.06", r.format.codecs)
        assertTrue(r.nalTypes.toString(), 62 in r.nalTypes && 63 in r.nalTypes)
    }
}

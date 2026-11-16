package dev.nitrate.android.playback

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DolbyVisionBaseLayerTest {
    /** VPS, SPS and PPS of a real HEVC Main 10 1080p stream (x265), as Media3 keeps them. */
    private val hevcMain10 = ("0000000140010c01ffff02200000030090000003000003007895980900000001420101022000000300900000030000030078a003c08010e4d96566924caf016808000003000800000300c040000000014401c172b46240")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun dolbyVision(codecs: String) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs)
        .setWidth(3840).setHeight(2160).setInitializationData(listOf(hevcMain10)).build()

    @Test
    fun `profile 7 becomes its HEVC Main 10 base layer`() {
        val base = DolbyVisionBaseLayer.baseLayer(dolbyVision("dvhe.07.06"))
        assertEquals(MimeTypes.VIDEO_H265, base.sampleMimeType)
        // HEVC Main 10 (profile 2), so the device's decoder is checked for the right profile.
        assertEquals("hvc1.2", base.codecs!!.split('.').take(2).joinToString("."))
        assertEquals(3840, base.width)
        assertEquals(listOf(hevcMain10.toList()), base.initializationData.map { it.toList() })
    }

    @Test
    fun `other Dolby Vision profiles and other formats are left alone`() {
        for (codecs in listOf("dvhe.08.06", "dvhe.05.06", "dvav.09.05")) {
            val f = dolbyVision(codecs)
            assertSame(f, DolbyVisionBaseLayer.baseLayer(f))
        }
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()
        assertSame(hevc, DolbyVisionBaseLayer.baseLayer(hevc))
    }

    /** An extractor that announces one profile 7 video track and one audio track. */
    private class Announcing(val video: Format) : Extractor {
        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) {
            output.track(0, C.TRACK_TYPE_VIDEO).format(video)
            output.track(1, C.TRACK_TYPE_AUDIO).format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build())
            output.endTracks()
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    private class Recording : ExtractorOutput {
        val formats = mutableListOf<Format>()
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) { formats += format }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = length
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {}
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    private fun announced(baseLayerOnly: Boolean): List<Format> {
        val inner = object : ExtractorsFactory {
            override fun createExtractors() = arrayOf<Extractor>(Announcing(dolbyVision("dvhe.07.06")))
        }
        val output = Recording()
        DolbyVisionBaseLayer({ baseLayerOnly }, inner).createExtractors().single().init(output)
        return output.formats
    }

    @Test
    fun `the player sees the base layer only where profile 7 can't play as Dolby Vision`() {
        assertEquals(listOf(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_E_AC3), announced(baseLayerOnly = true).map { it.sampleMimeType })
        assertEquals(listOf(MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.AUDIO_E_AC3), announced(baseLayerOnly = false).map { it.sampleMimeType })
    }
}

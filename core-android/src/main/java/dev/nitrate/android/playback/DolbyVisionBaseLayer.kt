package dev.nitrate.android.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Dual-layer Dolby Vision (profile 7, from UHD Blu-ray) where the device can't play it as Dolby
 * Vision: the track is announced as what its base layer is, HDR10 HEVC, so the device's HEVC
 * decoder plays it (decoders skip the enhancement layer and Dolby Vision's metadata). Media3 has
 * no such fallback for profile 7 (it has one for profiles 8 and 9), so without this the video
 * wouldn't play at all. Everything else passes through untouched.
 */
@OptIn(UnstableApi::class)
class DolbyVisionBaseLayer(
    /** True while profile 7 should play as its base layer (read when each file is opened). */
    private val baseLayerOnly: () -> Boolean,
    private val inner: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = inner.createExtractors().map(::Wrapped).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        inner.createExtractors(uri, responseHeaders).map(::Wrapped).toTypedArray()

    // Subtitle parsing and the rest are set on the real factory.
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory =
        apply { inner.setSubtitleParserFactory(subtitleParserFactory) }

    @Deprecated("Media3 deprecated")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory =
        apply { @Suppress("DEPRECATION") inner.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled) }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory =
        apply { inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory =
        apply { inner.setParseHagcMetadata(parseHagcMetadata) }

    private inner class Wrapped(private val extractor: Extractor) : Extractor {
        override fun sniff(input: ExtractorInput) = extractor.sniff(input)
        override fun getSniffFailureDetails() = extractor.sniffFailureDetails
        override fun init(output: ExtractorOutput) {
            val strip = baseLayerOnly()
            extractor.init(if (strip) BaseLayerOutput(output) else output)
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = extractor.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)
        override fun release() = extractor.release()
        override fun getUnderlyingImplementation() = extractor.underlyingImplementation
    }

    private class BaseLayerOutput(private val output: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = BaseLayerTrack(output.track(id, type))
        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
    }

    private class BaseLayerTrack(private val track: TrackOutput) : TrackOutput {
        override fun format(format: Format) = track.format(baseLayer(format))
        override fun durationUs(durationUs: Long) = track.durationUs(durationUs)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) =
            track.sampleData(input, length, allowEndOfInput, sampleDataPart)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = track.sampleData(data, length, sampleDataPart)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    companion object {
        /** Profile 7 Dolby Vision as its HEVC base layer; any other format as it is. */
        fun baseLayer(format: Format): Format {
            if (!isProfile7(format)) return format
            val hevcCodecs = runCatching { NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData) }.getOrNull()
            return format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(hevcCodecs).build()
        }

        /** "dvhe.07.06" (or dvh1.07...): dual-layer Dolby Vision. */
        fun isProfile7(format: Format): Boolean =
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
                format.codecs?.let { it.startsWith("dvhe.07") || it.startsWith("dvh1.07") } == true
    }
}

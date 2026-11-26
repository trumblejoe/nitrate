package dev.nitrate.android.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
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
import dev.nitrate.core.playback.Profile7Playback

/**
 * Dual-layer Dolby Vision (profile 7, from UHD Blu-ray) for devices that can't decode it as it is.
 * Media3 has no fallback for profile 7 (it has one for profiles 8 and 9), so this sits between
 * the file and the decoder:
 *
 * - [Profile7Playback.AS_PROFILE_8]: each frame's Dolby Vision metadata (RPU) is rewritten as
 *   profile 8.1 by libdovi and the enhancement layer is dropped, so the device's profile 8
 *   decoder shows it as Dolby Vision.
 * - [Profile7Playback.BASE_LAYER]: the track is announced as what its base layer is, HDR10 HEVC,
 *   and each frame's enhancement layer and metadata are dropped. A plain HEVC decoder can't use
 *   them, so the picture is the same, but some (MediaTek's) show nothing at all when a full
 *   enhancement layer (FEL) reaches them.
 * - [Profile7Playback.DOLBY_VISION]: untouched. Everything other than profile 7 passes through.
 */
@OptIn(UnstableApi::class)
class DolbyVisionProfile7(
    /** How profile 7 plays (read when each file is opened). */
    private val playback: () -> Profile7Playback,
    private val inner: ExtractorsFactory = DefaultExtractorsFactory(),
    /** Rewrites one RPU NAL unit (without its start code) as profile 8.1; null when it can't. */
    private val toProfile81: (ByteArray) -> ByteArray? = dev.nitrate.dovi.RpuConverter::toProfile81,
    /** Told how many frames' metadata couldn't be rewritten, now and then. */
    private val onFailures: (Int) -> Unit = {},
    /** Told once per file which kind of profile 7 it is (MEL or FEL), from its first frames' enhancement layer. */
    private val onLayers: (dev.nitrate.core.playback.DolbyVisionLayers, averageBytesPerFrame: Long) -> Unit = { _, _ -> },
    /** Told how a profile 7 track is played as it opens (for the playback log). */
    private val onProfile7: (Profile7Playback) -> Unit = {},
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
            // Wrapped in every mode (as Dolby Vision, frames pass untouched) so the log says the way.
            extractor.init(Profile7Output(output, playback()))
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = extractor.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)
        override fun release() = extractor.release()
        override fun getUnderlyingImplementation() = extractor.underlyingImplementation
    }

    private inner class Profile7Output(private val output: ExtractorOutput, private val mode: Profile7Playback) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = Profile7Track(output.track(id, type), mode)
        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
    }

    /**
     * Passes everything through until the track turns out to be profile 7. Then, unless the device
     * decodes profile 7 itself, each frame is gathered whole and sent on rewritten (converted, or
     * as its base layer alone).
     */
    private inner class Profile7Track(private val track: TrackOutput, private val mode: Profile7Playback) : TrackOutput {
        private var rewrite = false
        private var pending = ByteArray(256 * 1024)
        private var pendingSize = 0
        private var failures = 0
        // The enhancement layer's size over the first frames that have one (MEL or FEL), said once.
        private var layerFrames = 0
        private var layerBytes = 0L
        private var layersSaid = false

        override fun format(format: Format) {
            val profile7 = isProfile7(format)
            if (profile7) onProfile7(mode)
            rewrite = profile7 && mode != Profile7Playback.DOLBY_VISION
            track.format(
                when {
                    !profile7 -> format
                    mode == Profile7Playback.AS_PROFILE_8 -> asProfile8(format)
                    mode == Profile7Playback.BASE_LAYER -> baseLayer(format)
                    else -> format
                },
            )
        }

        override fun durationUs(durationUs: Long) = track.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (!rewrite) return track.sampleData(input, length, allowEndOfInput, sampleDataPart)
            ensure(length)
            val read = input.read(pending, pendingSize, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            pendingSize += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (!rewrite) return track.sampleData(data, length, sampleDataPart)
            ensure(length)
            data.readBytes(pending, pendingSize, length)
            pendingSize += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!rewrite) return track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            // The frame is the [size] bytes ending [offset] bytes before what's gathered so far
            // (anything after it belongs to the next frame and stays gathered).
            val end = pendingSize - offset
            val start = end - size
            val frame = pending.copyOfRange(start, end)
            val converting = mode == Profile7Playback.AS_PROFILE_8
            val rewrite: (ByteArray) -> ByteArray = { main ->
                rewriteFrame(main, if (converting) toProfile81 else DROP, onFailure = { if (converting) failures++ }, onEnhancementLayer = { bytes ->
                    if (!layersSaid) {
                        layerFrames++
                        layerBytes += bytes
                        if (layerFrames >= dev.nitrate.core.playback.DolbyVisionLayers.FRAMES) {
                            layersSaid = true
                            onLayers(dev.nitrate.core.playback.DolbyVisionLayers.of(layerBytes / layerFrames), layerBytes / layerFrames)
                        }
                    }
                })
            }
            val out = when {
                // Encrypted: sent on as it is.
                cryptoData != null -> frame
                // Every frame of a Matroska file that declares block additions (as Dolby Vision MKVs
                // do, for their dvcC) comes as [main size][main][extra]: the main part is rewritten.
                flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA != 0 -> withSupplemental(frame, rewrite)
                else -> rewrite(frame)
            }
            track.sampleData(ParsableByteArray(out), out.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            track.sampleMetadata(timeUs, flags, out.size, 0, cryptoData)
            // Keep what follows the frame (the next frame's first bytes, if any).
            pending.copyInto(pending, 0, end, pendingSize)
            pendingSize -= end
            if (failures > 0 && failures % 240 == 1) onFailures(failures)
        }

        private fun ensure(more: Int) {
            if (pendingSize + more > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + more))
        }
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** The base layer alone: the metadata goes with the enhancement layer. */
        val DROP: (ByteArray) -> ByteArray? = { null }
        private const val NAL_RPU = 62
        private const val NAL_ENHANCEMENT_LAYER = 63

        /** "dvhe.07.06" (or dvh1.07...): dual-layer Dolby Vision. */
        fun isProfile7(format: Format): Boolean =
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
                format.codecs?.let { it.startsWith("dvhe.07") || it.startsWith("dvh1.07") } == true

        /** Profile 7 Dolby Vision as its HEVC base layer; any other format as it is. */
        fun baseLayer(format: Format): Format {
            if (!isProfile7(format)) return format
            val hevcCodecs = runCatching { NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData) }.getOrNull()
            return format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(hevcCodecs).build()
        }

        /** Profile 7 announced as profile 8 (8.1: HDR10-compatible base layer), same level. */
        fun asProfile8(format: Format): Format {
            if (!isProfile7(format)) return format
            val codecs = format.codecs!!.split('.').toMutableList().also { it[1] = "08" }.joinToString(".")
            return format.buildUpon().setCodecs(codecs).build()
        }

        /**
         * A frame carrying extra data, as Media3 hands it over: its main part's size (4 bytes, big
         * endian), the main part, then the extra data. The main part goes through [rewrite] and the
         * size is set to match; a frame that doesn't add up is left as it is.
         */
        fun withSupplemental(frame: ByteArray, rewrite: (ByteArray) -> ByteArray): ByteArray {
            if (frame.size < 4) return frame
            val mainSize = ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
                ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            if (mainSize < 0 || 4 + mainSize > frame.size) return frame
            val main = rewrite(frame.copyOfRange(4, 4 + mainSize))
            val n = main.size
            return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) +
                main + frame.copyOfRange(4 + mainSize, frame.size)
        }

        /**
         * One frame (Annex B: NAL units after start codes) with its enhancement layer dropped and
         * its RPU rewritten by [convert]. An RPU that can't be rewritten is dropped too (that frame
         * then shows as HDR10). A frame that isn't Annex B is left as it is.
         */
        fun rewriteFrame(
            frame: ByteArray,
            convert: (ByteArray) -> ByteArray?,
            /** The frame's enhancement-layer bytes (told only when it has some). */
            onEnhancementLayer: (Int) -> Unit = {},
            onFailure: () -> Unit = {},
        ): ByteArray {
            val units = nalUnits(frame) ?: return frame
            val out = java.io.ByteArrayOutputStream(frame.size)
            var layer = 0
            for ((from, to) in units) {
                if (to - from < 2) continue
                when ((frame[from].toInt() shr 1) and 0x3F) {
                    NAL_ENHANCEMENT_LAYER -> { layer += to - from; continue }
                    NAL_RPU -> {
                        val converted = convert(frame.copyOfRange(from, to))
                        if (converted == null) { onFailure(); continue }
                        out.write(START_CODE)
                        out.write(converted)
                    }
                    else -> { out.write(START_CODE); out.write(frame, from, to - from) }
                }
            }
            if (layer > 0) onEnhancementLayer(layer)
            return out.toByteArray()
        }

        /** Where each NAL unit's bytes are (after its start code), or null when [b] isn't Annex B. */
        fun nalUnits(b: ByteArray): List<Pair<Int, Int>>? {
            fun startCodeAt(i: Int) = i + 2 < b.size && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1
            if (!(startCodeAt(0) || (b.size > 3 && b[0].toInt() == 0 && startCodeAt(1)))) return null
            val starts = mutableListOf<Int>()
            var i = 0
            while (i + 2 < b.size) {
                if (startCodeAt(i)) { starts += i + 3; i += 3 } else i++
            }
            return starts.mapIndexed { k, s ->
                var end = if (k + 1 < starts.size) starts[k + 1] - 3 else b.size
                // A 4-byte start code's leading zero belongs to it, not to the unit before.
                if (k + 1 < starts.size && end > s && b[end - 1].toInt() == 0) end--
                s to end
            }
        }
    }
}

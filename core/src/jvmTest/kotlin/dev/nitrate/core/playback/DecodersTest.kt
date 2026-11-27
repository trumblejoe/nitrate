package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecodersTest {
    @Test fun whoDecodesInPlainWordsWithTheNameInBrackets() {
        assertEquals("This device's hardware (OMX.MTK.VIDEO.DECODER.AV1)", Decoders.describe("OMX.MTK.VIDEO.DECODER.AV1"))
        assertEquals("Android's software decoder (c2.android.opus.decoder)", Decoders.describe("c2.android.opus.decoder"))
        // Nitrate's FFmpeg: the sound decoder's Media3 name, and the picture decoder's label with its codec.
        assertEquals("Nitrate's FFmpeg (ffmpeg6.0-opus)", Decoders.describe("ffmpeg6.0-opus"))
        assertEquals("Nitrate's FFmpeg (hevc)", Decoders.describe("FFmpeg (software) hevc"))
        assertEquals("Nitrate's FFmpeg", Decoders.describe("FFmpeg (software)"))
        assertEquals(DecoderTier.NITRATE_SOFTWARE, Decoders.tierOf("nitrate.ffmpeg.vc1"))
    }

    @Test fun soundPassedThroughAndWhatADeviceReports() {
        assertEquals(Decoders.PASSED_THROUGH, Decoders.describeSound("c2.dolby.eac3.decoder", passedThrough = true))
        assertNull(Decoders.describeSound(null, passedThrough = false))
        assertEquals(
            "Android's software decoder (c2.android.opus.decoder) for stereo; Nitrate's FFmpeg for 5.1 and 7.1",
            Decoders.describeReported("c2.android.opus.decoder for stereo; FFmpeg (software) for 5.1 and 7.1"),
        )
    }

    @Test fun settingsSayWhoDecodesEachFormatAndWhatTheServerDoes() {
        val base = caps(video = listOf(video("hevc")), audio = mapOf("eac3" to true))
        val c = base.copy(audio = base.audio.mapValues { (_, a) -> a.copy(passthrough = true) })
        assertEquals("This device's hardware (c2.vendor.hevc)", Decoders.decodedBy(c, "hevc"))
        assertEquals("This device's hardware (c2.eac3), or sent untouched to the TV or receiver", Decoders.decodedBy(c, "eac3"))
        assertNull(Decoders.decodedBy(c, "range:HDR10"))
        assertEquals("server converts the sound", Decoders.serverConverts("opus"))
        assertEquals("server converts the picture", Decoders.serverConverts("av1"))
    }

    @Test fun mpvSaysWhoDecodes() {
        val gpu = MpvDecoding.read(mapOf("hwdec-current" to "d3d11va", "video-format" to "hevc", "audio-codec-name" to "eac3", "audio-out-params/format" to "floatp")::get)
        assertEquals("This computer's graphics card (d3d11va, hevc)", Decoders.mpvPicture(gpu, "This computer's graphics card"))
        assertEquals("mpv's FFmpeg (eac3)", Decoders.mpvSound(gpu))
        val cpu = MpvDecoding(hwdec = "no", videoCodec = "av1", audioCodec = "truehd", audioOut = "spdif-truehd")
        assertEquals("mpv's software decoder (FFmpeg, av1)", Decoders.mpvPicture(cpu, "This computer's graphics card"))
        assertEquals("Sent untouched to the TV or receiver (spdif-truehd)", Decoders.mpvSound(cpu))
        // Nothing known yet: no row rather than a guess.
        assertNull(Decoders.mpvPicture(MpvDecoding(), "This device's hardware"))
    }

    @Test fun whatTheServerDoes() {
        fun plan(method: PlayMethod, reasons: List<String> = emptyList()) =
            PlaybackPlan("i", "u", method, "s", MediaSource("m", "mkv", null, emptyList()), isHls = false, audioIndex = null, subtitleIndex = -1, externalSubtitles = emptyList(), transcodeReasons = reasons)
        assertEquals("Plays as it is", Decoders.serverWords(plan(PlayMethod.DIRECT_PLAY)))
        assertEquals("Repackaged (nothing converted)", Decoders.serverWords(plan(PlayMethod.DIRECT_STREAM)))
        assertEquals("Converting the sound only (the picture passes through)", Decoders.serverWords(plan(PlayMethod.TRANSCODE, listOf("AudioCodecNotSupported"))))
        assertEquals("Converting picture and sound", Decoders.serverWords(plan(PlayMethod.TRANSCODE, listOf("VideoCodecNotSupported", "AudioCodecNotSupported"))))
    }
}

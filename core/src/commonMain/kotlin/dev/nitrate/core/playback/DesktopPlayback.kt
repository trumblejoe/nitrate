package dev.nitrate.core.playback

/**
 * What the desktop app's player (libmpv, LGPL build: FFmpeg's decoders, D3D11/DXVA2 or NVDEC
 * hardware decoding where the PC has it, libplacebo for HDR and Dolby Vision, libass for styled
 * subtitles) plays, so the server sends files as they are and converts nothing a PC can show.
 *
 * Every codec counts as "hardware": a PC either decodes it on the GPU or has the CPU to decode it
 * in software at full size (and mpv deinterlaces), so none is held to the phones' 1080p
 * progressive software limit. Every HDR kind is allowed whatever the screen: mpv shows HDR on an
 * HDR display and tone-maps it itself on an SDR one, better than converting on the server.
 * Dolby Vision profiles 5 and 8 are reshaped by libplacebo; profile 7 plays its HDR10 base layer.
 */
object DesktopPlayback {
    /** Codecs a receiver can take untouched over S/PDIF or HDMI when bitstreaming is on (mpv's `audio-spdif`). */
    val BITSTREAM_CODECS = listOf("ac3", "eac3", "dts", "truehd")

    /** The biggest picture trusted to mpv (8K; the GPU or CPU decides the rest). */
    const val MAX_WIDTH = 7680
    const val MAX_HEIGHT = 4320

    /**
     * The desktop's capabilities. [bitstream] holds the codecs passed through to a receiver
     * (subset of [BITSTREAM_CODECS]); empty decodes everything on the PC (the default).
     */
    fun capabilities(model: String, displayWidth: Int, displayHeight: Int, bitstream: Set<String> = emptySet()): DeviceCapabilities {
        val video = DeviceCapabilities.VIDEO_CODECS.mapValues { (codec, mime) ->
            VideoCodecCaps(
                codec = codec,
                mime = mime,
                decoderName = "mpv",
                hardware = true,
                maxWidth = MAX_WIDTH,
                maxHeight = MAX_HEIGHT,
                // MPEG-2, MPEG-4 Part 2 and VC-1 have no 10-bit; everything else does (Hi10P H.264 too).
                tenBit = codec !in EIGHT_BIT_ONLY,
            )
        }
        val audio = DeviceCapabilities.AUDIO_CODECS.keys.associateWith { codec ->
            AudioCodecCaps(codec, decoder = "mpv", passthrough = codec in bitstream && codec in BITSTREAM_CODECS)
        }
        return DeviceCapabilities(
            video = video,
            audio = audio,
            hdr = DeviceCapabilities.HDR_FORMATS.toSet(),
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            model = model,
            dolbyVisionProfiles = setOf(5, 8),
            passthroughExtras = buildSet {
                if ("dts" in bitstream) add(DeviceCapabilities.DTS_HD)
                if ("eac3" in bitstream) add(DeviceCapabilities.ATMOS_EAC3)
            },
            engine = PlayerEngine.MPV,
        )
    }

    /** mpv's `audio-spdif` value for [bitstream] (DTS as "dts-hd", which carries plain DTS too); empty = off. */
    fun spdifOption(bitstream: Set<String>): String = BITSTREAM_CODECS.filter { it in bitstream }
        .joinToString(",") { if (it == "dts") "dts-hd" else it }

    private val EIGHT_BIT_ONLY = setOf("mpeg2video", "mpeg4", "vc1")
}

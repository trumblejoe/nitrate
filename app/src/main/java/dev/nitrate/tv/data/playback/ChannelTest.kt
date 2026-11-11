package dev.nitrate.tv.data.playback

/**
 * A loudspeaker position, with where it sits in a top-down room diagram:
 * x from -1 (left wall) to 1 (right wall), y from 0 (screen) to 1 (back wall).
 */
enum class Speaker(val label: String, val short: String, val x: Float, val y: Float) {
    FL("Front left", "FL", -0.62f, 0.12f),
    FR("Front right", "FR", 0.62f, 0.12f),
    FC("Center", "C", 0f, 0.06f),
    LFE("Subwoofer", "SW", 0.34f, 0.04f),
    SL("Surround left", "SL", -0.92f, 0.58f),
    SR("Surround right", "SR", 0.92f, 0.58f),
    BL("Rear left", "RL", -0.45f, 0.94f),
    BR("Rear right", "RR", 0.45f, 0.94f),
}

/**
 * A walk-through clip (see tools/generate-channel-test.sh): each speaker in [speakers] order gets
 * one [ChannelTest.SEGMENT_MS] slot with its spoken name and a noise burst, every other channel silent.
 */
data class ChannelClip(
    val asset: String,
    val layout: String,
    val format: String,
    val speakers: List<Speaker>,
) {
    val uri get() = "asset:///channeltest/$asset"
    val durationMs get() = speakers.size * ChannelTest.SEGMENT_MS

    /** The speaker that should be sounding at [positionMs]. */
    fun speakerAt(positionMs: Long): Speaker? =
        if (positionMs < 0) null else speakers.getOrNull((positionMs / ChannelTest.SEGMENT_MS).toInt())

    /** Where [speaker]'s slot starts, for looping one speaker. */
    fun slotStartMs(speaker: Speaker): Long? = speakers.indexOf(speaker).takeIf { it >= 0 }?.let { it.toLong() * ChannelTest.SEGMENT_MS }
}

object ChannelTest {
    /** Must match SEGMENT in tools/generate-channel-test.sh. */
    const val SEGMENT_MS = 3_000L

    private val STEREO = listOf(Speaker.FL, Speaker.FR)
    private val SURROUND_51 = listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.SL, Speaker.SR)
    private val SURROUND_71 = listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR, Speaker.SL, Speaker.SR)

    val CLIPS = listOf(
        ChannelClip("stereo_aac.m4a", "Stereo", "AAC", STEREO),
        ChannelClip("51_ac3.mka", "5.1", "Dolby Digital", SURROUND_51),
        ChannelClip("51_eac3.mka", "5.1", "Dolby Digital Plus", SURROUND_51),
        ChannelClip("51_truehd.mka", "5.1", "Dolby TrueHD", SURROUND_51),
        ChannelClip("51_aac.m4a", "5.1", "AAC", SURROUND_51),
        ChannelClip("51_flac.mka", "5.1", "PCM (decoded by the TV)", SURROUND_51),
        ChannelClip("71_flac.mka", "7.1", "PCM (decoded by the TV)", SURROUND_71),
    )
}

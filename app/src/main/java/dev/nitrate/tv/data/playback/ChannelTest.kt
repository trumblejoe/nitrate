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
 * Something to play in the channel test. Walk-throughs (see tools/generate-channel-test.sh) give
 * each speaker in [speakers] order one [ChannelTest.SEGMENT_MS] slot, so the map can follow along.
 * Other files (e.g. a receiver maker's demo) still play, without the map lighting up.
 */
data class ChannelClip(
    val uri: String,
    val layout: String,
    val format: String,
    val speakers: List<Speaker>,
    val walk: Boolean = true,
    /** Set for files from the user's server. */
    val serverItemId: String? = null,
) {
    val durationMs get() = speakers.size * ChannelTest.SEGMENT_MS

    /** The speaker that should be sounding at [positionMs], if this is a walk-through. */
    fun speakerAt(positionMs: Long): Speaker? =
        if (!walk || positionMs < 0) null else speakers.getOrNull((positionMs / ChannelTest.SEGMENT_MS).toInt())

    /** Where [speaker]'s slot starts, for looping one speaker. */
    fun slotStartMs(speaker: Speaker): Long? =
        if (!walk) null else speakers.indexOf(speaker).takeIf { it >= 0 }?.let { it.toLong() * ChannelTest.SEGMENT_MS }
}

object ChannelTest {
    /** Must match SEGMENT in tools/generate-channel-test.sh. */
    const val SEGMENT_MS = 3_000L

    /** Server items whose title contains this show up in the test. */
    const val SERVER_TAG = "Nitrate Test"

    private val STEREO = listOf(Speaker.FL, Speaker.FR)
    private val SURROUND_51 = listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.SL, Speaker.SR)
    private val SURROUND_71 = listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR, Speaker.SL, Speaker.SR)

    private fun bundled(asset: String, layout: String, format: String, speakers: List<Speaker>) =
        ChannelClip("asset:///channeltest/$asset", layout, format, speakers)

    val CLIPS = listOf(
        bundled("stereo_aac.m4a", "Stereo", "AAC", STEREO),
        bundled("51_ac3.mka", "5.1", "Dolby Digital", SURROUND_51),
        bundled("51_eac3.mka", "5.1", "Dolby Digital Plus", SURROUND_51),
        bundled("51_truehd.mka", "5.1", "Dolby TrueHD", SURROUND_51),
        bundled("51_aac.m4a", "5.1", "AAC", SURROUND_51),
        bundled("51_flac.mka", "5.1", "PCM (decoded by the TV)", SURROUND_51),
        bundled("71_flac.mka", "7.1", "PCM (decoded by the TV)", SURROUND_71),
    )

    private val LAYOUT_TOKEN = Regex("""(?i)(?<![\d.])(7\.1|5\.1|2\.0|stereo)(?![\d.])""")

    /**
     * Builds a clip for a server file. The layout comes from a token in the title ("7.1", "5.1",
     * "2.0"/"Stereo"), else from the audio [channels]. Only titles saying "Walk" with a layout are
     * treated as walk-throughs, since anything else has unknown timing.
     */
    fun fromServer(itemId: String, title: String, uri: String, channels: Int?, format: String): ChannelClip {
        val token = LAYOUT_TOKEN.find(title)?.value?.lowercase()
        val speakers = when (token ?: when (channels) { 8 -> "7.1"; 6 -> "5.1"; 2 -> "2.0"; else -> null }) {
            "7.1" -> SURROUND_71
            "5.1" -> SURROUND_51
            "2.0", "stereo" -> STEREO
            else -> emptyList()
        }
        val layout = when (speakers) { SURROUND_71 -> "7.1"; SURROUND_51 -> "5.1"; STEREO -> "Stereo"; else -> "${channels ?: "?"} ch" }
        val walk = token != null && Regex("(?i)\\bwalk\\b").containsMatchIn(title)
        return ChannelClip(uri, layout, format, speakers, walk = walk, serverItemId = itemId)
    }
}

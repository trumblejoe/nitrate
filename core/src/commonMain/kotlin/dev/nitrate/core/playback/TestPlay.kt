package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.StreamKind
import kotlinx.serialization.Serializable

/** A short real playback of one title, through the normal path, and the trail it left. */
@Serializable
data class TestPlayResult(
    val itemId: String,
    val name: String,
    val atMs: Long,
    val trail: PlaybackTrail?,
    val droppedFrames: Int = 0,
    val playedMs: Long = 0,
    /** Why it didn't play, when it didn't. */
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && trail != null && trail.differences.isEmpty() && droppedFrames <= TestPlay.DROPPED_OK

    /** One line: "Played as predicted", "2 things differed", "Didn't play: …". */
    val summary: String
        get() = when {
            error != null -> "Didn't play: $error"
            trail == null -> "No result"
            else -> listOfNotNull(
                trail.differences.size.let { if (it == 0) "Played as predicted" else "$it thing${if (it == 1) "" else "s"} not as predicted" },
                droppedFrames.takeIf { it > TestPlay.DROPPED_OK }?.let { "$it dropped frames" },
            ).joinToString(" · ")
        }
}

object TestPlay {
    /** How long a test plays. */
    const val PLAY_MS = 30_000L

    /** Dropped frames a test may have and still count as smooth (a few at the start are normal). */
    const val DROPPED_OK = 10

    /** Where a test starts: a few minutes in, past studio logos, never past the middle. */
    fun startAt(runtimeMs: Long?): Long = runtimeMs?.takeIf { it > 0 }?.let { minOf(300_000L, it / 2) } ?: 0L

    /**
     * What makes two files play alike here: container, video codec, size class, bit depth, HDR,
     * the default sound's codec and channels, and picture subtitles (which may be burned in).
     */
    fun signature(source: MediaSource): String {
        val v = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val a = PlaybackPredictor.audio(source)
        val size = v?.height?.let { h -> when { h >= 2000 -> "4K"; h >= 1000 -> "1080"; h >= 700 -> "720"; else -> "SD" } }
        val range = v?.rangeType?.let { r -> when { r.startsWith("DOVIWithEL") -> "DV7"; r.startsWith("DOVI") -> "DV"; else -> r } }
        val pictureSubs = source.streams.any { it.kind == StreamKind.SUBTITLE && it.codec?.lowercase() in setOf("pgssub", "pgs", "dvdsub", "dvbsub", "vobsub") }
        return listOf(
            source.container?.lowercase()?.substringBefore(','), v?.codec?.lowercase(), size, v?.bitDepth?.toString(), range,
            if (v?.isInterlaced == true) "i" else null, a?.codec?.lowercase(), a?.channels?.toString(), if (pictureSubs) "picsubs" else null,
        ).joinToString("|") { it ?: "-" }
    }

    /** One title for each kind of file, so a few test plays cover the whole library. */
    fun sample(entries: List<OverviewEntry>): List<OverviewEntry> =
        entries.filter { it.source != null }.groupBy { signature(it.source!!) }.values.map { group -> group.first() }
            .sortedWith(compareBy({ it.prediction.delivery.ordinal }, { it.name }))

    /** A short name for a kind of file, for the sample list: "MKV · HEVC 4K 10-bit Dolby Vision · Dolby TrueHD 7.1". */
    fun describe(source: MediaSource): String {
        val v = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val a = PlaybackPredictor.audio(source)
        return listOfNotNull(
            source.container?.substringBefore(',')?.uppercase(),
            v?.let { PlaybackPredictor.describe(it.copy(frameRate = null)) },
            a?.let { listOfNotNull(codecLabel(it.codec), PlaybackPredictor.channels(it.channels)).joinToString(" ") },
        ).joinToString(" · ")
    }
}

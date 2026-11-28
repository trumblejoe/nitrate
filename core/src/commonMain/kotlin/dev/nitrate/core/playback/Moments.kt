package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind

/** When to offer skipping and when to show "Up next" during playback. */
object PlaybackMoments {
    /** Don't offer a skip that would only save a couple of seconds. */
    private const val MIN_SKIP_MS = 3_000L

    /** Without marked credits, Up Next appears this long before the end. */
    const val UP_NEXT_LEAD_MS = 30_000L

    /** The segment to offer skipping at [positionMs], if any. */
    fun skippable(segments: List<MediaSegment>, positionMs: Long): MediaSegment? =
        segments.firstOrNull { it.kind != SegmentKind.UNKNOWN && positionMs >= it.startMs && it.endMs - positionMs > MIN_SKIP_MS }

    /**
     * Where Up Next starts: at the credits when the server marks them near the end, otherwise
     * [UP_NEXT_LEAD_MS] before the end (never in the first 90% of short videos).
     */
    fun upNextAtMs(durationMs: Long, segments: List<MediaSegment>): Long? {
        if (durationMs <= 0) return null
        val outro = segments.filter { it.kind == SegmentKind.OUTRO && it.endMs >= durationMs - 60_000 }.minByOrNull { it.startMs }
        if (outro != null && outro.startMs < durationMs) return outro.startMs
        return maxOf(durationMs - UP_NEXT_LEAD_MS, durationMs * 9 / 10)
    }

    /**
     * Where a part counts as watched, for the user's own record and a group's alike: at the credits
     * when the server marks them near the end (where Up Next appears; an anime's ending often starts
     * just before 90 %), else at 90 % (Jellyfin's own mark). A marked outro in the first half is
     * taken for a mistake and ignored.
     */
    fun watchedAtMs(durationMs: Long, segments: List<MediaSegment>): Long? {
        if (durationMs <= 0) return null
        val nine = durationMs * 9 / 10
        val outro = segments.filter { it.kind == SegmentKind.OUTRO && it.endMs >= durationMs - 60_000 && it.startMs >= durationMs / 2 }.minOfOrNull { it.startMs }
        return if (outro != null) minOf(outro, nine) else nine
    }

    /**
     * The position to report a stop at: past [watchedAtMs], the end, so the server marks the part
     * watched exactly as when it plays to the end (a group play's own state is still put back by the
     * Companion); before it, where it stopped.
     */
    fun stopAtMs(positionMs: Long, durationMs: Long, segments: List<MediaSegment>): Long =
        watchedAtMs(durationMs, segments)?.takeIf { positionMs in it until durationMs }?.let { durationMs } ?: positionMs
}

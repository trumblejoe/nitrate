package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind

/**
 * mpv numbers a file's tracks per kind from 1 in file order (aid, sid), and subtitle files added
 * with sub-add after those; Jellyfin numbers every stream of the file together. This maps the
 * server's picks to mpv's: for a file played as it is, by position among that kind's embedded
 * streams; for a stream the server repackaged, its one audio track and only the added files.
 */
object MpvTracks {
    /** mpv's aid for the server's audio [index] (null: let mpv choose). */
    fun audioId(plan: PlaybackPlan, index: Int?): Int? {
        if (plan.method != PlayMethod.DIRECT_PLAY) return null
        return position(plan.source, StreamKind.AUDIO, index ?: return null)
    }

    /** mpv's sid for the server's subtitle [index]; 0 is off. External subtitle files come after the embedded ones, in [PlaybackPlan.externalSubtitles] order. */
    fun subtitleId(plan: PlaybackPlan, index: Int): Int {
        if (index < 0) return 0
        val embedded = if (plan.method == PlayMethod.DIRECT_PLAY) plan.source.streams.count { it.kind == StreamKind.SUBTITLE && !it.isExternal } else 0
        val external = plan.externalSubtitles.indexOfFirst { it.streamIndex == index }
        if (external >= 0) return embedded + external + 1
        if (plan.method != PlayMethod.DIRECT_PLAY) return 0
        return position(plan.source, StreamKind.SUBTITLE, index) ?: 0
    }

    private fun position(source: MediaSource, kind: StreamKind, index: Int): Int? {
        val inFile = source.streams.filter { it.kind == kind && !it.isExternal }.sortedBy { it.index }
        val at = inFile.indexOfFirst { it.index == index }
        return if (at >= 0) at + 1 else null
    }
}

package dev.nitrate.core.recap

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind
import dev.nitrate.core.pages.DisplayPrefs

/** The closing minutes of an episode or title, before its credits: played as a "previously on". */
data class RecapClip(val item: MediaItem, val startMs: Long, val endMs: Long)

/**
 * "Last time": shown before resuming a show (or a playlist marked in story order) after a break.
 * Only ever things the user has watched, so it can't spoil anything.
 */
data class Recap(
    /** "The Night Reel" or the playlist's name. */
    val title: String,
    /** "You're on S2 E5" (never how many are left) or, for a playlist, "6 of 12 watched · next: Thor". */
    val where: String,
    /** "3 weeks ago". */
    val since: String,
    /** The last few things watched, oldest first. */
    val cards: List<MediaItem>,
    /** The ending of the last one watched, if it has a known length. */
    val clip: RecapClip?,
    /** What plays after the recap. */
    val next: MediaItem,
    /** For a playlist: what plays after [next], in order. */
    val then: List<String> = emptyList(),
)

/** Choices for "Recap after a break of at least", in days (0: never). */
val RECAP_CHOICES = listOf(0 to "Never", 8 to "8 days", 14 to "2 weeks", 30 to "1 month", 182 to "6 months", 365 to "1 year")

object Recaps {
    /** How many watched titles a recap shows. */
    const val CARDS = 3

    /** The clip's length. */
    const val CLIP_MS = 3 * 60_000L

    /** When nothing marks the credits, they're taken to be this long. */
    const val CREDITS_GUESS_MS = 60_000L

    private const val DAY_MS = 24 * 60 * 60_000L

    /** Whether a recap is due: the setting is on and the last watch is that long ago or longer (to the millisecond). */
    fun due(prefs: DisplayPrefs, lastWatchedMs: Long?, nowMs: Long): Boolean =
        prefs.recapAfterDays > 0 && lastWatchedMs != null && nowMs - lastWatchedMs >= prefs.recapAfterDays * DAY_MS

    /**
     * The ending of [item] before its credits: the server's credits marker (a media segment), else a
     * chapter named like credits, else the last minute assumed to be credits. Null without a length.
     */
    fun clip(item: MediaItem, segments: List<MediaSegment>, chapters: List<Chapter> = item.chapters): RecapClip? {
        val runtime = item.runtimeMs?.takeIf { it > 0 } ?: return null
        val credits = segments.filter { it.kind == SegmentKind.OUTRO }.minOfOrNull { it.startMs }
            ?: chapters.lastOrNull { c -> CREDIT_NAMES.any { c.name.contains(it, ignoreCase = true) } }?.startMs?.takeIf { it > runtime / 2 }
            ?: (runtime - CREDITS_GUESS_MS)
        val end = credits.coerceIn(0, runtime)
        val start = (end - CLIP_MS).coerceAtLeast(0)
        return if (end - start < 20_000) null else RecapClip(item, start, end)
    }

    private val CREDIT_NAMES = listOf("credit", "end titles", "closing")

    /** "today", "yesterday", "5 days ago", "3 weeks ago", "2 months ago". */
    fun since(lastMs: Long, nowMs: Long): String {
        val days = ((nowMs - lastMs) / DAY_MS).toInt()
        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            days < 730 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    /**
     * A show's recap before playing [next]: the last episodes watched before it, in order. Null
     * when there's nothing watched before it or the break is too short.
     */
    fun forSeries(series: MediaItem, episodes: List<MediaItem>, next: MediaItem, prefs: DisplayPrefs, nowMs: Long, segments: List<MediaSegment> = emptyList()): Recap? {
        val ordered = episodes.filter { !it.isMissing }
        val upTo = ordered.indexOfFirst { it.id == next.id }.takeIf { it >= 0 } ?: ordered.size
        val watched = ordered.take(upTo).filter { it.user.played }
        val last = watched.lastOrNull() ?: return null
        val lastMs = watched.mapNotNull { it.user.lastPlayedMs }.maxOrNull()
        if (!due(prefs, lastMs, nowMs)) return null
        // Never how many are left in the season: that gives away the shape of the show's arcs.
        val where = "You're on S${next.seasonNumber ?: 0} E${next.indexNumber ?: 0}"
        return Recap(series.name, where, Recaps.since(lastMs!!, nowMs), watched.takeLast(CARDS), clip(last, segments), next)
    }

    /**
     * A playlist's recap (only for playlists marked in story order): the last items watched before
     * the next one, and how far through it the user is.
     */
    fun forPlaylist(playlist: MediaItem, items: List<MediaItem>, prefs: DisplayPrefs, nowMs: Long, segments: List<MediaSegment> = emptyList()): Recap? {
        if (playlist.id !in prefs.recapPlaylists) return null
        val nextIndex = items.indexOfFirst { !it.user.played }.takeIf { it > 0 } ?: return null
        val next = items[nextIndex]
        val watched = items.take(nextIndex)
        val lastMs = watched.mapNotNull { it.user.lastPlayedMs }.maxOrNull()
        if (!due(prefs, lastMs, nowMs)) return null
        val where = "$nextIndex of ${items.size} watched · next: ${label(next)}"
        return Recap(playlist.name, where, Recaps.since(lastMs!!, nowMs), watched.takeLast(CARDS), clip(watched.last(), segments), next, items.drop(nextIndex + 1).map { it.id })
    }

    /** "S2 E4 · Thaw" for an episode, else the title. */
    fun label(item: MediaItem) = if (item.kind == ItemKind.EPISODE) "S${item.seasonNumber ?: 0} E${item.indexNumber ?: 0} · ${item.name}" else item.name

    /**
     * The recap for playing [item] now, if one is due: as the next episode of its show, or the
     * next item of a playlist marked in story order ([playlist]). Reads the server; never throws.
     */
    suspend fun before(server: MediaServer, item: MediaItem, prefs: DisplayPrefs, nowMs: Long, playlist: MediaItem? = null): Recap? = runCatching {
        if (prefs.recapAfterDays <= 0) return@runCatching null
        if (playlist != null) {
            if (playlist.id !in prefs.recapPlaylists) return@runCatching null
            val items = server.playlistItems(playlist.id)
            val base = forPlaylist(playlist, items, prefs, nowMs) ?: return@runCatching null
            return@runCatching base.copy(clip = base.clip?.let { withSegments(server, it) })
        }
        val seriesId = item.seriesId ?: return@runCatching null
        if (item.kind != ItemKind.EPISODE) return@runCatching null
        val episodes = server.allEpisodes(seriesId)
        val series = MediaItem(seriesId, item.seriesName ?: "This show", ItemKind.SERIES)
        val base = forSeries(series, episodes, item, prefs, nowMs) ?: return@runCatching null
        base.copy(clip = base.clip?.let { withSegments(server, it) })
    }.getOrNull()

    /** The clip again, now with the episode's own credits marker and chapters from the server. */
    private suspend fun withSegments(server: MediaServer, clip: RecapClip): RecapClip? {
        val full = runCatching { server.getItem(clip.item.id) }.getOrDefault(clip.item)
        val segments = runCatching { server.mediaSegments(clip.item.id) }.getOrDefault(emptyList())
        return clip(full, segments) ?: clip
    }
}

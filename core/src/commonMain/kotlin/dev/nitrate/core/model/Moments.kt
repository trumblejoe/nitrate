package dev.nitrate.core.model

import kotlinx.serialization.Serializable

/** A marked part of a video, from the server's media segments (intro skipper plugins, chapters). */
@Serializable
enum class SegmentKind(val skipLabel: String) {
    INTRO("Skip intro"),
    RECAP("Skip recap"),
    PREVIEW("Skip preview"),
    COMMERCIAL("Skip ad"),
    OUTRO("Skip credits"),
    UNKNOWN("Skip"),
}

@Serializable
data class MediaSegment(val kind: SegmentKind, val startMs: Long, val endMs: Long)

/**
 * Scrubbing thumbnails: the server renders frames every [intervalMs] into JPEG sheets of
 * [tileColumns] x [tileRows] thumbnails, each [width] x [height].
 */
@Serializable
data class TrickplayInfo(
    val width: Int,
    val height: Int,
    val tileColumns: Int,
    val tileRows: Int,
    val thumbnailCount: Int,
    val intervalMs: Long,
    val mediaSourceId: String? = null,
) {
    /** Which sheet holds the frame for [positionMs], and where in it. */
    fun tileFor(positionMs: Long): TrickplayTile? {
        if (thumbnailCount <= 0 || intervalMs <= 0 || tileColumns <= 0 || tileRows <= 0) return null
        val thumb = (positionMs.coerceAtLeast(0) / intervalMs).toInt().coerceAtMost(thumbnailCount - 1)
        val perSheet = tileColumns * tileRows
        val within = thumb % perSheet
        return TrickplayTile(sheet = thumb / perSheet, column = within % tileColumns, row = within / tileColumns)
    }
}

/** Frame [column], [row] of sheet [sheet]: crop at (column * width, row * height). */
data class TrickplayTile(val sheet: Int, val column: Int, val row: Int)

/** Search results, grouped. */
data class SearchResults(
    val movies: List<MediaItem> = emptyList(),
    val shows: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val collections: List<MediaItem> = emptyList(),
    val people: List<Person> = emptyList(),
) {
    val isEmpty get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty() && collections.isEmpty() && people.isEmpty()
}

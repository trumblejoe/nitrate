package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.model.TrickplayTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomentsTest {
    private val intro = MediaSegment(SegmentKind.INTRO, 60_000, 150_000)
    private val credits = MediaSegment(SegmentKind.OUTRO, 2_500_000, 2_580_000)

    @Test
    fun `skip is offered inside a segment until its last seconds`() {
        assertNull(PlaybackMoments.skippable(listOf(intro), 59_000))
        assertEquals(intro, PlaybackMoments.skippable(listOf(intro), 60_000))
        assertEquals(intro, PlaybackMoments.skippable(listOf(intro), 146_000))
        assertNull(PlaybackMoments.skippable(listOf(intro), 148_000))
        assertNull(PlaybackMoments.skippable(listOf(MediaSegment(SegmentKind.UNKNOWN, 0, 100_000)), 1_000))
    }

    @Test
    fun `up next starts at the credits, or near the end without them`() {
        assertEquals(2_500_000L, PlaybackMoments.upNextAtMs(2_580_000, listOf(intro, credits)))
        assertEquals(2_550_000L, PlaybackMoments.upNextAtMs(2_580_000, listOf(intro)))
        // A credits marker in the middle (a mid-show sequence) doesn't count.
        assertEquals(2_550_000L, PlaybackMoments.upNextAtMs(2_580_000, listOf(MediaSegment(SegmentKind.OUTRO, 600_000, 700_000))))
        // Short clips: never before 90%.
        assertEquals(90_000L, PlaybackMoments.upNextAtMs(100_000, emptyList()))
        assertNull(PlaybackMoments.upNextAtMs(0, emptyList()))
    }

    @Test
    fun `trickplay frames map to sheets and cells`() {
        val info = TrickplayInfo(width = 320, height = 180, tileColumns = 10, tileRows = 10, thumbnailCount = 250, intervalMs = 10_000)
        assertEquals(TrickplayTile(0, 0, 0), info.tileFor(0))
        assertEquals(TrickplayTile(0, 3, 1), info.tileFor(135_000))
        assertEquals(TrickplayTile(1, 0, 0), info.tileFor(1_000_000))
        // Past the last frame: the last one.
        assertEquals(TrickplayTile(2, 9, 4), info.tileFor(99_000_000))
        assertNull(info.copy(thumbnailCount = 0).tileFor(0))
    }

    @Test
    fun `a part counts as watched at its credits, else at 90 percent`() {
        // An anime's ending starts before 90%: watched from there, and a stop there is reported as the end.
        val ending = MediaSegment(SegmentKind.OUTRO, 1_290_000, 1_440_000)
        assertEquals(1_290_000L, PlaybackMoments.watchedAtMs(1_440_000, listOf(ending)))
        assertEquals(1_440_000L, PlaybackMoments.stopAtMs(1_300_000, 1_440_000, listOf(ending)))
        assertEquals(1_200_000L, PlaybackMoments.stopAtMs(1_200_000, 1_440_000, listOf(ending)))
        // Without credits: Jellyfin's 90%.
        assertEquals(1_296_000L, PlaybackMoments.watchedAtMs(1_440_000, emptyList()))
        // Credits later than 90% don't hold it back; a credits mark in the first half is a mistake.
        assertEquals(1_296_000L, PlaybackMoments.watchedAtMs(1_440_000, listOf(MediaSegment(SegmentKind.OUTRO, 1_400_000, 1_440_000))))
        assertEquals(1_296_000L, PlaybackMoments.watchedAtMs(1_440_000, listOf(MediaSegment(SegmentKind.OUTRO, 500_000, 1_440_000))))
        assertNull(PlaybackMoments.watchedAtMs(0, listOf(ending)))
    }
}

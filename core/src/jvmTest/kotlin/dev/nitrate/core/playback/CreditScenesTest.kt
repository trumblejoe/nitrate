package dev.nitrate.core.playback

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreditScenesTest {
    private val min = 60_000L
    private val runtime = 120 * min

    private fun credits(): FrameLuma {
        // Black, with a few lines of small white text.
        val l = IntArray(48 * 27) { 8 }
        for (row in listOf(10, 13, 16)) for (x in 14..34) l[row * 48 + x] = 235
        return FrameLuma(48, 27, l)
    }
    private fun picture(): FrameLuma = FrameLuma(48, 27, IntArray(48 * 27) { (it * 37) % 200 + 30 })
    private fun darkScene(): FrameLuma = FrameLuma(48, 27, IntArray(48 * 27) { 30 + (it % 40) })

    @Test fun creditsLookLikeCredits() {
        assertTrue(CreditScenes.looksLikeCredits(credits()))
        assertTrue(CreditScenes.looksLikeCredits(FrameLuma(4, 4, IntArray(16))))
        assertFalse(CreditScenes.looksLikeCredits(picture()))
        assertFalse(CreditScenes.looksLikeCredits(darkScene()))
    }

    @Test fun tagsAlone() {
        val i = CreditScenes.info(runtime, listOf("superhero", "aftercreditsstinger"), emptyList(), emptyList())
        assertTrue(i.taggedAfter)
        assertFalse(i.taggedDuring)
        assertTrue(i.skips.isEmpty())
        assertFalse(i.automatic)
    }

    @Test fun outroEndingEarlyIsAScene() {
        val segs = listOf(MediaSegment(SegmentKind.OUTRO, 110 * min, 118 * min))
        val i = CreditScenes.info(runtime, emptyList(), segs, emptyList())
        assertEquals(listOf(CreditSkip(110 * min, 118 * min, afterCredits = true)), i.skips)
        assertEquals(CreditCertainty.MARKED, i.certainty)
        assertTrue(i.automatic)
        // An outro running to the end is just credits.
        val end = CreditScenes.info(runtime, emptyList(), listOf(MediaSegment(SegmentKind.OUTRO, 110 * min, runtime - 2_000)), emptyList())
        assertTrue(end.skips.isEmpty())
        assertEquals(110 * min, end.creditsStartMs)
    }

    @Test fun midAndPostCreditOutros() {
        val segs = listOf(MediaSegment(SegmentKind.OUTRO, 108 * min, 110 * min), MediaSegment(SegmentKind.OUTRO, 111 * min, 118 * min))
        val skips = CreditScenes.fromMarkers(runtime, segs, emptyList())
        assertEquals(listOf(CreditSkip(108 * min, 110 * min, false), CreditSkip(111 * min, 118 * min, true)), skips)
    }

    @Test fun chapters() {
        val ch = listOf(Chapter(0, "Opening"), Chapter(100 * min, "End Credits"), Chapter(105 * min, "Mid-credits scene"), Chapter(107 * min, "Credits continued"), Chapter(118 * min, "Post-Credits Scene"))
        val skips = CreditScenes.fromMarkers(runtime, emptyList(), ch)
        assertEquals(listOf(CreditSkip(100 * min, 105 * min, false), CreditSkip(107 * min, 118 * min, true)), skips)
        // A scene chapter with nothing like credits before it isn't trusted.
        assertTrue(CreditScenes.fromMarkers(runtime, emptyList(), listOf(Chapter(0, "Start"), Chapter(118 * min, "Stinger"))).isEmpty())
    }

    private fun frames(creditsFrom: Int, creditsTo: Int, total: Int) =
        List(total) { if (it in creditsFrom until creditsTo) credits() else picture() }

    @Test fun thumbnailsFindAScene() {
        // 10 s apart: credits from 110:00 to 118:00, then a picture to the end.
        val f = frames(660, 708, 720)
        val skips = CreditScenes.fromFrames(runtime, f, 10_000)
        assertEquals(listOf(CreditSkip(110 * min, 118 * min, true)), skips)
        // Untagged: offered only.
        assertEquals(CreditCertainty.GUESSED, CreditScenes.info(runtime, emptyList(), emptyList(), emptyList(), f).certainty)
        val tagged = CreditScenes.info(runtime, listOf("AfterCreditsStinger"), emptyList(), emptyList(), f)
        assertEquals(CreditCertainty.CONFIRMED, tagged.certainty)
        assertTrue(tagged.automatic)
    }

    @Test fun thumbnailsWithoutASceneOrTooEarly() {
        // Credits to the end: nothing to skip to.
        assertTrue(CreditScenes.fromFrames(runtime, frames(660, 720, 720), 10_000).isEmpty())
        // A dark stretch in the middle of the film isn't credits.
        assertTrue(CreditScenes.fromFrames(runtime, frames(300, 360, 720), 10_000).isEmpty())
        // A few seconds of picture after the credits (a logo) isn't a scene.
        assertTrue(CreditScenes.fromFrames(runtime, frames(660, 719, 720), 10_000).isEmpty())
    }

    @Test fun skipperSkipsOnceAtTheCredits() {
        val info = CreditScenes.info(runtime, emptyList(), listOf(MediaSegment(SegmentKind.OUTRO, 110 * min, 118 * min)), emptyList())
        val s = CreditSkipper(info, autoSkip = true)
        assertNull(s.at(100 * min))
        assertEquals(CreditSkipper.Action.Skip(118 * min, 110 * min, true), s.at(110 * min + 500))
        // Undone ("Watch credits"): back at the credits, nothing again.
        assertNull(s.at(110 * min + 600))
        // Seeking past the credits' start doesn't skip late.
        val late = CreditSkipper(info, autoSkip = true)
        assertNull(late.at(113 * min))
        // Turned off: offered instead.
        assertEquals(CreditSkipper.Action.Offer(118 * min, true), CreditSkipper(info, autoSkip = false).at(110 * min))
    }

    @Test fun headsUpWhenOnlyTagged() {
        val info = CreditScenes.info(runtime, listOf("duringcreditsstinger", "aftercreditsstinger"), listOf(MediaSegment(SegmentKind.OUTRO, 110 * min, runtime)), emptyList())
        val s = CreditSkipper(info, autoSkip = true)
        val a = s.at(110 * min + 1_000)
        assertEquals(CreditSkipper.Action.HeadsUp(true, true), a)
        assertEquals("Stay for scenes during and after the credits", CreditSkipper.message(a!!))
        assertNull(s.at(110 * min + 2_000))
    }

    @Test fun upNextWaitsForTheScene() {
        val none = CreditScenes.info(runtime, emptyList(), emptyList(), emptyList())
        assertEquals(110 * min, none.upNextAt(110 * min, runtime))
        val tagged = CreditScenes.info(runtime, listOf("aftercreditsstinger"), emptyList(), emptyList())
        assertEquals(runtime - 10_000, tagged.upNextAt(110 * min, runtime))
        val marked = CreditScenes.info(runtime, emptyList(), listOf(MediaSegment(SegmentKind.OUTRO, 110 * min, 115 * min)), emptyList())
        assertEquals(runtime - 10_000, marked.upNextAt(110 * min, runtime))
        assertNull(marked.upNextAt(null, runtime))
    }
}

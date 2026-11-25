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

    // Synthetic 320×180 thumbnails, drawn as the Companion's CreditTests draws its frames.
    private val w = 320
    private val h = 180

    private fun frame(luma: (Int, Int) -> Int, sat: ((Int, Int) -> Int)? = null) = FrameLuma(
        w, h, IntArray(w * h) { luma(it % w, it / w).coerceIn(0, 255) }, sat?.let { s -> IntArray(w * h) { s(it % w, it / w) } },
    )

    /** A line of small crisp letters (5×7, 2 apart, a space every 5), centred, its top at [top]. */
    private fun text(l: IntArray, top: Int, letters: Int) {
        val left = (w - letters * 7) / 2
        for (k in 0 until letters) {
            if (k % 5 == 4) continue
            val x0 = left + k * 7
            for (dy in 0 until 7) {
                val y = top + dy
                if (y !in 0 until h) continue
                for (dx in 0 until 5) {
                    val on = when (k % 3) {
                        0 -> dx == 0 || dx == 4 || dy == 3
                        1 -> dx == 0 || dy == 0 || dy == 3 || dy == 6
                        else -> ((dx == 0 || dx == 4) && dy in 1..5) || ((dy == 0 || dy == 6) && dx in 1..3)
                    }
                    if (on) l[y * w + x0 + dx] = 235
                }
            }
        }
    }

    private fun blank() = frame({ _, _ -> 16 }, { _, _ -> 0 })

    /** A credits card: a role and two names, centred. */
    private fun credits(seed: Int = 0): FrameLuma = blank().also { f ->
        text(f.luma, 70, 10 + seed % 4); text(f.luma, 86, 16 + seed % 7); text(f.luma, 102, 14 + seed % 5)
    }

    /** Rolling credits at thumbnail [t]: lines every 18 rows, moving up [step] rows per thumbnail. */
    private fun rolling(t: Int, step: Int = 120): FrameLuma = blank().also { f ->
        val offset = t * step
        var j = offset / 18
        while (j * 18 < offset + h + 18) { text(f.luma, j * 18 - offset, 12 + (j * 7) % 13); j++ }
    }

    private fun picture(t: Int = 0): FrameLuma = frame({ x, y -> 30 + (x * 7 + y * 13 + t * 5) % 200 }, { x, y -> 10 + (x + y) % 30 })

    /** A near-black smoke-filled scene with a few soft bright patches. */
    private fun smoke(t: Int = 0): FrameLuma = frame({ x, y ->
        val haze = 14 + (8 * (kotlin.math.sin((x + t) / 23.0) + kotlin.math.sin(y / 17.0) + 2) / 2).toInt() + (x * 31 + y * 17 + t) % 5
        val glow = listOf(80 + t to 60, 200 to 120, 260 - t to 40).sumOf { (cx, cy) -> 190 * kotlin.math.exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / 200.0) }
        haze + glow.toInt()
    }, { _, _ -> 2 })

    /** A dark fire scene: a soft orange glow, crisp orange sparks and flame tongues. */
    private fun fire(t: Int = 0): FrameLuma = frame({ x, y ->
        val glow = 170 * kotlin.math.exp(-((x - 160) * (x - 160) + (y - 150) * (y - 150)) / 600.0)
        val spark = (x + t * 3) % 23 < 2 && (y + t * 7) % 29 < 7 && y < 140
        14 + glow.toInt() + if (spark) 200 else 0
    }, { _, _ -> CreditFeatureReader.saturationOf(255, 150, 30) })

    private fun darkScene(): FrameLuma = smoke()

    @Test fun framesAreMeasuredAsTheCompanionDoes() {
        val reader = CreditFeatureReader()
        reader.next(credits(1))
        val card = reader.next(credits(1))
        assertEquals(3, card.lines)
        assertTrue(card.sharp > 0.9 && card.strokes > 0.9 && card.symmetry > 0.95, card.toString())
        assertTrue(card.still)
        val scroll = CreditFeatureReader(CreditScenes.THUMBNAIL_MAX_SHIFT).let { it.next(rolling(0)); it.next(rolling(1)) }
        assertEquals(120, scroll.scrollDy)
        assertTrue(CreditFeatureReader().next(fire()).saturation!! > CreditScenes.SATURATION_MAX)
        assertTrue(CreditFeatureReader().next(smoke()).sharp < CreditScenes.SHARP_MIN)
        // White is colourless, orange is not.
        assertEquals(0, CreditFeatureReader.saturationOf(255, 255, 255))
        assertTrue(CreditFeatureReader.saturationOf(255, 150, 30) > 50)
    }

    @Test fun verdicts() {
        fun v(frames: List<FrameLuma>) = String(CreditScenes.verdicts(CreditScenes.features(frames), 10_000))
        assertTrue(v(List(6) { rolling(it) }).all(CreditScenes::counts))
        assertTrue(v(List(6) { smoke(it) }).none(CreditScenes::counts))
        assertTrue(v(List(6) { fire(it) }).none(CreditScenes::counts))
        assertTrue(v(List(6) { picture(it) }).none(CreditScenes::counts))
        // Black right after cards counts with them; black alone doesn't.
        assertEquals("CCBCC", v(listOf(credits(0), credits(0), blank(), credits(1), credits(1))))
        assertEquals("bb", v(listOf(blank(), blank())))
    }

    @Test fun serverFindings() {
        val skip = CreditSkip(5_000_000, 5_300_000, afterCredits = true)
        // Matching the tag: sure enough to skip by itself; without a tag, only offered.
        val tagged = CreditScenes.info(6_000_000, listOf("aftercreditsstinger"), emptyList(), emptyList(), fromServer = listOf(skip))
        assertEquals(CreditCertainty.CONFIRMED, tagged.certainty)
        assertEquals(listOf(skip), tagged.skips)
        assertEquals(CreditCertainty.GUESSED, CreditScenes.info(6_000_000, emptyList(), emptyList(), emptyList(), fromServer = listOf(skip)).certainty)
        // Checked and nothing there: no skips (the device doesn't look again).
        assertTrue(CreditScenes.info(6_000_000, emptyList(), emptyList(), emptyList(), fromServer = emptyList()).skips.isEmpty())
    }

    @Test fun theCompanionsOutroIsOnlyTheFinalCreditsAndChangesNothing() {
        val skip = CreditSkip(5_000_000, 5_300_000, afterCredits = true)
        // The plugin's Outro: the last cards after the scene, to the very end.
        val outro = MediaSegment(dev.nitrate.core.model.SegmentKind.OUTRO, 5_400_000, 6_000_000)
        val i = CreditScenes.info(6_000_000, listOf("aftercreditsstinger"), listOf(outro), emptyList(), fromServer = listOf(skip))
        assertEquals(listOf(skip), i.skips)
        assertEquals(CreditCertainty.CONFIRMED, i.certainty)
        // The credits still start where they first do, not at the final cards.
        assertEquals(5_000_000, i.creditsStartMs)
        // Not read as a scene marker (it runs to the end).
        assertTrue(CreditScenes.fromMarkers(6_000_000, listOf(outro), emptyList()).isEmpty())
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

    private val card = credits()
    private val film = picture()
    private fun frames(creditsFrom: Int, creditsTo: Int, total: Int) =
        List(total) { if (it in creditsFrom until creditsTo) card else film }

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

    @Test fun aDarkSceneWithTheFilmGoingOnIsNotCredits() {
        // 85:00 to 87:00 near-black (a smoke-filled escape), the film to 110:00, then credits to the end.
        val f = List(720) { if (it in 510 until 522 || it >= 660) card else film }
        assertTrue(CreditScenes.fromFrames(runtime, f, 10_000).isEmpty())
        // Read the new way, the smoke-filled scene isn't credits at all, nor is a fire, even with
        // the scene after them short: credits only at the end.
        for (scene in listOf(::smoke, ::fire)) {
            val g = List(720) { if (it in 640 until 652) scene(it) else if (it >= 670) card else film }
            assertTrue(CreditScenes.fromFrames(runtime, g, 10_000).isEmpty())
        }
        // Kept from before this rule (by the server): a "scene" 33 minutes before the end isn't offered.
        val kept = CreditScenes.info(runtime, emptyList(), emptyList(), emptyList(), fromServer = listOf(CreditSkip(85 * min, 87 * min, true)))
        assertTrue(kept.skips.isEmpty())
        // A real one near the end still is.
        val real = CreditScenes.info(runtime, emptyList(), emptyList(), emptyList(), fromServer = listOf(CreditSkip(110 * min, 118 * min, true)))
        assertEquals(1, real.skips.size)
    }

    @Test fun rollingCreditsInThumbnails() {
        // Rolling from 110:00 to 116:00 (moving 120 rows between thumbnails), then a scene.
        val f = List(720) { if (it in 660 until 696) rolling(it - 660) else film }
        assertEquals(listOf(CreditSkip(110 * min, 116 * min, true)), CreditScenes.fromFrames(runtime, f, 10_000))
    }

    @Test fun theServerHearingDialogueInTheSceneConfirmsIt() {
        val spoken = CreditSkip(110 * min, 118 * min, afterCredits = true, spoken = true)
        assertEquals(CreditCertainty.CONFIRMED, CreditScenes.info(runtime, emptyList(), emptyList(), emptyList(), fromServer = listOf(spoken)).certainty)
        assertEquals(CreditCertainty.GUESSED, CreditScenes.info(runtime, emptyList(), emptyList(), emptyList(), fromServer = listOf(spoken.copy(spoken = false))).certainty)
    }

    @Test fun theCreditsCheckInAFewWords() {
        val r = CreditsReport(
            runtimeMs = runtime, startMs = 84 * min,
            skips = listOf(CreditSkip(110 * min, 117 * min, afterCredits = true, spoken = true)),
            outroStartMs = null, outroRejected = false, dialogueLines = 412,
            vetoed = listOf(VetoedStretch(95 * min, 97 * min, "15 lines of dialogue over 1:54")),
            rows = 2160, reading = false, note = null, json = "{}",
        )
        assertEquals(
            "Credits start at 1:50:00. A scene after them at 1:57:00 (someone speaks in it). Not credits: 1:35:00–1:37:00, 15 lines of dialogue over 1:54. 412 subtitle lines voted.",
            r.summary(),
        )
        assertEquals("No credits found after 1:24:00. No subtitles indexed: the pictures decided alone.", r.copy(skips = emptyList(), vetoed = emptyList(), dialogueLines = null).summary())
        assertTrue(r.copy(reading = true, rows = 0).summary().startsWith("Reading the end of the title"))
        assertEquals("Too short", r.copy(rows = 0, note = "Too short").summary())
        // How it was read, when the server says.
        val read = r.copy(skips = emptyList(), vetoed = emptyList(), dialogueLines = null, readMs = 9_400, framesDecoded = 312, readFromMs = runtime - 11 * min, hardware = "vaapi")
        assertEquals("No credits found after 1:24:00. No subtitles indexed: the pictures decided alone. Read 11 min back in 9 s, 312 frames, hardware (vaapi).", read.summary())
        assertEquals("Read 11 min back in 9 s, 312 frames, software.", read.copy(hardware = null).readLine())
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

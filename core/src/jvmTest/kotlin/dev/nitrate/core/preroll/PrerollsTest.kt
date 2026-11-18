package dev.nitrate.core.preroll

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.poetry.Poems
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrerollsTest {
    private val film = MediaItem(id = "f", name = "Film", kind = ItemKind.MOVIE)
    private val episode = MediaItem(id = "e", name = "Ep", kind = ItemKind.EPISODE)
    private val on = PrerollPrefs(on = true)

    @Test fun offUntilSwitchedOn() = assertFalse(Prerolls.applies(film, PrerollPrefs(), fromStart = true))

    @Test fun filmsByDefaultNotEpisodes() {
        assertTrue(Prerolls.applies(film, on, fromStart = false))
        assertFalse(Prerolls.applies(episode, on, fromStart = true))
    }

    @Test fun neverWhenResuming() {
        val started = film.copy(user = UserState(positionMs = 60_000))
        assertFalse(Prerolls.applies(started, on, fromStart = false))
        assertTrue(Prerolls.applies(started, on, fromStart = true))
    }

    @Test fun episodesOnceASitting() {
        val prefs = on.copy(episodes = true)
        assertTrue(Prerolls.applies(episode, prefs, true, nowMs = 10_000_000, lastPrerollMs = null))
        assertFalse(Prerolls.applies(episode, prefs, true, nowMs = 10_000_000, lastPrerollMs = 9_000_000))
        assertTrue(Prerolls.applies(episode, prefs, true, nowMs = 20_000_000, lastPrerollMs = 9_000_000))
    }

    @Test fun orderSceneVerseThenVideos() {
        val intro = MediaItem(id = "i", name = "Intro", kind = ItemKind.MOVIE)
        val own = MediaItem(id = "o", name = "Own", kind = ItemKind.MOVIE)
        val steps = Prerolls.plan(on.copy(scenes = setOf(PrerollScene.NORTHERN_LIGHTS)), listOf(intro, intro, intro), listOf(own), emptySet(), Random(1))
        assertTrue(steps[0] is PrerollStep.Scene)
        assertTrue(steps[1] is PrerollStep.Verse)
        assertEquals(listOf("i", "i", "o"), steps.drop(2).map { (it as PrerollStep.Video).item.id })
    }

    @Test fun clipOnlyScenesNeedTheirClip() {
        val prefs = on.copy(verses = false, scenes = setOf(PrerollScene.SKYDIVE))
        assertTrue(Prerolls.plan(prefs, emptyList(), emptyList(), emptySet()).isEmpty())
        assertEquals(PrerollStep.Scene(PrerollScene.SKYDIVE), Prerolls.plan(prefs, emptyList(), emptyList(), setOf("preroll-skydive")).single())
    }

    @Test fun ownLinesMixIn() {
        val prefs = on.copy(verses = false, ownLines = listOf("  Lights down.  ", ""))
        assertEquals(PrerollStep.Line("Lights down."), Prerolls.plan(prefs, emptyList(), emptyList(), emptySet()).single())
    }

    @Test fun versesAreWholeAndAttributed() {
        assertTrue(Poems.all.size >= 40)
        Poems.all.forEach { assertEquals(5, it.lines.size, it.poet); assertTrue(it.source.contains("Porter")) }
    }
}

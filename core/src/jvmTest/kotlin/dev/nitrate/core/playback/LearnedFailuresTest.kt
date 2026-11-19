package dev.nitrate.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnedFailuresTest {
    private class Repo(var s: PlaybackSettings = PlaybackSettings()) : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(s)
        override suspend fun current() = s
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { s = transform(s); settings.value = s }
    }

    @Test fun oneTitleFailingConvertsOnlyThatTitle() = runBlocking {
        val repo = Repo()
        repo.recordFailure("hevc", "DECODING_FAILED", 1_000, itemId = "film-a")
        assertTrue(repo.s.convertsTitle("film-a"))
        assertFalse(repo.s.convertsTitle("film-b"))
        assertTrue(repo.s.allows("hevc", claimed = true), "HEVC as a whole still plays as it is")
    }

    @Test fun threeDifferentTitlesStopTheFormat() = runBlocking {
        val repo = Repo()
        repeat(3) { repo.recordFailure("hevc", "DECODING_FAILED", 1_000, itemId = "film-a") }
        assertTrue(repo.s.allows("hevc", true), "the same title failing again isn't new evidence")
        repo.recordFailure("hevc", "DECODING_FAILED", 2_000, itemId = "film-b")
        repo.recordFailure("hevc", "DECODING_FAILED", 3_000, itemId = "film-c")
        assertFalse(repo.s.allows("hevc", true))
    }

    @Test fun oldFailuresAreLetGo() = runBlocking {
        val repo = Repo()
        listOf("a", "b", "c").forEach { repo.recordFailure("range:HDR10", "DECODING_FAILED", 0, itemId = it) }
        assertFalse(repo.s.allows("range:HDR10", true))
        repo.forgetOldFailures(LearnedFailure.KEEP_MS + 1)
        assertTrue(repo.s.allows("range:HDR10", true))
        assertFalse(repo.s.convertsTitle("a"))
        assertEquals(emptyMap(), repo.s.failedTitles)
    }

    @Test fun forgettingAFormatForgetsItsTitles() = runBlocking {
        val repo = Repo()
        repo.recordFailure("hevc", "x", 0, itemId = "a")
        repo.recordFailure("dts", "x", 0, itemId = "b")
        val after = SettingsChange.ForgetFailure("hevc").apply(repo.s)
        assertFalse(after.convertsTitle("a"))
        assertTrue(after.convertsTitle("b"))
        assertFalse(SettingsChange.ForgetTitle("b").apply(after).convertsTitle("b"))
    }
}

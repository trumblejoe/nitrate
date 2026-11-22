package dev.nitrate.core.playback

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayTargetsTest {
    private fun ep(id: String, played: Boolean = false) = MediaItem(id = id, name = id, kind = ItemKind.EPISODE, user = UserState(played = played))

    @Test fun aSeasonPlaysItsFirstEpisodeNotYetWatched() {
        assertEquals("e3", PlayTargets.pick(listOf(ep("e1", true), ep("e2", true), ep("e3"), ep("e4")))?.id)
    }

    @Test fun aSeasonAllWatchedPlaysFromItsFirst() {
        assertEquals("e1", PlayTargets.pick(listOf(ep("e1", true), ep("e2", true)))?.id)
        assertNull(PlayTargets.pick(emptyList()))
    }

    @Test fun onlyShowsAndSeasonsNeedAnEpisode() {
        kotlin.test.assertTrue(PlayTargets.needsEpisode(MediaItem(id = "s", name = "s", kind = ItemKind.SERIES)))
        kotlin.test.assertFalse(PlayTargets.needsEpisode(MediaItem(id = "m", name = "m", kind = ItemKind.MOVIE)))
    }
}

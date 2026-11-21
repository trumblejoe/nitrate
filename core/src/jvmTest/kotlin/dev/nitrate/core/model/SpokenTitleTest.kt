package dev.nitrate.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTitleTest {
    @Test fun episodesAreSaidByShowSeasonAndEpisodeNeverTheirName() {
        val e = MediaItem("e1", "The Twist Revealed", ItemKind.EPISODE, seriesName = "Gate Weave", seasonNumber = 2, indexNumber = 5)
        assertEquals("Gate Weave, season 2, episode 5", e.spokenTitle)
        assertEquals("The Night Reel, 2019", MediaItem("m1", "The Night Reel", ItemKind.MOVIE, productionYear = 2019).spokenTitle)
        assertEquals("Mixtape", MediaItem("a1", "Mixtape", ItemKind.MUSIC_ALBUM).spokenTitle)
    }
}

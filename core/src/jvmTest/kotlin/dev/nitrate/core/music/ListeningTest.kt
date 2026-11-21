package dev.nitrate.core.music

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListeningTest {
    private fun song(gain: Double?, container: String? = "flac") = MediaItem("s", "Song", ItemKind.AUDIO, normalizationGain = gain, container = container)

    @Test fun levellingQuietsLoudSongsAndNeverBoosts() {
        assertEquals(1f, Loudness.volume(null))
        assertEquals(0.5f, Loudness.volume(-6.0206), 0.001f)
        // A quiet song that would need boosting plays at full volume rather than clipping.
        assertEquals(1f, Loudness.volume(+4.0))
        // Never silenced by a strange measurement.
        assertEquals(Loudness.FLOOR, Loudness.volume(-60.0))
        assertEquals(1f, Loudness.volume(Double.NaN))
    }

    @Test fun albumLevellingUsesTheAlbumsGainWhenKnown() {
        val s = song(-9.0)
        assertNull(Loudness.gainDb(s, -4.0, Levelling.OFF))
        assertEquals(-9.0, Loudness.gainDb(s, -4.0, Levelling.TRACK))
        assertEquals(-4.0, Loudness.gainDb(s, -4.0, Levelling.ALBUM))
        assertEquals(-9.0, Loudness.gainDb(s, null, Levelling.ALBUM))
    }

    @Test fun filesPlayAsTheyAreUnlessMedia3CantReadThem() {
        assertTrue(ListenStream.direct(song(null, "flac")))
        assertTrue(ListenStream.direct(song(null, "mp3")))
        assertTrue(ListenStream.direct(song(null, null)))
        assertFalse(ListenStream.direct(song(null, "wma")))
        assertFalse(ListenStream.direct(song(null, "dsf")))
        assertFalse(ListenStream.direct(song(null, "ape")))
    }
}

package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDescriptionTest {
    private fun audio(index: Int, title: String?, language: String = "eng", default: Boolean = false) =
        MediaStream(index, StreamKind.AUDIO, codec = "eac3", language = language, title = title, isDefault = default)

    @Test fun `described tracks are recognised by their names`() {
        for (t in listOf("Audio Description", "English - Descriptive Video Service", "English AD 5.1", "[AD] Stereo", "DVS", "Audiodeskription", "For the visually impaired")) {
            assertTrue(t, AudioDescription.isDescribed(audio(1, t)))
        }
        for (t in listOf("English", "Commentary with the director", "Dolby Digital", "Headphones", "Adventure mix", null)) {
            assertFalse(t.toString(), AudioDescription.isDescribed(audio(1, t)))
        }
    }

    @Test fun `on picks the described track in the same language, off steers away from one`() {
        val source = MediaSource("s", "mkv", null, listOf(audio(1, "English", default = true), audio(2, "Français", "fre"), audio(3, "English AD"), audio(4, "Audiodescription", "fre")))
        assertEquals(3, AudioDescription.apply(source, null, wantDescribed = true))
        assertEquals(4, AudioDescription.apply(source, 2, wantDescribed = true))
        assertEquals(1, AudioDescription.apply(source, 1, wantDescribed = false))
        // The server would start the described one: off moves back to the main track.
        assertEquals(1, AudioDescription.apply(source, 3, wantDescribed = false))
        // No described track in that language: the language stays.
        val noFrenchAd = MediaSource("s", "mkv", null, listOf(audio(1, "English AD"), audio(2, "Français", "fre")))
        assertEquals(2, AudioDescription.apply(noFrenchAd, 2, wantDescribed = true))
    }

    @Test fun `a track picked for the show wins over the preference`() {
        val source = MediaSource("s", "mkv", null, listOf(audio(1, "English", default = true), audio(2, "English AD")))
        assertEquals(2 to -1, TrackSelection.initial(source, null, describedAudio = true))
        val picked = LanguageChoice(audioLanguage = "eng", audioTitle = "English", version = TrackMemory.VERSION)
        assertEquals(1, TrackSelection.initial(source, picked, describedAudio = true).first)
    }
}

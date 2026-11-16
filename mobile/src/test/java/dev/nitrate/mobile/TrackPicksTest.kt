package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tracks picked on a details page are kept: for good for a film, for the whole show for an episode. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackPicksTest {
    @Test fun picksAreRememberedAndComeBack() {
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val c = AppContainer(activity, "test", "0", serverOverride = FakeServer())
            runBlocking {
                c.displaySettings.import("user-1", emptyMap())
                val film = MediaItem("m1", "Nitrate", ItemKind.MOVIE)
                // The server's picks to start with: English, subtitles off.
                val first = c.trackSetup(film)
                assertEquals(1 to -1, first.audioIndex to first.subtitleIndex)
                c.pickTrack(film, first, StreamKind.AUDIO, 2)
                c.pickTrack(film, c.trackSetup(film), StreamKind.SUBTITLE, 4)
                // Opening the film again (a new screen): Japanese with the signs track.
                val again = c.trackSetup(film)
                assertEquals(2 to 4, again.audioIndex to again.subtitleIndex)
                assertEquals("Japanese · stereo", again.audioName)
                assertEquals("English (Signs & Songs, Forced)", again.subtitleName)
                // An episode's pick carries to the next episode of the show.
                val ep1 = MediaItem("e1", "One", ItemKind.EPISODE, seriesId = "s1")
                c.pickTrack(ep1, c.trackSetup(ep1), StreamKind.AUDIO, 2)
                assertEquals(2, c.trackSetup(MediaItem("e2", "Two", ItemKind.EPISODE, seriesId = "s1")).audioIndex)
                // Another film still starts with the server's picks.
                assertEquals(1, c.trackSetup(MediaItem("m2", "Other", ItemKind.MOVIE)).audioIndex)
            }
        }
    }
}

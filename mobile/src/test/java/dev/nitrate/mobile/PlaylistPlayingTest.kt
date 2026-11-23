package dev.nitrate.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.playlists.PlaylistPlaying
import dev.nitrate.android.storage.DataCache
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Continue your playlists" stays light for the server: Home asks on every reload, so after the first
 * time it's one small "played lately" request; playlists' contents are read only for those being
 * watched, and kept.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaylistPlayingTest {
    private val day = 24 * 60 * 60_000L
    private val now = 1_800_000_000_000L
    private val asked = mutableListOf<String>()
    private val fake = FakeServer()

    private fun episode(s: Int, n: Int, playedDaysAgo: Int?) = MediaItem(
        "s${s}e$n", "Episode $n", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonId = "season-$s", seasonNumber = s, indexNumber = n,
    ).let { e -> if (playedDaysAgo == null) e else e.copy(user = e.user.copy(played = true, lastPlayedMs = now - playedDaysAgo * day)) }

    private val server = object : MediaServer by fake {
        override suspend fun playlists(): List<MediaItem> { asked += "playlists"; return (1..20).map { MediaItem("p$it", "Playlist $it", ItemKind.PLAYLIST) } }
        override suspend fun playlistMembers(playlistId: String): List<MediaItem> {
            asked += "members:$playlistId"
            return if (playlistId == "p1") listOf(MediaItem("m1", "Nitrate", ItemKind.MOVIE), MediaItem("series-1", "The Night Reel", ItemKind.SERIES))
            else listOf(MediaItem("m9-$playlistId", "Other", ItemKind.MOVIE))
        }
        override suspend fun playlistItems(playlistId: String): List<MediaItem> {
            asked += "items:$playlistId"
            return if (playlistId == "p1") listOf(MediaItem("m1", "Nitrate", ItemKind.MOVIE), MediaItem("series-1", "The Night Reel", ItemKind.SERIES)) else emptyList()
        }
        override suspend fun allEpisodes(seriesId: String): List<MediaItem> { asked += "episodes:$seriesId"; return (1..4).map { episode(1, it, null) } }
        override suspend fun recentlyPlayed(limit: Int): List<MediaItem> { asked += "recent"; return listOf(episode(1, 2, 1), episode(1, 1, 3)) }
    }

    @Test
    fun homeReloadsAskOnlyWhatWasPlayedLately() = runBlocking {
        val playing = PlaylistPlaying(server, DataCache(ApplicationProvider.getApplicationContext<Context>()), nowMs = { now })
        val row = playing.continueRow()
        assertNotNull(row)
        // Carries on after the last episode played (S1 E2), in the playlist's order.
        assertEquals(listOf("s1e3"), row!!.items.map { it.id })
        // The first time: the list, every playlist's members (light), and only the watched one's contents.
        assertEquals(1, asked.count { it.startsWith("items:") })
        assertEquals(1, asked.count { it.startsWith("episodes:") })
        assertEquals(20, asked.count { it.startsWith("members:") })
        asked.clear()
        // Every reload after that: one small request.
        repeat(5) { playing.continueRow() }
        assertEquals(List(5) { "recent" }, asked)
    }

    @Test
    fun nothingPlayedLatelyAsksNothingElse() = runBlocking {
        val quiet = object : MediaServer by server {
            override suspend fun recentlyPlayed(limit: Int): List<MediaItem> { asked += "recent"; return emptyList() }
        }
        val playing = PlaylistPlaying(quiet, null, nowMs = { now })
        assertEquals(null, playing.continueRow())
        assertEquals(listOf("recent"), asked)
    }
}

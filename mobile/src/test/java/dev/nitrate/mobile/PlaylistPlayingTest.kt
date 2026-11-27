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
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Playlists keep their own record, apart from the user's own: a playlist is watched through only by
 * sittings started from it (never guessed from what was played), its place moves only by those, and
 * "Continue your playlists" reads only the records and the playlists' kept contents.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaylistPlayingTest {
    private val day = 24 * 60 * 60_000L
    private val now = 1_800_000_000_000L
    private val asked = mutableListOf<String>()
    private val fake = FakeServer()
    private val kept = mutableListOf<String>()
    private val stops = kotlinx.coroutines.flow.MutableSharedFlow<dev.nitrate.core.model.PlayStop>(extraBufferCapacity = 4)

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
        override suspend fun userData(itemId: String) = dev.nitrate.core.model.RemoteUserData(played = false, positionMs = 0, lastPlayedMs = null)
        override suspend fun keepOwnState(itemId: String, playlistId: String, before: dev.nitrate.core.model.UserState) { kept += itemId }
        override val playStops = stops
    }


    @Test
    fun activePlaylistsAreSeededOnceThenOnlyRecordsAreRead() = runBlocking {
        val playing = PlaylistPlaying(server, DataCache(ApplicationProvider.getApplicationContext<Context>()), nowMs = { now })
        // The first time: playlists being watched by the user's own marks get a record from those marks.
        val row = playing.continueRow()
        assertEquals(listOf("s1e3"), row!!.items.map { it.id })
        // The card stands for its playlist: playing it is a sitting of p1.
        assertEquals("p1", row.items.single().playlistId)
        asked.clear()
        // Every reload after that: nothing asked of the server (records, and contents kept).
        repeat(3) { playing.continueRow() }
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun watchingOnYourOwnDoesntMoveThePlaylist() = runBlocking {
        val playing = PlaylistPlaying(server, null, nowMs = { now })
        playing.continueRow()
        // S1 E3 and E4 watched on their own since: the playlist still carries on from S1 E3.
        val ownWatch = object : MediaServer by server {
            override suspend fun recentlyPlayed(limit: Int): List<MediaItem> = listOf(episode(1, 4, 0), episode(1, 3, 0), episode(1, 2, 1))
        }
        val again = PlaylistPlaying(ownWatch, null, nowMs = { now }, records = recordsOf(playing))
        assertEquals(listOf("s1e3"), again.continueRow()!!.items.map { it.id })
    }

    private suspend fun recordsOf(playing: PlaylistPlaying) = dev.nitrate.android.playlists.PlaylistRecordStore.InMemory().also { store ->
        playing.loadRecords().forEach { store.put(it) }
    }

    @Test
    fun onlyAPlayStartedFromAPlaylistIsASittingOfIt() = runBlocking {
        val playing = PlaylistPlaying(server, null, nowMs = { now })
        playing.continueRow()
        // S1 E3 is where p1 carries on, but played from Continue Watching: not a sitting, nothing guessed.
        assertEquals(null, playing.contextOf(episode(1, 3, null)))
        assertEquals(emptyList<String>(), kept)
        // Started from the playlist (its card, its page): a sitting, the user's own state kept, the next in its order.
        playing.startedFrom("p1", "s1e3")
        val ctx = playing.contextOf(episode(1, 3, null))!!
        assertEquals("s1e4", ctx.next?.id)
        assertEquals("p1", ctx.next?.playlistId)
        assertEquals(listOf("s1e3"), kept)
        // Played on its own after all: not a sitting any more.
        playing.playsOnItsOwn("s1e3")
        assertEquals(null, playing.contextOf(episode(1, 3, null)))
    }

    @Test
    fun aSittingsStopGoesToThePlaylistsOwnRecord() = runBlocking {
        val playing = PlaylistPlaying(server, null, nowMs = { now })
        playing.continueRow()
        val following = launch(kotlinx.coroutines.Dispatchers.Unconfined) { playing.followStops() }
        playing.startedFrom("p1", "s1e3")
        playing.contextOf(episode(1, 3, null))
        // Stopped 20 minutes in (runtime unknown here): its resume point in the playlist.
        stops.emit(dev.nitrate.core.model.PlayStop("s1e3", 1_200_000, forGroup = false))
        kotlinx.coroutines.delay(50)
        assertEquals(1_200_000L, playing.resumeIn("p1", "s1e3"))
        val entries = playing.entries("p1")
        assertEquals(1_200_000L, entries.first { it.id == "s1e3" }.user.positionMs)
        // A group's playback is never a playlist's.
        playing.startedFrom("p1", "s1e4")
        playing.contextOf(episode(1, 4, null))
        stops.emit(dev.nitrate.core.model.PlayStop("s1e4", 1_200_000, forGroup = true))
        kotlinx.coroutines.delay(50)
        assertEquals(0L, playing.resumeIn("p1", "s1e4"))
        following.cancel()
    }

    /** With the Companion, the server moved the record on as it heard the stop: the app reads it, it doesn't work it out. */
    @Test
    fun aStopTheServerRecordedIsReadBack() = runBlocking {
        val store = dev.nitrate.android.playlists.PlaylistRecordStore.InMemory()
        val playing = PlaylistPlaying(server, null, nowMs = { now }, records = store)
        playing.continueRow()
        val following = launch(kotlinx.coroutines.Dispatchers.Unconfined) { playing.followStops() }
        playing.startedFrom("p1", "s1e3")
        playing.contextOf(episode(1, 3, null))
        // As the Companion leaves it: watched to the end in the playlist.
        val before = store.get("p1")!!
        store.put(before.copy(watched = before.watched + "s1e3", lastId = "s1e3", lastAtMs = now))
        // The app's own stop said 20 minutes in, but the server's record is what counts.
        stops.emit(dev.nitrate.core.model.PlayStop("s1e3", 1_200_000, forGroup = false, recordedByServer = true))
        kotlinx.coroutines.delay(50)
        assertTrue(playing.seenInPlaylists("s1e3"))
        assertEquals(0L, playing.resumeIn("p1", "s1e3"))
        following.cancel()
    }

    @Test
    fun markingInThePlaylistLeavesTheUsersOwnMarks() = runBlocking {
        var setPlayed = 0
        val counting = object : MediaServer by server {
            override suspend fun setPlayed(itemId: String, played: Boolean) { setPlayed++ }
        }
        val playing = PlaylistPlaying(counting, null, nowMs = { now })
        playing.markUpTo("p1", "s1e3", watched = true)
        assertEquals(0, setPlayed)
        assertTrue(playing.entries("p1").first { it.id == "s1e3" }.user.played)
        assertTrue(playing.seenInPlaylists("s1e3"))
    }
}

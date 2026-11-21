package dev.nitrate.mobile

import androidx.media3.common.Player
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.MediaServer
import dev.nitrate.mobile.platform.RemoteSessionPlayer
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The notification's player: shows what the TV plays, and its buttons go to the TV through the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteSessionPlayerTest {
    private class Server : MediaServer by FakeServer() {
        val commands = mutableListOf<String>()
        override suspend fun sessionCommand(sessionId: String, command: SessionCommand) { commands += "$sessionId ${command.wire}" }
        override suspend fun seekSession(sessionId: String, positionMs: Long) { commands += "$sessionId seek $positionMs" }
    }

    @Test fun mirrorsTheTvAndSendsItsButtonsThere() = runBlocking {
        val server = Server()
        val player = RemoteSessionPlayer(server, "s1", MainScope())
        player.update(server.controllableSessions().first())
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertEquals("Nitrate", player.mediaMetadata.title)
        assertTrue(player.currentPosition >= 2_100_000)
        player.pause()
        player.seekTo(60_000)
        player.seekToNext()
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(listOf("s1 Pause", "s1 seek 60000", "s1 NextTrack"), server.commands)
    }
}

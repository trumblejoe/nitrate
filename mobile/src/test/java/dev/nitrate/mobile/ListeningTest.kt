package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.android.playback.AndroidListening
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.testing.FakeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** The listening player: one gapless queue, levelling, honest reports, and converting what won't play. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ListeningTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    /** Hands out local files as the songs' streams and keeps what was reported. */
    private class Server(val dir: File, val broken: Set<String> = emptySet()) : MediaServer by FakeServer() {
        val reports = mutableListOf<String>()
        override fun listenUrl(itemId: String, convert: Boolean, playSessionId: String): String =
            if (!convert && itemId in broken) File(dir, "broken-$itemId.mp3").apply { writeBytes(ByteArray(64) { 7 }) }.absolutePath
            else wav(File(dir, "$itemId-$convert.wav")).absolutePath
        override suspend fun reportListen(itemId: String, playSessionId: String, positionMs: Long, paused: Boolean, converted: Boolean, start: Boolean) {
            if (start) reports += "start $itemId${if (converted) " converted" else ""}"
        }
        override fun reportListenStopped(itemId: String, playSessionId: String, positionMs: Long) { reports += "stop $itemId at $positionMs" }
    }

    private fun song(id: String, gain: Double? = null) = MediaItem(id, "Song $id", ItemKind.AUDIO, runtimeMs = 30_000, normalizationGain = gain, container = "flac")

    private fun until(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && !what()) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(10) }
    }

    /** Downloaded songs: their files, and the plays kept for the server. */
    private class Downloads(val files: Map<String, String>) : dev.nitrate.core.music.ListenDownloads {
        val played = mutableListOf<String>()
        override fun file(itemId: String) = files[itemId]
        override suspend fun played(itemId: String, positionMs: Long, durationMs: Long, ended: Boolean) { played += itemId }
    }

    private fun run(server: (File) -> Server, downloads: (File) -> Downloads? = { null }, body: (AndroidListening, Server) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val s = server(activity.cacheDir)
            val container = AppContainer(activity, "test", "0", serverOverride = s)
            val listening = AndroidListening(activity, s, container.playbackSettings, downloads(activity.cacheDir))
            body(listening, s)
            listening.stop()
            listening.player.release()
        }
    }

    @Test fun aQueuePlaysLevelledAndSkipsAreNotCountedAsPlayed() = run({ Server(it) }) { l, server ->
        l.play(listOf(song("a", gain = -6.0206), song("b"), song("c")), startIndex = 0)
        until { l.player.playbackState == Player.STATE_READY && l.state.value.index == 0 }
        assertEquals(listOf("a", "b", "c"), l.state.value.queue.map { it.id })
        // Every song brought to the same loudness: -6 dB is half volume.
        until { kotlin.math.abs(l.player.volume - 0.5f) < 0.01f }
        assertEquals(0.5f, l.player.volume, 0.01f)
        l.next()
        until { l.state.value.index == 1 }
        until { server.reports.any { it.startsWith("stop a") } }
        // Skipped right away: reported where it was left, nowhere near its end.
        val stopA = server.reports.first { it.startsWith("stop a") }.substringAfter("at ").toLong()
        assertTrue("stop at $stopA", stopA < 1_500)
        assertTrue(server.reports.toString(), "start b" in server.reports)
        // Just started: Previous goes back a song rather than restarting this one.
        l.previous()
        until { l.state.value.index == 0 }
        assertEquals("a", l.state.value.current?.id)
    }

    @Test fun shuffleKeepsTheQueueInItsPlayingOrder() = run({ Server(it) }) { l, _ ->
        l.play((1..6).map { song("s$it") }, shuffle = true)
        until { l.player.playbackState == Player.STATE_READY }
        val st = l.state.value
        assertTrue(st.shuffle)
        assertEquals(6, st.queue.size)
        // The queue lists what plays next in the order it will play, starting with the song on.
        assertEquals(0, st.index)
        assertEquals(l.player.currentMediaItem?.mediaId, st.current?.id)
    }

    @Test fun aSongThatWontPlayAsItIsPlaysConverted() = run({ Server(it, broken = setOf("x")) }) { l, server ->
        l.play(listOf(song("x"), song("y")))
        until { "start x converted" in server.reports || l.state.value.index == 1 }
        until { l.player.playbackState == Player.STATE_READY }
        assertEquals("x", l.state.value.current?.id)
        assertTrue(server.reports.toString(), "start x converted" in server.reports)
    }

    @Test fun aDownloadedSongPlaysFromTheDeviceAndKeepsItsPlay() {
        lateinit var d: Downloads
        run({ Server(it, broken = setOf("x")) }, { dir -> Downloads(mapOf("x" to wav(File(dir, "downloaded-x.wav")).absolutePath)).also { d = it } }) { l, server ->
            l.play(listOf(song("x"), song("y")))
            until { l.player.playbackState == Player.STATE_READY }
            // The server's copy would fail: the device's file plays, nothing converted.
            assertEquals("x", l.state.value.current?.id)
            assertTrue(server.reports.toString(), "start x" in server.reports && "start x converted" !in server.reports)
            l.next()
            until { d.played.isNotEmpty() }
            assertEquals(listOf("x"), d.played)
        }
    }

    @Test fun aBookResumesStepsByChapterAndSleepsAtTheChaptersEnd() = run({ Server(it) }) { l, _ ->
        val book = MediaItem(
            "b", "The Long Reel", ItemKind.AUDIOBOOK, runtimeMs = 30_000, container = "m4b",
            chapters = listOf(dev.nitrate.core.model.Chapter(0, "One"), dev.nitrate.core.model.Chapter(10_000, "Two"), dev.nitrate.core.model.Chapter(20_000, "Three")),
        )
        // Carries on where it was left.
        l.play(listOf(book), startMs = 4_000)
        until { l.player.playbackState == Player.STATE_READY }
        assertTrue("resumed at ${l.player.currentPosition}", l.player.currentPosition in 3_000..9_000)
        // Next is the next chapter, not the next item.
        l.next()
        until { l.player.currentPosition >= 10_000 }
        assertTrue("at ${l.player.currentPosition}", l.player.currentPosition in 10_000..14_000)
        // Back 15 s from here is the start.
        l.skipBy(-15_000)
        until { l.player.currentPosition < 4_000 }
        // Sleep at the end of the chapter: playing stops as "One" ends, before "Two" gets far.
        l.seekTo(8_500)
        until { l.player.currentPosition >= 8_500 }
        l.setSpeed(1f)
        l.setSleep(dev.nitrate.core.music.Sleep.EndOfChapter)
        l.player.play()
        until { !l.player.playWhenReady }
        assertTrue("stopped at ${l.player.currentPosition}", l.player.currentPosition in 8_500..11_000)
        assertEquals(null, l.state.value.sleep)
    }

    @Test fun theCarsRequestsPlayThroughTheListeningPlayer() = run({ Server(it) }) { l, server ->
        val car = dev.nitrate.mobile.platform.CarPlayer(l.player) { l }
        // The car asked for song b of an album: it arrives as one stand-in item.
        val standIn = dev.nitrate.mobile.platform.CarBrowsing.hold(dev.nitrate.core.car.CarQueue(listOf(song("a"), song("b"), song("c")), startIndex = 1))
        car.setMediaItems(mutableListOf(standIn), 0, 0)
        car.prepare()
        car.play()
        until { l.player.playbackState == Player.STATE_READY && l.state.value.current?.id == "b" }
        assertEquals(listOf("a", "b", "c"), l.state.value.queue.map { it.id })
        assertTrue(server.reports.toString(), "start b" in server.reports)
        // The car's Next is the listening player's.
        car.seekToNext()
        until { l.state.value.current?.id == "c" }
        assertEquals("c", l.state.value.current?.id)
        // A stand-in is used once.
        assertEquals(null, dev.nitrate.mobile.platform.CarBrowsing.take(standIn.mediaId))
    }

    companion object {
        /** Thirty seconds of silent 16-bit mono PCM: long enough that nothing ends while a busy machine runs the test. */
        fun wav(file: File): File {
            if (file.exists()) return file
            val rate = 8000
            val data = ByteArray(rate * 2 * 30)
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            file.writeBytes(
                "RIFF".toByteArray() + le32(36 + data.size) + "WAVE".toByteArray() +
                    "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(rate) + le32(rate * 2) + le16(2) + le16(16) +
                    "data".toByteArray() + le32(data.size) + data,
            )
            return file
        }
    }
}

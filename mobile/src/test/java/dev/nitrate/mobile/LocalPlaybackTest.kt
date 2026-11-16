package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.testing.FakeServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** A downloaded file plays from the device: the player must read local files, not only http. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalPlaybackTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    /** One second of silent 16-bit mono PCM in a WAV file. */
    private fun wav(file: File): File {
        val rate = 8000
        val data = ByteArray(rate * 2)
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        file.writeBytes(
            "RIFF".toByteArray() + le32(36 + data.size) + "WAVE".toByteArray() +
                "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(rate) + le32(rate * 2) + le16(2) + le16(16) +
                "data".toByteArray() + le32(data.size) + data,
        )
        return file
    }

    @Test fun downloadedFilePlays() {
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val container = AppContainer(activity, "test", "0", serverOverride = FakeServer())
            val session = PlaybackSession(activity, container.server, container.playbackSettings, "This device", onEnded = {})
            val file = wav(File(activity.cacheDir, "download.wav"))
            val plan = PlaybackPlan(
                itemId = "m1", url = file.absolutePath, method = PlayMethod.DIRECT_PLAY, playSessionId = null,
                source = MediaSource("s1", "wav", null, emptyList()), isHls = false, audioIndex = null, subtitleIndex = -1,
                externalSubtitles = emptyList(), transcodeReasons = emptyList(),
            )
            session.loadLocal(MediaItem("m1", "Downloaded", ItemKind.MOVIE), plan, 0) { _, _, _ -> }
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && session.error.value == null && session.player.playbackState != Player.STATE_READY) {
                ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(10)
            }
            println("EVENTS " + session.eventLog().joinToString(" | "))
            assertEquals(null, session.error.value)
            assertEquals(Player.STATE_READY, session.player.playbackState)
            session.release()
        }
    }
}

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
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.testing.FakeServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Audio description in the player: a download with two English tracks, the second named as
 * described. The preference picks it (or keeps the main one), with nothing picked for the title.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class DescribedAudioTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private fun play(want: Boolean): Int? {
        var chosen: Int? = null
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val container = AppContainer(activity, "test", "0", serverOverride = FakeServer())
            val session = PlaybackSession(activity, container.server, container.playbackSettings, "This device", onEnded = {}, describedAudio = { want })
            val file = File(activity.cacheDir, "silence.wav").apply { wav(this) }
            val streams = listOf(
                MediaStream(0, StreamKind.AUDIO, codec = "aac", language = "eng", title = "English", isDefault = true),
                MediaStream(1, StreamKind.AUDIO, codec = "aac", language = "eng", title = "English (Audio Description)"),
            )
            val plan = PlaybackPlan(
                itemId = "m1", url = file.absolutePath, method = PlayMethod.DIRECT_PLAY, playSessionId = null,
                source = MediaSource("s1", "wav", null, streams), isHls = false, audioIndex = 0, subtitleIndex = -1,
                externalSubtitles = emptyList(), transcodeReasons = emptyList(),
            )
            session.loadLocal(MediaItem("m1", "Film", ItemKind.MOVIE), plan, 0) { _, _, _ -> }
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && session.player.playbackState != Player.STATE_READY) {
                ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(10)
            }
            repeat(50) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS) }
            println("EVENTS " + session.eventLog().joinToString(" | "))
            chosen = session.plan.value?.audioIndex
            session.release()
        }
        return chosen
    }

    /** One second of silent 16-bit mono PCM: the tracks in the plan are what's tested, not the file. */
    private fun wav(file: File) {
        val data = ByteArray(16_000)
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        file.writeBytes("RIFF".toByteArray() + le32(36 + data.size) + "WAVE".toByteArray() + "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(8000) + le32(16000) + le16(2) + le16(16) + "data".toByteArray() + le32(data.size) + data)
    }

    @Test fun `the described track starts when the preference is on`() {
        assertEquals(1, play(want = true))
    }

    @Test fun `and not when it's off`() {
        assertEquals(0, play(want = false))
    }
}

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
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
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
    private fun wav(file: File, seconds: Int = 1): File {
        val rate = 8000
        val data = ByteArray(rate * 2 * seconds)
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

    @Test fun downloadedStyledSubtitlesKeepTheirStyle() = styled(bom = false)

    /** Jellyfin writes subtitles it extracts from a video with a byte-order mark first. */
    @Test fun extractedStyledSubtitlesKeepTheirStyle() = styled(bom = true)

    private fun styled(bom: Boolean) {
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val container = AppContainer(activity, "test", "0", serverOverride = FakeServer())
            val session = PlaybackSession(activity, container.server, container.playbackSettings, "This device", onEnded = {})
            val file = wav(File(activity.cacheDir, "styled.wav"), seconds = 10)
            val ass = File(activity.cacheDir, "sub-3.ass").apply {
                writeText((if (bom) "\uFEFF" else "") +
                    """
                    [Script Info]
                    ScriptType: v4.00+
                    PlayResX: 640
                    PlayResY: 360

                    [V4+ Styles]
                    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                    Style: Default,Arial,28,&H0000FFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

                    [Events]
                    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                    Dialogue: 0,0:00:00.00,0:00:09.00,Default,,0,0,0,,Styled line
                    """.trimIndent(),
                )
            }
            val streams = listOf(dev.nitrate.core.model.MediaStream(3, dev.nitrate.core.model.StreamKind.SUBTITLE, codec = "ass", language = "eng", isExternal = true, delivery = dev.nitrate.core.model.SubtitleDelivery.EXTERNAL))
            val plan = PlaybackPlan(
                itemId = "m2", url = file.absolutePath, method = PlayMethod.DIRECT_PLAY, playSessionId = null,
                source = MediaSource("s2", "wav", null, streams), isHls = false, audioIndex = null, subtitleIndex = 3,
                externalSubtitles = listOf(dev.nitrate.core.model.ExternalSubtitle(3, ass.absolutePath, "text/x-ssa", "eng", "English")),
                transcodeReasons = emptyList(),
            )
            assertEquals(true, plan.styledSubtitle)
            session.loadLocal(MediaItem("m2", "Downloaded", ItemKind.MOVIE), plan, 0) { _, _, _ -> }
            val end = System.currentTimeMillis() + 10_000
            var cues: List<androidx.media3.common.text.Cue> = emptyList()
            while (System.currentTimeMillis() < end && session.error.value == null && cues.isEmpty()) {
                ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(10)
                cues = session.player.currentCues.cues
            }
            println("EVENTS " + session.eventLog().joinToString(" | "))
            val text = cues.firstOrNull()?.text as? android.text.Spanned
            println("CUES " + cues.map { "${it.text} spans=" + ((it.text as? android.text.Spanned)?.let { t -> t.getSpans(0, t.length, Any::class.java).map { s -> s::class.simpleName } }) })
            assertEquals("Styled line", text?.toString())
            assertEquals(true, text!!.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).isNotEmpty())
            // The media info shows the track on, its styles drawn, and what the saved file holds.
            val info = runBlocking { session.mediaInfo(dev.nitrate.core.playback.StyledSubtitles.FILE) }
            println("INFO\n$info")
            assertTrue(info, "[on] subtitles" in info)
            assertTrue(info, "The file's own styles: shown" in info)
            assertTrue(info, "Styles: 1, lines of dialogue: 1" in info)
            session.release()
        }
    }
}

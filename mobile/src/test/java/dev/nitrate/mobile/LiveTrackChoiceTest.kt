package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.android.playback.PlaybackSession
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Plays a title from the local test Jellyfin with the real player and prints which audio and
 * subtitle tracks end up selected. Skipped unless the server is up and NITRATE_TRACK_ITEM names
 * a title (a system property file in the scratchpad, since Gradle's daemon keeps its own env).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveTrackChoiceTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val item: String? = runCatching { java.io.File(System.getProperty("java.io.tmpdir"), "nitrate-track-item").readText().trim() }.getOrNull()

    private fun pump(until: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!until() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
    }

    @Test fun selectedTracks() = play(convert = false)

    @Test fun selectedTracksWhenConverting() = play(convert = true)

    private fun play(convert: Boolean) {
        val up = runCatching { java.net.URL("http://localhost:8096/System/Info/Public").readText() }.isSuccess
        assumeTrue(up && item != null)
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val container = AppContainer(activity, "test", "0")
            runBlocking {
                container.server.login("http://localhost:8096", "nitrate", "nitrate-pw")
                container.playbackSettings.update { it.copy(allowDirectPlay = !convert, allowDirectStream = !convert) }
            }
            val session = PlaybackSession(activity, container.server, container.playbackSettings, "This device", onEnded = {})
            session.load(item!!, fromStart = true)
            pump { session.plan.value != null && !session.player.currentTracks.isEmpty }
            // Let the session apply its choice.
            repeat(50) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
            val plan = session.plan.value
            println("PLAN convert=$convert method=${plan?.method} audio=${plan?.audioIndex} subtitle=${plan?.subtitleIndex} url=" + plan?.url?.substringAfter("?")?.split("&")?.filter { it.contains("StreamIndex") || it.contains("SubtitleMethod") })
            session.player.currentTracks.groups.forEach { g ->
                val f = g.getTrackFormat(0)
                val type = when (g.type) { C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"; C.TRACK_TYPE_VIDEO -> "video"; else -> "other" }
                println("TRACK $type id=${f.id} lang=${f.language} label=${f.label} default=${f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0} selected=${g.isSelected} supported=${g.isSupported}")
            }
            session.release()
        }
    }
}

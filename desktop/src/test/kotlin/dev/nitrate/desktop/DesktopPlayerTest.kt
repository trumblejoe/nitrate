package dev.nitrate.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation.compose.rememberNavController
import dev.nitrate.android.AppContainer
import dev.nitrate.core.downloads.MemoryDownloadStore
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.update.UpdateChannel
import dev.nitrate.mobile.ui.AppActions
import dev.nitrate.mobile.ui.DesktopPlayer
import dev.nitrate.mobile.ui.DesktopPlayerSession
import dev.nitrate.mobile.ui.DesktopPlayerSettings
import dev.nitrate.mobile.ui.NitrateTheme
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop player with a fake mpv (no libmpv in CI): what it asks mpv to play, how it reports to
 * the server, switching tracks, falling back to the server's conversion, and the controls.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPlayerTest {
    private fun container(server: PlayingServer): AppContainer {
        val home = kotlin.io.path.createTempDirectory("nitrate-player-test").toFile()
        val platform = DesktopPlatform(DesktopVersion("0.1.0", 1, UpdateChannel.NONE), DesktopFolders(File(home, "data"), File(home, "cache"), File(home, "downloads")))
        return AppContainer(platform, "test", "0", serverOverride = server, downloadStoreOverride = MemoryDownloadStore())
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `plays the file as it is from the resume point, reports it, and switches tracks in mpv`() {
        val server = PlayingServer()
        val mpv = FakeMpvPlayer()
        val session = DesktopPlayerSession(container(server), reported = true, player = mpv)
        session.load("m1", fromStart = false, startMs = null)
        waitFor("the file") { mpv.loads.isNotEmpty() }
        val media = mpv.loads.single()
        assertTrue(media.url.endsWith("/Videos/m1/stream?static=true"))
        assertEquals(server.authorizationHeader(), media.headers["Authorization"])
        assertEquals(2_100.0, media.startSeconds, "from the resume point")
        assertEquals(1, media.audioId, "English, the server's pick, is the file's first audio")
        assertEquals(0, media.subtitleId)
        assertEquals(listOf("http://192.168.1.20:8096/Videos/m1/Subtitles/9/Stream.srt"), media.subtitleUrls)
        waitFor("the start report") { "start DIRECT_PLAY at 2100000" in server.reports }

        session.selectTrack(StreamKind.AUDIO, 2)
        waitFor("Japanese") { "aid=2" in mpv.calls }
        // The French file comes after the file's two subtitles.
        session.selectTrack(StreamKind.SUBTITLE, 9)
        waitFor("French") { "sid=3" in mpv.calls }
        assertEquals(1, mpv.loads.size, "switched in mpv, never restarted")
        waitFor("the plan") { session.plan.value?.let { it.audioIndex == 2 && it.subtitleIndex == 9 } == true }

        session.release()
        assertTrue(mpv.released)
        assertTrue("stopped DIRECT_PLAY" in server.reports)
    }

    @Test
    fun `when mpv can't play the file, the server converts it once from the same spot`() {
        val server = PlayingServer()
        val mpv = FakeMpvPlayer()
        val session = DesktopPlayerSession(container(server), reported = true, player = mpv)
        session.load("m2", fromStart = true, startMs = null)
        waitFor("the file") { mpv.loads.isNotEmpty() }
        mpv.state.update { it.copy(positionSeconds = 30.0, error = "unsupported codec") }
        waitFor("the conversion") { mpv.loads.size == 2 }
        assertEquals(listOf(false, true), server.resolved)
        assertTrue(mpv.loads[1].url.endsWith("master.m3u8"))
        waitFor("reports") { "start TRANSCODE at 30000" in server.reports }
        assertTrue("stopped DIRECT_PLAY" in server.reports)
        session.release()
    }

    private fun SemanticsNodeInteractionsProvider.save(name: String) {
        val out = File("build/outputs/desktop").apply { mkdirs() }
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    @Test
    fun `the controls play, pause, jump, skip the intro and pick tracks`() = runDesktopComposeUiTest(1280, 720) {
        val server = PlayingServer(segments = listOf(PlayingServer.INTRO))
        val c = container(server)
        val mpv = FakeMpvPlayer()
        val session = DesktopPlayerSession(c, reported = true, player = mpv)
        session.load("m1", fromStart = true, startMs = null)
        setContent {
            NitrateTheme {
                val actions = AppActions(c, rememberNavController(), play = { _, _ -> })
                DesktopPlayer(actions, session, DesktopPlayerSettings.shared(c.platform.filesDir), local = false, onExit = {}, onEnded = {}, onPlayNext = null, clipEndMs = null)
            }
        }
        waitUntil(timeoutMillis = 10_000) { mpv.loads.isNotEmpty() && onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasContentDescription("Pause"))[0].performClick()
        waitUntil(timeoutMillis = 5_000) { "pause" in mpv.calls }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() }

        // The keyboard: Space plays, → jumps 10 seconds.
        onNodeWithTag("player").performKeyInput { pressKey(Key.Spacebar) }
        waitUntil(timeoutMillis = 5_000) { "play" in mpv.calls }
        onNodeWithTag("player").performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(timeoutMillis = 5_000) { "seek=10" in mpv.calls }

        // In the intro: "Skip intro" jumps to its end.
        mpv.state.update { it.copy(positionSeconds = 70.0) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Skip intro")).fetchSemanticsNodes().isNotEmpty() }
        save("desktop-player")
        val silent = dev.nitrate.testing.A11y.silent(this)
        check(silent.isEmpty()) { "a screen reader would say nothing for:\n" + silent.joinToString("\n") }
        onAllNodes(hasText("Skip intro"))[0].performClick()
        waitUntil(timeoutMillis = 5_000) { "seek=120" in mpv.calls }

        // Audio: the file's tracks, switched in mpv.
        onAllNodes(hasText("Audio"))[0].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Japanese", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Japanese", substring = true))[0].performClick()
        waitUntil(timeoutMillis = 5_000) { "aid=2" in mpv.calls }
        session.release()
    }
}

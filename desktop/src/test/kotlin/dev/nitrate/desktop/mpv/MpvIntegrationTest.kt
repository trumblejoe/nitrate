package dev.nitrate.desktop.mpv

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.nitrate.mobile.ui.MpvConfig
import dev.nitrate.mobile.ui.MpvMedia
import dev.nitrate.mobile.ui.VideoOutput
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real libmpv through JNA, when this machine has one (a developer's Linux or macOS with mpv
 * installed, or NITRATE_MPV_DIR pointing at the Windows build) and ffmpeg to make a clip; skipped
 * otherwise, as on CI. Plays a few seconds of colour bars with the software picture and checks a
 * frame arrives, that it pauses and seeks.
 */
@OptIn(ExperimentalTestApi::class)
class MpvIntegrationTest {
    @Test
    fun `libmpv plays a clip into Nitrate's picture`() {
        val lib = LibMpvLoader.load()
        assumeTrue("no libmpv here", lib is LibMpvLoader.Result.Loaded)
        val clip = File(kotlin.io.path.createTempDirectory("nitrate-mpv").toFile(), "bars.mkv")
        val made = runCatching {
            ProcessBuilder("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "smptebars=size=640x360:rate=25", "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "6", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", clip.path).inheritIO().start().waitFor() == 0
        }.getOrDefault(false)
        assumeTrue("no ffmpeg to make a clip", made && clip.exists())

        val api = JnaMpvApi.create(lib as LibMpvLoader.Result.Loaded)!!
        // No sound device on a build machine: mpv's null output.
        api.setOption("ao", "null")
        val player = LibMpvPlayer(api, MpvConfig(VideoOutput.SOFTWARE, hardwareDecoding = false, spdif = "", volume = 100, userAgent = "Nitrate/test"), windowId = { 0 })
        try {
            runDesktopComposeUiTest(320, 180) {
                setContent { player.Video(Modifier.size(320.dp, 180.dp)) }
                player.load(MpvMedia(clip.absolutePath, emptyMap(), startSeconds = 1.0, audioId = null, subtitleId = 0))
                waitUntil(timeoutMillis = 15_000) { player.state.value.loaded && player.state.value.durationSeconds > 5 }
                waitUntil(timeoutMillis = 15_000) { player.state.value.positionSeconds > 1.2 }
                waitUntil(timeoutMillis = 15_000) { player.framesDrawn > 0 }
                // A frame of the bars: something bright in the picture.
                waitUntil(timeoutMillis = 15_000) {
                    val img = onRoot().captureToImage().toAwtImage()
                    (0 until img.width step 8).any { x -> (img.getRGB(x, img.height / 3) and 0xffffff).let { (it shr 16 and 0xff) + (it shr 8 and 0xff) + (it and 0xff) } > 300 }
                }
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File("build/outputs/desktop").apply { mkdirs() }, "desktop-mpv-frame.png"))
                player.pause()
                waitUntil(timeoutMillis = 5_000) { player.state.value.paused }
                player.seek(4.0)
                waitUntil(timeoutMillis = 5_000) { player.state.value.positionSeconds in 3.5..4.5 }
                assertTrue(player.describe().any { it.first == "Video decoder" })
            }
        } finally {
            player.release()
        }
    }
}

package dev.nitrate.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import dev.nitrate.android.AppContainer
import dev.nitrate.core.downloads.MemoryDownloadStore
import dev.nitrate.core.update.UpdateChannel
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop window as people first see it, against [FakeServer]: Home, then Settings (two panes
 * in a wide window), drawn offscreen. Pictures land in build/outputs/desktop for a look.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopWindowTest {
    private fun container(): AppContainer {
        val home = kotlin.io.path.createTempDirectory("nitrate-desktop-test").toFile()
        val platform = DesktopPlatform(
            DesktopVersion("0.1.0", 1, UpdateChannel.NONE),
            DesktopFolders(File(home, "data"), File(home, "cache"), File(home, "downloads")),
        )
        return AppContainer(platform, "test", "0", serverOverride = FakeServer(), downloadStoreOverride = MemoryDownloadStore())
    }

    private fun SemanticsNodeInteractionsProvider.save(name: String) {
        val out = File("build/outputs/desktop").apply { mkdirs() }
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun SemanticsNodeInteractionsProvider.assertSpeaks(name: String) {
        val silent = dev.nitrate.testing.A11y.silent(this)
        check(silent.isEmpty()) { "$name: a screen reader would say nothing for:\n" + silent.joinToString("\n") }
    }

    private val settingsTab = hasText("Settings") or hasContentDescription("Settings")

    @Test
    fun opensHome() = runDesktopComposeUiTest(1280, 800) {
        val c = container()
        setContent { DesktopContent(c, MutableStateFlow(null), c.platform.cacheDir) }
        // Home's rows from the fake library.
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("Nitrate", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        save("desktop-home")
        assertTrue(onAllNodes(settingsTab).fetchSemanticsNodes().isNotEmpty(), "the Settings tab is there")
        assertSpeaks("desktop-home")
    }

    @Test
    fun opensSettingsInTwoPanes() = runDesktopComposeUiTest(1280, 800) {
        val c = container()
        setContent { DesktopContent(c, MutableStateFlow(null), c.platform.cacheDir) }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(settingsTab).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(settingsTab).onFirst().performClick()
        // Wide: the sections on the left and a page beside them.
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("Display", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        save("desktop-settings")
        assertSpeaks("desktop-settings")
    }
}

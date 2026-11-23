package dev.nitrate.desktop

import dev.nitrate.android.update.UpdateState
import dev.nitrate.core.update.UpdateChannel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the desktop app keeps things, and how it finds its updates. */
class DesktopPlatformTest {
    @Test
    fun `folders follow each system's conventions`() {
        val home = System.getProperty("user.home")
        val win = DesktopFolders.system(env = mapOf("APPDATA" to "C:\\Users\\a\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local")::get, os = "Windows 11")
        assertEquals(File("C:\\Users\\a\\AppData\\Roaming", "Nitrate"), win.data)
        assertEquals(File("C:\\Users\\a\\AppData\\Local", "Nitrate\\cache"), win.cache)
        assertEquals(File(home, "Videos\\Nitrate"), win.downloads)
        val linux = DesktopFolders.system(env = mapOf("XDG_DATA_HOME" to "/x/data")::get, os = "Linux")
        assertEquals(File("/x/data", "nitrate"), linux.data)
        assertEquals(File("$home/.cache", "nitrate"), linux.cache)
        val mac = DesktopFolders.system(env = { null }, os = "Mac OS X")
        assertEquals(File(home, "Library/Application Support/Nitrate"), mac.data)
        val one = DesktopFolders.system(env = mapOf("NITRATE_HOME" to "/tmp/n")::get, os = "Windows 11")
        assertEquals(File("/tmp/n", "data"), one.data)
    }

    @Test
    fun `the updater offers the newest installer on its channel and never updates local builds`() {
        val dir = kotlin.io.path.createTempDirectory("nitrate-updates").toFile()
        val releases = """[{"tag_name":"dev-30","name":"Nitrate Dev","prerelease":true,"draft":false,"body":"- Windows app\n<!-- nitrate-version-code: 1030 -->",
            "assets":[{"name":"nitrate-desktop-0.0.0-dev.30.msi","browser_download_url":"https://example/d.msi"},{"name":"SHA256SUMS","browser_download_url":"https://example/SHA256SUMS"}]}]"""
        val updater = DesktopUpdater(DesktopVersion("0.0.0-dev.20", 1020, UpdateChannel.DEV), File(dir, "u.properties"), File(dir, "dl"), appDir = null, fetch = { releases })
        updater.check()
        val end = System.currentTimeMillis() + 5_000
        while (updater.state.value !is UpdateState.Available && System.currentTimeMillis() < end) Thread.sleep(20)
        val offered = (updater.state.value as UpdateState.Available).release
        assertEquals("nitrate-desktop-0.0.0-dev.30.msi", offered.apkName)
        assertEquals(listOf("Windows app"), offered.notes)

        val local = DesktopUpdater(DesktopVersion("0.1.0", 1, UpdateChannel.NONE), File(dir, "l.properties"), File(dir, "dl2"), appDir = null, fetch = { error("never asked") })
        local.check()
        assertTrue(local.state.value is UpdateState.Failed)
    }
}

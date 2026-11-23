package dev.nitrate.desktop.mpv

import dev.nitrate.mobile.ui.MpvConfig
import dev.nitrate.mobile.ui.MpvMedia
import dev.nitrate.mobile.ui.VideoOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How Nitrate drives libmpv: options, loading with the account's header and tracks, and what comes back. */
class LibMpvPlayerTest {
    private fun config(output: VideoOutput = VideoOutput.SOFTWARE, spdif: String = "", hw: Boolean = true) =
        MpvConfig(output = output, hardwareDecoding = hw, spdif = spdif, volume = 90, userAgent = "Nitrate/test")

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `the software picture starts at once and leaves the mouse and keys to Nitrate`() {
        val api = FakeMpvApi()
        val player = LibMpvPlayer(api, config(), windowId = { 0 }, windows = true)
        assertTrue(api.initialized)
        assertEquals("libmpv", api.option("vo"))
        assertEquals("auto-copy-safe", api.option("hwdec"))
        assertEquals("no", api.option("input-default-bindings"))
        assertEquals("no", api.option("input-cursor"))
        assertEquals("yes", api.option("keep-open"))
        assertEquals("wasapi", api.option("ao"))
        assertEquals("90", api.option("volume"))
        assertNull(api.option("audio-spdif"), "bitstreaming is off unless chosen")
        assertTrue(listOf("time-pos", "duration", "pause", "eof-reached", "paused-for-cache").all { it in api.observed })
        player.release()
        assertTrue(api.destroyed)
    }

    @Test
    fun `the native window waits for its canvas and asks for HDR passthrough`() {
        val api = FakeMpvApi()
        val player = LibMpvPlayer(api, config(VideoOutput.WINDOW, spdif = "ac3,truehd", hw = false), windowId = { 0 }, windows = true)
        assertFalse(api.initialized, "wid is set before mpv starts, once the canvas has a window")
        assertEquals("gpu-next", api.option("vo"))
        assertEquals("d3d11", api.option("gpu-api"))
        assertEquals("yes", api.option("target-colorspace-hint"))
        assertEquals("no", api.option("hwdec"))
        assertEquals("ac3,truehd", api.option("audio-spdif"))
        assertEquals("yes", api.option("audio-exclusive"), "TrueHD needs WASAPI to itself")
        // A load before then waits.
        player.load(MpvMedia("http://server/v", emptyMap(), 0.0, null, 0))
        assertTrue(api.commands.none { it.first() == "loadfile" })
        player.release()
    }

    @Test
    fun `loading sends the Authorization header whole, the start and the tracks, then adds subtitle files`() {
        val api = FakeMpvApi()
        val player = LibMpvPlayer(api, config(), windowId = { 0 })
        val auth = "MediaBrowser Client=\"Nitrate\", Device=\"PC\", DeviceId=\"d\", Version=\"1\", Token=\"t\""
        player.load(MpvMedia("http://server/Videos/m1/stream?static=true", mapOf("Authorization" to auth), 12.5, audioId = 2, subtitleId = 3, subtitleUrls = listOf("http://server/sub.srt")))
        assertEquals(listOf("change-list", "http-header-fields", "clr", ""), api.commands[0])
        assertEquals(listOf("change-list", "http-header-fields", "append", "Authorization: $auth"), api.commands[1])
        assertEquals("12.500", api.property("start"))
        assertEquals("2", api.property("aid"))
        assertEquals("no", api.property("sid"), "the subtitle file isn't there yet")
        assertEquals(listOf("loadfile", "http://server/Videos/m1/stream?static=true", "replace"), api.commands.last())

        api.events.offer(MpvEvent.FileLoaded)
        until("the subtitle file") { api.commands.any { it.first() == "sub-add" } }
        assertEquals(listOf("sub-add", "http://server/sub.srt", "auto"), api.commands.first { it.first() == "sub-add" })
        until("the subtitle picked") { api.property("sid") == "3" }
        until("loaded") { player.state.value.loaded }
        player.release()
    }

    @Test
    fun `positions, pauses, the end and errors come back as state`() {
        val api = FakeMpvApi()
        val player = LibMpvPlayer(api, config(), windowId = { 0 })
        api.events.offer(MpvEvent.PropertyChange(1, "time-pos", 61.5))
        api.events.offer(MpvEvent.PropertyChange(2, "duration", 2700.0))
        api.events.offer(MpvEvent.PropertyChange(3, "pause", true))
        until("position") { player.state.value.positionSeconds == 61.5 && player.state.value.durationSeconds == 2700.0 && player.state.value.paused }
        api.events.offer(MpvEvent.PropertyChange(4, "eof-reached", true))
        until("the end") { player.state.value.ended }
        api.events.offer(MpvEvent.EndFile(MpvEvent.EndFile.ERROR, -13))
        until("the error") { player.state.value.error?.startsWith("loading failed") == true }
        // Seconds always with a dot, whatever the PC's language.
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            player.seek(90.25)
            assertEquals(listOf("seek", "90.250", "absolute"), api.commands.last())
            player.setAudioDelay(-125)
            assertEquals("-0.125", api.property("audio-delay"))
        } finally {
            java.util.Locale.setDefault(java.util.Locale.US)
        }
        player.release()
    }
}

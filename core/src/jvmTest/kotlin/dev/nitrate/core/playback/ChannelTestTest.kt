package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTestTest {
    private val clip71 = ChannelTest.CLIPS.first { it.layout == "7.1" }

    @Test
    fun `speakers take turns in layout order, one slot each`() {
        assertEquals(Speaker.FL, clip71.speakerAt(0))
        assertEquals(Speaker.FL, clip71.speakerAt(2_999))
        assertEquals(Speaker.FR, clip71.speakerAt(3_000))
        assertEquals(Speaker.LFE, clip71.speakerAt(10_500))
        assertEquals(Speaker.SR, clip71.speakerAt(clip71.durationMs - 1))
        assertNull(clip71.speakerAt(clip71.durationMs))
    }

    @Test
    fun `slot start is where a looped speaker begins`() {
        assertEquals(12_000L, clip71.slotStartMs(Speaker.BL))
        assertNull(ChannelTest.CLIPS.first { it.layout == "Stereo" }.slotStartMs(Speaker.FC))
    }

    @Test
    fun `bundled clips are unique walk-throughs matching their layout size`() {
        val sizes = mapOf("Stereo" to 2, "5.1" to 6, "7.1" to 8)
        for (clip in ChannelTest.CLIPS) {
            assertEquals(clip.uri, sizes[clip.layout], clip.speakers.size)
            assertTrue(clip.walk)
        }
        assertEquals(ChannelTest.CLIPS.size, ChannelTest.CLIPS.map { it.uri }.toSet().size)
    }

    private fun server(title: String, channels: Int? = null) = ChannelTest.fromServer("id", title, "http://x", channels, "TrueHD")

    @Test
    fun `server walk-through takes its layout from the title`() {
        val clip = server("Nitrate Test Walk 7.1 TrueHD", channels = 8)
        assertTrue(clip.walk)
        assertEquals("7.1", clip.layout)
        assertEquals(Speaker.BL, clip.speakerAt(12_000))
    }

    @Test
    fun `other server files play without the map following`() {
        val clip = server("Nitrate Test - Atmos demo 7.1")
        assertFalse(clip.walk)
        assertEquals("7.1", clip.layout)
        assertNull(clip.speakerAt(0))
        assertNull(clip.slotStartMs(Speaker.FL))
    }

    @Test
    fun `without a layout in the title the channel count decides, and walk needs a layout`() {
        assertEquals("5.1", server("Nitrate Test Walk DTS", channels = 6).layout)
        assertFalse(server("Nitrate Test Walk DTS", channels = 6).walk)
        assertEquals("Stereo", server("Nitrate Test stereo check").layout)
        assertEquals("3 ch", server("Nitrate Test odd", channels = 3).layout)
    }

    @Test
    fun `layout tokens are not matched inside other numbers`() {
        assertEquals("? ch", server("Nitrate Test 15.10 build").layout)
        assertEquals("Stereo", server("Nitrate Test walkthrough 2.0").layout)
        assertFalse(server("Nitrate Test walkthrough 2.0").walk)
    }
}

package dev.nitrate.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `every clip has one bundled asset and matches its layout size`() {
        val sizes = mapOf("Stereo" to 2, "5.1" to 6, "7.1" to 8)
        for (clip in ChannelTest.CLIPS) assertEquals(clip.asset, sizes[clip.layout], clip.speakers.size)
        assertEquals(ChannelTest.CLIPS.size, ChannelTest.CLIPS.map { it.asset }.toSet().size)
    }
}

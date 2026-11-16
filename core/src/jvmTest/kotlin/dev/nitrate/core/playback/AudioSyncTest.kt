package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioSyncTest {
    @Test fun `moving the clock moves the picture against the sound`() {
        // Sound later by 200 ms: the clock reads ahead, so the picture shows 200 ms earlier.
        assertEquals(10_200_000L, AudioSync.shiftedPositionUs(10_000_000L, 200_000L))
        // Sound earlier (Bluetooth lag): the clock reads behind, never below zero.
        assertEquals(9_850_000L, AudioSync.shiftedPositionUs(10_000_000L, -150_000L))
        assertEquals(0L, AudioSync.shiftedPositionUs(100_000L, -150_000L))
    }

    @Test fun `labels and limits`() {
        assertEquals("In sync", AudioSync.label(0))
        assertEquals("+0.150 s (sound later)", AudioSync.label(150))
        assertEquals("−1.025 s (sound earlier)", AudioSync.label(-1025))
        assertEquals(2_000, AudioSync.clamp(5_000))
        assertEquals(-2_000, AudioSync.clamp(-9_000))
    }
}

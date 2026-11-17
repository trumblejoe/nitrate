package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class SoundModeTest {
    @Test
    fun readsAndWritesTheTwoSwitches() {
        val s = PlaybackSettings()
        assertEquals(SoundMode.NORMAL, s.soundMode)
        assertEquals(SoundMode.CLEARER_DIALOGUE, s.copy(dialogueBoost = true).soundMode)
        // Night on its own (set by an older version) reads as Night.
        assertEquals(SoundMode.NIGHT, s.copy(nightMode = true).soundMode)
        assertEquals(SoundMode.NIGHT, s.copy(nightMode = true, dialogueBoost = true).soundMode)
        for (m in SoundMode.entries) assertEquals(m, s.withSoundMode(m).soundMode)
        val night = s.withSoundMode(SoundMode.NIGHT)
        assertEquals(true, night.nightMode && night.dialogueBoost)
        val normal = night.withSoundMode(SoundMode.NORMAL)
        assertEquals(false, normal.nightMode || normal.dialogueBoost)
    }
}

package dev.nitrate.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test fun `names come first`() {
        assertEquals("Subtitle size", SettingsSearch.find("subtitle size", tv = true).first().title)
        assertEquals("Audio sync", SettingsSearch.find("audio sync", tv = false).first().title)
    }

    @Test fun `other words find it too`() {
        assertTrue(SettingsSearch.find("night mode", tv = true).any { it.place == SettingsPlace.SOUND })
        assertTrue(SettingsSearch.find("christmas", tv = true).any { it.place == SettingsPlace.DISPLAY_EFFECTS })
        assertTrue(SettingsSearch.find("atmos", tv = true).any { it.title == "Lossless and object audio" })
        assertTrue(SettingsSearch.find("pip", tv = false).any { it.title == "Picture-in-picture" })
    }

    @Test fun `only what this app has`() {
        assertTrue(SettingsSearch.find("refresh rate", tv = false).none { it.on == SettingsOn.TV })
        assertTrue(SettingsSearch.find("new episodes", tv = true).isEmpty())
        assertTrue(SettingsSearch.find("library health", tv = true).isEmpty())
        assertEquals("Library health", SettingsSearch.find("library health", tv = true, admin = true).first().title)
    }

    @Test fun `every word typed must match`() {
        assertTrue(SettingsSearch.find("subtitle banana", tv = true).isEmpty())
        assertTrue(SettingsSearch.find("   ", tv = true).isEmpty())
    }
}

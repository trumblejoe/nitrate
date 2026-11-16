package dev.nitrate.core.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class EasyModeTest {
    @Test fun `easy mode keeps Home, Search, the libraries, Settings and switching user`() {
        val all = listOf(MenuStops.SEARCH, MenuStops.HOME, MenuStops.library("films"), MenuStops.page("mine"), MenuStops.CHANNELS, MenuStops.PAGES, MenuStops.TOGETHER, MenuStops.SETTINGS, MenuStops.PROFILES)
        assertEquals(listOf(MenuStops.SEARCH, MenuStops.HOME, MenuStops.library("films"), MenuStops.SETTINGS, MenuStops.PROFILES), all.filter(MenuStops::essential))
    }
}

package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals

class MenuStopsTest {
    private val defaults = MenuStops.defaults(listOf("m", "s"), listOf("p1"))
    private val available = defaults.toSet()

    @Test fun defaultsInOrder() {
        assertEquals(listOf("search", "home", "library:m", "library:s", "page:p1", "channels", "pages", "settings", "profiles"), defaults)
        assertEquals(defaults, MenuStops.arrange(null, available, defaults))
        // Without channels on this server the defaults close up (no gap on the dial).
        assertEquals(defaults - "channels", MenuStops.arrange(null, available - "channels", defaults))
    }

    @Test fun dropsMissingStopsAndKeepsSettings() {
        // A removed library leaves an empty spot (it matters on the dial); Settings comes back if left out.
        assertEquals(listOf("home", null, "search", "settings"), MenuStops.arrange(listOf("home", "library:gone", "search"), available, defaults))
        assertEquals(listOf("settings"), MenuStops.arrange(listOf("library:gone", null), available, defaults))
        // A stop the app no longer has (the old "franchises") is just left out of the rail.
        assertEquals(listOf("home", "search", "settings"), MenuStops.linear(MenuStops.arrange(listOf("home", "franchises", "search"), available, defaults)))
    }

    @Test fun placingMovesAndSwaps() {
        val slots = listOf("search", "home", "settings")
        // Settings to spot 0: Search takes Settings' old spot.
        assertEquals(listOf("settings", "home", "search"), MenuStops.place(slots, 0, "settings"))
        // A new stop past the end: empty spots in between (the dial's outer ring).
        assertEquals(listOf("search", "home", "settings", null, null, "pages"), MenuStops.place(slots, 5, "pages"))
        // Moving the last stop inward trims the empty tail.
        assertEquals(listOf("search", "home", "pages"), MenuStops.place(listOf("search", "home", null, null, null, "pages"), 2, "pages"))
        assertEquals(listOf("search", null, "settings"), MenuStops.remove(slots, 1))
        assertEquals(listOf("search", "settings"), MenuStops.linear(MenuStops.remove(slots, 1)))
    }

    @Test fun cornerStopsLeaveOverlayAndRailOnly() {
        val ids = listOf("search", "home", "library:m", "more", "settings", "profiles")
        assertEquals(listOf("home", "library:m", "more"), MenuStops.shownIn(ids, TvMenuStyle.OVERLAY))
        assertEquals(listOf("home", "library:m", "more"), MenuStops.shownIn(ids, TvMenuStyle.LEFT))
        assertEquals(listOf("home", "library:m", "more"), MenuStops.shownIn(ids, TvMenuStyle.RIGHT))
        // The bar and the dial keep them.
        assertEquals(ids, MenuStops.shownIn(ids, TvMenuStyle.TOP))
        assertEquals(ids, MenuStops.shownIn(ids, TvMenuStyle.RADIAL))
    }

    @Test fun movingStepsOverStopsNotShown() {
        val arranged = listOf("search", "home", "library:m", "settings", "library:s")
        // Down from Movies: past the unshown Settings, so it really moves on screen.
        assertEquals(
            listOf("search", "home", "settings", "library:s", "library:m"),
            MenuLayout.move(arranged, emptyList(), emptyList(), "library:m", 1, MenuStops.IN_CORNER),
        )
        // Without skipping, it only swaps with Settings.
        assertEquals(
            listOf("search", "home", "settings", "library:m", "library:s"),
            MenuLayout.move(arranged, emptyList(), emptyList(), "library:m", 1),
        )
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    @Test fun overlayIsTheDefaultButSavedSettingsKeepTheRail() {
        assertEquals(TvMenuStyle.OVERLAY, DisplayPrefs().tvMenu)
        // Saved before the overlay: no tvMenu meant the left rail.
        assertEquals(TvMenuStyle.LEFT, DisplayPrefs.decode(json, """{"tvClock":true}""").tvMenu)
        assertEquals(TvMenuStyle.TOP, DisplayPrefs.decode(json, """{"tvMenu":"TOP"}""").tvMenu)
        // From now on the choice is always written, the default too, so it reads back as itself.
        val saved = json.encodeToString(DisplayPrefs.serializer(), DisplayPrefs(tvClock = true))
        assertEquals(true, "\"tvMenu\":\"OVERLAY\"" in saved, saved)
        assertEquals(TvMenuStyle.OVERLAY, DisplayPrefs.decode(json, saved).tvMenu)
    }
}

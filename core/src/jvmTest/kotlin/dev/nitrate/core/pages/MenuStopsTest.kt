package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals

class MenuStopsTest {
    private val defaults = MenuStops.defaults(listOf("m", "s"), listOf("p1"))
    private val available = defaults.toSet()

    @Test fun defaultsInOrder() {
        assertEquals(listOf("search", "home", "library:m", "library:s", "page:p1", "pages", "together", "settings", "profiles"), defaults)
        assertEquals(defaults, MenuStops.arrange(null, available, defaults))
    }

    @Test fun dropsMissingStopsAndKeepsSettings() {
        // A removed library leaves an empty spot (it matters on the dial); Settings comes back if left out.
        assertEquals(listOf("home", null, "search", "settings"), MenuStops.arrange(listOf("home", "library:gone", "search"), available, defaults))
        assertEquals(listOf("settings"), MenuStops.arrange(listOf("library:gone", null), available, defaults))
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
}

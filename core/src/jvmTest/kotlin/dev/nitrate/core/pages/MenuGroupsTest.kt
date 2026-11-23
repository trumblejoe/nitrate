package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuGroupsTest {
    private val libs = linkedMapOf(
        "library:a1" to "Anime Movies", "library:a2" to "Anime TV Shows",
        "library:k1" to "Kids Movies", "library:k2" to "Kids TV Shows",
        "library:m" to "Movies", "library:t" to "TV Shows", "library:c" to "Collections",
    )

    @Test fun `shared leading words make a suggestion`() {
        val s = MenuGroups.suggest(libs, emptyList(), emptySet())
        assertEquals(listOf("Anime", "Kids"), s.map { it.name })
        assertEquals(listOf("library:a1", "library:a2"), s.first().stops)
    }

    @Test fun `longest shared start, and each must have something left`() {
        val s = MenuGroups.suggest(mapOf("library:1" to "Kids Classic Films", "library:2" to "Kids Classic Cartoons", "library:3" to "Kids"), emptyList(), emptySet())
        // "Kids" alone has nothing after it, so it can't join; the other two share "Kids Classic".
        assertEquals(emptyList(), s)
        val t = MenuGroups.suggest(mapOf("library:1" to "Kids Classic Films", "library:2" to "Kids Classic Cartoons"), emptyList(), emptySet())
        assertEquals(listOf("Kids Classic"), t.map { it.name })
    }

    @Test fun `made or dismissed suggestions aren't offered again`() {
        val made = MenuGroups.make(DisplayPrefs(), "Anime", listOf("library:a1", "library:a2"))
        assertEquals(listOf("Kids"), MenuGroups.suggest(libs, made.tvMenuGroups, emptySet()).map { it.name })
        val dismissed = setOf(MenuGroups.suggestionKey(listOf("library:k2", "library:k1")))
        assertEquals(emptyList(), MenuGroups.suggest(libs, made.tvMenuGroups, dismissed))
    }

    @Test fun `members drop the group's words`() {
        val g = MenuGroup("anime", "Anime", emptyList())
        assertEquals("Movies", MenuGroups.memberLabel(g, "Anime Movies"))
        assertEquals("Movies", MenuGroups.memberLabel(g, "Movies"))
        assertEquals("TV Shows", MenuGroups.memberLabel(g, "anime TV Shows"))
    }

    @Test fun `the menu shows groups in place of their members, More before Settings`() {
        val arranged = listOf("search", "home", "library:a1", "library:a2", "library:c", "library:k1", "library:k2", "library:m", "channels", "settings", "profiles")
        val groups = listOf(MenuGroup("anime", "Anime", listOf("library:a1", "library:a2")), MenuGroup("kids", "Kids", listOf("library:k1", "library:k2")))
        val more = MenuLayout.more(null, MenuStops.defaultMore(arranged, { it == "c" }), arranged.toSet(), groups)
        assertEquals(listOf("library:c", "channels", "profiles"), more)
        val main = MenuLayout.main(arranged, groups, more)
        assertEquals(listOf("search", "home", "group:anime", "group:kids", "library:m", "more", "settings"), main)
        assertEquals(StopPlace.MORE, MenuLayout.placeOf("channels", main, more))
        assertEquals(StopPlace.HIDDEN, MenuLayout.placeOf("library:t", main, more))
    }

    @Test fun `Search, Home and Settings never go into More, and nothing in More means no More stop`() {
        val arranged = listOf("search", "home", "library:m", "settings")
        val more = MenuLayout.more(listOf("search", "settings", "home"), emptyList(), arranged.toSet(), emptyList())
        assertEquals(emptyList(), more)
        assertEquals(arranged, MenuLayout.main(arranged, emptyList(), more))
    }

    @Test fun `splitting a group brings its members back`() {
        val p = MenuGroups.make(DisplayPrefs(tvMoreStops = listOf("library:a1", "channels")), "Anime", listOf("library:a1", "library:a2"))
        assertEquals(listOf("channels"), p.tvMoreStops)
        val split = MenuGroups.split(p, p.tvMenuGroups.single().id)
        assertTrue(split.tvMenuGroups.isEmpty())
        val main = MenuLayout.main(listOf("home", "library:a1", "library:a2", "settings"), split.tvMenuGroups, emptyList())
        assertEquals(listOf("home", "library:a1", "library:a2", "settings"), main)
    }

    @Test fun `groups move as one, and places change`() {
        val arranged = listOf("search", "home", "library:a1", "library:a2", "library:m", "channels", "settings")
        val groups = listOf(MenuGroup("anime", "Anime", listOf("library:a1", "library:a2")))
        val down = MenuLayout.move(arranged, groups, emptyList(), "group:anime", +1)
        assertEquals(listOf("search", "home", "library:m", "group:anime", "channels", "settings"), MenuLayout.main(down, groups, emptyList()))
        val up = MenuLayout.move(down, groups, emptyList(), "channels", -1)
        assertEquals(listOf("search", "home", "library:m", "channels", "group:anime", "settings"), MenuLayout.main(up, groups, emptyList()))

        val p = DisplayPrefs(tvMenuGroups = groups)
        val toMore = MenuLayout.setPlace(p, "channels", StopPlace.MORE, arranged, emptyList())
        assertEquals(listOf("channels"), toMore.tvMoreStops)
        val hidden = MenuLayout.setPlace(toMore, "group:anime", StopPlace.HIDDEN, arranged, listOf("channels"))
        assertEquals(listOf("search", "home", "library:m", "more", "settings"), MenuLayout.main(hidden.tvMenuStops!!, groups, hidden.tvMoreStops!!))
        val back = MenuLayout.setPlace(hidden, "group:anime", StopPlace.MENU, hidden.tvMenuStops!!, hidden.tvMoreStops!!)
        assertTrue("group:anime" in MenuLayout.main(back.tvMenuStops!!, groups, back.tvMoreStops!!))
        // Settings and Home never leave the menu.
        assertEquals(p, MenuLayout.setPlace(p, "settings", StopPlace.HIDDEN, arranged, emptyList()))
    }

    @Test fun `a new group stands where its first member was`() {
        val arranged = listOf("home", "library:m", "library:a1", "library:a2", "settings")
        val p = MenuGroups.make(DisplayPrefs(), "Anime", listOf("library:a1", "library:a2"), arranged)
        assertEquals(listOf("home", "library:m", "group:anime", "settings"), MenuLayout.main(p.tvMenuStops!!, p.tvMenuGroups, emptyList()))
    }

    @Test fun `old saved settings still read`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(DisplayPrefs.serializer(), """{"tvMenuStops":["home","settings"],"tvMenu":"LEFT"}""")
        assertEquals(listOf("home", "settings"), old.tvMenuStops)
        assertTrue(old.tvMenuGroups.isEmpty())
        assertEquals(null, old.tvMoreStops)
        val round = json.decodeFromString(DisplayPrefs.serializer(), json.encodeToString(DisplayPrefs.serializer(), MenuGroups.make(old, "Anime", listOf("library:a1"))))
        assertEquals("Anime", round.tvMenuGroups.single().name)
    }
}

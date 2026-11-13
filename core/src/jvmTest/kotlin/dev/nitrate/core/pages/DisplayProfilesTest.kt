package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayProfilesTest {
    private fun list(vararg p: Pair<String, String>) = DisplayProfiles.profilesJson(p.map { DisplayProfile(it.first, it.second) })

    @Test
    fun sharedAndProfileNames() {
        assertEquals("pages", DisplayProfiles.scoped("a", "pages"))
        assertEquals("tracks", DisplayProfiles.scoped("a", "tracks"))
        assertEquals("layout:custom/x", DisplayProfiles.scoped("a", "layout:custom/x"))
        assertEquals("p:a:layout:home", DisplayProfiles.scoped("a", "layout:home"))
        assertEquals("p:a:rows", DisplayProfiles.scoped("a", "rows"))
        assertEquals("p:a:view:browse/lib", DisplayProfiles.scoped("a", "view:browse/lib"))
    }

    @Test
    fun oldSettingsBecomeMySettings() {
        val old = mapOf("rows" to "R", "prefs" to "P", "pages" to "G", "tracks" to "T", "layout:home" to "H", "layout:custom/x" to "C")
        val m = DisplayProfiles.migrate(old) { """{"hideSpoilers":true}""" }
        assertEquals(
            mapOf(
                "p:migrated:rows" to "R", "p:migrated:prefs" to "P", "p:migrated:layout:home" to "H",
                "pages" to "G", "tracks" to "T", "layout:custom/x" to "C",
                "shared" to """{"hideSpoilers":true}""",
                "profiles" to list("migrated" to "My settings"),
            ),
            m,
        )
        assertFalse(DisplayProfiles.isLegacy(m))
        assertEquals(m, DisplayProfiles.migrate(m) { null })
    }

    @Test
    fun copyAndRemoveAProfile() {
        val e = mapOf("p:a:rows" to "R", "p:a:prefs" to "P", "p:b:rows" to "X", "pages" to "G")
        assertEquals(mapOf("p:c:rows" to "R", "p:c:prefs" to "P"), DisplayProfiles.copy(e, "a", "c"))
        assertEquals(mapOf("p:b:rows" to "X", "pages" to "G"), DisplayProfiles.without(e, "a"))
        assertTrue(DisplayProfiles.copy(e, "tv", "c").isEmpty())
    }

    @Test
    fun mergeKeepsBothDevicesChanges() {
        val base = mapOf("p:tv1:rows" to "1", "p:ph:rows" to "1", "tracks" to "t")
        val local = mapOf("p:tv1:rows" to "2", "p:ph:rows" to "1", "tracks" to "t") // the TV changed its rows
        val remote = mapOf("p:tv1:rows" to "1", "p:ph:rows" to "9", "tracks" to "t2") // the phone changed its own
        assertEquals(mapOf("p:tv1:rows" to "2", "p:ph:rows" to "9", "tracks" to "t2"), DisplayProfiles.merge(base, local, remote))
    }

    @Test
    fun mergeRemovals() {
        val base = mapOf("a" to "1", "b" to "1")
        assertEquals(mapOf("b" to "1"), DisplayProfiles.merge(base, mapOf("b" to "1"), base)) // removed here
        assertEquals(mapOf("a" to "1"), DisplayProfiles.merge(base, base, mapOf("a" to "1"))) // removed there
    }

    @Test
    fun mergeProfileLists() {
        val base = mapOf("profiles" to list("a" to "Living room", "b" to "Phone"))
        val local = mapOf("profiles" to list("a" to "Lounge", "b" to "Phone", "c" to "Tablet")) // renamed a, added c
        val remote = mapOf("profiles" to list("a" to "Living room", "d" to "Bedroom")) // removed b, added d
        val merged = DisplayProfiles.profilesOf(DisplayProfiles.merge(base, local, remote))
        assertEquals(listOf("a" to "Lounge", "d" to "Bedroom", "c" to "Tablet"), merged.map { it.id to it.name })
    }
}
